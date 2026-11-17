package com.pd1610.hifictl;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.PorterDuff;
import android.view.Gravity;
import android.view.View;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.view.animation.AnimationSet;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.TranslateAnimation;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

/**
 * v2.0 基础控件：分区标题、分段控件、卡内结果条、忙碌按钮。
 * （底部导航在 {@link BottomNav}，列表行与折叠组在 {@link Rows}。）
 */
final class Widgets {

    private Widgets() {
    }

    // ============================================================
    // 3.2 SectionHeader
    // ============================================================

    /** 13sp medium GREEN_DEEP，左右 16dp，上 20dp / 下 8dp，纯文字无背景 */
    static TextView section(Context c, Wds w, String text) {
        TextView t = w.sectionText(text);
        int px = w.dp(Wds.PAGE_PAD);
        t.setPadding(px, w.dp(20), px, w.dp(8));
        return t;
    }

    // ============================================================
    // 3.4 SegmentedControl（绿滑块版）
    // ============================================================

    /**
     * 槽 44dp（SURFACE_2 / 圆角 12）+ 内滑块 36dp（GREEN_DEEP 实底 / 圆角 9）。
     * 支持「无选中」（clearSelection：滑块 alpha 0），用于 HAL 读不回状态的场合。
     */
    static final class SegmentedControl extends FrameLayout {

        interface OnSegmentChange {
            void onSegmentChange(int index, boolean fromUser);
        }

        private static final int ANIM_MS = 200;

        private final Wds w;
        private final TextView[] labels;
        private final View thumb;
        private final int count;
        private int selected = 0;
        private float segW = 0f;
        private float thumbX = 0f;
        private ValueAnimator anim;
        private OnSegmentChange listener;

        SegmentedControl(Context c, Wds w, String[] items) {
            super(c);
            this.w = w;
            this.count = items.length;
            setBackground(w.solid(Wds.SURFACE_2, Wds.R_SEG_SLOT));

            thumb = new View(c);
            thumb.setBackground(w.solid(Wds.GREEN_DEEP, Wds.R_SEG_THUMB));
            addView(thumb, new FrameLayout.LayoutParams(0, 0));

            labels = new TextView[count];
            for (int i = 0; i < count; i++) {
                TextView t = w.text(items[i], Wds.SP_SEG, Wds.TEXT_2, Wds.REG);
                t.setGravity(Gravity.CENTER);
                t.setSingleLine(true);
                final int idx = i;
                t.setOnClickListener(v -> {
                    Haptics.tap(v);
                    if (idx == selected) return;
                    setSelectedIndex(idx, true);
                    if (listener != null) listener.onSegmentChange(idx, true);
                });
                w.ink(t, Wds.R_SEG_THUMB);
                addView(t, new FrameLayout.LayoutParams(0, 0));
                labels[i] = t;
            }
            updateTextColors();
        }

        void setOnSegmentChangeListener(OnSegmentChange l) {
            listener = l;
        }

        /** 无选中态：滑块隐藏，所有段回到未选中色（HAL 状态未知时用） */
        void clearSelection() {
            setSelectedIndex(-1, false);
        }

        /** 选中某段（-1 = 无选中）；animate=true 时滑块 200ms 缓出移动 */
        void setSelectedIndex(int index, boolean animate) {
            int idx = index < -1 ? -1 : (index >= count ? count - 1 : index);
            selected = idx;
            updateTextColors();
            thumb.setAlpha(idx < 0 ? 0f : 1f);
            float target = segW * Math.max(0, idx);
            if (anim != null) {
                anim.cancel();
                anim = null;
            }
            // 首次 layout 前 segW 还是 0，位置交给 onSizeChanged
            if (!animate || segW <= 0f || Math.abs(target - thumbX) < 0.5f) {
                thumbX = target;
                thumb.setTranslationX(thumbX);
                return;
            }
            anim = ValueAnimator.ofFloat(thumbX, target);
            anim.setDuration(ANIM_MS);
            anim.setInterpolator(new DecelerateInterpolator());
            anim.addUpdateListener(a -> {
                thumbX = (Float) a.getAnimatedValue();
                thumb.setTranslationX(thumbX);
            });
            anim.start();
        }

        private void updateTextColors() {
            for (int i = 0; i < count; i++) {
                boolean sel = i == selected;
                labels[i].setTextColor(sel ? Wds.ON_GREEN : Wds.TEXT_2);
                labels[i].setTypeface(sel ? Wds.MED : Wds.REG);
            }
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            int width = MeasureSpec.getSize(widthSpec);
            int height = w.dp(44);
            if (MeasureSpec.getMode(heightSpec) == MeasureSpec.EXACTLY) {
                height = MeasureSpec.getSize(heightSpec);
            } else if (MeasureSpec.getMode(heightSpec) == MeasureSpec.AT_MOST) {
                height = Math.min(height, MeasureSpec.getSize(heightSpec));
            }
            setMeasuredDimension(width, height);

            int inner = Math.max(0, width - getPaddingLeft() - getPaddingRight());
            int sw = count == 0 ? 0 : inner / count;
            int inset = w.dp(4);
            thumb.measure(MeasureSpec.makeMeasureSpec(Math.max(0, sw - inset * 2), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(Math.max(0, height - inset * 2), MeasureSpec.EXACTLY));
            int labelW = MeasureSpec.makeMeasureSpec(sw, MeasureSpec.EXACTLY);
            int labelH = MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY);
            for (TextView t : labels) {
                t.measure(labelW, labelH);
            }
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int width = r - l;
            int height = b - t;
            int inner = Math.max(0, width - getPaddingLeft() - getPaddingRight());
            int sw = count == 0 ? 0 : inner / count;
            int inset = w.dp(4);
            thumb.layout(getPaddingLeft() + inset, getPaddingTop() + inset,
                    getPaddingLeft() + inset + thumb.getMeasuredWidth(),
                    getPaddingTop() + inset + thumb.getMeasuredHeight());
            thumb.setTranslationX(thumbX);
            for (int i = 0; i < count; i++) {
                int x0 = getPaddingLeft() + i * sw;
                labels[i].layout(x0, getPaddingTop(), x0 + sw, getPaddingTop() + height);
            }
        }

        @Override
        protected void onSizeChanged(int width, int height, int oldw, int oldh) {
            super.onSizeChanged(width, height, oldw, oldh);
            int inner = Math.max(0, width - getPaddingLeft() - getPaddingRight());
            segW = count == 0 ? 0f : inner / (float) count;
            thumbX = segW * Math.max(0, selected);
            if (thumb != null) thumb.setTranslationX(thumbX);
        }

        @Override
        protected void onDetachedFromWindow() {
            if (anim != null) {
                anim.cancel();
                anim = null;
            }
            super.onDetachedFromWindow();
        }
    }

    // ============================================================
    // 3.5 InlineBanner
    // ============================================================

    /** 卡内结果条：结论 + 下一步，不自动消失（对账成功 3s 收起除外） */
    static final class InlineBanner extends LinearLayout {

        static final int INFO = 0;
        static final int OK = 1;
        static final int WARN = 2;
        static final int DANGER = 3;

        private final Wds w;
        private final TextView message;
        private final TextView action;
        private Runnable actionRunnable;
        private Runnable onDismissed;
        private final Runnable autoHide = this::animateOut;

        InlineBanner(Context c, Wds w, int type, String text) {
            super(c);
            this.w = w;
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            int px = w.dp(12);
            int py = w.dp(10);
            setPadding(px, py, w.dp(8), py);
            setMinimumHeight(w.dp(44));
            setBackground(w.strokeAlpha(wash(type), accent(type), 0x4D, Wds.R_BANNER));

            View bar = new View(c);
            bar.setBackground(w.solid(accent(type), 1.5f));
            LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(w.dp(3), w.dp(16));
            barLp.rightMargin = w.dp(10);
            addView(bar, barLp);

            message = w.text(text, Wds.SP_BODY, Wds.TEXT, Wds.REG);
            message.setLineSpacing(0f, Wds.LH_BODY);
            addView(message, new LinearLayout.LayoutParams(0, -2, 1f));

            action = w.text("", Wds.SP_BODY, accent(type), Wds.MED);
            action.setGravity(Gravity.CENTER);
            action.setPadding(w.dp(8), w.dp(8), w.dp(8), w.dp(8));
            action.setVisibility(GONE);
            action.setOnClickListener(v -> {
                Haptics.tap(v);
                if (actionRunnable != null) actionRunnable.run();
            });
            w.ink(action, 8f);
            addView(action, new LinearLayout.LayoutParams(-2, -2));

            TextView close = w.text("×", Wds.SP_LIST, Wds.TEXT_3, Wds.REG);
            close.setGravity(Gravity.CENTER);
            close.setOnClickListener(v -> {
                Haptics.tap(v);
                dismiss();
            });
            w.ink(close, 14f);
            LinearLayout.LayoutParams closeLp = new LinearLayout.LayoutParams(w.dp(28), w.dp(28));
            closeLp.leftMargin = w.dp(2);
            addView(close, closeLp);

            animateIn();
        }

        private static int accent(int type) {
            switch (type) {
                case OK:
                    return Wds.GREEN;
                case WARN:
                    return Wds.WARN;
                case DANGER:
                    return Wds.DANGER;
                default:
                    return Wds.GREEN_DEEP;
            }
        }

        private static int wash(int type) {
            switch (type) {
                case OK:
                    return Wds.WASH_OK;
                case WARN:
                    return Wds.WASH_WARN;
                case DANGER:
                    return Wds.WASH_DANGER;
                default:
                    return Wds.WASH_INFO;
            }
        }

        void setAction(String label, Runnable r) {
            action.setText(label);
            action.setVisibility(VISIBLE);
            actionRunnable = r;
        }

        void setOnDismissed(Runnable r) {
            onDismissed = r;
        }

        /** 唯一允许自动收起的场景：重新对账成功 */
        void dismissAfter(long ms) {
            removeCallbacks(autoHide);
            postDelayed(autoHide, ms);
        }

        void dismiss() {
            removeCallbacks(autoHide);
            animateOut();
        }

        private void animateIn() {
            AnimationSet set = new AnimationSet(true);
            set.addAnimation(new AlphaAnimation(0f, 1f));
            set.addAnimation(new TranslateAnimation(0f, 0f, w.dpf(6), 0f));
            set.setDuration(120);
            set.setInterpolator(new DecelerateInterpolator());
            startAnimation(set);
        }

        private void animateOut() {
            if (getVisibility() != VISIBLE) return;
            removeCallbacks(autoHide);
            AnimationSet set = new AnimationSet(true);
            set.addAnimation(new AlphaAnimation(1f, 0f));
            set.addAnimation(new TranslateAnimation(0f, 0f, 0f, w.dpf(-4)));
            set.setDuration(120);
            set.setInterpolator(new DecelerateInterpolator());
            set.setAnimationListener(new Animation.AnimationListener() {
                @Override
                public void onAnimationStart(Animation a) {
                    // 无需处理
                }

                @Override
                public void onAnimationEnd(Animation a) {
                    setVisibility(GONE);
                    if (onDismissed != null) onDismissed.run();
                }

                @Override
                public void onAnimationRepeat(Animation a) {
                    // 无需处理
                }
            });
            startAnimation(set);
        }

        @Override
        protected void onDetachedFromWindow() {
            removeCallbacks(autoHide);
            super.onDetachedFromWindow();
        }
    }

    // ============================================================
    // 3.8 BusyButton
    // ============================================================

    /** 高 48dp，SURFACE 底 + HAIRLINE 描边；busy 时 16dp 转圈 + 禁用 + alpha 0.6 */
    static final class BusyButton extends LinearLayout {

        private final TextView label;
        private final ProgressBar spinner;
        private final String idleText;
        private final String busyText;
        private boolean busy = false;

        BusyButton(Context c, Wds w, String idleText, String busyText) {
            super(c);
            this.idleText = idleText;
            this.busyText = busyText;
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER);
            setMinimumHeight(w.dp(Wds.TOUCH));
            int px = w.dp(16);
            int py = w.dp(12);
            setPadding(px, py, px, py);
            setBackground(w.stroke(Wds.SURFACE, Wds.HAIRLINE, Wds.R_BTN));
            w.ink(this, Wds.R_BTN);

            spinner = new ProgressBar(c, null, android.R.attr.progressBarStyleSmall);
            spinner.setIndeterminate(true);
            spinner.getIndeterminateDrawable().setColorFilter(Wds.GREEN_DEEP, PorterDuff.Mode.SRC_IN);
            spinner.setVisibility(GONE);
            LinearLayout.LayoutParams spinLp = new LinearLayout.LayoutParams(w.dp(16), w.dp(16));
            spinLp.rightMargin = w.dp(8);
            addView(spinner, spinLp);

            label = w.text(idleText, Wds.SP_BODY, Wds.TEXT, Wds.MED);
            label.setGravity(Gravity.CENTER);
            addView(label, new LinearLayout.LayoutParams(-2, -2));
        }

        void setDestructive(boolean destructive) {
            label.setTextColor(destructive ? Wds.DANGER : Wds.TEXT);
        }

        boolean isBusy() {
            return busy;
        }

        void setBusy(boolean value) {
            if (busy == value) return;
            busy = value;
            spinner.setVisibility(value ? VISIBLE : GONE);
            label.setText(value ? busyText : idleText);
            setEnabled(!value);
            setAlpha(value ? 0.6f : 1f);
        }
    }
}
