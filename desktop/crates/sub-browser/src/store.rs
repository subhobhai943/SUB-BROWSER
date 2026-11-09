//! Bookmarks / history / settings persistence — SQLite (rusqlite, bundled)
//! under the same per-profile dir as the engine profile:
//! `<config_dir>/sub-browser/store/<profile>/sub-browser.db`
//! so engine profile and shell store live side by side and switch together.

use std::path::PathBuf;

use rusqlite::{params, Connection};
use std::sync::Mutex;

/// `Connection` is `Send` but not `Sync`; a Mutex makes `Store` shareable
/// across the shell worker threads (SQLite calls here are tiny and rare).
pub struct Store {
    conn: Mutex<Connection>,
}

#[derive(Debug, Clone, serde::Serialize)]
pub struct Bookmark {
    pub id: i64,
    pub url: String,
    pub title: String,
    pub added_at: i64,
}

#[derive(Debug, Clone, serde::Serialize)]
pub struct HistoryEntry {
    pub id: i64,
    pub url: String,
    pub title: String,
    pub visited_at: i64,
}

fn now() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs() as i64)
        .unwrap_or(0)
}

impl Store {
    /// Open (creating if needed) the store for `profile`.
    pub fn open(profile: &str) -> Result<Self, String> {
        let dir = dirs::config_dir()
            .ok_or("no config dir")?
            .join("sub-browser")
            .join("store")
            .join(profile);
        Self::open_at(&dir.join("sub-browser.db"))
    }

    pub fn open_at(path: &PathBuf) -> Result<Self, String> {
        if let Some(d) = path.parent() {
            std::fs::create_dir_all(d).map_err(|e| e.to_string())?;
        }
        let conn = Connection::open(path).map_err(|e| e.to_string())?;
        conn.execute_batch(
            "PRAGMA journal_mode=WAL;
             CREATE TABLE IF NOT EXISTS bookmarks(
               id INTEGER PRIMARY KEY, url TEXT NOT NULL, title TEXT NOT NULL,
               added_at INTEGER NOT NULL, UNIQUE(url));
             CREATE TABLE IF NOT EXISTS history(
               id INTEGER PRIMARY KEY, url TEXT NOT NULL, title TEXT NOT NULL,
               visited_at INTEGER NOT NULL);
             CREATE INDEX IF NOT EXISTS ix_hist_time ON history(visited_at DESC);
             CREATE TABLE IF NOT EXISTS settings(key TEXT PRIMARY KEY, value TEXT NOT NULL);",
        )
        .map_err(|e| e.to_string())?;
        Ok(Self { conn: Mutex::new(conn) })
    }

    // ---- bookmarks ---------------------------------------------------------

    pub fn add_bookmark(&self, url: &str, title: &str) -> Result<i64, String> {
        self.conn.lock().unwrap()
            .execute(
                "INSERT INTO bookmarks(url,title,added_at) VALUES(?1,?2,?3)
                 ON CONFLICT(url) DO UPDATE SET title=excluded.title",
                params![url, title, now()],
            )
            .map_err(|e| e.to_string())?;
        Ok(self.conn.lock().unwrap().last_insert_rowid())
    }

    pub fn remove_bookmark(&self, url: &str) -> Result<(), String> {
        self.conn.lock().unwrap()
            .execute("DELETE FROM bookmarks WHERE url=?1", params![url])
            .map_err(|e| e.to_string())?;
        Ok(())
    }

    pub fn is_bookmarked(&self, url: &str) -> bool {
        self.conn.lock().unwrap()
            .query_row("SELECT 1 FROM bookmarks WHERE url=?1", params![url], |_| Ok(()))
            .is_ok()
    }

    pub fn list_bookmarks(&self) -> Vec<Bookmark> {
        let conn = match self.conn.lock() {
            Ok(c) => c,
            Err(_) => return vec![],
        };
        let mut stmt = match conn
            .prepare("SELECT id,url,title,added_at FROM bookmarks ORDER BY added_at DESC")
        {
            Ok(s) => s,
            Err(_) => return vec![],
        };
        stmt.query_map([], |r| {
            Ok(Bookmark {
                id: r.get(0)?,
                url: r.get(1)?,
                title: r.get(2)?,
                added_at: r.get(3)?,
            })
        })
        .map(|it| it.filter_map(Result::ok).collect())
        .unwrap_or_default()
    }

    // ---- history -------------------------------------------------------------

    /// Record a visit. Consecutive identical URLs collapse (last-wins).
    pub fn record_visit(&self, url: &str, title: &str) -> Result<(), String> {
        let last: Option<String> = self
            .conn
            .lock()
            .ok()
            .and_then(|c| c.query_row("SELECT url FROM history ORDER BY id DESC LIMIT 1", [], |r| r.get(0)).ok());
        if last.as_deref() == Some(url) {
            return Ok(());
        }
        self.conn.lock().unwrap()
            .execute(
                "INSERT INTO history(url,title,visited_at) VALUES(?1,?2,?3)",
                params![url, title, now()],
            )
            .map_err(|e| e.to_string())?;
        // Cap history at 5000 rows.
        self.conn.lock().unwrap()
            .execute(
                "DELETE FROM history WHERE id < (SELECT COALESCE(MAX(id),0)-5000 FROM history)",
                [],
            )
            .ok();
        Ok(())
    }

    pub fn list_history(&self, limit: i64) -> Vec<HistoryEntry> {
        let conn = match self.conn.lock() {
            Ok(c) => c,
            Err(_) => return vec![],
        };
        let mut stmt = match conn
            .prepare("SELECT id,url,title,visited_at FROM history ORDER BY id DESC LIMIT ?1")
        {
            Ok(s) => s,
            Err(_) => return vec![],
        };
        stmt.query_map(params![limit], |r| {
            Ok(HistoryEntry {
                id: r.get(0)?,
                url: r.get(1)?,
                title: r.get(2)?,
                visited_at: r.get(3)?,
            })
        })
        .map(|it| it.filter_map(Result::ok).collect())
        .unwrap_or_default()
    }

    pub fn clear_history(&self) -> Result<(), String> {
        self.conn.lock().unwrap().execute("DELETE FROM history", []).map_err(|e| e.to_string())?;
        Ok(())
    }

    // ---- settings --------------------------------------------------------------

    pub fn set_setting(&self, key: &str, value: &str) -> Result<(), String> {
        self.conn.lock().unwrap()
            .execute(
                "INSERT INTO settings(key,value) VALUES(?1,?2) ON CONFLICT(key) DO UPDATE SET value=excluded.value",
                params![key, value],
            )
            .map_err(|e| e.to_string())?;
        Ok(())
    }

    pub fn setting(&self, key: &str) -> Option<String> {
        self.conn.lock().unwrap()
            .query_row("SELECT value FROM settings WHERE key=?1", params![key], |r| r.get(0))
            .ok()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn bookmarks_history_settings() {
        let dir = std::env::temp_dir().join(format!("substore-{}", std::process::id()));
        let s = Store::open_at(&dir.join("t.db")).unwrap();
        assert_eq!(s.add_bookmark("https://a.test", "A").unwrap() > 0, true);
        s.add_bookmark("https://a.test", "A2").unwrap(); // upsert, no dup
        assert!(s.is_bookmarked("https://a.test"));
        assert_eq!(s.list_bookmarks().len(), 1);
        s.record_visit("https://a.test", "A").unwrap();
        s.record_visit("https://a.test", "A").unwrap(); // collapses
        s.record_visit("https://b.test", "B").unwrap();
        assert_eq!(s.list_history(10).len(), 2);
        s.set_setting("adblock", "off").unwrap();
        assert_eq!(s.setting("adblock").as_deref(), Some("off"));
        s.clear_history().unwrap();
        assert!(s.list_history(10).is_empty());
        let _ = std::fs::remove_dir_all(&dir);
    }
}
