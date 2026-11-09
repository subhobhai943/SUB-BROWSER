/*
 * SUB-BROWSER — GPL-3.0-or-later
 * Copyright (C) 2026 Subhadip Sarkar
 * See LICENSE at repository root.
 */
package com.subbrowser.app;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {
    static final String UA = " SUBBrowser/1.0 Android";
    static final int BLUE = 0xFF29ABE2, BG = 0xFF050505, CARD = 0xFF11161c;
    List<WebView> tabs = new ArrayList<>();
    int cur = -1;
    LinearLayout tabStrip; FrameLayout pageArea; EditText urlBar; TextView titleView;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        // tab strip
        HorizontalScrollView sv = new HorizontalScrollView(this);
        sv.setHorizontalScrollBarEnabled(false);
        tabStrip = new LinearLayout(this);
        tabStrip.setOrientation(LinearLayout.HORIZONTAL);
        tabStrip.setBackgroundColor(0xFF090d12);
        sv.addView(tabStrip);
        root.addView(sv, new LinearLayout.LayoutParams(-1, dp(44)));

        // toolbar
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(0xFF090d12);
        bar.setPadding(dp(6), 0, dp(6), 0);
        bar.addView(navBtn("←", v -> { WebView w = tab(); if (w != null && w.canGoBack()) w.goBack(); }));
        bar.addView(navBtn("→", v -> { WebView w = tab(); if (w != null && w.canGoForward()) w.goForward(); }));
        bar.addView(navBtn("⟳", v -> { WebView w = tab(); if (w != null) w.reload(); }));
        bar.addView(navBtn("⌂", v -> load("file:///android_asset/newtab.html")));
        urlBar = new EditText(this);
        urlBar.setSingleLine(true);
        urlBar.setHint("Search or enter URL");
        urlBar.setTextColor(Color.WHITE);
        urlBar.setHintTextColor(0xFF6b7683);
        urlBar.setBackgroundColor(CARD);
        urlBar.setPadding(dp(14), dp(8), dp(14), dp(8));
        urlBar.setTextSize(14);
        urlBar.setOnEditorActionListener((tv, a, e) -> { if (a == EditorInfo.IME_ACTION_GO) { submitBar(); return true; } return false; });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(40), 1f);
        lp.setMargins(dp(6), dp(5), dp(6), dp(5));
        bar.addView(urlBar, lp);
        bar.addView(navBtn("＋", v -> newTab()));
        root.addView(bar, new LinearLayout.LayoutParams(-1, dp(50)));

        // webview area
        pageArea = new FrameLayout(this);
        root.addView(pageArea, new LinearLayout.LayoutParams(-1, -1, 1f));

        setContentView(root);
        newTab();
    }

    TextView navBtn(String t, View.OnClickListener l) {
        TextView b = new TextView(this);
        b.setText(t); b.setTextColor(BLUE); b.setTextSize(20);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(14), 0, dp(14), 0);
        b.setOnClickListener(l);
        return b;
    }

    WebView tab() { return cur >= 0 && cur < tabs.size() ? tabs.get(cur) : null; }

    WebView newTab() {
        WebView w = new WebView(this);
        w.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView v, String u) { if (v == tab()) syncUi(); }
        });
        w.setBackgroundColor(BG);
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setUserAgentString(s.getUserAgentString() + UA);
        s.setSupportZoom(true); s.setBuiltInZoomControls(true); s.setDisplayZoomControls(false);
        FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(-1, -1);
        pageArea.addView(w, flp);
        w.setVisibility(View.GONE);
        tabs.add(w);
        cur = tabs.size() - 1;
        rebuildTabs();
        load("file:///android_asset/newtab.html");
        return w;
    }

    void load(String url) {
        WebView w = tab();
        if (w != null) w.loadUrl(url);
    }

    void submitBar() {
        String q = urlBar.getText().toString().trim();
        if (q.isEmpty()) return;
        hideKb();
        String url;
        if (q.startsWith("http://") || q.startsWith("https://")) url = q;
        else if (q.matches("[\\w\\-.]+(\\.[a-z]{2,})(:\\d+)?(/.*)?")) url = "https://" + q;
        else url = "https://www.google.com/search?q=" + android.net.Uri.encode(q);
        load(url);
    }

    void syncUi() {
        WebView w = tab();
        if (w == null) return;
        String u = w.getUrl() == null ? "" : w.getUrl();
        urlBar.setText(u.startsWith("file://") ? "" : u);
        String t = w.getTitle();
        rebuildTabTitle();
        if (t != null && !t.isEmpty() && !u.startsWith("file://")) titleView = null;
    }

    void rebuildTabs() {
        tabStrip.removeAllViews();
        for (int i = 0; i < tabs.size(); i++) {
            final int idx = i;
            WebView w = tabs.get(i);
            LinearLayout chip = new LinearLayout(this);
            chip.setOrientation(LinearLayout.HORIZONTAL);
            chip.setGravity(Gravity.CENTER_VERTICAL);
            chip.setBackgroundColor(i == cur ? CARD : BG);
            chip.setPadding(dp(12), 0, dp(8), 0);
            TextView t = new TextView(this);
            String tt = w.getTitle() == null ? "New tab" : w.getTitle();
            t.setText(tt.length() > 14 ? tt.substring(0, 14) + "…" : tt);
            t.setTextColor(i == cur ? BLUE : 0xFF8b949e);
            t.setTextSize(12);
            t.setOnClickListener(v -> selectTab(idx));
            chip.addView(t, new LinearLayout.LayoutParams(dp(110), -1));
            TextView x = new TextView(this);
            x.setText(" ✕"); x.setTextColor(0xFF6b7683); x.setTextSize(13);
            x.setOnClickListener(v -> closeTab(idx));
            chip.addView(x);
            LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(-2, dp(40));
            llp.setMargins(dp(4), dp(2), 0, dp(2));
            tabStrip.addView(chip, llp);
        }
        TextView plus = new TextView(this);
        plus.setText("  ＋  "); plus.setTextColor(BLUE); plus.setTextSize(16);
        plus.setGravity(Gravity.CENTER);
        plus.setOnClickListener(v -> newTab());
        tabStrip.addView(plus);
    }

    void rebuildTabTitle() { rebuildTabs(); }

    void selectTab(int i) {
        cur = i;
        for (int k = 0; k < tabs.size(); k++) tabs.get(k).setVisibility(k == i ? View.VISIBLE : View.GONE);
        rebuildTabs(); syncUi();
    }

    void closeTab(int i) {
        WebView w = tabs.remove(i);
        pageArea.removeView(w);
        w.destroy();
        if (tabs.isEmpty()) { newTab(); return; }
        if (cur >= tabs.size()) cur = tabs.size() - 1;
        selectTab(cur);
    }

    void hideKb() {
        InputMethodManager im = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        im.hideSoftInputFromWindow(urlBar.getWindowToken(), 0);
    }

    @Override public boolean onKeyDown(int k, KeyEvent e) {
        WebView w = tab();
        if (k == KeyEvent.KEYCODE_BACK && w != null && w.canGoBack()) { w.goBack(); return true; }
        return super.onKeyDown(k, e);
    }

    int dp(float v) { return Math.round(getResources().getDisplayMetrics().density * v); }
}
