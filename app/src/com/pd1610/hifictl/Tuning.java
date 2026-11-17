package com.pd1610.hifictl;

import android.content.Context;
import android.util.Log;

/**
 * 音质档（耳机放大器工作模式 RX HPH Mode）。
 *
 * 实测（2026-11-17，PD1610 + LOS16）：
 *  - tinymix 控件 #128 "RX HPH Mode"，可写枚举：CLS_H_HIFI / CLS_H_LP / CLS_AB / CLS_H_LOHIFI
 *  - 官方原厂两版（1.15.1 / 8.19.16）的 headphone-hifi path 逐字节相同，默认都是 CLS_H_LP
 *  - 切换立即生效（写的是 codec 寄存器，不需要重开播放），且不随 APP 退出丢失
 *
 * 本档不是"两版固件调音"——两版 HIFI 通路参数完全一致。这里切的是耳放工作模式：
 *  省电（CLS_H_LP，官方默认）/ 高保真（CLS_H_HIFI，耳放推满，耗电略高）。
 *
 * AOSP 官方 tinymix 路径：优先 Magisk 模块自带，回退 /data/local/tmp。
 */
final class Tuning {

    private static final String TAG = "HifiCtlTuning";
    static final String PREF = "tuning";

    static final int MODE_SAVE = 0;   // CLS_H_LP   省电（官方默认）
    static final int MODE_HIFI = 1;   // CLS_H_HIFI 高保真

    private static final String CTL = "RX HPH Mode";
    private static final String[] VALUES = {"CLS_H_LP", "CLS_H_HIFI"};
    static final String[] LABELS = {"官方默认（省电）", "高保真（耳放推满）"};

    private static final String[] TINYMIX_PATHS = {
            "/data/adb/modules/audio_pd1610/tinymix",
            "/data/local/tmp/tinymix",
            "/system/bin/tinymix",
            "/vendor/bin/tinymix",
    };

    private Tuning() {}

    /** 用户上次选的档（0 省电 / 1 高保真），默认官方省电档 */
    static int saved(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE).getInt("mode", MODE_SAVE);
    }

    private static void save(Context c, int mode) {
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putInt("mode", mode).apply();
    }

    /** 找一个可执行的 tinymix */
    static String tinymixPath() {
        for (String p : TINYMIX_PATHS) {
            if (RootShell.run("[ -x " + p + " ] && echo yes").out.contains("yes")) return p;
        }
        return null;
    }

    /** 读当前档；返回 -1 表示读不到（未 root / 无 tinymix / 解析失败） */
    static int read() {
        String tm = tinymixPath();
        if (tm == null) return -1;
        RootShell.Result r = RootShell.run(tm + " '" + CTL + "'");
        if (!r.ok()) return -1;
        // 输出形如：RX HPH Mode: CLS_H_INVALID >CLS_H_LP CLS_AB ...
        int gt = r.out.indexOf('>');
        if (gt < 0) return -1;
        String cur = r.out.substring(gt + 1).trim().split("\\s+")[0];
        if (cur.equals("CLS_H_HIFI")) return MODE_HIFI;
        if (cur.equals("CLS_H_LP") || cur.equals("CLS_AB") || cur.equals("CLS_H_LOHIFI")) return MODE_SAVE;
        return -1;
    }

    /** 应用档位并持久化；立即生效（写 codec 控件），无需重开播放 */
    static boolean apply(Context c, int mode) {
        int m = mode == MODE_HIFI ? MODE_HIFI : MODE_SAVE;
        String tm = tinymixPath();
        if (tm == null) {
            Log.w(TAG, "tinymix not found");
            return false;
        }
        RootShell.Result r = RootShell.run(tm + " '" + CTL + "' " + VALUES[m]);
        Log.i(TAG, "apply " + VALUES[m] + " -> " + r);
        if (!r.ok()) return false;
        save(c, m);
        return true;
    }

    /**
     * 开机自恢复：写一个 Magisk service.d 脚本，开机后把上次选的档重新写一遍。
     *
     * 为什么需要：codec 控件在 audioserver 重启（开机）后回到 mixer XML 默认值，
     * 而 APP 不常驻；service.d 由 Magisk 在 late_start 阶段执行，正好补上这一层。
     * 只在用户改过档位时写入，省电档（官方默认）下不生成脚本。
     */
    static void syncBootScript(Context c) {
        String tm = tinymixPath();
        if (tm == null) return;
        int mode = saved(c);
        String script = "/data/adb/service.d/hifictl_tuning.sh";
        if (mode == MODE_SAVE) {
            // 官方默认，不需要自启脚本；清掉旧的避免残留
            RootShell.run("rm -f " + script);
            return;
        }
        String body = "#!/system/bin/sh\n"
                + "# HifiCtl 音质档开机恢复（由 APP 生成，改动请回 APP 里切档）\n"
                + "sleep 20\n"
                + tm + " '" + CTL + "' " + VALUES[mode] + " 2>/dev/null\n";
        String b64 = android.util.Base64.encodeToString(
                body.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                android.util.Base64.NO_WRAP);
        RootShell.Result r = RootShell.run(
                "mkdir -p /data/adb/service.d && echo '" + b64 + "' | base64 -d > " + script
                        + " && chmod 755 " + script);
        Log.i(TAG, "syncBootScript " + VALUES[mode] + " -> " + r);
    }
}
