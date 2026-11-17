package com.pd1610.hifictl;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * su 命令执行封装。
 *
 * 注意：本类只用于【需要 root 的少数操作】（写 mixer 文件、读受保护分区）。
 * 读 DT 参数、调 AudioManager 都不需要 root。
 */
public final class RootShell {

    public static final class Result {
        public final int code;
        public final String out;
        public final String err;

        Result(int c, String o, String e) {
            code = c;
            out = o;
            err = e;
        }

        public boolean ok() {
            return code == 0;
        }

        @Override
        public String toString() {
            return "rc=" + code + "\n--- stdout ---\n" + out + "\n--- stderr ---\n" + err;
        }
    }

    private RootShell() {}

    /** 执行一条 su 命令（单条，复杂命令请写成脚本再 sh 执行） */
    public static Result run(String cmd) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            String out = drain(p.getInputStream());
            String err = drain(p.getErrorStream());
            int c = p.waitFor();
            return new Result(c, out, err);
        } catch (Throwable t) {
            return new Result(-1, "", t.toString());
        }
    }

    /** 执行设备上的脚本文件（推荐：把多条命令写成 .sh 推到 /data/local/tmp） */
    public static Result runScript(String path) {
        return run("sh " + path);
    }

    private static String drain(java.io.InputStream in) {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in))) {
            String l;
            while ((l = r.readLine()) != null) sb.append(l).append('\n');
        } catch (Throwable ignored) {
        }
        return sb.toString();
    }

    /** 是否已有 root（su 可用且 uid=0） */
    public static boolean hasRoot() {
        Result r = run("id");
        return r.ok() && r.out.contains("uid=0");
    }

    /** 读文件内容（需要 root 的路径用这个） */
    public static String cat(String path) {
        Result r = run("cat '" + path + "'");
        return r.ok() ? r.out : "";
    }

    /** 写文件：把 content 写到 path（覆盖），并设置权限 */
    public static boolean write(String path, String content, String mode) {
        // 用 base64 避免引号/换行转义问题
        String b64 = android.util.Base64.encodeToString(
                content.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                android.util.Base64.NO_WRAP);
        String cmd = "echo '" + b64 + "' | base64 -d > '" + path + "' && chmod " + mode + " '" + path + "'";
        return run(cmd).ok();
    }
}
