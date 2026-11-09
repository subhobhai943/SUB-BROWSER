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
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {

    private static final int MAX_TABS = 3;
    private static final String UA =
        "Mozilla/5.0 (Android 14; Mobile; rv:159.0) Gecko/159.0 Firefox/159.0 SUBBrowser/1.0";

    private static final int BAR_NORMAL = Color.parseColor("#0C0F14");
    private static final int BAR_INCOGNITO = Color.parseColor("#1A0F2E");
    private static final int ACCENT = Color.parseColor("#29ABE2");

    private static GeckoRuntime runtime;

    private GeckoView geckoView;
    private EditText urlBar;
    private ImageView backBtn, fwdBtn, starBtn;
    private LinearLayout tabStrip;
    private HorizontalScrollView tabScroll;
    private LinearLayout toolbarRow;
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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        try {
            tfBold = Typeface.createFromAsset(getAssets(), "fonts/Poppins-Bold.ttf");
        } catch (Exception e) { tfBold = Typeface.DEFAULT_BOLD; }

        AdBlock.load(this);
        if (runtime == null) {
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
        }
        // apply persisted zoom (80-150 %) via content font scale
        runtime.getSettings().setFontSizeFactor(BrowserState.zoom(this) / 100f);

        int bg = Color.parseColor("#050505");
        final float dp = getResources().getDisplayMetrics().density;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(bg);

        // ---- row 1: tab chip strip ----
        HorizontalScrollView sv = new HorizontalScrollView(this);
        tabScroll = sv;
        sv.setHorizontalScrollBarEnabled(false);
        sv.setBackgroundColor(BAR_INCOGNITO);
        tabStrip = new LinearLayout(this);
        tabStrip.setOrientation(LinearLayout.HORIZONTAL);
        tabStrip.setGravity(Gravity.CENTER_VERTICAL);
        tabStrip.setPadding(dp(8), dp(6), dp(8), dp(6));
        sv.addView(tabStrip);
        root.addView(sv, new LinearLayout.LayoutParams(-1, dp(48 * dp)));

        // ---- row 2: logo + icons + pill + star + menu ----
        toolbarRow = new LinearLayout(this);
        toolbarRow.setOrientation(LinearLayout.HORIZONTAL);
        toolbarRow.setGravity(Gravity.CENTER_VERTICAL);
        toolbarRow.setBackgroundColor(BAR_NORMAL);
        toolbarRow.setPadding(dp(4 * dp), dp(4 * dp), dp(6 * dp), dp(10 * dp));

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.dragon_mini);
        logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        logo.setPadding(dp(4 * dp), dp(4 * dp), dp(4 * dp), dp(4 * dp));
        logo.setBackgroundResource(R.drawable.icon_btn_bg);
        logo.setOnClickListener(v -> load(BrowserState.homeUrl(this)));
        toolbarRow.addView(logo, lp(dp(24 * dp), dp(24 * dp), 0, 0));

        backBtn = iconButton(R.drawable.ic_back,
                v -> { GeckoSession s = session(); if (s != null) s.goBack(); });
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
        pill.setPadding(dp(12 * dp), 0, dp(6 * dp), 0);
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
        urlBar.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        urlBar.setTypeface(tfBold);
        urlBar.setPadding(dp(8 * dp), 0, dp(4 * dp), 0);
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
        pill.addView(urlBar, new LinearLayout.LayoutParams(0, dp(42 * dp), 1f));
        stylePill(pill, false, dp);
        toolbarRow.addView(pill, lp(0, dp(42 * dp), 1f, dp(6 * dp)));

        // star: bookmark current page
        starBtn = new ImageView(this);
        starBtn.setImageResource(R.drawable.ic_star);
        starBtn.setBackgroundResource(R.drawable.icon_btn_bg);
        starBtn.setPadding(dp(8 * dp), dp(8 * dp), dp(8 * dp), dp(8 * dp));
        starBtn.setOnClickListener(v -> toggleBookmark());
        toolbarRow.addView(starBtn, lp(dp(38 * dp), dp(38 * dp), 0, dp(2 * dp)));

        // 3-dot overflow menu
        ImageView menuBtn = new ImageView(this);
        menuBtn.setImageResource(R.drawable.ic_menu);
        menuBtn.setBackgroundResource(R.drawable.icon_btn_bg);
        menuBtn.setPadding(dp(8 * dp), dp(8 * dp), dp(8 * dp), dp(8 * dp));
        menuBtn.setOnClickListener(v -> openMenu(menuBtn));
        toolbarRow.addView(menuBtn, lp(dp(38 * dp), dp(38 * dp), 0, dp(2 * dp)));

        // shield badge: tap opens adblock toggle dialog
        FrameLayout shieldWrap = new FrameLayout(this);
        ImageView shield = new ImageView(this);
        shield.setImageResource(R.drawable.ic_shield);
        shield.setBackgroundResource(R.drawable.icon_btn_bg);
        shield.setPadding(dp(7 * dp), dp(7 * dp), dp(7 * dp), dp(7 * dp));
        shield.setOnClickListener(v -> showAdblockDialog());
        shieldWrap.addView(shield, new FrameLayout.LayoutParams(dp(38 * dp), dp(38 * dp)));
        shieldBadge = new TextView(this);
        shieldBadge.setTextColor(Color.WHITE);
        shieldBadge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9);
        shieldBadge.setTypeface(tfBold);
        shieldBadge.setGravity(Gravity.CENTER);
        GradientDrawable badgeBg = new GradientDrawable();
        badgeBg.setShape(GradientDrawable.OVAL);
        badgeBg.setColor(ACCENT);
        shieldBadge.setBackground(badgeBg);
        FrameLayout.LayoutParams bfp = new FrameLayout.LayoutParams(dp(18 * dp), dp(18 * dp));
        bfp.gravity = Gravity.TOP | Gravity.END;
        shieldWrap.addView(shieldBadge, bfp);
        toolbarRow.addView(shieldWrap, lp(dp(38 * dp), dp(38 * dp), 0, dp(2 * dp)));

        root.addView(toolbarRow, new LinearLayout.LayoutParams(-1, -2));

        content = new FrameLayout(this);
        geckoView = new GeckoView(this);
        geckoView.setBackgroundColor(bg);
        content.addView(geckoView, new FrameLayout.LayoutParams(-1, -1));

        // ---- find-in-page bar (hidden) ----
        findBar = buildFindBar(dp);
        FrameLayout.LayoutParams fbp = new FrameLayout.LayoutParams(-1, dp(52 * dp));
        fbp.gravity = Gravity.TOP;
        content.addView(findBar, fbp);

        // ---- snackbar-style status line (hidden) ----
        snack = new TextView(this);
        snack.setTextColor(Color.WHITE);
        snack.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        snack.setTypeface(tfBold);
        snack.setPadding(dp(16 * dp), dp(12 * dp), dp(16 * dp), dp(12 * dp));
        snack.setVisibility(View.GONE);
        GradientDrawable sbg = new GradientDrawable();
        sbg.setCornerRadius(dp(12 * dp));
        sbg.setColor(Color.parseColor("#1E2A35"));
        snack.setBackground(sbg);
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(-2, -2);
        slp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        slp.setMargins(0, 0, 0, (int) (18 * dp));
        content.addView(snack, slp);

        // ---- bookmarks/history overlay (hidden) ----
        overlay = buildOverlay(dp);
        content.addView(overlay, new FrameLayout.LayoutParams(-1, -1));

        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1f));

        setContentView(root);
        newTab();
        updateIncognitoUi();
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
        bg.setCornerRadius(dp(21 * dp));
        bg.setColor(Color.parseColor("#12181f"));
        bg.setStroke(dp((focused ? 2 : 1) * dp),
                focused ? ACCENT : Color.parseColor("#1e2a35"));
        pill.setBackground(bg);
        pill.setElevation(dp(focused ? 6 * dp : 0));
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
        float dp = getResources().getDisplayMetrics().density;
        iv.setPadding(dp(7 * dp), dp(7 * dp), dp(7 * dp), dp(7 * dp));
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iv.setOnClickListener(l);
        LinearLayout.LayoutParams p = lp(dp(36 * dp), dp(36 * dp), 0, dp(1 * dp));
        p.setMarginEnd(dp(1 * dp));
        iv.setLayoutParams(p);
        return iv;
    }

    private ImageView iconInline(int res, int tint, float dp) {
        ImageView iv = new ImageView(this);
        iv.setImageResource(res);
        iv.setColorFilter(tint);
        iv.setLayoutParams(new LinearLayout.LayoutParams(dp(16 * dp), dp(16 * dp)));
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
            () -> snack.setVisibility(View.GONE);

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
        android.util.Log.i("SUBBM", "toggleBookmark url=" + currentUrl);
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
        final float dp = getResources().getDisplayMetrics().density;
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
            t.setPadding(dp(24 * dp), dp(60 * dp), dp(24 * dp), 0);
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
                        h.setPadding(dp(20 * dp), dp(16 * dp), dp(20 * dp), dp(6 * dp));
                        overlayList.addView(h,
                                new LinearLayout.LayoutParams(-1, -2));
                    }
                }
                overlayList.addView(
                        listRow(e, onTap, onDelete, dp));
            }
        }

        if (actionLabel != null) {
            TextView act = new TextView(this);
            act.setText(actionLabel.toUpperCase(Locale.US));
            act.setTextColor(Color.parseColor("#FF6B6B"));
            act.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            act.setTypeface(tfBold);
            act.setGravity(Gravity.CENTER);
            act.setPadding(dp(20 * dp), dp(22 * dp), dp(20 * dp), dp(28 * dp));
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
            java.util.function.Consumer<BrowserDB.Entry> onDelete, float dp) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(20 * dp), dp(13 * dp), dp(14 * dp), dp(13 * dp));
        GradientDrawable rg = new GradientDrawable();
        rg.setColor(Color.parseColor("#0E141a"));
        rg.setCornerRadius(dp(12 * dp));
        row.setBackground(rg);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
        rlp.setMargins(dp(14 * dp), dp(4 * dp), dp(14 * dp), dp(4 * dp));
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
        u.setPadding(dp(10 * dp), 0, dp(6 * dp), 0);
        row.addView(u, new LinearLayout.LayoutParams(0, -2, 1.2f));

        if (onDelete != null) {
            ImageView x = new ImageView(this);
            x.setImageResource(R.drawable.ic_close);
            x.setColorFilter(Color.parseColor("#66788a"));
            x.setPadding(dp(8 * dp), dp(8 * dp), dp(8 * dp), dp(8 * dp));
            x.setOnClickListener(v -> onDelete.accept(e));
            row.addView(x, new LinearLayout.LayoutParams(dp(28 * dp), dp(28 * dp)));
        }

        row.setOnClickListener(v -> { if (onTap != null) onTap.accept(e); });
        return row;
    }

    private View buildOverlay(float dp) {
        FrameLayout fl = new FrameLayout(this);
        fl.setBackgroundColor(Color.parseColor("#050505"));
        fl.setVisibility(View.GONE);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(20 * dp), dp(18 * dp), dp(12 * dp), dp(10 * dp));

        overlayTitle = new TextView(this);
        overlayTitle.setTextColor(Color.WHITE);
        overlayTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        overlayTitle.setTypeface(tfBold);
        head.addView(overlayTitle, new LinearLayout.LayoutParams(0, -2, 1f));

        ImageView close = new ImageView(this);
        close.setImageResource(R.drawable.ic_close);
        close.setColorFilter(Color.parseColor("#66788a"));
        close.setPadding(dp(10 * dp), dp(10 * dp), dp(10 * dp), dp(10 * dp));
        close.setOnClickListener(v -> hideOverlay());
        head.addView(close, new LinearLayout.LayoutParams(dp(40 * dp), dp(40 * dp)));
        fl.addView(head, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        ScrollView sc = new ScrollView(this);
        overlayList = new LinearLayout(this);
        overlayList.setOrientation(LinearLayout.VERTICAL);
        overlayList.setPadding(0, dp(64 * dp), 0, 0);
        sc.addView(overlayList, new ScrollView.LayoutParams(-1, -2));
        fl.addView(sc, new FrameLayout.LayoutParams(-1, -1));
        return fl;
    }

    private void hideOverlay() { overlay.setVisibility(View.GONE); }

    // ---------------- find in page ----------------

    private View buildFindBar(float dp) {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(12 * dp), 0, dp(12 * dp), 0);
        bar.setVisibility(View.GONE);
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
        bar.addView(findInput, new LinearLayout.LayoutParams(0, dp(44 * dp), 1f));

        findCount = new TextView(this);
        findCount.setTextColor(Color.parseColor("#66788a"));
        findCount.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        findCount.setTypeface(tfBold);
        findCount.setMinWidth(dp(64 * dp));
        findCount.setGravity(Gravity.CENTER);
        bar.addView(findCount, new LinearLayout.LayoutParams(-2, -2));

        ImageView prev = iconButton(R.drawable.ic_back, v -> doFind(true));
        ImageView next = iconButton(R.drawable.ic_forward, v -> doFind(false));
        bar.addView(prev); bar.addView(next);

        ImageView close = new ImageView(this);
        close.setImageResource(R.drawable.ic_close);
        close.setColorFilter(Color.parseColor("#66788a"));
        close.setPadding(dp(10 * dp), dp(10 * dp), dp(10 * dp), dp(10 * dp));
        close.setOnClickListener(v -> closeFind());
        bar.addView(close, new LinearLayout.LayoutParams(dp(36 * dp), dp(36 * dp)));

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
        if (s != null) { s.getFinder().clear(); }
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
            s.getFinder().clear();
            findTerm = q;
        }
        if (q.isEmpty()) { findCount.setText(""); return; }
        s.getFinder().setDisplayFlags(GeckoSession.FINDER_DISPLAY_HIGHLIGHT_ALL);
        s.getFinder().find(q, flags).accept(res -> {
            runOnUiThread(() -> {
                if (res == null || !res.found) findCount.setText("0/0");
                else findCount.setText(res.current + 1 + "/" + res.total);
            });
        }, err -> runOnUiThread(() -> findCount.setText("0/0")));
    }

    // ---------------- zoom ----------------

    private void showZoomMenu() {
        String[] levels = {"80%", "90%", "100%", "110%", "125%", "150%"};
        int cur = BrowserState.zoom(this);
        String[] items = new String[levels.length];
        for (int i = 0; i < levels.length; i++)
            items[i] = levels[i] + (BrowserState.zoom(this)
                    == Integer.parseInt(levels[i].replace("%", "")) ? "  \u25CF" : "");
        new AlertDialog.Builder(this)
            .setTitle("Zoom \u2014 page text scale")
            .setItems(items, (d, w) -> {
                int pct = Integer.parseInt(levels[w].replace("%", ""));
                BrowserState.setZoom(this, pct);
                runtime.getSettings().setFontSizeFactor(pct / 100f);
                Toast.makeText(this, "Zoom " + pct + "%",
                        Toast.LENGTH_SHORT).show();
            })
            .show();
    }

    // ---------------- home page setting ----------------

    private void showHomeSetting() {
        final float dp = getResources().getDisplayMetrics().density;
        final EditText in = new EditText(this);
        in.setText(BrowserState.homeUrl(this));
        in.setTextColor(Color.WHITE);
        in.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        in.setSingleLine(true);
        FrameLayout wrap = new FrameLayout(this);
        wrap.addView(in, new FrameLayout.LayoutParams(-1, -2));
        wrap.setPadding(dp(20 * dp), dp(10 * dp), dp(20 * dp), 0);
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
            s.close();
            GeckoSession ns = makeTab(incognito);
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
            if (tabScroll != null) tabScroll.setBackgroundColor(bar);
            updateStar();
        });
    }

    // ---------------- overflow menu ----------------

    private void openMenu(View anchor) {
        final float dp = getResources().getDisplayMetrics().density;
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#12181f"));
        bg.setCornerRadius(dp(14 * dp));
        bg.setStroke(dp(dp), Color.parseColor("#2a3a48"));
        list.setBackground(bg);
        list.setPadding(dp(6 * dp), dp(8 * dp), dp(6 * dp), dp(8 * dp));
        list.setElevation(dp(12 * dp));

        addMenuItem(list, "Bookmarks", v -> { dismissMenu(); showBookmarks(); }, dp);
        addMenuItem(list, "History", v -> { dismissMenu(); showHistory(); }, dp);
        addMenuItem(list, "Find in page", v -> { dismissMenu(); openFind(); }, dp);
        addMenuItem(list, "Zoom  " + BrowserState.zoom(this) + "%",
                v -> { dismissMenu(); showZoomMenu(); }, dp);
        addMenuItem(list, "Incognito  " + (incognito ? "ON" : "OFF"),
                v -> { dismissMenu(); toggleIncognito(); }, dp);
        addMenuItem(list, "Set home page", v -> { dismissMenu(); showHomeSetting(); }, dp);

        menuPopup = new PopupWindow(list, dp(230 * dp), -2, true);
        menuPopup.showAsDropDown(anchor, 0, dp(6 * dp));
    }

    private void dismissMenu() {
        if (menuPopup != null && menuPopup.isShowing()) menuPopup.dismiss();
    }

    private void addMenuItem(LinearLayout list, String label,
            View.OnClickListener l, float dp) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextColor(Color.WHITE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        t.setTypeface(tfBold);
        t.setPadding(dp(16 * dp), dp(13 * dp), dp(16 * dp), dp(13 * dp));
        t.setOnClickListener(l);
        list.addView(t, new LinearLayout.LayoutParams(-1, -2));
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
        android.util.Log.i("SUBDL", "download delegate fired (intercept) for " + uri);
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
                android.util.Log.i("SUBDL", "saved " + out.getAbsolutePath()
                        + " bytes=" + n);
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
        android.util.Log.i("SUBDL", "download delegate fired (external) for " + uri);

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
            android.util.Log.i("SUBDL", "saved " + out.getAbsolutePath()
                    + " bytes=" + n);
            showSnack("Saved to Downloads (" + fname + ")");
        }, "subdl").start();
    }

    // ---------------- tabs ----------------

    private GeckoSession session() {
        return (current >= 0 && current < tabs.size()) ? tabs.get(current) : null;
    }

    private GeckoSession makeTab(boolean priv) {
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
    }

    private void newTab() {
        if (tabs.size() >= MAX_TABS) {
            Toast.makeText(this, "Max " + MAX_TABS + " tabs", Toast.LENGTH_SHORT).show();
            return;
        }
        tabs.add(makeTab(incognito));
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
        if (s != null) s.close();
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
            float dp = getResources().getDisplayMetrics().density;
            for (int i = 0; i < tabs.size(); i++) {
                final int idx = i;
                boolean priv = tabPrivate.get(i);
                LinearLayout chip = new LinearLayout(this);
                chip.setOrientation(LinearLayout.HORIZONTAL);
                chip.setGravity(Gravity.CENTER_VERTICAL);
                chip.setPadding(dp(14 * dp), 0, dp(8 * dp), 0);
                GradientDrawable cb = new GradientDrawable();
                cb.setCornerRadius(dp(10 * dp));
                if (i == current) {
                    cb.setColor(priv ? Color.parseColor("#1C1430")
                            : Color.parseColor("#16202a"));
                    cb.setStroke(dp(dp), priv ? Color.parseColor("#3A2A5E")
                            : Color.parseColor("#2a3a48"));
                } else {
                    cb.setColor(0x00000000);
                    cb.setStroke(dp(dp), Color.parseColor("#141c24"));
                }
                chip.setBackground(cb);
                if (i == current) chip.setElevation(dp(3 * dp));

                TextView t = new TextView(this);
                t.setText(priv ? "Private" : "Tab " + (i + 1));
                t.setTextColor(i == current
                        ? (priv ? Color.parseColor("#B388FF") : ACCENT)
                        : Color.parseColor("#66788a"));
                t.setTextSize(12);
                t.setTypeface(tfBold);
                t.setOnClickListener(v -> selectTab(idx));
                chip.addView(t, new LinearLayout.LayoutParams(dp(64 * dp), -1));

                ImageView x = new ImageView(this);
                x.setImageResource(R.drawable.ic_close);
                x.setColorFilter(i == current ? ACCENT
                        : Color.parseColor("#44525e"));
                x.setPadding(dp(6 * dp), dp(6 * dp), dp(6 * dp), dp(6 * dp));
                x.setOnClickListener(v -> { if (idx == current) closeCurrentTab();
                    else { GeckoSession s2 = tabs.remove(idx); s2.close();
                           tabPrivate.remove(idx);
                           if (idx <= current) current--;
                           geckoView.setSession(session()); rebuildTabs(); } });
                chip.addView(x, new LinearLayout.LayoutParams(dp(24 * dp), dp(24 * dp)));

                LinearLayout.LayoutParams llp = lp(-2, dp(36 * dp), 0, dp(5 * dp));
                tabStrip.addView(chip, llp);
            }
        });
    }

    private void selectTab(int i) {
        current = i;
        geckoView.setSession(session());
        updateNav(false, false);
        rebuildTabs();
        updateIncognitoUi();
        updateStar();
    }

    private void load(String uri) {
        GeckoSession s = session();
        if (s != null) s.loadUri(uri);
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
        InputMethodManager imm =
            (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(urlBar.getWindowToken(), 0);
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

    // ---------------- delegates ----------------

    private GeckoResult<AllowOrDeny> intercept(String uri) {
        if (!AdBlock.enabled(this) || uri == null) return null;
        try {
            String host = Uri.parse(uri).getHost();
            if (host != null && AdBlock.matches(host)) {
                AdBlock.addBlocked(this);
                updateShield();
                android.util.Log.i("adblock", "blocked " + host);
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
            // direct file-link taps become downloads
            if (looksDownloadable(request.uri)
                    && (request.uri.startsWith("http://") || request.uri.startsWith("https://"))) {
                manualDownload(request.uri);
                return GeckoResult.fromValue(AllowOrDeny.DENY);
            }
            // never block the main-frame page the user typed
            if (request.triggerUri == null && request.hasUserGesture) {
                return GeckoResult.fromValue(AllowOrDeny.ALLOW);
            }
            GeckoResult<AllowOrDeny> r = intercept(request.uri);
            return r != null ? r : GeckoResult.fromValue(AllowOrDeny.ALLOW);
        }
        @Override
        public GeckoResult<AllowOrDeny> onSubframeLoadRequest(GeckoSession session,
                LoadRequest request) {
            GeckoResult<AllowOrDeny> r = intercept(request.uri);
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
                BrowserDB.get(MainActivity.this).addHistory(url, null);
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
    }

    private boolean fullscreenChromeHidden = false;

    private class ContentDelegate implements GeckoSession.ContentDelegate {
        @Override
        public void onTitleChange(GeckoSession session, String title) {
            runOnUiThread(() -> setTitle(
                title == null || title.isEmpty() ? "SUB-BROWSER" : title));
            if (session == session()) {
                currentTitle = title;
                if (!isPrivateTab() && currentUrl != null)
                    BrowserDB.get(MainActivity.this)
                            .updateHistoryTitle(currentUrl, title);
            }
        }
        @Override
        public void onCrash(GeckoSession session) {
            runOnUiThread(() -> Toast.makeText(MainActivity.this,
                "Content crashed; press reload.", Toast.LENGTH_LONG).show());
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
            });
        }
    }

    @Override
    public void onBackPressed() {
        if (overlay.getVisibility() == View.VISIBLE) { hideOverlay(); return; }
        if (findBar.getVisibility() == View.VISIBLE) { closeFind(); return; }
        if (menuPopup != null && menuPopup.isShowing()) { dismissMenu(); return; }
        GeckoSession s = session();
        if (s != null) s.goBack(); else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        for (GeckoSession s : tabs) s.close();
        tabs.clear();
        tabPrivate.clear();
        super.onDestroy();
    }
}
