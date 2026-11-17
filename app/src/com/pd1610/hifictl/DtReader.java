package com.pd1610.hifictl;

import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;

/**
 * 读 device tree 二进制属性。
 *
 * DT 属性有两种形态：
 *  1) 空属性（如 vivo,hifi-support）—— 长度 0，存在即表示支持
 *  2) 大端 u32 数组（如 vivo,wcd-hph-gain = 00 00 00 04 00 00 00 03 ...）
 *
 * 注意：DT 里的数值是【大端】。od 直接看会是 0x04000000 这种"大数"，
 * 必须按大端解析才是 4。
 */
public final class DtReader {
    private static final String TAG = "DtReader";

    public static final String DT_BASE = "/sys/firmware/devicetree/base";

    private DtReader() {}

    /** 读原始字节；不可读返回 null（不是空数组） */
    public static byte[] read(String path) {
        File f = new File(path);
        if (!f.exists()) {
            Log.w(TAG, "not exist: " + path);
            return null;
        }
        if (!f.canRead()) {
            Log.w(TAG, "not readable: " + path);
            return null;
        }
        try (FileInputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toByteArray();
        } catch (Throwable t) {
            Log.e(TAG, "read fail: " + path, t);
            return null;
        }
    }

    /** 按大端 u32 解析成 int[]；不可读或长度非 4 的倍数返回 null */
    public static int[] readBeU32(String path) {
        byte[] b = read(path);
        if (b == null) return null;
        if (b.length == 0) return new int[0];      // 空属性
        if (b.length % 4 != 0) {
            Log.w(TAG, "length not multiple of 4: " + b.length + " @ " + path);
            return null;
        }
        int n = b.length / 4;
        int[] out = new int[n];
        for (int i = 0; i < n; i++) {
            int o = i * 4;
            out[i] = ((b[o] & 0xff) << 24)
                   | ((b[o + 1] & 0xff) << 16)
                   | ((b[o + 2] & 0xff) << 8)
                   | (b[o + 3] & 0xff);
        }
        return out;
    }

    /** 读 C 字符串属性（如 compatible / name） */
    public static String readString(String path) {
        byte[] b = read(path);
        if (b == null || b.length == 0) return null;
        int end = 0;
        while (end < b.length && b[end] != 0) end++;
        return new String(b, 0, end);
    }

    /** 属性是否存在（含空属性） */
    public static boolean exists(String path) {
        return new File(path).exists();
    }

    // ---- 本项目关心的具体属性 ----

    public static String dacCompatible() {
        return readString(DT_BASE + "/soc/i2c@75b6000/dac@48/compatible");
    }

    public static int[] dacParamNum() {
        return readBeU32(DT_BASE + "/soc/i2c@75b6000/dac@48/vivo,param-num");
    }

    public static int[] dacParams() {
        return readBeU32(DT_BASE + "/soc/i2c@75b6000/dac@48/vivo,params");
    }

    public static int[] hphGain() {
        return readBeU32(DT_BASE + "/soc/sound-9335/vivo,wcd-hph-gain");
    }

    public static int[] impedDefs() {
        return readBeU32(DT_BASE + "/soc/sound-9335/vivo,wcd-imped-defs");
    }

    public static boolean hifiSupported() {
        return exists(DT_BASE + "/soc/sound-9335/vivo,hifi-support");
    }

    /** vivo-hifi-codec 节点下的属性清单（12 个） */
    public static String[] hifiCodecProps() {
        File dir = new File(DT_BASE + "/soc/vivo-hifi-codec");
        String[] names = dir.list();
        return names == null ? new String[0] : names;
    }

    /** 把 int[] 转成 "[4, 3, 1, 1]" */
    public static String fmt(int[] a) {
        if (a == null) return "(不可读)";
        if (a.length == 0) return "(空属性)";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < a.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(a[i]);
        }
        return sb.append(']').toString();
    }
}
