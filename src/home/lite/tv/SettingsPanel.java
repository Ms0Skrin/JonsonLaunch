package home.lite.tv;

import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;
import java.util.Set;

/** Боковая панель настроек лаунчера. OK — следующее значение, ◀ ▶ — листать значения (у приложений — порядок). */
final class SettingsPanel extends FrameLayout {

    interface Side { void on(int dir); }

    private final MainActivity host;
    private final Prefs prefs;
    private final float dp;
    private final LinearLayout list;
    private final View sheet, dim;

    SettingsPanel(MainActivity host, Prefs prefs) {
        super(host);
        this.host = host;
        this.prefs = prefs;
        dp = host.getResources().getDisplayMetrics().density;
        setClickable(true);

        dim = new View(host);
        dim.setBackgroundColor(0x80000000);
        addView(dim, new LayoutParams(-1, -1));

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF01C1C1E);
        bg.setCornerRadius(px(28));
        ScrollView scroll = new ScrollView(host);
        scroll.setBackground(bg);
        scroll.setClipToOutline(true);
        scroll.setElevation(px(24));
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setPadding(px(18), px(26), px(18), px(26));
        scroll.setClipToPadding(false);
        list = new LinearLayout(host);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list, new LayoutParams(-1, -2));
        LayoutParams slp = new LayoutParams(px(420), -1, Gravity.END);
        slp.setMargins(0, px(20), px(20), px(20));
        addView(scroll, slp);
        sheet = scroll;

        build();
    }

    private int px(float v) { return Math.round(v * dp); }

    void show() {
        sheet.setTranslationX(px(460));
        dim.setAlpha(0f);
        sheet.animate().translationX(0).setDuration(240).start();
        dim.animate().alpha(1f).setDuration(240).start();
        focusFirst();
    }

    void hide(Runnable end) {
        dim.animate().alpha(0f).setDuration(180).start();
        sheet.animate().translationX(px(460)).setDuration(180).withEndAction(end).start();
    }

    private void focusFirst() {
        for (int i = 0; i < list.getChildCount(); i++) {
            View v = list.getChildAt(i);
            if (v.isFocusable()) { v.requestFocus(); return; }
        }
    }

    private void rebuildKeeping(String tag) {
        list.removeAllViews();
        build();
        View v = list.findViewWithTag(tag);
        if (v != null) v.requestFocus(); else focusFirst();
    }

    // ---------------------------------------------------------------- Содержимое

    private void build() {
        TextView head = label("Настройки", 28, Color.WHITE, "sans-serif-medium");
        head.setPadding(px(14), 0, 0, px(10));
        list.addView(head);

        section("Оформление");
        choice("Фон", Prefs.BG, Prefs.BG_NAMES, Prefs.BG_PIC);
        picRow();
        choice("Менять картинку", Prefs.ROTATE, Prefs.ROTATE_NAMES, Prefs.ROTATE_DEF);
        // На картинках — следующая картинка, на абстрактных фонах — новый узор
        action("Сменить фон", "›", new Runnable() {
            @Override public void run() {
                if (prefs.bg() >= Prefs.BG_PIC) {
                    stepPicture(1);
                    return;
                } else {
                    prefs.put(Prefs.SEED, prefs.get(Prefs.SEED, 0) + 1);
                }
                host.applyPrefs();
            }
        });
        toggle("Живой фон", Prefs.LIVE, true);
        choice("Ночной режим", Prefs.NIGHT, Prefs.NIGHT_NAMES, Prefs.NIGHT_DEF);
        toggle("Подсветка от приложения", Prefs.TINT, false);
        choice("Прозрачность плиток", Prefs.OPACITY, Prefs.OPACITY_NAMES, 0);
        choice("Прозрачность активной", Prefs.FOCUS_OPACITY, Prefs.OPACITY_NAMES, 0);
        choice("Размер плиток", Prefs.SIZE, Prefs.SIZE_NAMES, 1);
        choice("Скругление", Prefs.ROUND, Prefs.ROUND_NAMES, 1);
        choice("Анимации", Prefs.SPEED, Prefs.SPEED_NAMES, 0);

        section("Экран");
        toggle("Название приложения", Prefs.TITLE, true);
        toggle("Часы", Prefs.CLOCK, true);
        toggle("Дата", Prefs.DATE, true);
        toggle("Кнопка поиска", Prefs.SEARCH, true);
        toggle("Значок «нет сети»", Prefs.NET, true);
        toggle("Плавное появление", Prefs.INTRO, true);

        section("При включении ТВ");
        choice("Открывать", Prefs.BOOT, Prefs.BOOT_NAMES, 0);

        section("Приложения");
        TextView hint = label("OK — док / меню / скрыть,  ◀ ▶ — порядок.  Долгое OK на плитке — меню", 13, 0x80FFFFFF, "sans-serif");
        hint.setPadding(px(14), 0, 0, px(8));
        list.addView(hint);
        appRows();

        section("Обновления");
        toggle("Автоустановка обновлений", Prefs.AUTO_UPDATE, true);
        action("Версия", Updater.currentName(host), null);
        final Row check = row("Проверить обновления");
        check.value("›");
        check.click = new Runnable() {
            @Override public void run() {
                Updater.check(host, true, new Updater.Listener() {
                    @Override public void status(String text) { check.value(text); }
                });
            }
        };
        final Row allow = row("Разрешить тихую установку");
        allow.value("›");
        allow.click = new Runnable() {
            @Override public void run() {
                Updater.requestPermission(host, new Updater.Listener() {
                    @Override public void status(String text) { allow.value(text); }
                });
            }
        };

        section("Система");
        action("Настройки Android", "›", new Runnable() {
            @Override public void run() { host.openSystemSettings(); }
        });
        final Row reset = row("Сбросить настройки лаунчера");
        reset.setTag("reset");
        final boolean[] armed = {false};
        reset.click = new Runnable() {
            @Override public void run() {
                if (!armed[0]) {
                    armed[0] = true;
                    reset.value("Нажмите ещё раз");
                    return;
                }
                prefs.clear();
                host.applyPrefs();
                rebuildKeeping("reset");
            }
        };
    }

    private void appRows() {
        List<MainActivity.App> apps = host.allApps();
        Set<String> hidden = prefs.hidden(), dock = host.dockKeys();
        for (final MainActivity.App a : apps) {
            final String tag = "app:" + a.key;
            final Row r = row(a.label);
            r.setTag(tag);
            final int state = hidden.contains(a.key) ? 2 : dock.contains(a.key) ? 0 : 1;
            r.value(new String[] {"В доке", "В меню", "Скрыто"}[state]);
            r.title.setAlpha(state == 2 ? 0.45f : 1f);
            r.click = new Runnable() {
                @Override public void run() {
                    host.setAppState(a.key, (state + 1) % 3);
                    rebuildKeeping(tag);
                }
            };
            r.side = new Side() {
                @Override public void on(int dir) {
                    host.moveApp(a.key, dir);
                    rebuildKeeping(tag);
                }
            };
        }
    }

    private void section(String name) {
        TextView t = label(name.toUpperCase(), 12, 0x80FFFFFF, "sans-serif-medium");
        t.setLetterSpacing(0.08f);
        t.setPadding(px(14), px(18), 0, px(6));
        list.addView(t);
    }

    private void choice(String title, final String key, final String[] names, final int def) {
        final Row r = row(title);
        r.setTag("pref:" + key);
        final Runnable show = new Runnable() {
            @Override public void run() { r.value("‹  " + names[prefs.pick(key, def, names.length)] + "  ›"); }
        };
        show.run();
        r.side = new Side() {
            @Override public void on(int dir) {
                int v = (prefs.pick(key, def, names.length) + dir + names.length) % names.length;
                prefs.put(key, v);
                show.run();
                // Включили смену — фон сразу меняется на случайное фото из альбомов, дальше по таймеру
                if (key.equals(Prefs.ROTATE) && v > 0) {
                    if (prefs.bg() < Prefs.BG_PIC) prefs.put(Prefs.BG, Prefs.BG_PIC + 1);
                    prefs.putTime(Prefs.PIC_AT, 0);
                }
                if (key.equals(Prefs.BG) && picShow != null) picShow.run();
                host.applyPrefs();
            }
        };
        r.click = new Runnable() { @Override public void run() { r.side.on(1); } };
    }

    private Runnable picShow;

    /** «Картинка» — номер в текущем наборе (пейзажи, города…); выбор на абстрактном узоре включает «Абстракцию». */
    private void picRow() {
        final Row r = row("Картинка");
        r.setTag("pref:" + Prefs.PIC);
        picShow = new Runnable() {
            @Override public void run() {
                int bg = prefs.bg(), n = Backgrounds.count(host, Prefs.picSet(bg));
                r.value("‹  " + (prefs.pick(Prefs.picKey(bg), 0, Math.max(1, n)) + 1) + " из " + n + "  ›");
            }
        };
        picShow.run();
        r.side = new Side() {
            @Override public void on(int dir) { stepPicture(dir); }
        };
        r.click = new Runnable() { @Override public void run() { stepPicture(1); } };
    }

    private void stepPicture(int dir) {
        if (prefs.bg() < Prefs.BG_PIC) setBg(Prefs.BG_PIC);
        int bg = prefs.bg(), n = Backgrounds.count(host, Prefs.picSet(bg));
        if (n == 0) return;
        String key = Prefs.picKey(bg);
        prefs.put(key, (prefs.pick(key, 0, n) + dir + n) % n);
        prefs.putTime(Prefs.PIC_AT, System.currentTimeMillis());
        picShow.run();
        host.applyPrefs();
    }

    private void setBg(int bg) {
        prefs.put(Prefs.BG, bg);
        View row = list.findViewWithTag("pref:" + Prefs.BG);
        if (row != null) ((Row) row).value("‹  " + Prefs.BG_NAMES[bg] + "  ›");
    }

    private void toggle(String title, final String key, final boolean def) {
        final Row r = row(title);
        r.setTag("pref:" + key);
        r.value(prefs.on(key, def) ? "Вкл" : "Выкл");
        r.click = new Runnable() {
            @Override public void run() {
                boolean v = !prefs.on(key, def);
                prefs.put(key, v);
                r.value(v ? "Вкл" : "Выкл");
                host.applyPrefs();
            }
        };
    }

    private void action(String title, String value, Runnable run) {
        Row r = row(title);
        r.value(value);
        r.click = run;
    }

    private Row row(String title) {
        Row r = new Row(title);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, px(48));
        lp.bottomMargin = px(4);
        list.addView(r, lp);
        return r;
    }

    private TextView label(String s, float sp, int color, String family) {
        TextView t = new TextView(getContext());
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.create(family, Typeface.NORMAL));
        return t;
    }

    /** Строка настройки: название слева, значение справа; в фокусе — белая плашка. */
    private final class Row extends LinearLayout {
        final TextView title, val;
        final GradientDrawable bg = new GradientDrawable();
        Runnable click;
        Side side;

        Row(String name) {
            super(SettingsPanel.this.getContext());
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setPadding(px(14), 0, px(14), 0);
            setFocusable(true);
            setClickable(true);
            bg.setCornerRadius(px(14));
            bg.setColor(0);
            setBackground(bg);

            title = label(name, 16, Color.WHITE, "sans-serif");
            title.setSingleLine(true);
            addView(title, new LayoutParams(0, -2, 1f));
            val = label("", 14, 0x99FFFFFF, "sans-serif");
            addView(val, new LayoutParams(-2, -2));

            setOnClickListener(new OnClickListener() {
                @Override public void onClick(View v) { if (click != null) click.run(); }
            });
            setOnKeyListener(new OnKeyListener() {
                @Override public boolean onKey(View v, int code, KeyEvent ev) {
                    if (side == null || ev.getAction() != KeyEvent.ACTION_DOWN) return false;
                    if (code == KeyEvent.KEYCODE_DPAD_LEFT) { side.on(-1); return true; }
                    if (code == KeyEvent.KEYCODE_DPAD_RIGHT) { side.on(1); return true; }
                    return false;
                }
            });
            setOnFocusChangeListener(new OnFocusChangeListener() {
                @Override public void onFocusChange(View v, boolean has) {
                    bg.setColor(has ? Color.WHITE : 0);
                    title.setTextColor(has ? 0xFF1C1C1E : Color.WHITE);
                    val.setTextColor(has ? 0x991C1C1E : 0x99FFFFFF);
                    animate().scaleX(has ? 1.02f : 1f).scaleY(has ? 1.02f : 1f).setDuration(120).start();
                }
            });
        }

        void value(String s) { val.setText(s); }
    }
}
