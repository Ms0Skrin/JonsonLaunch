package home.lite.tv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/** Фоны рисуются один раз в маленький битмап и растягиваются на экран — для слабого железа это почти бесплатно. */
final class Backgrounds {
    private Backgrounds() {}

    /** Первый цвет — основа, остальные — пятна. */
    private static final int[][] PALETTES = {
            {0xFF070B1F, 0xFF2E5BFF, 0xFF7A2EFF, 0xFF00C2A8, 0xFFFF4F9A},
            {0xFF16070D, 0xFFFF6A3D, 0xFFFF2E63, 0xFF8A2BE2, 0xFFFFB347},
            {0xFF02111C, 0xFF0077B6, 0xFF00B4D8, 0xFF023E8A, 0xFF5EE6D0},
            {0xFF04120C, 0xFF1B998B, 0xFF2D6A4F, 0xFF52B788, 0xFF3A86FF},
            {0xFF0B0B0D, 0xFF3A3A40, 0xFF5E5E66, 0xFF26262B, 0xFF8E8E93},
    };

    /** Абстрактная «аврора»: несколько мягких цветных пятен, размытых и слегка зашумлённых против полос. */
    static Bitmap abstractArt(int palette, int seed) {
        int w = 320, h = 180;
        int[] p = PALETTES[Math.max(0, Math.min(palette, PALETTES.length - 1))];
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.drawColor(p[0]);
        Random r = new Random(seed * 7919L + palette);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        for (int i = 0; i < 7; i++) {
            int col = p[1 + (i + seed) % 4];
            float cx = r.nextFloat() * w, cy = r.nextFloat() * h * 0.85f;
            float rad = (0.30f + r.nextFloat() * 0.40f) * w;
            paint.setShader(new RadialGradient(cx, cy, rad,
                    new int[] {alpha(col, 0xB4), alpha(col, 0x48), alpha(col, 0)},
                    new float[] {0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
            c.drawCircle(cx, cy, rad, paint);
        }
        boxBlur(b, 10);
        boxBlur(b, 10);
        dither(b, r);
        return b;
    }

    private static final Map<String, Integer> COUNTS = new HashMap<>();

    /** Сколько картинок в наборе assets/bg/<set>; считается один раз. */
    static synchronized int count(Context c, String set) {
        Integer n = COUNTS.get(set);
        if (n == null) {
            n = 0;
            try {
                for (String f : c.getAssets().list("bg/" + set)) if (f.endsWith(".jpg")) n++;
            } catch (IOException ignored) {
            }
            COUNTS.put(set, n);
        }
        return n;
    }

    /** Картинка из assets/bg/<set>; RGB_565 — вдвое меньше памяти, для фона за плитками разницы не видно. */
    static Bitmap picture(Context c, String set, int index) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inPreferredConfig = Bitmap.Config.RGB_565;
        try (InputStream in = c.getAssets().open(String.format("bg/%s/%02d.jpg", set, index + 1))) {
            return BitmapFactory.decodeStream(in, null, o);
        } catch (IOException e) {
            return null;
        }
    }

    /** Размытая подсветка из баннера приложения. */
    static Bitmap wash(Bitmap banner) {
        Bitmap tiny = Bitmap.createScaledBitmap(banner, 24, 14, true);
        // Чуть насыщеннее и темнее, чтобы светлые баннеры не засвечивали экран
        ColorMatrix cm = new ColorMatrix();
        cm.setSaturation(1.35f);
        cm.postConcat(new ColorMatrix(new float[] {
                0.72f, 0, 0, 0, 0, 0, 0.72f, 0, 0, 0, 0, 0, 0.72f, 0, 0, 0, 0, 0, 1, 0}));
        Paint tint = new Paint(Paint.FILTER_BITMAP_FLAG);
        tint.setColorFilter(new ColorMatrixColorFilter(cm));
        Bitmap mid = Bitmap.createBitmap(192, 108, Bitmap.Config.ARGB_8888);
        new Canvas(mid).drawBitmap(tiny, null, new RectF(0, 0, 192, 108), tint);
        boxBlur(mid, 6);
        boxBlur(mid, 6);
        return mid;
    }

    private static int alpha(int c, int a) { return (c & 0xFFFFFF) | (a << 24); }

    static void boxBlur(Bitmap b, int r) {
        int w = b.getWidth(), h = b.getHeight();
        int[] src = new int[w * h], dst = new int[w * h];
        b.getPixels(src, 0, w, 0, 0, w, h);
        blurPass(src, dst, w, h, r, 1, w);
        blurPass(dst, src, h, w, r, w, 1);
        b.setPixels(src, 0, w, 0, 0, w, h);
    }

    /** Один проход скользящего среднего вдоль линий длиной len; step — шаг внутри линии, lineStep — между линиями. */
    private static void blurPass(int[] in, int[] out, int len, int lines, int r, int step, int lineStep) {
        int n = 2 * r + 1;
        for (int l = 0; l < lines; l++) {
            int base = l * lineStep, sr = 0, sg = 0, sb = 0;
            for (int i = -r; i <= r; i++) {
                int c = in[base + clamp(i, len) * step];
                sr += (c >> 16) & 0xFF;
                sg += (c >> 8) & 0xFF;
                sb += c & 0xFF;
            }
            for (int i = 0; i < len; i++) {
                out[base + i * step] = 0xFF000000 | ((sr / n) << 16) | ((sg / n) << 8) | (sb / n);
                int add = in[base + clamp(i + r + 1, len) * step];
                int rem = in[base + clamp(i - r, len) * step];
                sr += ((add >> 16) & 0xFF) - ((rem >> 16) & 0xFF);
                sg += ((add >> 8) & 0xFF) - ((rem >> 8) & 0xFF);
                sb += (add & 0xFF) - (rem & 0xFF);
            }
        }
    }

    private static void dither(Bitmap b, Random r) {
        int w = b.getWidth(), h = b.getHeight();
        int[] px = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        for (int i = 0; i < px.length; i++) {
            int d = r.nextInt(5) - 2, c = px[i];
            px[i] = 0xFF000000 | (ch((c >> 16) & 0xFF, d) << 16) | (ch((c >> 8) & 0xFF, d) << 8) | ch(c & 0xFF, d);
        }
        b.setPixels(px, 0, w, 0, 0, w, h);
    }

    private static int ch(int v, int d) { v += d; return v < 0 ? 0 : v > 255 ? 255 : v; }

    private static int clamp(int i, int len) { return i < 0 ? 0 : i >= len ? len - 1 : i; }
}
