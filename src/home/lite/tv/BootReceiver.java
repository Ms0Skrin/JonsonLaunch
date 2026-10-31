package home.lite.tv;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** После включения ТВ открывает выбранное в настройках приложение. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        Prefs prefs = new Prefs(c);
        String pkg = Prefs.BOOT_PKGS[prefs.pick(Prefs.BOOT, 0, Prefs.BOOT_PKGS.length)];
        if (pkg == null) return;
        Intent launch = c.getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
        // На MediaTek-телевизорах (KIVI) эфир живёт в другом пакете
        if (launch == null && pkg.equals("com.android.tv"))
            launch = c.getPackageManager().getLeanbackLaunchIntentForPackage("com.mediatek.wwtv.tvcenter");
        if (launch != null) c.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }
}
