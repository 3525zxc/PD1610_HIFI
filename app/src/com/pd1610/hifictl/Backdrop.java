package com.pd1610.hifictl;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.renderscript.Allocation;
import android.renderscript.Element;
import android.renderscript.RenderScript;
import android.renderscript.ScriptIntrinsicBlur;
import android.view.View;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 液态玻璃的「真模糊」来源。
 *
 * API 28 没有 RenderEffect（那是 API 31+），所以用 RenderScript 的
 * {@link ScriptIntrinsicBlur} 自己补一遍高斯模糊。
 *
 * 性能策略（Xplay6 是 2016 年的机器，模糊必须省着花）：
 * - 只截「底栏所占的那一条矩形区域」，不截整屏；
 * - 快照先缩 6 倍（672×128 → 112×21）再模糊，成本降到 1/36，放大后视觉几乎无差；
 * - 同一时刻只允许一个任务在跑，跑的过程中来的请求只置 pending 标记，完成后补一次；
 * - 取快照用 {@code source.draw(canvas)} 画进小位图，不用已废弃的 getDrawingCache()。
 *
 * 所有失败路径（RenderScript 初始化 / Allocation / 模糊 / 取快照）都吞掉异常并返回 null，
 * 由 {@link GlassBar} 降级成纯半透明玻璃（2~5 层），绝不崩。
 *
 * 线程：取快照必须在主线程（View.draw 只能在 UI 线程），模糊在单线程 worker 上串行，
 * 结果回主线程交给 Callback。RenderScript 实例缓存复用，且只在创建它的 worker 线程上使用。
 */
final class Backdrop {

    /** 模糊结果回调（主线程） */
    interface Callback {
        void onBlurred(Bitmap bmp);
    }

    /** 滚动停止后的防抖延迟 */
    static final int DEBOUNCE_MS = 120;

    /** 快照缩小倍数 */
    private static final int SCALE = 6;
    /** 模糊半径（在缩小后的位图上算） */
    private static final float RADIUS = 10f;
    /** 被模糊的区域最多允许这么多像素（异常保护，防超大屏炸内存） */
    private static final int MAX_SIDE = 4096;

    private final Context ctx;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    /** 待截取的区域，坐标与 {@link #source} 一致 */
    private final Rect region = new Rect();
    /** 截取时的临时裁剪结果，避免每次分配 */
    private final Rect clip = new Rect();

    private View source;
    private Callback callback;

    /** 缓存的 RenderScript，只允许在 worker 线程上用 */
    private RenderScript rs;
    /** RenderScript 初始化失败一次就不再重试，避免反复付失败代价 */
    private boolean rsUnavailable;

    /** 是否有一个任务正在跑（含取快照 + 模糊） */
    private boolean busy;
    /** 跑的过程中又来请求了，完成后补一次 */
    private boolean pending;
    private boolean released;

    /** 防抖后的实际动作 */
    private final Runnable fire = new Runnable() {
        @Override
        public void run() {
            capture();
        }
    };

    Backdrop(Context c) {
        ctx = c.getApplicationContext();
    }

    // ============================================================
    // 对外接口
    // ============================================================

    void setSource(View v) {
        source = v;
    }

    void setCallback(Callback cb) {
        callback = cb;
    }

    /** 设置要模糊的区域（与 source 同一坐标系）；区域变化后调用方自己 request */
    void setRegion(Rect r) {
        region.set(r);
    }

    /**
     * 请求一次刷新：delayMs 毫秒后再动手，期间重复调用只保留最后一次（removeCallbacks + postDelayed）。
     * 若有任务正在跑，只置 pending，等它结束后补一次。
     */
    void request(int delayMs) {
        if (released) return;
        // 区域还没成立（首帧前 / 布局未结算），这次请求没有意义，等下一次 layout 再来
        if (region.width() <= 0 || region.height() <= 0) return;
        if (busy) {
            pending = true;
            return;
        }
        ui.removeCallbacks(fire);
        ui.postDelayed(fire, Math.max(0, delayMs));
    }

    /** 页面销毁时释放：停掉所有待执行动作，销毁 RenderScript 与 worker */
    void release() {
        if (released) return;
        released = true;
        pending = false;
        ui.removeCallbacks(fire);
        try {
            worker.execute(new Runnable() {
                @Override
                public void run() {
                    destroyRs();
                }
            });
        } catch (Throwable ignored) {
            // worker 已经关掉之类的情况，忽略
        }
        try {
            worker.shutdown();
        } catch (Throwable ignored) {
            // 同上
        }
    }

    // ============================================================
    // 快照（主线程）
    // ============================================================

    private void capture() {
        if (released) return;
        if (busy) {
            pending = true;
            return;
        }
        busy = true;

        Bitmap raw = null;
        try {
            raw = snapshot();
        } catch (Throwable t) {
            raw = null;
        }
        if (raw == null) {
            finish();
            return;
        }

        final Bitmap src = raw;
        try {
            worker.execute(new Runnable() {
                @Override
                public void run() {
                    Bitmap out = null;
                    try {
                        out = blur(src);
                    } catch (Throwable t) {
                        out = null;
                    }
                    // 模糊完成后原快照就没用了，立即释放（它自己不再被别处引用，回收是安全的）
                    recycle(src);
                    final Bitmap result = out;
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            deliver(result);
                        }
                    });
                }
            });
        } catch (Throwable t) {
            // worker 已关闭：本次放弃，并让边框保持旧内容
            recycle(src);
            finish();
        }
    }

    /** 把 source 上 region 那一块缩小 1/SCALE 画进新位图；任何异常返回 null */
    private Bitmap snapshot() {
        View src = source;
        if (src == null || released) return null;
        int sw = src.getWidth();
        int sh = src.getHeight();
        if (sw <= 0 || sh <= 0) return null;
        if (region.width() <= 0 || region.height() <= 0) return null;

        // 区域限制在 source 之内，避免越界（栏贴边或布局还没结算完时会出现）
        clip.set(region);
        if (!clip.intersect(0, 0, sw, sh)) return null;
        if (clip.width() <= 0 || clip.height() <= 0) return null;

        int bw = Math.max(1, clip.width() / SCALE);
        int bh = Math.max(1, clip.height() / SCALE);
        if (bw > MAX_SIDE || bh > MAX_SIDE) return null;

        Bitmap small = null;
        try {
            small = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(small);
            canvas.scale(1f / SCALE, 1f / SCALE);
            canvas.translate(-clip.left, -clip.top);
            src.draw(canvas);
            return small;
        } catch (Throwable t) {
            recycle(small);
            return null;
        }
    }

    private void deliver(Bitmap result) {
        busy = false;
        if (released) {
            recycle(result);
            return;
        }
        if (result != null && callback != null) {
            try {
                callback.onBlurred(result);
            } catch (Throwable ignored) {
                // 回调里出的问题不许影响主流程
            }
        }
        if (pending) {
            pending = false;
            request(DEBOUNCE_MS);
        }
    }

    private void finish() {
        busy = false;
        if (released) return;
        if (pending) {
            pending = false;
            request(DEBOUNCE_MS);
        }
    }

    // ============================================================
    // 模糊（worker 线程）
    // ============================================================

    /** 高斯模糊；RenderScript 不可用或任一步失败返回 null */
    private Bitmap blur(Bitmap src) {
        RenderScript script = rs();
        if (script == null) return null;

        Allocation in = null;
        Allocation out = null;
        Bitmap dst = null;
        try {
            in = Allocation.createFromBitmap(script, src,
                    Allocation.MipmapControl.MIPMAP_NONE, Allocation.USAGE_SCRIPT);
            dst = Bitmap.createBitmap(src.getWidth(), src.getHeight(), Bitmap.Config.ARGB_8888);
            out = Allocation.createTyped(script, in.getType());

            ScriptIntrinsicBlur intrinsic =
                    ScriptIntrinsicBlur.create(script, Element.U8_4(script));
            intrinsic.setRadius(clamp(RADIUS, 1f, 25f));
            intrinsic.setInput(in);
            intrinsic.forEach(out);
            out.copyTo(dst);
            return dst;
        } catch (Throwable t) {
            recycle(dst);
            // RS 上下文可能已失效，丢掉缓存让下次重新创建
            destroyRs();
            return null;
        } finally {
            destroyQuietly(in);
            destroyQuietly(out);
        }
    }

    /** 懒创建并缓存 RenderScript；失败只标记一次，之后直接返回 null */
    private RenderScript rs() {
        if (rs != null) return rs;
        if (rsUnavailable) return null;
        try {
            rs = RenderScript.create(ctx);
        } catch (Throwable t) {
            rs = null;
        }
        if (rs == null) rsUnavailable = true;
        return rs;
    }

    private void destroyRs() {
        RenderScript old = rs;
        rs = null;
        if (old == null) return;
        try {
            old.destroy();
        } catch (Throwable ignored) {
            // 已是失效状态，忽略
        }
    }

    private static void destroyQuietly(Allocation a) {
        if (a == null) return;
        try {
            a.destroy();
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    private static void recycle(Bitmap b) {
        if (b == null) return;
        try {
            if (!b.isRecycled()) b.recycle();
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
