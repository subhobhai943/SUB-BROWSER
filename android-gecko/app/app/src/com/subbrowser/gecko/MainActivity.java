package com.subbrowser.gecko;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
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
import android.widget.Toast;

import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoRuntimeSettings;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoSessionSettings;
import org.mozilla.geckoview.GeckoView;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {

    private static final String HOME = "resource://android/assets/newtab.html";
    private static final int MAX_TABS = 3;
    private static final String UA =
        "Mozilla/5.0 (Android 14; Mobile; rv:159.0) Gecko/159.0 Firefox/159.0 SUBBrowser/1.0";

    private static GeckoRuntime runtime;

    private GeckoView geckoView;
    private EditText urlBar;
    private ImageView backBtn, fwdBtn;
    private LinearLayout tabStrip;
    private final List<GeckoSession> tabs = new ArrayList<>();
    private int current = -1;
    private Typeface tfBold;
    private boolean urlEditedByUser = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        try {
            tfBold = Typeface.createFromAsset(getAssets(), "fonts/Poppins-Bold.ttf");
        } catch (Exception e) { tfBold = Typeface.DEFAULT_BOLD; }

        if (runtime == null) {
            runtime = GeckoRuntime.create(getApplication(),
                new GeckoRuntimeSettings.Builder().build());
        }

        int bg = Color.parseColor("#050505");
        int bar = Color.parseColor("#0C0F14");
        int accent = Color.parseColor("#29ABE2");
        final float dp = getResources().getDisplayMetrics().density;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(bg);

        // ---- row 1: tab chip strip ----
        HorizontalScrollView sv = new HorizontalScrollView(this);
        sv.setHorizontalScrollBarEnabled(false);
        sv.setBackgroundColor(bar);
        tabStrip = new LinearLayout(this);
        tabStrip.setOrientation(LinearLayout.HORIZONTAL);
        tabStrip.setGravity(Gravity.CENTER_VERTICAL);
        tabStrip.setPadding(dp(8), dp(6), dp(8), dp(6));
        sv.addView(tabStrip);
        root.addView(sv, new LinearLayout.LayoutParams(-1, dp(48 * dp)));


        // ---- row 2: logo + icons + pill ----
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundColor(bar);
        row.setPadding(dp(6 * dp), dp(4 * dp), dp(8 * dp), dp(10 * dp));

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.dragon_mini);
        logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        logo.setPadding(dp(4 * dp), dp(4 * dp), dp(4 * dp), dp(4 * dp));
        logo.setBackgroundResource(R.drawable.icon_btn_bg);
        logo.setOnClickListener(v -> load(HOME));
        row.addView(logo, lp(dp(28 * dp), dp(28 * dp), 0, 0));

        backBtn = iconButton(R.drawable.ic_back,
                v -> { GeckoSession s = session(); if (s != null) s.goBack(); });
        fwdBtn = iconButton(R.drawable.ic_forward,
                v -> { GeckoSession s = session(); if (s != null) s.goForward(); });
        ImageView refresh = iconButton(R.drawable.ic_refresh,
                v -> { GeckoSession s = session(); if (s != null) s.reload(); });
        ImageView home = iconButton(R.drawable.ic_home, v -> load(HOME));
        row.addView(backBtn); row.addView(fwdBtn); row.addView(refresh); row.addView(home);

        final LinearLayout pill = new LinearLayout(this);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setPadding(dp(14 * dp), 0, dp(6 * dp), 0);
        pill.addView(iconInline(R.drawable.ic_search, Color.parseColor("#48606e"), dp));

        urlBar = new EditText(this);
        urlBar.setBackground(null);
        urlBar.setHint("Search or enter URL");
        urlBar.setTextColor(Color.WHITE);
        urlBar.setHintTextColor(Color.parseColor("#48606e"));
        urlBar.setInputType(InputType.TYPE_TEXT_VARIATION_URI
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        urlBar.setSingleLine(true);
        urlBar.setImeOptions(EditorInfo.IME_ACTION_GO);
        urlBar.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        urlBar.setTypeface(tfBold);
        urlBar.setPadding(dp(10 * dp), 0, dp(6 * dp), 0);
        urlBar.setOnFocusChangeListener((v, f) -> stylePill(pill, f, dp));
        urlBar.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_GO
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                        && event.getAction() == KeyEvent.ACTION_DOWN)) {
                navigate(urlBar.getText().toString());
                return true;
            }
            return false;
        });
        pill.addView(urlBar, new LinearLayout.LayoutParams(0, dp(44 * dp), 1f));
        stylePill(pill, false, dp);
        row.addView(pill, lp(0, dp(44 * dp), 1f, dp(8 * dp)));

        ImageView plus = new ImageView(this);
        plus.setImageResource(R.drawable.ic_add);
        plus.setBackgroundResource(R.drawable.plus_btn_bg);
        plus.setPadding(dp(9 * dp), dp(9 * dp), dp(9 * dp), dp(9 * dp));
        plus.setOnClickListener(v -> newTab());
        row.addView(plus, lp(dp(40 * dp), dp(40 * dp), 0, dp(4 * dp)));

        root.addView(row, new LinearLayout.LayoutParams(-1, -2));

        FrameLayout content = new FrameLayout(this);
        geckoView = new GeckoView(this);
        geckoView.setBackgroundColor(bg);
        content.addView(geckoView, new FrameLayout.LayoutParams(-1, -1));
        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1f));

        setContentView(root);
        newTab();
    }

    private int dp(float px) { return (int) (px + 0.5f); }

    private static LinearLayout.LayoutParams lp(int w, int h, float weight, int marginL) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h, weight);
        p.setMarginStart(marginL);
        return p;
    }

    private void stylePill(LinearLayout pill, boolean focused, float dp) {
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(dp(22 * dp));
        bg.setColor(Color.parseColor("#12181f"));
        bg.setStroke(dp((focused ? 2 : 1) * dp),
                focused ? Color.parseColor("#29ABE2") : Color.parseColor("#1e2a35"));
        pill.setBackground(bg);
        pill.setElevation(dp(focused ? 6 * dp : 0));
    }

    private ImageView iconButton(int res, View.OnClickListener l) {
        ImageView iv = new ImageView(this);
        iv.setImageResource(res);
        iv.setColorFilter(Color.parseColor("#29ABE2"));
        iv.setBackgroundResource(R.drawable.icon_btn_bg);
        float dp = getResources().getDisplayMetrics().density;
        iv.setPadding(dp(8 * dp), dp(8 * dp), dp(8 * dp), dp(8 * dp));
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iv.setOnClickListener(l);
        LinearLayout.LayoutParams p = lp(dp(40 * dp), dp(40 * dp), 0, dp(1 * dp));
        p.setMarginEnd(dp(1 * dp));
        iv.setLayoutParams(p);
        return iv;
    }

    private ImageView iconInline(int res, int tint, float dp) {
        ImageView iv = new ImageView(this);
        iv.setImageResource(res);
        iv.setColorFilter(tint);
        iv.setLayoutParams(new LinearLayout.LayoutParams(dp(18 * dp), dp(18 * dp)));
        return iv;
    }

    private void updateNav(boolean canBack, boolean canFwd) {
        int on = Color.parseColor("#29ABE2");
        int off = Color.parseColor("#3a4550");
        backBtn.setColorFilter(canBack ? on : off);
        fwdBtn.setColorFilter(canFwd ? on : off);
    }

    // ---------------- tabs ----------------

    private GeckoSession session() {
        return (current >= 0 && current < tabs.size()) ? tabs.get(current) : null;
    }

    private GeckoSessionSettings tabSettings() {
        return new GeckoSessionSettings.Builder()
                .usePrivateMode(false)
                .userAgentOverride(UA)
                .build();
    }

    private GeckoSession makeTab() {
        GeckoSession s = new GeckoSession(tabSettings());
        s.setContentDelegate(new ContentDelegate());
        s.setProgressDelegate(new ProgressDelegate());
        s.setNavigationDelegate(new NavigationDelegate());
        s.open(runtime);
        return s;
    }

    private void newTab() {
        if (tabs.size() >= MAX_TABS) {
            Toast.makeText(this, "Max " + MAX_TABS + " tabs", Toast.LENGTH_SHORT).show();
            return;
        }
        tabs.add(makeTab());
        current = tabs.size() - 1;
        geckoView.setSession(session());
        updateNav(false, false);
        rebuildTabs();
        load(HOME);
    }

    private void closeCurrentTab() {
        GeckoSession s = session();
        if (s != null) s.close();
        if (current >= 0) tabs.remove(current);
        if (tabs.isEmpty()) { current = -1; geckoView.setSession(null); newTab(); }
        else {
            if (current >= tabs.size()) current = tabs.size() - 1;
            geckoView.setSession(session());
        }
        rebuildTabs();
    }

    private void rebuildTabs() {
        runOnUiThread(() -> {
            tabStrip.removeAllViews();
            float dp = getResources().getDisplayMetrics().density;
            for (int i = 0; i < tabs.size(); i++) {
                final int idx = i;
                LinearLayout chip = new LinearLayout(this);
                chip.setOrientation(LinearLayout.HORIZONTAL);
                chip.setGravity(Gravity.CENTER_VERTICAL);
                chip.setPadding(dp(14 * dp), 0, dp(8 * dp), 0);
                GradientDrawable cb = new GradientDrawable();
                cb.setCornerRadius(dp(10 * dp));
                if (i == current) {
                    cb.setColor(Color.parseColor("#16202a"));
                    cb.setStroke(dp(dp), Color.parseColor("#2a3a48"));
                } else {
                    cb.setColor(0x00000000);
                    cb.setStroke(dp(dp), Color.parseColor("#141c24"));
                }
                chip.setBackground(cb);
                if (i == current) chip.setElevation(dp(3 * dp));

                TextView t = new TextView(this);
                t.setText("Tab " + (i + 1));
                t.setTextColor(i == current ? Color.parseColor("#29ABE2")
                        : Color.parseColor("#66788a"));
                t.setTextSize(12);
                t.setTypeface(tfBold);
                t.setOnClickListener(v -> selectTab(idx));
                chip.addView(t, new LinearLayout.LayoutParams(dp(64 * dp), -1));

                ImageView x = new ImageView(this);
                x.setImageResource(R.drawable.ic_close);
                x.setColorFilter(i == current ? Color.parseColor("#29ABE2")
                        : Color.parseColor("#44525e"));
                x.setPadding(dp(6 * dp), dp(6 * dp), dp(6 * dp), dp(6 * dp));
                x.setOnClickListener(v -> { if (idx == current) closeCurrentTab();
                    else { GeckoSession s2 = tabs.remove(idx); s2.close();
                           if (idx <= current) current--;
                           geckoView.setSession(session()); rebuildTabs(); } });
                chip.addView(x, new LinearLayout.LayoutParams(dp(24 * dp), dp(24 * dp)));

                LinearLayout.LayoutParams llp = lp(-2, dp(36 * dp), 0, dp(5 * dp));
                tabStrip.addView(chip, llp);
            }
            ImageView plus = new ImageView(this);
            plus.setImageResource(R.drawable.ic_add);
            plus.setBackgroundResource(R.drawable.plus_btn_bg);
            plus.setPadding(dp(8 * dp), dp(8 * dp), dp(8 * dp), dp(8 * dp));
            plus.setOnClickListener(v -> newTab());
            tabStrip.addView(plus, lp(dp(36 * dp), dp(36 * dp), 0, dp(8 * dp)));
        });
    }

    private void selectTab(int i) {
        current = i;
        geckoView.setSession(session());
        updateNav(false, false);
        rebuildTabs();
    }

    private void load(String uri) {
        GeckoSession s = session();
        if (s != null) s.loadUri(uri);
    }

    private void navigate(String raw) {
        String input = raw == null ? "" : raw.trim();
        if (input.isEmpty()) { load(HOME); return; }
        String uri;
        if (input.startsWith("http://") || input.startsWith("https://")
                || input.startsWith("about:") || input.startsWith("resource://")
                || input.startsWith("file://") || input.startsWith("data:")) {
            uri = input;
        } else if (input.matches("[A-Za-z0-9\\-.]+(:\\d+)?(/[\\w./?%&=+#-]*)?")
                && input.matches("[A-Za-z0-9\\-.]+\\.[A-Za-z]{2,}(/[\\w./?%&=+#-]*)?")) {
            uri = "https://" + input;
        } else {
            uri = "https://www.google.com/search?q=" + Uri.encode(input);
        }
        load(uri);
        hideKeyboard();
    }

    private void hideKeyboard() {
        InputMethodManager imm =
            (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(urlBar.getWindowToken(), 0);
    }

    private void showAddress(String url, boolean canBack) {
        runOnUiThread(() -> {
            if (!urlEditedByUser) {
                if (url == null || url.startsWith("resource://android/assets")) {
                    urlBar.getText().clear();
                } else {
                    urlBar.setText(url);
                    urlBar.setSelection(urlBar.getText().length());
                }
            }
            updateNav(canBack, false);
        });
    }

    // ---------------- delegates ----------------

    private class NavigationDelegate implements GeckoSession.NavigationDelegate {
        @Override
        public void onLocationChange(GeckoSession session, String url,
                java.util.List<GeckoSession.PermissionDelegate.ContentPermission> perms,
                Boolean isSameDocument) {
            if (session == session()) showAddress(url, true);
        }
        @Override
        public void onCanGoBack(GeckoSession session, boolean canGoBack) {
            if (session == session()) runOnUiThread(() -> updateNav(canGoBack, false));
        }
        @Override
        public GeckoResult<GeckoSession> onNewSession(GeckoSession session, String uri) {
            if (tabs.size() >= MAX_TABS) return null;
            GeckoSession s = makeTab();
            tabs.add(s);
            current = tabs.size() - 1;
            geckoView.setSession(s);
            rebuildTabs();
            return GeckoResult.fromValue(s);
        }
    }

    private class ProgressDelegate implements GeckoSession.ProgressDelegate {
    }

    private class ContentDelegate implements GeckoSession.ContentDelegate {
        @Override
        public void onTitleChange(GeckoSession session, String title) {
            runOnUiThread(() -> setTitle(
                title == null || title.isEmpty() ? "SUB-BROWSER" : title));
        }
        @Override
        public void onCrash(GeckoSession session) {
            runOnUiThread(() -> Toast.makeText(MainActivity.this,
                "Content crashed; press reload.", Toast.LENGTH_LONG).show());
        }
    }

    @Override
    public void onBackPressed() {
        GeckoSession s = session();
        if (s != null) s.goBack(); else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        for (GeckoSession s : tabs) s.close();
        tabs.clear();
        super.onDestroy();
    }
}
