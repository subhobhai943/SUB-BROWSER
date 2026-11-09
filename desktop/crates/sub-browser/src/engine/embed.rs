//! `embed` feature — in-process `servo` crate backend (VERDICT.md Option 1).
//!
//! Architecture (verified against the real servo 0.8.0 crates.io API; the
//! crate's `examples/winit_minimal.rs` + `webview.rs` are the ground truth
//! for signatures — the earlier spike E0599 came from inventing methods like
//! `Servo::create()`/`handle_wakeup()` that don't exist; the real surface is
//! `ServoBuilder::default().event_loop_waker(..).build()`,
//! `Servo::spin_event_loop()`, and `WebView::{load, reload, go_back(n),
//! go_forward(n), paint, resize, notify_input_event, evaluate_javascript,
//! page_title, url, load_status}`).
//!
//! * Servo objects are `Rc`/`RefCell`-based and single-threaded, so
//!   [`EmbedBackend`] owns a dedicated **embed thread** that builds `Servo`
//!   over a `SoftwareRenderingContext` (surfman software adapter = SWGL;
//!   no GPU, no display server needed) and owns one `WebView` per tab. Trait
//!   methods marshal commands over mpsc and block on a reply channel, so the
//!   public surface stays `Send + Sync`. `EventLoopWaker` is wired to the
//!   channel so Servo wakes the pump instead of spinning blind.
//! * Frames: `WebViewDelegate::notify_new_frame_ready` flags dirty; the loop
//!   paints the active tab into the software context. `screenshot()` paints,
//!   reads the back buffer via `RenderingContext::read_to_image` (the "native
//!   surface" — direct pixels, no WebDriver PNG round-trip), and PNG-encodes
//!   it once for trait compatibility.
//! * Input: `WebView::notify_input_event` (mouse down/up, wheel, keyboard)
//!   — no WebDriver anywhere in this backend.
//! * JS eval IS in the 0.8.0 embedder API (`WebView::evaluate_javascript`
//!   → `JSValue`), so adblock reuses the *same* DOM-filter script as the IPC
//!   backend over one channel hop. Honest caveat: it remains a post-load DOM
//!   filter (ads were already fetched), identical semantics to IPC; the
//!   request-level `NetworkDelegate` idea never shipped as public API in
//!   0.8.0. Embed's win here is zero WebDriver round-trips, not stronger
//!   blocking.
//! * 0.8.0 exposes no public `WebView::deinit`; `close_view` drops the
//!   thread's handle and Servo retires the pipeline internally.

#[cfg(not(feature = "embed"))]
use super::EngineError;

#[cfg(not(feature = "embed"))]
pub struct EmbedBackend;

#[cfg(not(feature = "embed"))]
impl EmbedBackend {
    #[allow(dead_code)]
    pub fn spawn(_profile: &str) -> Result<Self, EngineError> {
        Err(EngineError::Unsupported(
            "in-process servo embed not built — compile with `--features embed`".into(),
        ))
    }
}

#[cfg(feature = "embed")]
mod imp {
    use std::collections::HashMap;
    use std::path::PathBuf;
    use std::rc::Rc;
    use std::sync::atomic::{AtomicBool, Ordering};
    use std::sync::mpsc::{channel, Receiver, RecvTimeoutError, Sender};
    use std::sync::{Mutex, Once};
    use std::thread::JoinHandle;
    use std::time::{Duration, Instant};

    use dpi::PhysicalSize;
    use servo::{
        Code, DeviceIntPoint, DeviceIntRect, DeviceIntSize, DevicePoint, EventLoopWaker, JSValue,
        Key, KeyboardEvent, KeyState, LoadStatus, Location, Modifiers, RenderingContext,
        Servo, ServoBuilder, SoftwareRenderingContext, WebView, WebViewBuilder, WebViewDelegate,
        WebViewPoint, WheelDelta, WheelEvent, WheelMode,
    };
    use url::Url;

    use super::super::servo_ipc::{ServoIpcBackend, ADBLOCK_SCRIPT};
    use super::super::{BlockedEvent, EngineBackend, EngineError, EngineResult};

    /// Default embed viewport (GPU-less box; env-overridable for smoke runs).
    fn viewport_size() -> (u32, u32) {
        let w = std::env::var("SUB_EMBED_W").ok().and_then(|v| v.parse().ok()).unwrap_or(1280);
        let h = std::env::var("SUB_EMBED_H").ok().and_then(|v| v.parse().ok()).unwrap_or(800);
        (w.max(64), h.max(64))
    }

    enum Dir {
        Back,
        Forward,
    }

    enum Cmd {
        Nop,
        CreateView { tab: String, url: String, reply: Sender<EngineResult<()>> },
        Navigate { tab: String, url: String, reply: Sender<EngineResult<()>> },
        Traverse { tab: String, dir: Dir, reply: Sender<EngineResult<()>> },
        Reload { tab: String, reply: Sender<EngineResult<()>> },
        ReadUrl { tab: String, reply: Sender<EngineResult<String>> },
        ReadTitle { tab: String, reply: Sender<EngineResult<String>> },
        Screenshot { tab: String, reply: Sender<EngineResult<Vec<u8>>> },
        Focus { tab: String, reply: Sender<EngineResult<()>> },
        CloseView { tab: String, reply: Sender<EngineResult<()>> },
        Adblock { tab: String, reply: Sender<EngineResult<Vec<(String, String)>>> },
        Click { tab: String, x: f64, y: f64, reply: Sender<EngineResult<()>> },
        Key { tab: String, key: String, reply: Sender<EngineResult<()>> },
        Scroll { tab: String, dx: f64, dy: f64, reply: Sender<EngineResult<()>> },
        Shutdown,
    }

    /// Wakes the embed pump when Servo asks the embedder to spin its event
    /// loop (per winit_minimal's `Waker`; our "event loop" is the mpsc pump).
    struct TxWaker(Sender<Cmd>);

    impl EventLoopWaker for TxWaker {
        fn clone_box(&self) -> Box<dyn EventLoopWaker> {
            Box::new(TxWaker(self.0.clone()))
        }
        fn wake(&self) {
            // Pump is on an 8ms timeout anyway; a failed send means shutdown.
            let _ = self.0.send(Cmd::Nop);
        }
    }

    /// `notify_new_frame_ready` marks the surface dirty for the pump loop.
    struct FrameDelegate {
        dirty: std::cell::Cell<bool>,
    }

    impl WebViewDelegate for FrameDelegate {
        fn notify_new_frame_ready(&self, _webview: WebView) {
            self.dirty.set(true);
        }
        fn notify_crashed(&self, webview: WebView, reason: String, _bt: Option<String>) {
            eprintln!("[embed] webview {:?} crashed: {reason}", webview.id());
        }
    }

    struct EmbedInner {
        servo: Servo,
        ctx: Rc<SoftwareRenderingContext>,
        delegate: Rc<FrameDelegate>,
        tabs: HashMap<String, WebView>,
        active: Option<String>,
        viewport: (u32, u32),
        frames: u64,
    }

    impl EmbedInner {
        fn tab(&self, tab: &str) -> EngineResult<WebView> {
            self.tabs
                .get(tab)
                .cloned()
                .ok_or_else(|| EngineError::NoSuchTab(tab.to_string()))
        }

        fn parse_url(url: &str) -> EngineResult<Url> {
            Url::parse(url).map_err(|e| EngineError::Engine(format!("bad url {url}: {e}")))
        }

        /// Paint the active tab into the software surface and swap.
        fn paint_active(&mut self) {
            if let Some(id) = self.active.clone() {
                if let Some(wv) = self.tabs.get(&id) {
                    let _ = self.ctx.make_current();
                    wv.paint();
                    self.ctx.present();
                    self.frames += 1;
                    self.delegate.dirty.set(false);
                }
            }
        }

        fn create_view(&mut self, tab: &str, url: &str) -> EngineResult<()> {
            if self.tabs.contains_key(tab) {
                return Ok(());
            }
            let u = Self::parse_url(url)?;
            let wv = WebViewBuilder::new(&self.servo, self.ctx.clone() as Rc<dyn RenderingContext>)
                .url(u)
                .delegate(self.delegate.clone() as Rc<dyn WebViewDelegate>)
                .build();
            wv.resize(PhysicalSize::new(self.viewport.0, self.viewport.1));
            self.tabs.insert(tab.to_string(), wv);
            self.active = Some(tab.to_string());
            Ok(())
        }

        /// Settle the load, paint, read the software back buffer, encode PNG.
        fn screenshot(&mut self, tab: &str) -> EngineResult<Vec<u8>> {
            let wv = self.tab(tab)?;
            let t0 = Instant::now();
            while t0.elapsed() < Duration::from_secs(15) {
                self.servo.spin_event_loop();
                if wv.load_status() == LoadStatus::Complete && !self.delegate.dirty.get() {
                    break;
                }
                if wv.load_status() == LoadStatus::Complete && self.delegate.dirty.get() {
                    self.active = Some(tab.to_string());
                    self.paint_active();
                    self.servo.spin_event_loop();
                }
                std::thread::sleep(Duration::from_millis(5));
            }
            self.active = Some(tab.to_string());
            let _ = self.ctx.make_current();
            wv.paint();
            let size = self.ctx.size2d();
            let rect = DeviceIntRect::from_origin_and_size(
                DeviceIntPoint::new(0, 0),
                DeviceIntSize::new(size.width as i32, size.height as i32),
            );
            let img = self
                .ctx
                .read_to_image(rect)
                .ok_or_else(|| EngineError::Engine("software surface empty".into()))?;
            self.ctx.present();
            self.frames += 1;
            self.delegate.dirty.set(false);
            let mut out = std::io::Cursor::new(Vec::new());
            image::DynamicImage::ImageRgba8(img)
                .write_to(&mut out, image::ImageFormat::Png)
                .map_err(|e| EngineError::Engine(format!("png encode: {e}")))?;
            Ok(out.into_inner())
        }

        /// Same post-load DOM filter as the IPC backend, over
        /// WebView::evaluate_javascript (results arrive via the event loop).
        fn adblock(&mut self, tab: &str) -> EngineResult<Vec<(String, String)>> {
            let wv = self.tab(tab)?;
            let body =
                ADBLOCK_SCRIPT.replace("__SUBLIST__", &ServoIpcBackend::adblock_domains_json());
            let script = format!("(function() {{\n{body}\n}})()");
            let (tx, rx) = channel::<EngineResult<String>>();
            wv.evaluate_javascript(script, move |res| {
                let v = match res {
                    Ok(JSValue::String(s)) => Ok(s),
                    Ok(other) => Err(EngineError::Engine(format!("adblock js -> {other:?}"))),
                    Err(e) => Err(EngineError::Engine(format!("adblock js error: {e:?}"))),
                };
                let _ = tx.send(v);
            });
            let json = loop {
                match rx.recv_timeout(Duration::from_millis(100)) {
                    Ok(v) => break v?,
                    Err(RecvTimeoutError::Timeout) => self.servo.spin_event_loop(),
                    Err(RecvTimeoutError::Disconnected) => {
                        return Err(EngineError::Engine("adblock js callback lost".into()))
                    }
                }
            };
            let removals: Vec<(String, String)> =
                serde_json::from_str::<Vec<[String; 2]>>(&json)
                    .unwrap_or_default()
                    .into_iter()
                    .map(|[h, k]| (h, k))
                    .collect();
            Ok(removals)
        }

        fn handle(&mut self, cmd: Cmd) {
            match cmd {
                Cmd::Nop => {}
                Cmd::CreateView { tab, url, reply } => {
                    let r = self.create_view(&tab, &url);
                    let _ = reply.send(r);
                }
                Cmd::Navigate { tab, url, reply } => {
                    let r = (|| {
                        let wv = self.tab(&tab)?;
                        wv.load(Self::parse_url(&url)?);
                        Ok(())
                    })();
                    let _ = reply.send(r);
                }
                Cmd::Traverse { tab, dir, reply } => {
                    let r = (|| {
                        let wv = self.tab(&tab)?;
                        match dir {
                            Dir::Back if wv.can_go_back() => {
                                wv.go_back(1);
                            }
                            Dir::Forward if wv.can_go_forward() => {
                                wv.go_forward(1);
                            }
                            _ => {}
                        }
                        Ok(())
                    })();
                    let _ = reply.send(r);
                }
                Cmd::Reload { tab, reply } => {
                    let r = self.tab(&tab).map(|wv| wv.reload());
                    let _ = reply.send(r);
                }
                Cmd::ReadUrl { tab, reply } => {
                    self.servo.spin_event_loop();
                    let r = self.tab(&tab).map(|wv| {
                        wv.url().map(|u| u.to_string()).unwrap_or_default()
                    });
                    let _ = reply.send(r);
                }
                Cmd::ReadTitle { tab, reply } => {
                    self.servo.spin_event_loop();
                    let r = self.tab(&tab).map(|wv| wv.page_title().unwrap_or_default());
                    let _ = reply.send(r);
                }
                Cmd::Screenshot { tab, reply } => {
                    let r = self.screenshot(&tab);
                    let _ = reply.send(r);
                }
                Cmd::Focus { tab, reply } => {
                    if self.tabs.contains_key(&tab) {
                        self.active = Some(tab.clone());
                        self.paint_active();
                        let _ = reply.send(Ok(()));
                    } else {
                        let _ = reply.send(Err(EngineError::NoSuchTab(tab)));
                    }
                }
                Cmd::CloseView { tab, reply } => {
                    // No public WebView::deinit in 0.8.0; dropping our Rc
                    // leaves Servo's internal lifecycle to retire it.
                    self.tabs.remove(&tab);
                    if self.active.as_deref() == Some(tab.as_str()) {
                        self.active = self.tabs.keys().next().cloned();
                    }
                    let _ = reply.send(Ok(()));
                }
                Cmd::Adblock { tab, reply } => {
                    let r = self.adblock(&tab);
                    let _ = reply.send(r);
                }
                Cmd::Click { tab, x, y, reply } => {
                    let r = self.tab(&tab).map(|wv| {
                        let p = WebViewPoint::from(DevicePoint::new(x as f32, y as f32));
                        use servo::{InputEvent, MouseButton, MouseButtonAction, MouseButtonEvent};
                        wv.notify_input_event(InputEvent::MouseButton(MouseButtonEvent::new(
                            MouseButtonAction::Down,
                            MouseButton::Primary,
                            p,
                        )));
                        wv.notify_input_event(InputEvent::MouseButton(MouseButtonEvent::new(
                            MouseButtonAction::Up,
                            MouseButton::Primary,
                            p,
                        )));
                    });
                    let _ = reply.send(r);
                }
                Cmd::Key { tab, key, reply } => {
                    let r = self.tab(&tab).map(|wv| {
                        use servo::InputEvent;
                        for ch in key.chars() {
                            let k = parse_key(&ch.to_string());
                            wv.notify_input_event(InputEvent::Keyboard(
                                KeyboardEvent::new_without_event(
                                    KeyState::Down,
                                    k.clone(),
                                    Code::Unidentified,
                                    Location::Standard,
                                    Modifiers::empty(),
                                    false,
                                    false,
                                ),
                            ));
                            wv.notify_input_event(InputEvent::Keyboard(
                                KeyboardEvent::new_without_event(
                                    KeyState::Up,
                                    k,
                                    Code::Unidentified,
                                    Location::Standard,
                                    Modifiers::empty(),
                                    false,
                                    false,
                                ),
                            ));
                        }
                    });
                    let _ = reply.send(r);
                }
                Cmd::Scroll { tab, dx, dy, reply } => {
                    let r = self.tab(&tab).map(|wv| {
                        // Servo wheel convention: positive delta reveals
                        // content above/left → negate page-scroll deltas.
                        use servo::InputEvent;
                        wv.notify_input_event(InputEvent::Wheel(WheelEvent::new(
                            WheelDelta { x: -dx, y: -dy, z: 0.0, mode: WheelMode::DeltaPixel },
                            WebViewPoint::from(DevicePoint::new(1.0, 1.0)),
                        )));
                    });
                    let _ = reply.send(r);
                }
                Cmd::Shutdown => {}
            }
        }
    }

    fn parse_key(s: &str) -> Key {
        // keyboard_types FromStr: "a" → Character("a"); "Enter" → NamedKey.
        s.parse::<Key>()
            .unwrap_or_else(|_| Key::Character(s.to_string().into()))
    }

    fn install_crypto_provider() {
        static ONCE: Once = Once::new();
        ONCE.call_once(|| {
            // servo-net's hyper-rustls needs a process-wide crypto provider;
            // same bootstrap the official winit_minimal example does.
            let _ = rustls::crypto::aws_lc_rs::default_provider().install_default();
        });
    }

    fn profile_dir(profile: &str) -> Option<PathBuf> {
        dirs::config_dir().map(|d| d.join("sub-browser").join("profile").join(profile))
    }

    pub struct EmbedBackend {
        cmd: Sender<Cmd>,
        alive: Arc<AtomicBool>,
        blocked_txs: Mutex<Vec<Sender<BlockedEvent>>>,
        adblock_on: AtomicBool,
        viewport: (u32, u32),
        thread: Mutex<Option<JoinHandle<()>>>,
    }

    use std::sync::Arc;

    impl EmbedBackend {
        /// Bring up Servo in-process (software WebRender/SWGL). `profile`
        /// only reserves the config dir; cookies persist per profile once
        /// servo config-dir plumbing lands.
        pub fn spawn(profile: &str) -> Result<Self, EngineError> {
            install_crypto_provider();
            let viewport = viewport_size();
            let (cmd_tx, cmd_rx) = channel::<Cmd>();
            let alive = Arc::new(AtomicBool::new(true));
            let alive2 = alive.clone();
            let profile = profile.to_string();
            let (ready_tx, ready_rx) = channel::<EngineResult<()>>();
            let err_ready_tx = ready_tx.clone();
            let waker_tx = cmd_tx.clone();
            let thread = std::thread::Builder::new()
                .name("sub-embed".into())
                .spawn(move || {
                    let r = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
                        embed_main(&profile, viewport, cmd_rx, waker_tx, ready_tx)
                    }));
                    let v = match r {
                        Ok(v) => v,
                        Err(_) => Err(EngineError::Transport("embed thread panicked".into())),
                    };
                    // Only meaningful when boot never reported ready.
                    let _ = err_ready_tx.send(v);
                    alive2.store(false, Ordering::Relaxed);
                })
                .map_err(|e| EngineError::Transport(format!("spawn embed thread: {e}")))?;
            // Software WebRender init on 2 vCPU: a few seconds.
            ready_rx
                .recv_timeout(Duration::from_secs(180))
                .map_err(|e| EngineError::Transport(format!("embed init: {e}")))??;
            Ok(Self {
                cmd: cmd_tx,
                alive,
                blocked_txs: Mutex::new(Vec::new()),
                adblock_on: AtomicBool::new(true),
                viewport,
                thread: Mutex::new(Some(thread)),
            })
        }

        fn call<T>(&self, cmd: Cmd, rx: Receiver<EngineResult<T>>, timeout: Duration) -> EngineResult<T> {
            if !self.alive.load(Ordering::Relaxed) {
                return Err(EngineError::Transport("embed thread gone".into()));
            }
            self.cmd
                .send(cmd)
                .map_err(|_| EngineError::Transport("embed pump gone".into()))?;
            rx.recv_timeout(timeout)
                .map_err(|e| EngineError::Transport(format!("embed rpc timeout: {e}")))?
        }

        fn publish_blocked(&self, tab_id: &str, removals: &[(String, String)]) {
            if removals.is_empty() {
                return;
            }
            let mut txs = self.blocked_txs.lock().unwrap();
            txs.retain(|tx| {
                removals.iter().all(|(host, kind)| {
                    tx.send(BlockedEvent {
                        tab_id: tab_id.to_string(),
                        host: host.clone(),
                        kind: kind.clone(),
                    })
                    .is_ok()
                })
            });
        }
    }

    fn embed_main(
        profile: &str,
        viewport: (u32, u32),
        cmd_rx: Receiver<Cmd>,
        cmd_tx: Sender<Cmd>,
        ready: Sender<EngineResult<()>>,
    ) -> EngineResult<()> {
        if let Some(dir) = profile_dir(profile) {
            let _ = std::fs::create_dir_all(&dir);
        }
        let ctx = Rc::new(
            SoftwareRenderingContext::new(PhysicalSize::new(viewport.0, viewport.1))
                .map_err(|e| EngineError::Transport(format!("SoftwareRenderingContext: {e:?}")))?,
        );
        ctx.make_current()
            .map_err(|e| EngineError::Transport(format!("make_current: {e:?}")))?;
        let servo = ServoBuilder::default()
            .event_loop_waker(Box::new(TxWaker(cmd_tx)))
            .build();
        let _ = ready.send(Ok(()));

        let mut inner = EmbedInner {
            servo,
            ctx,
            delegate: Rc::new(FrameDelegate { dirty: std::cell::Cell::new(false) }),
            tabs: HashMap::new(),
            active: None,
            viewport,
            frames: 0,
        };

        loop {
            match cmd_rx.recv_timeout(Duration::from_millis(8)) {
                Ok(Cmd::Shutdown) | Err(RecvTimeoutError::Disconnected) => break,
                Ok(cmd) => inner.handle(cmd),
                Err(RecvTimeoutError::Timeout) => {}
            }
            inner.servo.spin_event_loop();
            if inner.delegate.dirty.get() {
                inner.paint_active();
            }
        }
        eprintln!("[embed] shut down after {} composited frames", inner.frames);
        Ok(())
    }

    #[cfg(feature = "embed")]
    impl EngineBackend for EmbedBackend {
        fn create_view(&self, tab_id: &str, url: &str) -> EngineResult<()> {
            let (tx, rx) = channel();
            self.call(
                Cmd::CreateView { tab: tab_id.into(), url: url.into(), reply: tx },
                rx,
                Duration::from_secs(30),
            )
        }
        fn navigate(&self, tab_id: &str, url: &str) -> EngineResult<()> {
            let (tx, rx) = channel();
            self.call(
                Cmd::Navigate { tab: tab_id.into(), url: url.into(), reply: tx },
                rx,
                Duration::from_secs(30),
            )
        }
        fn back(&self, tab_id: &str) -> EngineResult<()> {
            let (tx, rx) = channel();
            self.call(
                Cmd::Traverse { tab: tab_id.into(), dir: Dir::Back, reply: tx },
                rx,
                Duration::from_secs(15),
            )
        }
        fn forward(&self, tab_id: &str) -> EngineResult<()> {
            let (tx, rx) = channel();
            self.call(
                Cmd::Traverse { tab: tab_id.into(), dir: Dir::Forward, reply: tx },
                rx,
                Duration::from_secs(15),
            )
        }
        fn reload(&self, tab_id: &str) -> EngineResult<()> {
            let (tx, rx) = channel();
            self.call(Cmd::Reload { tab: tab_id.into(), reply: tx }, rx, Duration::from_secs(30))
        }
        fn url(&self, tab_id: &str) -> EngineResult<String> {
            let (tx, rx) = channel();
            self.call(Cmd::ReadUrl { tab: tab_id.into(), reply: tx }, rx, Duration::from_secs(10))
        }
        fn title(&self, tab_id: &str) -> EngineResult<String> {
            let (tx, rx) = channel();
            self.call(Cmd::ReadTitle { tab: tab_id.into(), reply: tx }, rx, Duration::from_secs(10))
        }
        fn screenshot(&self, tab_id: &str) -> EngineResult<Vec<u8>> {
            let (tx, rx) = channel();
            self.call(
                Cmd::Screenshot { tab: tab_id.into(), reply: tx },
                rx,
                Duration::from_secs(60),
            )
        }
        fn viewport(&self, _tab_id: &str) -> EngineResult<(u32, u32)> {
            Ok(self.viewport)
        }
        fn focus(&self, tab_id: &str) -> EngineResult<()> {
            let (tx, rx) = channel();
            self.call(Cmd::Focus { tab: tab_id.into(), reply: tx }, rx, Duration::from_secs(10))
        }
        fn close_view(&self, tab_id: &str) -> EngineResult<()> {
            let (tx, rx) = channel();
            self.call(Cmd::CloseView { tab: tab_id.into(), reply: tx }, rx, Duration::from_secs(10))
        }
        fn inject_adblock(&self, tab_id: &str) -> EngineResult<Vec<(String, String)>> {
            if !self.adblock_on.load(Ordering::Relaxed) {
                return Ok(Vec::new());
            }
            let (tx, rx) = channel();
            let removals = self.call(
                Cmd::Adblock { tab: tab_id.into(), reply: tx },
                rx,
                Duration::from_secs(30),
            )?;
            self.publish_blocked(tab_id, &removals);
            Ok(removals)
        }
        fn click(&self, tab_id: &str, x: f64, y: f64) -> EngineResult<()> {
            let (tx, rx) = channel();
            self.call(
                Cmd::Click { tab: tab_id.into(), x, y, reply: tx },
                rx,
                Duration::from_secs(10),
            )
        }
        fn key(&self, tab_id: &str, key: &str) -> EngineResult<()> {
            let (tx, rx) = channel();
            self.call(
                Cmd::Key { tab: tab_id.into(), key: key.into(), reply: tx },
                rx,
                Duration::from_secs(10),
            )
        }
        fn scroll(&self, tab_id: &str, dx: f64, dy: f64) -> EngineResult<()> {
            let (tx, rx) = channel();
            self.call(
                Cmd::Scroll { tab: tab_id.into(), dx, dy, reply: tx },
                rx,
                Duration::from_secs(10),
            )
        }
        fn blocked_event_stream(&self) -> Receiver<BlockedEvent> {
            let (tx, rx) = channel();
            self.blocked_txs.lock().unwrap().push(tx);
            rx
        }
        fn describe(&self) -> String {
            format!(
                "embed (servo 0.8.0 in-process, software surface {}x{}, zero-copy)",
                self.viewport.0, self.viewport.1
            )
        }
    }

    impl Drop for EmbedBackend {
        fn drop(&mut self) {
            let _ = self.cmd.send(Cmd::Shutdown);
            if let Some(h) = self.thread.lock().unwrap().take() {
                let _ = h.join();
            }
        }
    }
}

#[cfg(feature = "embed")]
pub use imp::EmbedBackend;
