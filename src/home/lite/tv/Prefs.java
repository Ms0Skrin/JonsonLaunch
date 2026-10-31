package home.lite.tv;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Настройки лаунчера: ключи, варианты значений и доступ к SharedPreferences. */
final class Prefs {
    static final String BG = "bg", SEED = "seed", LIVE = "live", TINT = "tint", SIZE = "size", ROUND = "round",
            TITLE = "title", CLOCK = "clock", DATE = "date", SEARCH = "search", SPEED = "speed", BOOT = "boot",
            ORDER = "order", HIDDEN = "hidden", DOCK = "dock", PIC = "pic", ROTATE = "rotate", PIC_AT = "pic_at",
            NIGHT = "night", NET = "net", INTRO = "intro", OPACITY = "opacity",
            FOCUS_OPACITY = "focus_opacity", AUTO_UPDATE = "auto_update", UPDATE_AT = "update_at",
            QUOTES = "quotes_every", QUOTE = "quote", QUOTE_AT = "quote_at";

    static final String[] BG_NAMES = {"Аврора", "Закат", "Океан", "Лес", "Графит", "От приложения",
            "Абстракция", "Пейзажи", "Автомобили", "Животные", "Города", "Люди"};
    /** С BG_PIC начинаются наборы картинок; папки в assets/bg — в том же порядке. */
    static final int BG_APP = 5, BG_PIC = 6;
    static final String[] PIC_SETS = {"abstract", "land", "cars", "animals", "city", "people"};
    static final String[] ROTATE_NAMES = {"Выкл", "Каждые 15 минут", "Каждый час", "Каждые 6 часов", "Раз в день"};
    static final long[] ROTATE_MS = {0, 15 * 60_000L, 60 * 60_000L, 6 * 60 * 60_000L, 24 * 60 * 60_000L};
    /** Ночной режим: с выбранного часа до утра фон темнее и теплее. */
    static final String[] NIGHT_NAMES = {"Выкл", "С 21:00", "С 22:00", "С 23:00"};
    static final int[] NIGHT_FROM = {-1, 21, 22, 23};
    static final int NIGHT_TO = 7, NIGHT_DEF = 2;
    /** По умолчанию фон сам меняется раз в день — случайная фотография из фото-альбомов. */
    static final int ROTATE_DEF = 4;
    static final String[] OPACITY_NAMES = {"100%", "90%", "80%", "70%", "60%", "50%", "40%", "30%"};
    static final float[] OPACITIES = {1f, 0.9f, 0.8f, 0.7f, 0.6f, 0.5f, 0.4f, 0.3f};
    /** Цитата под доком: выключена / новая при каждом возврате / раз в час / раз в день. */
    static final String[] QUOTE_NAMES = {"Выкл", "При каждом заходе", "Каждый час", "Раз в день"};
    static final long[] QUOTE_MS = {-1, 0, 60 * 60_000L, 24 * 60 * 60_000L};
    static final int QUOTE_DEF = 3;
    static final String[] SIZE_NAMES = {"Маленькие", "Средние", "Крупные"};
    static final int[][] SIZES = {{164, 92}, {186, 105}, {216, 122}};
    static final String[] ROUND_NAMES = {"Мягкое", "Среднее", "Сильное"};
    static final float[] ROUNDS = {0.10f, 0.17f, 0.25f};
    static final String[] SPEED_NAMES = {"Плавные", "Быстрые"};
    static final String[] BOOT_NAMES = {"Домашний экран", "YouTube", "Телевидение"};
    static final String[] BOOT_PKGS = {null, "com.google.android.youtube.tv", "com.android.tv"};

    private final SharedPreferences sp;

    Prefs(Context c) { sp = c.getSharedPreferences("home", Context.MODE_PRIVATE); }

    int get(String key, int def) { return sp.getInt(key, def); }
    boolean on(String key, boolean def) { return sp.getBoolean(key, def); }
    void put(String key, int v) { sp.edit().putInt(key, v).apply(); }
    void put(String key, boolean v) { sp.edit().putBoolean(key, v).apply(); }
    long time(String key) { return sp.getLong(key, 0); }
    void putTime(String key, long v) { sp.edit().putLong(key, v).apply(); }
    void clear() { sp.edit().clear().apply(); }

    int bg() { return pick(BG, BG_PIC, BG_NAMES.length); }

    /** Папка набора картинок для фона bg (для не-картинок — абстракция). */
    static String picSet(int bg) { return PIC_SETS[bg >= BG_PIC ? bg - BG_PIC : 0]; }

    /** У каждого набора своя выбранная картинка; у абстракции — старый ключ, чтобы выбор не сбросился. */
    static String picKey(int bg) { return bg <= BG_PIC ? PIC : PIC + "_" + picSet(bg); }

    static String[] picNames(int count) {
        String[] n = new String[Math.max(1, count)];
        for (int i = 0; i < n.length; i++) n[i] = (i + 1) + " из " + count;
        return n;
    }

    /** Значение-индекс, гарантированно попадающее в массив вариантов. */
    int pick(String key, int def, int count) {
        int v = get(key, def);
        return v >= 0 && v < count ? v : def;
    }

    List<String> order() {
        String s = sp.getString(ORDER, "");
        return s.isEmpty() ? new ArrayList<String>() : new ArrayList<>(Arrays.asList(s.split(",")));
    }

    void setOrder(List<String> keys) { sp.edit().putString(ORDER, TextUtils.join(",", keys)).apply(); }

    Set<String> hidden() { return new HashSet<>(sp.getStringSet(HIDDEN, new HashSet<String>())); }

    void setHidden(Set<String> keys) { sp.edit().putStringSet(HIDDEN, keys).apply(); }

    /** Приложения, закреплённые в доке; по умолчанию — переданный набор. */
    Set<String> dock(List<String> defaults) {
        Set<String> s = sp.getStringSet(DOCK, null);
        return new HashSet<>(s != null ? s : defaults);
    }

    void setDock(Set<String> keys) { sp.edit().putStringSet(DOCK, keys).apply(); }
}
