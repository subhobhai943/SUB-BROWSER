//! CLI smoke test for the in-process `embed` backend (no GUI, no display):
//!   cargo run --release --features embed --bin embed-smoke -- [url]
//! Asserts: Servo boots in-process over SoftwareRenderingContext, navigation,
//! title fetch, native-surface screenshot > 10 KB, adblock JS eval executes,
//! back/forward/reload/close respond. Exits 0 on PASS.

use std::sync::Arc;
use std::time::{Duration, Instant};

use sub_browser::engine::embed::EmbedBackend;
use sub_browser::engine::EngineBackend;

fn main() {
    let url = std::env::args().nth(1).unwrap_or_else(|| "https://example.com/".to_string());
    println!("[embed-smoke] booting in-process servo (software surface)…");
    let t0 = Instant::now();
    let be = Arc::new(EmbedBackend::spawn("embed-smoke").expect("embed spawn"));
    println!("[embed-smoke] engine up in {:?}: {}", t0.elapsed(), be.describe());

    be.create_view("embed-1", &url).expect("create_view");
    println!("[embed-smoke] view created, loading {url}");

    let t1 = Instant::now();
    let mut title = String::new();
    let mut final_url = String::new();
    while t1.elapsed() < Duration::from_secs(90) {
        std::thread::sleep(Duration::from_millis(300));
        let u = be.url("embed-1").unwrap_or_default();
        let t = be.title("embed-1").unwrap_or_default();
        if u == final_url && !t.is_empty() {
            title = t;
            break;
        }
        final_url = u;
        title = t;
    }
    assert!(!title.is_empty(), "title never became available");
    println!("[embed-smoke] TITLE: {title:?} URL: {final_url:?} load={:?}", t1.elapsed());
    assert!(final_url.contains("example.com"), "unexpected final url {final_url}");

    // v2.0.1 stretched-display proof: resize the software surface + tabs and
    // assert native-surface screenshots come back at EXACTLY the requested
    // size (dims read from the PNG IHDR).
    for (w, h) in [(1024u32, 768u32), (1600u32, 900u32)] {
        be.set_viewport(w, h).expect("set_viewport");
        std::thread::sleep(Duration::from_millis(300));
        let shot = be.screenshot("embed-1").expect("resized screenshot");
        let pw = u32::from_be_bytes([shot[16], shot[17], shot[18], shot[19]]);
        let ph = u32::from_be_bytes([shot[20], shot[21], shot[22], shot[23]]);
        println!("[embed-smoke] resized viewport {w}x{h} -> surface capture {pw}x{ph}");
        assert_eq!((pw, ph), (w, h), "embed surface did not follow set_viewport");
        std::fs::write(format!("/tmp/sub201_embed_{w}.png"), &shot).ok();
    }

    // v2.0.1 homepage proof: bundled newtab data: URL renders in-process.
    be.navigate("embed-1", &sub_browser::newtab::newtab_url()).expect("newtab nav");
    std::thread::sleep(Duration::from_millis(1500));
    let t = be.title("embed-1").unwrap_or_default();
    println!("[embed-smoke] newtab title: {t:?}");
    let shot = be.screenshot("embed-1").expect("newtab screenshot");
    std::fs::write("/tmp/sub201_embed_newtab.png", &shot).ok();
    // restore a real page for the remaining traversal checks
    be.navigate("embed-1", &url).expect("renav");
    std::thread::sleep(Duration::from_millis(800));

    let ts = Instant::now();
    let shot = be.screenshot("embed-1").expect("screenshot");
    println!(
        "[embed-smoke] screenshot {} bytes from native surface in {:?}",
        shot.len(),
        ts.elapsed()
    );
    assert!(shot.len() > 10_000, "screenshot too small: {} bytes", shot.len());
    std::fs::write("/tmp/embed-smoke.png", &shot).ok();

    let blocked = be.inject_adblock("embed-1").expect("adblock js eval");
    println!("[embed-smoke] adblock injection OK ({} removals reported)", blocked.len());

    // Frame cadence on a dirty page: two screenshots of a CSS-animated page.
    be.navigate("embed-1", "https://servo.org/").expect("navigate servo.org");
    std::thread::sleep(Duration::from_secs(12));
    let ts = Instant::now();
    let mut n = 0usize;
    let mut bytes = 0usize;
    while ts.elapsed() < Duration::from_secs(10) {
        if let Ok(s) = be.screenshot("embed-1") {
            bytes += s.len();
            n += 1;
        }
    }
    if n > 0 {
        println!(
            "[embed-smoke] capture throughput on servo.org: {:.2} captures/s, avg {} bytes",
            n as f64 / ts.elapsed().as_secs_f64(),
            bytes / n
        );
    }

    be.back("embed-1").expect("back");
    std::thread::sleep(Duration::from_secs(4));
    be.forward("embed-1").expect("forward");
    be.reload("embed-1").expect("reload");
    be.focus("embed-1").expect("focus");
    be.click("embed-1", 100.0, 100.0).expect("click");
    be.scroll("embed-1", 0.0, 300.0).expect("scroll");
    be.key("embed-1", "a").expect("key");
    be.close_view("embed-1").expect("close_view");
    println!("[embed-smoke] traversal/input/close OK — PASS");
}
