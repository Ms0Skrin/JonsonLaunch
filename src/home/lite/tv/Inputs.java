package home.lite.tv;

import android.content.Context;
import android.content.Intent;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Входы HDMI телевизора (TvInputManager) и переключение на них через Live TV. */
final class Inputs {

    static final class In {
        String id, label;
        boolean connected;
    }

    private Inputs() {}

    /** HDMI-входы по порядку; подпись от прошивки, иначе «HDMI n». */
    static List<In> hdmi(Context c) {
        List<In> out = new ArrayList<>();
        try {
            TvInputManager tm = (TvInputManager) c.getSystemService(Context.TV_INPUT_SERVICE);
            if (tm == null) return out;
            List<TvInputInfo> all = new ArrayList<>(tm.getTvInputList());
            Collections.sort(all, new Comparator<TvInputInfo>() {
                @Override public int compare(TvInputInfo a, TvInputInfo b) { return a.getId().compareTo(b.getId()); }
            });
            int n = 0;
            for (TvInputInfo i : all) {
                if (i.getType() != TvInputInfo.TYPE_HDMI || !i.isPassthroughInput()) continue;
                // HDMI-CEC устройства — дочерние входы порта, показываем только сами порты
                if (i.getParentId() != null) continue;
                n++;
                In x = new In();
                x.id = i.getId();
                CharSequence l = null;
                try { l = i.loadCustomLabel(c); } catch (Throwable ignored) {}
                if (l == null || l.length() == 0) try { l = i.loadLabel(c); } catch (Throwable ignored) {}
                String s = l == null ? "" : l.toString().trim();
                x.label = s.isEmpty() || s.equalsIgnoreCase("hdmi") ? "HDMI " + n : s;
                int st = TvInputManager.INPUT_STATE_CONNECTED;
                try { st = tm.getInputState(x.id); } catch (Throwable ignored) {}
                x.connected = st != TvInputManager.INPUT_STATE_DISCONNECTED;
                out.add(x);
            }
        } catch (Throwable t) {
            Journal.e("inputs", t);
        }
        return out;
    }

    /** Открыть вход в Live TV (passthrough-канал). */
    static boolean open(Context c, String id) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(id));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
            return true;
        } catch (Throwable t) {
            Journal.e("input open " + id, t);
            return false;
        }
    }
}
