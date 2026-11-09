package com.subbrowser.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {
    static final String UA = " SUBBrowser/1.0 Android";
    static final String HOME = "file:///android_asset/newtab.html";
    static final int BLUE = 0xFF29ABE2, BG = 0xFF050505, BAR = 0xFF0C0F14;
    static final int DISABLED = 0xFF3a4550, PILL = 0xFF12181f, PILL_STROKE = 0xFF1e2a35;

    List<WebView> tabs = new ArrayList<>();
    int cur = -1;
    LinearLayout tabStrip; FrameLayout pageArea; EditText urlBar;
    ImageView backBtn, fwdBtn; TextView shieldBadge;
    Typeface tfBold, tfSemi;
    boolean pillFocused = false;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        AdBlock.load(this);
        try {
            tfBold = Typeface.createFromAsset(getAssets(), "fonts/Poppins-Bold.ttf");
            tfSemi = Typeface.createFromAsset(getAssets(), "fonts/Poppins-ExtraBold.ttf");
        } catch (Exception e) { tfBold = tfSemi = Typeface.DEFAULT_BOLD; }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        // --- row 1: tab chip strip ---
        HorizontalScrollView sv = new HorizontalScrollView(this);
        sv.setHorizontalScrollBarEnabled(false);
        sv.setBackgroundColor(BAR);
        tabStrip = new LinearLayout(this);
        tabStrip.setOrientation(LinearLayout.HORIZONTAL);
        tabStrip.setGravity(Gravity.CENTER_VERTICAL);
        tabStrip.setPadding(dp(8), dp(6), dp(8), dp(6));
        sv.addView(tabStrip);
        root.addView(sv, new LinearLayout.LayoutParams(-1, dp(48)));

        // --- row 2: logo + icons + pill bar ---
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(BAR);
        bar.setPadding(dp(6), dp(4), dp(8), dp(10));

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.dragon_mini);
        logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        logo.setPadding(dp(4), dp(4), dp(4), dp(4));
        logo.setBackgroundResource(R.drawable.icon_btn_bg);
        logo.setOnClickListener(v -> load(HOME));
        bar.addView(logo, new LinearLayout.LayoutParams(dp(28), dp(28)));

        backBtn = iconButton("ic_back", v -> { WebView w = tab(); if (w != null && w.canGoBack()) w.goBack(); });
        fwdBtn = iconButton("ic_forward", v -> { WebView w = tab(); if (w != null && w.canGoForward()) w.goForward(); });
        ImageView refresh = iconButton("ic_refresh", v -> { WebView w = tab(); if (w != null) w.reload(); });
        ImageView home = iconButton("ic_home", v -> load(HOME));
        bar.addView(backBtn); bar.addView(fwdBtn); bar.addView(refresh); bar.addView(home);

        // pill container: search icon + edit text
        LinearLayout pill = new LinearLayout(this);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setPadding(dp(14), 0, dp(6), 0);
        pill.addView(iconInline("ic_search", 0xFF48606e));
        urlBar = new EditText(this);
        urlBar.setBackground(null);
        urlBar.setHint("Search or enter URL");
        urlBar.setTextColor(Color.WHITE);
        urlBar.setHintTextColor(0xFF48606e);
        urlBar.setSingleLine(true);
        urlBar.setTextSize(14);
        urlBar.setTypeface(tfBold);
        urlBar.setImeOptions(EditorInfo.IME_ACTION_GO);
        urlBar.setPadding(dp(10), 0, dp(6), 0);
        urlBar.setOnEditorActionListener((tv, a, e) -> {
            if (a == EditorInfo.IME_ACTION_GO) { submitBar(); return true; }
            return false;
        });
        urlBar.setOnFocusChangeListener((v, f) -> { pillFocused = f; stylePill(pill, f); });
        pill.addView(urlBar, new LinearLayout.LayoutParams(0, dp(44), 1f));
        stylePill(pill, false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(44), 1f);
        lp.setMargins(dp(8), 0, dp(4), 0);
        bar.addView(pill, lp);

        ImageView plus = new ImageView(this);
        plus.setImageResource(R.drawable.ic_add);
        plus.setBackgroundResource(R.drawable.plus_btn_bg);
        plus.setPadding(dp(9), dp(9), dp(9), dp(9));
        plus.setOnClickListener(v -> newTab());
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(dp(40), dp(40));
        plp.setMargins(dp(4), 0, dp(0), 0);
        bar.addView(plus, plp);

        // shield badge with blocked-count overlay
        FrameLayout shieldWrap = new FrameLayout(this);
        ImageView shield = new ImageView(this);
        shield.setImageResource(R.drawable.ic_shield);
        shield.setBackgroundResource(R.drawable.icon_btn_bg);
        shield.setPadding(dp(8), dp(8), dp(8), dp(8));
        shield.setOnClickListener(v -> showAdblockDialog());
        shieldWrap.addView(shield, new FrameLayout.LayoutParams(dp(40), dp(40)));
        shieldBadge = new TextView(this);
        shieldBadge.setTextColor(Color.WHITE);
        shieldBadge.setTextSize(9);
        shieldBadge.setTypeface(tfBold);
        shieldBadge.setGravity(Gravity.CENTER);
        GradientDrawable badgeBg = new GradientDrawable();
        badgeBg.setShape(GradientDrawable.OVAL);
        badgeBg.setColor(BLUE);
        shieldBadge.setBackground(badgeBg);
        FrameLayout.LayoutParams bfp = new FrameLayout.LayoutParams(dp(18), dp(18));
        bfp.gravity = Gravity.TOP | Gravity.END;
        shieldWrap.addView(shieldBadge, bfp);
        bar.addView(shieldWrap, new LinearLayout.LayoutParams(dp(40), dp(40)));

        root.addView(bar, new LinearLayout.LayoutParams(-1, -2));

        pageArea = new FrameLayout(this);
        root.addView(pageArea, new LinearLayout.LayoutParams(-1, -1, 1f));

        setContentView(root);
        updateShield();
        newTab();
    }

    void stylePill(LinearLayout pill, boolean focused) {
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(dp(22));
        bg.setColor(PILL);
        bg.setStroke(dp(focused ? 2 : 1), focused ? BLUE : PILL_STROKE);
        pill.setBackground(bg);
        pill.setElevation(dp(focused ? 6 : 0));
    }

    private void updateShield() {
        runOnUiThread(() -> {
            int n = AdBlock.blockedCount(this);
            if (n > 0 && AdBlock.enabled(this)) {
                shieldBadge.setText(String.valueOf(n));
                shieldBadge.setVisibility(View.VISIBLE);
            } else shieldBadge.setVisibility(View.GONE);
        });
    }

    private void showAdblockDialog() {
        final boolean was = AdBlock.enabled(this);
        String msg = AdBlock.ruleCount() + " rules loaded\n\n"
                + AdBlock.blockedCount(this) + " blocked today\n\n"
                + "Status: " + (was ? "ON" : "OFF");
        new AlertDialog.Builder(this)
            .setTitle("Adblocker")
            .setMessage(msg)
            .setPositiveButton(was ? "Turn OFF" : "Turn ON", (d, w) -> {
                AdBlock.setEnabled(this, !was);
                Toast.makeText(this, "Adblocker " + (!was ? "ON" : "OFF"),
                        Toast.LENGTH_SHORT).show();
                updateShield();
            })
            .setNegativeButton("Close", null)
            .show();
    }

    private WebResourceResponse blocked() {
        // 204 No Content, empty body
        return new WebResourceResponse(null, null, new java.io.ByteArrayInputStream(new byte[0]));
    }

    ImageView iconButton(String name, android.view.View.OnClickListener l) {
        ImageView iv = new ImageView(this);
        int res = getResources().getIdentifier(name, "drawable", getPackageName());
        iv.setImageResource(res);
        iv.setBackgroundResource(R.drawable.icon_btn_bg);
        iv.setPadding(dp(8), dp(8), dp(8), dp(8));
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iv.setOnClickListener(l);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(40), dp(40));
        p.setMargins(dp(1), 0, dp(1), 0);
        iv.setLayoutParams(p);
        return iv;
    }

    ImageView iconInline(String name, int tint) {
        ImageView iv = new ImageView(this);
        int res = getResources().getIdentifier(name, "drawable", getPackageName());
        iv.setImageResource(res);
        iv.setColorFilter(tint);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(18), dp(18));
        iv.setLayoutParams(p);
        return iv;
    }

    void updateNav() {
        WebView w = tab();
        backBtn.setColorFilter(w != null && w.canGoBack() ? BLUE : DISABLED);
        fwdBtn.setColorFilter(w != null && w.canGoForward() ? BLUE : DISABLED);
    }

    WebView tab() { return cur >= 0 && cur < tabs.size() ? tabs.get(cur) : null; }

    WebView newTab() {
        WebView w = new WebView(this);
        w.setWebChromeClient(new android.webkit.WebChromeClient() {
            @Override public boolean onConsoleMessage(android.webkit.ConsoleMessage m) {
                android.util.Log.w("WBConsole", m.message() + " @" + m.lineNumber());
                return true;
            }
        });
        w.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest req) {
                if (AdBlock.enabled(MainActivity.this) && !req.isForMainFrame()) {
                    String host = req.getUrl().getHost();
                    if (host != null && AdBlock.matches(host)) {
                        AdBlock.addBlocked(MainActivity.this);
                        android.util.Log.i("adblock", "blocked " + host);
                        updateShield();
                        return blocked();
                    }
                }
                return null;
            }
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) {
                // user-typed navigation is handled by submitBar/load; let normal link taps load in place
                return false;
            }
            @Override public void onPageFinished(WebView v, String u) {
                android.util.Log.w("WBConsole", "pageFinished " + u);
                if (v == tab()) { syncUi(); updateNav(); }
            }
            @Override public void onReceivedError(WebView v, int f, String d, String u2) {
                android.util.Log.e("WBConsole", "ERR " + d + " " + u2);
            }
            @Override public void doUpdateVisitedHistory(WebView v, String u, boolean r) {
                if (v == tab()) { syncUi(); updateNav(); }
            }
        });
        w.setBackgroundColor(BG);
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setUserAgentString(s.getUserAgentString() + UA);
        s.setSupportZoom(true); s.setBuiltInZoomControls(true); s.setDisplayZoomControls(false);
        pageArea.addView(w, new FrameLayout.LayoutParams(-1, -1));
        w.setVisibility(View.GONE);
        tabs.add(w);
        cur = tabs.size() - 1;
        for (int k = 0; k < tabs.size(); k++) tabs.get(k).setVisibility(k == cur ? View.VISIBLE : View.GONE);
        rebuildTabs();
        load(HOME);
        return w;
    }

    void load(String url) { WebView w = tab(); if (w != null) w.loadUrl(url); }

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
        rebuildTabs();
    }

    void rebuildTabs() {
        tabStrip.removeAllViews();
        for (int i = 0; i < tabs.size(); i++) {
            final int idx = i;
            WebView w = tabs.get(i);
            LinearLayout chip = new LinearLayout(this);
            chip.setOrientation(LinearLayout.HORIZONTAL);
            chip.setGravity(Gravity.CENTER_VERTICAL);
            chip.setPadding(dp(14), 0, dp(8), 0);
            GradientDrawable cb = new GradientDrawable();
            cb.setCornerRadius(dp(10));
            if (i == cur) {
                cb.setColor(0xFF16202a);
                cb.setStroke(dp(1), 0xFF2a3a48);
            } else {
                cb.setColor(0x00000000);
                cb.setStroke(dp(1), 0xFF141c24);
            }
            chip.setBackground(cb);
            if (i == cur) chip.setElevation(dp(3));
            TextView t = new TextView(this);
            String tt = w.getTitle();
            if (tt == null || tt.isEmpty() || w.getUrl() != null && w.getUrl().startsWith("file://")) tt = "New tab";
            t.setText(tt.length() > 12 ? tt.substring(0, 12) + "…" : tt);
            t.setTextColor(i == cur ? BLUE : 0xFF66788a);
            t.setTextSize(12);
            t.setTypeface(tfBold);
            t.setOnClickListener(v -> selectTab(idx));
            chip.addView(t, new LinearLayout.LayoutParams(dp(96), -1));
            ImageView x = new ImageView(this);
            x.setImageResource(R.drawable.ic_close);
            x.setColorFilter(i == cur ? BLUE : 0xFF44525e);
            x.setPadding(dp(6), dp(6), dp(6), dp(6));
            x.setOnClickListener(v -> closeTab(idx));
            chip.addView(x, new LinearLayout.LayoutParams(dp(24), dp(24)));
            LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(-2, dp(36));
            llp.setMargins(dp(5), 0, dp(0), 0);
            tabStrip.addView(chip, llp);
        }
        ImageView plus = new ImageView(this);
        plus.setImageResource(R.drawable.ic_add);
        plus.setBackgroundResource(R.drawable.plus_btn_bg);
        plus.setPadding(dp(8), dp(8), dp(8), dp(8));
        plus.setOnClickListener(v -> newTab());
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(dp(36), dp(36));
        plp.setMargins(dp(8), 0, dp(0), 0);
        tabStrip.addView(plus, plp);
    }

    void selectTab(int i) {
        cur = i;
        for (int k = 0; k < tabs.size(); k++) tabs.get(k).setVisibility(k == i ? View.VISIBLE : View.GONE);
        rebuildTabs(); syncUi(); updateNav();
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
