package com.pd1610.hifictl;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.TranslateAnimation;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 3.7 ConfirmSheet：自绘底部确认层（不用系统 AlertDialog）。
 *
 * 防双击（v1.2 QA 的 Major，必须保留）：退场动画 220ms 期间 overlay 仍在窗口上且命中矩形不变，
 * 所以确认回调首行判 dismissed，并立刻禁用两个按钮 + overlay 置不可点。
 * panel 必须 setClickable(true)，否则点标题/说明的空白区会穿透到遮罩导致误关闭。
 */
final class ConfirmSheet {

    private static final int ANIM_MS = 220;

    private final FrameLayout overlay;
    private final View sheet;
    private final TextView confirm;
    private final TextView cancel;
    private boolean dismissed = false;

    private ConfirmSheet(FrameLayout overlay, View sheet, TextView confirm, TextView cancel) {
        this.overlay = overlay;
        this.sheet = sheet;
        this.confirm = confirm;
        this.cancel = cancel;
    }

    static ConfirmSheet show(Activity activity, Wds w, String title, String message,
                             String confirmLabel, Runnable onConfirm) {
        View content = activity.findViewById(android.R.id.content);
        if (!(content instanceof ViewGroup)) return null;
        final ViewGroup host = (ViewGroup) content;

        final FrameLayout overlay = new FrameLayout(activity);
        overlay.setClickable(true);
        overlay.setFocusable(true);

        View scrim = new View(activity);
        scrim.setBackgroundColor(0x99000000);
        scrim.setClickable(true);
        overlay.addView(scrim, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setClickable(true);
        panel.setBackground(w.topRounded(Wds.SURFACE_2, Wds.R_SHEET));
        int px = w.dp(20);
        panel.setPadding(px, w.dp(20), px, w.dp(20));

        panel.addView(w.title(title), w.lpw());

        TextView sub = w.body(message);
        LinearLayout.LayoutParams subLp = w.lpw();
        subLp.topMargin = w.dp(8);
        panel.addView(sub, subLp);

        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        final TextView cancelBtn = button(w, "取消", Wds.TEXT, false);
        row.addView(cancelBtn, new LinearLayout.LayoutParams(0, w.dp(Wds.TOUCH), 1f));
        w.hgap(row, 12);
        final TextView confirmBtn = button(w, confirmLabel, Wds.ON_DANGER, true);
        row.addView(confirmBtn, new LinearLayout.LayoutParams(0, w.dp(Wds.TOUCH), 1f));

        LinearLayout.LayoutParams rowLp = w.lpw();
        rowLp.topMargin = w.dp(20);
        panel.addView(row, rowLp);

        final ConfirmSheet holder = new ConfirmSheet(overlay, panel, confirmBtn, cancelBtn);
        overlay.addView(panel, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

        scrim.setOnClickListener(v -> {
            if (holder.dismissed) return;
            holder.dismiss();
        });
        cancelBtn.setOnClickListener(v -> {
            if (holder.dismissed) return;
            Haptics.tap(v);
            holder.dismiss();
        });
        confirmBtn.setOnClickListener(v -> {
            if (holder.dismissed) return;
            Haptics.tap(v);
            confirmBtn.setEnabled(false);
            cancelBtn.setEnabled(false);
            overlay.setClickable(false);
            holder.dismiss();
            if (onConfirm != null) onConfirm.run();
        });

        host.addView(overlay, new ViewGroup.LayoutParams(-1, -1));

        AlphaAnimation fade = new AlphaAnimation(0f, 1f);
        fade.setDuration(ANIM_MS);
        scrim.startAnimation(fade);

        TranslateAnimation slide = new TranslateAnimation(
                Animation.RELATIVE_TO_SELF, 0f, Animation.RELATIVE_TO_SELF, 0f,
                Animation.RELATIVE_TO_SELF, 1f, Animation.RELATIVE_TO_SELF, 0f);
        slide.setDuration(ANIM_MS);
        slide.setInterpolator(new DecelerateInterpolator());
        panel.startAnimation(slide);

        return holder;
    }

    boolean isShowing() {
        return !dismissed && overlay.getParent() != null;
    }

    void dismiss() {
        if (dismissed) return;
        dismissed = true;
        confirm.setEnabled(false);
        cancel.setEnabled(false);
        overlay.setClickable(false);

        TranslateAnimation slide = new TranslateAnimation(
                Animation.RELATIVE_TO_SELF, 0f, Animation.RELATIVE_TO_SELF, 0f,
                Animation.RELATIVE_TO_SELF, 0f, Animation.RELATIVE_TO_SELF, 1f);
        slide.setDuration(ANIM_MS);
        slide.setInterpolator(new DecelerateInterpolator());
        slide.setAnimationListener(new Animation.AnimationListener() {
            @Override
            public void onAnimationStart(Animation a) {
                // 无需处理
            }

            @Override
            public void onAnimationEnd(Animation a) {
                detach();
            }

            @Override
            public void onAnimationRepeat(Animation a) {
                // 无需处理
            }
        });
        sheet.startAnimation(slide);
        // 兜底：系统丢弃动画时也要移除
        new Handler(Looper.getMainLooper()).postDelayed(this::detach, ANIM_MS + 80);
    }

    private void detach() {
        Object parent = overlay.getParent();
        if (parent instanceof ViewGroup) {
            ((ViewGroup) parent).removeView(overlay);
        }
    }

    /** 底部按钮：危险主按钮 DANGER 实底 + 深色文字，次按钮 HAIRLINE 描边 */
    private static TextView button(Wds w, String text, int color, boolean danger) {
        TextView t = w.text(text, Wds.SP_LIST, color, Wds.MED);
        t.setGravity(Gravity.CENTER);
        if (danger) {
            t.setBackground(w.ripple(w.solid(Wds.DANGER, Wds.R_BTN), Wds.RIPPLE_DARK, Wds.R_BTN));
        } else {
            t.setBackground(w.ripple(w.stroke(0x00000000, Wds.HAIRLINE, Wds.R_BTN), Wds.RIPPLE, Wds.R_BTN));
        }
        t.setClickable(true);
        return t;
    }
}
