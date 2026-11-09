//! ServoIpcBackend — servoshell-as-engine over WebDriver IPC (VERDICT.md
//! Option 2, proven path).
//!
//! * One `servoshell` child process per profile (v1: one profile, "default").
//!   Binary resolved from `$SUB_SERVOSHELL`, then `resources/servoshell`
//!   beside the app, then the dev path.
//! * Launched with `--config-dir=<dirs::config_dir()>/sub-browser/profile/<p>`
//!   so cookies/history persist in the engine profile, and
//!   `--webdriver=127.0.0.1:<random-port>` (loopback only; the port is grabbed
//!   via an ephemeral listener and released before spawn).
//! * Tabs map tab_id -> WebDriver window handle (`window/new` type:"tab").
//!   Servo's WebDriver server applies nav/title/screenshot to the *focused*
//!   context, so `focus()` (switch) must precede per-tab reads; the shell
//!   keeps exactly one focused tab, which matches the UI.
//! * Screenshots are the tile/presentation fallback (MJPEG-style polling in
//!   the chrome). Real embedding arrives with the `embed` feature.

use std::collections::HashMap;
use std::net::TcpListener;
use std::path::PathBuf;
use std::process::{Child, Command, Stdio};
use std::sync::mpsc::{channel, Receiver, Sender};
use std::sync::{Mutex, RwLock};
use std::time::{Duration, Instant};

use serde_json::Value;

use super::wd::{WdClient, WdError};
use super::{BlockedEvent, EngineBackend, EngineError, EngineResult};

/// Embedded ad-host blocklist (539 registrable domains, one per line).
const ADBLOCK_LIST: &str = include_str!("../../adblock_domains.txt");

/// The injected content script. After each navigation the shell runs this via
/// `execute/sync`: it scans every img/iframe/script/embed/source/a[href] src
/// (plus `data-src`), removes elements whose host suffix-matches the list,
/// then collapses visible fixed/absolute ad-ish containers by class/id
/// heuristics, and returns a JSON array of ["host","kind"] removals.
/// `__SUBLIST__` is replaced with the JSON-embedded domain array.
pub(crate) const ADBLOCK_SCRIPT: &str = r#"
var list = __SUBLIST__;
var set = {}; for (var i=0;i<list.length;i++) set[list[i]]=1;
function hostOf(u){ try{ if(!u) return ''; if(/^[a-z]+:\/\//i.test(u)) u=u.replace(/^[a-z]+:\/\//i,''); u=u.split(/[\/?#]/)[0]; return u.toLowerCase().replace(/^www\./,''); }catch(e){ return ''; } }
function blocked(h){ var s=h; for(;;){ if(set[s]) return true; var j=s.indexOf('.'); if(j<0) return false; s=s.substring(j+1);} }
var removed=[];
var sel='img,iframe,script,embed,source,frame,[background]';
var nodes=document.querySelectorAll(sel);
for(var i=0;i<nodes.length;i++){
  var el=nodes[i];
  var srcs=[el.getAttribute('src')||'',el.getAttribute('data-src')||'',el.getAttribute('data-iframe-src')||'',el.getAttribute('background')||''];
  for(var k=0;k<srcs.length;k++){
    var h=hostOf(srcs[k]);
    if(h && blocked(h)){ removed.push([h, el.tagName.toLowerCase()]); el.parentNode && el.parentNode.removeChild(el); break; }
  }
}
var links=document.querySelectorAll('a[href]');
for(var i=0;i<links.length;i++){
  var h=hostOf(links[i].getAttribute('href'));
  if(h && blocked(h) && /^(ad|adv|banner|sponsor)/i.test(links[i].className||links[i].id||'')){
    removed.push([h,'link']); links[i].parentNode && links[i].parentNode.removeChild(links[i]);
  }
}
var cand=document.querySelectorAll('[class],[id]');
var re=/^($|[-_])(ad|ads|advert|banner|sponsor|doubleclick|pop)([-_]|$)/i;
for(var i=0;i<cand.length;i++){
  var el=cand[i]; var cs=(el.className&&el.className.baseVal!==undefined?el.className.baseVal:el.className)||''; if(typeof cs!=='string') cs='';
  var key=cs+' '+el.id;
  if(re.test(cs)||re.test(el.id||'')){
    var r=el.getBoundingClientRect();
    if(r.width>0&&r.height>0){ removed.push([el.id||cs,'container']); el.parentNode&&el.parentNode.removeChild(el); }
  }
}
return JSON.stringify(removed);
"#;

pub struct ServoIpcBackend {
    inner: RwLock<Inner>,
    /// Children are kept so they can be reaped on drop; the supervisor keeps
    /// them alive for the process lifetime otherwise.
    child: Mutex<Option<Child>>,
    blocked_txs: Mutex<Vec<Sender<BlockedEvent>>>,
    client: WdClient,
    session: Mutex<Option<String>>,
    tab_handles: RwLock<HashMap<String, String>>,
    active: Mutex<Option<String>>,
    /// Viewport the shell last requested via `set_viewport` (window resize);
    /// new tabs are brought up at this size so every tab matches the window.
    viewport: RwLock<Option<(u32, u32)>>,
    adblock_enabled: std::sync::atomic::AtomicBool,
}

struct Inner {
    _profile_dir: PathBuf,
    port: u16,
}

impl ServoIpcBackend {
    /// Spawn + wait for the WebDriver endpoint to report ready.
    pub fn spawn(profile: &str) -> Result<Self, EngineError> {
        let base = dirs::config_dir()
            .ok_or_else(|| EngineError::Transport("no config dir".into()))?
            .join("sub-browser")
            .join("profile")
            .join(profile);
        std::fs::create_dir_all(&base).map_err(|e| EngineError::Transport(e.to_string()))?;

        let bin = Self::resolve_binary()?;

        // Grab a free loopback port, then release it before spawn so
        // servoshell can bind it.
        let port = {
            let l = TcpListener::bind("127.0.0.1:0").map_err(|e| EngineError::Transport(e.to_string()))?;
            let p = l.local_addr().map_err(|e| EngineError::Transport(e.to_string()))?.port();
            drop(l);
            p
        };

        let null = Stdio::null();
        let child = Command::new(&bin)
            .arg("--headless")
            // NOTE: servoshell 0.8.0 rejects `--webdriver=127.0.0.1:PORT`
            // ("is not expected in this context"); a bare port is required.
            .arg(format!("--webdriver={port}"))
            .arg(format!("--config-dir={}", base.display()))
            .stdin(null)
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .spawn()
            .map_err(|e| EngineError::Transport(format!("spawn {bin:?}: {e}")))?;

        let client = WdClient::new(format!("127.0.0.1:{port}"));
        // Wait for readiness (engine cold start on 2 vCPU SWGL: a few seconds).
        let t0 = Instant::now();
        loop {
            if client.status_ready() {
                break;
            }
            if t0.elapsed() > Duration::from_secs(60) {
                return Err(EngineError::Transport("servoshell webdriver never became ready".into()));
            }
            std::thread::sleep(Duration::from_millis(250));
        }

        Ok(Self {
            inner: RwLock::new(Inner { _profile_dir: base, port }),
            child: Mutex::new(Some(child)),
            blocked_txs: Mutex::new(Vec::new()),
            client,
            session: Mutex::new(None),
            tab_handles: RwLock::new(HashMap::new()),
            active: Mutex::new(None),
            viewport: RwLock::new(None),
            adblock_enabled: std::sync::atomic::AtomicBool::new(true),
        })
    }

    fn resolve_binary() -> EngineResult<PathBuf> {
        if let Ok(p) = std::env::var("SUB_SERVOSHELL") {
            let p = PathBuf::from(p);
            if p.exists() {
                return Ok(p);
            }
        }
        if let Ok(exe) = std::env::current_exe() {
            let cand = exe.parent().unwrap_or(std::path::Path::new(".")).join("servoshell");
            if cand.exists() {
                return Ok(cand);
            }
            let cand = exe.parent().unwrap_or(std::path::Path::new(".")).join("engine").join("servoshell");
            if cand.exists() {
                return Ok(cand);
            }
        }
        let dev = PathBuf::from("/opt/gecko-desktop/servoshell/servo/servoshell");
        if dev.exists() {
            return Ok(dev);
        }
        Err(EngineError::Transport("servoshell binary not found (set SUB_SERVOSHELL)".into()))
    }

    /// Servoshell supports one session; create lazily, recreate if lost.
    fn session(&self) -> EngineResult<String> {
        {
            let g = self.session.lock().unwrap();
            if let Some(s) = g.as_ref() {
                return Ok(s.clone());
            }
        }
        let sid = self
            .client
            .new_session()
            .map_err(|e| EngineError::Transport(format!("{e:?}")))?;
        *self.session.lock().unwrap() = Some(sid.clone());
        Ok(sid)
    }

    fn drop_session(&self) {
        *self.session.lock().unwrap() = None;
    }

    fn handle(&self, tab_id: &str) -> EngineResult<String> {
        self.tab_handles
            .read()
            .unwrap()
            .get(tab_id)
            .cloned()
            .ok_or_else(|| EngineError::NoSuchTab(tab_id.into()))
    }

    /// Focus the tab's context so subsequent per-tab commands act on it.
    fn ensure_focused(&self, tab_id: &str) -> EngineResult<String> {
        let sid = self.session()?;
        let h = self.handle(tab_id)?;
        if self.active.lock().unwrap().as_deref() != Some(h.as_str()) {
            self.client
                .switch_window(&sid, &h)
                .map_err(|e| EngineError::Transport(format!("{e:?}")))?;
            *self.active.lock().unwrap() = Some(h.clone());
            // Give the engine a beat to make the context current.
            std::thread::sleep(Duration::from_millis(60));
        }
        Ok(sid)
    }

    fn map_err(e: WdError) -> EngineError {
        match e {
            WdError::Io(s) => EngineError::Transport(s),
            WdError::Response(s) => EngineError::Engine(s),
        }
    }

    pub(crate) fn adblock_domains_json() -> String {
        let domains: Vec<&str> = ADBLOCK_LIST
            .lines()
            .map(|l| l.trim().trim_start_matches('.'))
            .filter(|l| !l.is_empty() && !l.starts_with('!') && l.contains('.'))
            .collect();
        Value::from(domains.iter().map(|d| Value::from(*d)).collect::<Vec<Value>>()).to_string()
    }

    fn publish_blocked(&self, tab_id: &str, removals: Vec<(String, String)>) {
        if removals.is_empty() {
            return;
        }
        let mut txs = self.blocked_txs.lock().unwrap();
        txs.retain(|tx| {
            let mut alive = true;
            for (host, kind) in &removals {
                // Receiver may be dropped: first failure marks the sender dead.
                if tx
                    .send(BlockedEvent { tab_id: tab_id.to_string(), host: host.clone(), kind: kind.clone() })
                    .is_err()
                {
                    alive = false;
                    break;
                }
            }
            alive
        });
    }

    /// Toggle content-script filtering (persisted by the shell's settings store).
    pub fn set_adblock_enabled(&self, on: bool) {
        self.adblock_enabled.store(on, std::sync::atomic::Ordering::Relaxed);
    }
}

impl EngineBackend for ServoIpcBackend {
    fn create_view(&self, tab_id: &str, url: &str) -> EngineResult<()> {
        let sid = self.session()?;
        let handle = self.client.new_window(&sid, "tab").map_err(Self::map_err)?;
        self.client.switch_window(&sid, &handle).map_err(Self::map_err)?;
        *self.active.lock().unwrap() = Some(handle.clone());
        self.tab_handles.write().unwrap().insert(tab_id.to_string(), handle);
        // Match the window's current viewport: headless servoshell tabs start
        // at a fixed 1024x740 and would letterbox/stretch into a resized
        // window otherwise (v2.0.1 stretched-display fix).
        if let Some((w, h)) = self.requested_viewport() {
            let _ = self.client.set_window_rect(&sid, w, h);
        }
        self.client.navigate(&sid, url).map_err(Self::map_err)?;
        Ok(())
    }

    fn navigate(&self, tab_id: &str, url: &str) -> EngineResult<()> {
        let sid = self.ensure_focused(tab_id)?;
        self.client.navigate(&sid, url).map_err(Self::map_err)
    }

    fn back(&self, tab_id: &str) -> EngineResult<()> {
        let sid = self.ensure_focused(tab_id)?;
        self.client.back(&sid).map_err(Self::map_err)
    }

    fn forward(&self, tab_id: &str) -> EngineResult<()> {
        let sid = self.ensure_focused(tab_id)?;
        self.client.forward(&sid).map_err(Self::map_err)
    }

    fn reload(&self, tab_id: &str) -> EngineResult<()> {
        let sid = self.ensure_focused(tab_id)?;
        self.client.refresh(&sid).map_err(Self::map_err)
    }

    fn url(&self, tab_id: &str) -> EngineResult<String> {
        let sid = self.ensure_focused(tab_id)?;
        self.client.get_url(&sid).map_err(Self::map_err)
    }

    fn title(&self, tab_id: &str) -> EngineResult<String> {
        let sid = self.ensure_focused(tab_id)?;
        self.client.get_title(&sid).map_err(Self::map_err)
    }

    fn screenshot(&self, tab_id: &str) -> EngineResult<Vec<u8>> {
        let sid = self.ensure_focused(tab_id)?;
        let b64 = self.client.screenshot(&sid).map_err(Self::map_err)?;
        Ok(crate::b64::decode(&b64))
    }

    fn viewport(&self, tab_id: &str) -> EngineResult<(u32, u32)> {
        let sid = self.ensure_focused(tab_id)?;
        self.client.window_rect(&sid).map_err(Self::map_err)
    }

    fn set_viewport(&self, w: u32, h: u32) -> EngineResult<()> {
        let w = w.max(64);
        let h = h.max(64);
        *self.viewport.write().unwrap() = Some((w, h));
        // Servo's WebDriver applies window/rect to the focused context only,
        // so sweep every open tab; failures are best-effort (a closed or
        // transiently-unsupported handle must not break the resize).
        let sid = self.session()?;
        let handles: Vec<String> = self.tab_handles.read().unwrap().values().cloned().collect();
        let mut last_err = None;
        for h_ in handles {
            if let Err(e) = self.client.switch_window(&sid, &h_).and_then(|_| self.client.set_window_rect(&sid, w, h)) {
                last_err = Some(e);
            }
        }
        match last_err {
            Some(e) => Err(Self::map_err(e)),
            None => Ok(()),
        }
    }

    fn requested_viewport(&self) -> Option<(u32, u32)> {
        *self.viewport.read().unwrap()
    }

    fn focus(&self, tab_id: &str) -> EngineResult<()> {
        self.ensure_focused(tab_id).map(|_| ())
    }

    fn close_view(&self, tab_id: &str) -> EngineResult<()> {
        let sid = self.session()?;
        if let Some(h) = self.tab_handles.write().unwrap().remove(tab_id) {
            let _ = self.client.close_window(&sid, &h);
            *self.active.lock().unwrap() = None;
        }
        Ok(())
    }

    fn inject_adblock(&self, tab_id: &str) -> EngineResult<Vec<(String, String)>> {
        if !self.adblock_enabled.load(std::sync::atomic::Ordering::Relaxed) {
            return Ok(Vec::new());
        }
        let sid = self.ensure_focused(tab_id)?;
        let script = ADBLOCK_SCRIPT.replace("__SUBLIST__", &Self::adblock_domains_json());
        let v = self.client.execute_sync(&sid, &script, vec![]).map_err(Self::map_err)?;
        let json_str = v.as_str().unwrap_or("[]");
        let removals: Vec<(String, String)> = serde_json::from_str::<Vec<[String; 2]>>(json_str)
            .unwrap_or_default()
            .into_iter()
            .map(|[h, k]| (h, k))
            .collect();
        self.publish_blocked(tab_id, removals.clone());
        Ok(removals)
    }

    fn click(&self, tab_id: &str, x: f64, y: f64) -> EngineResult<()> {
        let sid = self.ensure_focused(tab_id)?;
        let (x, y) = (x.max(0.0) as i64, y.max(0.0) as i64);
        self.client
            .pointer_actions(
                &sid,
                vec![
                    serde_json::json!({"type":"pointerMove","duration":0,"x":x,"y":y}),
                    serde_json::json!({"type":"pointerDown","button":0}),
                    serde_json::json!({"type":"pause","duration":10}),
                    serde_json::json!({"type":"pointerUp","button":0}),
                ],
            )
            .map_err(Self::map_err)
    }

    fn key(&self, tab_id: &str, key: &str) -> EngineResult<()> {
        let sid = self.ensure_focused(tab_id)?;
        let mut actions = vec![serde_json::json!({"type":"pause","duration":10})];
        for ch in key.chars() {
            let s = ch.to_string();
            actions.push(serde_json::json!({"type":"keyDown","value":s}));
            actions.push(serde_json::json!({"type":"pause","duration":10}));
            actions.push(serde_json::json!({"type":"keyUp","value":s}));
        }
        self.client.key_actions(&sid, actions).map_err(Self::map_err)
    }

    fn scroll(&self, tab_id: &str, dx: f64, dy: f64) -> EngineResult<()> {
        // Servo's WebDriver has no wheel input source yet; scrollBy via
        // execute script is the faithful equivalent for the tile stream.
        let sid = self.ensure_focused(tab_id)?;
        let script = format!("window.scrollBy({dx}, {dy}); return [window.scrollX, window.scrollY];");
        self.client.execute_sync(&sid, &script, vec![]).map_err(Self::map_err)?;
        Ok(())
    }

    fn blocked_event_stream(&self) -> Receiver<BlockedEvent> {
        let (tx, rx) = channel();
        self.blocked_txs.lock().unwrap().push(tx);
        rx
    }

    fn set_adblock_enabled(&self, on: bool) {
        self.adblock_enabled.store(on, std::sync::atomic::Ordering::Relaxed);
    }

    fn describe(&self) -> String {
        let port = self.inner.read().unwrap().port;
        format!("servo-ipc (servoshell 0.8.0, webdriver 127.0.0.1:{port})")
    }
}

impl Drop for ServoIpcBackend {
    fn drop(&mut self) {
        if let Some(sid) = self.session.lock().unwrap().take() {
            self.client.delete_session(&sid);
        }
        if let Some(mut c) = self.child.lock().unwrap().take() {
            let _ = c.kill();
            let _ = c.wait();
        }
    }
}
