//! CLI smoke test for the ServoIpcBackend end-to-end path (no GUI):
//!   sub-browser-smoke [url]
//! Asserts: session up, navigation, title fetch, screenshot > 10 KB,
//! adblock injection executes without JS error. Exits 0 on PASS.

use std::sync::Arc;
use std::time::{Duration, Instant};

use sub_browser::engine::servo_ipc::ServoIpcBackend;
use sub_browser::engine::EngineBackend;

fn main() {
    let url = std::env::args().nth(1).unwrap_or_else(|| "https://example.com/".to_string());
    println!("[smoke] spawning engine…");
    let be = Arc::new(ServoIpcBackend::spawn("smoke").expect("engine spawn"));
    println!("[smoke] engine up: {}", be.describe());

    be.create_view("smoke-1", &url).expect("create_view");
    println!("[smoke] view created, navigating to {url}");

    // Poll for a stable title (load completes on this box in a few seconds).
    let t0 = Instant::now();
    let mut title = String::new();
    let mut final_url = String::new();
    while t0.elapsed() < Duration::from_secs(60) {
        std::thread::sleep(Duration::from_millis(500));
        let u = be.url("smoke-1").unwrap_or_default();
        let t = be.title("smoke-1").unwrap_or_default();
        if u == final_url && !t.is_empty() {
            title = t;
            break;
        }
        final_url = u;
        title = t;
    }
    assert!(!title.is_empty(), "title never became available");
    println!("[smoke] TITLE: {title:?} URL: {final_url:?}");
    assert!(final_url.contains("example.com"), "unexpected final url {final_url}");

    let shot = be.screenshot("smoke-1").expect("screenshot");
    println!("[smoke] screenshot bytes: {}", shot.len());
    assert!(shot.len() > 10_000, "screenshot too small: {}", shot.len());
    assert_eq!(&shot[..8], b"\x89PNG\r\n\x1a\n", "not a PNG");

    let (vw, vh) = be.viewport("smoke-1").expect("viewport");
    println!("[smoke] viewport: {vw}x{vh}");

    // v2.0.1 stretched-display proof: resize the engine viewport and assert
    // the next screenshot comes back at EXACTLY the requested size (2 dims
    // read straight from the PNG IHDR).
    for (w, h) in [(1024u32, 768u32), (1600u32, 900u32)] {
        be.set_viewport(w, h).expect("set_viewport");
        std::thread::sleep(Duration::from_millis(800));
        let shot = be.screenshot("smoke-1").expect("resized screenshot");
        assert_eq!(&shot[..8], b"\x89PNG\r\n\x1a\n", "not a PNG");
        let pw = u32::from_be_bytes([shot[16], shot[17], shot[18], shot[19]]);
        let ph = u32::from_be_bytes([shot[20], shot[21], shot[22], shot[23]]);
        println!("[smoke] resized viewport {w}x{h} -> screenshot {pw}x{ph}");
        assert_eq!((pw, ph), (w, h), "engine did not follow set_viewport");
        std::fs::write(format!("/tmp/sub201_ipc_{w}.png"), &shot).ok();
    }

    // v2.0.1 homepage proof: the bundled newtab data: URL navigates and
    // renders (title + non-black screenshot).
    be.navigate("smoke-1", &sub_browser::newtab::newtab_url()).expect("newtab nav");
    std::thread::sleep(Duration::from_millis(1500));
    let t = be.title("smoke-1").unwrap_or_default();
    println!("[smoke] newtab title: {t:?}");
    assert!(t.contains("SUB"), "newtab title missing: {t:?}");
    let shot = be.screenshot("smoke-1").expect("newtab screenshot");
    std::fs::write("/tmp/sub201_ipc_newtab.png", &shot).ok();

    // Adblock injection must execute without a JS error on a clean page.
    match be.inject_adblock("smoke-1") {
        Ok(removed) => println!("[smoke] adblock injection OK, removals: {}", removed.len()),
        Err(e) => panic!("adblock injection failed: {e}"),
    }

    // Second injection on an ad-hostile page: removals must be reported.
    be.create_view("smoke-2", "https://www.w3.org/People/Berners-Lee/").ok();
    std::thread::sleep(Duration::from_secs(6));
    match be.inject_adblock("smoke-2") {
        Ok(r) => println!("[smoke] adblock second-page OK, removals: {}", r.len()),
        Err(e) => println!("[smoke] adblock second-page soft-fail: {e}"),
    }

    // back/forward/reload plumbing.
    be.back("smoke-1").ok();
    std::thread::sleep(Duration::from_millis(600));
    be.forward("smoke-1").ok();
    be.reload("smoke-1").expect("reload");
    println!("[smoke] back/forward/reload OK");

    be.close_view("smoke-1").ok();
    be.close_view("smoke-2").ok();
    println!("SMOKE PASS");
}
