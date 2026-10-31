package home.lite.tv;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.TransitionDrawable;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Лёгкий домашний экран в духе tvOS: абстрактный фон, закреплённый док чуть выше середины,
 * под разделителем — прозрачная зона, из которой по кнопке «вниз» проявляется сетка всех приложений.
 */
public class MainActivity extends Activity {

    static final String SETTINGS_KEY = "#settings";
    /** Порядок по умолчанию; эти же приложения по умолчанию в доке. */
    private static final List<String> DEFAULT_DOCK = Arrays.asList(
            "com.google.android.youtube.tv", "com.android.tv", "com.mediatek.wwtv.tvcenter",
            "tv.twitch.android.app", "com.android.vending", SETTINGS_KEY);
    /** com.android.tv.settings открывает то же, что своя плитка «Настройки», — второй раз не показываем. */
    private static final List<String> NEVER_SHOW = Arrays.asList("com.mitv.dream", "com.xiaomi.mitv.tvmanager",
            "com.android.tv.settings");
    private static final Map<String, String> NAMES = new HashMap<>();
    static {
        NAMES.put("com.google.android.youtube.tv", "YouTube");
        NAMES.put("com.android.tv", "Телевидение");
        NAMES.put("com.mediatek.wwtv.tvcenter", "Телевидение");
        NAMES.put("com.android.vending", "Play Маркет");
    }

    private static final int SIDE = 64, DOCK_GAP = 24, GRID_W = 140, GRID_H = 79, GRID_GAP = 24;
    private static final float FOCUS_SCALE = 1.12f;

    static final class App {
        String key, label;
        Intent intent;
        Bitmap banner;
    }

    private Prefs prefs;
    private float dp;
    private final Handler handler = new Handler();
    private final Random rnd = new Random();
    private final Map<String, Bitmap> washes = new HashMap<>();

    private ImageView artView, washBack, washFront;
    private FrameLayout washBox, content;
    private View topBar, gridBg;
    private Widgets.Pill searchPill, netPill;
    private TextView title, clock, date, caption, netBadge, quoteText, quoteAuthor;
    private LinearLayout quoteBox;
    private String[] quote;
    private PageScroll page;
    private View topSpacer;
    private HorizontalScrollView dockScroller;
    private LinearLayout dockRow, grid;
    private FrameLayout gridBox;
    private SettingsPanel panel;
    private AppMenu appMenu;

    private List<App> apps = new ArrayList<>();
    private String washKey, focusKey, artKey;
    private boolean gridMode, resumed, appsDirty;
    private Runnable pendingWash;
    private ValueAnimator driftAnim, nightAnim;
    /** 0 — день, 1 — полный ночной режим. */
    private float night = -1f;
    /** Экран гас (ТВ выключали пультом) — при следующем показе плавно проявиться. */
    private boolean introPending = true;
    private ConnectivityManager.NetworkCallback netCallback;
    /** Номер загрузки фона: устаревшая фоновая декодировка не перебьёт более новую. */
    private int artLoad;


    private final BroadcastReceiver timeReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { updateClock(); }
    };
    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            // Лаунчер может ожить ещё при тёмном экране — тогда появление ждёт, пока экран загорится
            if (Intent.ACTION_SCREEN_OFF.equals(i.getAction())) {
                introPending = true;
                // Прячем заранее: подсветка ТВ загорается с задержкой, и появление должно быть видно целиком
                if (prefs.on(Prefs.INTRO, true)) hideForIntro();
                // ТВ выключили — самое время тихо поставить скачанное обновление
                if (prefs.on(Prefs.AUTO_UPDATE, true)) Updater.installOnScreenOff(MainActivity.this);
            } else if (resumed) {
                maybeIntro();
            }
        }
    };
    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            // Пока лаунчер скрыт, баннеры не перечитываем — только при возврате
            if (resumed) reloadApps(); else appsDirty = true;
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = new Prefs(this);
        dp = getResources().getDisplayMetrics().density;
        setContentView(buildUi());
        // Чёрное окно под полноэкранным фоном — лишняя заливка каждого кадра; слабому GPU это заметно
        getWindow().setBackgroundDrawable(null);

        IntentFilter pf = new IntentFilter();
        pf.addAction(Intent.ACTION_PACKAGE_ADDED);
        pf.addAction(Intent.ACTION_PACKAGE_REMOVED);
        pf.addAction(Intent.ACTION_PACKAGE_CHANGED);
        pf.addDataScheme("package");
        registerReceiver(packageReceiver, pf);
        IntentFilter sf = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        sf.addAction(Intent.ACTION_SCREEN_ON);
        registerReceiver(screenReceiver, sf);

        reloadApps();
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(packageReceiver);
        unregisterReceiver(screenReceiver);
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        IntentFilter tf = new IntentFilter(Intent.ACTION_TIME_TICK);
        tf.addAction(Intent.ACTION_TIME_CHANGED);
        tf.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        registerReceiver(timeReceiver, tf);
        updateClock();
        if (appsDirty) {
            appsDirty = false;
            reloadApps();
        }
        if (panel == null && appMenu == null) restoreFocus();
        applyNight(false);
        maybeIntro();
        artView.post(new Runnable() { @Override public void run() { drift(); } });
        rotatePicture.run();
        // Тихая проверка обновлений: скачается сейчас, поставится, когда лаунчер уйдёт с экрана
        if (prefs.on(Prefs.AUTO_UPDATE, true)
                && System.currentTimeMillis() - prefs.time(Prefs.UPDATE_AT) > Updater.CHECK_EVERY) {
            Updater.check(this, false, null);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        // Фон отпускается в onStop; декодируем сразу, чтобы не мелькнул чёрный экран
        if (artKey == null) applyBackground(false);
        watchNetwork(true);
        nextQuote();
    }

    @Override
    protected void onStop() {
        // Пока смотрят YouTube или эфир, картинка фона и подсветки не держат память
        artLoad++;
        artKey = null;
        artView.setImageDrawable(null);
        washes.clear();
        washKey = null;
        washBack.setImageDrawable(null);
        washFront.setImageDrawable(null);
        watchNetwork(false);
        super.onStop();
    }

    @Override
    public void onWindowFocusChanged(boolean has) {
        super.onWindowFocusChanged(has);
        // Поверх — голосовой поиск или заставка: фон замирает
        if (has) drift(); else stopDrift();
    }


    /** Смена картинки фона по таймеру: проверяется при возврате на экран и пока он открыт. */
    private final Runnable rotatePicture = new Runnable() {
        @Override public void run() {
            handler.removeCallbacks(this);
            long every = Prefs.ROTATE_MS[prefs.pick(Prefs.ROTATE, Prefs.ROTATE_DEF, Prefs.ROTATE_MS.length)];
            if (!resumed || every == 0 || prefs.bg() < Prefs.BG_PIC) return;
            long now = System.currentTimeMillis(), left = prefs.time(Prefs.PIC_AT) + every - now;
            if (left <= 0) {
                // Случайная фотография из любого фото-набора (пейзажи, авто, животные, города, люди), абстракция не участвует
                int bg = Prefs.BG_PIC + 1 + rnd.nextInt(Prefs.PIC_SETS.length - 1);
                int count = Backgrounds.count(MainActivity.this, Prefs.picSet(bg));
                if (count == 0) return;
                int pic = rnd.nextInt(count);
                if (bg == prefs.bg() && count > 1 && pic == prefs.pick(Prefs.picKey(bg), 0, count)) pic = (pic + 1) % count;
                prefs.put(Prefs.BG, bg);
                prefs.put(Prefs.picKey(bg), pic);
                prefs.putTime(Prefs.PIC_AT, now);
                applyBackground(true);
                left = every;
            }
            handler.postDelayed(this, Math.min(left, 60_000L));
        }
    };

    @Override
    protected void onPause() {
        resumed = false;
        handler.removeCallbacks(rotatePicture);
        unregisterReceiver(timeReceiver);
        stopDrift();
        super.onPause();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // «Домой» на домашнем экране: закрыть настройки и вернуться к началу дока
        if (panel != null) closePanel();
        if (appMenu != null) closeAppMenu();
        focusKey = null;
        dockScroller.smoothScrollTo(0, 0);
        restoreFocus();
    }

    @Override
    public void onBackPressed() {
        if (appMenu != null) closeAppMenu();
        else if (panel != null && !panel.back()) closePanel();
        else if (gridMode) restoreFocus();
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent ev) {
        if (code == KeyEvent.KEYCODE_MENU && panel == null && appMenu == null) {
            openPanel();
            return true;
        }
        return super.onKeyDown(code, ev);
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_MODERATE) washes.clear();
    }

    // ---------------------------------------------------------------- Разметка

    private int px(float v) { return Math.round(v * dp); }

    private View buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        artView = new ImageView(this);
        artView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        artView.setScaleX(1.18f);
        artView.setScaleY(1.18f);
        root.addView(artView, match());

        washBox = new FrameLayout(this);
        washBack = new ImageView(this);
        washFront = new ImageView(this);
        for (ImageView iv : new ImageView[] {washBack, washFront}) {
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            washBox.addView(iv, match());
        }
        washFront.setAlpha(0f);
        root.addView(washBox, match());

        View shade = new View(this);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] {0x4D000000, 0x14000000, 0x40000000, 0xB3000000}));
        root.addView(shade, match());

        content = new FrameLayout(this);
        content.addView(buildPage(), match());
        topBar = buildTopBar();
        content.addView(topBar, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));
        root.addView(content, match());
        return root;
    }

    private View buildTopBar() {
        FrameLayout top = new FrameLayout(this);
        top.setPadding(px(SIDE), px(30), px(SIDE), 0);
        top.setClipChildren(false);
        top.setClipToPadding(false);

        LinearLayout pills = new LinearLayout(this);
        pills.setClipChildren(false);
        searchPill = new Widgets.Pill(this, Widgets.Pill.MIC, "Поиск");
        searchPill.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { launchSearch(); }
        });
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(px(118), px(38));
        slp.rightMargin = px(12);
        pills.addView(searchPill, slp);
        Widgets.Pill gear = new Widgets.Pill(this, Widgets.Pill.GEAR, null);
        gear.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openPanel(); }
        });
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(px(38), px(38));
        glp.rightMargin = px(12);
        pills.addView(gear, glp);
        // Значок подключения: провод / Wi‑Fi / нет сети; OK — настройки сети
        netPill = new Widgets.Pill(this, Widgets.Pill.WIFI, null);
        netPill.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openNetworkSettings(); }
        });
        pills.addView(netPill, new LinearLayout.LayoutParams(px(38), px(38)));
        top.addView(pills, new FrameLayout.LayoutParams(-2, -2, Gravity.START | Gravity.TOP));

        LinearLayout clockBox = new LinearLayout(this);
        clockBox.setOrientation(LinearLayout.VERTICAL);
        clockBox.setGravity(Gravity.END);
        clock = text(34, Color.WHITE, "sans-serif-light");
        date = text(14, 0xB3FFFFFF, "sans-serif");
        clockBox.addView(clock);
        clockBox.addView(date);
        netBadge = text(13, 0xE6FFFFFF, "sans-serif-medium");
        netBadge.setText("●  Нет сети");
        netBadge.setVisibility(View.GONE);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(-2, -2);
        nlp.topMargin = px(8);
        clockBox.addView(netBadge, nlp);
        top.addView(clockBox, new FrameLayout.LayoutParams(-2, -2, Gravity.END | Gravity.TOP));
        return top;
    }

    private View buildPage() {
        page = new PageScroll(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setClipChildren(false);
        col.setClipToPadding(false);

        topSpacer = new View(this);
        col.addView(topSpacer, new LinearLayout.LayoutParams(-1, px(100)));

        title = text(40, Color.WHITE, "sans-serif-medium");
        title.setShadowLayer(px(12), 0, px(2), 0x80000000);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-1, -2);
        tlp.leftMargin = px(SIDE);
        tlp.rightMargin = px(SIDE);
        col.addView(title, tlp);

        dockScroller = new HorizontalScrollView(this);
        dockScroller.setHorizontalScrollBarEnabled(false);
        dockScroller.setOverScrollMode(View.OVER_SCROLL_NEVER);
        dockScroller.setClipChildren(false);
        dockScroller.setClipToPadding(false);
        dockScroller.setPadding(0, px(18), 0, px(30));
        dockRow = new LinearLayout(this);
        dockRow.setClipChildren(false);
        dockRow.setClipToPadding(false);
        dockRow.setPadding(px(SIDE), 0, px(SIDE), 0);
        dockScroller.addView(dockRow, new FrameLayout.LayoutParams(-2, -2));
        col.addView(dockScroller, new LinearLayout.LayoutParams(-1, -2));

        // Цитата под доком: крупно, чтобы читалась с дивана; в меню приложений уходит
        quoteBox = new LinearLayout(this);
        quoteBox.setOrientation(LinearLayout.VERTICAL);
        quoteText = text(22, 0xF2FFFFFF, "sans-serif-light");
        quoteText.setShadowLayer(px(10), 0, px(1), 0x99000000);
        quoteText.setLineSpacing(px(3), 1f);
        quoteText.setMaxLines(2);
        quoteText.setEllipsize(TextUtils.TruncateAt.END);
        quoteAuthor = text(15, 0xB3FFFFFF, "sans-serif");
        quoteAuthor.setShadowLayer(px(8), 0, px(1), 0x99000000);
        quoteBox.addView(quoteText);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(-2, -2);
        alp.topMargin = px(8);
        quoteBox.addView(quoteAuthor, alp);
        LinearLayout.LayoutParams qlp = new LinearLayout.LayoutParams(-1, -2);
        qlp.leftMargin = px(SIDE);
        qlp.rightMargin = px(SIDE + 120);
        qlp.bottomMargin = px(22);
        col.addView(quoteBox, qlp);

        // Разделитель и подпись к меню
        View line = new View(this);
        line.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[] {0x00FFFFFF, 0x4DFFFFFF, 0x4DFFFFFF, 0x00FFFFFF}));
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(-1, Math.max(1, px(1)));
        llp.leftMargin = px(SIDE - 24);
        llp.rightMargin = px(SIDE - 24);
        col.addView(line, llp);
        caption = text(13, 0x99FFFFFF, "sans-serif-medium");
        caption.setText("ВСЕ ПРИЛОЖЕНИЯ");
        caption.setLetterSpacing(0.1f);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-2, -2);
        clp.leftMargin = px(SIDE);
        clp.topMargin = px(14);
        col.addView(caption, clp);

        // Сетка всех приложений на «стеклянной» подложке
        gridBox = new FrameLayout(this);
        gridBox.setClipChildren(false);
        gridBox.setClipToPadding(false);
        gridBg = new View(this);
        GradientDrawable glass = new GradientDrawable();
        glass.setColor(0x47000000);
        glass.setCornerRadius(px(28));
        gridBg.setBackground(glass);
        gridBg.setAlpha(0f);
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(-1, -1);
        blp.leftMargin = px(SIDE - 28);
        blp.rightMargin = px(SIDE - 28);
        gridBox.addView(gridBg, blp);
        grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setClipChildren(false);
        grid.setClipToPadding(false);
        grid.setPadding(px(SIDE), px(22), px(SIDE), px(8));
        grid.setAlpha(0f);
        gridBox.addView(grid, new FrameLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(-1, -2);
        glp.topMargin = px(12);
        col.addView(gridBox, glp);

        // Запас снизу, чтобы сетку можно было поднять к верху экрана
        col.addView(new View(this), new LinearLayout.LayoutParams(-1, px(420)));
        page.addView(col, new FrameLayout.LayoutParams(-1, -2));
        return page;
    }

    private static FrameLayout.LayoutParams match() { return new FrameLayout.LayoutParams(-1, -1); }

    private TextView text(float sp, int color, String family) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.create(family, Typeface.NORMAL));
        t.setIncludeFontPadding(false);
        return t;
    }

    // ---------------------------------------------------------------- Настройки → экран

    private long dur(long ms) { return prefs.pick(Prefs.SPEED, 0, 2) == 1 ? ms * 55 / 100 : ms; }

    private int[] dockSize() { return Prefs.SIZES[prefs.pick(Prefs.SIZE, 1, Prefs.SIZES.length)]; }

    private float roundness() { return Prefs.ROUNDS[prefs.pick(Prefs.ROUND, 1, Prefs.ROUNDS.length)]; }

    private boolean appBackground() { return prefs.pick(Prefs.BG, Prefs.BG_PIC, Prefs.BG_NAMES.length) == Prefs.BG_APP; }

    /** Применить все настройки: фон, видимость элементов, плитки. */
    void applyPrefs() {
        applyBackground(true);
        boolean appBg = appBackground();
        boolean tint = prefs.on(Prefs.TINT, false);
        washBox.setVisibility(appBg || tint ? View.VISIBLE : View.GONE);
        washBox.setAlpha(appBg ? 1f : 0.42f);
        if (!prefs.on(Prefs.LIVE, true)) {
            stopDrift();
            artView.animate().translationX(0).translationY(0).setStartDelay(0).setDuration(600).start();
        } else {
            drift();
        }

        title.setVisibility(prefs.on(Prefs.TITLE, true) ? View.VISIBLE : View.INVISIBLE);
        clock.setVisibility(prefs.on(Prefs.CLOCK, true) ? View.VISIBLE : View.GONE);
        date.setVisibility(prefs.on(Prefs.DATE, true) ? View.VISIBLE : View.GONE);
        searchPill.setVisibility(prefs.on(Prefs.SEARCH, true) ? View.VISIBLE : View.GONE);
        boolean quotes = Prefs.QUOTE_MS[prefs.pick(Prefs.QUOTES, Prefs.QUOTE_DEF, Prefs.QUOTE_MS.length)] >= 0;
        quoteBox.setVisibility(quotes ? View.VISIBLE : View.GONE);
        if (quotes && quote == null) nextQuote();

        // Док по высоте — чуть выше середины экрана
        int[] size = dockSize();
        float screenDp = getResources().getDisplayMetrics().heightPixels / dp;
        int spacer = Math.max(84, Math.round(screenDp * 0.44f - size[1] / 2f - 18 - 48));
        topSpacer.getLayoutParams().height = px(spacer);
        topSpacer.requestLayout();

        buildTiles();
        // Включили или выключили смену картинок — таймер подхватит сразу
        handler.post(rotatePicture);
        if (night >= 0) applyNight(true);
        if (netCallback != null && Build.VERSION.SDK_INT >= 24) {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            Network active = cm.getActiveNetwork();
            showNetwork(active == null ? null : cm.getNetworkCapabilities(active));
        }
    }

    /**
     * Фон: абстрактный рисуется сразу (он крошечный), картинка при смене декодируется в фоновом потоке
     * и плавно проявляется поверх старой; при возврате на экран (smooth = false) — сразу и без анимации.
     */
    private void applyBackground(boolean smooth) {
        boolean appBg = appBackground();
        artView.setVisibility(appBg ? View.GONE : View.VISIBLE);
        // Чёрная подложка нужна, только когда фон не закрывает экран целиком
        ((View) content.getParent()).setBackgroundColor(appBg ? Color.BLACK : Color.TRANSPARENT);
        if (appBg) return;
        int bg = prefs.bg();
        final int seed = prefs.get(Prefs.SEED, 0);
        final String set = Prefs.picSet(bg);
        final int pic = prefs.pick(Prefs.picKey(bg), 0, Math.max(1, Backgrounds.count(this, set)));
        final String key = bg >= Prefs.BG_PIC ? "pic:" + set + ":" + pic : bg + ":" + seed;
        if (key.equals(artKey)) return;
        boolean first = artKey == null;
        final boolean fade = smooth && !first && artKey.startsWith("pic:") && bg >= Prefs.BG_PIC;
        artKey = key;
        final int load = ++artLoad;
        if (bg < Prefs.BG_PIC) {
            artView.setImageBitmap(Backgrounds.abstractArt(bg, seed));
            return;
        }
        if (!smooth || first) {
            showArt(load, pictureOrFallback(set, pic, seed), false);
            return;
        }
        new Thread(new Runnable() {
            @Override public void run() {
                final Bitmap art = pictureOrFallback(set, pic, seed);
                handler.post(new Runnable() { @Override public void run() { showArt(load, art, fade); } });
            }
        }, "bg").start();
    }

    private Bitmap pictureOrFallback(String set, int pic, int seed) {
        Bitmap art = Backgrounds.picture(this, set, pic);
        return art != null ? art : Backgrounds.abstractArt(0, seed);
    }

    private void showArt(int load, final Bitmap art, boolean fade) {
        if (load != artLoad) return;
        Drawable old = artView.getDrawable();
        if (old instanceof TransitionDrawable) old = ((TransitionDrawable) old).getDrawable(1);
        if (!fade || !(old instanceof BitmapDrawable)) {
            artView.setImageBitmap(art);
            return;
        }
        final TransitionDrawable t = new TransitionDrawable(new Drawable[] {old, new BitmapDrawable(getResources(), art)});
        t.setCrossFadeEnabled(true);
        artView.setImageDrawable(t);
        int ms = (int) dur(900);
        t.startTransition(ms);
        // После перехода оставляем одну картинку: старая освобождается.
        // Только если поверх не начался следующий переход — иначе вернули бы прежнюю картинку.
        artView.postDelayed(new Runnable() {
            @Override public void run() {
                if (artView.getDrawable() == t) artView.setImageBitmap(art);
            }
        }, ms + 100);
    }

    /** Медленный дрейф абстрактного фона — двигается одна картинка, перерисовки нет. */
    private void drift() {
        if (!resumed || !prefs.on(Prefs.LIVE, true) || appBackground() || artView.getWidth() == 0) return;
        if (!hasWindowFocus()) return;
        if (driftAnim != null && driftAnim.isRunning()) return;
        float range = artView.getWidth() * 0.07f;
        final float x0 = artView.getTranslationX(), y0 = artView.getTranslationY();
        final float dx = (rnd.nextFloat() * 2 - 1) * range - x0, dy = (rnd.nextFloat() * 2 - 1) * range * 0.6f - y0;
        driftAnim = ValueAnimator.ofFloat(0f, 1f);
        driftAnim.setDuration(18000);
        driftAnim.setInterpolator(new AccelerateDecelerateInterpolator());
        // Фон ползёт на несколько пикселей в секунду — хватает каждого третьего кадра (20 к/с), остальные не рисуются
        driftAnim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            int frame;
            @Override public void onAnimationUpdate(ValueAnimator a) {
                if (frame++ % 3 != 0) return;
                float f = a.getAnimatedFraction();
                artView.setTranslationX(x0 + dx * f);
                artView.setTranslationY(y0 + dy * f);
            }
        });
        driftAnim.addListener(new AnimatorListenerAdapter() {
            boolean cancelled;
            @Override public void onAnimationCancel(Animator a) { cancelled = true; }
            @Override public void onAnimationEnd(Animator a) { if (!cancelled) drift(); }
        });
        driftAnim.start();
    }

    private void stopDrift() {
        if (driftAnim != null) driftAnim.cancel();
    }

    // ---------------------------------------------------------------- Плитки

    private void reloadApps() {
        apps = loadApps();
        applyPrefs();
    }

    private void buildTiles() {
        Set<String> hidden = prefs.hidden(), dock = dockKeys();
        List<App> dockApps = new ArrayList<>(), gridApps = new ArrayList<>();
        for (App a : apps) {
            if (hidden.contains(a.key)) continue;
            if (dock.contains(a.key)) dockApps.add(a);
            // В меню — все приложения; системные настройки — только если их нет в доке
            if (!SETTINGS_KEY.equals(a.key) || !dock.contains(a.key)) gridApps.add(a);
        }

        int[] size = dockSize();
        dockRow.removeAllViews();
        for (int i = 0; i < dockApps.size(); i++) {
            View t = tile(dockApps.get(i), size[0], size[1], false);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(size[0]), px(size[1]));
            if (i < dockApps.size() - 1) lp.rightMargin = px(DOCK_GAP);
            dockRow.addView(t, lp);
        }

        grid.removeAllViews();
        float screenDp = getResources().getDisplayMetrics().widthPixels / dp;
        int cols = Math.max(1, (int) ((screenDp - 2 * SIDE + GRID_GAP) / (GRID_W + GRID_GAP)));
        LinearLayout line = null;
        for (int i = 0; i < gridApps.size(); i++) {
            if (i % cols == 0) {
                line = new LinearLayout(this);
                line.setClipChildren(false);
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-2, -2);
                rlp.bottomMargin = px(18);
                grid.addView(line, rlp);
            }
            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setClipChildren(false);
            App a = gridApps.get(i);
            cell.addView(tile(a, GRID_W, GRID_H, true), new LinearLayout.LayoutParams(px(GRID_W), px(GRID_H)));
            TextView name = text(13, 0xCCFFFFFF, "sans-serif");
            name.setText(a.label);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            name.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(px(GRID_W), -2);
            nlp.topMargin = px(12);
            cell.addView(name, nlp);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-2, -2);
            if (i % cols != cols - 1) clp.rightMargin = px(GRID_GAP);
            line.addView(cell, clp);
        }
        caption.setVisibility(gridApps.isEmpty() ? View.INVISIBLE : View.VISIBLE);
        if (panel == null) restoreFocus();
    }

    private View tile(final App app, int w, int h, final boolean inGrid) {
        final Widgets.Tile tile = new Widgets.Tile(this, app.banner, roundness());
        tile.setOpacity(Prefs.OPACITIES[prefs.pick(Prefs.OPACITY, 0, Prefs.OPACITIES.length)],
                Prefs.OPACITIES[prefs.pick(Prefs.FOCUS_OPACITY, 0, Prefs.OPACITIES.length)]);
        tile.setTag((inGrid ? "g:" : "d:") + app.key);
        tile.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { launch(app); }
        });
        tile.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) { openAppMenu(app, tile); return true; }
        });
        tile.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean has) {
                float s = has ? FOCUS_SCALE : 1f;
                tile.animate().scaleX(s).scaleY(s).translationZ(has && tile.opaqueWhenFocused() ? px(14) : 0)
                        .setDuration(dur(170)).setInterpolator(new DecelerateInterpolator()).start();
                if (!has) return;
                focusKey = (String) tile.getTag();
                setGridMode(inGrid, tile);
                onAppFocused(app);
            }
        });
        return tile;
    }

    /** Переход между доком и меню: меню проявляется из прозрачного и поднимается к верху экрана. */
    private void setGridMode(boolean on, View focused) {
        if (on) {
            View rowView = (View) focused.getParent().getParent();
            int gridTop = gridBox.getTop();
            int viewport = page.getHeight();
            int target = gridTop - px(56);
            int rowBottom = gridTop + grid.getPaddingTop() + rowView.getBottom() + px(40);
            target = Math.max(target, rowBottom - viewport);
            scrollPage(target);
        } else if (gridMode) {
            scrollPage(0);
        }
        if (on == gridMode) return;
        gridMode = on;
        long d = dur(320);
        grid.animate().alpha(on ? 1f : 0f).setDuration(d).start();
        gridBg.animate().alpha(on ? 1f : 0f).setDuration(d).start();
        dockScroller.animate().alpha(on ? 0.35f : 1f).setDuration(d).start();
        title.animate().alpha(on ? 0f : 1f).setDuration(d).start();
        quoteBox.animate().alpha(on ? 0f : 1f).translationY(on ? -px(14) : 0).setDuration(d).start();
        topBar.animate().alpha(on ? 0f : 1f).setDuration(d).start();
        caption.animate().alpha(on ? 1f : 0.6f).setDuration(d).start();
    }

    private void scrollPage(int y) {
        ObjectAnimator a = ObjectAnimator.ofInt(page, "scrollY", page.getScrollY(), Math.max(0, y));
        a.setDuration(dur(380));
        a.setInterpolator(new DecelerateInterpolator(1.6f));
        a.start();
    }

    private void restoreFocus() {
        if (dockRow == null) return;
        View target = focusKey == null ? null : content.findViewWithTag(focusKey);
        if (target == null && dockRow.getChildCount() > 0) target = dockRow.getChildAt(0);
        if (target == null && grid.getChildCount() > 0) target = ((ViewGroup) ((ViewGroup) grid.getChildAt(0)).getChildAt(0)).getChildAt(0);
        if (target == null) return;
        final View t = target;
        t.post(new Runnable() { @Override public void run() { t.requestFocus(); } });
    }

    private void onAppFocused(final App app) {
        if (!app.label.contentEquals(title.getText())) {
            title.setText(app.label);
            if (!gridMode) {
                title.setAlpha(0.4f);
                title.animate().alpha(1f).setDuration(dur(200)).start();
            }
        }
        if (washBox.getVisibility() != View.VISIBLE) return;
        // Подсветку меняем с небольшой задержкой, чтобы не дёргать при быстром пролистывании
        if (pendingWash != null) handler.removeCallbacks(pendingWash);
        pendingWash = new Runnable() { @Override public void run() { showWash(app); } };
        handler.postDelayed(pendingWash, 140);
    }

    private void showWash(App app) {
        if (app.key.equals(washKey)) return;
        washKey = app.key;
        Bitmap cached = washes.get(app.key);
        if (cached == null) washes.put(app.key, cached = Backgrounds.wash(app.banner));
        final Bitmap next = cached;
        washFront.animate().cancel();
        if (washFront.getAlpha() > 0f) washBack.setImageDrawable(washFront.getDrawable());
        washFront.setImageBitmap(next);
        washFront.setAlpha(0f);
        washFront.animate().alpha(1f).setDuration(dur(420)).withEndAction(new Runnable() {
            @Override public void run() {
                washBack.setImageBitmap(next);
                washFront.setAlpha(0f);
            }
        }).start();
    }

    private void updateClock() {
        Date now = new Date();
        boolean h24 = DateFormat.is24HourFormat(this);
        clock.setText(new SimpleDateFormat(h24 ? "HH:mm" : "h:mm", Locale.getDefault()).format(now));
        date.setText(new SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(now));
        if (night >= 0) applyNight(true);
    }

    // ---------------------------------------------------------------- Ночь, сеть, появление

    private boolean isNight() {
        int from = Prefs.NIGHT_FROM[prefs.pick(Prefs.NIGHT, Prefs.NIGHT_DEF, Prefs.NIGHT_FROM.length)];
        if (from < 0) return false;
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        return h >= from || h < Prefs.NIGHT_TO;
    }

    /** Ночью фон темнее и теплее: цветовой фильтр на тех же картинках, без лишнего слоя поверх экрана. */
    private void applyNight(boolean animate) {
        float target = isNight() ? 1f : 0f;
        if (target == night && (nightAnim == null || !nightAnim.isRunning())) return;
        if (nightAnim != null) nightAnim.cancel();
        if (!animate || night < 0) {
            setNight(target);
            return;
        }
        nightAnim = ValueAnimator.ofFloat(night, target);
        nightAnim.setDuration(3000);
        nightAnim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(ValueAnimator a) { setNight((Float) a.getAnimatedValue()); }
        });
        nightAnim.start();
    }

    private void setNight(float k) {
        night = k;
        ColorMatrixColorFilter f = null;
        if (k > 0f) {
            ColorMatrix cm = new ColorMatrix();
            cm.setScale(1f - 0.30f * k, 1f - 0.40f * k, 1f - 0.55f * k, 1f);
            f = new ColorMatrixColorFilter(cm);
        }
        artView.setColorFilter(f);
        washBack.setColorFilter(f);
        washFront.setColorFilter(f);
    }

    /** Значок «нет сети» у часов: слушаем сеть, только пока экран виден. */
    private void watchNetwork(boolean on) {
        if (Build.VERSION.SDK_INT < 24) return;
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (cm == null) return;
        if (!on) {
            if (netCallback != null) cm.unregisterNetworkCallback(netCallback);
            netCallback = null;
            return;
        }
        if (netCallback != null) return;
        Network active = cm.getActiveNetwork();
        showNetwork(active == null ? null : cm.getNetworkCapabilities(active));
        netCallback = new ConnectivityManager.NetworkCallback() {
            @Override public void onCapabilitiesChanged(Network n, final NetworkCapabilities caps) {
                handler.post(new Runnable() { @Override public void run() { showNetwork(caps); } });
            }
            @Override public void onLost(Network n) {
                handler.post(new Runnable() { @Override public void run() { showNetwork(null); } });
            }
        };
        cm.registerDefaultNetworkCallback(netCallback);
    }

    /** Цитата меняется с выбранной частотой; номер и время смены хранятся, чтобы «раз в день» переживал перезапуск. */
    private void nextQuote() {
        long every = Prefs.QUOTE_MS[prefs.pick(Prefs.QUOTES, Prefs.QUOTE_DEF, Prefs.QUOTE_MS.length)];
        if (every < 0) return;
        int i = prefs.pick(Prefs.QUOTE, -1, Quotes.ALL.length);
        long now = System.currentTimeMillis();
        if (i < 0 || now - prefs.time(Prefs.QUOTE_AT) >= every) {
            quote = Quotes.random(rnd, i < 0 ? null : Quotes.ALL[i]);
            prefs.put(Prefs.QUOTE, Arrays.asList(Quotes.ALL).indexOf(quote));
            prefs.putTime(Prefs.QUOTE_AT, now);
        } else {
            quote = Quotes.ALL[i];
        }
        quoteText.setText("«" + quote[0] + "»");
        quoteAuthor.setText("— " + quote[1]);
    }

    /** caps == null — сети нет. */
    private void showNetwork(NetworkCapabilities caps) {
        showOffline(caps == null);
        int icon = caps == null ? Widgets.Pill.OFFLINE
                : caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ? Widgets.Pill.ETHERNET
                : Widgets.Pill.WIFI;
        netPill.setIcon(icon);
    }

    private void openNetworkSettings() {
        // На Android TV «Wi‑Fi» открывает общий раздел «Сеть и интернет» — там и проводное подключение
        String[] actions = {Settings.ACTION_WIFI_SETTINGS, Settings.ACTION_WIRELESS_SETTINGS, Settings.ACTION_SETTINGS};
        for (String a : actions) {
            try {
                startActivity(new Intent(a).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            } catch (ActivityNotFoundException ignored) {
            }
        }
    }

    private void showOffline(boolean offline) {
        boolean show = offline && prefs.on(Prefs.NET, true);
        if (show == (netBadge.getVisibility() == View.VISIBLE)) return;
        netBadge.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) {
            netBadge.setTextColor(0xFFFF8A80);
            netBadge.setAlpha(0f);
            netBadge.animate().alpha(1f).setDuration(400).start();
        }
    }

    private void maybeIntro() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (!introPending || pm != null && !pm.isInteractive()) return;
        introPending = false;
        if (prefs.on(Prefs.INTRO, true)) intro();
    }

    private void hideForIntro() {
        ((View) content.getParent()).setBackgroundColor(Color.BLACK);
        artView.animate().cancel();
        content.animate().cancel();
        artView.setAlpha(0f);
        artView.setScaleX(1.26f);
        artView.setScaleY(1.26f);
        content.setAlpha(0f);
        content.setTranslationY(px(12));
    }

    /** Включили ТВ: фон приближается из лёгкого зума, интерфейс проявляется следом. */
    private void intro() {
        final View root = (View) content.getParent();
        if (content.getAlpha() > 0f) hideForIntro();
        // Небольшая пауза — пока панель ТВ разгорается
        artView.animate().alpha(1f).scaleX(1.18f).scaleY(1.18f).setStartDelay(500).setDuration(1200)
                .setInterpolator(new DecelerateInterpolator())
                .withEndAction(new Runnable() {
                    @Override public void run() { if (!appBackground()) root.setBackgroundColor(Color.TRANSPARENT); }
                }).start();
        content.animate().alpha(1f).translationY(0).setStartDelay(900).setDuration(700)
                .setInterpolator(new DecelerateInterpolator()).start();
    }

    // ---------------------------------------------------------------- Панель настроек

    private void openPanel() {
        panel = new SettingsPanel(this, prefs);
        content.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        ((ViewGroup) content.getParent()).addView(panel, match());
        panel.show();
    }

    private void closePanel() {
        final SettingsPanel p = panel;
        panel = null;
        content.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        restoreFocus();
        p.hide(new Runnable() {
            @Override public void run() { ((ViewGroup) p.getParent()).removeView(p); }
        });
    }

    private void openAppMenu(final App app, View tile) {
        if (appMenu != null || panel != null) return;
        final AppMenu m = new AppMenu(this, app.label.toUpperCase(Locale.getDefault()), tile);
        final boolean inDock = dockKeys().contains(app.key);
        m.add("Открыть", new Runnable() {
            @Override public void run() { closeAppMenu(); launch(app); }
        });
        m.add(inDock ? "Убрать из дока" : "Закрепить в доке", new Runnable() {
            @Override public void run() { closeAppMenu(); setAppState(app.key, inDock ? 1 : 0); }
        });
        m.add("Скрыть", new Runnable() {
            @Override public void run() { closeAppMenu(); setAppState(app.key, 2); }
        });
        if (!SETTINGS_KEY.equals(app.key)) {
            m.add("О приложении", new Runnable() {
                @Override public void run() { closeAppMenu(); openDetails(app); }
            });
        }
        appMenu = m;
        content.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        ((ViewGroup) content.getParent()).addView(m, match());
        m.show();
    }

    private void closeAppMenu() {
        final AppMenu m = appMenu;
        appMenu = null;
        content.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        restoreFocus();
        m.hide(new Runnable() {
            @Override public void run() { ((ViewGroup) m.getParent()).removeView(m); }
        });
    }

    /** Все приложения в текущем порядке, включая скрытые — для списка в настройках. */
    List<App> allApps() { return apps; }

    Set<String> dockKeys() { return prefs.dock(DEFAULT_DOCK); }

    /** 0 — в доке, 1 — в меню, 2 — скрыто. */
    void setAppState(String key, int state) {
        Set<String> dock = dockKeys(), hidden = prefs.hidden();
        dock.remove(key);
        hidden.remove(key);
        if (state == 0) dock.add(key);
        if (state == 2) hidden.add(key);
        prefs.setDock(dock);
        prefs.setHidden(hidden);
        buildTiles();
    }

    void moveApp(String key, int dir) {
        List<String> keys = new ArrayList<>();
        for (App a : apps) keys.add(a.key);
        int i = keys.indexOf(key), j = i + dir;
        if (i < 0 || j < 0 || j >= keys.size()) return;
        Collections.swap(keys, i, j);
        prefs.setOrder(keys);
        sortApps(apps);
        buildTiles();
    }

    void openSystemSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException ignored) {
        }
    }

    // ---------------------------------------------------------------- Приложения

    private List<App> loadApps() {
        PackageManager pm = getPackageManager();
        Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        List<App> list = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
            ActivityInfo ai = ri.activityInfo;
            String pkg = ai.packageName;
            if (pkg.equals(getPackageName()) || NEVER_SHOW.contains(pkg) || !seen.add(pkg)) continue;
            App a = new App();
            a.key = pkg;
            String name = NAMES.get(pkg);
            a.label = name != null ? name : String.valueOf(ri.loadLabel(pm));
            a.intent = new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                    .setComponent(new ComponentName(pkg, ai.name))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            a.banner = banner(ai, pm);
            list.add(a);
        }
        App s = new App();
        s.key = SETTINGS_KEY;
        s.label = "Настройки";
        s.intent = new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        s.banner = settingsBanner();
        list.add(s);
        sortApps(list);
        return list;
    }

    /** Сначала пользовательский порядок, затем порядок по умолчанию, затем по алфавиту. */
    private void sortApps(List<App> list) {
        final List<String> order = prefs.order();
        Collections.sort(list, new Comparator<App>() {
            @Override public int compare(App x, App y) {
                int r = Integer.compare(rank(order, x.key), rank(order, y.key));
                return r != 0 ? r : x.label.compareToIgnoreCase(y.label);
            }
        });
    }

    private static int rank(List<String> order, String key) {
        int i = order.indexOf(key);
        if (i >= 0) return i;
        i = DEFAULT_DOCK.indexOf(key);
        return 1000 + (i >= 0 ? i : 100);
    }

    private void launch(App app) {
        try {
            startActivity(app.intent);
        } catch (ActivityNotFoundException | SecurityException e) {
            reloadApps();
        }
    }

    private void openDetails(App app) {
        if (SETTINGS_KEY.equals(app.key)) return;
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", app.key, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException ignored) {
        }
    }

    private void launchSearch() {
        String[] actions = {Intent.ACTION_ASSIST, "android.search.action.GLOBAL_SEARCH", Intent.ACTION_WEB_SEARCH};
        for (String a : actions) {
            try {
                startActivity(new Intent(a).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            } catch (ActivityNotFoundException ignored) {
            }
        }
    }

    // ---------------------------------------------------------------- Баннеры

    /** Баннеры рисуются под самый крупный размер плитки в фокусе, меньшие плитки их просто ужимают. */
    private int[] bannerPx() {
        int[] big = Prefs.SIZES[Prefs.SIZES.length - 1];
        return new int[] {px(big[0] * FOCUS_SCALE), px(big[1] * FOCUS_SCALE)};
    }

    private Bitmap banner(ActivityInfo ai, PackageManager pm) {
        int[] s = bannerPx();
        int w = s[0], h = s[1];
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Drawable d = ai.loadBanner(pm);
        if (d != null) {
            d.setBounds(0, 0, w, h);
            d.draw(c);
            return b;
        }
        // Нет баннера — иконка на цветной подложке
        Bitmap icon = toBitmap(ai.loadIcon(pm), px(64));
        int base = averageColor(icon);
        Paint bg = new Paint();
        bg.setShader(new LinearGradient(0, 0, w, h, shift(base, 0.85f), shift(base, 0.45f), Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, bg);
        int is = Math.round(h * 0.52f);
        c.drawBitmap(icon, null, new RectF((w - is) / 2f, (h - is) / 2f, (w + is) / 2f, (h + is) / 2f),
                new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG));
        return b;
    }

    private Bitmap settingsBanner() {
        int[] s = bannerPx();
        int w = s[0], h = s[1];
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint bg = new Paint();
        bg.setShader(new LinearGradient(0, 0, w, h, 0xFF8E8E93, 0xFF48484A, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, bg);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.WHITE);
        Widgets.drawGear(c, w / 2f, h / 2f, h * 0.27f, p);
        return b;
    }

    private static Bitmap toBitmap(Drawable d, int size) {
        if (d instanceof BitmapDrawable && ((BitmapDrawable) d).getBitmap() != null) return ((BitmapDrawable) d).getBitmap();
        Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        d.setBounds(0, 0, size, size);
        d.draw(c);
        return b;
    }

    private static int averageColor(Bitmap b) {
        int c = Bitmap.createScaledBitmap(b, 1, 1, true).getPixel(0, 0);
        return Color.alpha(c) < 40 ? 0xFF3A3A3C : c | 0xFF000000;
    }

    private static int shift(int c, float k) {
        return Color.rgb(Math.round(Color.red(c) * k), Math.round(Color.green(c) * k), Math.round(Color.blue(c) * k));
    }

    /** Вертикальная прокрутка, которой управляет только лаунчер: без автоскролла к фокусу и без листания стрелками. */
    static final class PageScroll extends ScrollView {
        PageScroll(Context c) {
            super(c);
            setVerticalScrollBarEnabled(false);
            setOverScrollMode(OVER_SCROLL_NEVER);
            setClipChildren(false);
            setFillViewport(true);
        }

        @Override
        protected int computeScrollDeltaToGetChildRectOnScreen(Rect rect) { return 0; }

        @Override
        public boolean executeKeyEvent(KeyEvent event) { return false; }
    }
}
