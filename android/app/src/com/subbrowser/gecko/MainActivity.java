package com.subbrowser.gecko;

import android.app.Activity;
import android.app.AlertDialog;
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
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.mozilla.geckoview.AllowOrDeny;
import org.mozilla.geckoview.ContentBlocking;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoRuntimeSettings;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoSessionSettings;
import org.mozilla.geckoview.GeckoView;
import org.mozilla.geckoview.WebResponse;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {

    private static final int MAX_TABS = 3;
    private static final String UA =
        "Mozilla/5.0 (Android 14; Mobile; rv:159.0) Gecko/159.0 Firefox/159.0 SUBBrowser/2.0.3";

    private static final int BAR_NORMAL = Color.parseColor("#0C0F14");
    private static final int BAR_INCOGNITO = Color.parseColor("#1A0F2E");
    private static final int ACCENT = Color.parseColor("#29ABE2");
    private static final int BG = Color.parseColor("#050505");

    private static GeckoRuntime runtime;
    /** True once GeckoRuntime.create() has returned without throwing. */
    private static boolean runtimeReady = false;

    private GeckoView geckoView;
    private EditText urlBar;
    private ImageView backBtn, fwdBtn, starBtn;
    private LinearLayout tabStrip;
    private HorizontalScrollView tabScroll;
    private LinearLayout toolbarRow;
    private View pageCover;
    private View progressBar;
    private TextView shieldBadge;
    private FrameLayout content;
    private View findBar;
    private EditText findInput;
    private TextView findCount;
    private View overlay;        // bookmarks/history list overlay
    private LinearLayout overlayList;
    private TextView overlayTitle;
    private TextView snack;
    private PopupWindow menuPopup;

    private final List<GeckoSession> tabs = new ArrayList<>();
    private final List<Boolean> tabPrivate = new ArrayList<>();
    private int current = -1;
    private boolean incognito = false;   // applies to newly created tabs
    private Typeface tfBold;
    private boolean urlEditedByUser = false;
    private String findTerm = "";
    private String currentUrl = null;
    private String currentTitle = null;
    private boolean runtimeFailedShown = false;

    // ------------------------------------------------------------------
    // Crash forensics: keep the last uncaught exception in filesDir so a
    // field crash can be retrieved without adb (`run-as` / rooted pull).
    // ------------------------------------------------------------------
    private static final Thread.UncaughtExceptionHandler defaultHandler =
            Thread.getDefaultUncaughtExceptionHandler();

    private void installCrashRecorder() {
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try {
                File f = new File(getFilesDir(), "crash.log");
                try (PrintWriter pw = new PrintWriter(new FileOutputStream(f, false))) {
                    pw.println("time: " + new SimpleDateFormat(
                            "yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()));
                    pw.println("thread: " + t.getName());
                    pw.println("version: 2.0.3 (3)");
                    pw.println("device: " + android.os.Build.MANUFACTURER + " "
                            + android.os.Build.MODEL + " android "
                            + android.os.Build.VERSION.RELEASE
                            + " (sdk " + android.os.Build.VERSION.SDK_INT + ")");
                    pw.println("abi: " + java.util.Arrays.toString(
                            android.os.Build.SUPPORTED_ABIS));
                    e.printStackTrace(pw);
                    pw.flush();
                }
                android.util.Log.e("SUBCRASH", "uncaught on " + t.getName(), e);
            } catch (Throwable ignored) { }
            finally {
                // let the platform do its normal death thing
                if (defaultHandler != null) defaultHandler.uncaughtException(t, e);
            }
        });
    }

    /** Create/restore the GeckoRuntime. Safe to call from onCreate AND onResume;
     *  returns true when the engine is usable. OriginOS-style killers can reap
     *  the engine without taking the whole app down, so this is idempotent. */
    private boolean ensureRuntime() {
        if (runtimeReady && runtime != null) return true;
        try {
            runtime = GeckoRuntime.create(getApplication(),
                new GeckoRuntimeSettings.Builder()
                    .contentBlocking(new ContentBlocking.Settings.Builder()
                        .enhancedTrackingProtectionLevel(
                            ContentBlocking.EtpLevel.STRICT)
                        .enhancedTrackingProtectionCategory(
                            ContentBlocking.EtpCategory.STRICT)
                        .cookieBehavior(ContentBlocking.CookieBehavior.ACCEPT_NON_TRACKERS)
                        .cookieBehaviorPrivateMode(ContentBlocking.CookieBehavior.ACCEPT_NONE)
                        .strictSocialTrackingProtection(true)
                        .cookiePurging(true)
                        .build())
                    .build());
            runtimeReady = (runtime != null);
        } catch (Throwable t) {
            android.util.Log.e("SUBBM", "GeckoRuntime.create failed", t);
            runtime = null;
            runtimeReady = false;
        }
        if (runtime != null) {
            try {
                runtime.getSettings().setFontSizeFactor(BrowserState.zoom(this) / 100f);
            } catch (Throwable ignored) { }
        }
        return runtimeReady && runtime != null;
    }

    @Override
    protected void onResume() {
        super.onResume();
        // vivo/OriginOS background-kill path: if the engine died (or never
        // came up) while the Activity survived, rebuild it now; then make
        // sure at least one live tab exists so the user never sees a dead UI.
        if (!runtimeReady || runtime == null) {
            if (ensureRuntime() && tabs.isEmpty()) {
                runtimeFailedShown = false;
                try { newTab(); } catch (Throwable ignored) { }
            }
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        installCrashRecorder();
        super.onCreate(savedInstanceState);

        try {
            tfBold = Typeface.createFromAsset(getAssets(), "fonts/Poppins-Bold.ttf");
        } catch (Exception e) { tfBold = Typeface.DEFAULT_BOLD; }

        AdBlock.load(this);

        // ---- GeckoView runtime bring-up, hardened ----
        // Classic footgun: GeckoRuntime.create() can throw on low-memory /
        // second-process-start paths; never continue with a half-dead runtime.
        // Also covers aggressive OEM kill policies (vivo OriginOS etc.) where
        // engine child processes are reaped while the app process lives.
        ensureRuntime();
        if (runtime == null) {
            // Build the full UI anyway so the app never dies on cold start;
            // showSnack informs and a retry happens on next launch.
            buildUi();
            showSnack("Engine failed to start - relaunch the app");
            return;
        }
        // apply persisted zoom (80-150 %) via content font scale
        try {
            runtime.getSettings().setFontSizeFactor(BrowserState.zoom(this) / 100f);
        } catch (Throwable ignored) { }

        buildUi();

        // ---- tab restore, guarded ----
        // After a crash/kill, savedInstanceState can be non-null while our
        // static runtime is gone; restoring sessions against a dead runtime
        // used to NPE-loop. We do not persist sessions across process death,
        // so we drop the super state and always start with one fresh tab.
        try {
            newTab();
        } catch (Throwable t) {
            android.util.Log.e("SUBBM", "initial newTab failed", t);
            try {
                tabs.clear(); tabPrivate.clear(); current = -1;
            } catch (Throwable ignored2) { }
        }
        updateIncognitoUi();
    }

    private float D;   // density
    private int dp(float dpp) { return (int) (dpp * D + 0.5f); }

    private void buildUi() {
        D = getResources().getDisplayMetrics().density;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        // Android 15/16 enforce edge-to-edge for newer targets and OEM builds
        // (vivo OriginOS) behave inconsistently even for targetSdk 34: apply
        // system-bar insets as padding on the root instead of relying on
        // deprecated systemUiVisibility / statusBarColor behaviour.
        try {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                getWindow().setStatusBarColor(android.graphics.Color.TRANSPARENT);
                getWindow().setNavigationBarColor(android.graphics.Color.TRANSPARENT);
            }
            root.setOnApplyWindowInsetsListener((v, insets) -> {
                android.graphics.Insets bars;
                if (android.os.Build.VERSION.SDK_INT >= 30) {
                    bars = insets.getInsets(android.view.WindowInsets.Type.systemBars());
                } else {
                    final android.graphics.Rect r = new android.graphics.Rect();
                    r.left = insets.getSystemWindowInsetLeft();
                    r.top = insets.getSystemWindowInsetTop();
                    r.right = insets.getSystemWindowInsetRight();
                    r.bottom = insets.getSystemWindowInsetBottom();
                    v.setPadding(r.left, r.top, r.right, r.bottom);
                    return insets;
                }
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
                return insets;
            });
        } catch (Throwable ignored) { }

        // ---- row 1: tab chip strip (48dp) ----
        HorizontalScrollView sv = new HorizontalScrollView(this);
        tabScroll = sv;
        sv.setHorizontalScrollBarEnabled(false);
        sv.setBackgroundColor(BG);
        tabStrip = new LinearLayout(this);
        tabStrip.setOrientation(LinearLayout.HORIZONTAL);
        tabStrip.setGravity(Gravity.CENTER_VERTICAL);
        tabStrip.setPadding(dp(10), dp(6), dp(10), dp(6));
        sv.addView(tabStrip);
        root.addView(sv, new LinearLayout.LayoutParams(-1, dp(48)));

        // ---- row 2: toolbar (56dp rhythm) ----
        toolbarRow = new LinearLayout(this);
        toolbarRow.setOrientation(LinearLayout.HORIZONTAL);
        toolbarRow.setGravity(Gravity.CENTER_VERTICAL);
        toolbarRow.setBackgroundColor(BAR_NORMAL);
        toolbarRow.setPadding(dp(6), 0, dp(8), 0);
        toolbarRow.setElevation(dp(3));   // subtle chrome/page separation

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.dragon_mini);
        logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        logo.setPadding(dp(4), dp(4), dp(4), dp(4));
        logo.setBackgroundResource(R.drawable.icon_btn_bg);
        logo.setOnClickListener(v -> load(BrowserState.homeUrl(this)));
        toolbarRow.addView(logo, lp(dp(32), dp(32), 0, 0));

        backBtn = iconButton(R.drawable.ic_back,
                v -> { GeckoSession s = session(); if (s != null) safeGoBack(s); });
        fwdBtn = iconButton(R.drawable.ic_forward,
                v -> { GeckoSession s = session(); if (s != null) s.goForward(); });
        ImageView refresh = iconButton(R.drawable.ic_refresh,
                v -> { GeckoSession s = session(); if (s != null) s.reload(); });
        ImageView home = iconButton(R.drawable.ic_home,
                v -> load(BrowserState.homeUrl(this)));
        toolbarRow.addView(backBtn); toolbarRow.addView(fwdBtn);
        toolbarRow.addView(refresh); toolbarRow.addView(home);

        final LinearLayout pill = new LinearLayout(this);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setPadding(dp(14), 0, dp(8), 0);
        pill.addView(iconInline(R.drawable.ic_search, Color.parseColor("#48606e"), dp(18)));

        urlBar = new EditText(this);
        urlBar.setBackground(null);
        urlBar.setHint("Search or enter URL");
        urlBar.setTextColor(Color.WHITE);
        urlBar.setHintTextColor(Color.parseColor("#48606e"));
        urlBar.setInputType(InputType.TYPE_TEXT_VARIATION_URI
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        urlBar.setSingleLine(true);
        urlBar.setImeOptions(EditorInfo.IME_ACTION_GO);
        urlBar.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        urlBar.setTypeface(tfBold);
        urlBar.setPadding(dp(8), 0, dp(4), 0);
        urlBar.setOnFocusChangeListener((v, f) -> stylePill(pill, f));
        urlBar.setOnTouchListener((v, ev) -> {
            if (ev.getAction() == android.view.MotionEvent.ACTION_DOWN)
                urlEditedByUser = true;
            return false;
        });
        urlBar.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_GO
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                        && event.getAction() == KeyEvent.ACTION_DOWN)) {
                navigate(urlBar.getText().toString());
                return true;
            }
            return false;
        });
        pill.addView(urlBar, new LinearLayout.LayoutParams(0, dp(48), 1f));
        stylePill(pill, false);
        toolbarRow.addView(pill, lp(0, dp(48), 1f, dp(8)));

        // star: bookmark current page (44dp touch)
        starBtn = new ImageView(this);
        starBtn.setImageResource(R.drawable.ic_star);
        starBtn.setBackgroundResource(R.drawable.icon_btn_bg);
        starBtn.setPadding(dp(10), dp(10), dp(10), dp(10));
        starBtn.setOnClickListener(v -> toggleBookmark());
        toolbarRow.addView(starBtn, lp(dp(44), dp(44), 0, 0));

        // 3-dot overflow menu (44dp touch)
        ImageView menuBtn = new ImageView(this);
        menuBtn.setImageResource(R.drawable.ic_menu);
        menuBtn.setBackgroundResource(R.drawable.icon_btn_bg);
        menuBtn.setPadding(dp(10), dp(10), dp(10), dp(10));
        menuBtn.setOnClickListener(v -> openMenu(menuBtn));
        toolbarRow.addView(menuBtn, lp(dp(44), dp(44), 0, dp(4)));

        // shield badge: tap opens adblock toggle dialog (44dp touch)
        FrameLayout shieldWrap = new FrameLayout(this);
        ImageView shield = new ImageView(this);
        shield.setImageResource(R.drawable.ic_shield);
        shield.setBackgroundResource(R.drawable.icon_btn_bg);
        shield.setPadding(dp(10), dp(10), dp(10), dp(10));
        shield.setOnClickListener(v -> showAdblockDialog());
        shieldWrap.addView(shield, new FrameLayout.LayoutParams(dp(44), dp(44)));
        shieldBadge = new TextView(this);
        shieldBadge.setTextColor(Color.WHITE);
        shieldBadge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9);
        shieldBadge.setTypeface(tfBold);
        shieldBadge.setGravity(Gravity.CENTER);
        GradientDrawable badgeBg = new GradientDrawable();
        badgeBg.setShape(GradientDrawable.OVAL);
        badgeBg.setColor(ACCENT);
        shieldBadge.setBackground(badgeBg);
        FrameLayout.LayoutParams bfp = new FrameLayout.LayoutParams(dp(18), dp(18));
        bfp.gravity = Gravity.TOP | Gravity.END;
        shieldWrap.addView(shieldBadge, bfp);
        toolbarRow.addView(shieldWrap, lp(dp(44), dp(44), 0, dp(4)));

        root.addView(toolbarRow, new LinearLayout.LayoutParams(-1, dp(56)));

        // ---- smooth 2dp progress line under toolbar ----
        progressBar = new View(this);
        progressBar.setBackgroundColor(ACCENT);
        progressBar.setVisibility(View.GONE);
        root.addView(progressBar, new LinearLayout.LayoutParams(dp(1), dp(2)));

        content = new FrameLayout(this);
        content.setElevation(0);
        geckoView = new GeckoView(this);
        geckoView.setBackgroundColor(BG);
        content.addView(geckoView, new FrameLayout.LayoutParams(-1, -1));

        // dark cover: hides the white about:blank flash until first page paint
        pageCover = new View(this);
        pageCover.setBackgroundColor(BG);
        content.addView(pageCover, new FrameLayout.LayoutParams(-1, -1));

        // ---- find-in-page bar (hidden) ----
        findBar = buildFindBar();
        FrameLayout.LayoutParams fbp = new FrameLayout.LayoutParams(-1, dp(56));
        fbp.gravity = Gravity.TOP;
        content.addView(findBar, fbp);

        // ---- snackbar-style status line (hidden) ----
        snack = new TextView(this);
        snack.setTextColor(Color.WHITE);
        snack.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        snack.setTypeface(tfBold);
        snack.setPadding(dp(18), dp(13), dp(18), dp(13));
        snack.setVisibility(View.GONE);
        GradientDrawable sbg = new GradientDrawable();
        sbg.setCornerRadius(dp(14));
        sbg.setColor(Color.parseColor("#1E2A35"));
        snack.setBackground(sbg);
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(-2, -2);
        slp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        slp.setMargins(0, 0, 0, dp(20));
        content.addView(snack, slp);

        // ---- bookmarks/history overlay (hidden) ----
        overlay = buildOverlay();
        content.addView(overlay, new FrameLayout.LayoutParams(-1, -1));

        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1f));

        setContentView(root);
    }

    /** Progress update: 0/100 hides, else width = pct of root width. */
    private void showProgress(final int pct) {
        runOnUiThread(() -> {
            if (progressBar == null) return;
            if (pct <= 0 || pct >= 100) {
                progressBar.setVisibility(View.GONE);
                return;
            }
            progressBar.setVisibility(View.VISIBLE);
            int w = progressBar.getParent() instanceof View
                    ? ((View) progressBar.getParent()).getWidth() : 0;
            if (w <= 0) w = getResources().getDisplayMetrics().widthPixels;
            LinearLayout.LayoutParams p = (LinearLayout.LayoutParams) progressBar.getLayoutParams();
            p.width = (int) (w * pct / 100f + 0.5f);
            progressBar.setLayoutParams(p);
        });
    }

    private static LinearLayout.LayoutParams lp(int w, int h, float weight, int marginL) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h, weight);
        p.setMarginStart(marginL);
        return p;
    }

    private void stylePill(LinearLayout pill, boolean focused) {
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(dp(24));
        bg.setColor(Color.parseColor("#12181f"));
        bg.setStroke(dp(focused ? 2 : 1),
                focused ? ACCENT : Color.parseColor("#1e2a35"));
        pill.setBackground(bg);
        pill.setElevation(dp(focused ? 6 : 0));
    }

    private void updateShield() {
        runOnUiThread(() -> {
            int n = AdBlock.blockedCount(this);
            boolean on = AdBlock.enabled(this);
            if (n > 0 && on) {
                shieldBadge.setText(String.valueOf(n));
                shieldBadge.setVisibility(View.VISIBLE);
            } else {
                shieldBadge.setVisibility(View.GONE);
            }
        });
    }

    private void showAdblockDialog() {
        final boolean was = AdBlock.enabled(this);
        String msg = (AdBlock.ruleCount() + " rules loaded\n\n"
                + AdBlock.blockedCount(this) + " blocked today\n\n"
                + "Status: " + (was ? "ON" : "OFF"));
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

    private ImageView iconButton(int res, View.OnClickListener l) {
        ImageView iv = new ImageView(this);
        iv.setImageResource(res);
        iv.setColorFilter(ACCENT);
        iv.setBackgroundResource(R.drawable.icon_btn_bg);
        iv.setPadding(dp(10), dp(10), dp(10), dp(10));
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iv.setOnClickListener(l);
        iv.setLayoutParams(lp(dp(44), dp(44), 0, 0));
        return iv;
    }

    private ImageView iconInline(int res, int tint, int size) {
        ImageView iv = new ImageView(this);
        iv.setImageResource(res);
        iv.setColorFilter(tint);
        iv.setLayoutParams(new LinearLayout.LayoutParams(size, size));
        return iv;
    }

    private void updateNav(boolean canBack, boolean canFwd) {
        int on = ACCENT;
        int off = Color.parseColor("#3a4550");
        backBtn.setColorFilter(canBack ? on : off);
        fwdBtn.setColorFilter(canFwd ? on : off);
    }

    private void updateStar() {
        boolean priv = isPrivateTab();
        boolean marked = !priv && currentUrl != null
                && BrowserDB.get(this).isBookmarked(currentUrl);
        starBtn.setColorFilter(marked ? Color.parseColor("#FFC93C")
                : (priv ? Color.parseColor("#3a4550") : Color.parseColor("#66788a")));
    }

    // ---------------- snackbar ----------------

    private void showSnack(String text) {
        runOnUiThread(() -> {
            snack.setText(text);
            snack.setVisibility(View.VISIBLE);
            snack.removeCallbacks(hideSnack);
            snack.postDelayed(hideSnack, 3200);
        });
    }

    private final Runnable hideSnack =
            () -> { if (snack != null) snack.setVisibility(View.GONE); };

    // ---------------- bookmarks ----------------

    private void toggleBookmark() {
        if (isPrivateTab()) {
            Toast.makeText(this, "No bookmarks in Incognito",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        if (currentUrl == null || !currentUrl.startsWith("http")) {
            Toast.makeText(this, "Nothing to bookmark here",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        BrowserDB db = BrowserDB.get(this);
        if (db.isBookmarked(currentUrl)) {
            List<BrowserDB.Entry> es = db.bookmarks();
            for (BrowserDB.Entry e : es)
                if (e.url.equals(currentUrl)) { db.deleteBookmark(e.id); break; }
            Toast.makeText(this, "Bookmark removed", Toast.LENGTH_SHORT).show();
        } else {
            db.addBookmark(currentUrl, currentTitle);
            Toast.makeText(this, "Bookmarked", Toast.LENGTH_SHORT).show();
        }
        updateStar();
    }

    private void showBookmarks() {
        List<BrowserDB.Entry> es = BrowserDB.get(this).bookmarks();
        openOverlay("Bookmarks", es.isEmpty() ? "No bookmarks yet.\nTap the star on a page." : null,
                (BrowserDB.Entry e) -> {
                    hideOverlay();
                    load(e.url);
                },
                (BrowserDB.Entry e) -> {
                    BrowserDB.get(this).deleteBookmark(e.id);
                    showBookmarks();
                    updateStar();
                }, null);
    }

    // ---------------- history ----------------

    private void showHistory() {
        List<BrowserDB.Entry> es = BrowserDB.get(this).history();
        openOverlay("History", es.isEmpty() ? "No history yet." : null,
                (BrowserDB.Entry e) -> { hideOverlay(); load(e.url); },
                null, "Clear all");
    }

    private static String dayLabel(long ms) {
        SimpleDateFormat day = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        String ed = day.format(new Date(ms));
        String td = day.format(new Date());
        Calendar y = Calendar.getInstance();
        y.add(Calendar.DAY_OF_YEAR, -1);
        String yd = day.format(y.getTime());
        if (ed.equals(td)) return "Today";
        if (ed.equals(yd)) return "Yesterday";
        return new SimpleDateFormat("EEEE, d MMM yyyy", Locale.US).format(new Date(ms));
    }

    private void openOverlay(String title, String emptyMsg,
            java.util.function.Consumer<BrowserDB.Entry> onTap,
            java.util.function.Consumer<BrowserDB.Entry> onDelete,
            String actionLabel) {
        overlayTitle.setText(title);
        overlayList.removeAllViews();
        overlay.setVisibility(View.VISIBLE);

        boolean isHist = title.equals("History");
        List<BrowserDB.Entry> es = isHist
                ? BrowserDB.get(this).history()
                : BrowserDB.get(this).bookmarks();

        if (es.isEmpty() && emptyMsg != null) {
            TextView t = new TextView(this);
            t.setText(emptyMsg);
            t.setTextColor(Color.parseColor("#66788a"));
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            t.setGravity(Gravity.CENTER);
            t.setPadding(dp(24), dp(60), dp(24), 0);
            overlayList.addView(t, new LinearLayout.LayoutParams(-1, -2));
        } else {
            String lastDay = null;
            for (BrowserDB.Entry e : es) {
                if (isHist) {
                    String d = dayLabel(e.ms);
                    if (!d.equals(lastDay)) {
                        lastDay = d;
                        TextView h = new TextView(this);
                        h.setText(d);
                        h.setTextColor(ACCENT);
                        h.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                        h.setTypeface(tfBold);
                        h.setPadding(dp(22), dp(18), dp(22), dp(8));
                        overlayList.addView(h,
                                new LinearLayout.LayoutParams(-1, -2));
                    }
                }
                overlayList.addView(listRow(e, onTap, onDelete));
            }
        }

        if (actionLabel != null) {
            TextView act = new TextView(this);
            act.setText(actionLabel.toUpperCase(Locale.US));
            act.setTextColor(Color.parseColor("#FF6B6B"));
            act.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            act.setTypeface(tfBold);
            act.setGravity(Gravity.CENTER);
            act.setPadding(dp(20), dp(24), dp(20), dp(30));
            act.setOnClickListener(v -> {
                new AlertDialog.Builder(this)
                    .setTitle("Clear all history?")
                    .setMessage("This removes every recorded page.")
                    .setPositiveButton("Clear", (d, w) -> {
                        BrowserDB.get(this).clearHistory();
                        showHistory();
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
            });
            overlayList.addView(act, new LinearLayout.LayoutParams(-1, -2));
        }
    }

    private View listRow(BrowserDB.Entry e,
            java.util.function.Consumer<BrowserDB.Entry> onTap,
            java.util.function.Consumer<BrowserDB.Entry> onDelete) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(20), dp(15), dp(14), dp(15));
        GradientDrawable rg = new GradientDrawable();
        rg.setColor(Color.parseColor("#0E141a"));
        rg.setCornerRadius(dp(14));
        row.setBackground(rg);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, dp(56));
        rlp.setMargins(dp(14), dp(5), dp(14), dp(5));
        row.setLayoutParams(rlp);

        TextView t = new TextView(this);
        String title = e.title == null || e.title.isEmpty() ? e.url : e.title;
        t.setText(title);
        t.setTextColor(Color.WHITE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        t.setMaxLines(1);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        row.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView u = new TextView(this);
        u.setText(e.url);
        u.setTextColor(Color.parseColor("#48606e"));
        u.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        u.setMaxLines(1);
        u.setEllipsize(android.text.TextUtils.TruncateAt.END);
        u.setPadding(dp(10), 0, dp(6), 0);
        row.addView(u, new LinearLayout.LayoutParams(0, -2, 1.2f));

        if (onDelete != null) {
            ImageView x = new ImageView(this);
            x.setImageResource(R.drawable.ic_close);
            x.setColorFilter(Color.parseColor("#66788a"));
            x.setPadding(dp(10), dp(10), dp(10), dp(10));
            x.setOnClickListener(v -> onDelete.accept(e));
            row.addView(x, new LinearLayout.LayoutParams(dp(40), dp(40)));
        }

        row.setOnClickListener(v -> { if (onTap != null) onTap.accept(e); });
        return row;
    }

    private View buildOverlay() {
        FrameLayout fl = new FrameLayout(this);
        fl.setBackgroundColor(BG);
        fl.setVisibility(View.GONE);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(20), dp(18), dp(12), dp(12));

        overlayTitle = new TextView(this);
        overlayTitle.setTextColor(Color.WHITE);
        overlayTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        overlayTitle.setTypeface(tfBold);
        head.addView(overlayTitle, new LinearLayout.LayoutParams(0, -2, 1f));

        ImageView close = new ImageView(this);
        close.setImageResource(R.drawable.ic_close);
        close.setColorFilter(Color.parseColor("#66788a"));
        close.setPadding(dp(10), dp(10), dp(10), dp(10));
        close.setOnClickListener(v -> hideOverlay());
        head.addView(close, new LinearLayout.LayoutParams(dp(44), dp(44)));
        fl.addView(head, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        ScrollView sc = new ScrollView(this);
        overlayList = new LinearLayout(this);
        overlayList.setOrientation(LinearLayout.VERTICAL);
        overlayList.setPadding(0, dp(68), 0, 0);
        sc.addView(overlayList, new ScrollView.LayoutParams(-1, -2));
        fl.addView(sc, new FrameLayout.LayoutParams(-1, -1));
        return fl;
    }

    private void hideOverlay() { overlay.setVisibility(View.GONE); }

    // ---------------- find in page ----------------

    private View buildFindBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(12), 0, dp(12), 0);
        bar.setVisibility(View.GONE);
        bar.setElevation(dp(4));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#0E141a"));
        bar.setBackground(bg);

        findInput = new EditText(this);
        findInput.setHint("Find on page");
        findInput.setTextColor(Color.WHITE);
        findInput.setHintTextColor(Color.parseColor("#48606e"));
        findInput.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        findInput.setSingleLine(true);
        findInput.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        findInput.setBackground(null);
        bar.addView(findInput, new LinearLayout.LayoutParams(0, dp(48), 1f));

        findCount = new TextView(this);
        findCount.setTextColor(Color.parseColor("#66788a"));
        findCount.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        findCount.setTypeface(tfBold);
        findCount.setMinWidth(dp(64));
        findCount.setGravity(Gravity.CENTER);
        bar.addView(findCount, new LinearLayout.LayoutParams(-2, -2));

        ImageView prev = iconButton(R.drawable.ic_back, v -> doFind(true));
        ImageView next = iconButton(R.drawable.ic_forward, v -> doFind(false));
        bar.addView(prev); bar.addView(next);

        ImageView close = new ImageView(this);
        close.setImageResource(R.drawable.ic_close);
        close.setColorFilter(Color.parseColor("#66788a"));
        close.setPadding(dp(10), dp(10), dp(10), dp(10));
        close.setOnClickListener(v -> closeFind());
        bar.addView(close, new LinearLayout.LayoutParams(dp(44), dp(44)));

        findInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                        && event.getAction() == KeyEvent.ACTION_DOWN)) {
                doFind(event != null && event.isShiftPressed());
                return true;
            }
            return false;
        });
        return bar;
    }

    private void openFind() {
        findBar.setVisibility(View.VISIBLE);
        findInput.setText("");
        findCount.setText("");
        findInput.requestFocus();
        findTerm = "";
    }

    private void closeFind() {
        GeckoSession s = session();
        if (s != null) { try { s.getFinder().clear(); } catch (Throwable ignored) {} }
        findBar.setVisibility(View.GONE);
        hideKeyboard();
    }

    private void doFind(boolean backwards) {
        GeckoSession s = session();
        if (s == null) return;
        String q = findInput.getText().toString();
        int flags = (backwards ? GeckoSession.FINDER_FIND_BACKWARDS
                : GeckoSession.FINDER_FIND_FORWARD);
        if (!q.equals(findTerm)) {
            try { s.getFinder().clear(); } catch (Throwable ignored) {}
            findTerm = q;
        }
        if (q.isEmpty()) { findCount.setText(""); return; }
        try {
            s.getFinder().setDisplayFlags(GeckoSession.FINDER_DISPLAY_HIGHLIGHT_ALL);
            s.getFinder().find(q, flags).accept(res -> {
                runOnUiThread(() -> {
                    if (findCount == null) return;
                    if (res == null || !res.found) findCount.setText("0/0");
                    else findCount.setText(res.current + 1 + "/" + res.total);
                });
            }, err -> runOnUiThread(() -> {
                if (findCount != null) findCount.setText("0/0");
            }));
        } catch (Throwable t) {
            android.util.Log.w("SUBBM", "find failed", t);
        }
    }

    // ---------------- zoom ----------------

    private void showZoomMenu() {
        String[] levels = {"80%", "90%", "100%", "110%", "125%", "150%"};
        String[] items = new String[levels.length];
        for (int i = 0; i < levels.length; i++)
            items[i] = levels[i] + (BrowserState.zoom(this)
                    == Integer.parseInt(levels[i].replace("%", "")) ? "  \u25CF" : "");
        new AlertDialog.Builder(this)
            .setTitle("Zoom \u2014 page text scale")
            .setItems(items, (d, w) -> {
                int pct = Integer.parseInt(levels[w].replace("%", ""));
                BrowserState.setZoom(this, pct);
                if (runtime != null) {
                    try { runtime.getSettings().setFontSizeFactor(pct / 100f); }
                    catch (Throwable ignored) { }
                }
                Toast.makeText(this, "Zoom " + pct + "%",
                        Toast.LENGTH_SHORT).show();
            })
            .show();
    }

    // ---------------- home page setting ----------------

    private void showHomeSetting() {
        final EditText in = new EditText(this);
        in.setText(BrowserState.homeUrl(this));
        in.setTextColor(Color.WHITE);
        in.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        in.setSingleLine(true);
        FrameLayout wrap = new FrameLayout(this);
        wrap.addView(in, new FrameLayout.LayoutParams(-1, -2));
        wrap.setPadding(dp(20), dp(10), dp(20), 0);
        new AlertDialog.Builder(this)
            .setTitle("Home page (start URL)")
            .setView(wrap)
            .setPositiveButton("Save", (d, w) -> {
                String v = in.getText().toString().trim();
                if (v.isEmpty() || v.equalsIgnoreCase("home")
                        || v.startsWith("resource://android")) {
                    BrowserState.setHomeUrl(this, BrowserState.HOME_DEFAULT);
                } else {
                    if (!v.startsWith("http://") && !v.startsWith("https://"))
                        v = "https://" + v;
                    BrowserState.setHomeUrl(this, v);
                }
                Toast.makeText(this, "Home page saved", Toast.LENGTH_SHORT).show();
            })
            .setNeutralButton("Reset", (d, w) -> {
                BrowserState.setHomeUrl(this, BrowserState.HOME_DEFAULT);
                Toast.makeText(this, "Home page reset", Toast.LENGTH_SHORT).show();
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    // ---------------- incognito ----------------

    private boolean isPrivateTab() {
        return current >= 0 && current < tabPrivate.size()
                && tabPrivate.get(current);
    }

    private void toggleIncognito() {
        incognito = !incognito;
        // convert current tab to match the new mode
        GeckoSession s = session();
        String url = currentUrl;
        if (s != null && url != null && !url.startsWith("resource://")) {
            int idx = current;
            try { s.close(); } catch (Throwable ignored) { }
            GeckoSession ns = makeTab(incognito);
            if (ns == null) { Toast.makeText(this, "Engine unavailable",
                    Toast.LENGTH_SHORT).show(); return; }
            tabs.set(idx, ns);
            tabPrivate.set(idx, incognito);
            geckoView.setSession(ns);
            ns.loadUri(url);
        }
        updateIncognitoUi();
        rebuildTabs();
        Toast.makeText(this, incognito ? "Incognito ON" : "Incognito OFF",
                Toast.LENGTH_SHORT).show();
    }

    private void updateIncognitoUi() {
        runOnUiThread(() -> {
            boolean priv = isPrivateTab();
            int bar = priv ? BAR_INCOGNITO : BAR_NORMAL;
            toolbarRow.setBackgroundColor(bar);
            if (tabScroll != null) tabScroll.setBackgroundColor(priv ? bar : BG);
            updateStar();
        });
    }

    // ---------------- overflow menu ----------------

    private void openMenu(View anchor) {
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#12181f"));
        bg.setCornerRadius(dp(16));
        bg.setStroke(dp(1), Color.parseColor("#2a3a48"));
        list.setBackground(bg);
        list.setPadding(dp(8), dp(10), dp(8), dp(10));
        list.setElevation(dp(16));

        addMenuItem(list, R.drawable.ic_star, "Bookmarks",
                v -> { dismissMenu(); showBookmarks(); });
        addMenuItem(list, R.drawable.ic_history, "History",
                v -> { dismissMenu(); showHistory(); });
        addMenuItem(list, R.drawable.ic_search, "Find in page",
                v -> { dismissMenu(); openFind(); });
        addMenuItem(list, R.drawable.ic_zoom, "Zoom  " + BrowserState.zoom(this) + "%",
                v -> { dismissMenu(); showZoomMenu(); });
        addMenuItem(list, R.drawable.ic_incognito, "Incognito  " + (incognito ? "ON" : "OFF"),
                v -> { dismissMenu(); toggleIncognito(); });
        addMenuItem(list, R.drawable.ic_home, "Set home page",
                v -> { dismissMenu(); showHomeSetting(); });

        menuPopup = new PopupWindow(list, dp(252), -2, true);
        // keep the card fully on-screen: right-align near the anchor's right edge
        int[] loc = new int[2];
        anchor.getLocationOnScreen(loc);
        int popW = dp(252);
        int x = loc[0] + anchor.getWidth() - popW;
        int maxX = getResources().getDisplayMetrics().widthPixels - popW - dp(8);
        if (x > maxX) x = maxX;
        if (x < dp(8)) x = dp(8);
        int y = loc[1] + anchor.getHeight() + dp(8);
        menuPopup.showAtLocation(anchor, Gravity.NO_GRAVITY, x, y);
    }

    private void dismissMenu() {
        if (menuPopup != null && menuPopup.isShowing()) {
            try { menuPopup.dismiss(); } catch (Throwable ignored) { }
        }
    }

    private void addMenuItem(LinearLayout list, int iconRes, String label,
            View.OnClickListener l) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(7), dp(14), dp(7));
        GradientDrawable rowBg = new GradientDrawable();
        rowBg.setCornerRadius(dp(12));
        rowBg.setColor(Color.TRANSPARENT);
        row.setBackground(rowBg);
        row.setOnClickListener(l);

        ImageView ic = new ImageView(this);
        ic.setImageResource(iconRes);
        ic.setColorFilter(ACCENT);
        row.addView(ic, new LinearLayout.LayoutParams(dp(20), dp(20)));

        TextView t = new TextView(this);
        t.setText(label);
        t.setTextColor(Color.WHITE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        t.setTypeface(tfBold);
        t.setPadding(dp(14), dp(11), dp(10), dp(11));
        row.addView(t, new LinearLayout.LayoutParams(-1, -2));

        list.addView(row, new LinearLayout.LayoutParams(-1, dp(48)));
    }

    // ---------------- downloads ----------------

    private static final String[] DL_EXT = {"png","jpg","jpeg","gif","webp","svg",
            "pdf","zip","gz","tar","apk","txt","csv","mp4","mkv","mp3","iso","dmg"};

    private static boolean looksDownloadable(String uri) {
        if (uri == null) return false;
        String path = uri;
        int q = path.indexOf('?'); if (q > 0) path = path.substring(0, q);
        int h = path.indexOf('#'); if (h > 0) path = path.substring(0, h);
        int dot = path.lastIndexOf('.');
        if (dot < 0 || dot < path.lastIndexOf('/')) return false;
        String ext = path.substring(dot + 1).toLowerCase(Locale.US);
        for (String e : DL_EXT) if (e.equals(ext)) return true;
        return false;
    }

    /** Manual stream download for direct file-link navigation. */
    private final java.util.Set<String> dlActive =
            java.util.Collections.synchronizedSet(new java.util.HashSet<>());

    private void manualDownload(String uri) {
        if (!dlActive.add(uri)) return;   // dedupe double-fired requests
        String name = Uri.parse(uri).getLastPathSegment();
        if (name == null || name.isEmpty()) name = "download.bin";
        name = name.replaceAll("[^A-Za-z0-9._-]", "_");
        if (name.length() > 80) name = name.substring(name.length() - 80);
        showSnack("Downloading " + name + "\u2026");
        final String fname = name;
        new Thread(() -> {
            java.net.HttpURLConnection cn = null;
            try {
                java.net.URL u = new java.net.URL(uri);
                cn = (java.net.HttpURLConnection) u.openConnection();
                cn.setConnectTimeout(15000); cn.setReadTimeout(30000);
                cn.connect();
                File dir = getExternalFilesDir("Download");
                if (dir == null) dir = getFilesDir();
                dir.mkdirs();
                File out = new File(dir, fname);
                long n = 0;
                try (InputStream in = cn.getInputStream();
                     FileOutputStream fo = new FileOutputStream(out)) {
                    byte[] buf = new byte[16384];
                    int r;
                    while ((r = in.read(buf)) > 0) { fo.write(buf, 0, r); n += r; }
                }
                BrowserState.addDownload(this);
                showSnack("Saved to Downloads (" + fname + ")");
            } catch (Exception e) {
                android.util.Log.w("SUBDL", "download failed", e);
                showSnack("Download failed");
            } finally { dlActive.remove(uri); if (cn != null) cn.disconnect(); }
        }, "subdl").start();
    }

    private void handleExternalResponse(GeckoSession sess, WebResponse response) {
        String uri = response.uri;
        if (uri == null || !(uri.startsWith("http://") || uri.startsWith("https://"))) {
            return;
        }
        String name = Uri.parse(uri).getLastPathSegment();
        if (name == null || name.isEmpty()) name = "download.bin";
        // strip query-derived junk, keep simple name safe
        name = name.replaceAll("[^A-Za-z0-9._-]", "_");
        if (name.length() > 80) name = name.substring(name.length() - 80);

        final String fname = name;
        showSnack("Downloading " + fname + "\u2026");

        new Thread(() -> {
            File dir = getExternalFilesDir("Download");
            if (dir == null) dir = getFilesDir();
            dir.mkdirs();
            File out = new File(dir, fname);
            long n = 0;
            try (InputStream in = response.body;
                 FileOutputStream fo = new FileOutputStream(out)) {
                byte[] buf = new byte[16384];
                int r;
                while ((r = in.read(buf)) > 0) { fo.write(buf, 0, r); n += r; }
            } catch (Exception e) {
                android.util.Log.w("SUBDL", "download failed", e);
                showSnack("Download failed");
                return;
            }
            BrowserState.addDownload(this);
            showSnack("Saved to Downloads (" + fname + ")");
        }, "subdl").start();
    }

    // ---------------- tabs ----------------

    private GeckoSession session() {
        return (current >= 0 && current < tabs.size()) ? tabs.get(current) : null;
    }

    private GeckoSession makeTab(boolean priv) {
        if (runtime == null) {
            if (!runtimeFailedShown) {
                runtimeFailedShown = true;
                showSnack("Engine unavailable - relaunch the app");
            }
            return null;
        }
        try {
            GeckoSessionSettings.Builder b = new GeckoSessionSettings.Builder()
                    .usePrivateMode(priv)
                    .userAgentOverride(UA);
            if (priv) b.useTrackingProtection(true);
            GeckoSession s = new GeckoSession(b.build());
            s.setContentDelegate(new ContentDelegate());
            s.setProgressDelegate(new ProgressDelegate());
            s.setNavigationDelegate(new NavigationDelegate());
            s.setContentBlockingDelegate(new ContentBlockingDelegate());
            s.open(runtime);
            return s;
        } catch (Throwable t) {
            android.util.Log.e("SUBBM", "makeTab failed", t);
            return null;
        }
    }

    private void newTab() {
        if (tabs.size() >= MAX_TABS) {
            Toast.makeText(this, "Max " + MAX_TABS + " tabs", Toast.LENGTH_SHORT).show();
            return;
        }
        GeckoSession s = makeTab(incognito);
        if (s == null) return;   // engine down; UI + snack already in place
        tabs.add(s);
        tabPrivate.add(incognito);
        current = tabs.size() - 1;
        geckoView.setSession(session());
        updateNav(false, false);
        rebuildTabs();
        updateIncognitoUi();
        load(BrowserState.homeUrl(this));
    }

    private void closeCurrentTab() {
        GeckoSession s = session();
        if (s != null) { try { s.close(); } catch (Throwable ignored) { } }
        if (current >= 0) { tabs.remove(current); tabPrivate.remove(current); }
        if (tabs.isEmpty()) { current = -1; geckoView.setSession(null); newTab(); }
        else {
            if (current >= tabs.size()) current = tabs.size() - 1;
            geckoView.setSession(session());
        }
        rebuildTabs();
        updateIncognitoUi();
    }

    private void rebuildTabs() {
        runOnUiThread(() -> {
            tabStrip.removeAllViews();
            for (int i = 0; i < tabs.size(); i++) {
                final int idx = i;
                boolean priv = tabPrivate.get(i);
                LinearLayout chip = new LinearLayout(this);
                chip.setOrientation(LinearLayout.HORIZONTAL);
                chip.setGravity(Gravity.CENTER_VERTICAL);
                chip.setPadding(dp(16), 0, dp(6), 0);
                GradientDrawable cb = new GradientDrawable();
                cb.setCornerRadius(dp(22));   // pill chips
                if (i == current) {
                    cb.setColor(priv ? Color.parseColor("#1C1430")
                            : Color.parseColor("#16202a"));
                    cb.setStroke(dp(1), priv ? Color.parseColor("#3A2A5E")
                            : Color.parseColor("#2a3a48"));
                } else {
                    cb.setColor(0x00000000);
                    cb.setStroke(dp(1), Color.parseColor("#141c24"));
                }
                chip.setBackground(cb);
                if (i == current) chip.setElevation(dp(3));

                TextView t = new TextView(this);
                t.setText(priv ? "Private" : "Tab " + (i + 1));
                t.setTextColor(i == current
                        ? (priv ? Color.parseColor("#B388FF") : ACCENT)
                        : Color.parseColor("#66788a"));
                t.setTextSize(12);
                t.setTypeface(tfBold);
                t.setOnClickListener(v -> selectTab(idx));
                chip.addView(t, new LinearLayout.LayoutParams(dp(62), -1));

                // close X: hidden on active chip until long-press ("closeMode")
                ImageView x = new ImageView(this);
                x.setImageResource(R.drawable.ic_close);
                x.setColorFilter(i == current ? ACCENT
                        : Color.parseColor("#44525e"));
                x.setPadding(dp(7), dp(7), dp(7), dp(7));
                x.setTag("x-" + idx);
                x.setVisibility(View.GONE);
                x.setOnClickListener(v -> closeTabAt(idx));
                chip.addView(x, new LinearLayout.LayoutParams(dp(28), dp(28)));

                // long-press the chip toggles the X on the active tab
                chip.setOnLongClickListener(v -> {
                    View xv = chip.findViewWithTag("x-" + idx);
                    if (xv != null) xv.setVisibility(
                            xv.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
                    return true;
                });

                LinearLayout.LayoutParams llp = lp(-2, dp(38), 0, dp(6));
                tabStrip.addView(chip, llp);
            }
        });
    }

    /** Close a tab by index; last tab close = recreate home tab (never zero-session). */
    private void closeTabAt(int idx) {
        if (idx < 0 || idx >= tabs.size()) return;
        try { tabs.get(idx).close(); } catch (Throwable ignored) { }
        tabs.remove(idx); tabPrivate.remove(idx);
        if (tabs.isEmpty()) {
            current = -1;
            geckoView.setSession(null);
            newTab();
            return;
        }
        if (idx < current) current--;
        else if (idx == current || current >= tabs.size()) current = Math.min(idx, tabs.size() - 1);
        geckoView.setSession(session());
        rebuildTabs();
        updateIncognitoUi();
        updateStar();
    }

    private void selectTab(int i) {
        current = i;
        urlEditedByUser = false;
        geckoView.setSession(session());
        updateNav(false, false);
        rebuildTabs();
        updateIncognitoUi();
        updateStar();
    }

    private void load(String uri) {
        urlEditedByUser = false;
        GeckoSession s = session();
        if (s == null) return;
        String u = sanitize(uri);
        if (u == null) { showSnack("This link type is not supported"); return; }
        try { s.loadUri(u); } catch (Throwable t) {
            android.util.Log.w("SUBBM", "loadUri failed", t);
        }
    }

    /** Block non-browser schemes that crash or are dangerous (javascript:, mailto:, intent:, content:, file:// outside assets). */
    private static String sanitize(String uri) {
        if (uri == null || uri.isEmpty()) return null;
        if (uri.startsWith("http://") || uri.startsWith("https://")
                || uri.startsWith("about:")
                || uri.startsWith("resource://android/assets")
                || uri.startsWith("data:text/html")) {
            return uri;
        }
        return null;
    }

    private void navigate(String raw) {
        String input = raw == null ? "" : raw.trim();
        if (input.isEmpty()) { load(BrowserState.homeUrl(this)); return; }
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
        try {
            InputMethodManager imm =
                (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null && urlBar != null)
                imm.hideSoftInputFromWindow(urlBar.getWindowToken(), 0);
        } catch (Throwable ignored) { }
    }

    private void showAddress(String url, boolean canBack) {
        runOnUiThread(() -> {
            currentUrl = url;
            if (!urlEditedByUser) {
                if (url == null || url.startsWith("resource://android/assets")) {
                    urlBar.getText().clear();
                } else {
                    urlBar.setText(url);
                    urlBar.setSelection(urlBar.getText().length());
                }
            }
            updateNav(canBack, false);
            updateStar();
        });
    }

    private void safeGoBack(GeckoSession s) {
        try { s.goBack(); } catch (Throwable ignored) { }
    }

    // ---------------- delegates ----------------

    private GeckoResult<AllowOrDeny> intercept(String uri) {
        if (!AdBlock.enabled(this) || uri == null) return null;
        try {
            String host = Uri.parse(uri).getHost();
            if (host != null && AdBlock.matches(host)) {
                AdBlock.addBlocked(this);
                updateShield();
                return GeckoResult.fromValue(AllowOrDeny.DENY);
            }
        } catch (Exception ignored) { }
        return null;
    }

    private class ContentBlockingDelegate implements ContentBlocking.Delegate {
        @Override
        public void onContentBlocked(GeckoSession session,
                ContentBlocking.BlockEvent event) {
            if (!AdBlock.enabled(MainActivity.this)) return;
            AdBlock.addBlocked(MainActivity.this);
            updateShield();
        }
    }

    private class NavigationDelegate implements GeckoSession.NavigationDelegate {
        @Override
        public GeckoResult<AllowOrDeny> onLoadRequest(GeckoSession session,
                LoadRequest request) {
            String uri = request.uri;
            if (uri == null) return GeckoResult.fromValue(AllowOrDeny.DENY);
            // non-browser schemes: deny instead of handing Gecko something it
            // can't resolve (javascript:/mailto:/intent:/file: caused crashes)
            if (sanitize(uri) == null) {
                if (uri.startsWith("javascript:")) {
                    // inline script URLs are silently dropped (XSS-ish, no crash)
                    return GeckoResult.fromValue(AllowOrDeny.DENY);
                }
                // mailto:, intent:, file:, blob:, content:, unknown schemes
                showSnack("Can't open: unsupported link type");
                return GeckoResult.fromValue(AllowOrDeny.DENY);
            }
            // direct file-link taps become downloads
            if (looksDownloadable(uri)
                    && (uri.startsWith("http://") || uri.startsWith("https://"))) {
                manualDownload(uri);
                return GeckoResult.fromValue(AllowOrDeny.DENY);
            }
            // never block the main-frame page the user typed
            if (request.triggerUri == null && request.hasUserGesture) {
                return GeckoResult.fromValue(AllowOrDeny.ALLOW);
            }
            GeckoResult<AllowOrDeny> r = intercept(uri);
            return r != null ? r : GeckoResult.fromValue(AllowOrDeny.ALLOW);
        }
        @Override
        public GeckoResult<AllowOrDeny> onSubframeLoadRequest(GeckoSession session,
                LoadRequest request) {
            String uri = request.uri;
            if (uri != null && sanitize(uri) == null) {
                return GeckoResult.fromValue(AllowOrDeny.DENY);
            }
            GeckoResult<AllowOrDeny> r = intercept(uri);
            return r != null ? r : GeckoResult.fromValue(AllowOrDeny.ALLOW);
        }
        @Override
        public void onLocationChange(GeckoSession session, String url,
                java.util.List<GeckoSession.PermissionDelegate.ContentPermission> perms,
                Boolean isSameDocument) {
            if (session == session()) showAddress(url, true);
            // record main-frame http(s) loads; never while incognito
            int ti = tabs.indexOf(session);
            if (ti >= 0 && !tabPrivate.get(ti)
                    && url != null
                    && (url.startsWith("http://") || url.startsWith("https://"))) {
                BrowserDB.get(MainActivity.this).addHistoryAsync(url, null);
            }
        }
        @Override
        public void onCanGoBack(GeckoSession session, boolean canGoBack) {
            if (session == session()) runOnUiThread(() -> updateNav(canGoBack, false));
        }
        @Override
        public GeckoResult<GeckoSession> onNewSession(GeckoSession session, String uri) {
            if (tabs.size() >= MAX_TABS) return null;
            GeckoSession s = makeTab(incognito);
            if (s == null) return null;
            tabs.add(s);
            tabPrivate.add(incognito);
            current = tabs.size() - 1;
            geckoView.setSession(s);
            rebuildTabs();
            updateIncognitoUi();
            return GeckoResult.fromValue(s);
        }
    }

    private class ProgressDelegate implements GeckoSession.ProgressDelegate {
        @Override
        public void onProgressChange(GeckoSession session, int progress) {
            if (session == session()) showProgress(progress);
        }
        @Override
        public void onPageStart(GeckoSession session, String url) {
            if (session == session()) showProgress(8);
        }
        @Override
        public void onPageStop(GeckoSession session, boolean success) {
            if (session == session()) showProgress(100);
            if (pageCover != null && pageCover.getVisibility() == View.VISIBLE) {
                runOnUiThread(() -> pageCover.setVisibility(View.GONE));
            }
        }
        @Override
        public void onSecurityChange(GeckoSession session, SecurityInformation info) {
        }
    }

    private boolean fullscreenChromeHidden = false;

    private class ContentDelegate implements GeckoSession.ContentDelegate {
        @Override
        public void onTitleChange(GeckoSession session, String title) {
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                setTitle(title == null || title.isEmpty() ? "SUB-BROWSER" : title);
            });
            if (session == session()) {
                currentTitle = title;
                if (!isPrivateTab() && currentUrl != null)
                    BrowserDB.get(MainActivity.this)
                            .updateHistoryTitleAsync(currentUrl, title);
            }
        }
        @Override
        public void onCrash(GeckoSession session) {
            // Content process died: don't just toast — close the dead session
            // and rebuild the tab so the UI recovers without a manual reload.
            runOnUiThread(() -> {
                int ti = tabs.indexOf(session);
                android.util.Log.w("SUBBM", "content crash on tab " + ti);
                if (ti < 0) {   // stale/closed already
                    Toast.makeText(MainActivity.this,
                        "Content crashed; recovered.", Toast.LENGTH_LONG).show();
                    return;
                }
                String url = null;
                if (ti == current && currentUrl != null) url = currentUrl;
                try { session.close(); } catch (Throwable ignored) { }
                GeckoSession ns = makeTab(tabPrivate.get(ti));
                if (ns == null) return;
                tabs.set(ti, ns);
                if (ti == current) geckoView.setSession(ns);
                if (url != null) ns.loadUri(url);
                rebuildTabs();
                Toast.makeText(MainActivity.this,
                    "Content crashed; tab recovered.", Toast.LENGTH_LONG).show();
            });
        }
        @Override
        public void onExternalResponse(GeckoSession session, WebResponse response) {
            handleExternalResponse(session, response);
        }
        @Override
        public void onFullScreen(GeckoSession session, boolean inFullscreen) {
            runOnUiThread(() -> {
                fullscreenChromeHidden = inFullscreen;
                if (tabScroll != null) tabScroll.setVisibility(
                        inFullscreen ? View.GONE : View.VISIBLE);
                toolbarRow.setVisibility(
                        inFullscreen ? View.GONE : View.VISIBLE);
                if (progressBar != null) progressBar.setVisibility(
                        inFullscreen ? View.GONE : progressBar.getVisibility());
            });
        }
    }

    @Override
    public void onBackPressed() {
        if (menuPopup != null && menuPopup.isShowing()) { dismissMenu(); return; }
        if (overlay.getVisibility() == View.VISIBLE) { hideOverlay(); return; }
        if (findBar.getVisibility() == View.VISIBLE) { closeFind(); return; }
        GeckoSession s = session();
        if (s != null && currentUrl != null && !currentUrl.startsWith("resource://")) {
            safeGoBack(s);
            return;
        }
        // at root: never exit silently on first back — double-back confirms
        if (!rootBackArmed) {
            rootBackArmed = true;
            showSnack("Press back again to close");
            getWindow().getDecorView().postDelayed(() -> rootBackArmed = false, 2000);
            return;
        }
        super.onBackPressed();
    }
    private boolean rootBackArmed = false;

    @Override
    protected void onPause() {
        super.onPause();
        try { dismissMenu(); } catch (Throwable ignored) { }
        // GeckoView wants the session detached when the view goes away.
        try {
            if (geckoView != null && geckoView.getSession() != null) {
                // keep session attached: GeckoView handles pause internally,
                // detaching here caused blank tabs on resume. Nothing to do.
            }
        } catch (Throwable ignored) { }
    }

    @Override
    protected void onDestroy() {
        try { dismissMenu(); } catch (Throwable ignored) { }
        for (GeckoSession s : tabs) {
            try { s.close(); } catch (Throwable ignored) { }
        }
        tabs.clear();
        tabPrivate.clear();
        // NOTE: runtime is intentionally kept alive (static) — GeckoRuntime
        // is a singleton tied to the Application; closing it on activity
        // destroy breaks restart-after-rotation within the same process.
        super.onDestroy();
    }
}
