package home.lite.tv;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Журнал работы лаунчера: события, ошибки и падения с временем и версией.
 * Лежит в /sdcard/Android/data/home.lite.tv/files/journal.txt — его можно забрать по adb
 * (release/tv-log.sh <ip>) без root и без отладочной сборки. Старше 256 КБ — уходит в journal.old.txt.
 */
final class Journal {
    private static final String TAG = "JonsonLaunch";
    private static final long MAX = 256 * 1024;
    private static File file;
    private static boolean crashHook;

    private Journal() {}

    /** Подготовить журнал и перехват падений; безопасно вызывать много раз. */
    static synchronized void init(Context ctx) {
        final Context c = ctx.getApplicationContext();
        if (file == null) {
            File dir = c.getExternalFilesDir(null);
            if (dir == null) dir = c.getFilesDir();
            dir.mkdirs();
            file = new File(dir, "journal.txt");
            i("запуск " + Updater.currentName(c) + " (" + Updater.currentCode(c) + "), "
                    + Build.MANUFACTURER + " " + Build.MODEL + ", Android " + Build.VERSION.RELEASE);
        }
        if (!crashHook) {
            crashHook = true;
            final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
            Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
                @Override public void uncaughtException(Thread t, Throwable e) {
                    e("ПАДЕНИЕ в потоке " + t.getName(), e);
                    if (prev != null) prev.uncaughtException(t, e);
                }
            });
        }
    }

    static void i(String msg) { write("I", msg, null); }

    static void e(String msg, Throwable t) { write("E", msg, t); }

    private static synchronized void write(String level, String msg, Throwable t) {
        if ("E".equals(level)) Log.e(TAG, msg, t); else Log.i(TAG, msg);
        if (file == null) return;
        try {
            if (file.length() > MAX) file.renameTo(new File(file.getParent(), "journal.old.txt"));
            try (PrintWriter w = new PrintWriter(new FileWriter(file, true))) {
                String time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
                w.println(time + " " + level + " " + msg);
                if (t != null) {
                    StringWriter sw = new StringWriter();
                    t.printStackTrace(new PrintWriter(sw));
                    w.println(sw.toString().trim());
                }
            }
        } catch (Exception ignored) {
            // Журнал не должен ронять лаунчер
        }
    }
}
