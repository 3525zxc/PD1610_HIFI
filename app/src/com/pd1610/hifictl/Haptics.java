package com.pd1610.hifictl;

import android.content.Context;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.HapticFeedbackConstants;
import android.view.View;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 震度（0–100%）与 HAL 写队列。
 *
 * 震度：percent=0 完全不震；否则 amp = clamp(round(pct*255/100), 20, 255)，保证下限可感知。
 * 若本机马达不支持振幅控制（Xplay6 这类 2016 年机型常见），系统会忽略 amplitude 参数，
 * 10%~100% 手感几乎一样，所以降级成「用脉冲时长表达强度」——时长是所有马达都认的量。
 * 所有原来 performHapticFeedback(KEYBOARD_TAP) 的调用点都换成 {@link #tap(View)}。
 *
 * IO：单线程串行队列，所有 HAL 写操作（模式 / DSD 音量 / I2S 时钟 / mixer 备份恢复）都走它，
 * 保证「最后一次点击」就是 HAL 的最终状态；只读探测不要进来，避免被慢 su 卡住。
 */
final class Haptics {

    /** HAL 写操作串行队列 */
    static final ExecutorService IO = Executors.newSingleThreadExecutor();

    static final String PREF = "ui";
    static final String KEY_PCT = "haptic_pct";
    static final int DEFAULT_PCT = 70;
    /** 马达不支持振幅时该参数会被系统忽略，传满值无害 */
    private static final int DEFAULT_AMPLITUDE = 255;

    private static Vibrator vibrator;
    private static int percent = DEFAULT_PCT;
    /** 马达是否支持振幅控制；不支持时改用脉冲时长表达强度 */
    private static boolean amplitudeOk = false;

    private Haptics() {
    }

    static void init(Context c) {
        percent = clamp(c.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .getInt(KEY_PCT, DEFAULT_PCT), 0, 100);
        vibrator = vibratorOf(c);
        amplitudeOk = supportsAmplitude(vibrator);
    }

    /** 供设置页脚注如实说明：本机能否按振幅调节震度 */
    static boolean amplitudeSupported() {
        return amplitudeOk;
    }

    static int percent() {
        return percent;
    }

    /** 写入 SharedPreferences 并生效（0 表示完全关闭） */
    static void setPercent(Context c, int value) {
        percent = clamp(value, 0, 100);
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
                .putInt(KEY_PCT, percent).apply();
    }

    /** 百分比 → 振幅，0 表示不震 */
    static int amplitude(int pct) {
        if (pct <= 0) return 0;
        return clamp(Math.round(pct * 255f / 100f), 20, 255);
    }

    /** 百分比 → 脉冲时长（不支持振幅时用它表达强度）：10%≈11ms，50%≈24ms，100%=40ms */
    private static int durationMs(int pct) {
        return clamp(Math.round(8 + pct * 0.32f), 8, 40);
    }

    /** 单次点击触感 */
    static void tap(View v) {
        if (percent <= 0) return;
        int pct = percent;
        boolean sent = amplitudeOk
                // 支持振幅：固定 16ms + 振幅
                ? oneShot(16L, amplitude(pct))
                // 不支持振幅：时长随 pct 增长，振幅参数被忽略
                : oneShot(durationMs(pct), DEFAULT_AMPLITUDE);
        if (sent) return;
        // 兜底：Vibrator 不可用时退回系统触感
        if (v != null) {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        }
    }

    /** 试震：连续两下，间隔固定 80ms；不支持振幅时每下的时长按 pct 缩放 */
    static void buzz(Context c) {
        if (percent <= 0) return;
        int pct = percent;
        long on = amplitudeOk ? 24L : durationMs(pct);
        int amp = amplitudeOk ? amplitude(pct) : DEFAULT_AMPLITUDE;
        Vibrator v = vibrator != null ? vibrator : vibratorOf(c);
        if (v == null) return;
        try {
            if (!v.hasVibrator()) return;
            v.vibrate(VibrationEffect.createWaveform(
                    new long[]{0L, on, 80L, on},
                    new int[]{0, amp, 0, amp}, -1));
        } catch (Throwable ignored) {
            // 无权限或机型不支持时静默降级，不许崩
        }
    }

    private static boolean oneShot(long ms, int amp) {
        if (amp <= 0) return false;
        Vibrator v = vibrator;
        if (v == null) return false;
        try {
            if (!v.hasVibrator()) return false;
            v.vibrate(VibrationEffect.createOneShot(ms, amp));
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 探测一次马达是否支持振幅控制（hasAmplitudeControl 是 API 26 公开方法） */
    private static boolean supportsAmplitude(Vibrator v) {
        if (v == null) return false;
        try {
            return v.hasVibrator() && v.hasAmplitudeControl();
        } catch (Throwable t) {
            return false;
        }
    }

    private static Vibrator vibratorOf(Context c) {
        if (c == null) return null;
        try {
            return (Vibrator) c.getSystemService(Context.VIBRATOR_SERVICE);
        } catch (Throwable t) {
            return null;
        }
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
