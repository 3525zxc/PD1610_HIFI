package com.pd1610.hifictl;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.widget.SeekBar;

/**
 * 3.6 Slider 皮肤（震度与 DSD 音量共用）：
 * 轨道 4dp（已走 GREEN_DEEP / 未走 #3B4A54），thumb 16dp → 按下 20dp，GREEN_DEEP 实心 + 2dp BG_PAGE 描边。
 */
final class SeekSkin {

    private SeekSkin() {
    }

    static void apply(SeekBar bar, Wds w) {
        bar.setProgressDrawable(new Track(w.density));
        bar.setThumb(new Thumb(w.density));
        bar.setSplitTrack(false);
        sync(bar);
    }

    /** 进度变化后调用，保证已走轨道宽度与 thumb 同步 */
    static void sync(SeekBar bar) {
        Drawable d = bar.getProgressDrawable();
        if (d != null && bar.getMax() > 0) {
            d.setLevel(level(bar.getProgress(), bar.getMax()));
        }
    }

    static int level(int progress, int max) {
        if (max <= 0) return 0;
        return (int) (progress * 10000f / max);
    }

    // ---------------- 轨道 ----------------

    static final class Track extends Drawable {

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float height;
        private final RectF rect = new RectF();

        Track(float density) {
            height = 4f * density;
        }

        @Override
        public void draw(Canvas canvas) {
            Rect b = getBounds();
            if (b.width() <= 0 || b.height() <= 0) return;
            float cy = b.exactCenterY();
            float r = height / 2f;

            rect.set(b.left, cy - r, b.right, cy + r);
            paint.setColor(Wds.TRACK_OFF);
            canvas.drawRoundRect(rect, r, r, paint);

            float ratio = getLevel() / 10000f;
            if (ratio <= 0f) return;
            float right = b.left + b.width() * ratio;
            if (right - b.left < height) right = b.left + height;
            rect.set(b.left, cy - r, Math.min(right, b.right), cy + r);
            paint.setColor(Wds.GREEN_DEEP);
            canvas.drawRoundRect(rect, r, r, paint);
        }

        @Override
        public int getIntrinsicHeight() {
            return Math.round(height);
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter cf) {
            paint.setColorFilter(cf);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    // ---------------- 滑块 ----------------

    static final class Thumb extends Drawable {

        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float normalRadius;
        private final float pressedRadius;
        private boolean down = false;

        Thumb(float density) {
            normalRadius = 8f * density;   // 直径 16dp
            pressedRadius = 10f * density; // 按下 20dp
            fill.setColor(Wds.GREEN_DEEP);
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(2f * density);
            ring.setColor(Wds.BG_PAGE);
        }

        @Override
        public void draw(Canvas canvas) {
            Rect b = getBounds();
            float r = down ? pressedRadius : normalRadius;
            float limit = Math.min(b.width(), b.height()) / 2f;
            if (limit > 0f) r = Math.min(r, limit);
            float cx = b.exactCenterX();
            float cy = b.exactCenterY();
            canvas.drawCircle(cx, cy, r, fill);
            canvas.drawCircle(cx, cy, r, ring);
        }

        @Override
        public int getIntrinsicWidth() {
            return Math.round(pressedRadius * 2f);
        }

        @Override
        public int getIntrinsicHeight() {
            return Math.round(pressedRadius * 2f);
        }

        @Override
        public boolean isStateful() {
            return true;
        }

        @Override
        protected boolean onStateChange(int[] state) {
            boolean pressed = false;
            for (int s : state) {
                if (s == android.R.attr.state_pressed || s == android.R.attr.state_focused) {
                    pressed = true;
                    break;
                }
            }
            if (pressed != down) {
                down = pressed;
                invalidateSelf();
                return true;
            }
            return false;
        }

        @Override
        public void setAlpha(int alpha) {
            fill.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter cf) {
            fill.setColorFilter(cf);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    // ---------------- 刻度尺 ----------------

    /** 滑杆下方刻度：左 0 / 右 max / 默认值位置一条 1dp 竖线（严格按 def/max 比例） */
    static final class Ticks extends View {

        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float density;
        private final int max;
        private final int def;

        Ticks(Context c, Wds w, int max, int def) {
            super(c);
            this.density = w.density;
            this.max = max;
            this.def = def;
            float sd = c.getResources().getDisplayMetrics().scaledDensity;
            textPaint.setTextSize(Wds.SP_CAPTION * sd);
            textPaint.setTypeface(Wds.REG);
            linePaint.setColor(Wds.HAIRLINE);
            linePaint.setStrokeWidth(Math.max(1f, density));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int width = getWidth();
            int height = getHeight();
            if (width <= 0 || height <= 0) return;
            // 与 SeekBar thumb 半宽（20dp/2）对齐
            float pad = 10f * density;
            float left = pad;
            float right = width - pad;
            float span = right - left;
            if (span <= 0f) return;

            float base = Math.min(height, 16f * density);
            float markerX = left + span * def / (float) max;
            canvas.drawLine(markerX, 0f, markerX, 4f * density, linePaint);

            textPaint.setColor(Wds.TEXT_3);
            textPaint.setTextAlign(Paint.Align.LEFT);
            canvas.drawText("0", left, base, textPaint);

            textPaint.setTextAlign(Paint.Align.RIGHT);
            canvas.drawText(String.valueOf(max), right, base, textPaint);

            textPaint.setColor(Wds.TEXT_2);
            textPaint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText("默认 " + def, markerX, base, textPaint);
            textPaint.setTextAlign(Paint.Align.LEFT);
        }
    }
}
