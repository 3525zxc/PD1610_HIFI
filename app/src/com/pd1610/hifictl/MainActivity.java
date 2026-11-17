package com.pd1610.hifictl;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * PD1610 HIFI 控制 v2.0（Wupp / WhatsApp 深色系，平面分层）。
 *
 * 本类只负责壳：AppBar、底部 4 Tab、页面不重建的切换、返回键与生命周期。
 * 各页内容：{@link ControlPage} / {@link StatusPage} / {@link SettingsPage} / {@link AboutPage}。
 */
public final class MainActivity extends Activity {

    private static final int TAB_CONTROL = 0;
    private static final int TAB_STATUS = 1;
    private static final int TAB_SETTINGS = 2;
    private static final int TAB_ABOUT = 3;

    private static final String[] TAB_NAMES = {"控制", "状态", "设置", "关于"};
    private static final int[] TAB_ICONS = {Icons.SLIDERS, Icons.PULSE, Icons.GEAR, Icons.INFO};
    private static final String PREF = "ui";
    private static final int ANIM_PAGE = 160;

    private Wds w;
    private HifiController ctl;
    private ControlPage controlPage;
    private StatusPage statusPage;
    private SettingsPage settingsPage;

    private final FrameLayout[] panes = new FrameLayout[4];
    private BottomNav nav;
    private TextView appBarTitle;
    private LinearLayout appBarRight;
    private LinearLayout badge;
    private View badgeDot;
    private TextView badgeText;
    private FrameLayout refreshBtn;
    private FrameLayout updateBtn;
    private View updateDot;
    private UpdateSheet updateSettingsSheet;
    private int page = TAB_CONTROL;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        w = new Wds(this);
        Haptics.init(this);
        ctl = new HifiController(this);

        getWindow().setStatusBarColor(Wds.BG_BAR);
        getWindow().setNavigationBarColor(Wds.BG_BAR);

        LinearLayout root = w.column();
        root.setBackgroundColor(Wds.BG_PAGE);
        root.addView(buildAppBar(), new LinearLayout.LayoutParams(-1, w.dp(Wds.APP_BAR_H)));

        FrameLayout content = new FrameLayout(this);
        for (int i = 0; i < panes.length; i++) {
            panes[i] = new FrameLayout(this);
            panes[i].setVisibility(i == TAB_CONTROL ? View.VISIBLE : View.GONE);
            content.addView(panes[i], new FrameLayout.LayoutParams(-1, -1));
        }

        controlPage = new ControlPage(this, w, ctl);
        panes[TAB_CONTROL].addView(scroll(controlPage.view()), new FrameLayout.LayoutParams(-1, -1));
        statusPage = new StatusPage(this, w, ctl);
        panes[TAB_STATUS].addView(statusPage.view(), new FrameLayout.LayoutParams(-1, -1));
        settingsPage = new SettingsPage(this, w, ctl, this::onDefaultsApplied);
        panes[TAB_SETTINGS].addView(scroll(settingsPage.view()), new FrameLayout.LayoutParams(-1, -1));
        panes[TAB_ABOUT].addView(scroll(AboutPage.build(this, w)), new FrameLayout.LayoutParams(-1, -1));

        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1f));
        root.addView(buildBottomBar(), new LinearLayout.LayoutParams(-1, -2));
        setContentView(root);

        controlPage.modePanel().setStatusListener(this::onModeStatus);

        int saved = getSharedPreferences(PREF, MODE_PRIVATE).getInt("last_page", TAB_CONTROL);
        selectTab(saved, false);
        controlPage.refreshMode();

        // 按用户设置的频率自动检查更新；发现新版本时点亮右上角圆点
        if (Updates.shouldAutoCheck(this)) {
            Updates.check(this, (tag, url, notes, err) -> refreshUpdateDot());
        }
    }

    private void refreshUpdateDot() {
        if (updateDot == null) return;
        boolean has = Updates.hasUpdate(this);
        updateDot.setVisibility(has ? View.VISIBLE : View.GONE);
        updateBtn.setContentDescription(has ? "有可用更新" : "更新检查设置");
    }

    // ============================================================
    // AppBar（56dp：标题 20sp + 右侧动作槽）
    // ============================================================

    private View buildAppBar() {
        FrameLayout bar = new FrameLayout(this);
        bar.setBackgroundColor(Wds.BG_BAR);

        appBarTitle = w.title(TAB_NAMES[TAB_CONTROL]);
        FrameLayout.LayoutParams titleLp = new FrameLayout.LayoutParams(
                -2, -2, Gravity.LEFT | Gravity.CENTER_VERTICAL);
        titleLp.leftMargin = w.dp(Wds.PAGE_PAD);
        bar.addView(appBarTitle, titleLp);

        appBarRight = w.row();
        FrameLayout.LayoutParams rightLp = new FrameLayout.LayoutParams(
                -2, -2, Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        rightLp.rightMargin = w.dp(8);
        bar.addView(appBarRight, rightLp);

        // 控制页右侧：状态徽标（小圆点 + 当前模式）
        badge = w.row();
        badgeDot = new View(this);
        badgeDot.setBackground(w.circle(Wds.WARN));
        badge.addView(badgeDot, new LinearLayout.LayoutParams(w.dp(8), w.dp(8)));
        badgeText = w.caption("读取中");
        LinearLayout.LayoutParams badgeTextLp = new LinearLayout.LayoutParams(-2, -2);
        badgeTextLp.leftMargin = w.dp(6);
        badge.addView(badgeText, badgeTextLp);

        // 状态页右侧：刷新按钮（手动重测）
        refreshBtn = new FrameLayout(this);
        refreshBtn.setBackground(w.ripple(Wds.RIPPLE, 24f));
        refreshBtn.setClickable(true);
        Icons.Icon refreshIcon = new Icons.Icon(this);
        refreshIcon.set(Icons.REFRESH, Wds.TEXT_2);
        refreshBtn.addView(refreshIcon, new FrameLayout.LayoutParams(w.dp(24), w.dp(24), Gravity.CENTER));
        refreshBtn.setOnClickListener(v -> {
            Haptics.tap(v);
            statusPage.remeasure();
        });

        // 右上角常驻：更新检查设置（有新版本时圆点亮起）
        updateBtn = new FrameLayout(this);
        updateBtn.setBackground(w.ripple(Wds.RIPPLE, 24f));
        updateBtn.setClickable(true);
        Icons.Icon upIcon = new Icons.Icon(this);
        upIcon.set(Icons.REFRESH, Wds.TEXT_2);
        updateBtn.addView(upIcon, new FrameLayout.LayoutParams(w.dp(24), w.dp(24), Gravity.CENTER));
        updateDot = new View(this);
        updateDot.setBackground(w.circle(Wds.GREEN));
        FrameLayout.LayoutParams dotLp = new FrameLayout.LayoutParams(w.dp(7), w.dp(7),
                Gravity.TOP | Gravity.RIGHT);
        dotLp.topMargin = w.dp(6);
        dotLp.rightMargin = w.dp(6);
        updateBtn.addView(updateDot, dotLp);
        updateBtn.setOnClickListener(v -> {
            Haptics.tap(v);
            updateSettingsSheet = UpdateSheet.showSettings(this, w, this::refreshUpdateDot);
        });

        View line = new View(this);
        line.setBackgroundColor(Wds.HAIRLINE);
        bar.addView(line, new FrameLayout.LayoutParams(-1, Math.max(1, w.dp(1)), Gravity.BOTTOM));
        return bar;
    }

    private void onModeStatus(int dotColor, String modeTitle) {
        if (badgeDot == null) return;
        badgeDot.setBackground(w.circle(dotColor));
        badgeText.setText(modeTitle);
    }

    private void updateAppBar(int tab) {
        appBarTitle.setText(TAB_NAMES[tab]);
        appBarRight.removeAllViews();
        if (tab == TAB_CONTROL) {
            appBarRight.addView(badge, new LinearLayout.LayoutParams(-2, -2));
        } else if (tab == TAB_STATUS) {
            appBarRight.addView(refreshBtn, new LinearLayout.LayoutParams(w.dp(Wds.TOUCH), w.dp(Wds.TOUCH)));
        }
        // 更新按钮：四页常驻，始终在最右
        appBarRight.addView(updateBtn, new LinearLayout.LayoutParams(w.dp(Wds.TOUCH), w.dp(Wds.TOUCH)));
        refreshUpdateDot();
    }

    // ============================================================
    // 底部导航（1dp 分割线 + 64dp 导航条）
    // ============================================================

    private View buildBottomBar() {
        LinearLayout bar = w.column();
        View line = new View(this);
        line.setBackgroundColor(Wds.HAIRLINE);
        bar.addView(line, new LinearLayout.LayoutParams(-1, Math.max(1, w.dp(1))));

        nav = new BottomNav(this, w, TAB_ICONS, TAB_NAMES);
        nav.setOnTabSelected(index -> selectTab(index, true));
        bar.addView(nav, new LinearLayout.LayoutParams(-1, w.dp(Wds.NAV_H)));
        return bar;
    }

    // ============================================================
    // 切页：不重建 View（保留滚动位置），淡入 + 上移 160ms
    // ============================================================

    private void selectTab(int index, boolean animate) {
        int idx = index < 0 ? 0 : (index > TAB_ABOUT ? TAB_ABOUT : index);
        int prev = page;
        page = idx;

        nav.setSelected(idx, animate);
        updateAppBar(idx);

        for (int i = 0; i < panes.length; i++) {
            panes[i].animate().cancel();
            panes[i].setVisibility(i == idx ? View.VISIBLE : View.GONE);
        }

        View target = panes[idx];
        if (animate && prev != idx) {
            target.setAlpha(0f);
            target.setTranslationY(w.dpf(6));
            target.animate().alpha(1f).translationY(0f)
                    .setDuration(ANIM_PAGE)
                    .setInterpolator(new DecelerateInterpolator())
                    .start();
        } else {
            target.setAlpha(1f);
            target.setTranslationY(0f);
        }

        getSharedPreferences(PREF, MODE_PRIVATE).edit().putInt("last_page", idx).apply();
        if (idx == TAB_STATUS) statusPage.onEnter();
    }

    private ScrollView scroll(View content) {
        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        sv.setClipToPadding(false);
        sv.addView(content, new ScrollView.LayoutParams(-1, -2));
        return sv;
    }

    /** 「恢复默认」成功后同步控制页控件（Maintenance 在 UI 线程回调） */
    private void onDefaultsApplied() {
        controlPage.applyDefaults();
    }

    // ============================================================
    // 返回键 / 生命周期
    // ============================================================

    @Override
    public void onBackPressed() {
        if (updateSettingsSheet != null && updateSettingsSheet.isShowing()) {
            updateSettingsSheet.dismiss();
            return;
        }
        if (settingsPage != null && settingsPage.dismissSheet()) return;
        if (page != TAB_CONTROL) {
            selectTab(TAB_CONTROL, true);
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        for (FrameLayout pane : panes) {
            if (pane != null) pane.animate().cancel();
        }
        super.onDestroy();
    }
}
