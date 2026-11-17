package com.pd1610.hifictl;

import android.app.Activity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

/**
 * 控制页 Hero 卡：当前模式 32sp + 状态副标 + 三段分段 + 卡内结果条。
 *
 * 交互（brief 4.1）：
 *  - 乐观更新：点击先动滑块与标题，再走串行队列写 HAL，最后回读对账；
 *  - token 竞态：applyMode 在 UI 线程 ++token，工作线程把 myToken 带回比对，过期结果丢弃；
 *    rollback 也只比对不自增（禁止越权作废更晚的点击）；
 *  - DSD 不谎报：副标固定「由音源触发」，只挂 info banner，不挂动作按钮；
 *  - 冷启动读不到 enable_hifi 时 clearSelection()，绝不默认高亮 Off。
 */
final class ModePanel {

    interface StatusListener {
        void onStatus(int dotColor, String modeTitle);
    }

    private static final String[] MODE_KEYS = {"off", "hifi", "dsd"};
    private static final String[] MODE_TITLES = {"Off", "HiFi", "DSD"};

    private final Activity host;
    private final Wds w;
    private final HifiController ctl;
    private final LinearLayout card;
    private final Widgets.SegmentedControl seg;
    private final TextView hero;
    private final TextView sub;
    private final LinearLayout bannerSlot;
    private Widgets.InlineBanner banner;
    private StatusListener statusListener;
    private int dotColor = Wds.WARN;
    private int token = 0;

    ModePanel(Activity host, Wds w, HifiController ctl) {
        this.host = host;
        this.w = w;
        this.ctl = ctl;

        card = w.card();
        hero = w.hero("读取中");
        card.addView(hero, w.lpw());

        sub = w.body("正在读取 HAL 状态…");
        LinearLayout.LayoutParams subLp = w.lpw();
        subLp.topMargin = w.dp(4);
        card.addView(sub, subLp);

        seg = new Widgets.SegmentedControl(host, w, MODE_TITLES);
        LinearLayout.LayoutParams segLp = new LinearLayout.LayoutParams(-1, w.dp(44));
        segLp.topMargin = w.dp(16);
        card.addView(seg, segLp);
        seg.setOnSegmentChangeListener((index, fromUser) -> {
            if (fromUser) applyMode(index);
        });

        bannerSlot = w.column();
        card.addView(bannerSlot, w.lpw());
    }

    View view() {
        return card;
    }

    void setStatusListener(StatusListener l) {
        statusListener = l;
        if (l != null) l.onStatus(dotColor, hero.getText().toString());
    }

    // ---------------- 文案与状态徽标 ----------------

    private void setHeadline(String text, int dot) {
        hero.setText(text);
        dotColor = dot;
        if (statusListener != null) statusListener.onStatus(dot, text);
    }

    private void setSub(String text) {
        sub.setText(text);
    }

    // ---------------- 冷启动读取 ----------------

    void refresh() {
        new Thread(() -> {
            String state = ctl.currentHifiState();
            host.runOnUiThread(() -> {
                if (host.isFinishing() || host.isDestroyed()) return;
                int idx = stateToIndex(state);
                if (idx < 0) {
                    setHeadline("未知", Wds.WARN);
                    seg.clearSelection(); // 读不到就绝不默认高亮 Off
                    if (state.isEmpty()) {
                        setSub("HAL 未回报 · 可能尚未重开音频流");
                    } else {
                        setSub("HAL 回报了未知值：" + state);
                    }
                    return;
                }
                setHeadline(MODE_TITLES[idx], Wds.GREEN_DEEP);
                seg.setSelectedIndex(idx, false);
                setSub("正在生效 · enable_hifi = " + state);
            });
        }).start();
    }

    /** 外部（恢复默认）直接改状态：作废在途对账并更新 UI */
    void setMode(int index, boolean animate, String subText) {
        token++;
        seg.setSelectedIndex(index, animate);
        setHeadline(MODE_TITLES[index], Wds.GREEN_DEEP);
        setSub(subText);
    }

    // ---------------- 乐观更新 + 回读对账 ----------------

    private void applyMode(int index) {
        final int myToken = ++token;
        final String mode = MODE_KEYS[index];

        setHeadline(MODE_TITLES[index], Wds.WARN);
        clearBanner();
        if ("dsd".equals(mode)) {
            setSub("由音源触发，需原生 DSD 播放器");
            showBanner(Widgets.InlineBanner.INFO,
                    "已允许 DSD · 真正生效需要原生 DSD 音源与支持直出的播放器，本 APP 无法验证",
                    null, null);
        } else {
            setSub("已发送，等待重开播放");
        }

        // 写在串行队列里，读回也放在同一个任务内，保证读到的是自己刚写的值
        Haptics.IO.execute(() -> {
            boolean ok = ctl.setHifiMode(mode);
            String state = ctl.currentHifiState();
            host.runOnUiThread(() -> {
                if (host.isFinishing() || host.isDestroyed()) return;
                onResult(myToken, index, ok, state);
            });
        });
    }

    private void onResult(int myToken, int index, boolean ok, String state) {
        if (myToken != token) return; // 已被更新的点击取代
        String mode = MODE_KEYS[index];

        if (!ok) {
            setHeadline(MODE_TITLES[index], Wds.DANGER);
            setSub("调用失败");
            showBanner(Widgets.InlineBanner.DANGER,
                    "设置失败 · 查看 logcat 过滤 HifiCtl", null, null);
            rollback(myToken);
            return;
        }
        if ("dsd".equals(mode)) {
            setHeadline(MODE_TITLES[index], Wds.WARN);
            setSub("由音源触发，需原生 DSD 播放器");
            return;
        }
        if (matches(mode, state)) {
            setHeadline(MODE_TITLES[index], Wds.GREEN_DEEP);
            setSub("正在生效 · enable_hifi = " + state);
            showBanner(Widgets.InlineBanner.OK,
                    "已切到 " + MODE_TITLES[index] + " · 重开播放后生效",
                    "我已重开播放", () -> recheck(mode));
        } else {
            setHeadline(MODE_TITLES[index], Wds.WARN);
            setSub("HAL 未回报");
            showBanner(Widgets.InlineBanner.WARN,
                    "HAL 未回报 enable_hifi，可能尚未重开音频流",
                    "我已重开播放", () -> recheck(mode));
        }
    }

    /** 重新对账：模式由发起 banner 时传入，并用 token 校验防连点串台 */
    private void recheck(String mode) {
        final int myToken = token;
        new Thread(() -> {
            String state = ctl.currentHifiState();
            host.runOnUiThread(() -> {
                if (host.isFinishing() || host.isDestroyed()) return;
                if (myToken != token) return;
                if (matches(mode, state)) {
                    setHeadline(MODE_TITLES[indexOf(mode)], Wds.GREEN_DEEP);
                    setSub("正在生效 · enable_hifi = " + state);
                    showBanner(Widgets.InlineBanner.OK,
                            "已确认生效 · enable_hifi = " + state, null, null);
                    if (banner != null) banner.dismissAfter(3000);
                } else {
                    String shown = state.isEmpty() ? "读取不到" : state;
                    setSub("HAL 未回报");
                    showBanner(Widgets.InlineBanner.WARN,
                            "仍未回报 · enable_hifi = " + shown, "再试一次", () -> recheck(mode));
                }
            });
        }).start();
    }

    /** 调用失败时回滚到 HAL 真实状态（只比对 token，不自增） */
    private void rollback(int myToken) {
        new Thread(() -> {
            String state = ctl.currentHifiState();
            host.runOnUiThread(() -> {
                if (host.isFinishing() || host.isDestroyed()) return;
                if (myToken != token) return;
                int idx = stateToIndex(state);
                if (idx < 0) {
                    seg.clearSelection();
                    setHeadline("未知", Wds.WARN);
                    setSub("已回滚 · HAL 未回报当前状态");
                    return;
                }
                seg.setSelectedIndex(idx, true);
                setHeadline(MODE_TITLES[idx], Wds.GREEN_DEEP);
                setSub("已回滚到 HAL 真实状态 · enable_hifi = " + state);
            });
        }).start();
    }

    // ---------------- 结果条 ----------------

    void showBanner(int type, String text, String actionLabel, Runnable action) {
        clearBanner();
        Widgets.InlineBanner b = new Widgets.InlineBanner(host, w, type, text);
        b.setOnDismissed(() -> {
            if (banner == b) banner = null;
        });
        if (actionLabel != null) b.setAction(actionLabel, action);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = w.dp(12);
        bannerSlot.addView(b, lp);
        banner = b;
    }

    void clearBanner() {
        bannerSlot.removeAllViews();
        banner = null;
    }

    // ---------------- 状态映射 ----------------

    private static int indexOf(String mode) {
        for (int i = 0; i < MODE_KEYS.length; i++) {
            if (MODE_KEYS[i].equals(mode)) return i;
        }
        return 1;
    }

    static boolean matches(String mode, String state) {
        String v = state == null ? "" : state.trim().toLowerCase(Locale.US);
        if ("off".equals(mode)) return v.equals("0") || v.equals("false");
        if ("hifi".equals(mode)) return v.equals("1") || v.equals("true");
        return false; // DSD 无法用参数验证
    }

    static int stateToIndex(String state) {
        String v = state == null ? "" : state.trim().toLowerCase(Locale.US);
        if (v.equals("0") || v.equals("false")) return 0;
        if (v.equals("1") || v.equals("true")) return 1;
        return -1;
    }
}
