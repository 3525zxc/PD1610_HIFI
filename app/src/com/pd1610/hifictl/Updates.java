package com.pd1610.hifictl;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 版本更新：检查 GitHub Releases、下载 APK、root 静默安装、浏览器打开发布页。
 *
 * 检查频率（SharedPreferences "update" / mode）：
 *   0 = 关闭；1 = 每日一次（默认）；2 = 每次打开。
 * 网络全部在子线程，回调切主线程；任何失败静默记录，不影响 APP 主功能。
 */
final class Updates {

    static final String PREF = "update";
    static final String RELEASES_PAGE = "https://github.com/3525zxc/PD1610_HIFI/releases";
    private static final String API_LATEST =
            "https://api.github.com/repos/3525zxc/PD1610_HIFI/releases/latest";

    static final int MODE_OFF = 0;
    static final int MODE_DAILY = 1;
    static final int MODE_LAUNCH = 2;
    private static final long DAY_MS = 24L * 60 * 60 * 1000;

    private Updates() {}

    // ---------------- 存储 ----------------

    static int mode(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE).getInt("mode", MODE_DAILY);
    }

    static void setMode(Context c, int m) {
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putInt("mode", m).apply();
    }

    private static long lastCheck(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE).getLong("last_check", 0L);
    }

    static String latestTag(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString("tag", "");
    }

    static String latestUrl(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString("url", "");
    }

    /** API asset 直链：不经 github.com，国内网络可用 */
    static String latestApiUrl(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString("api_url", "");
    }

    static String latestNotes(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString("notes", "");
    }

    static boolean shouldAutoCheck(Context c) {
        int m = mode(c);
        if (m == MODE_OFF) return false;
        if (m == MODE_LAUNCH) return true;
        return System.currentTimeMillis() - lastCheck(c) >= DAY_MS;
    }

    // ---------------- 当前版本 ----------------

    static String currentVersion(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "2.4";
        }
    }

    static boolean hasUpdate(Context c) {
        String latest = latestTag(c);
        return latest != null && !latest.isEmpty() && compare(latest, currentVersion(c)) > 0;
    }

    /** 版本比较：a > b 返回正数。支持 v2.4 / 2.4.1 / 1.1-glass 等形式。 */
    static int compare(String a, String b) {
        int[] aa = nums(a), bb = nums(b);
        int n = Math.max(aa.length, bb.length);
        for (int i = 0; i < n; i++) {
            int x = i < aa.length ? aa[i] : 0;
            int y = i < bb.length ? bb[i] : 0;
            if (x != y) return x - y;
        }
        return 0;
    }

    private static int[] nums(String v) {
        if (v == null) return new int[0];
        String s = v.trim().replaceFirst("^[vV]", "");
        String[] parts = s.split("[^0-9]+");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                out[i] = Integer.parseInt(parts[i]);
            } catch (Throwable ignored) {
                out[i] = 0;
            }
        }
        return out;
    }

    // ---------------- 检查更新 ----------------

    interface CheckCb {
        void done(String tag, String url, String notes, String error);
    }

    static void check(final Context ctx, final CheckCb cb) {
        final Context app = ctx.getApplicationContext();
        new Thread(() -> {
            String tag = null, url = null, notes = "", err = null, apiUrl = "";
            HttpURLConnection cn = null;
            try {
                cn = (HttpURLConnection) new URL(API_LATEST).openConnection();
                cn.setConnectTimeout(8000);
                cn.setReadTimeout(15000);
                cn.setRequestProperty("Accept", "application/vnd.github+json");
                cn.setRequestProperty("User-Agent", "HifiCtl-Updater");
                int rc = cn.getResponseCode();
                if (rc != 200) throw new java.io.IOException("HTTP " + rc);
                String json = read(cn.getInputStream());
                JSONObject o = new JSONObject(json);
                tag = o.optString("tag_name", "");
                notes = o.optString("body", "");
                JSONArray assets = o.optJSONArray("assets");
                if (assets != null) {
                    for (int i = 0; i < assets.length(); i++) {
                        JSONObject a = assets.getJSONObject(i);
                        String name = a.optString("name", "");
                        if (name.endsWith(".apk")) {
                            url = a.optString("browser_download_url", "");
                            // api.github.com 的 asset 地址会 302 到 objects.githubusercontent.com，
                            // 国内 github.com 常被墙但 api 域通常可达
                            apiUrl = a.optString("url", "");
                            break;
                        }
                    }
                }
                if (tag.isEmpty() || (url.isEmpty() && apiUrl.isEmpty())) {
                    throw new java.io.IOException("响应缺少 tag 或 APK 地址");
                }
            } catch (Throwable t) {
                err = t.getMessage() == null ? t.toString() : t.getMessage();
            } finally {
                if (cn != null) cn.disconnect();
            }
            final String T = tag, U = url, N = notes, E = err, A = apiUrl;
            if (E == null) {
                app.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
                        .putLong("last_check", System.currentTimeMillis())
                        .putString("tag", T).putString("url", U)
                        .putString("api_url", A).putString("notes", N).apply();
            }
            new Handler(Looper.getMainLooper()).post(() -> cb.done(T, U, N, E));
        }, "hifictl-check").start();
    }

    // ---------------- 下载 ----------------

    interface DlCb {
        void progress(int pct, long done, long total);
        void done(boolean ok, String pathOrError);
    }

    static void download(final Context ctx, final String url, final DlCb cb) {
        final Context app = ctx.getApplicationContext();
        new Thread(() -> {
            File out = null;
            String err = null;
            HttpURLConnection cn = null;
            try {
                File dir = app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                if (dir == null) dir = app.getFilesDir();
                if (!dir.exists() && !dir.mkdirs()) throw new java.io.IOException("无法创建下载目录");
                out = new File(dir, "HifiCtl-update.apk");
                cn = (HttpURLConnection) new URL(url).openConnection();
                cn.setConnectTimeout(8000);
                cn.setReadTimeout(20000);
                cn.setRequestProperty("User-Agent", "HifiCtl-Updater");
                // 走 api.github.com 的 asset 接口时必须是 octet-stream，否则返回 302/JSON
                if (url.contains("api.github.com")) {
                    cn.setRequestProperty("Accept", "application/octet-stream");
                }
                cn.setInstanceFollowRedirects(true);
                int rc = cn.getResponseCode();
                if (rc != 200) throw new java.io.IOException("HTTP " + rc);
                final long total = cn.getContentLengthLong();
                InputStream in = cn.getInputStream();
                OutputStream os = new FileOutputStream(out);
                byte[] buf = new byte[8192];
                long done = 0L;
                int n;
                while ((n = in.read(buf)) > 0) {
                    os.write(buf, 0, n);
                    done += n;
                    final long d = done;
                    final int pct = total > 0 ? (int) (d * 100 / total) : -1;
                    new Handler(Looper.getMainLooper()).post(() -> cb.progress(pct, d, total));
                }
                os.flush();
                os.close();
                in.close();
                if (total > 0 && done < total) throw new java.io.IOException("下载不完整");
            } catch (Throwable t) {
                err = t.getMessage() == null ? t.toString() : t.getMessage();
                if (out != null) out.delete();
            } finally {
                if (cn != null) cn.disconnect();
                final String path = (err == null && out != null) ? out.getAbsolutePath() : null;
                final String fe = err;
                new Handler(Looper.getMainLooper()).post(() -> cb.done(path != null, path != null ? path : fe));
            }
        }, "hifictl-download").start();
    }

    /** 后台静默安装（Magisk 环境）。
     *
     *  关键：安装本 APP 时 PackageManager 会杀掉当前进程，若安装是其子进程会被一起终止，
     *  所以必须把 pm install 放到独立会话里延迟执行（setsid + sleep），让它在 APP 退出后继续跑。
     *  返回是否成功把安装任务挂到后台。 */
    static boolean installDetached(String path) {
        String tmp = "/data/local/tmp/hifictl_update.apk";
        String cmd = "cp -f '" + path + "' " + tmp + " && chmod 644 " + tmp
                + " && setsid sh -c 'sleep 3; pm install -r " + tmp + "' >/dev/null 2>&1 &";
        RootShell.Result r = RootShell.run(cmd);
        android.util.Log.i("HifiCtlUpdate", "installDetached rc=" + r.code + " " + r.err);
        return r.ok();
    }

    static void openBrowser(Activity a) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(RELEASES_PAGE));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            a.startActivity(i);
        } catch (Throwable t) {
            Toast.makeText(a, "没有可用的浏览器", Toast.LENGTH_SHORT).show();
        }
    }

    private static String read(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] b = new byte[4096];
        int n;
        while ((n = in.read(b)) > 0) bo.write(b, 0, n);
        in.close();
        return bo.toString("UTF-8");
    }
}
