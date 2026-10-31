package home.lite.tv;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.os.SystemClock;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Звуки нажатий пульта: набор WAV из assets/sfx/<набор>/ (move1..N, select, back).
 * Файлы копируются в кэш — так SoundPool не зависит от сжатия ассетов в APK.
 */
final class Sounds {
    static final int MOVE = 0, SELECT = 1, BACK = 2;

    private final Context ctx;
    private SoundPool pool;
    private String loaded;
    private final List<Integer> moves = new ArrayList<>();
    private int select, back;
    private final Random rnd = new Random();
    private long lastMove;

    Sounds(Context c) { ctx = c.getApplicationContext(); }

    /** Подготовить набор (null — звуки выключены, ресурсы освобождаются). */
    void use(String pack) {
        if (pack == null) { release(); return; }
        if (pack.equals(loaded) && pool != null) return;
        release();
        pool = new SoundPool.Builder().setMaxStreams(4).setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build();
        loaded = pack;
        try {
            String dir = "sfx/" + pack;
            for (String f : ctx.getAssets().list(dir)) {
                int id = pool.load(copy(dir + "/" + f).getPath(), 1);
                if (f.startsWith("move")) moves.add(id);
                else if (f.startsWith("select")) select = id;
                else if (f.startsWith("back")) back = id;
            }
        } catch (Exception e) {
            Journal.e("звуки " + pack + " не загрузились", e);
            release();
        }
    }

    void play(int kind, float volume) {
        if (pool == null) return;
        int id;
        if (kind == MOVE) {
            // Зажатая стрелка повторяет нажатия очень часто — не превращаем это в трель
            long now = SystemClock.uptimeMillis();
            if (now - lastMove < 70 || moves.isEmpty()) return;
            lastMove = now;
            id = moves.get(rnd.nextInt(moves.size()));
        } else {
            id = kind == SELECT ? select : back;
        }
        if (id != 0) pool.play(id, volume, volume, 1, 0, 1f);
    }

    void release() {
        if (pool != null) pool.release();
        pool = null;
        loaded = null;
        moves.clear();
        select = back = 0;
    }

    private File copy(String asset) throws Exception {
        File f = new File(ctx.getCacheDir(), Updater.currentCode(ctx) + "/" + asset);
        if (f.exists() && f.length() > 0) return f;
        f.getParentFile().mkdirs();
        try (InputStream in = ctx.getAssets().open(asset); OutputStream out = new FileOutputStream(f)) {
            byte[] b = new byte[8192];
            int r;
            while ((r = in.read(b)) > 0) out.write(b, 0, r);
        }
        return f;
    }
}
