package com.pd1610.hifictl;

import android.content.Context;
import android.media.AudioManager;
import android.util.Log;

import java.lang.reflect.Method;

/**
 * HIFI 控制核心。
 *
 * 三条通路：
 *  1) setParameters(String)        —— 实时，走 HAL，无需 root（@hide，反射）
 *  2) AudioSystem.setParameters    —— 备用（更底层，同样 @hide）
 *  3) writeMixerXml                —— 持久化，需 root，改 /vendor/etc/mixer_paths*.xml
 *
 * HAL 实测支持的 key（strings /vendor/lib64/hw/audio.primary.msm8996.so）：
 *   hifi-playback / hifi-record / hifi_i2s_clock_rate / dsdvol_index / DSDEnable
 *
 * 重要：这些 key 的确切语义与取值必须用 logcat 验证（见指导清单阶段 A）。
 * 本类给出的是"按已观测字符串推断"的调用方式，未在实机验证前不要当作结论。
 */
public final class HifiController {
    private static final String TAG = "HifiCtl";

    /** 官方默认值（来自 mixer_paths_tasha.PD1610.xml） */
    public static final int DSD_VOLUME_DEFAULT = 19;
    public static final int[] HPH_GAIN_DEFAULT = {4, 3, 1, 1};

    private final AudioManager am;

    public HifiController(Context ctx) {
        am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
    }

    // ---------------- 通路 1：AudioManager.setParameters ----------------

    /**
     * 反射调用 AudioManager.setParameters(String)。
     * 该方法在 AOSP 中标 @hide，但运行时存在。
     */
    public boolean setParameters(String kv) {
        if (am == null) {
            Log.e(TAG, "AudioManager is null");
            return false;
        }
        try {
            Method m = AudioManager.class.getMethod("setParameters", String.class);
            m.setAccessible(true);
            Object ret = m.invoke(am, kv);
            Log.i(TAG, "setParameters OK: " + kv + " -> " + ret);
            return true;
        } catch (NoSuchMethodException e) {
            Log.e(TAG, "setParameters not found (API level too low?)", e);
            return false;
        } catch (Throwable t) {
            Log.e(TAG, "setParameters FAIL: " + kv, t);
            return false;
        }
    }

    /** 读回参数（AudioManager.getParameters 同样是 @hide） */
    public String getParameters(String keys) {
        if (am == null) return "";
        try {
            Method m = AudioManager.class.getMethod("getParameters", String.class);
            m.setAccessible(true);
            Object ret = m.invoke(am, keys);
            return ret == null ? "" : ret.toString();
        } catch (Throwable t) {
            Log.e(TAG, "getParameters FAIL: " + keys, t);
            return "";
        }
    }

    // ---------------- 业务封装 ----------------

    /** HIFI 模式：off / hifi / dsd
     *
     * 实测（2026-11-17，PD1610 + LOS16）：
     *   - 真正生效的开关是 enable_hifi (0/1)，不是 hifi-playback
     *   - 改完必须重建输出流（停止播放再播）才会应用 headphone-hifi path
     *   - DSDEnable 目前未验证出有效写法（HAL 解析 value 为空），先保留调用
     */
    public boolean setHifiMode(String mode) {
        if (mode == null) return false;
        switch (mode.toLowerCase()) {
            case "off":
                return setParameters("enable_hifi=0");
            case "hifi":
                return setParameters("enable_hifi=1");
            case "dsd":
                setParameters("enable_hifi=1");
                return setParameters("DSDEnable=1");
            default:
                Log.w(TAG, "unknown mode: " + mode);
                return false;
        }
    }

    /** DSD 音量：0..31，官方默认 19 */
    public boolean setDsdVolume(int v) {
        v = clamp(v, 0, 31);
        return setParameters("dsdvol_index=" + v);
    }

    /** I2S 时钟（Hz）；取值需实测，常见 44100 倍数 */
    public boolean setI2sClockRate(int hz) {
        return setParameters("hifi_i2s_clock_rate=" + hz);
    }

    public boolean setHifiPlayback(boolean on) {
        return setParameters("enable_hifi=" + (on ? 1 : 0));
    }

    // ---------------- 状态读取 ----------------

    /** 当前 HIFI 状态：从 HAL 读 enable_hifi（返回 "true"/"false"） */
    public String currentHifiState() {
        String s = getParameters("enable_hifi");
        if (s == null) return "";
        int i = s.indexOf('=');
        return i >= 0 ? s.substring(i + 1).trim() : s.trim();
    }

    // ---------------- DT 只读封装 ----------------

    public int[] readHphGain() {
        return DtReader.hphGain();
    }

    public int[] readImpedDefs() {
        return DtReader.impedDefs();
    }

    public int[] readDacParams() {
        return DtReader.dacParams();
    }

    public int[] readDacParamNum() {
        return DtReader.dacParamNum();
    }

    public String readDacCompatible() {
        return DtReader.dacCompatible();
    }

    public boolean isHifiSupported() {
        return DtReader.hifiSupported();
    }

    // ---------------- 持久化（需 root） ----------------

    /** mixer 文件在 LOS16 的实际路径（/vendor -> /system/vendor） */
    public static final String MIXER_PD1610 = "/vendor/etc/mixer_paths_tasha.PD1610.xml";

    /** 备份当前 mixer 到 /data/local/tmp（需 root） */
    public boolean backupMixer() {
        RootShell.Result r = RootShell.run(
                "cp -f " + MIXER_PD1610 + " /data/local/tmp/mixer_paths_tasha.PD1610.xml.bak && " +
                "md5sum /data/local/tmp/mixer_paths_tasha.PD1610.xml.bak");
        Log.i(TAG, "backupMixer: " + r);
        return r.ok();
    }

    /** 从备份恢复（需 root） */
    public boolean restoreMixer() {
        RootShell.Result r = RootShell.run(
                "cp -f /data/local/tmp/mixer_paths_tasha.PD1610.xml.bak " + MIXER_PD1610 + " && " +
                "md5sum " + MIXER_PD1610);
        Log.i(TAG, "restoreMixer: " + r);
        return r.ok();
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
