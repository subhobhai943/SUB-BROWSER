package com.subbrowser.gecko;

import android.content.Context;
import android.content.SharedPreferences;

/** Prefs: custom home page, zoom percent. Incognito + download count live here too. */
public final class BrowserState {

    public static final String HOME_DEFAULT =
            "resource://android/assets/newtab.html";

    private static SharedPreferences p(Context c) {
        return c.getSharedPreferences("sub_browser", Context.MODE_PRIVATE);
    }

    public static String homeUrl(Context c) {
        String h = p(c).getString("home_url", null);
        return (h == null || h.isEmpty()) ? HOME_DEFAULT : h;
    }

    public static void setHomeUrl(Context c, String url) {
        p(c).edit().putString("home_url", url == null ? "" : url).apply();
    }

    public static int zoom(Context c) {
        int z = p(c).getInt("zoom_pct", 100);
        return (z >= 50 && z <= 200) ? z : 100;
    }

    public static void setZoom(Context c, int pct) {
        p(c).edit().putInt("zoom_pct", pct).apply();
    }

    public static int downloads(Context c) {
        return p(c).getInt("downloads", 0);
    }

    public static void addDownload(Context c) {
        p(c).edit().putInt("downloads", downloads(c) + 1).apply();
    }

    private BrowserState() { }
}
