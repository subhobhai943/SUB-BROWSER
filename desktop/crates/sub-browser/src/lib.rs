//! SUB-BROWSER desktop v2 — engine + storage library layer.
//!
//! Layout:
//! * `engine`  — `EngineBackend` trait + implementations.
//!   - `servo_ipc` (default, PROVEN): supervisor spawns the official Servo
//!     v0.8.0 `servoshell` binary per profile and drives it over a hand-rolled
//!     tiny WebDriver HTTP/JSON client bound to loopback.
//!   - `embed` (cargo feature, stub): in-process `servo` crate embed path, see
//!     docs/ENGINE.md.
//! * `store`   — bookmarks / history / settings in SQLite (rusqlite) under the
//!     per-profile dir.
//! * `b64`     — minimal base64 (WebDriver screenshots are base64 PNGs).

pub mod b64;
pub mod engine;
pub mod store;
