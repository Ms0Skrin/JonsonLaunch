package home.lite.tv;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.PowerManager;
import android.widget.Toast;

/**
 * Таймер сна: через выбранное время ТВ засыпает (как кнопка питания), даже если открыт YouTube.
 * За минуту — предупреждение. Усыпляет через SelfAdb («Разрешить тихую установку» в настройках).
 */
public class SleepTimer extends BroadcastReceiver {
    static final int[] STEPS = {15, 30, 60, 90, 120};
    private static final String WARN = "home.lite.tv.SLEEP_WARN", SLEEP = "home.lite.tv.SLEEP";

    /** Сколько минут осталось (0 — таймер выключен). */
    static int minutesLeft(Context c) {
        long left = new Prefs(c).time(Prefs.SLEEP_AT) - System.currentTimeMillis();
        return left > 0 ? (int) ((left + 59_999) / 60_000) : 0;
    }

    /** Следующий шаг по кнопке: выкл → 15 → 30 → 60 → 90 → 120 → выкл. */
    static int next(Context c) {
        int left = minutesLeft(c);
        if (left == 0) return STEPS[0];
        for (int s : STEPS) if (s > left) return s;
        return 0;
    }

    static void set(Context ctx, int minutes) {
        Context c = ctx.getApplicationContext();
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        am.cancel(pi(c, WARN));
        am.cancel(pi(c, SLEEP));
        Prefs prefs = new Prefs(c);
        if (minutes <= 0) {
            if (prefs.time(Prefs.SLEEP_AT) != 0) Journal.i("таймер сна выключен");
            prefs.putTime(Prefs.SLEEP_AT, 0);
            return;
        }
        long end = System.currentTimeMillis() + minutes * 60_000L;
        prefs.putTime(Prefs.SLEEP_AT, end);
        at(am, end - 60_000L, pi(c, WARN));
        at(am, end, pi(c, SLEEP));
        Journal.i("таймер сна: " + minutes + " мин");
    }

    private static void at(AlarmManager am, long when, PendingIntent p) {
        if (Build.VERSION.SDK_INT >= 23) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, p);
        else am.setExact(AlarmManager.RTC_WAKEUP, when, p);
    }

    private static PendingIntent pi(Context c, String action) {
        return PendingIntent.getBroadcast(c, 0, new Intent(action).setClass(c, SleepTimer.class),
                PendingIntent.FLAG_UPDATE_CURRENT);
    }

    @Override
    public void onReceive(Context ctx, Intent i) {
        final Context c = ctx.getApplicationContext();
        Journal.init(c);
        PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
        if (new Prefs(c).time(Prefs.SLEEP_AT) == 0 || !pm.isInteractive()) {
            new Prefs(c).putTime(Prefs.SLEEP_AT, 0);
            return;
        }
        if (WARN.equals(i.getAction())) {
            Toast.makeText(c, "Таймер сна: телевизор выключится через минуту", Toast.LENGTH_LONG).show();
            return;
        }
        new Prefs(c).putTime(Prefs.SLEEP_AT, 0);
        final PendingResult done = goAsync();
        new Thread(new Runnable() {
            @Override public void run() {
                try (SelfAdb adb = new SelfAdb(c, 10_000, false)) {
                    adb.exec("input keyevent 223", null); // KEYCODE_SLEEP
                    Journal.i("таймер сна: ТВ усыплён");
                } catch (Exception e) {
                    Journal.e("таймер сна не смог выключить ТВ (разрешена ли тихая установка?)", e);
                } finally {
                    done.finish();
                }
            }
        }, "sleep").start();
    }
}
