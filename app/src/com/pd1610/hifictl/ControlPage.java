package com.pd1610.hifictl;

import android.app.Activity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

/**
 * 页0 控制：当前输出（Hero 卡）/ DSD 音量 / I2S 时钟。
 * 维护相关操作已搬到设置页，本页只做「听感参数」。
 */
final class ControlPage {

    private static final String[] CLOCK_LABELS = {"44.1k", "48k", "96k", "192k"};
    private static final int[] CLOCK_HZ = {44100, 48000, 96000, 192000};

    private final Activity host;
    private final Wds w;
    private final HifiController ctl;
    private final LinearLayout view;
    private final ModePanel modePanel;

    private final TextView dsdValue;
    private final SeekBar dsdSeek;
    private final TextView dsdNote;
    private final Widgets.SegmentedControl clockSeg;
    private final TextView clockNote;

    ControlPage(Activity host, Wds w, HifiController ctl) {
        this.host = host;
        this.w = w;
        this.ctl = ctl;
        view = w.column();

        // ---- 分区：当前输出 ----
        view.addView(Widgets.section(host, w, "当前输出"));
        modePanel = new ModePanel(host, w, ctl);
        view.addView(modePanel.view(), w.cardLp());

        // ---- 分区：DSD 音量 ----
        view.addView(Widgets.section(host, w, "DSD 音量"));
        LinearLayout dsd = w.card();
        LinearLayout head = w.row();
        head.addView(w.text("音量", Wds.SP_SECTION, Wds.TEXT_2, Wds.REG),
                new LinearLayout.LayoutParams(0, -2, 1f));
        dsdValue = w.hero(String.valueOf(HifiController.DSD_VOLUME_DEFAULT));
        head.addView(dsdValue);
        dsd.addView(head, w.lpw());

        dsdSeek = new SeekBar(host);
        dsdSeek.setMax(31);
        dsdSeek.setProgress(HifiController.DSD_VOLUME_DEFAULT);
        SeekSkin.apply(dsdSeek, w);
        LinearLayout.LayoutParams seekLp = new LinearLayout.LayoutParams(-1, w.dp(40));
        seekLp.topMargin = w.dp(4);
        dsd.addView(dsdSeek, seekLp);

        dsd.addView(new SeekSkin.Ticks(host, w, 31, HifiController.DSD_VOLUME_DEFAULT),
                new LinearLayout.LayoutParams(-1, w.dp(20)));

        dsdNote = w.caption("松手后应用 · HAL key dsdvol_index");
        LinearLayout.LayoutParams noteLp = w.lpw();
        noteLp.topMargin = w.dp(8);
        dsd.addView(dsdNote, noteLp);

        dsdSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                dsdValue.setText(String.valueOf(progress));
                SeekSkin.sync(bar);
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
                // 拖动过程中不写 HAL，松手才应用
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                applyDsdVolume(bar.getProgress());
            }
        });
        view.addView(dsd, w.cardLp());

        // ---- 分区：I2S 时钟 ----
        view.addView(Widgets.section(host, w, "I2S 时钟"));
        LinearLayout clk = w.card();
        clk.addView(w.text("采样率", Wds.SP_SECTION, Wds.TEXT_2, Wds.REG), w.lpw());
        clockSeg = new Widgets.SegmentedControl(host, w, CLOCK_LABELS);
        LinearLayout.LayoutParams clkLp = new LinearLayout.LayoutParams(-1, w.dp(44));
        clkLp.topMargin = w.dp(12);
        clk.addView(clockSeg, clkLp);
        clockSeg.clearSelection(); // 当前档位读不回来，不预设选中，避免谎报
        clockSeg.setOnSegmentChangeListener((index, fromUser) -> {
            if (fromUser) applyClock(index);
        });

        clockNote = w.caption("当前档位未读取 · HAL key hifi_i2s_clock_rate，各档是否支持需实机验证");
        LinearLayout.LayoutParams clkNoteLp = w.lpw();
        clkNoteLp.topMargin = w.dp(8);
        clk.addView(clockNote, clkNoteLp);
        view.addView(clk, w.cardLp());

        w.gap(view, 8);
    }

    View view() {
        return view;
    }

    ModePanel modePanel() {
        return modePanel;
    }

    void refreshMode() {
        modePanel.refresh();
    }

    // ---------------- HAL 写操作（串行队列） ----------------

    private void applyDsdVolume(final int value) {
        dsdNote.setTextColor(Wds.TEXT_3);
        dsdNote.setText("排队中…");
        Haptics.IO.execute(() -> {
            host.runOnUiThread(() -> {
                if (host.isFinishing() || host.isDestroyed()) return;
                dsdNote.setText("正在应用…");
            });
            boolean ok = ctl.setDsdVolume(value);
            host.runOnUiThread(() -> {
                if (host.isFinishing() || host.isDestroyed()) return;
                if (ok) {
                    dsdNote.setTextColor(Wds.TEXT_3);
                    dsdNote.setText("已应用 · " + value + " · " + Wds.time());
                } else {
                    dsdNote.setTextColor(Wds.DANGER);
                    dsdNote.setText("设置失败 · 查看 logcat 过滤 HifiCtl · " + Wds.time());
                }
            });
        });
    }

    private void applyClock(final int index) {
        final int hz = CLOCK_HZ[index];
        clockNote.setTextColor(Wds.TEXT_3);
        clockNote.setText("排队中…");
        Haptics.IO.execute(() -> {
            host.runOnUiThread(() -> {
                if (host.isFinishing() || host.isDestroyed()) return;
                clockNote.setText("正在发送 " + hz + " Hz…");
            });
            boolean ok = ctl.setI2sClockRate(hz);
            host.runOnUiThread(() -> {
                if (host.isFinishing() || host.isDestroyed()) return;
                if (ok) {
                    clockNote.setTextColor(Wds.TEXT_3);
                    clockNote.setText("已发送 " + hz + " Hz · 是否被 HAL 接受需实机验证 · " + Wds.time());
                } else {
                    clockNote.setTextColor(Wds.DANGER);
                    clockNote.setText("设置失败 · 查看 logcat 过滤 HifiCtl · " + Wds.time());
                }
            });
        });
    }

    /** 「恢复默认」成功后同步本页控件（由 Maintenance 在 UI 线程回调） */
    void applyDefaults() {
        dsdSeek.setProgress(HifiController.DSD_VOLUME_DEFAULT);
        dsdValue.setText(String.valueOf(HifiController.DSD_VOLUME_DEFAULT));
        SeekSkin.sync(dsdSeek);
        dsdNote.setTextColor(Wds.TEXT_3);
        dsdNote.setText("已恢复默认 · " + Wds.time());
        clockSeg.clearSelection();
        clockNote.setTextColor(Wds.TEXT_3);
        clockNote.setText("已恢复默认 · 当前档位未读取");
        modePanel.setMode(0, true, "已恢复默认 · 重开播放后生效");
        modePanel.showBanner(Widgets.InlineBanner.OK,
                "已恢复 Off / 音量 19 · 重开播放后生效", null, null);
    }
}
