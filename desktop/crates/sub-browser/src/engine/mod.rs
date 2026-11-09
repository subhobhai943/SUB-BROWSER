//! Engine abstraction for SUB-BROWSER v2.
//!
//! The Tauri chrome (toolbar window + command bus) only talks to this trait,
//! so the rendering engine can be swapped without touching UI code:
//!
//! * [`servo_ipc::ServoIpcBackend`] — default, proven path (Option 2 of
//!   /opt/gecko-desktop/VERDICT.md): the official Servo v0.8.0 `servoshell`
//!   binary runs as a child process per profile and is driven over its
//!   built-in WebDriver server on loopback.
//! * [`embed::EmbedBackend`] — cargo feature `embed`: in-process `servo`
//!   crate over `SoftwareRenderingContext` (SWGL). Rendering proven on the
//!   GPU-less box; see docs/ENGINE.md for status + the headless smoke.

pub mod embed;
pub mod servo_ipc;
pub mod wd;

use std::sync::mpsc::Receiver;

/// One ad-host removal reported by the content-script filter pass.
#[derive(Debug, Clone)]
pub struct BlockedEvent {
    pub tab_id: String,
    /// Blocked host (matched against the embedded blocklist, suffix match).
    pub host: String,
    /// Element kind that was removed (img/iframe/script/...).
    pub kind: String,
}

#[derive(Debug)]
pub enum EngineError {
    /// Engine process gone / socket failure.
    Transport(String),
    /// WebDriver returned an error value.
    Engine(String),
    /// Unknown tab id.
    NoSuchTab(String),
    /// Feature not implemented by this backend.
    Unsupported(String),
}

impl std::fmt::Display for EngineError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            EngineError::Transport(s) => write!(f, "transport error: {s}"),
            EngineError::Engine(s) => write!(f, "engine error: {s}"),
            EngineError::NoSuchTab(s) => write!(f, "no such tab: {s}"),
            EngineError::Unsupported(s) => write!(f, "unsupported: {s}"),
        }
    }
}

impl std::error::Error for EngineError {}

pub type EngineResult<T> = Result<T, EngineError>;

/// A viewport-sized view of one tab, rendered by the engine.
pub trait EngineBackend: Send + Sync {
    /// Create a view for `tab_id` and start loading `url`.
    fn create_view(&self, tab_id: &str, url: &str) -> EngineResult<()>;
    /// Navigate the tab's view to `url`.
    fn navigate(&self, tab_id: &str, url: &str) -> EngineResult<()>;
    fn back(&self, tab_id: &str) -> EngineResult<()>;
    fn forward(&self, tab_id: &str) -> EngineResult<()>;
    fn reload(&self, tab_id: &str) -> EngineResult<()>;
    /// Current URL of the tab.
    fn url(&self, tab_id: &str) -> EngineResult<String>;
    /// Current document title of the tab.
    fn title(&self, tab_id: &str) -> EngineResult<String>;
    /// PNG screenshot of the tab viewport (active-tab semantics: the engine
    /// captures its focused browsing context).
    fn screenshot(&self, tab_id: &str) -> EngineResult<Vec<u8>>;
    /// Viewport size (px) the engine renders at — used by the chrome to scale
    /// the screenshot stream and map input coordinates.
    fn viewport(&self, tab_id: &str) -> EngineResult<(u32, u32)>;
    /// Resize the engine rendering viewport to exactly `w`x`h` px so the
    /// stream never has to be non-uniformly stretched into the window
    /// (v2.0.1: window resizes propagate to the engine here).
    fn set_viewport(&self, w: u32, h: u32) -> EngineResult<()>;
    /// Last viewport set via [`Self::set_viewport`] (None = never resized).
    /// Backends use this to bring newly created tabs up at the current size.
    fn requested_viewport(&self) -> Option<(u32, u32)>;
    /// Enable/disable post-load ad filtering at runtime. Default no-op for
    /// backends that only filter on demand (embed wires this when available).
    fn set_adblock_enabled(&self, _on: bool) {}
    /// Make this tab the engine's focused context (raise).
    fn focus(&self, tab_id: &str) -> EngineResult<()>;
    /// Destroy the tab's view.
    fn close_view(&self, tab_id: &str) -> EngineResult<()>;
    /// Inject the ad-filter content script; returns removed (host, kind) pairs.
    fn inject_adblock(&self, tab_id: &str) -> EngineResult<Vec<(String, String)>>;
    /// Forward a mouse click at viewport coordinates.
    fn click(&self, tab_id: &str, x: f64, y: f64) -> EngineResult<()>;
    /// Forward one keyboard key (W3C actions value, e.g. "a", "Enter").
    fn key(&self, tab_id: &str, key: &str) -> EngineResult<()>;
    /// Scroll the page by (dx, dy) CSS px.
    fn scroll(&self, tab_id: &str, dx: f64, dy: f64) -> EngineResult<()>;
    /// Subscribe to ad-block events (one channel per subscriber).
    fn blocked_event_stream(&self) -> Receiver<BlockedEvent>;
    /// Human-readable engine id, e.g. "servo-ipc 0.8.0".
    fn describe(&self) -> String;
}
