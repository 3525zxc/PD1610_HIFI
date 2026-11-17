package com.pd1610.hifictl;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.TranslateAnimation;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 版本更新面板（自绘底部 Sheet，沿用 ConfirmSheet 的退场防双击思路）。
 *
 * showUpdate  ：关于页入口。显示当前/最新版本，两个选项——本地更新（面板内进度）+ 浏览器更新。
 * showSettings：右上角入口。检查频率三选一：关闭 / 每日一次（默认）/ 每次打开。
 */
final class UpdateSheet {

    private static final int ANIM_MS = 220;

    private final FrameLayout overlay;
    private final View panel;
    private boolean dismissed = false;

    private UpdateSheet(FrameLayout overlay, View panel) {
        this.overlay = overlay;
        this.panel = panel;
    }

    // ============================================================
    // 公共骨架
    // ============================================================

    private static UpdateSheet mount(Activity a, Wds w, String title, LinearLayout content) {
        View rootView = a.findViewById(android.R.id.content);
        if (!(rootView instanceof ViewGroup)) return null;
        final ViewGroup host = (ViewGroup) rootView;

        final FrameLayout overlay = new FrameLayout(a);
        overlay.setClickable(true);
        overlay.setFocusable(true);

        View scrim = new View(a);
        scrim.setBackgroundColor(0x99000000);
        scrim.setClickable(true);
        overlay.addView(scrim, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout panel = new LinearLayout(a);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setClickable(true);
        panel.setBackground(w.topRounded(Wds.SURFACE_2, Wds.R_SHEET));
        int px = w.dp(20);
        panel.setPadding(px, w.dp(18), px, w.dp(24));

        panel.addView(w.title(title), w.lpw());
        w.gap(panel, 14);
        panel.addView(content, w.lpw());

        final UpdateSheet holder = new UpdateSheet(overlay, panel);

        overlay.addView(panel, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

        scrim.setOnClickListener(v -> {
            if (holder.dismissed) return;
            Haptics.tap(v);
            holder.dismiss();
        });
        host.addView(overlay, new ViewGroup.LayoutParams(-1, -1));

        AlphaAnimation fade = new AlphaAnimation(0f, 1f);
        fade.setDuration(ANIM_MS);
        scrim.startAnimation(fade);
        TranslateAnimation slide = new TranslateAnimation(
                Animation.RELATIVE_TO_SELF, 0f, Animation.RELATIVE_TO_SELF, 0f,
                Animation.RELATIVE_TO_SELF, 1f, Animation.RELATIVE_TO_SELF, 0f);
        slide.setDuration(ANIM_MS);
        slide.setInterpolator(new DecelerateInterpolator());
        panel.startAnimation(slide);
        return holder;
    }

    boolean isShowing() {
        return !dismissed && overlay.getParent() != null;
    }

    void dismiss() {
        if (dismissed) return;
        dismissed = true;
        overlay.setClickable(false);
        TranslateAnimation slide = new TranslateAnimation(
                Animation.RELATIVE_TO_SELF, 0f, Animation.RELATIVE_TO_SELF, 0f,
                Animation.RELATIVE_TO_SELF, 0f, Animation.RELATIVE_TO_SELF, 1f);
        slide.setDuration(ANIM_MS);
        slide.setInterpolator(new DecelerateInterpolator());
        slide.setAnimationListener(new Animation.AnimationListener() {
            public void onAnimationStart(Animation a) {}
            public void onAnimationEnd(Animation a) { detach(); }
            public void onAnimationRepeat(Animation a) {}
        });
        panel.startAnimation(slide);
        new Handler(Looper.getMainLooper()).postDelayed(this::detach, ANIM_MS + 80);
    }

    private void detach() {
        Object parent = overlay.getParent();
        if (parent instanceof ViewGroup) ((ViewGroup) parent).removeView(overlay);
    }

    // ============================================================
    // 版本更新面板
    // ============================================================

    static UpdateSheet showUpdate(Activity a, Wds w) {
        LinearLayout content = w.column();
        TextView status = w.body("正在检查更新…");
        content.addView(status, w.lpw());

        LinearLayout actions = w.row();
        content.addView(actions, w.lpw());

        final UpdateSheet[] ref = new UpdateSheet[1];
        ref[0] = mount(a, w, "版本更新", content);
        if (ref[0] == null) return null;

        TextView cur = w.caption("当前版本 v" + Updates.currentVersion(a));
        LinearLayout.LayoutParams curLp = w.lpw();
        curLp.topMargin = w.dp(4);
        content.addView(cur, curLp);

        TextView note = w.caption("");
        LinearLayout.LayoutParams noteLp = w.lpw();
        noteLp.topMargin = w.dp(6);
        content.addView(note, noteLp);

        TextView tip = w.caption("本地更新走 api.github.com（国内通常可用）；浏览器更新需能打开 github.com，打不开请挂代理");
        LinearLayout.LayoutParams tipLp = w.lpw();
        tipLp.topMargin = w.dp(8);
        content.addView(tip, tipLp);

        // 渲染版本状态 + 两个操作按钮（检查完成后或已有缓存时）
        final Runnable render = () -> {
            if (ref[0].dismissed) return;
            String latest = Updates.latestTag(a);
            boolean has = Updates.hasUpdate(a);
            if (latest == null || latest.isEmpty()) {
                status.setText("检查失败，可用浏览器打开发布页查看");
                status.setTextColor(Wds.WARN);
            } else if (has) {
                status.setText("发现新版本 " + latest);
                status.setTextColor(Wds.GREEN);
            } else {
                status.setText("当前已是最新版本（发布页 " + latest + "）");
                status.setTextColor(Wds.TEXT_2);
            }
            String n = Updates.latestNotes(a);
            if (n != null && n.length() > 160) n = n.substring(0, 160) + "…";
            note.setText(plain(n));

            actions.removeAllViews();
            actions.addView(primaryBtn(w, "本地更新", v -> doLocalUpdate(a, w, ref[0], actions, status, note)));
            w.hgap(actions, 12);
            actions.addView(ghostBtn(w, "浏览器更新", v -> Updates.openBrowser(a)));
        };

        // 每次打开面板都重新检查一次（用户手动入口，即时反映远端）
        Updates.check(a, (tag, url, notes, err) -> render.run());
        return ref[0];
    }

    private static void doLocalUpdate(Activity a, Wds w, UpdateSheet sheet,
                                      LinearLayout actions, TextView status, TextView note) {
        if (sheet.dismissed) return;
        String url = Updates.latestApiUrl(a);
        if (url == null || url.isEmpty()) url = Updates.latestUrl(a);
        if (url == null || url.isEmpty()) {
            Toast.makeText(a, "还没有可用的下载地址，请先检查更新", Toast.LENGTH_SHORT).show();
            return;
        }
        final String dlUrl = url;
        // 降级保护：发布页版本不比当前新时不执行本地安装
        String latest = Updates.latestTag(a);
        if (latest != null && !latest.isEmpty()
                && Updates.compare(latest, Updates.currentVersion(a)) <= 0) {
            Toast.makeText(a, "当前已是最新版本，无需本地更新", Toast.LENGTH_SHORT).show();
            return;
        }
        actions.removeAllViews();
        status.setText("正在下载更新包…");
        status.setTextColor(Wds.TEXT_2);
        note.setText("");

        Bar bar = new Bar(a, w);
        TextView pct = w.mono("0%");
        LinearLayout row = w.row();
        row.addView(bar, new LinearLayout.LayoutParams(0, w.dp(10), 1f));
        w.hgap(row, 10);
        row.addView(pct);
        actions.addView(row, w.lpw());

        Updates.download(a, dlUrl, new Updates.DlCb() {
            public void progress(int p, long done, long total) {
                if (sheet.dismissed) return;
                if (p >= 0) {
                    bar.set(p);
                    pct.setText(p + "%");
                } else {
                    pct.setText((done / 1024) + " KB");
                }
            }
            public void done(boolean ok, String pathOrError) {
                if (sheet.dismissed) return;
                if (!ok) {
                    // API 通道失败时回退到 browser_download_url 再试一次
                    String alt = Updates.latestUrl(a);
                    if (alt != null && !alt.isEmpty() && !alt.equals(dlUrl)) {
                        status.setText("API 通道失败，改用直链重试…");
                        doLocalUpdateWith(a, w, sheet, actions, status, note, alt, false);
                        return;
                    }
                    status.setText("下载失败：" + pathOrError);
                    status.setTextColor(Wds.DANGER);
                    actions.removeAllViews();
                    actions.addView(ghostBtn(w, "重试", v -> doLocalUpdate(a, w, sheet, actions, status, note)));
                    return;
                }
                bar.set(100);
                pct.setText("100%");
                status.setText("下载完成，3 秒后自动安装…");
                status.setTextColor(Wds.GREEN);
                boolean queued = Updates.installDetached(pathOrError);
                Toast.makeText(a, queued ? "即将安装，APP 会自动关闭，装完请重新打开"
                                : "安装启动失败，请改用浏览器更新",
                        Toast.LENGTH_LONG).show();
                if (queued) {
                    new Handler(Looper.getMainLooper()).postDelayed(sheet::dismiss, 1200);
                } else {
                    actions.removeAllViews();
                    actions.addView(ghostBtn(w, "浏览器更新", v -> Updates.openBrowser(a)));
                }
            }
        });
    }

    /** 带指定 URL 的下载（回退用） */
    private static void doLocalUpdateWith(Activity a, Wds w, UpdateSheet sheet, LinearLayout actions,
                                          TextView status, TextView note, String url, boolean allowFallback) {
        if (sheet.dismissed) return;
        Updates.download(a, url, new Updates.DlCb() {
            public void progress(int p, long done, long total) {
                if (sheet.dismissed) return;
                if (p >= 0) status.setText("正在下载（直链）… " + p + "%");
            }
            public void done(boolean ok, String pathOrError) {
                if (sheet.dismissed) return;
                if (!ok) {
                    status.setText("下载失败：" + pathOrError);
                    status.setTextColor(Wds.DANGER);
                    actions.removeAllViews();
                    actions.addView(ghostBtn(w, "浏览器更新", v -> Updates.openBrowser(a)));
                    return;
                }
                status.setText("下载完成，3 秒后自动安装…");
                status.setTextColor(Wds.GREEN);
                boolean queued = Updates.installDetached(pathOrError);
                Toast.makeText(a, queued ? "即将安装，APP 会自动关闭，装完请重新打开"
                                : "安装启动失败，请改用浏览器更新",
                        Toast.LENGTH_LONG).show();
                if (queued) {
                    new Handler(Looper.getMainLooper()).postDelayed(sheet::dismiss, 1200);
                } else {
                    actions.removeAllViews();
                    actions.addView(ghostBtn(w, "浏览器更新", v -> Updates.openBrowser(a)));
                }
            }
        });
    }

    // ============================================================
    // 检查频率设置面板
    // ============================================================

    static UpdateSheet showSettings(Activity a, Wds w, Runnable onChanged) {
        LinearLayout content = w.column();
        content.addView(w.body("选择自动检查更新的频率"), w.lpw());
        TextView hint = w.caption("发现新版本时，右上角更新按钮会亮起提示");
        LinearLayout.LayoutParams hintLp = w.lpw();
        hintLp.topMargin = w.dp(4);
        content.addView(hint, hintLp);
        w.gap(content, 10);

        final int[] options = {Updates.MODE_OFF, Updates.MODE_DAILY, Updates.MODE_LAUNCH};
        final String[] labels = {"关闭更新检查", "每日检查一次（默认）", "每次打开都检查"};
        final TextView[] rows = new TextView[3];
        final UpdateSheet[] ref = new UpdateSheet[1];

        LinearLayout list = w.cardBleed();
        for (int i = 0; i < options.length; i++) {
            final int idx = i;
            TextView row = w.list(labels[i]);
            row.setMinimumHeight(w.dp(Wds.ROW_SHORT));
            int pad = w.dp(Wds.CARD_PAD);
            row.setPadding(pad, 0, pad, 0);
            w.ink(row, 0f);
            final int mode = options[i];
            row.setOnClickListener(v -> {
                if (ref[0].dismissed) return;
                Haptics.tap(v);
                Updates.setMode(a, mode);
                paintOptions(rows, options);
                if (onChanged != null) onChanged.run();
                new Handler(Looper.getMainLooper()).postDelayed(() -> ref[0].dismiss(), 180);
            });
            rows[i] = row;
            if (i > 0) list.addView(w.divider(16));
            list.addView(row, w.lpw());
        }
        content.addView(list, w.lpw());
        paintOptions(rows, options);

        ref[0] = mount(a, w, "更新检查设置", content);
        return ref[0];
    }

    private static void paintOptions(TextView[] rows, int[] options) {
        for (int i = 0; i < rows.length; i++) {
            if (rows[i] == null) continue;
            boolean on = Updates.mode(rows[i].getContext()) == options[i];
            rows[i].setTextColor(on ? Wds.GREEN_DEEP : Wds.TEXT);
        }
    }

    /** 把 Release 正文的 Markdown 标记剥掉，保留可读纯文本。 */
    private static String plain(String s) {
        if (s == null) return "";
        s = s.replace("\r", "")
                .replace("**", "")
                .replace("`", "");
        StringBuilder out = new StringBuilder();
        for (String line : s.split("\n")) {
            String t = line.trim();
            if (t.isEmpty()) continue;
            if (t.startsWith("### ")) t = t.substring(4);
            else if (t.startsWith("## ")) t = t.substring(3);
            else if (t.startsWith("# ")) t = t.substring(2);
            else if (t.startsWith("- ")) t = "· " + t.substring(2);
            else if (t.startsWith("> ")) t = t.substring(2);
            out.append(t).append('\n');
        }
        return out.toString().trim();
    }

    // ============================================================
    // 按钮 / 进度条
    // ============================================================

    private static TextView primaryBtn(Wds w, String text, View.OnClickListener l) {
        TextView t = w.text(text, Wds.SP_BODY, Wds.ON_GREEN, Wds.MED);
        t.setGravity(Gravity.CENTER);
        t.setBackground(w.ripple(w.solid(Wds.GREEN, Wds.R_BTN), Wds.RIPPLE_DARK, Wds.R_BTN));
        t.setClickable(true);
        t.setLayoutParams(new LinearLayout.LayoutParams(0, w.dp(44), 1f));
        t.setOnClickListener(l);
        return t;
    }

    private static TextView ghostBtn(Wds w, String text, View.OnClickListener l) {
        TextView t = w.text(text, Wds.SP_BODY, Wds.TEXT, Wds.MED);
        t.setGravity(Gravity.CENTER);
        t.setBackground(w.ripple(w.stroke(0x00000000, Wds.HAIRLINE, Wds.R_BTN), Wds.RIPPLE, Wds.R_BTN));
        t.setClickable(true);
        t.setLayoutParams(new LinearLayout.LayoutParams(0, w.dp(44), 1f));
        t.setOnClickListener(l);
        return t;
    }

    /** 水平进度条：轨道 TRACK_OFF + 填充 GREEN_DEEP，圆角。 */
    private static final class Bar extends View {
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int pct = 0;
        private final float r;

        Bar(Context c, Wds w) {
            super(c);
            track.setColor(Wds.TRACK_OFF);
            fill.setColor(Wds.GREEN_DEEP);
            r = w.dpf(5);
        }

        void set(int p) {
            pct = Math.max(0, Math.min(100, p));
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int h = getHeight(), wd = getWidth();
            if (wd <= 0 || h <= 0) return;
            canvas.drawRoundRect(0, 0, wd, h, r, r, track);
            float right = wd * pct / 100f;
            if (right > 0) canvas.drawRoundRect(0, 0, right, h, r, r, fill);
        }
    }
}
