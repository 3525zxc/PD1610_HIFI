package com.pd1610.hifictl;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Shader;
import android.view.View;

import java.io.InputStream;

/**
 * 圆形头像：从 assets/avatars/ 读图，按最大边中心裁切后画进圆里，外加 1dp 描边。
 * 图读不到时退化成绿底 + 首字，保证布局不塌、不崩。
 */
final class Avatar extends View {

    private static final int DECODE_PX = 160;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glyph = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint image = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Bitmap bitmap;
    private final String initial;
    private final Matrix matrix = new Matrix();

    Avatar(Context c, String asset, String initial, float density) {
        super(c);
        this.initial = initial;
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(Math.max(1f, density));
        ring.setColor(Wds.HAIRLINE);
        glyph.setColor(Wds.GREEN_DEEP);
        glyph.setTextSize(16f * c.getResources().getDisplayMetrics().scaledDensity);
        glyph.setTypeface(Wds.MED);
        glyph.setTextAlign(Paint.Align.CENTER);
        fill.setColor(Wds.GREEN_WASH);
        bitmap = load(c, asset);
    }

    private static Bitmap load(Context c, String asset) {
        if (asset == null) return null;
        try (InputStream probe = c.getAssets().open("avatars/" + asset)) {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeStream(probe, null, bounds);
            BitmapFactory.Options opts = new BitmapFactory.Options();
            int sample = 1;
            while (bounds.outWidth / (sample * 2) >= DECODE_PX
                    && bounds.outHeight / (sample * 2) >= DECODE_PX) {
                sample *= 2;
            }
            opts.inSampleSize = sample;
            try (InputStream in = c.getAssets().open("avatars/" + asset)) {
                return BitmapFactory.decodeStream(in, null, opts);
            }
        } catch (Throwable t) {
            return null;
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (bitmap == null || w <= 0 || h <= 0) return;
        BitmapShader shader = new BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        float scale = Math.max(w / (float) bitmap.getWidth(), h / (float) bitmap.getHeight());
        matrix.setScale(scale, scale);
        matrix.postTranslate((w - bitmap.getWidth() * scale) / 2f,
                (h - bitmap.getHeight() * scale) / 2f);
        shader.setLocalMatrix(matrix);
        image.setShader(shader);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth();
        float h = getHeight();
        if (w <= 0 || h <= 0) return;
        float radius = Math.min(w, h) / 2f - ring.getStrokeWidth() / 2f;
        float cx = w / 2f;
        float cy = h / 2f;

        if (bitmap != null && image.getShader() != null) {
            canvas.drawCircle(cx, cy, radius, image);
        } else {
            canvas.drawCircle(cx, cy, radius, fill);
            if (initial != null && !initial.isEmpty()) {
                float baseline = cy - (glyph.descent() + glyph.ascent()) / 2f;
                canvas.drawText(initial, cx, baseline, glyph);
            }
        }
        canvas.drawCircle(cx, cy, radius, ring);
    }
}
