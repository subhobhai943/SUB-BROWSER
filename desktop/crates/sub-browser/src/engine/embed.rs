//! `embed` feature stub — in-process `servo` crate backend (VERDICT.md
//! Option 1). This module intentionally does NOT compile the servo crate
//! dependency yet; docs/ENGINE.md describes the exact switch procedure once
//! the servo 0.8.x dependency graph builds on CI (mozjs/SpiderMonkey C++ is
//! the 60-150 min cold-build long pole on 2 vCPU).
//!
//! When enabled, add to Cargo.toml under `[target.'cfg(not())'.dependencies]`
//! style gating or an optional dependency:
//!
//! ```toml
//! servo = { version = "0.8", default-features = false, features = ["js_jit"], optional = true }
//! ```
//!
//! and implement [`EmbedBackend`] against `servo::ServoBuilder`,
//! `WebViewBuilder`, `WebViewDelegate` + `SoftwareRenderingContext`: each
//! tab is a `WebView`; frames land in a shared buffer that the chrome polls
//! at zero copy (replacing the WebDriver screenshot stream), input goes
//! through `WebView::notify_event(WebViewInputEvent)` — no WebDriver at all.
//! Profile dirs are layout-compatible with ServoIpcBackend, so switching
//! backends preserves user state.

use std::sync::mpsc::Receiver;

use super::EngineError;
#[cfg(feature = "embed")]
use super::{BlockedEvent, EngineBackend, EngineResult};

pub struct EmbedBackend;

impl EmbedBackend {
    #[allow(dead_code)]
    pub fn spawn(_profile: &str) -> Result<Self, EngineError> {
        Err(EngineError::Unsupported(
            "in-process servo embed not built yet — enable the `embed` cargo feature \
             once the servo crate compiles on CI (see docs/ENGINE.md)".into(),
        ))
    }
}

// The trait is implemented only when the feature is on so the stub cannot be
// mistaken for a working backend.
#[cfg(feature = "embed")]
impl EngineBackend for EmbedBackend {
    fn create_view(&self, _t: &str, _u: &str) -> EngineResult<()> { unimplemented!("embed: pending servo crate build") }
    fn navigate(&self, _t: &str, _u: &str) -> EngineResult<()> { unimplemented!() }
    fn back(&self, _t: &str) -> EngineResult<()> { unimplemented!() }
    fn forward(&self, _t: &str) -> EngineResult<()> { unimplemented!() }
    fn reload(&self, _t: &str) -> EngineResult<()> { unimplemented!() }
    fn url(&self, _t: &str) -> EngineResult<String> { unimplemented!() }
    fn title(&self, _t: &str) -> EngineResult<String> { unimplemented!() }
    fn screenshot(&self, _t: &str) -> EngineResult<Vec<u8>> { unimplemented!() }
    fn viewport(&self, _t: &str) -> EngineResult<(u32, u32)> { unimplemented!() }
    fn focus(&self, _t: &str) -> EngineResult<()> { unimplemented!() }
    fn close_view(&self, _t: &str) -> EngineResult<()> { unimplemented!() }
    fn inject_adblock(&self, _t: &str) -> EngineResult<Vec<(String, String)>> { unimplemented!() }
    fn click(&self, _t: &str, _x: f64, _y: f64) -> EngineResult<()> { unimplemented!() }
    fn key(&self, _t: &str, _k: &str) -> EngineResult<()> { unimplemented!() }
    fn scroll(&self, _t: &str, _dx: f64, _dy: f64) -> EngineResult<()> { unimplemented!() }
    fn blocked_event_stream(&self) -> Receiver<BlockedEvent> {
        let (tx, rx) = std::sync::mpsc::channel();
        drop(tx);
        rx
    }
    fn describe(&self) -> String { "embed (servo 0.8 in-process, STUB)".into() }
}
