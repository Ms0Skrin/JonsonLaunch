package home.lite.tv;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;

/**
 * Обновления с GitHub: последний релиз → APK → проверка подписи → тихая установка через SelfAdb.
 * Номер версии — versionCode, тег релиза — «v<versionCode>».
 */
final class Updater {

    static final String REPO = "Ms0Skrin/JonsonLaunch";
    /** Автопроверка не чаще раза в 6 часов — у GitHub API лимит 60 запросов в час без входа. */
    static final long CHECK_EVERY = 6 * 60 * 60_000L;

    interface Listener { void status(String text); }

    private static volatile boolean busy;
    private static File pending;

    private Updater() {}

    static int currentCode(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionCode;
        } catch (PackageManager.NameNotFoundException e) {
            return 0;
        }
    }

    static String currentName(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "?";
        }
    }

    /** Скачанное и проверенное обновление ждёт установки (ставим, когда лаунчер не на экране). */
    static synchronized boolean hasPending() { return pending != null && pending.exists(); }

    /**
     * Проверить и скачать. installNow — ставить сразу (кнопка в настройках),
     * иначе обновление ждёт момента, когда лаунчер уйдёт с экрана.
     */
    static void check(final Context ctx, final boolean installNow, final Listener l) {
        if (busy) return;
        busy = true;
        final Context c = ctx.getApplicationContext();
        final Handler ui = new Handler(Looper.getMainLooper());
        new Thread(new Runnable() {
            void say(final String s) {
                if (l != null) ui.post(new Runnable() { @Override public void run() { l.status(s); } });
            }

            @Override public void run() {
                try {
                    say("Проверяю…");
                    new Prefs(c).putTime(Prefs.UPDATE_AT, System.currentTimeMillis());
                    JSONObject rel = new JSONObject(get("https://api.github.com/repos/" + REPO + "/releases/latest"));
                    int code = Integer.parseInt(rel.getString("tag_name").replaceAll("[^0-9]", ""));
                    if (code <= currentCode(c)) {
                        say("Последняя версия");
                        return;
                    }
                    String url = null;
                    JSONArray assets = rel.getJSONArray("assets");
                    for (int i = 0; i < assets.length(); i++) {
                        JSONObject a = assets.getJSONObject(i);
                        if (a.getString("name").endsWith(".apk")) url = a.getString("browser_download_url");
                    }
                    if (url == null) throw new IOException("в релизе нет APK");
                    say("Скачиваю " + rel.getString("tag_name") + "…");
                    File apk = new File(c.getCacheDir(), "update.apk");
                    download(url, apk);
                    if (!sameSigner(c, apk)) {
                        apk.delete();
                        throw new IOException("чужая подпись");
                    }
                    synchronized (Updater.class) { pending = apk; }
                    if (installNow) {
                        say("Устанавливаю…");
                        installPending(c);
                    } else {
                        say("Скачано, поставлю в фоне");
                    }
                } catch (Exception e) {
                    say("Ошибка: " + reason(e));
                } finally {
                    busy = false;
                }
            }
        }, "update").start();
    }

    /** Поставить скачанное обновление. Процесс лаунчера при этом перезапустится. */
    static void installPending(Context c) throws IOException {
        File apk;
        synchronized (Updater.class) { apk = pending; }
        if (apk == null || !apk.exists()) return;
        try (SelfAdb adb = new SelfAdb(c, 30_000); InputStream data = new FileInputStream(apk)) {
            String out = adb.exec("cmd package install -r -S " + apk.length(), data);
            if (!out.contains("Success")) throw new IOException(out.trim());
        }
        synchronized (Updater.class) { pending = null; }
        apk.delete();
    }

    /** То же в фоне — когда лаунчер ушёл с экрана. */
    static void installPendingQuietly(final Context ctx) {
        if (!hasPending() || busy) return;
        busy = true;
        final Context c = ctx.getApplicationContext();
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    installPending(c);
                } catch (Exception ignored) {
                    // Не вышло (например, не разрешена отладка) — попробуем при следующем уходе с экрана
                } finally {
                    busy = false;
                }
            }
        }, "update-install").start();
    }

    /** Подключиться к своему adb: в первый раз ТВ покажет «Разрешить отладку». */
    static void requestPermission(final Context ctx, final Listener l) {
        final Context c = ctx.getApplicationContext();
        final Handler ui = new Handler(Looper.getMainLooper());
        new Thread(new Runnable() {
            void say(final String s) { ui.post(new Runnable() { @Override public void run() { l.status(s); } }); }

            @Override public void run() {
                say("Нажмите «Разрешить» на ТВ…");
                try (SelfAdb adb = new SelfAdb(c, 60_000)) {
                    say("Разрешено ✓");
                } catch (Exception e) {
                    say("Ошибка: " + reason(e));
                }
            }
        }, "adb-auth").start();
    }

    // ---------------------------------------------------------------- Сеть и подпись

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        h.setConnectTimeout(15_000);
        h.setReadTimeout(30_000);
        h.setRequestProperty("User-Agent", "JonsonLaunch");
        h.setRequestProperty("Accept", "application/vnd.github+json");
        h.setInstanceFollowRedirects(true);
        if (h.getResponseCode() / 100 != 2) throw new IOException("HTTP " + h.getResponseCode());
        return h;
    }

    private static String get(String url) throws IOException {
        HttpURLConnection h = open(url);
        try (InputStream in = h.getInputStream()) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            copy(in, b);
            return b.toString("UTF-8");
        } finally {
            h.disconnect();
        }
    }

    private static void download(String url, File to) throws IOException {
        HttpURLConnection h = open(url);
        try (InputStream in = h.getInputStream(); OutputStream out = new FileOutputStream(to)) {
            copy(in, out);
        } finally {
            h.disconnect();
        }
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
    }

    /** Ставим только APK того же пакета, подписанный тем же ключом, что и установленный лаунчер. */
    @SuppressWarnings("deprecation")
    private static boolean sameSigner(Context c, File apk) {
        PackageManager pm = c.getPackageManager();
        PackageInfo theirs = pm.getPackageArchiveInfo(apk.getPath(), PackageManager.GET_SIGNATURES);
        if (theirs == null || !c.getPackageName().equals(theirs.packageName)) return false;
        try {
            Signature[] ours = pm.getPackageInfo(c.getPackageName(), PackageManager.GET_SIGNATURES).signatures;
            return Arrays.equals(ours, theirs.signatures);
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private static String reason(Exception e) {
        String m = e.getMessage();
        if (e instanceof java.net.UnknownHostException) return "нет сети";
        if (e instanceof java.net.ConnectException) return "отладка по сети выключена";
        if (e instanceof java.net.SocketTimeoutException) return "нет ответа (не разрешено на ТВ?)";
        return m != null ? m : e.getClass().getSimpleName();
    }
}
