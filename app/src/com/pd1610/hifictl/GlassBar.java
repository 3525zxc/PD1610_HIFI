package com.pd1610.hifictl;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 液态玻璃底栏（glass 分支）。
 *
 * 结构：整条栏是一个自绘的横向 LinearLayout —— 背景五层全部画在 {@link #onDraw(Canvas)} 里，
 * 只放 4 个 tab 作为子 View（图标 24 + 文字 11sp），不叠任何背景子 View，触摸与层级都简单。
 *
 * 五层（自下而上）：
 * 1. 模糊快照：{@link Backdrop} 算好的内容条带位图，用 BitmapShader 按圆角铺满整条栏；
 * 2. 着色：#0E1A22 @ 42%；
 * 3. 顶部高光：垂直渐变 #FFFFFF1F → 透明，覆盖上 45%；
 * 4. 内描边 1dp：上缘 #FFFFFF47、其余 #FFFFFF1F；
 * 5. 外投影：elevation 10dp + 圆角 outline（不裁剪子 View，只用来生成阴影）。
 *
 * 模糊没算出来（RenderScript 不可用 / 还在算）时第 1 层不画，退化成半透明玻璃，功能照常。
 */
final class GlassBar extends LinearLayout {

    interface OnTabSelected {
        void onTabSelected(int index);
    }

    /** 点击水波纹（比 Wds.RIPPLE 稍亮一档，玻璃底上才有反馈感） */
    private static final int RIPPLE = 0x26FFFFFF;
    /** 药丸过渡时长 */
    private static final int ANIM_MS = 180;
    /** 未选中时药丸的缩比 */
    private static final float PILL_SCALE_IDLE = 0.8f;

    /** 切页后触发一次快照的延迟：等 160ms 的页面淡入结束 */
    private static final int PAGE_SETTLE_MS = 180;
    /** 栏圆角 28dp */
    private static final float R_BAR = 28f;
    /** 药丸 64×32dp */
    private static final int PILL_W = 64;
    private static final int PILL_H = 32;
    /** 图标 24dp、图标与文字间距 3dp */
    private static final int ICON_SIZE = 24;
    private static final int LABEL_GAP = 3;
    private static final float LABEL_SP = 11f;
    /** 长按唤起放大镜的判定时长 */
    private static final int LONG_PRESS_MS = 320;

    private final Wds w;
    private final float radius;
    private final float strokeW;

    private final LinearLayout[] items;
    private final FrameLayout[] slots;
    private final FrameLayout[] pills;
    private final Icons.Icon[] icons;
    private final TextView[] labels;
    private final int[] tabIcons;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shaderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final RectF edgeRect = new RectF();
    private final Rect region = new Rect();
    private final Matrix shaderMatrix = new Matrix();
    /** 换算坐标用的复用数组，避免滚动时每帧分配 */
    private final int[] locMine = new int[2];
    private final int[] locTheirs = new int[2];

    private final Backdrop backdrop;

    /** 模糊快照与它的 shader（尺寸变化时重建） */
    private Bitmap blur;
    private BitmapShader shader;
    private LinearGradient highlight;

    private int shaderW;
    private int shaderH;
    private int selected;
    private OnTabSelected listener;
    /** 待模糊的内容视图（与栏同级、铺满窗口的那一层） */
    private View contentView;

    // ---------------- 长按放大镜 ----------------

    /** 浮起的圆镜，由 MainActivity 注入（与栏同级、盖在栏上） */
    private GlassLens lens;
    private final int touchSlop;
    /** 手指按下的那一项（-1 = 按在空白处） */
    private int downIndex = -1;
    private float downX;
    private float downY;
    /** 长按计时器还挂着 */
    private boolean armed;
    /** 圆镜已接管手势 */
    private boolean lensActive;
    /** 圆镜当前悬停的项 */
    private int lensIndex = -1;
    /** 被圆镜接管的项：它的药丸与图标让位给镜子，避免同一位置画两遍 */
    private int hiddenPill = -1;
    /** 手势开始时选中的项，用于 CANCEL 回滚 */
    private int selectedBefore;

    private final Runnable longPress = new Runnable() {
        @Override
        public void run() {
            if (!armed) return;
            armed = false;
            if (downIndex < 0 || lens == null) return;
            startLens(downIndex);
        }
    };

    GlassBar(Context c, Wds w, int[] iconIds, String[] names) {
        super(c);
        this.w = w;
        this.radius = w.dpf(R_BAR);
        this.strokeW = Math.max(1f, w.dpf(1f));
        this.backdrop = new Backdrop(c);
        this.touchSlop = ViewConfiguration.get(c).getScaledTouchSlop();

        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        // ViewGroup 默认 willNotDraw=true，自绘背景必须显式打开
        setWillNotDraw(false);
        setClipToOutline(false);
        // 触控由栏自己处理：长按后要连续跟踪手指，交给子项 click 拿不到 MOVE
        setClickable(true);

        int n = names.length;
        items = new LinearLayout[n];
        slots = new FrameLayout[n];
        pills = new FrameLayout[n];
        icons = new Icons.Icon[n];
        labels = new TextView[n];
        tabIcons = iconIds;

        for (int i = 0; i < n; i++) {
            LinearLayout item = new LinearLayout(c);
            item.setOrientation(VERTICAL);
            item.setGravity(Gravity.CENTER);

            // 药丸只做背景层，图标是它的兄弟节点：未选中 pill.setAlpha(0) 时不能把图标一起吃掉
            FrameLayout slot = new FrameLayout(c);
            FrameLayout pill = new FrameLayout(c);
            pill.setBackground(w.stroke(Wds.GLASS_PILL, Wds.GLASS_PILL_EDGE, Wds.R_PILL));
            pill.setAlpha(0f);
            pill.setScaleX(PILL_SCALE_IDLE);
            pill.setScaleY(PILL_SCALE_IDLE);
            slot.addView(pill, new FrameLayout.LayoutParams(-1, -1));

            Icons.Icon icon = new Icons.Icon(c);
            icon.set(iconIds[i], Wds.GLASS_IDLE);
            slot.addView(icon, new FrameLayout.LayoutParams(w.dp(ICON_SIZE), w.dp(ICON_SIZE),
                    Gravity.CENTER));
            item.addView(slot, new LinearLayout.LayoutParams(w.dp(PILL_W), w.dp(PILL_H)));

            TextView label = w.text(names[i], LABEL_SP, Wds.GLASS_IDLE, Wds.REG);
            label.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(-1, -2);
            labelLp.topMargin = w.dp(LABEL_GAP);
            item.addView(label, labelLp);

            // 整项热区：宽 = 栏宽/4，高 = 栏高 64dp，远大于 48dp。
            // 不设 clickable —— 触摸统一走 GlassBar.onTouchEvent，波纹靠手动 setPressed 驱动
            item.setBackground(w.ripple(RIPPLE, Wds.R_PILL));
            item.setContentDescription(names[i]);
            addView(item, new LinearLayout.LayoutParams(0, -1, 1f));

            items[i] = item;
            slots[i] = slot;
            pills[i] = pill;
            icons[i] = icon;
            labels[i] = label;
        }

        // 投影：圆角 outline 由自己提供，elevation 由 MainActivity 设
        setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline outline) {
                int wd = v.getWidth();
                int ht = v.getHeight();
                if (wd <= 0 || ht <= 0) {
                    outline.setEmpty();
                    return;
                }
                outline.setRoundRect(0, 0, wd, ht, Math.min(radius, Math.min(wd, ht) / 2f));
            }
        });

        backdrop.setCallback(new Backdrop.Callback() {
            @Override
            public void onBlurred(Bitmap bmp) {
                setBlur(bmp);
            }
        });

        applySelection(false);
    }

    // ============================================================
    // 对外接口
    // ============================================================

    void setOnTabSelected(OnTabSelected l) {
        listener = l;
    }

    int getSelected() {
        return selected;
    }

    /** 绑定被折射的内容层，之后由本栏自己截取、自己模糊 */
    void attachContent(View content) {
        contentView = content;
        backdrop.setSource(content);
        syncRegion();
    }

    /** 注入长按浮起的圆镜（与栏同级、盖在栏上） */
    void attachLens(GlassLens l) {
        lens = l;
    }

    /** 切换选中项：药丸 alpha/scale 180ms 过渡，图标文字直接换色 */
    void setSelected(int index, boolean animate) {
        if (index < 0 || index >= pills.length) return;
        selected = index;
        applySelection(animate);
    }

    /** 切页后调用：等淡入动画结束再补一张快照 */
    void onPageChanged() {
        request(PAGE_SETTLE_MS);
    }

    /** 内容滚动中/滚动后调用，内部做 120ms 防抖 */
    void onContentScrolled() {
        request(Backdrop.DEBOUNCE_MS);
    }

    /** Activity onDestroy 调用 */
    void release() {
        backdrop.release();
        blur = null;
        shader = null;
        shaderW = 0;
        shaderH = 0;
    }

    // ============================================================
    // 布局：栏的矩形换算到内容层坐标系，交给 Backdrop
    // ============================================================

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        // 首次布局 / 旋转 / 任何尺寸位置变化后，栏的矩形才成立，此时才能取第一张快照
        if (changed) request(Backdrop.DEBOUNCE_MS);
    }

    @Override
    protected void onSizeChanged(int wNew, int hNew, int wOld, int hOld) {
        super.onSizeChanged(wNew, hNew, wOld, hOld);
        highlight = null;
        shader = null;
        shaderW = 0;
        shaderH = 0;
        invalidateOutline();
    }

    private void syncRegion() {
        View content = contentView;
        if (content == null || getWidth() <= 0 || getHeight() <= 0) return;
        getLocationInWindow(locMine);
        content.getLocationInWindow(locTheirs);
        int dx = locMine[0] - locTheirs[0];
        int dy = locMine[1] - locTheirs[1];
        if (region.left == dx && region.top == dy
                && region.width() == getWidth() && region.height() == getHeight()) {
            return;
        }
        region.set(dx, dy, dx + getWidth(), dy + getHeight());
        backdrop.setRegion(region);
    }

    private void request(int delayMs) {
        syncRegion();
        backdrop.request(delayMs);
    }

    private void setBlur(Bitmap bmp) {
        if (bmp == null || bmp.isRecycled()) return;
        blur = bmp;
        shader = null;
        shaderW = 0;
        shaderH = 0;
        invalidate();
    }

    // ============================================================
    // 五层背景
    // ============================================================

    @Override
    protected void onDraw(Canvas canvas) {
        int wd = getWidth();
        int ht = getHeight();
        if (wd <= 0 || ht <= 0) return;

        rect.set(0f, 0f, wd, ht);
        float r = Math.min(radius, Math.min(wd, ht) / 2f);

        drawBlurLayer(canvas, r);

        paint.setStyle(Paint.Style.FILL);
        paint.setShader(null);

        // 第 2 层：42% 着色
        paint.setColor(Wds.GLASS_TINT);
        canvas.drawRoundRect(rect, r, r, paint);

        // 第 3 层：顶部高光（上 45%）
        if (highlight == null) {
            float hiH = Math.max(1f, ht * 0.45f);
            highlight = new LinearGradient(0f, 0f, 0f, hiH,
                    Wds.GLASS_HI_TOP, 0x00FFFFFF, Shader.TileMode.CLAMP);
        }
        paint.setShader(highlight);
        canvas.drawRoundRect(rect, r, r, paint);
        paint.setShader(null);

        // 第 4 层：内描边 1dp（描边画在边框内侧，所以整体内缩半个线宽）
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(strokeW);
        edgeRect.set(rect);
        edgeRect.inset(strokeW / 2f, strokeW / 2f);

        paint.setColor(Wds.GLASS_EDGE);
        canvas.drawRoundRect(edgeRect, r, r, paint);

        // 上缘单独提亮：只描到圆角弧线结束的位置，正好是「玻璃上沿受光」的一条边
        int save = canvas.save();
        canvas.clipRect(0f, 0f, wd, Math.min(ht * 0.5f, r));
        paint.setColor(Wds.GLASS_EDGE_TOP);
        canvas.drawRoundRect(edgeRect, r, r, paint);
        canvas.restoreToCount(save);

        paint.setStyle(Paint.Style.FILL);
    }

    /** 第 1 层：模糊快照，用 shader 铺满圆角矩形（圆角自然裁掉四角） */
    private void drawBlurLayer(Canvas canvas, float r) {
        Bitmap bmp = blur;
        if (bmp == null || bmp.isRecycled()) return;
        if (shader == null || shaderW != bmp.getWidth() || shaderH != bmp.getHeight()) {
            try {
                shader = new BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            } catch (Throwable t) {
                shader = null;
                return;
            }
            shaderW = bmp.getWidth();
            shaderH = bmp.getHeight();
            shaderMatrix.setScale((float) getWidth() / shaderW, (float) getHeight() / shaderH);
            shader.setLocalMatrix(shaderMatrix);
        }
        shaderPaint.setShader(shader);
        canvas.drawRoundRect(rect, r, r, shaderPaint);
        shaderPaint.setShader(null);
    }

    // ============================================================
    // 选中态
    // ============================================================

    private void applySelection(boolean animate) {
        for (int i = 0; i < pills.length; i++) {
            boolean sel = i == selected;
            // 被圆镜接管的项：药丸和图标都让位给镜子，否则同一位置会画两遍
            boolean hidden = i == hiddenPill;
            FrameLayout pill = pills[i];
            pill.animate().cancel();
            if (hidden) {
                pill.setAlpha(0f);
                pill.setScaleX(PILL_SCALE_IDLE);
                pill.setScaleY(PILL_SCALE_IDLE);
            } else if (animate) {
                pill.animate()
                        .alpha(sel ? 1f : 0f)
                        .scaleX(sel ? 1f : PILL_SCALE_IDLE)
                        .scaleY(sel ? 1f : PILL_SCALE_IDLE)
                        .setDuration(ANIM_MS)
                        .start();
            } else {
                pill.setAlpha(sel ? 1f : 0f);
                pill.setScaleX(sel ? 1f : PILL_SCALE_IDLE);
                pill.setScaleY(sel ? 1f : PILL_SCALE_IDLE);
            }
            int color = sel ? Wds.GLASS_ACCENT : Wds.GLASS_IDLE;
            icons[i].setColor(color);
            // 图标与文字都让位：镜子正罩在这一项上，两套东西会打架
            icons[i].setAlpha(hidden ? 0f : 1f);
            labels[i].setTextColor(color);
            labels[i].setAlpha(hidden ? 0f : 1f);
        }
    }

    // ============================================================
    // 触摸：点按 = 切页；长按 = 浮起圆镜，拖动跟手，松手落位
    // ============================================================

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                arm(e.getX(), e.getY());
                return true;

            case MotionEvent.ACTION_MOVE:
                if (lensActive) {
                    moveLens(e.getX());
                } else if (armed) {
                    float dx = e.getX() - downX;
                    float dy = e.getY() - downY;
                    // 还没到长按时就滑开了：按「不是长按」处理，交给系统正常滚动
                    if (dx * dx + dy * dy > (float) touchSlop * touchSlop) disarm();
                }
                return true;

            case MotionEvent.ACTION_UP:
                if (lensActive) {
                    finishLens(e.getX());
                } else if (armed) {
                    disarm();
                    int idx = indexAt(e.getX());
                    if (idx >= 0 && idx == downIndex) {
                        Haptics.tap(this);
                        if (idx != selected) {
                            setSelected(idx, true);
                            if (listener != null) listener.onTabSelected(idx);
                        }
                    }
                }
                return true;

            case MotionEvent.ACTION_CANCEL:
                if (lensActive) cancelLens();
                else disarm();
                return true;

            default:
                return true;
        }
    }

    private void arm(float x, float y) {
        downX = x;
        downY = y;
        downIndex = indexAt(x);
        selectedBefore = selected;
        armed = downIndex >= 0;
        if (!armed) return;
        items[downIndex].setPressed(true);
        // 波纹从手指位置扩散，而不是默认的控件中心
        Drawable bg = items[downIndex].getBackground();
        if (bg instanceof RippleDrawable) {
            bg.setHotspot(x - items[downIndex].getLeft(), y - items[downIndex].getTop());
        }
        // 长按要 320ms 才触发，这段时间足够把玻璃备好
        if (lens != null) lens.prepare();
        postDelayed(longPress, LONG_PRESS_MS);
    }

    private void disarm() {
        armed = false;
        removeCallbacks(longPress);
        clearPressed();
    }

    private void clearPressed() {
        for (LinearLayout item : items) {
            if (item != null) item.setPressed(false);
        }
    }

    private void startLens(int idx) {
        if (lens == null || lensActive) return;
        clearPressed();
        lensActive = true;
        lensIndex = idx;
        hiddenPill = idx;
        setSelected(idx, true);
        Haptics.buzz(getContext());
        lens.show(tabIcons[idx], centerXOf(idx), pillCenterY());
    }

    private void moveLens(float x) {
        int idx = indexAt(x);
        if (idx >= 0 && idx != lensIndex) {
            lensIndex = idx;
            hiddenPill = idx;
            Haptics.tap(this);
            setSelected(idx, true);
        }
        if (lens != null) lens.moveTo(clampX(x));
    }

    private void finishLens(float x) {
        int hit = indexAt(x);
        final int target = hit < 0 ? Math.max(0, lensIndex) : hit;
        lensActive = false;
        moveTo(target, () -> {
            if (listener != null) listener.onTabSelected(target);
        });
    }

    private void cancelLens() {
        lensActive = false;
        moveTo(selectedBefore < 0 ? 0 : selectedBefore, null);
    }

    /** 圆镜缩回目标项：缩完那一刻它已经长成药丸，这里再交还给药丸，切换零跳变 */
    private void moveTo(int idx, final Runnable after) {
        if (lens == null) {
            restoreAfterLens(idx);
            if (after != null) after.run();
            return;
        }
        lens.release(centerXOf(idx), () -> {
            restoreAfterLens(idx);
            if (after != null) after.run();
        });
    }

    private void restoreAfterLens(int idx) {
        hiddenPill = -1;
        lensIndex = -1;
        setSelected(idx, false);
    }

    /** 栏内坐标：第 i 项的中心 x（栏与圆镜同属一个父容器，坐标系一致） */
    private float centerXOf(int i) {
        float itemW = getWidth() / (float) Math.max(1, pills.length);
        return getLeft() + itemW * (i + 0.5f);
    }

    /** 药丸中线在父容器里的 y（bar 顶 + item 顶 + slot 顶 + 半高，逐层累加） */
    private float pillCenterY() {
        if (items[0] == null || slots[0] == null) return getTop();
        return getTop() + items[0].getTop() + slots[0].getTop() + slots[0].getHeight() / 2f;
    }

    /** 圆镜的横向范围：与库的行为一致，夹在首尾 tab 中心之间 */
    float clampX(float x) {
        if (pills.length == 0) return x;
        float lo = centerXOf(0);
        float hi = centerXOf(pills.length - 1);
        return x < lo ? lo : (x > hi ? hi : x);
    }

    /** 手指落在第几项（越界返回 -1） */
    private int indexAt(float x) {
        int wd = getWidth();
        if (wd <= 0) return -1;
        int n = pills.length;
        float rel = x - getPaddingLeft();
        if (rel < 0 || rel >= wd) return -1;
        int idx = (int) (rel / (wd / (float) n));
        return idx < 0 ? 0 : (idx >= n ? n - 1 : idx);
    }
}
