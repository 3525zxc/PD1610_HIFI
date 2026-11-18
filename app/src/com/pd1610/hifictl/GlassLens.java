package com.pd1610.hifictl;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;

/**
 * 底栏长按时浮起的那圈「液态玻璃放大镜」。
 *
 * 交互（对齐酷安那颗液态指示器）：
 * 1. 长按底栏 → 一圈**透明玻璃**在该 tab 上出现并微微涨大；
 * 2. 手指拖动 → **只有这圈玻璃跟手滑动**；底栏的图标、文字一动不动地留在自己的格子里，
 *    从玻璃后面透出来 —— 镜子只负责"罩住"，不搬运任何东西；
 * 3. 松手 → 玻璃缩回静止尺寸并滑到手指所落的 tab，随后淡出，选中态交还给药丸。
 *
 * 尺寸：静止直径 = 导航栏高度（64dp，"等高等大"，上下正好与栏齐平），
 * 长按只涨到 72dp —— 比原来大一点，不夸张。
 *
 * 为什么镜内不放"放大的图标"：一个 tab 格约 84dp 宽，而镜子只有 64~72dp，
 * 一旦按 1.5 倍放大重绘，相邻图标会被推到圆外，镜子里大半时间是空的。
 * 真透明才符合"镜框滑过底栏"的观感：玻璃透光，图标留在原地，罩住谁就看清谁。
 *
 * 五层（自下而上）：柔影 / 极淡着色 / 顶部高光 / 底部内阴影 / 镜缘 + 顶缘高光弧。
 * 全部纯 Canvas 绘制，无 RenderScript、无位图，不存在降级后漏画的可能。
 */
final class GlassLens extends View {

    /** 静止直径 = 栏高 */
    private static final float D_REST = 64f;
    /** 长按后的直径：只比原来大一点 */
    private static final float D_PRESS = 72f;

    /** 出现 / 收镜 / 淡出 */
    private static final int SHOW_MS = 210;
    private static final int HIDE_MS = 200;
    private static final int FADE_MS = 130;
    /** 跟手阻尼：每帧把剩余距离吃掉的比例 */
    private static final float FOLLOW = 0.42f;

    private final Wds w;

    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF tmpRect = new RectF();

    private LinearGradient hiGradient;
    private float hiHeight;
    private LinearGradient innerGradient;
    private float innerHeight;

    private float cx;
    private float cy;
    private float targetX;
    /** 0 = 静止尺寸，1 = 长按尺寸 */
    private float t;

    private boolean following;
    private boolean shown;
    private ValueAnimator anim;

    /** 局部重绘：镜子上一次 / 这一次占的矩形，只把并集标脏 */
    private final Rect lastRect = new Rect();
    private final Rect nowRect = new Rect();
    private final Rect dirtyRect = new Rect();

    GlassLens(Context c, Wds w) {
        super(c);
        this.w = w;
        setClickable(false);
        setFocusable(false);
        setVisibility(GONE);
    }

    // ============================================================
    // 对外接口
    // ============================================================

    /** 在第 (x,y) 处长出圆镜（坐标与底栏同一个父容器） */
    void show(float x, float y) {
        cancelAnim();
        cx = x;
        cy = y;
        targetX = x;
        t = 0f;
        shown = true;
        setAlpha(0f);
        setVisibility(VISIBLE);
        animateT(1f, SHOW_MS);
    }

    /** 跟手：只取 x，y 锁在栏中线（x 已是父容器坐标） */
    void moveTo(float x) {
        targetX = x;
        if (!following) {
            following = true;
            postOnAnimation(tick);
        }
    }

    /**
     * 松手：滑到目标 tab 并缩回静止尺寸。
     * 这里**不回调** —— 页面的切换与药丸的交还由 GlassBar 在 UP 那一刻立即完成，
     * 镜子只管自己缩回去淡出；这样中途再点也不会出现"迟到的回调把高亮拉回旧位置"。
     */
    void release(float toX) {
        following = false;
        removeCallbacks(tick);
        cancelAnim();

        final float fromX = cx;
        final float fromT = t;
        ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
        a.setDuration(HIDE_MS);
        a.setInterpolator(new DecelerateInterpolator(1.6f));
        a.addUpdateListener(an -> {
            float p = (float) an.getAnimatedValue();
            t = fromT * (1f - p);
            cx = fromX + (toX - fromX) * p;
            invalidateLens();
        });
        a.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                // cancel() 也会走到这里；只有"当前这一支"动画正常结束才算收镜
                if (GlassLens.this.anim != a) return;
                GlassLens.this.anim = null;
                fadeOut();
            }
        });
        anim = a;
        a.start();
    }

    /** 立即消失（手势被打断 / Activity 退到后台），不播任何动画 */
    void releaseAll() {
        following = false;
        removeCallbacks(tick);
        cancelAnim();
        hide();
        setAlpha(1f);
    }

    // ============================================================
    // 动画
    // ============================================================

    private void animateT(float to, int ms) {
        ValueAnimator a = ValueAnimator.ofFloat(t, to);
        a.setDuration(ms);
        a.setInterpolator(new OvershootInterpolator(1.1f));
        a.addUpdateListener(an -> {
            t = (float) an.getAnimatedValue();
            setAlpha(Math.min(1f, Math.max(0f, t) * 2.2f));
            invalidateLens();
        });
        anim = a;
        a.start();
    }

    private void fadeOut() {
        ValueAnimator a = ValueAnimator.ofFloat(1f, 0f);
        a.setDuration(FADE_MS);
        a.addUpdateListener(an -> {
            setAlpha((float) an.getAnimatedValue());
            invalidateLens();
        });
        a.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (GlassLens.this.anim != a) return;
                GlassLens.this.anim = null;
                hide();
                setAlpha(1f);
            }
        });
        anim = a;
        a.start();
    }

    private void cancelAnim() {
        ValueAnimator a = anim;
        // 先摘引用再 cancel：cancel 会同步回调 onAnimationEnd，靠引用比对把这次回调挡掉
        anim = null;
        if (a != null) {
            try {
                a.cancel();
            } catch (Throwable ignore) {
            }
        }
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!following) return;
            float d = targetX - cx;
            if (Math.abs(d) < 0.4f) {
                cx = targetX;
                following = false;
                invalidateLens();
                return;
            }
            cx += d * FOLLOW;
            invalidateLens();
            postOnAnimation(this);
        }
    };

    private void hide() {
        shown = false;
        setVisibility(GONE);
        t = 0f;
        cx = 0f;
        cy = 0f;
        targetX = 0f;
        lastRect.setEmpty();
    }

    /**
     * 只标脏镜子经过的那条带。
     * 这个 View 是整屏 overlay，直接 invalidate() 会把整页重画一遍；
     * 跟手时 60fps 全屏重绘在 Xplay6 上是要掉帧的。
     */
    private void invalidateLens() {
        float half = w.dpf(D_PRESS / 2f + 14f);
        nowRect.set((int) (cx - half), (int) (cy - half), (int) (cx + half), (int) (cy + half));
        if (lastRect.isEmpty()) {
            invalidate(nowRect.left, nowRect.top, nowRect.right, nowRect.bottom);
        } else {
            dirtyRect.set(lastRect);
            dirtyRect.union(nowRect);
            invalidate(dirtyRect.left, dirtyRect.top, dirtyRect.right, dirtyRect.bottom);
        }
        lastRect.set(nowRect);
    }

    @Override
    protected void onDetachedFromWindow() {
        following = false;
        removeCallbacks(tick);
        cancelAnim();
        super.onDetachedFromWindow();
    }

    // ============================================================
    // 绘制
    // ============================================================

    @Override
    protected void onDraw(Canvas canvas) {
        if (!shown) return;

        float t = Math.max(0f, this.t);
        float d = w.dpf(D_REST + (D_PRESS - D_REST) * t);
        float r = d / 2f;

        // 0. 浮起柔影
        drawHalo(canvas, r);

        // 1. 极淡的着色：玻璃要透，别把底下的图标闷死
        fillPaint.setStyle(Paint.Style.FILL);
        fillPaint.setShader(null);
        fillPaint.setColor(Wds.LENS_TINT);
        canvas.drawCircle(cx, cy, r, fillPaint);

        // 2. 顶部高光
        if (hiGradient == null || Math.abs(hiHeight - d) > 1f) {
            hiHeight = d;
            hiGradient = new LinearGradient(0f, cy - r, 0f, cy - r + d * 0.22f,
                    Wds.LENS_HI_TOP, 0x00FFFFFF, Shader.TileMode.CLAMP);
        }
        fillPaint.setShader(hiGradient);
        fillPaint.setAlpha(Math.round(255 * Math.min(1f, t * 1.4f)));
        canvas.drawCircle(cx, cy, r, fillPaint);
        fillPaint.setShader(null);

        // 3. 底部内阴影：玻璃"厚度"的来源
        if (innerGradient == null || Math.abs(innerHeight - d) > 1f) {
            innerHeight = d;
            innerGradient = new LinearGradient(0f, cy + r * 0.44f, 0f, cy + r,
                    0x00000000, Wds.LENS_INNER, Shader.TileMode.CLAMP);
        }
        fillPaint.setShader(innerGradient);
        fillPaint.setAlpha(Math.round(255 * Math.min(1f, t * 1.4f)));
        canvas.drawCircle(cx, cy, r, fillPaint);
        fillPaint.setShader(null);

        // 4. 镜缘 + 顶缘高光弧
        strokePaint.setStyle(Paint.Style.STROKE);
        float sw = Math.max(1f, w.dpf(1.3f));
        strokePaint.setStrokeWidth(sw);
        strokePaint.setColor(Wds.LENS_RIM);
        canvas.drawCircle(cx, cy, r - sw / 2f, strokePaint);

        strokePaint.setColor(Wds.LENS_SPEC);
        strokePaint.setStrokeWidth(Math.max(1f, w.dpf(1.5f)));
        strokePaint.setStrokeCap(Paint.Cap.ROUND);
        tmpRect.set(cx - r + w.dpf(1.5f), cy - r + w.dpf(1.5f),
                cx + r - w.dpf(1.5f), cy + r - w.dpf(1.5f));
        canvas.drawArc(tmpRect, 198f, 144f, false, strokePaint);
        strokePaint.setStrokeCap(Paint.Cap.BUTT);
    }

    /** 浮起柔影：三层外扩描边，比 setShadowLayer 便宜且硬件加速下可控 */
    private void drawHalo(Canvas canvas, float r) {
        int base = Math.round(40 * Math.min(1f, t * 1.3f));
        strokePaint.setStyle(Paint.Style.STROKE);
        for (int i = 0; i < 3; i++) {
            strokePaint.setColor(fade(Wds.LENS_SHADOW, (base - i * 12) / 40f));
            strokePaint.setStrokeWidth(w.dpf(3f + i * 3.2f));
            canvas.drawCircle(cx, cy + w.dpf(1f), r + w.dpf(1.4f + i * 2f), strokePaint);
        }
    }

    /** 只改 alpha，保留令牌自己的底色 */
    private static int fade(int color, float f) {
        int a = Math.round(((color >>> 24) & 0xFF) * Math.max(0f, Math.min(1f, f)));
        return (a << 24) | (color & 0x00FFFFFF);
    }
}
