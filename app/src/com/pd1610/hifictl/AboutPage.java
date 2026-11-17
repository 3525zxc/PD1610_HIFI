package com.pd1610.hifictl;

import android.app.Activity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 页3 关于：品牌块 + 4 个折叠 FAQ + 致谢块（致谢必须在页面最靠下）。
 */
final class AboutPage {

    private AboutPage() {
    }

    static View build(Activity host, Wds w) {
        LinearLayout page = w.column();
        w.gap(page, 12);

        // ---- 品牌块 ----
        LinearLayout brand = w.card();
        brand.addView(w.title("HIFI控制器"), w.lpw());
        TextView ver = w.body("v" + Updates.currentVersion(host) + " · com.pd1610.hifictl");
        LinearLayout.LayoutParams verLp = w.lpw();
        verLp.topMargin = w.dp(4);
        brand.addView(ver, verLp);
        TextView desc = w.body("只调 HAL 运行参数并备份 mixer，不改系统分区、不刷机。");
        LinearLayout.LayoutParams descLp = w.lpw();
        descLp.topMargin = w.dp(4);
        brand.addView(desc, descLp);
        page.addView(brand, w.cardLp());

        // ---- 版本更新入口 ----
        page.addView(Widgets.section(host, w, "版本更新"));
        LinearLayout updCard = w.cardBleed();
        Rows.ListRow upd = new Rows.ListRow(host, w)
                .title("检查更新")
                .subtitle("当前 v" + Updates.currentVersion(host)
                        + (Updates.hasUpdate(host) ? " · 发现新版本 " + Updates.latestTag(host) : " · 已是最新"))
                .dot(Updates.hasUpdate(host) ? Wds.GREEN : Wds.GREEN_DEEP)
                .chevron();
        upd.setClickable(true);
        upd.setOnClickListener(v -> {
            Haptics.tap(v);
            UpdateSheet.showUpdate(host, w);
        });
        updCard.addView(upd, w.lpw());
        page.addView(updCard, w.cardLp());

        // ---- 常见问题 ----
        page.addView(Widgets.section(host, w, "常见问题"));
        LinearLayout faq = w.cardBleed();
        faq.addView(faqDsd(host, w));
        faq.addView(w.divider(16));
        faq.addView(faqNoChange(host, w));
        faq.addView(w.divider(16));
        faq.addView(faqFirmware(host, w));
        faq.addView(w.divider(16));
        faq.addView(faqRisk(host, w));
        page.addView(faq, w.cardLp());

        // ---- 致谢（最靠下） ----
        page.addView(Widgets.section(host, w, "致谢"));
        page.addView(thanks(host, w), w.cardLp());

        w.gap(page, 24);
        return page;
    }

    // ---------------- 4 个 FAQ ----------------

    private static Rows.Collapsible faqDsd(Activity host, Wds w) {
        Rows.Collapsible c = new Rows.Collapsible(host, w, "为什么点 DSD 没反应");
        LinearLayout b = c.body();
        para(w, b, "2026-11-17 逐层实测的结果：");
        mono(w, b, "1. DSDEnable=1        → 无效，path 仍 headphone-hifi");
        mono(w, b, "2. tinymix 置 DSD 控件 → 控件变了，HAL 不认");
        mono(w, b, "3. AudioTrack DSD     → Invalid encoding");
        mono(w, b, "   (AUDIO_FORMAT_DSD = 0x1A000000)");
        w.gap(b, 8);
        para(w, b, "HAL 内部逻辑（strings 实证）：\n"
                + "always select HiFi DSD when its thread is writing data");
        w.gap(b, 8);
        para(w, b, "结论：DSD 由「输出流本身是 DSD 格式」触发，不是参数开关。"
                + "而 Java 层 AudioFormat 白名单拒绝 DSD 格式，普通 APP 建不了 DSD 流。");
        caption(w, b, "要听 DSD 请用支持原生 DSD 直出的播放器（如 Neutron / USB Audio Player Pro），"
                + "并在其设置中开启 Hi-Res / DSD 输出。");
        return c;
    }

    private static Rows.Collapsible faqNoChange(Activity host, Wds w) {
        Rows.Collapsible c = new Rows.Collapsible(host, w, "切换后为什么听不出变化");
        LinearLayout b = c.body();
        para(w, b, "enable_hifi 只是 HAL 参数，HAL 在「开始播放」时才按它应用 mixer path。"
                + "如果正在播放，切换不会立刻生效。");
        w.gap(b, 8);
        para(w, b, "正确的验证顺序：");
        mono(w, b, "1. 控制页切到 HiFi");
        mono(w, b, "2. 完全退出播放器（或暂停后重开）");
        mono(w, b, "3. 回到控制页，副标应显示「正在生效」");
        w.gap(b, 8);
        para(w, b, "如果副标一直是「HAL 未回报」，说明 HAL 没有接受这次写入，"
                + "请在结果条上点「我已重开播放」重新对账。");
        caption(w, b, "蓝牙、部分第三方播放器与通话通路会绕开 headphone-hifi，不在此参数覆盖范围内。");
        return c;
    }

    private static Rows.Collapsible faqFirmware(Activity host, Wds w) {
        Rows.Collapsible c = new Rows.Collapsible(host, w, "两版固件差异");
        LinearLayout b = c.body();
        para(w, b, "目标文件 mixer_paths_tasha.PD1610.xml");
        mono(w, b, "1.15.1  (6.0.1)  118,532 B  3,145 行  @ /etc/");
        mono(w, b, "8.19.16 (8.1)    119,329 B  3,176 行  @ /vendor/etc/");
        w.gap(b, 8);
        para(w, b, "差异 195 行，全为 MultiMedia3/8 通路编号、EC Reference、HDMI 等平台扩展；"
                + "headphone-hifi / headphone-hifi-DSD 两条通路逐字节一致，"
                + "因此不存在「两版调音不同」这回事，两版可以共用同一套参数。");
        w.gap(b, 8);
        para(w, b, "真正能改变听感的是耳放工作模式（控制页「音质档」）：");
        mono(w, b, "CLS_H_LP    官方默认 · 省电");
        mono(w, b, "CLS_H_HIFI  耳放推满 · 耗电略高");
        w.gap(b, 8);
        mono(w, b, "HiFi Mode Switch        Off / HiFi / DSD");
        mono(w, b, "DSD SampleRate Switch   Off / On");
        mono(w, b, "HiFi DSD Volume Switch  19（0..31）");
        mono(w, b, "SmartPA Switch          0 / 1");
        mono(w, b, "HPHL / HPHR Volume      14 / 20");
        return c;
    }

    private static Rows.Collapsible faqRisk(Activity host, Wds w) {
        Rows.Collapsible c = new Rows.Collapsible(host, w, "风险与回滚");
        LinearLayout b = c.body();
        para(w, b, "· setParameters 是 @hide API，不同 ROM 行为可能不同");
        para(w, b, "· 改 mixer 会影响所有音频路径（含通话与免提外放）");
        para(w, b, "· 任何异常请先在设置页执行「从备份恢复 mixer」，再重启手机");
        w.gap(b, 8);
        para(w, b, "回滚步骤：");
        mono(w, b, "1. 设置 → 维护 → 从备份恢复 mixer");
        mono(w, b, "2. 重启手机");
        mono(w, b, "3. 备份文件 /data/local/tmp/mixer_paths_tasha.PD1610.xml.bak");
        caption(w, b, "本 APP 不修改系统分区、不刷机；所有写入都发生在 /vendor 的 mixer 文件与其备份上。");
        return c;
    }

    // ---------------- 致谢 ----------------

    private static View thanks(Activity host, Wds w) {
        LinearLayout card = w.card();
        card.addView(w.body("一起日日夜夜，共同进步和研究的小伙伴们"), w.lpw());
        w.gap(card, 12);
        card.addView(creditRow(host, w, "qj.jpg", "切", "@切姜"));
        card.addView(creditRow(host, w, "al.jpg", "A", "@Alexllinois"));
        card.addView(creditRow(host, w, "an.jpg", "氨", "@氨基不酸"));
        w.gap(card, 12);
        card.addView(w.caption("感谢你们在 PD1610 HIFI 移植与调音上的日日夜夜。"));
        return card;
    }

    /** 圆形真人头像（assets/avatars）+ 名字；不可点、无 ripple、无 chevron */
    private static View creditRow(Activity host, Wds w, String asset, String initial, String name) {
        LinearLayout row = w.row();
        row.setMinimumHeight(w.dp(Wds.ROW_SHORT));
        row.setClickable(false);

        Avatar avatar = new Avatar(host, asset, initial, w.density);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                w.dp(Wds.ICON_CIRCLE), w.dp(Wds.ICON_CIRCLE));
        alp.rightMargin = w.dp(16);
        row.addView(avatar, alp);

        row.addView(w.list(name), new LinearLayout.LayoutParams(0, -2, 1f));
        return row;
    }

    // ---------------- 文本小工具 ----------------

    private static void para(Wds w, LinearLayout parent, String text) {
        parent.addView(w.body(text), w.lpw());
    }

    private static void caption(Wds w, LinearLayout parent, String text) {
        LinearLayout.LayoutParams lp = w.lpw();
        lp.topMargin = w.dp(8);
        parent.addView(w.caption(text), lp);
    }

    private static void mono(Wds w, LinearLayout parent, String text) {
        TextView t = w.mono(text);
        t.setPadding(0, w.dp(2), 0, w.dp(2));
        parent.addView(t, w.lpw());
    }
}
