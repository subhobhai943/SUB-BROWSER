//! Hand-rolled tiny WebDriver HTTP/JSON client (loopback only).
//!
//! Servo's built-in WebDriver server (servoshell `--webdriver=HOST:PORT`) is
//! plain HTTP/1.1 + JSON (W3C subset). We deliberately avoid reqwest/hyper:
//! one blocking `TcpStream` per request, `Content-Length` framing, no chunked
//! bodies on loopback. Verified against Servo v0.8.0 (see docs/ENGINE.md
//! evidence table): session create/delete, url get/post, title, back/forward/
//! refresh, window (get/post/new/delete/handles), screenshot, execute/sync,
//! W3C actions, window/rect.

use std::fmt::Write as _;
use std::io::{Read, Write};
use std::net::TcpStream;
use std::time::Duration;

use serde_json::Value;

#[derive(Debug)]
pub enum WdError {
    Io(String),
    /// JSON body carried a `value.error` (W3C error response).
    Response(String),
}

impl std::fmt::Display for WdError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            WdError::Io(s) => write!(f, "wd io: {s}"),
            WdError::Response(s) => write!(f, "wd resp: {s}"),
        }
    }
}

pub type WdResult<T> = Result<T, WdError>;

fn hex_encode(bytes: &[u8]) -> String {
    let mut s = String::with_capacity(bytes.len() * 2);
    for b in bytes {
        let _ = write!(s, "{b:02x}");
    }
    s
}

/// Percent-encode a WebDriver window handle (they contain `:` and `,`, e.g.
/// `PainterId: 1, TopLevelBrowsingContext(0,2)`).
pub fn pct(s: &str) -> String {
    let mut out = String::with_capacity(s.len() * 3);
    for b in s.bytes() {
        match b {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'_' | b'.' | b'~' => {
                out.push(b as char)
            }
            _ => {
                let mut buf = [0u8; 1];
                buf[0] = b;
                let _ = write!(out, "%{}", hex_encode(&buf).to_uppercase());
            }
        }
    }
    out
}

pub struct WdClient {
    addr: String,
    timeout: Duration,
}

impl WdClient {
    pub fn new(addr: impl Into<String>) -> Self {
        Self { addr: addr.into(), timeout: Duration::from_secs(30) }
    }

    fn request(&self, method: &str, path: &str, body: Option<&Value>) -> WdResult<Value> {
        let body_str = body.map(|v| v.to_string()).unwrap_or_default();
        let host = self.addr.clone();
        let mut req = format!(
            "{method} {path} HTTP/1.1\r\nHost: {host}\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: {}\r\nConnection: close\r\n\r\n",
            body_str.len()
        );
        req.push_str(&body_str);

        let mut stream = TcpStream::connect(&self.addr).map_err(|e| WdError::Io(e.to_string()))?;
        stream
            .set_read_timeout(Some(self.timeout))
            .map_err(|e| WdError::Io(e.to_string()))?;
        stream.write_all(req.as_bytes()).map_err(|e| WdError::Io(e.to_string()))?;
        stream.flush().map_err(|e| WdError::Io(e.to_string()))?;

        let mut raw = Vec::new();
        stream.read_to_end(&mut raw).map_err(|e| WdError::Io(e.to_string()))?;

        // Split headers/body (servoshell replies with a single Content-Length;
        // tolerate chunked by falling back to "last {..}" scan).
        let split = find_headers_end(&raw).ok_or_else(|| WdError::Io("malformed http".into()))?;
        let head = String::from_utf8_lossy(&raw[..split]).to_ascii_lowercase();
        let body = &raw[split..];
        let payload: Vec<u8> = if head.contains("transfer-encoding: chunked") {
            dechunk(body)
        } else {
            body.to_vec()
        };
        let v: Value = serde_json::from_slice(&payload)
            .map_err(|e| WdError::Io(format!("bad json: {e}: {}", String::from_utf8_lossy(&payload[..payload.len().min(200)]))))?;
        // W3C responses wrap the payload in "value"; error responses carry
        // value.error.
        if let Some(err) = v.get("value").and_then(|v| v.get("error")).and_then(|v| v.as_str()) {
            return Err(WdError::Response(format!(
                "{err}: {}",
                v.get("value").and_then(|v| v.get("message")).and_then(|m| m.as_str()).unwrap_or("")
            )));
        }
        Ok(v.get("value").cloned().unwrap_or(Value::Null))
    }

    // ---- Session ----------------------------------------------------------

    /// Create a session; returns the sessionId. Servoshell allows exactly one
    /// session per process.
    pub fn new_session(&self) -> WdResult<String> {
        let v = self.request("POST", "/session", Some(&serde_json::json!({"capabilities": {"alwaysMatch": {}}})))?;
        v.get("sessionId")
            .and_then(|s| s.as_str())
            .map(|s| s.to_string())
            .ok_or_else(|| WdError::Response("no sessionId".into()))
    }

    pub fn delete_session(&self, sid: &str) {
        let _ = self.request("DELETE", &format!("/session/{sid}"), None);
    }

    pub fn status_ready(&self) -> bool {
        self.request("GET", "/status", None)
            .and_then(|v| {
                v.get("ready")
                    .and_then(|r| r.as_bool())
                    .ok_or(WdError::Response("no ready".into()))
            })
            .unwrap_or(false)
    }

    // ---- Navigation (per focused window handle) ---------------------------

    pub fn navigate(&self, sid: &str, url: &str) -> WdResult<()> {
        self.request("POST", &format!("/session/{sid}/url"), Some(&serde_json::json!({"url": url})))?;
        Ok(())
    }
    pub fn get_url(&self, sid: &str) -> WdResult<String> {
        Ok(self.request("GET", &format!("/session/{sid}/url"), None)?.as_str().unwrap_or_default().to_string())
    }
    pub fn get_title(&self, sid: &str) -> WdResult<String> {
        Ok(self.request("GET", &format!("/session/{sid}/title"), None)?.as_str().unwrap_or_default().to_string())
    }
    pub fn back(&self, sid: &str) -> WdResult<()> {
        self.request("POST", &format!("/session/{sid}/back"), Some(&serde_json::json!({})))?;
        Ok(())
    }
    pub fn forward(&self, sid: &str) -> WdResult<()> {
        self.request("POST", &format!("/session/{sid}/forward"), Some(&serde_json::json!({})))?;
        Ok(())
    }
    pub fn refresh(&self, sid: &str) -> WdResult<()> {
        self.request("POST", &format!("/session/{sid}/refresh"), Some(&serde_json::json!({})))?;
        Ok(())
    }

    // ---- Windows (Servo: each `type:"tab"` = one top-level browsing context)

    /// Handle of the currently focused window/context.
    pub fn focused_window(&self, sid: &str) -> WdResult<String> {
        Ok(self.request("GET", &format!("/session/{sid}/window"), None)?.as_str().unwrap_or_default().to_string())
    }
    pub fn window_handles(&self, sid: &str) -> WdResult<Vec<String>> {
        Ok(self
            .request("GET", &format!("/session/{sid}/window/handles"), None)?
            .as_array()
            .map(|a| a.iter().filter_map(|v| v.as_str().map(String::from)).collect())
            .unwrap_or_default())
    }
    pub fn new_window(&self, sid: &str, typ: &str) -> WdResult<String> {
        let v = self.request("POST", &format!("/session/{sid}/window/new"), Some(&serde_json::json!({"type": typ})))?;
        v.get("handle")
            .and_then(|h| h.as_str())
            .map(|s| s.to_string())
            .ok_or_else(|| WdError::Response("no window handle".into()))
    }
    /// Focus (raise) a window/context; subsequent nav/title/etc. apply to it.
    pub fn switch_window(&self, sid: &str, handle: &str) -> WdResult<()> {
        self.request("POST", &format!("/session/{sid}/window"), Some(&serde_json::json!({"handle": handle})))?;
        Ok(())
    }
    pub fn close_window(&self, sid: &str, handle: &str) -> WdResult<()> {
        self.request("DELETE", &format!("/session/{sid}/window/{}", pct(handle)), None)?;
        Ok(())
    }
    pub fn window_rect(&self, sid: &str) -> WdResult<(u32, u32)> {
        let v = self.request("POST", &format!("/session/{sid}/window/rect"), Some(&serde_json::json!({})))?;
        let w = v.get("width").and_then(|w| w.as_f64()).unwrap_or(1024.0);
        let h = v.get("height").and_then(|h| h.as_f64()).unwrap_or(740.0);
        Ok((w as u32, h as u32))
    }
    /// Resize the focused window's rendering viewport. Verified on servoshell
    /// 0.8.0 headless: innerWidth/innerHeight and screenshot dimensions both
    /// follow the requested rect exactly.
    pub fn set_window_rect(&self, sid: &str, width: u32, height: u32) -> WdResult<(u32, u32)> {
        let v = self.request(
            "POST",
            &format!("/session/{sid}/window/rect"),
            Some(&serde_json::json!({ "width": width, "height": height })),
        )?;
        let w = v.get("width").and_then(|w| w.as_f64()).unwrap_or(width as f64);
        let h = v.get("height").and_then(|h| h.as_f64()).unwrap_or(height as f64);
        Ok((w as u32, h as u32))
    }

    // ---- Content ----------------------------------------------------------

    /// Base64 PNG of the current viewport.
    pub fn screenshot(&self, sid: &str) -> WdResult<String> {
        Ok(self.request("GET", &format!("/session/{sid}/screenshot"), None)?.as_str().unwrap_or_default().to_string())
    }

    pub fn execute_sync(&self, sid: &str, script: &str, args: Vec<Value>) -> WdResult<Value> {
        self.request(
            "POST",
            &format!("/session/{sid}/execute/sync"),
            Some(&serde_json::json!({"script": script, "args": args})),
        )
    }

    // ---- Input -------------------------------------------------------------

    /// W3C pointer actions array (single mouse source).
    pub fn pointer_actions(&self, sid: &str, actions: Vec<Value>) -> WdResult<()> {
        self.request(
            "POST",
            &format!("/session/{sid}/actions"),
            Some(&serde_json::json!({"actions": [{
                "type": "pointer", "id": "sub-mouse",
                "parameters": {"pointerType": "mouse"},
                "actions": actions
            }]})),
        )?;
        Ok(())
    }

    /// W3C key actions array (single key source).
    pub fn key_actions(&self, sid: &str, actions: Vec<Value>) -> WdResult<()> {
        self.request(
            "POST",
            &format!("/session/{sid}/actions"),
            Some(&serde_json::json!({"actions": [{
                "type": "key", "id": "sub-key", "actions": actions
            }]})),
        )?;
        Ok(())
    }
}

fn find_headers_end(raw: &[u8]) -> Option<usize> {
    raw.windows(4).position(|w| w == b"\r\n\r\n").map(|p| p + 4)
}

fn dechunk(mut body: &[u8]) -> Vec<u8> {
    let mut out = Vec::new();
    loop {
        let nl = match body.windows(2).position(|w| w == b"\r\n") {
            Some(i) => i,
            None => break,
        };
        let len_line = std::str::from_utf8(&body[..nl]).unwrap_or("0").trim();
        let size = usize::from_str_radix(len_line, 16).unwrap_or(0);
        if size == 0 {
            break;
        }
        let start = nl + 2;
        if body.len() < start + size {
            break;
        }
        out.extend_from_slice(&body[start..start + size]);
        body = &body[(start + size + 2).min(body.len())..];
    }
    out
}
