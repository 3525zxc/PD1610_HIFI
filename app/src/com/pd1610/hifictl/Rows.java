package com.pd1610.hifictl;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * WhatsApp 形态的两种结构：列表行（3.3）与折叠组（4.2 的 FAQ / 详细参数都用它）。
 */
final class Rows {

    private Rows() {
    }

    // ============================================================
    // 3.3 ListRow
    // ============================================================

    /**
     * 列表行：左 40dp 圆（或 8dp 色点）+ 标题 16sp / 副标 14sp + 右侧 chevron 或数值。
     * 行高 56dp（无副标）/ 72dp（有副标）；整行通栏 ripple。
     * 带 detail() 的行点击展开详情（chevron 旋转 90°）。
     */
    static final class ListRow extends LinearLayout {

        private final Wds w;
        private final LinearLayout row;
        private final FrameLayout lead;
        private final LinearLayout textCol;
        private final LinearLayout rightSlot;
        private TextView subtitleView;
        private View dotView;
        private TextView detailView;
        private Icons.Icon chevronView;
        private boolean expanded = false;

        ListRow(Context c, Wds w) {
            super(c);
            this.w = w;
            setOrientation(VERTICAL);

            row = new LinearLayout(c);
            row.setOrientation(HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMinimumHeight(w.dp(Wds.ROW_SHORT));
            int ph = w.dp(Wds.PAGE_PAD);
            int pv = w.dp(8);
            row.setPadding(ph, pv, ph, pv);

            lead = new FrameLayout(c);
            LinearLayout.LayoutParams leadLp = new LinearLayout.LayoutParams(
                    w.dp(Wds.ICON_CIRCLE), w.dp(Wds.ICON_CIRCLE));
            leadLp.rightMargin = w.dp(16);
            row.addView(lead, leadLp);

            textCol = new LinearLayout(c);
            textCol.setOrientation(VERTICAL);
            row.addView(textCol, new LinearLayout.LayoutParams(0, -2, 1f));

            rightSlot = new LinearLayout(c);
            rightSlot.setOrientation(HORIZONTAL);
            rightSlot.setGravity(Gravity.CENTER_VERTICAL);
            row.addView(rightSlot, new LinearLayout.LayoutParams(-2, -2));

            addView(row, new LinearLayout.LayoutParams(-1, -2));
        }

        ListRow title(String text) {
            TextView t = w.list(text);
            t.setSingleLine(true);
            t.setEllipsize(TextUtils.TruncateAt.END);
            textCol.addView(t, new LinearLayout.LayoutParams(-1, -2));
            return this;
        }

        ListRow subtitle(String text) {
            TextView t = w.body(text);
            t.setSingleLine(true);
            t.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = w.dp(2);
            textCol.addView(t, lp);
            subtitleView = t;
            row.setMinimumHeight(w.dp(Wds.ROW_TALL));
            return this;
        }

        /** 8dp 语义色点（无图标时用） */
        ListRow dot(int color) {
            View d = new View(getContext());
            d.setBackground(w.circle(color));
            lead.addView(d, new FrameLayout.LayoutParams(w.dp(8), w.dp(8), Gravity.CENTER));
            dotView = d;
            return this;
        }

        ListRow chevron() {
            chevronView = new Icons.Icon(getContext());
            chevronView.set(Icons.CHEVRON, Wds.TEXT_3);
            rightSlot.addView(chevronView, new LinearLayout.LayoutParams(w.dp(16), w.dp(16)));
            return this;
        }

        /** 可展开详情：13sp TEXT_2，缩进 56dp；点击行展开/收起 */
        ListRow detail(String text) {
            detailView = w.text(text, Wds.SP_SECTION, Wds.TEXT_2, Wds.REG);
            detailView.setLineSpacing(0f, Wds.LH_BODY);
            detailView.setVisibility(GONE);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.leftMargin = w.dp(56);
            lp.rightMargin = w.dp(Wds.PAGE_PAD);
            lp.bottomMargin = w.dp(12);
            addView(detailView, lp);

            w.ink(row, 0f);
            row.setOnClickListener(v -> {
                Haptics.tap(v);
                expanded = !expanded;
                detailView.setVisibility(expanded ? VISIBLE : GONE);
                if (chevronView != null) {
                    chevronView.animate().rotation(expanded ? 90f : 0f).setDuration(120).start();
                }
            });
            return this;
        }

        /** 静默刷新：只改左侧色点颜色（状态页刷新 HAL 行时用） */
        void setDotColor(int color) {
            if (dotView != null) dotView.setBackground(w.circle(color));
        }

        /** 静默刷新：只改副标题文字 */
        void setCaption(String text) {
            if (subtitleView != null) subtitleView.setText(text);
        }

        /** 详情是否展开（重测前保存用） */
        boolean isExpanded() {
            return expanded;
        }

        /** 重建后静默恢复展开态：不播动画、不依赖测量宽度 */
        void restoreExpanded(boolean value) {
            expanded = value;
            if (detailView != null) detailView.setVisibility(value ? VISIBLE : GONE);
            if (chevronView != null) chevronView.setRotation(value ? 90f : 0f);
        }

    }

    // ============================================================
    // 折叠组
    // ============================================================

    /** 头部 56dp 可点，内容高度 0→measured 200ms，alpha 同步淡入 */
    static final class Collapsible extends LinearLayout {

        private static final int ANIM_MS = 200;

        private final Wds w;
        private final Icons.Icon chevron;
        private final FrameLayout clip;
        private final LinearLayout body;
        private boolean expanded = false;
        private ValueAnimator anim;

        Collapsible(Context c, Wds w, String titleText) {
            super(c);
            this.w = w;
            setOrientation(VERTICAL);

            LinearLayout header = new LinearLayout(c);
            header.setOrientation(HORIZONTAL);
            header.setGravity(Gravity.CENTER_VERTICAL);
            int ph = w.dp(Wds.PAGE_PAD);
            header.setPadding(ph, w.dp(16), ph, w.dp(16));
            header.setMinimumHeight(w.dp(Wds.ROW_SHORT));

            TextView title = w.list(titleText);
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.END);
            header.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));

            chevron = new Icons.Icon(c);
            chevron.set(Icons.CHEVRON, Wds.TEXT_3);
            header.addView(chevron, new LinearLayout.LayoutParams(w.dp(16), w.dp(16)));

            w.ink(header, 0f);
            header.setOnClickListener(v -> {
                Haptics.tap(v);
                setExpanded(!expanded, true);
            });
            addView(header, new LinearLayout.LayoutParams(-1, -2));

            clip = new FrameLayout(c);
            body = new LinearLayout(c);
            body.setOrientation(VERTICAL);
            int bodyPad = w.dp(Wds.PAGE_PAD);
            body.setPadding(bodyPad, 0, bodyPad, w.dp(Wds.PAGE_PAD));
            clip.addView(body, new FrameLayout.LayoutParams(-1, -2));
            clip.setAlpha(0f);
            addView(clip, new LinearLayout.LayoutParams(-1, 0));
        }

        LinearLayout body() {
            return body;
        }

        boolean isExpanded() {
            return expanded;
        }

        void setExpanded(boolean expand, boolean animate) {
            if (expanded == expand) return;
            expanded = expand;
            if (animate) {
                chevron.animate().rotation(expand ? 90f : 0f).setDuration(120).start();
            } else {
                chevron.setRotation(expand ? 90f : 0f); // 静默恢复：直接落位，避免「正文瞬现、chevron 还在转」
            }
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) clip.getLayoutParams();
            int width = clip.getWidth();
            if (anim != null) {
                anim.cancel();
                anim = null;
            }
            if (width <= 0 || !animate) {
                lp.height = expand ? LinearLayout.LayoutParams.WRAP_CONTENT : 0;
                clip.setLayoutParams(lp);
                clip.setAlpha(expand ? 1f : 0f);
                return;
            }
            body.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            int target = body.getMeasuredHeight();
            int from = expand ? 0 : (lp.height < 0 ? target : lp.height);
            anim = ValueAnimator.ofInt(from, expand ? target : 0);
            anim.setDuration(ANIM_MS);
            anim.setInterpolator(new DecelerateInterpolator());
            anim.addUpdateListener(a -> {
                lp.height = (Integer) a.getAnimatedValue();
                clip.setLayoutParams(lp);
            });
            anim.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator a) {
                    lp.height = expanded ? LinearLayout.LayoutParams.WRAP_CONTENT : 0;
                    clip.setLayoutParams(lp);
                    anim = null;
                }
            });
            clip.animate().alpha(expand ? 1f : 0f).setDuration(ANIM_MS).start();
            anim.start();
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
}
