package home.lite.tv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.View;
import android.view.ViewOutlineProvider;

final class Widgets {
    private Widgets() {}

    /**
     * Прямоугольник с «непрерывными» углами (как иконки iOS): каждый угол — четверть суперэллипса,
     * поэтому скругление плавно перетекает в стороны без излома.
     */
    static Path squircle(float w, float h, float radius) {
        float r = Math.min(radius * 1.35f, Math.min(w, h) / 2f);
        float[][] centers = {{w - r, r}, {w - r, h - r}, {r, h - r}, {r, r}};
        Path p = new Path();
        final int steps = 12;
        for (int k = 0; k < 4; k++) {
            float cx = centers[k][0], cy = centers[k][1];
            for (int i = 0; i <= steps; i++) {
                double t = Math.PI / 2 * i / steps;
                float a = (float) Math.sqrt(Math.sin(t)) * r, b = (float) Math.sqrt(Math.cos(t)) * r;
                float x, y;
                switch (k) {
                    case 0: x = cx + a; y = cy - b; break;  // правый верхний: сверху вправо
                    case 1: x = cx + b; y = cy + a; break;  // правый нижний
                    case 2: x = cx - a; y = cy + b; break;  // левый нижний
                    default: x = cx - b; y = cy - a; break; // левый верхний
                }
                if (k == 0 && i == 0) p.moveTo(x, y); else p.lineTo(x, y);
            }
        }
        p.close();
        return p;
    }

    /** Плитка приложения: баннер в форме сквиркла, в фокусе — блик и светлая кромка. */
    static final class Tile extends View {
        private final Bitmap bitmap;
        private final float roundness;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint gloss = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        private Path shape = new Path();
        /** Прозрачность без фокуса и в фокусе — настраиваются отдельно. */
        private float opacity = 1f, focusOpacity = 1f;

        Tile(Context c, Bitmap bitmap, float roundness) {
            super(c);
            this.bitmap = bitmap;
            this.roundness = roundness;
            setFocusable(true);
            setClickable(true);
            setLongClickable(true);
            edge.setStyle(Paint.Style.STROKE);
            setOutlineProvider(new ViewOutlineProvider() {
                @Override public void getOutline(View v, Outline o) {
                    if (shape.isConvex()) o.setConvexPath(shape);
                    else o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), v.getHeight() * Tile.this.roundness);
                }
            });
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            shape = squircle(w, h, h * roundness);
            BitmapShader shader = new BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            Matrix m = new Matrix();
            m.setRectToRect(new RectF(0, 0, bitmap.getWidth(), bitmap.getHeight()), new RectF(0, 0, w, h),
                    Matrix.ScaleToFit.FILL);
            shader.setLocalMatrix(m);
            paint.setShader(shader);
            gloss.setShader(new LinearGradient(0, 0, w * 0.4f, h, 0x40FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
            edge.setStrokeWidth(Math.max(1.5f, w / 220f));
            invalidateOutline();
        }

        void setOpacity(float normal, float focused) {
            opacity = normal;
            focusOpacity = focused;
            invalidate();
        }

        /** Тень от подъёма в фокусе просвечивала бы сквозь полупрозрачную плитку тёмным прямоугольником. */
        boolean opaqueWhenFocused() { return focusOpacity >= 1f; }

        // Прозрачность рисуется альфой кисти, а не View.setAlpha — без лишнего слоя на каждую плитку
        @Override
        public boolean hasOverlappingRendering() { return false; }

        @Override
        protected void onDraw(Canvas c) {
            boolean f = isFocused();
            int a = Math.round(255 * (f ? focusOpacity : opacity));
            paint.setAlpha(a);
            c.drawPath(shape, paint);
            if (f) {
                c.drawPath(shape, gloss);
                edge.setColor(0x80FFFFFF);
            } else {
                edge.setColor(0x1FFFFFFF);
            }
            c.drawPath(shape, edge);
        }

        @Override
        protected void onFocusChanged(boolean focused, int direction, Rect previous) {
            super.onFocusChanged(focused, direction, previous);
            invalidate();
        }
    }

    /** Полупрозрачная «пилюля» в верхней строке; в фокусе становится белой, как в tvOS. */
    static final class Pill extends View {
        static final int MIC = 0, GEAR = 1, WIFI = 2, ETHERNET = 3, OFFLINE = 4;
        private int icon;
        private final String label;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final float d;

        Pill(Context c, int icon, String label) {
            super(c);
            this.icon = icon;
            this.label = label;
            d = c.getResources().getDisplayMetrics().density;
            setFocusable(true);
            setClickable(true);
            ink.setTextSize(15 * c.getResources().getDisplayMetrics().scaledDensity);
            ink.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeCap(Paint.Cap.ROUND);
            stroke.setStrokeWidth(1.6f * d);
        }

        void setIcon(int i) {
            if (icon == i) return;
            icon = i;
            invalidate();
        }

        @Override
        protected void onFocusChanged(boolean focused, int direction, Rect previous) {
            super.onFocusChanged(focused, direction, previous);
            float s = focused ? 1.08f : 1f;
            animate().scaleX(s).scaleY(s).setDuration(150).start();
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            boolean f = isFocused();
            int fg = f ? 0xFF1C1C1E : 0xE6FFFFFF;
            fill.setColor(f ? Color.WHITE : 0x2EFFFFFF);
            ink.setColor(fg);
            stroke.setColor(fg);
            rect.set(0, 0, getWidth(), getHeight());
            float r = getHeight() / 2f;
            c.drawRoundRect(rect, r, r, fill);

            float my = getHeight() / 2f;
            if (icon == MIC) {
                float mx = r + 4 * d;
                c.drawRoundRect(new RectF(mx - 3.5f * d, my - 8 * d, mx + 3.5f * d, my + 2.5f * d), 3.5f * d, 3.5f * d, ink);
                c.drawArc(new RectF(mx - 6.5f * d, my - 5 * d, mx + 6.5f * d, my + 5.5f * d), 20, 140, false, stroke);
                c.drawLine(mx, my + 5.5f * d, mx, my + 8.5f * d, stroke);
                c.drawText(label, mx + 13 * d, my - (ink.descent() + ink.ascent()) / 2f, ink);
            } else if (icon == GEAR) {
                drawGear(c, getWidth() / 2f, my, 9 * d, ink);
            } else if (icon == ETHERNET) {
                drawEthernet(c, getWidth() / 2f, my, d, ink, stroke);
            } else {
                drawWifi(c, getWidth() / 2f, my, d, ink, stroke, icon == OFFLINE);
            }
        }
    }

    /** Wi‑Fi: точка и три дуги веером вверх; без сети — дуги бледнее и перечёркнуты. */
    static void drawWifi(Canvas c, float cx, float cy, float d, Paint dot, Paint arc, boolean off) {
        float by = cy + 6 * d;
        int color = arc.getColor();
        if (off) arc.setAlpha(Color.alpha(color) * 2 / 5);
        for (int i = 1; i <= 3; i++) {
            float r = 4.2f * d * i;
            c.drawArc(new RectF(cx - r, by - r, cx + r, by + r), 225, 90, false, arc);
        }
        c.drawCircle(cx, by - 0.4f * d, 1.6f * d, dot);
        arc.setColor(color);
        if (off) c.drawLine(cx - 8 * d, cy - 7 * d, cx + 8 * d, cy + 8 * d, arc);
    }

    /** Провод: разъём RJ‑45 — корпус с защёлкой и контактами. */
    static void drawEthernet(Canvas c, float cx, float cy, float d, Paint fill, Paint line) {
        RectF body = new RectF(cx - 7 * d, cy - 5 * d, cx + 7 * d, cy + 5 * d);
        c.drawRoundRect(body, 1.8f * d, 1.8f * d, line);
        c.drawRect(cx - 3 * d, cy + 5 * d, cx + 3 * d, cy + 8 * d, fill);
        for (int i = -1; i <= 1; i++) c.drawLine(cx + i * 3 * d, cy - 2.5f * d, cx + i * 3 * d, cy + 1 * d, line);
    }

    /** Шестерёнка: обод с отверстием и восемь зубцов. */
    static void drawGear(Canvas c, float cx, float cy, float r, Paint p) {
        Path gear = new Path();
        gear.setFillType(Path.FillType.EVEN_ODD);
        gear.addCircle(cx, cy, r * 0.74f, Path.Direction.CW);
        gear.addCircle(cx, cy, r * 0.32f, Path.Direction.CW);
        c.drawPath(gear, p);
        RectF tooth = new RectF(cx - r * 0.17f, cy - r, cx + r * 0.17f, cy - r * 0.6f);
        for (int i = 0; i < 8; i++) {
            c.save();
            c.rotate(i * 45f, cx, cy);
            c.drawRoundRect(tooth, r * 0.06f, r * 0.06f, p);
            c.restore();
        }
    }
}
