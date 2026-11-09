//! SUB-BROWSER — Rust + Tauri 2 (WRY/WebKitGTK) desktop browser.
//!
//! One native GTK window ("main") containing:
//!   * a trusted local "toolbar" webview (top strip: tab pills, URL bar, nav buttons)
//!   * "tab-N" browsing webviews (remote content) below the toolbar; switching is
//!     show/hide, bounds are recomputed on resize.
//! IPC: toolbar -> Rust via `__TAURI_INTERNALS__.invoke('plugin:event|emit', ...)`
//! emitting "sub-cmd"; Rust -> toolbar via `emit_to("toolbar", "sub-state", ...)`.
//!
//! Threading notes (webkit2gtk backend): on Linux, `Webview::url()` reads the
//! WebKit URI property directly (no main-thread round-trip) and `eval()` is
//! thread-safe, so callbacks/handlers may use them; but `Window::inner_size()`
//! (used by `relayout`) must run on the main thread — it is only called from
//! window-event handlers and setup. `Window::add_child` is safe from any thread
//! (dispatches to the main thread when the loop is running).

use std::collections::HashMap;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::Mutex;

use serde::Serialize;
use tauri::utils::config::Color;
use tauri::webview::{PageLoadEvent, WebviewBuilder};
use tauri::{
    AppHandle, Emitter, Listener, LogicalPosition, LogicalSize, Manager, PhysicalPosition,
    PhysicalSize, Rect, Url, Webview, WebviewUrl, Window, WindowEvent, Wry,
};

const TOOLBAR_H: f64 = 96.0; // logical px: tab-strip row + URL row
const W: f64 = 1280.0;
const H: f64 = 800.0;
const DARK: Color = Color(5, 5, 5, 255);
const HOME_PAGE: &str = "newtab.html";

#[derive(Clone, Serialize)]
struct TabInfo {
    tab: String,
    url: String,
    title: String,
    active: bool,
    /// "url" | "title" | "meta" | "closed"
    event: String,
}

struct AppState {
    tabs: Mutex<HashMap<String, Webview<Wry>>>,
    active: Mutex<Option<String>>,
    counter: AtomicUsize,
    user_agent: String,
    start_url: String,
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

/// Heuristic address-bar parsing: absolute url / bare domain / duckduckgo search.
fn normalize_url(input: &str) -> Url {
    let s = input.trim();
    let fallback = || -> Url { format!("https://duckduckgo.com/?q={}", urlenc(s)).parse().unwrap() };
    let candidate = if s.is_empty() {
        return "https://duckduckgo.com/".parse().unwrap();
    } else if s.starts_with("http://") || s.starts_with("https://") {
        s.to_string()
    } else if s.contains('.') && !s.contains(' ') && !s.contains('/') {
        format!("https://{}", s)
    } else {
        return fallback();
    };
    candidate.parse::<Url>().unwrap_or_else(|_| fallback())
}

fn content_bounds(size: PhysicalSize<u32>, scale: f64) -> (PhysicalPosition<i32>, PhysicalSize<u32>) {
    let tb = (TOOLBAR_H * scale).max(1.0) as u32;
    (
        PhysicalPosition::new(0i32, tb as i32),
        PhysicalSize::new(size.width.max(1), size.height.saturating_sub(tb).max(1)),
    )
}

/// Must be called from the main thread (window-event handler / setup).
fn relayout(window: &Window<Wry>, app: &AppHandle<Wry>, state: &AppState) {
    let Ok(size) = window.inner_size() else { return };
    let Ok(scale) = window.scale_factor() else { return };
    let tb = (TOOLBAR_H * scale).max(1.0) as u32;
    if let Some(toolbar) = app.get_webview("toolbar") {
        let _ = toolbar.set_bounds(Rect {
            position: PhysicalPosition::new(0i32, 0i32).into(),
            size: PhysicalSize::new(size.width.max(1), tb).into(),
        });
    }
    let (pos, sz) = content_bounds(size, scale);
    let tabs = state.tabs.lock().unwrap();
    for wv in tabs.values() {
        let _ = wv.set_bounds(Rect { position: pos.into(), size: sz.into() });
    }
}

fn is_active(app: &AppHandle<Wry>, label: &str) -> bool {
    app.state::<AppState>().active.lock().unwrap().as_deref() == Some(label)
}

fn send(app: &AppHandle<Wry>, info: TabInfo) {
    let _ = app.emit_to("toolbar", "sub-state", info);
}

fn send_url(app: &AppHandle<Wry>, label: &str, url: &str) {
    send(app, TabInfo {
        tab: label.into(),
        url: url.into(),
        title: String::new(),
        active: is_active(app, label),
        event: "url".into(),
    });
}

fn send_title(app: &AppHandle<Wry>, label: &str, url: &str, title: &str) {
    send(app, TabInfo {
        tab: label.into(),
        url: url.into(),
        title: title.into(),
        active: is_active(app, label),
        event: "title".into(),
    });
}

fn send_closed(app: &AppHandle<Wry>, label: &str) {
    send(app, TabInfo {
        tab: label.into(),
        url: String::new(),
        title: String::new(),
        active: false,
        event: "closed".into(),
    });
}

/// Create a browsing webview tab. `url` may be a remote URL or the local new-tab page.
fn create_tab(app: &AppHandle<Wry>, url: WebviewUrl) -> Result<String, String> {
    let state = app.state::<AppState>();
    let window = app.get_window("main").ok_or("no main window")?;
    let n = state.counter.fetch_add(1, Ordering::SeqCst);
    let label = format!("tab-{}", n);
    let ua = state.user_agent.clone();

    let app_pl = app.clone();
    let lab_pl = label.clone();
    let app_tl = app.clone();
    let lab_tl = label.clone();

    let builder = WebviewBuilder::new(label.clone(), url)
        .user_agent(&ua)
        .background_color(DARK)
        .on_navigation(|_u| true)
        .on_page_load(move |_wv, payload| {
            if matches!(payload.event(), PageLoadEvent::Finished) {
                send_url(&app_pl, &lab_pl, payload.url().as_str());
            }
        })
        .on_document_title_changed(move |wv, title| {
            let u = wv.url().map(|u| u.to_string()).unwrap_or_default();
            send_title(&app_tl, &lab_tl, &u, &title);
        });

    let size = window.inner_size().unwrap_or(PhysicalSize::new(W as u32, H as u32));
    let scale = window.scale_factor().unwrap_or(1.0);
    let (pos, sz) = content_bounds(size, scale);

    let wv = window.add_child(builder, pos, sz).map_err(|e| e.to_string())?;
    state.tabs.lock().unwrap().insert(label.clone(), wv);
    Ok(label)
}

fn home_url() -> WebviewUrl {
    WebviewUrl::App(HOME_PAGE.into())
}

fn show_only(app: &AppHandle<Wry>, label: &str) {
    let state = app.state::<AppState>();
    {
        let tabs = state.tabs.lock().unwrap();
        for (l, wv) in tabs.iter() {
            if l == label {
                let _ = wv.show();
            } else {
                let _ = wv.hide();
            }
        }
    }
    *state.active.lock().unwrap() = Some(label.to_string());
    if let Some(wv) = app.get_webview(label) {
        let u = wv.url().map(|u| u.to_string()).unwrap_or_default();
        send(app, TabInfo {
            tab: label.into(),
            url: u,
            title: String::new(),
            active: true,
            event: "meta".into(),
        });
    }
}

fn active_tab(app: &AppHandle<Wry>) -> Option<Webview<Wry>> {
    let state = app.state::<AppState>();
    let l = state.active.lock().unwrap().clone()?;
    let wv = { let tabs = state.tabs.lock().unwrap(); tabs.get(&l).cloned() };
    wv
}

fn tab_n(label: &str) -> usize {
    label.trim_start_matches("tab-").parse().unwrap_or(0)
}

fn main() {
    tauri::Builder::default()
        .setup(|app| {
            let handle = app.handle().clone();

            let wk_ver = tauri::webview_version().unwrap_or_else(|_| "2.x".into());
            let ua = format!(
                "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/8.0 Safari/605.1.15 SUBBrowser/1.0 (WebKit {})",
                wk_ver
            );

            let start_url = std::env::args()
                .nth(1)
                .or_else(|| std::env::var("SUB_START_URL").ok())
                .unwrap_or_else(|| "https://example.com".to_string());

            app.manage(AppState {
                tabs: Mutex::new(HashMap::new()),
                active: Mutex::new(None),
                counter: AtomicUsize::new(0),
                user_agent: ua,
                start_url,
            });

            let window = Window::builder(app.handle(), "main")
                .inner_size(W, H)
                .min_inner_size(640.0, 400.0)
                .title("SUB-BROWSER")
                .background_color(DARK)
                .build()
                .map_err(|e| e.to_string())?;

            // Trusted toolbar webview (local UI, top strip)
            let _toolbar = window
                .add_child(
                    WebviewBuilder::new("toolbar", WebviewUrl::App("index.html".into()))
                        .background_color(DARK),
                    LogicalPosition::new(0.0, 0.0),
                    LogicalSize::new(W, TOOLBAR_H),
                )
                .map_err(|e| e.to_string())?;

            // First browsing tab at the start URL
            let first = {
                let st = app.state::<AppState>();
                normalize_url(&st.start_url.clone())
            };
            let label = create_tab(&handle, WebviewUrl::External(first))?;
            show_only(&handle, &label);
            if let Some(wv) = active_tab(&handle) {
                let _ = wv.set_focus();
            }
            relayout(&window, &handle, &app.state::<AppState>());
            // Headless/some WMs ignore show during setup; re-show after the
            // event loop is running via a short-lived helper thread.
            let wshow = window.clone();
            std::thread::spawn(move || {
                for delay_ms in [200u64, 1000, 3000] {
                    std::thread::sleep(std::time::Duration::from_millis(delay_ms));
                    let _ = wshow.show();
                    let _ = wshow.set_size(tauri::LogicalSize::new(W, H));
                    let _ = wshow.set_focus();
                }
            });

            let win2 = window.clone();
            let h1 = handle.clone();
            window.on_window_event(move |event| {
                if let WindowEvent::Resized(_) = event {
                    let st = win2.state::<AppState>();
                    relayout(&win2, &h1, &st);
                }
            });

            // Toolbar command bus. `create_tab` must never run on the GLib main
            // thread while the loop is blocked; worker threads are always safe.
            let h = handle.clone();
            handle.listen("sub-cmd", move |ev| {
                let p: serde_json::Value = serde_json::from_str(ev.payload()).unwrap_or_default();
                let cmd = p.get("cmd").and_then(|v| v.as_str()).unwrap_or("").to_string();
                let arg = p.get("arg").and_then(|v| v.as_str()).unwrap_or("").to_string();
                let app = h.clone();
                std::thread::spawn(move || {
                    let state = app.state::<AppState>();
                    match cmd.as_str() {
                        "ready" => {
                            // toolbar (re)loaded: replay full tab map
                            let tabs = state.tabs.lock().unwrap();
                            for (l, wv) in tabs.iter() {
                                let u = wv.url().map(|u| u.to_string()).unwrap_or_default();
                                send(&app, TabInfo {
                                    tab: l.clone(),
                                    url: u,
                                    title: String::new(),
                                    active: is_active(&app, l),
                                    event: "meta".into(),
                                });
                            }
                        }
                        "navigate" => {
                            if let Some(wv) = active_tab(&app) {
                                let _ = wv.navigate(normalize_url(&arg));
                            }
                        }
                        "new-tab" => {
                            if let Ok(l) = create_tab(&app, home_url()) {
                                show_only(&app, &l);
                                if let Some(wv) = active_tab(&app) {
                                    let _ = wv.set_focus();
                                }
                            }
                        }
                        "switch-tab" => {
                            if state.tabs.lock().unwrap().contains_key(&arg) {
                                show_only(&app, &arg);
                                if let Some(wv) = app.get_webview(&arg) {
                                    let _ = wv.set_focus();
                                }
                            }
                        }
                        "close-tab" => {
                            let removed = state.tabs.lock().unwrap().remove(&arg);
                            if let Some(wv) = removed {
                                let _ = wv.close();
                                send_closed(&app, &arg);
                                let closed_n = tab_n(&arg);
                                let mut remaining: Vec<(usize, String)> = state
                                    .tabs
                                    .lock()
                                    .unwrap()
                                    .keys()
                                    .map(|k| (tab_n(k), k.clone()))
                                    .collect();
                                remaining.sort();
                                if remaining.is_empty() {
                                    if let Ok(l) = create_tab(&app, home_url()) {
                                        show_only(&app, &l);
                                        if let Some(wv) = active_tab(&app) {
                                            let _ = wv.set_focus();
                                        }
                                    }
                                } else {
                                    let next = remaining
                                        .iter()
                                        .find(|(n, _)| *n > closed_n)
                                        .or_else(|| remaining.first())
                                        .map(|(_, k)| k.clone());
                                    if let Some(next) = next {
                                        show_only(&app, &next);
                                        if let Some(wv) = app.get_webview(&next) {
                                            let _ = wv.set_focus();
                                        }
                                    }
                                }
                            }
                        }
                        "back" => {
                            if let Some(wv) = active_tab(&app) {
                                let _ = wv.eval("window.history.back()");
                            }
                        }
                        "forward" => {
                            if let Some(wv) = active_tab(&app) {
                                let _ = wv.eval("window.history.forward()");
                            }
                        }
                        "reload" => {
                            if let Some(wv) = active_tab(&app) {
                                let _ = wv.reload();
                            }
                        }
                        _ => {}
                    }
                });
            });

            Ok(())
        })
        .build(tauri::generate_context!())
        .expect("error while building SUB-BROWSER")
        .run(|_app, _event| {});
}
