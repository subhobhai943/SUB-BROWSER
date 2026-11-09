//! Bundled SUB new-tab page, served to the engine as a `data:` URL.
//!
//! The engine (headless servoshell / embed Servo) cannot render the Tauri
//! `tauri://localhost` UI origin, so the newtab ships as a standalone HTML
//! asset (`newtab.html`, dark #050505, dragon wordmark, search pill,
//! speed-dial tiles — the Android newtab ported to a bridge-free document:
//! plain `<a href>` tiles and a GET form, so navigation works natively in
//! any engine without the Tauri event bus). It is percent-encoded into a
//! `data:text/html;charset=utf-8,...` URL, which both backends accept as a
//! top-level navigation (verified against servoshell 0.8.0 WebDriver:
//! navigate → title → screenshot all succeed on a data: URL).

/// The engine-side newtab document (no Tauri bridge, no external assets).
pub const NEWTAB_HTML: &str = include_str!("../newtab.html");

/// `data:` URL carrying [`NEWTAB_HTML`] — usable as the default homepage
/// setting and the first-tab URL of a fresh profile.
pub fn newtab_url() -> String {
    let mut out = String::with_capacity(NEWTAB_HTML.len() * 3 + 32);
    out.push_str("data:text/html;charset=utf-8,");
    for b in NEWTAB_HTML.as_bytes() {
        match b {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'_' | b'.' | b'~' => {
                out.push(*b as char)
            }
            _ => out.push_str(&format!("%{b:02X}")),
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn newtab_url_shape() {
        let u = newtab_url();
        assert!(u.starts_with("data:text/html;charset=utf-8,%3C"));
        // Unreserved-safe encoding: a raw '<' or '"' must not survive.
        assert!(!u[30..].contains('<'));
        assert!(!u[30..].contains('"'));
        // Comfortably below any practical URL length limit.
        assert!(u.len() < 200_000, "data url too long: {}", u.len());
    }

    #[test]
    fn newtab_decodes_back() {
        let u = newtab_url();
        let body = &u["data:text/html;charset=utf-8,".len()..];
        // Percent-decode round-trip (naive decoder; sufficient for the test).
        let bytes = body.as_bytes();
        let mut out = Vec::new();
        let mut i = 0;
        while i < bytes.len() {
            if bytes[i] == b'%' {
                let hi = (bytes[i + 1] as char).to_digit(16).unwrap();
                let lo = (bytes[i + 2] as char).to_digit(16).unwrap();
                out.push((hi * 16 + lo) as u8);
                i += 3;
            } else {
                out.push(bytes[i]);
                i += 1;
            }
        }
        assert_eq!(String::from_utf8(out).unwrap(), NEWTAB_HTML);
    }
}
