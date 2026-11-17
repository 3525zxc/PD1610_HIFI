package com.pd1610.hifictl;

import android.app.Activity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 设置页「维护」卡：备份 mixer / 从备份恢复 / 恢复官方默认值。
 *
 * 三个都是需要 root 的重操作：写入串行队列（Haptics.IO）执行，按钮 busy 态不可连点，
 * 结果写入按钮下方 caption 并带 HH:mm:ss；恢复类先过 ConfirmSheet（brief 4.5）。
 */
final class Maintenance {

    private final Activity host;
    private final Wds w;
    private final HifiController ctl;
    private final Runnable onDefaultsApplied;

    private final LinearLayout card;
    private final Widgets.BusyButton backupBtn;
    private final Widgets.BusyButton restoreBtn;
    private final Widgets.BusyButton resetBtn;
    private final TextView backupNote;
    private final TextView restoreNote;
    private final TextView resetNote;
    private ConfirmSheet sheet;

    Maintenance(Activity host, Wds w, HifiController ctl, Runnable onDefaultsApplied) {
        this.host = host;
        this.w = w;
        this.ctl = ctl;
        this.onDefaultsApplied = onDefaultsApplied;

        card = w.card();

        backupBtn = new Widgets.BusyButton(host, w, "备份 mixer", "备份中…");
        backupBtn.setOnClickListener(v -> {
            Haptics.tap(v);
            doBackup();
        });
        card.addView(backupBtn, w.lpw());
        backupNote = noteLine();

        w.gap(card, 12);
        restoreBtn = new Widgets.BusyButton(host, w, "从备份恢复 mixer", "恢复中…");
        restoreBtn.setOnClickListener(v -> {
            Haptics.tap(v);
            confirmRestore();
        });
        card.addView(restoreBtn, w.lpw());
        restoreNote = noteLine();

        w.gap(card, 12);
        resetBtn = new Widgets.BusyButton(host, w, "恢复官方默认值", "正在恢复…");
        resetBtn.setDestructive(true);
        resetBtn.setOnClickListener(v -> {
            Haptics.tap(v);
            confirmReset();
        });
        card.addView(resetBtn, w.lpw());
        resetNote = noteLine();
    }

    View view() {
        return card;
    }

    /** 返回键：有确认层时先关它 */
    boolean dismissSheet() {
        if (!sheetShowing()) return false;
        sheet.dismiss();
        sheet = null;
        return true;
    }

    private boolean sheetShowing() {
        return sheet != null && sheet.isShowing();
    }

    private TextView noteLine() {
        TextView t = w.caption("");
        LinearLayout.LayoutParams lp = w.lpw();
        lp.topMargin = w.dp(4);
        card.addView(t, lp);
        return t;
    }

    // ---------------- 备份 ----------------

    private void doBackup() {
        if (backupBtn.isBusy()) return;
        backupBtn.setBusy(true);
        backupNote.setTextColor(Wds.TEXT_3);
        backupNote.setText("正在写入 /data/local/tmp…");
        Haptics.IO.execute(() -> {
            boolean ok = ctl.backupMixer();
            host.runOnUiThread(() -> {
                if (host.isFinishing() || host.isDestroyed()) return;
                backupBtn.setBusy(false);
                backupNote.setTextColor(ok ? Wds.TEXT_2 : Wds.DANGER);
                backupNote.setText(ok
                        ? "已备份到 /data/local/tmp · " + Wds.time()
                        : "备份失败，请确认已授予 root · " + Wds.time());
            });
        });
    }

    // ---------------- 恢复 mixer ----------------

    private void confirmRestore() {
        if (restoreBtn.isBusy() || sheetShowing()) return;
        sheet = ConfirmSheet.show(host, w, "从备份恢复 mixer",
                "会用 /data/local/tmp 的备份覆盖当前 mixer，并打断正在播放的音频。"
                        + "恢复后建议重启手机完全生效。",
                "恢复", this::doRestore);
    }

    private void doRestore() {
        if (restoreBtn.isBusy()) return;
        restoreBtn.setBusy(true);
        restoreNote.setTextColor(Wds.TEXT_3);
        restoreNote.setText("正在恢复…");
        Haptics.IO.execute(() -> {
            boolean ok = ctl.restoreMixer();
            host.runOnUiThread(() -> {
                if (host.isFinishing() || host.isDestroyed()) return;
                restoreBtn.setBusy(false);
                restoreNote.setTextColor(ok ? Wds.TEXT_2 : Wds.DANGER);
                restoreNote.setText(ok
                        ? "已恢复，重启后完全生效 · " + Wds.time()
                        : "恢复失败，需 root 且已存在备份 · " + Wds.time());
            });
        });
    }

    // ---------------- 恢复官方默认值 ----------------

    private void confirmReset() {
        if (resetBtn.isBusy() || sheetShowing()) return;
        sheet = ConfirmSheet.show(host, w, "恢复官方默认值",
                "把 HIFI 模式切回 Off，并把 DSD 音量复位为 19。这只改 HAL 运行参数，不动 mixer 文件。",
                "恢复默认", this::doReset);
    }

    private void doReset() {
        if (resetBtn.isBusy()) return;
        resetBtn.setBusy(true);
        resetNote.setTextColor(Wds.TEXT_3);
        resetNote.setText("正在恢复…");
        Haptics.IO.execute(() -> {
            boolean modeOk = ctl.setHifiMode("off");
            boolean volOk = ctl.setDsdVolume(HifiController.DSD_VOLUME_DEFAULT);
            host.runOnUiThread(() -> {
                if (host.isFinishing() || host.isDestroyed()) return;
                resetBtn.setBusy(false);
                if (modeOk && volOk) {
                    resetNote.setTextColor(Wds.TEXT_2);
                    resetNote.setText("已恢复默认 · " + Wds.time());
                    if (onDefaultsApplied != null) onDefaultsApplied.run();
                } else {
                    resetNote.setTextColor(Wds.DANGER);
                    resetNote.setText("恢复失败 · 查看 logcat 过滤 HifiCtl · " + Wds.time());
                }
            });
        });
    }
}
