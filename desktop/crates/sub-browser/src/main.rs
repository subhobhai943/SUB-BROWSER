//! SUB-BROWSER v2 desktop shell.
//!
//! Tauri 2 window ("main") hosting ONE trusted toolbar webview that fills the
//! window: chrome (tab pills + URL row) on top, and below it the engine view
//! region — a live-scaled screenshot stream of the ACTIVE tab's Servo
//! rendering (WebDriver screenshot polled at ~10 fps; VERDICT.md Option 2
//! presentation-mode (b)). Mouse/keyboard events in that region are forwarded
//! to the engine as W3C pointer/key actions. This is the v1 tile path; the
//! `embed` feature (in-process servo crate) replaces it when the servo crate
//! builds — see docs/ENGINE.md.
//!
//! IPC: toolbar -> Rust via `sub-cmd` events (same proven bus as v1);
//! Rust -> toolbar via `sub-state` (tab meta), `frame` (PNG base64),
//! `adblock-count`, `engine-info`.
//!
//! Engine: `spawn_backend` (crates/sub-browser/src/engine) picks
//! `ServoIpcBackend` (default) or `EmbedBackend` (`--features embed` +
//! setting engine=embed) behind the `EngineBackend` trait.
//! official servoshell 0.8.0 binary per profile over loopback WebDriver.
//! Bookmarks/history/settings: SQLite (rusqlite) beside the engine profile.

use std::collections::HashMap;
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
use std::sync::{Arc, Mutex, OnceLock};
use std::time::{Duration, Instant};

use serde::Serialize;
use tauri::utils::config::Color;
use tauri::webview::WebviewBuilder;
use tauri::{AppHandle, Emitter, Listener, LogicalPosition, LogicalSize, Manager, PhysicalPosition, PhysicalSize, Rect, Url, WebviewUrl, Window, WindowEvent, Wry};

use sub_browser::engine::embed::EmbedBackend;
use sub_browser::engine::servo_ipc::ServoIpcBackend;
use sub_browser::engine::{EngineBackend, EngineError};
use sub_browser::store::Store;

const TOOLBAR_H: f64 = 96.0; // logical px chrome height (mirrors ui/index.html)
const W: f64 = 1280.0;
const H: f64 = 800.0;
const DARK: Color = Color(5, 5, 5, 255);
const FRAME_MS: u64 = 100; // ~10 fps tile stream
const PROFILE: &str = "default";

#[derive(Clone, Serialize)]
struct TabInfo {
    tab: String,
    url: String,
    title: String,
    active: bool,
    /// "url" | "title" | "meta" | "closed"
    event: String,
}

#[derive(Clone, Serialize)]
struct Frame {
    tab: String,
    /// base64 PNG
    img: String,
    /// engine viewport size (for input coordinate mapping)
    vw: u32,
    vh: u32,
}

struct AppState {
    engine: Mutex<Option<Arc<dyn EngineBackend>>>,
    tab_order: Mutex<Vec<String>>,
    active: Mutex<Option<String>>,
    counter: AtomicUsize,
    blocked: AtomicUsize,
    adblock_enabled: AtomicBool,
    /// url/title of the last "page ready" pass per tab (drives re-injection).
    settled: Mutex<HashMap<String, String>>, // tab -> url
    /// cached tab meta (poller-maintained; replay on toolbar reload without
    /// touching the engine, which would steal focus per read).
    meta: Mutex<HashMap<String, (String, String)>>,
    store: Mutex<Option<Arc<Store>>>,
    last_badge: Mutex<Option<Instant>>,
    engine_dead: AtomicBool,
}

static SHARED_STATE: OnceLock<Arc<AppState>> = OnceLock::new();
static SHARED_APP: OnceLock<AppHandle<Wry>> = OnceLock::new();

fn engine(_app: &AppHandle<Wry>) -> Option<Arc<dyn EngineBackend>> {
    SHARED_STATE.get()?.engine.lock().unwrap().clone()
}

/// Backend selection: store setting `engine` = "embed" | "ipc" (default
/// "ipc"). `embed` is only compilable with `--features embed`; asking for it
/// without the feature (or when init fails) falls back to IPC so the app
/// never bricks on engine choice. Embed numbers on this box (docs/ENGINE.md):
/// 84 ms boot, 8 ms native-surface captures — worth flipping once the
/// feature is on by default for Linux release builds.
fn spawn_backend(store: Option<&Arc<Store>>) -> Result<Arc<dyn EngineBackend>, EngineError> {
    let want_embed = store.and_then(|s| s.setting("engine")).as_deref() == Some("embed");
    if want_embed {
        #[cfg(feature = "embed")]
        match EmbedBackend::spawn(PROFILE) {
            Ok(be) => return Ok(Arc::new(be)),
            Err(e) => eprintln!("[main] embed backend failed ({e}); falling back to IPC"),
        }
        #[cfg(not(feature = "embed"))]
        eprintln!("[main] engine=embed requested but binary built without `embed` feature; using IPC");
    }
    ServoIpcBackend::spawn(PROFILE).map(|be| Arc::new(be) as Arc<dyn EngineBackend>)
}

fn state_of<M: Manager<R>, R: tauri::Runtime>(manager: &M) -> Arc<AppState> {
    manager.state::<Arc<AppState>>().inner().clone()
}

fn is_active(app: &AppHandle<Wry>, label: &str) -> bool {
    state_of(app).active.lock().unwrap().as_deref() == Some(label)
}

fn send(app: &AppHandle<Wry>, info: TabInfo) {
    let _ = app.emit_to("toolbar", "sub-state", info);
}

fn send_meta(app: &AppHandle<Wry>, label: &str) {
    let eng = engine(app);
    let (url, title) = match (&eng, label) {
        (Some(e), l) if !l.is_empty() => (
            e.url(l).unwrap_or_default(),
            e.title(l).unwrap_or_default(),
        ),
        _ => (String::new(), String::new()),
    };
    state_of(app).meta.lock().unwrap().insert(label.into(), (url.clone(), title.clone()));
    send(app, TabInfo {
        tab: label.into(),
        url,
        title,
        active: is_active(app, label),
        event: "meta".into(),
    });
}

fn send_meta_cached(app: &AppHandle<Wry>, label: &str) {
    let (url, title) = state_of(app).meta.lock().unwrap().get(label).cloned().unwrap_or_default();
    send(app, TabInfo {
        tab: label.into(),
        url,
        title,
        active: is_active(app, label),
        event: "meta".into(),
    });
}

fn emit_adblock_count(app: &AppHandle<Wry>, force: bool) {
    let state = match SHARED_STATE.get() {
        Some(s) => s,
        None => return,
    };
    {
        let mut last = state.last_badge.lock().unwrap();
        let now = Instant::now();
        if !force {
            if let Some(prev) = *last {
                if now.duration_since(prev).as_millis() < 400 {
                    return;
                }
            }
        }
        *last = Some(now);
    }
    let _ = app.emit_to("toolbar", "adblock-count", serde_json::json!({
        "count": state.blocked.load(Ordering::Relaxed),
        "enabled": state.adblock_enabled.load(Ordering::Relaxed),
    }));
}

fn urlenc(s: &str) -> String {
    let mut out = String::new();
    for b in s.as_bytes() {
        match b {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'_' | b'.' | b'~' => out.push(*b as char),
            b' ' => out.push('+'),
            _ => out.push_str(&format!("%{:02X}", b)),
        }
    }
    out
}

fn normalize_url(input: &str) -> Url {
    let s = input.trim();
    let fallback = || -> Url { format!("https://duckduckgo.com/?q={}", urlenc(s)).parse().unwrap() };
    if s.is_empty() {
        return "https://duckduckgo.com/".parse().unwrap();
    }
    let candidate = if s.starts_with("http://") || s.starts_with("https://") {
        s.to_string()
    } else if s.contains('.') && !s.contains(' ') && !s.contains('/') {
        format!("https://{s}")
    } else {
        return fallback();
    };
    candidate.parse::<Url>().unwrap_or_else(|_| fallback())
}

fn relayout(window: &Window<Wry>, app: &AppHandle<Wry>) {
    let Ok(size) = window.inner_size() else { return };
    let Ok(scale) = window.scale_factor() else { return };
    let _ = scale;
    if let Some(toolbar) = app.get_webview("toolbar") {
        // The toolbar webview fills the whole window; chrome is the top strip
        // and the engine tile stream is rendered inside it below TOOLBAR_H.
        let _ = toolbar.set_bounds(Rect {
            position: PhysicalPosition::new(0i32, 0i32).into(),
            size: PhysicalSize::new(size.width.max(1), size.height.max(1)).into(),
        });
    }
}

fn new_tab(app: &AppHandle<Wry>, url: Url) -> Result<String, String> {
    let state = state_of(app);
    let Some(eng) = engine(app) else { return Err("engine not ready".into()) };
    let n = state.counter.fetch_add(1, Ordering::SeqCst);
    let label = format!("tab-{n}");
    eng.create_view(&label, url.as_str()).map_err(|e| e.to_string())?;
    state.tab_order.lock().unwrap().push(label.clone());
    show_only(app, &label);
    Ok(label)
}

fn show_only(app: &AppHandle<Wry>, label: &str) {
    let state = state_of(app);
    if let Some(eng) = engine(app) {
        let _ = eng.focus(label);
    }
    *state.active.lock().unwrap() = Some(label.to_string());
    // Reset settle tracking so the poller re-detects readiness & re-injects.
    state.settled.lock().unwrap().remove(label);
    send_meta(app, label);
}

fn active_label(app: &AppHandle<Wry>) -> Option<String> {
    state_of(app).active.lock().unwrap().clone()
}

fn tab_n(label: &str) -> usize {
    label.trim_start_matches("tab-").parse().unwrap_or(0)
}

/// Background supervisor: engine spawn + per-frame tile stream + page-ready
/// detection (adblock injection, history, tab meta).
fn spawn_engine_and_pump(app: AppHandle<Wry>, start_url: Url) {
    std::thread::spawn(move || {
        let state = state_of(&app);
        // 1) Spawn engine (embed: ms; IPC: spawn + webdriver a few seconds).
        let store_opt = state.store.lock().unwrap().clone();
        match spawn_backend(store_opt.as_ref()) {
            Ok(be) => {
                let rx = be.blocked_event_stream();
                *state.engine.lock().unwrap() = Some(be.clone());
                let _ = app.emit_to("toolbar", "engine-info", serde_json::json!({
                    "backend": be.describe(), "ok": true,
                }));
                // Forward blocked events (engine-side) to the badge.
                let app_rx = app.clone();
                std::thread::spawn(move || {
                    while rx.recv().is_ok() {
                        app_rx
                            .state::<Arc<AppState>>()
                            .blocked
                            .fetch_add(1, Ordering::Relaxed);
                        emit_adblock_count(&app_rx, false);
                    }
                });
                // First tab.
                let _ = new_tab(&app, start_url);
            }
            Err(e) => {
                state.engine_dead.store(true, Ordering::Relaxed);
                let _ = app.emit_to("toolbar", "engine-info", serde_json::json!({
                    "ok": false, "error": e.to_string(),
                }));
                return;
            }
        }

        // 2) Tile stream + page-ready poll loop.
        let mut last_poll = Instant::now();
        let mut last_seen: HashMap<String, String> = HashMap::new();
        loop {
            std::thread::sleep(Duration::from_millis(FRAME_MS));
            let Some(eng) = engine(&app) else { break };
            let Some(active) = active_label(&app) else { continue };

            // Frame: screenshot of active tab -> <img> in the toolbar region.
            match eng.screenshot(&active) {
                Ok(png) if !png.is_empty() => {
                    let (vw, vh) = eng.viewport(&active).unwrap_or((1024, 740));
                    let img = sub_browser::b64::encode(&png);
                    let _ = app.emit_to("toolbar", "frame", Frame { tab: active.clone(), img, vw, vh });
                }
                Ok(_) => {}
                Err(e) => {
                    let _ = app.emit_to("toolbar", "engine-info", serde_json::json!({
                        "ok": false, "error": format!("frame: {e}"),
                    }));
                }
            }

            // 500 ms: page-ready detection.
            if last_poll.elapsed() < Duration::from_millis(500) {
                continue;
            }
            last_poll = Instant::now();
            let url = match eng.url(&active) {
                Ok(u) => u,
                Err(_) => continue,
            };
            let title = eng.title(&active).unwrap_or_default();
            state_of(&app).meta.lock().unwrap().insert(active.clone(), (url.clone(), title.clone()));
            let prev = last_seen.insert(active.clone(), url.clone());
            if prev.as_deref() != Some(url.as_str()) {
                // URL still moving (redirect/load) — surface it.
                send(&app, TabInfo {
                    tab: active.clone(),
                    url: url.clone(),
                    title: String::new(),
                    active: true,
                    event: "url".into(),
                });
                continue;
            }
            // Stable URL on the active tab. "Ready" when the url has not been
            // processed since it last changed and a title exists (or empty is
            // stable across two extra polls).
            let already = state.settled.lock().unwrap().get(&active).cloned();
            if already.as_deref() == Some(url.as_str()) || url.is_empty() {
                continue;
            }
            // Page settled on `url`: inject ad filter, record history, notify.
            state.settled.lock().unwrap().insert(active.clone(), url.clone());
            let removals = eng.inject_adblock(&active).unwrap_or_default();
            if !removals.is_empty() {
                state.blocked.fetch_add(removals.len(), Ordering::Relaxed);
                emit_adblock_count(&app, true);
            }
            if let Some(store) = state.store.lock().unwrap().clone() {
                let _ = store.record_visit(&url, &title);
            }
            send(&app, TabInfo {
                tab: active.clone(),
                url: url.clone(),
                title,
                active: true,
                event: "title".into(),
            });
        }
    });
}

fn main() {
    tauri::Builder::default()
        .setup(|app| {
            let handle = app.handle().clone();

            let start_url = std::env::args()
                .nth(1)
                .or_else(|| std::env::var("SUB_START_URL").ok())
                .unwrap_or_else(|| "https://example.com".to_string());

            let store_opt: Option<Arc<Store>> = Store::open(PROFILE).ok().map(Arc::new);

            let adblock_pref = store_opt
                .as_ref()
                .and_then(|s| s.setting("adblock"))
                .map(|v| v != "off")
                .unwrap_or(true);

            let state_arc = Arc::new(AppState {
                engine: Mutex::new(None),
                tab_order: Mutex::new(Vec::new()),
                active: Mutex::new(None),
                counter: AtomicUsize::new(0),
                blocked: AtomicUsize::new(0),
                adblock_enabled: AtomicBool::new(adblock_pref),
                settled: Mutex::new(HashMap::new()),
                meta: Mutex::new(HashMap::new()),
                store: Mutex::new(store_opt),
                last_badge: Mutex::new(None),
                engine_dead: AtomicBool::new(false),
            });
            app.manage(state_arc.clone());
            SHARED_STATE.set(state_arc).ok();
            SHARED_APP.set(handle.clone()).ok();

            let window = Window::builder(&handle, "main")
                .inner_size(W, H)
                .min_inner_size(800.0, 400.0)
                .title("SUB-BROWSER")
                .background_color(DARK)
                .build()
                .map_err(|e| e.to_string())?;

            // Full-window toolbar webview: chrome strip + engine tile region.
            let _toolbar = window
                .add_child(
                    WebviewBuilder::new("toolbar", WebviewUrl::App("index.html".into()))
                        .background_color(DARK),
                    LogicalPosition::new(0.0, 0.0),
                    LogicalSize::new(W, H),
                )
                .map_err(|e| e.to_string())?;

            relayout(&window, &handle);

            // WM-less displays ignore show() during setup — re-show shortly.
            let wshow = window.clone();
            std::thread::spawn(move || {
                for delay_ms in [200u64, 1000, 3000] {
                    std::thread::sleep(std::time::Duration::from_millis(delay_ms));
                    let _ = wshow.show();
                    let _ = wshow.set_focus();
                }
            });

            let win2 = window.clone();
            let h1 = handle.clone();
            window.on_window_event(move |event| {
                if let WindowEvent::Resized(_) = event {
                    relayout(&win2, &h1);
                }
            });

            spawn_engine_and_pump(handle.clone(), normalize_url(&start_url));

            // Toolbar command bus (worker threads; never the GLib main thread).
            let h = handle.clone();
            handle.listen("sub-cmd", move |ev| {
                let p: serde_json::Value = serde_json::from_str(ev.payload()).unwrap_or_default();
                let cmd = p.get("cmd").and_then(|v| v.as_str()).unwrap_or("").to_string();
                let arg = p.get("arg").and_then(|v| v.as_str()).unwrap_or("").to_string();
                let app = h.clone();
                std::thread::spawn(move || {
                    let state = state_of(&app);
                    let eng = engine(&app);
                    let active = active_label(&app);
                    match cmd.as_str() {
                        "ready" => {
                            // (re)play: tab map + badge + engine state (cache:
                            // reading the engine per-tab would steal focus).
                            let tabs = state.tab_order.lock().unwrap().clone();
                            for l in tabs {
                                send_meta_cached(&app, &l);
                            }
                            emit_adblock_count(&app, true);
                        }
                        "navigate" => {
                            if let (Some(e), Some(l)) = (&eng, &active) {
                                let url = normalize_url(&arg);
                                state.settled.lock().unwrap().remove(l);
                                let _ = e.navigate(l, url.as_str());
                            }
                        }
                        "home" => {
                            if let (Some(e), Some(l)) = (&eng, &active) {
                                state.settled.lock().unwrap().remove(l);
                                let _ = e.navigate(l, "https://example.com/");
                            }
                        }
                        "new-tab" => {
                            // The engine cannot render the tauri-origin newtab
                            // page, so fresh tabs start on the search engine.
                            let _ = new_tab(&app, normalize_url("duckduckgo.com"));
                        }
                        "switch-tab" => {
                            if state.tab_order.lock().unwrap().contains(&arg) {
                                show_only(&app, &arg);
                            }
                        }
                        "close-tab" => {
                            if let Some(e) = &eng {
                                let _ = e.close_view(&arg);
                            }
                            let mut order = state.tab_order.lock().unwrap();
                            let closed_n = tab_n(&arg);
                            order.retain(|l| l != &arg);
                            let remaining = order.clone();
                            drop(order);
                            state.meta.lock().unwrap().remove(&arg);
                            send(&app, TabInfo { tab: arg.clone(), url: String::new(), title: String::new(), active: false, event: "closed".into() });
                            if let Some(next) = remaining.iter().find(|l| tab_n(l) > closed_n).or_else(|| remaining.first()) {
                                show_only(&app, next);
                            } else if let Some(e) = &eng {
                                // last tab closed: open a fresh one
                                let _ = new_tab(&app, normalize_url("duckduckgo.com"));
                                let _ = e;
                            }
                        }
                        "back" | "forward" | "reload" => {
                            if let (Some(e), Some(l)) = (&eng, &active) {
                                state.settled.lock().unwrap().remove(l);
                                match cmd.as_str() {
                                    "back" => { let _ = e.back(l); }
                                    "forward" => { let _ = e.forward(l); }
                                    _ => { let _ = e.reload(l); }
                                }
                            }
                        }
                        "toggle-adblock" => {
                            let now = !state.adblock_enabled.fetch_xor(true, Ordering::SeqCst);
                            if let Some(e) = &eng {
                                e.set_adblock_enabled(now);
                            }
                            if let Some(s) = state.store.lock().unwrap().as_ref() {
                                let _ = s.set_setting("adblock", if now { "on" } else { "off" });
                            }
                            // Re-run filtering over the current page when turning on.
                            if now {
                                if let (Some(e), Some(l)) = (&eng, &active) {
                                    let removals = e.inject_adblock(&l).unwrap_or_default();
                                    state.blocked.fetch_add(removals.len(), Ordering::Relaxed);
                                }
                            }
                            emit_adblock_count(&app, true);
                        }
                        "click" => {
                            if let (Some(e), Some(l)) = (&eng, &active) {
                                let (x, y) = parse_pair(&arg);
                                let _ = e.click(l, x, y);
                                state.settled.lock().unwrap().remove(l);
                            }
                        }
                        "key" => {
                            if let (Some(e), Some(l)) = (&eng, &active) {
                                let _ = e.key(l, &arg);
                            }
                        }
                        "scroll" => {
                            if let (Some(e), Some(l)) = (&eng, &active) {
                                let (dx, dy) = parse_pair(&arg);
                                let _ = e.scroll(l, dx, dy);
                            }
                        }
                        "bookmark" => {
                            if let (Some(s), Some(l)) = (state.store.lock().unwrap().clone(), &active) {
                                if let Some(e) = &eng {
                                    if let Ok(u) = e.url(l) {
                                        if !u.is_empty() {
                                            if s.is_bookmarked(&u) {
                                                let _ = s.remove_bookmark(&u);
                                            } else {
                                                let t = e.title(l).unwrap_or_default();
                                                let _ = s.add_bookmark(&u, &t);
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        "list-bookmarks" => {
                            let list = state.store.lock().unwrap().as_ref().map(|s| s.list_bookmarks()).unwrap_or_default();
                            let _ = app.emit_to("toolbar", "bookmarks", serde_json::to_value(&list).unwrap_or_default());
                        }
                        "list-history" => {
                            let list = state.store.lock().unwrap().as_ref().map(|s| s.list_history(100)).unwrap_or_default();
                            let _ = app.emit_to("toolbar", "history", serde_json::to_value(&list).unwrap_or_default());
                        }
                        _ => {}
                    }
                });
            });

            Ok(())
        })
        .build(tauri::generate_context!())
        .expect("error while building SUB-BROWSER v2")
        .run(|_app, _event| {});
}

fn parse_pair(s: &str) -> (f64, f64) {
    let mut it = s.split(',').map(|p| p.trim().parse::<f64>().unwrap_or(0.0));
    (it.next().unwrap_or(0.0), it.next().unwrap_or(0.0))
}
