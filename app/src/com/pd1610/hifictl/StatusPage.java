package com.pd1610.hifictl;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 页1 状态：运行健康（3 行列表）/ 详细参数（折叠）/ 运行环境（折叠）。
 *
 * 刷新策略（brief 4.2）：
 *  - 首次进入：后台加载全量快照 + 骨架屏占位；
 *  - 再次进入：只静默刷新「HAL 回报」一行（单个 getParameters，不 removeAllViews，
 *    不动滚动位置与折叠展开态）；
 *  - AppBar 刷新按钮：全量重测，重测期间保留旧内容。
 */
final class StatusPage {

    private final Activity host;
    private final Wds w;
    private final HifiController ctl;
    private final LinearLayout body;
    private final ScrollView scroll;
    private Rows.ListRow hifiRow;
    private Rows.ListRow rootRow;
    private Rows.ListRow halRow;
    private Rows.Collapsible detailCol;
    private Rows.Collapsible envCol;
    private boolean loading = false;
    private boolean loaded = false;

    StatusPage(Activity host, Wds w, HifiController ctl) {
        this.host = host;
        this.w = w;
        this.ctl = ctl;

        body = w.column();
        scroll = new ScrollView(host);
        scroll.setFillViewport(true);
        scroll.addView(body, new ScrollView.LayoutParams(-1, -2));

        showSkeleton();
    }

    View view() {
        return scroll;
    }

    /** Tab 切到本页时调用：首帧全量加载，之后只静默刷新 HAL 行 */
    void onEnter() {
        if (!loaded) {
            if (loading) return;
            loading = true;
            new Thread(this::load).start();
            return;
        }
        refreshHal();
    }

    /** AppBar 刷新按钮：全量重测（保留旧内容，只把 HAL 行标注为重测中） */
    void remeasure() {
        if (loading) return;
        if (halRow != null) halRow.setCaption("重测中…");
        loading = true;
        new Thread(this::load).start();
    }

    // ---------------- 数据快照 ----------------

    /** 后台线程收集的只读快照，渲染时不再碰 IO */
    private static final class Snapshot {
        boolean hifiSupport;
        String dacCompat;
        int[] dacParams;
        int[] dacParamNum;
        int[] hphGain;
        int[] impedDefs;
        String[] codecProps = new String[0];
        boolean root;
        String hifiState = "";
    }

    private void load() {
        Snapshot s = new Snapshot();
        s.hifiSupport = ctl.isHifiSupported();
        s.dacCompat = ctl.readDacCompatible();
        s.dacParams = ctl.readDacParams();
        s.dacParamNum = ctl.readDacParamNum();
        s.hphGain = ctl.readHphGain();
        s.impedDefs = ctl.readImpedDefs();
        s.codecProps = DtReader.hifiCodecProps();
        s.root = RootShell.hasRoot();
        s.hifiState = ctl.currentHifiState();

        host.runOnUiThread(() -> {
            if (host.isFinishing() || host.isDestroyed()) return;
            loading = false;
            loaded = true;
            render(s);
        });
    }

    /** 只读一次 enable_hifi，只更新 HAL 行（不动其他 View、不动滚动） */
    private void refreshHal() {
        if (halRow == null || loading) return;
        new Thread(() -> {
            String state = ctl.currentHifiState();
            host.runOnUiThread(() -> {
                if (host.isFinishing() || host.isDestroyed()) return;
                if (halRow == null) return;
                halRow.setDotColor(isOn(state) ? Wds.GREEN_DEEP : Wds.WARN);
                halRow.setCaption(state.isEmpty() ? "读取不到 enable_hifi" : "enable_hifi = " + state);
            });
        }).start();
    }

    // ---------------- 骨架屏 ----------------

    private void showSkeleton() {
        body.removeAllViews();
        body.addView(Widgets.section(host, w, "运行健康"));
        LinearLayout card = w.cardBleed();
        for (int i = 0; i < 3; i++) {
            if (i > 0) card.addView(w.divider(16));
            View bar = new View(host);
            bar.setBackground(w.solid(0x14FFFFFF, 8f));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, w.dp(56));
            lp.leftMargin = w.dp(16);
            lp.rightMargin = w.dp(16);
            card.addView(bar, lp);
        }
        body.addView(card, w.cardLp());
        w.gap(body, 24);
    }

    // ---------------- 渲染 ----------------

    private void render(Snapshot s) {
        // 重建前记住两个折叠组与三个健康行的展开态，重建后静默恢复（不播动画）
        boolean wasDetail = detailCol != null && detailCol.isExpanded();
        boolean wasEnv = envCol != null && envCol.isExpanded();
        boolean wasHifi = hifiRow != null && hifiRow.isExpanded();
        boolean wasRoot = rootRow != null && rootRow.isExpanded();
        boolean wasHal = halRow != null && halRow.isExpanded();
        body.removeAllViews();

        // ---- 分区：运行健康 ----
        body.addView(Widgets.section(host, w, "运行健康"));
        LinearLayout health = w.cardBleed();

        hifiRow = new Rows.ListRow(host, w)
                .dot(s.hifiSupport ? Wds.GREEN_DEEP : Wds.DANGER)
                .title("HIFI 硬件")
                .subtitle(s.hifiSupport ? "设备树已声明，硬件支持 HIFI" : "未声明，HAL 可能走普通通路")
                .chevron()
                .detail(s.hifiSupport
                        ? "节点 /soc/sound-9335/vivo,hifi-support 存在（空属性，存在即支持）"
                        : "节点 /soc/sound-9335/vivo,hifi-support 不存在，请确认固件版本");
        health.addView(hifiRow);

        health.addView(w.divider(56));

        rootRow = new Rows.ListRow(host, w)
                .dot(s.root ? Wds.GREEN_DEEP : Wds.WARN)
                .title("Root 权限")
                .subtitle(s.root ? "已获取，持久化操作可用" : "未获取，备份与恢复不可用")
                .chevron()
                .detail(s.root
                        ? "su 返回 uid=0，备份文件写入 /data/local/tmp"
                        : "未检测到可用 su，mixer 备份 / 恢复会失败");
        health.addView(rootRow);

        health.addView(w.divider(56));

        halRow = new Rows.ListRow(host, w)
                .dot(isOn(s.hifiState) ? Wds.GREEN_DEEP : Wds.WARN)
                .title("HAL 回报")
                .subtitle(s.hifiState.isEmpty() ? "读取不到 enable_hifi" : "enable_hifi = " + s.hifiState)
                .chevron()
                .detail("HAL 在重新开始播放时才更新该参数，改完不重开流会停留旧值");
        health.addView(halRow);

        body.addView(health, w.cardLp());

        // 按创建顺序恢复三行详情（与上面 addView 顺序一致：HIFI 硬件 / Root / HAL）
        hifiRow.restoreExpanded(wasHifi);
        rootRow.restoreExpanded(wasRoot);
        halRow.restoreExpanded(wasHal);

        // ---- 分区：详细参数 ----
        body.addView(Widgets.section(host, w, "详细参数"));
        LinearLayout detailCard = w.cardBleed();
        detailCol = new Rows.Collapsible(host, w, "DAC / 耳机增益 / 电路节点");
        Rows.Collapsible detail = detailCol;
        detailCard.addView(detail);
        body.addView(detailCard, w.cardLp());

        LinearLayout d = detail.body();
        d.addView(paramRow("compatible = " + (s.dacCompat == null ? "(不可读)" : s.dacCompat), true));
        d.addView(paramRow("vivo,params = " + DtReader.fmt(s.dacParams), false));
        d.addView(paramRow("vivo,param-num = "
                + (s.dacParamNum != null && s.dacParamNum.length >= 2
                ? s.dacParamNum[0] + " × " + s.dacParamNum[1] + "（两组表）" : "(不可读)"), false));
        w.gap(d, 12);
        d.addView(paramRow("vivo,wcd-hph-gain = " + DtReader.fmt(s.hphGain), false));
        d.addView(paramRow("vivo,wcd-imped-defs = " + DtReader.fmt(s.impedDefs), false));
        d.addView(w.caption("增益为 5 bit 字段（0..31），写入 codec 寄存器 0x6d3 / 0x6d6"));
        w.gap(d, 12);
        d.addView(paramRow("/soc/vivo-hifi-codec · " + s.codecProps.length + " 个属性", true));
        int shown = 0;
        for (String prop : s.codecProps) {
            if (!prop.startsWith("vivo,")) continue;
            d.addView(paramRow("  " + prop, false));
            if (++shown >= 16) break;
        }
        d.addView(w.caption("长按任意参数行可复制到剪贴板"));

        // ---- 分区：运行环境 ----
        body.addView(Widgets.section(host, w, "运行环境"));
        LinearLayout envCard = w.cardBleed();
        envCol = new Rows.Collapsible(host, w, "mixer 文件与固件对比");
        Rows.Collapsible env = envCol;
        envCard.addView(env);
        body.addView(envCard, w.cardLp());

        LinearLayout e = env.body();
        e.addView(paramRow(HifiController.MIXER_PD1610, true));
        w.gap(e, 12);
        e.addView(paramRow("1.15.1 (6.0.1)  118,532 B  3,145 行  @ /etc/", false));
        e.addView(paramRow("8.19.16 (8.1)   119,329 B  3,176 行  @ /vendor/etc/", false));
        e.addView(w.caption("差异 195 行，全为 MultiMedia8/9、EC Reference、HDMI 等通路扩展；"
                + "HIFI 相关控件逐字节一致。"));

        if (wasDetail) detailCol.setExpanded(true, false);
        if (wasEnv) envCol.setExpanded(true, false);

        w.gap(body, 24);
    }

    /** 可长按复制的参数行；big=true 用 11sp mono 显示路径、短文用同款更紧凑 */
    private TextView paramRow(String text, boolean prominent) {
        TextView t = w.mono(text);
        t.setTextColor(prominent ? Wds.TEXT_2 : Wds.TEXT_3);
        t.setPadding(0, w.dp(4), 0, w.dp(4));
        t.setOnLongClickListener(v -> {
            copy(text);
            return true;
        });
        return t;
    }

    private void copy(String text) {
        ClipboardManager cm = (ClipboardManager) host.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("hifictl", text));
        }
        Toast.makeText(host, "已复制", Toast.LENGTH_SHORT).show();
    }

    private static boolean isOn(String state) {
        if (state == null) return false;
        String v = state.trim().toLowerCase(java.util.Locale.US);
        return v.equals("1") || v.equals("true");
    }
}
