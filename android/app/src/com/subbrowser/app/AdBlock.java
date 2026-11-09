package com.subbrowser.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.HashSet;
import java.util.Set;

/** Domain-suffix ad/tracker blocklist, loaded from assets/adblock_domains.txt. */
public final class AdBlock {
    private static final String PREFS = "adblock";
    private static final Set<String> DOMAINS = new HashSet<>();
    private static boolean loaded = false;

    private AdBlock() {}

    public static synchronized void load(Context ctx) {
        if (loaded) return;
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(ctx.getAssets().open("adblock_domains.txt")))) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty() && !line.startsWith("#")) DOMAINS.add(line);
            }
            loaded = true;
            Log.i("adblock", DOMAINS.size() + " rules loaded");
        } catch (Exception e) {
            Log.e("adblock", "failed to load rules", e);
        }
    }

    public static int ruleCount() { return DOMAINS.size(); }

    public static boolean matches(String host) {
        if (host == null || host.isEmpty()) return false;
        host = host.toLowerCase();
        if (DOMAINS.contains(host)) return true;
        int i = host.indexOf('.');
        while (i >= 0) {
            if (DOMAINS.contains(host.substring(i + 1))) return true;
            i = host.indexOf('.', i + 1);
        }
        return false;
    }

    // ---- persisted state ----

    public static boolean enabled(Context c) {
        return prefs(c).getBoolean("enabled", true); // default ON
    }

    public static void setEnabled(Context c, boolean v) {
        prefs(c).edit().putBoolean("enabled", v).apply();
    }

    /** blocked count resets per local day */
    public static int blockedCount(Context c) {
        return prefs(c).getInt("cnt_" + today(), 0);
    }

    public static void addBlocked(Context c) {
        String k = "cnt_" + today();
        int n = prefs(c).getInt(k, 0) + 1;
        prefs(c).edit().putInt(k, n).apply();
    }

    private static String today() {
        return new java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US)
                .format(new java.util.Date());
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
