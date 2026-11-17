package com.pd1610.hifictl;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 3.1 底部导航：高 64dp，BG_BAR 底，4 项（图标 24 + 文字 11sp）。
 * 选中项是 64×32dp 的 GREEN_WASH 药丸 + GREEN_DEEP 图标与文字，切换 180ms 过渡。
 */
final class BottomNav extends LinearLayout {

    interface OnTabSelected {
        void onTabSelected(int index);
    }

    private final Wds w;
    private final FrameLayout[] pills;
    private final Icons.Icon[] icons;
    private final TextView[] labels;
    private int selected = 0;
    private OnTabSelected listener;

    BottomNav(Context c, Wds w, int[] iconIds, String[] names) {
        super(c);
        this.w = w;
        setOrientation(HORIZONTAL);
        setBackgroundColor(Wds.BG_BAR);
        setMinimumHeight(w.dp(Wds.NAV_H));

        int n = names.length;
        pills = new FrameLayout[n];
        icons = new Icons.Icon[n];
        labels = new TextView[n];

        for (int i = 0; i < n; i++) {
            final int idx = i;
            LinearLayout item = new LinearLayout(c);
            item.setOrientation(VERTICAL);
            item.setGravity(Gravity.CENTER);
            item.setMinimumWidth(w.dp(Wds.TOUCH));

            // 药丸只做背景层，图标必须是它的兄弟节点：
            // 若把图标挂在药丸里，未选中时 pill.setAlpha(0) 会把子 View 一起吃掉，图标就看不见了。
            FrameLayout slot = new FrameLayout(c);
            FrameLayout pill = new FrameLayout(c);
            pill.setBackground(w.pill(Wds.GREEN_WASH, Wds.R_PILL));
            pill.setAlpha(0f);
            slot.addView(pill, new FrameLayout.LayoutParams(-1, -1));
            Icons.Icon icon = new Icons.Icon(c);
            icon.set(iconIds[i], Wds.TEXT_2);
            slot.addView(icon, new FrameLayout.LayoutParams(w.dp(24), w.dp(24), Gravity.CENTER));
            item.addView(slot, new LinearLayout.LayoutParams(w.dp(64), w.dp(32)));

            TextView label = w.text(names[i], 11f, Wds.TEXT_2, Wds.REG);
            label.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(-1, -2);
            labelLp.topMargin = w.dp(4);
            item.addView(label, labelLp);

            w.ink(item, 0f);
            item.setOnClickListener(v -> {
                Haptics.tap(v);
                if (idx == selected) return;
                setSelected(idx, true);
                if (listener != null) listener.onTabSelected(idx);
            });
            addView(item, new LinearLayout.LayoutParams(0, -1, 1f));

            pills[i] = pill;
            icons[i] = icon;
            labels[i] = label;
        }
        applySelection(false);
    }

    void setOnTabSelected(OnTabSelected l) {
        listener = l;
    }

    int getSelected() {
        return selected;
    }

    /** 切换选中项：药丸 alpha/scale 与图标文字颜色 180ms 过渡 */
    void setSelected(int index, boolean animate) {
        if (index < 0 || index >= pills.length) return;
        selected = index;
        applySelection(animate);
    }

    private void applySelection(boolean animate) {
        for (int i = 0; i < pills.length; i++) {
            boolean sel = i == selected;
            View pill = pills[i];
            pill.animate().cancel();
            if (animate) {
                pill.animate()
                        .alpha(sel ? 1f : 0f)
                        .scaleX(sel ? 1f : 0.8f)
                        .scaleY(sel ? 1f : 0.8f)
                        .setDuration(180)
                        .start();
            } else {
                pill.setAlpha(sel ? 1f : 0f);
                pill.setScaleX(sel ? 1f : 0.8f);
                pill.setScaleY(sel ? 1f : 0.8f);
            }
            icons[i].setColor(sel ? Wds.GREEN_DEEP : Wds.TEXT_2);
            labels[i].setTextColor(sel ? Wds.GREEN_DEEP : Wds.TEXT_2);
        }
    }
}
