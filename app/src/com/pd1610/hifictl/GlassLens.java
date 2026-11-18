package com.pd1610.hifictl;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.renderscript.Allocation;
import android.renderscript.Element;
import android.renderscript.RenderScript;
import android.renderscript.ScriptIntrinsicBlur;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 底栏长按时浮起的「液态玻璃放大镜」。
 *
 * 交互（对齐酷安那颗液态指示器）：
 * 1. 长按任一 tab → 该 tab 的药丸原地涨成一颗直径 60dp 的圆镜，镜里是**放大 + 模糊**的背景；
 * 2. 手指继续移动 → 圆镜带阻尼跟手连续滑动，越过 tab 中点就换选中项（并给一次轻震）；
 * 3. 松手 → 圆镜缩回手指所在的 tab，形状收成那颗药丸，然后切页。
 *
 * 关键设计：镜子的形状由单一参数 t 驱动 —— 宽 64→60dp、高 32→60dp、圆角恒为半高，
 * 所以 t=0 时它与 {@link GlassBar} 里的药丸**逐像素重合**（同色同描边同位置）。
 * 于是「长出来」和「缩回去」都不需要淡入淡出，形状本身就是连续的，没有跳变。
 *
 * 折射用的是真快照：DOWN 时先把内容层缩 1/4 画进小位图（此时离长按触发还有 320ms，
 * 足够在 worker 上模糊完），镜内按 1.5 倍采样同一张位图 —— 这才是「放大镜」而不是
 * 「磨砂玻璃块」。模糊失败（RenderScript 不可用）就退回未模糊的放大图，再不行只画玻璃层。
 *
 * 所有失败路径都吞异常：这颗镜子绝不能因为取快照或模糊失败而把 App 带崩。
 */
final class GlassLens extends View {

    /** 快照缩小倍数：1/3 时栅格化成本只有全尺寸的 1/9，放大后仍够糊得有细节 */
    private static final int SNAP = 3;
    /** 模糊半径（在 1/3 快照上算，等效全尺寸的 3 倍） */
    private static final float BLUR_R = 3f;
    /** 镜内放大倍率 */
    private static final float ZOOM = 1.5f;

    /** 药丸 64×32dp —— 与 GlassBar 的 PILL_W/PILL_H 保持一致 */
    private static final float PILL_W = 64f;
    private static final float PILL_H = 32f;
    /** 按下后圆镜直径 */
    private static final float LENS_D = 60f;
    /** 镜内图标 24→30dp */
    private static final float ICON = 24f;
    private static final float ICON_PRESS = 30f;

    /** 长出来 / 缩回去的时长 */
    private static final int SHOW_MS = 230;
    private static final int HIDE_MS = 200;
    /** 跟手阻尼：每帧把剩余距离吃掉的比例，越大越"硬" */
    private static final float FOLLOW = 0.42f;

    private final Wds w;
    private final Context app;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shaderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF shape = new RectF();
    private final RectF tmpRect = new RectF();
    private final RectF iconBox = new RectF();
    private final Matrix matrix = new Matrix();

    private View source;
    /** 未模糊的 1/4 快照；模糊完成后 {@link #glass} 才有值 */
    private Bitmap snap;
    private Bitmap glass;
    private BitmapShader shader;
    private LinearGradient hiGradient;
    private float hiHeight;
    private LinearGradient innerGradient;
    private float innerHeight;

    private RenderScript rs;
    private boolean rsDead;

    private float cx;
    private float cy;
    private float targetX;
    private float t;
    private int iconId = Icons.DOT;

    private boolean following;
    private boolean shown;
    private ValueAnimator anim;

    /** 局部重绘用：镜子上一次 / 这一次占的矩形，只把两者的并集标脏 */
    private final Rect lastRect = new Rect();
    private final Rect nowRect = new Rect();
    private final Rect dirtyRect = new Rect();

    GlassLens(Context c, Wds w) {
        super(c);
        this.w = w;
        this.app = c.getApplicationContext();
        setClickable(false);
        setFocusable(false);
        setVisibility(GONE);
    }

    // ============================================================
    // 对外接口
    // ============================================================

    /** 绑定内容层：之后自己截取、自己模糊 */
    void attachSource(View content) {
        source = content;
    }

    /**
     * 长按按下前先备好玻璃：截一张 1/4 快照丢给 worker 模糊。
     * 在 DOWN 时调用，等 320ms 长按触发时结果通常已经回来了。
     */
    void prepare() {
        View v = source;
        if (v == null) return;
        int vw = v.getWidth();
        int vh = v.getHeight();
        if (vw <= 0 || vh <= 0) return;

        final int sw = Math.max(1, vw / SNAP);
        final int sh = Math.max(1, vh / SNAP);
        Bitmap b = null;
        try {
            b = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(b);
            canvas.scale(1f / SNAP, 1f / SNAP);
            v.draw(canvas);
        } catch (Throwable e) {
            if (b != null) b.recycle();
            return;
        }

        final Bitmap raw = b;
        snap = raw;
        glass = null;
        shader = null;
        // 旧快照不在这里 recycle：worker 可能正在读它。位图交给 GC，
        // 一次只多占几百 KB，换的是绝不会因为回收正在被读的位图而 native 崩。

        try {
            worker.execute(() -> {
                final Bitmap blurred = blur(raw);
                ui.post(() -> {
                    if (snap != raw) {
                        // 期间又截了新的一张，这张作废（它是独立的新位图，回收安全）
                        if (blurred != null) blurred.recycle();
                        return;
                    }
                    if (blurred == null) return;   // 模糊失败：仍用 raw 放大，只是不磨砂
                    glass = blurred;
                    shader = null;
                    invalidateLens();
                });
            });
        } catch (Throwable ignore) {
            // worker 已关闭（Activity 正在销毁），本轮不出玻璃层
        }
    }

    /** 从 t=0（正好是那颗药丸）长成圆镜，并开始接管这一项 */
    void show(int which, float x, float y) {
        cancelAnim();
        iconId = which;
        cx = x;
        cy = y;
        targetX = x;
        t = 0f;
        shown = true;
        setVisibility(VISIBLE);
        animateT(1f, SHOW_MS, true);
    }

    /** 跟手：只取 x，y 锁在药丸中线（tab 栏本来就是横向的） */
    void moveTo(float x) {
        targetX = x;
        if (!following) {
            following = true;
            postOnAnimation(tick);
        }
    }

    /** 松手：形状缩回药丸并滑到目标 tab 中心，结束后回调（由调用方切页） */
    void release(float toX, final Runnable done) {
        following = false;
        removeCallbacks(tick);
        cancelAnim();

        final float fromX = cx;
        final float fromY = cy;
        final float fromT = t;
        ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
        a.setDuration(HIDE_MS);
        a.setInterpolator(new DecelerateInterpolator(1.7f));
        a.addUpdateListener(an -> {
            float p = (float) an.getAnimatedValue();
            t = fromT * (1f - p);
            cx = fromX + (toX - fromX) * p;
            cy = fromY;
            invalidateLens();
        });
        a.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                // cancel() 也会走到这里；只有"当前这一支"动画正常结束才算收镜
                if (GlassLens.this.anim != a) return;
                GlassLens.this.anim = null;
                hide();
                if (done != null) done.run();
            }
        });
        anim = a;
        a.start();
    }

    void releaseAll() {
        following = false;
        removeCallbacks(tick);
        cancelAnim();
        hide();
        // 释放顺序跟着 Backdrop 走：先把 destroy 排进队列，再 shutdown，
        // 这样正在跑的模糊不会被 RS 提前销毁带崩
        try {
            worker.execute(() -> {
                if (rs != null) {
                    try {
                        rs.destroy();
                    } catch (Throwable ignore) {
                    }
                    rs = null;
                }
            });
        } catch (Throwable ignore) {
        }
        try {
            worker.shutdown();
        } catch (Throwable ignore) {
        }
    }

    // ============================================================
    // 动画与跟手
    // ============================================================

    private void animateT(float to, int ms, boolean overshoot) {
        ValueAnimator a = ValueAnimator.ofFloat(t, to);
        a.setDuration(ms);
        a.setInterpolator(overshoot ? new OvershootInterpolator(1.4f) : new DecelerateInterpolator());
        a.addUpdateListener(an -> {
            t = (float) an.getAnimatedValue();
            invalidateLens();
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
        float hw = w.dpf(LENS_D / 2f + 14f);
        float hh = w.dpf(LENS_D / 2f + 14f);
        nowRect.set((int) (cx - hw), (int) (cy - hh), (int) (cx + hw), (int) (cy + hh));
        if (lastRect.isEmpty()) {
            invalidate(nowRect);
        } else {
            dirtyRect.set(lastRect);
            dirtyRect.union(nowRect);
            invalidate(dirtyRect);
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

        float t = Math.max(0f, Math.min(1.12f, this.t));
        // 单一参数驱动形状：t=0 就是那颗 64×32 的药丸，t=1 是 60×60 的圆镜。
        // 这里不夹 1，让 overshoot 把圆镜顶到 63dp 再落回 60dp，才有"弹起来"的手感。
        float wd = w.dpf(PILL_W + (LENS_D - PILL_W) * t);
        float ht = w.dpf(PILL_H + (LENS_D - PILL_H) * t);
        float hw = wd / 2f;
        float hh = ht / 2f;
        shape.set(cx - hw, cy - hh, cx + hw, cy + hh);
        float r = Math.min(hh, hw);

        // 0. 浮起柔影（t=0 时不存在，与药丸贴底时一致）
        if (t > 0.02f) drawHalo(canvas, r, t);

        // 1. 放大 + 模糊的背景（真折射）
        drawRefraction(canvas, r, t);

        // 2. 底色：从"药丸绿"过渡到"玻璃青黑"
        fillPaint.setStyle(Paint.Style.FILL);
        fillPaint.setShader(null);
        if (t < 0.995f) {
            fillPaint.setColor(fade(Wds.GLASS_PILL, Math.min(1f, 1f - t)));
            canvas.drawRoundRect(shape, r, r, fillPaint);
        }
        if (t > 0.005f) {
            fillPaint.setColor(fade(Wds.LENS_TINT, Math.min(1f, t)));
            canvas.drawRoundRect(shape, r, r, fillPaint);
        }

        // 3. 顶部高光（渐变建在原点，靠 translate 跟随：镜子在动，绝对坐标的渐变会漂）
        if (t > 0.02f) {
            if (hiGradient == null || Math.abs(hiHeight - ht) > 1f) {
                hiHeight = ht;
                hiGradient = new LinearGradient(0f, 0f, 0f, ht * 0.55f,
                        Wds.LENS_HI_TOP, 0x00FFFFFF, Shader.TileMode.CLAMP);
            }
            int sc = canvas.save();
            canvas.translate(0f, shape.top);
            tmpRect.set(shape.left, 0f, shape.right, ht);
            fillPaint.setShader(hiGradient);
            fillPaint.setAlpha(Math.round(255 * Math.min(1f, t)));
            canvas.drawRoundRect(tmpRect, r, r, fillPaint);
            fillPaint.setShader(null);
            canvas.restoreToCount(sc);
        }

        // 4. 底部内阴影：玻璃"厚度"的来源，没有它就像一块贴纸
        if (t > 0.02f) {
            if (innerGradient == null || Math.abs(innerHeight - ht) > 1f) {
                innerHeight = ht;
                innerGradient = new LinearGradient(0f, ht * 0.58f, 0f, ht,
                        0x00000000, 0x40000000, Shader.TileMode.CLAMP);
            }
            int sc2 = canvas.save();
            canvas.translate(0f, shape.top);
            tmpRect.set(shape.left, 0f, shape.right, ht);
            fillPaint.setShader(innerGradient);
            fillPaint.setAlpha(Math.round(255 * Math.min(1f, t)));
            canvas.drawRoundRect(tmpRect, r, r, fillPaint);
            fillPaint.setShader(null);
            canvas.restoreToCount(sc2);
        }

        // 5. 描边：药丸绿边 → 白色镜缘
        strokePaint.setStyle(Paint.Style.STROKE);
        tmpRect.set(shape);
        float sw = Math.max(1f, w.dpf(1.4f));
        strokePaint.setStrokeWidth(sw);
        tmpRect.inset(sw / 2f, sw / 2f);
        if (t < 0.995f) {
            strokePaint.setColor(fade(Wds.GLASS_PILL_EDGE, Math.min(1f, 1f - t)));
            canvas.drawRoundRect(tmpRect, r, r, strokePaint);
        }
        if (t > 0.005f) {
            strokePaint.setColor(fade(Wds.LENS_RIM, Math.min(1f, t)));
            canvas.drawRoundRect(tmpRect, r, r, strokePaint);

            // 顶缘那道高光弧：只占上方约 120°，像玻璃受光
            strokePaint.setColor(fade(Wds.LENS_SPEC, Math.min(1f, t)));
            strokePaint.setStrokeWidth(Math.max(1f, w.dpf(1.6f)));
            strokePaint.setStrokeCap(Paint.Cap.ROUND);
            tmpRect.inset(w.dpf(1.5f), w.dpf(1.5f));
            canvas.drawArc(tmpRect, 196f, 148f, false, strokePaint);
            strokePaint.setStrokeCap(Paint.Cap.BUTT);
        }

        // 6. 图标：24→30dp
        float iconSide = w.dpf(ICON + (ICON_PRESS - ICON) * Math.min(1f, t));
        iconBox.set(cx - iconSide / 2f, cy - iconSide / 2f, cx + iconSide / 2f, cy + iconSide / 2f);
        Icons.draw(canvas, iconBox, iconId, Wds.GLASS_ACCENT);
    }

    /** 镜内的放大背景：把 1/3 快照按 1.5 倍采样后裁进圆角形状 */
    private void drawRefraction(Canvas canvas, float r, float t) {
        if (t <= 0.02f) return;
        Bitmap bmp = (glass != null && !glass.isRecycled()) ? glass : snap;
        if (bmp == null || bmp.isRecycled()) return;
        if (shader == null) {
            try {
                shader = new BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            } catch (Throwable e) {
                shader = null;
                return;
            }
        }
        // 采样窗口以 (cx,cy) 为中心、按 ZOOM 缩小，再放大铺满镜子
        float k = SNAP * ZOOM;
        matrix.setScale(k, k);
        matrix.postTranslate(cx * (1f - ZOOM), cy * (1f - ZOOM));
        shader.setLocalMatrix(matrix);

        int save = canvas.save();
        canvas.clipRect(shape.left, shape.top, shape.right, shape.bottom);
        shaderPaint.setShader(shader);
        shaderPaint.setAlpha(Math.round(255 * Math.min(1f, t)));
        canvas.drawRoundRect(shape, r, r, shaderPaint);
        shaderPaint.setShader(null);
        canvas.restoreToCount(save);
    }

    /** 只改 alpha，保留令牌自己的底色（直接 setAlpha 会把 0x26 这类半透明令牌变成实心） */
    private static int fade(int color, float f) {
        int a = Math.round(((color >>> 24) & 0xFF) * Math.max(0f, Math.min(1f, f)));
        return (a << 24) | (color & 0x00FFFFFF);
    }

    /** 浮起柔影：三层外扩描边，比 setShadowLayer 便宜且硬件加速下可控 */
    private void drawHalo(Canvas canvas, float r, float t) {
        int base = Math.round(44 * Math.min(1f, t));
        strokePaint.setStyle(Paint.Style.STROKE);
        for (int i = 0; i < 3; i++) {
            strokePaint.setColor(fade(Wds.LENS_SHADOW, (base - i * 13) / 44f));
            strokePaint.setStrokeWidth(w.dpf(3f + i * 3.2f));
            float g = w.dpf(1.4f + i * 2f);
            tmpRect.set(shape);
            tmpRect.inset(-g, -g);
            canvas.drawRoundRect(tmpRect, r + g, r + g, strokePaint);
        }
    }

    // ============================================================
    // 模糊
    // ============================================================

    /** 只在 worker 线程调用；失败返回 null 并永久停用 RenderScript */
    private Bitmap blur(Bitmap src) {
        if (rsDead || src == null || src.isRecycled()) return null;
        Allocation in = null;
        Allocation out = null;
        ScriptIntrinsicBlur blur = null;
        Bitmap dst = null;
        try {
            if (rs == null) rs = RenderScript.create(app);
            dst = Bitmap.createBitmap(src.getWidth(), src.getHeight(), Bitmap.Config.ARGB_8888);
            in = Allocation.createFromBitmap(rs, src);
            out = Allocation.createTyped(rs, in.getType());
            blur = ScriptIntrinsicBlur.create(rs, Element.U8_4(rs));
            blur.setRadius(BLUR_R);
            blur.setInput(in);
            blur.forEach(out);
            out.copyTo(dst);
            return dst;
        } catch (Throwable e) {
            rsDead = true;
            if (dst != null) dst.recycle();
            return null;
        } finally {
            if (blur != null) {
                try {
                    blur.destroy();
                } catch (Throwable ignore) {
                }
            }
            if (in != null) {
                try {
                    in.destroy();
                } catch (Throwable ignore) {
                }
            }
            if (out != null) {
                try {
                    out.destroy();
                } catch (Throwable ignore) {
                }
            }
        }
    }
}
