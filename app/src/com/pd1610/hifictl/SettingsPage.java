package com.pd1610.hifictl;

import android.app.Activity;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

/**
 * 页2 设置：触感反馈（震度 0–100，10% 吸附 + 试震）+ 维护（三个根操作）。
 */
final class SettingsPage {

    private final Activity host;
    private final Wds w;
    private final TextView pctLabel;
    private final TextView pctNote;
    private final Maintenance maintenance;
    private final LinearLayout view;

    SettingsPage(Activity host, Wds w, HifiController ctl, Runnable onDefaultsApplied) {
        this.host = host;
        this.w = w;
        view = w.column();

        // ---- 分区：触感反馈 ----
        view.addView(Widgets.section(host, w, "触感反馈"));
        LinearLayout haptic = w.card();

        LinearLayout head = w.row();
        head.addView(w.list("震度"), new LinearLayout.LayoutParams(0, -2, 1f));
        pctLabel = w.text(Haptics.percent() + "%", Wds.SP_BODY, Wds.TEXT_2, Wds.REG);
        head.addView(pctLabel);
        haptic.addView(head, w.lpw());

        SeekBar bar = new SeekBar(host);
        bar.setMax(100);
        bar.setProgress(Haptics.percent());
        SeekSkin.apply(bar, w);
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(-1, w.dp(40));
        barLp.topMargin = w.dp(8);
        haptic.addView(bar, barLp);

        pctNote = w.caption("");
        haptic.addView(pctNote, w.lpw());
        applyNote(Haptics.percent());

        // 试震：GREEN 实底主按钮（本页唯一主操作），右对齐 104dp 胶囊
        TextView test = w.text("试震一下", Wds.SP_BODY, Wds.ON_GREEN, Wds.MED);
        test.setGravity(Gravity.CENTER);
        test.setBackground(w.ripple(w.solid(Wds.GREEN, 20f), Wds.RIPPLE_DARK, 20f));
        test.setClickable(true);
        test.setOnClickListener(v -> Haptics.buzz(host));
        LinearLayout testRow = w.row();
        testRow.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams testLp = new LinearLayout.LayoutParams(w.dp(104), w.dp(40));
        testLp.topMargin = w.dp(12);
        testRow.addView(test, testLp);
        haptic.addView(testRow, w.lpw());

        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int value, boolean fromUser) {
                if (fromUser) {
                    // 10% 吸附（SeekBar 的 setStepSize 在 API 28 上不是公开接口，手工吸附）
                    int snapped = Math.round(value / 10f) * 10;
                    if (snapped != value) {
                        seekBar.setProgress(snapped);
                        return;
                    }
                }
                pctLabel.setText(value + "%");
                applyNote(value);
                SeekSkin.sync(seekBar);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                // 无需处理
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                Haptics.setPercent(host, seekBar.getProgress());
                applyNote(seekBar.getProgress());
                Haptics.tap(seekBar); // 松手立刻用新强度反馈一次
            }
        });
        view.addView(haptic, w.cardLp());

        // ---- 分区：维护 ----
        view.addView(Widgets.section(host, w, "维护（需 root）"));
        maintenance = new Maintenance(host, w, ctl, onDefaultsApplied);
        view.addView(maintenance.view(), w.cardLp());

        w.gap(view, 8);
    }

    View view() {
        return view;
    }

    /** 返回键：有确认层时先关它 */
    boolean dismissSheet() {
        return maintenance.dismissSheet();
    }

    private void applyNote(int pct) {
        if (pct <= 0) {
            pctNote.setTextColor(Wds.WARN);
            pctNote.setText("触感已关闭");
        } else {
            pctNote.setTextColor(Wds.TEXT_3);
            // 马达不支持振幅时如实告知：强度是按脉冲时长模拟的
            pctNote.setText("当前震度 " + pct + "% · "
                    + (Haptics.amplitudeSupported()
                    ? "仅影响本 APP 的触感反馈"
                    : "本机不支持振幅调节，已按脉冲时长模拟"));
        }
    }
}
