package com.subbrowser.gecko;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

/** Plain android.database.sqlite store for bookmarks + browsing history. */
public class BrowserDB extends SQLiteOpenHelper {

    private static BrowserDB instance;

    public static synchronized BrowserDB get(Context c) {
        if (instance == null) instance = new BrowserDB(c.getApplicationContext());
        return instance;
    }

    private BrowserDB(Context c) { super(c, "sub-browser.db", null, 1); }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE bookmarks(" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "url TEXT NOT NULL UNIQUE," +
                "title TEXT," +
                "added_ms INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE history(" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "url TEXT NOT NULL," +
                "title TEXT," +
                "visited_ms INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX idx_hist_ms ON history(visited_ms DESC)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int o, int n) { }

    public static class Entry {
        public long id; public String url; public String title; public long ms;
        Entry(long id, String url, String title, long ms) {
            this.id = id; this.url = url; this.title = title; this.ms = ms;
        }
    }

    // ---- bookmarks ----

    public void addBookmark(String url, String title) {
        if (url == null || url.isEmpty()) return;
        ContentValues v = new ContentValues();
        v.put("url", url);
        v.put("title", title == null ? url : title);
        v.put("added_ms", System.currentTimeMillis());
        // REPLACE keeps uniqueness on repeated saves
        getWritableDatabase().insertWithOnConflict("bookmarks", null, v,
                SQLiteDatabase.CONFLICT_REPLACE);
    }

    public boolean isBookmarked(String url) {
        if (url == null) return false;
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT 1 FROM bookmarks WHERE url=? LIMIT 1", new String[]{url})) {
            return c.moveToFirst();
        }
    }

    public void deleteBookmark(long id) {
        getWritableDatabase().delete("bookmarks", "id=" + id, null);
    }

    public List<Entry> bookmarks() {
        List<Entry> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT id,url,title,added_ms FROM bookmarks "
                        + "ORDER BY added_ms DESC LIMIT 1000", null)) {
            while (c.moveToNext())
                out.add(new Entry(c.getLong(0), c.getString(1), c.getString(2), c.getLong(3)));
        }
        return out;
    }

    // ---- history (main-frame http(s), capped at 1000) ----

    private static final int HISTORY_CAP = 1000;

    public void addHistory(String url, String title) {
        if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))) return;
        SQLiteDatabase db = getWritableDatabase();
        ContentValues v = new ContentValues();
        v.put("url", url);
        v.put("title", title == null ? url : title);
        v.put("visited_ms", System.currentTimeMillis());
        db.insert("history", null, v);
        // trim: delete the oldest beyond the cap
        db.execSQL("DELETE FROM history WHERE id NOT IN "
                + "(SELECT id FROM history ORDER BY visited_ms DESC LIMIT " + HISTORY_CAP + ")");
    }

    public void updateHistoryTitle(String url, String title) {
        if (url == null || title == null || title.isEmpty()) return;
        SQLiteDatabase db = getWritableDatabase();
        db.execSQL("UPDATE history SET title=? WHERE url=? AND id=(SELECT MAX(id) FROM history WHERE url=?)",
                new Object[]{title, url, url});
    }

    public List<Entry> history() {
        List<Entry> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT id,url,title,visited_ms FROM history "
                        + "ORDER BY visited_ms DESC LIMIT " + HISTORY_CAP, null)) {
            while (c.moveToNext())
                out.add(new Entry(c.getLong(0), c.getString(1), c.getString(2), c.getLong(3)));
        }
        return out;
    }

    public void clearHistory() {
        getWritableDatabase().delete("history", null, null);
    }
}
