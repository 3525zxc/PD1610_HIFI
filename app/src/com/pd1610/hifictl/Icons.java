package com.pd1610.hifictl;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/**
 * 24dp 线性图标，全部用 Canvas + Path 画（不引入任何资源文件）。
 *
 * 约定：统一在 24×24 的逻辑网格里绘制，stroke 2、圆头圆角；调用方给出目标方框，
 * 内部按方框尺寸缩放，所以同一套路径可画 16 / 20 / 24dp 三种尺寸。
 */
final class Icons {

    static final int SLIDERS = 0;
    static final int PULSE = 1;
    static final int GEAR = 2;
    static final int INFO = 3;
    static final int REFRESH = 4;
    static final int CHEVRON = 5;
    static final int CHECK = 6;
    static final int DOT = 7;

    private Icons() {
    }

    /** 在 box 内画指定图标 */
    static void draw(Canvas canvas, RectF box, int which, int color) {
        if (box.width() <= 0f || box.height() <= 0f) return;
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(2f);

        canvas.save();
        canvas.translate(box.left, box.top);
        canvas.scale(box.width() / 24f, box.height() / 24f);

        switch (which) {
            case SLIDERS:
                drawSliders(canvas, p);
                break;
            case PULSE:
                drawPulse(canvas, p);
                break;
            case GEAR:
                drawGear(canvas, p);
                break;
            case INFO:
                drawInfo(canvas, p);
                break;
            case REFRESH:
                drawRefresh(canvas, p);
                break;
            case CHEVRON:
                drawChevron(canvas, p);
                break;
            case CHECK:
                drawCheck(canvas, p);
                break;
            default:
                p.setStyle(Paint.Style.FILL);
                canvas.drawCircle(12f, 12f, 3f, p);
                break;
        }
        canvas.restore();
    }

    // ---------------- 各图标 ----------------

    /** 三条横线 + 两个滑块钮 */
    private static void drawSliders(Canvas c, Paint p) {
        c.drawLine(3f, 6f, 21f, 6f, p);
        c.drawLine(3f, 12f, 21f, 12f, p);
        c.drawLine(3f, 18f, 21f, 18f, p);
        Paint f = new Paint(p);
        f.setStyle(Paint.Style.FILL);
        c.drawCircle(9f, 6f, 2.1f, f);
        c.drawCircle(15f, 12f, 2.1f, f);
        c.drawCircle(11f, 18f, 2.1f, f);
    }

    /** 心电图折线 */
    private static void drawPulse(Canvas c, Paint p) {
        Path path = new Path();
        path.moveTo(2f, 12f);
        path.lineTo(6f, 12f);
        path.lineTo(8.5f, 6.5f);
        path.lineTo(11.5f, 17.5f);
        path.lineTo(14f, 9.5f);
        path.lineTo(16f, 12f);
        path.lineTo(22f, 12f);
        c.drawPath(path, p);
    }

    /** 齿轮：内圈 + 8 个齿 */
    private static void drawGear(Canvas c, Paint p) {
        c.drawCircle(12f, 12f, 5.2f, p);
        for (int i = 0; i < 8; i++) {
            double a = Math.PI * i / 4.0;
            float cos = (float) Math.cos(a);
            float sin = (float) Math.sin(a);
            c.drawLine(12f + cos * 6.6f, 12f + sin * 6.6f,
                    12f + cos * 9.2f, 12f + sin * 9.2f, p);
        }
    }

    /** 圆圈 + i */
    private static void drawInfo(Canvas c, Paint p) {
        c.drawCircle(12f, 12f, 9f, p);
        c.drawLine(12f, 11.2f, 12f, 16.6f, p);
        Paint f = new Paint(p);
        f.setStyle(Paint.Style.FILL);
        c.drawCircle(12f, 8f, 1.2f, f);
    }

    /** 圆弧 + 箭头 */
    private static void drawRefresh(Canvas c, Paint p) {
        RectF oval = new RectF(4f, 4f, 20f, 20f);
        c.drawArc(oval, -60f, 290f, false, p);
        float ex = 12f + 8f * (float) Math.cos(Math.toRadians(-60));
        float ey = 12f + 8f * (float) Math.sin(Math.toRadians(-60));
        Path head = new Path();
        head.moveTo(ex + 0.6f, ey - 3.6f);
        head.lineTo(ex - 3.4f, ey + 0.4f);
        head.lineTo(ex + 3.2f, ey + 2.2f);
        head.close();
        Paint f = new Paint(p);
        f.setStyle(Paint.Style.FILL);
        c.drawPath(head, f);
    }

    /** › 右尖括号 */
    private static void drawChevron(Canvas c, Paint p) {
        p.setStrokeWidth(2.2f);
        Path path = new Path();
        path.moveTo(9f, 5f);
        path.lineTo(15f, 12f);
        path.lineTo(9f, 19f);
        c.drawPath(path, p);
    }

    /** 勾 */
    private static void drawCheck(Canvas c, Paint p) {
        p.setStrokeWidth(2.2f);
        Path path = new Path();
        path.moveTo(5f, 12.5f);
        path.lineTo(10f, 17.5f);
        path.lineTo(19f, 6.5f);
        c.drawPath(path, p);
    }

    // ---------------- 承载图标的小 View ----------------

    /** 定尺寸由调用方通过 LayoutParams 给出（建议 16/20/24dp）。 */
    static final class Icon extends View {

        private final RectF box = new RectF();
        private int which = DOT;
        private int color = Wds.TEXT_2;

        Icon(Context c) {
            super(c);
        }

        void set(int iconId, int iconColor) {
            which = iconId;
            color = iconColor;
            invalidate();
        }

        void setColor(int iconColor) {
            color = iconColor;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float size = Math.min(getWidth(), getHeight());
            if (size <= 0f) return;
            float left = (getWidth() - size) / 2f;
            float top = (getHeight() - size) / 2f;
            box.set(left, top, left + size, top + size);
            Icons.draw(canvas, box, which, color);
        }
    }
}
