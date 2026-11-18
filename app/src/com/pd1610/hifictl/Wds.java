package com.pd1610.hifictl;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.TypedValue;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * v2.0 设计系统（Wds = Wupp Design System）。
 *
 * 视觉语言：WhatsApp 深色系的「平面分层」—— BG_PAGE 页底 + SURFACE 卡片 + 1dp HAIRLINE 分割，
 * 没有半透明玻璃、没有渐变高光、没有投影（与 v1.x 玻璃风的根本区别）。
 *
 * 颜色规则按用途而非数量：GREEN_DEEP 只用于选中态/分区标题/滑块已走轨道，
 * GREEN 只用于主操作实底，其余一律中性色，禁止装饰性染色。
 */
final class Wds {

    // ---------------- 1.1 色板 ----------------

    static final int BG_PAGE = 0xFF0B141A;
    static final int BG_BAR = 0xFF1F2C33;
    static final int SURFACE = 0xFF111B21;
    static final int SURFACE_2 = 0xFF202C33;
    static final int HAIRLINE = 0xFF2A3942;

    static final int TEXT = 0xFFE9EDEF;
    static final int TEXT_2 = 0xFF8696A0;
    static final int TEXT_3 = 0xFF667781;

    static final int GREEN = 0xFF25D366;
    static final int GREEN_DEEP = 0xFF00A884;
    static final int GREEN_WASH = 0xFF0F2B24;
    static final int ON_GREEN = 0xFF0B141A;

    static final int DANGER = 0xFFF15C6D;
    static final int ON_DANGER = 0xFF2A0F12;
    static final int WARN = 0xFFFFBC38;

    /** banner 语义底（brief 3.5） */
    static final int WASH_INFO = 0xFF12323B;
    static final int WASH_OK = 0xFF0F2B24;
    static final int WASH_WARN = 0xFF2E2415;
    static final int WASH_DANGER = 0xFF2E1719;

    /** 滑块未走轨道 */
    static final int TRACK_OFF = 0xFF3B4A54;
    /** 通用 ripple */
    static final int RIPPLE = 0x1FFFFFFF;
    static final int RIPPLE_DARK = 0x33000000;

    // ---------------- 1.1b 液态玻璃令牌（glass 分支） ----------------
    // 只服务 GlassBar / Backdrop，上面 1.1 的平面版色板一个都不动。

    /** 玻璃着色：深青黑 @ 42%，模糊层之上压这一层才够「厚」 */
    static final int GLASS_TINT = 0x6B0E1A22;
    /** 顶部高光起点（往下渐隐到全透明） */
    static final int GLASS_HI_TOP = 0x1FFFFFFF;
    /** 1dp 内描边：上缘更亮 */
    static final int GLASS_EDGE_TOP = 0x47FFFFFF;
    /** 1dp 内描边：其余三边 */
    static final int GLASS_EDGE = 0x1FFFFFFF;
    /** 选中药丸填充：#25D366 @ 15% */
    static final int GLASS_PILL = 0x2625D366;
    /** 选中药丸描边：#25D366 @ 40% */
    static final int GLASS_PILL_EDGE = 0x6625D366;
    /** 玻璃上的强调色（比 GREEN_DEEP 亮一档，半透明底上更跳） */
    static final int GLASS_ACCENT = 0xFF22E39A;
    /** 玻璃上的未选中色（比 TEXT_2 亮，半透明底上更清晰） */
    static final int GLASS_IDLE = 0xFF9FB0B8;
    /** 内容底部留白(dp)：64 栏高 + 12 底边距 + 12 呼吸 + 8 余量 */
    static final int NAV_INSET = 96;

    // ---------------- 1.1c 放大镜令牌（长按底栏浮起的圆镜） ----------------

    /** 镜内着色：比栏身的 42% 更深，图标压在上面才立得住 */
    static final int LENS_TINT = 0x520B151C;
    /** 镜下柔影（浮起感） */
    static final int LENS_SHADOW = 0x4D000000;
    /** 镜缘常色 */
    static final int LENS_RIM = 0x59FFFFFF;
    /** 镜缘顶部那道高光弧 */
    static final int LENS_SPEC = 0xD9FFFFFF;
    /** 镜内高光渐变起点 */
    static final int LENS_HI_TOP = 0x33FFFFFF;

    // ---------------- 1.2 字体阶梯 ----------------

    static final float SP_HERO = 32f;
    static final float SP_TITLE = 20f;
    static final float SP_LIST = 16f;
    static final float SP_BODY = 14f;
    static final float SP_SECTION = 13f;
    static final float SP_CAPTION = 12f;
    static final float SP_MONO = 11f;
    static final float SP_SEG = 15f;

    static final float LH_HERO = 1.1f;
    static final float LH_BODY = 1.45f;
    static final float LH_CAPTION = 1.35f;

    // ---------------- 1.3 尺寸与间距（4dp 栅格） ----------------

    static final int PAGE_PAD = 16;
    static final int GAP_CARD = 12;
    static final int CARD_PAD = 16;
    static final int APP_BAR_H = 56;
    static final int NAV_H = 64;
    static final int ROW_TALL = 72;
    static final int ROW_SHORT = 56;
    static final int TOUCH = 48;
    static final int ICON_CIRCLE = 40;

    static final float R_CARD = 16f;
    static final float R_SHEET = 28f;
    static final float R_SEG_SLOT = 12f;
    static final float R_SEG_THUMB = 9f;
    static final float R_BTN = 12f;
    static final float R_BANNER = 12f;
    static final float R_PILL = 16f;

    static final Typeface REG = Typeface.create("sans-serif", Typeface.NORMAL);
    static final Typeface MED = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    static final Typeface MONO = Typeface.MONOSPACE;

    private final Context ctx;
    final float density;

    Wds(Context c) {
        ctx = c;
        density = c.getResources().getDisplayMetrics().density;
    }

    /** HH:mm:ss，用于根操作结果时间戳 */
    static String time() {
        return new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
    }

    // ---------------- 尺寸 ----------------

    int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                ctx.getResources().getDisplayMetrics());
    }

    float dpf(float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                ctx.getResources().getDisplayMetrics());
    }

    // ---------------- 文本 ----------------

    TextView text(String s, float sp, int color, Typeface tf) {
        TextView t = new TextView(ctx);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(tf);
        return t;
    }

    /** 32sp medium：当前模式 / DSD 数值 */
    TextView hero(String s) {
        TextView t = text(s, SP_HERO, TEXT, MED);
        t.setLineSpacing(0f, LH_HERO);
        return t;
    }

    /** 20sp medium：顶栏标题 / Sheet 标题 / 品牌名 */
    TextView title(String s) {
        return text(s, SP_TITLE, TEXT, MED);
    }

    /** 16sp regular：列表行标题 */
    TextView list(String s) {
        return text(s, SP_LIST, TEXT, REG);
    }

    /** 14sp regular：正文 / 副标题 */
    TextView body(String s) {
        return body(s, TEXT_2);
    }

    TextView body(String s, int color) {
        TextView t = text(s, SP_BODY, color, REG);
        t.setLineSpacing(0f, LH_BODY);
        return t;
    }

    /** 13sp medium GREEN_DEEP：分区标题 */
    TextView sectionText(String s) {
        return text(s, SP_SECTION, GREEN_DEEP, MED);
    }

    /** 12sp：脚注 / 时间戳 */
    TextView caption(String s) {
        TextView t = text(s, SP_CAPTION, TEXT_3, REG);
        t.setLineSpacing(0f, LH_CAPTION);
        return t;
    }

    /** 11sp mono：仅设备树键名 / 寄存器 / 原始数组 / 路径 */
    TextView mono(String s) {
        TextView t = text(s, SP_MONO, TEXT_2, MONO);
        t.setLineSpacing(0f, LH_CAPTION);
        return t;
    }

    // ---------------- 容器 ----------------

    LinearLayout column() {
        LinearLayout l = new LinearLayout(ctx);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    LinearLayout row() {
        LinearLayout l = new LinearLayout(ctx);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(android.view.Gravity.CENTER_VERTICAL);
        return l;
    }

    /** 标准卡片：SURFACE 底 + 1dp HAIRLINE 描边 + 16dp 内边距 */
    LinearLayout card() {
        LinearLayout l = column();
        l.setBackground(cardBg());
        int p = dp(CARD_PAD);
        l.setPadding(p, p, p, p);
        return l;
    }

    /** 通栏卡片：无内边距，供 ListRow / 折叠头自行带 16dp 左内缩 */
    LinearLayout cardBleed() {
        LinearLayout l = column();
        l.setBackground(cardBg());
        return l;
    }

    Drawable cardBg() {
        return stroke(SURFACE, HAIRLINE, R_CARD);
    }

    /** 卡片在页面里的标准外框：左右 16dp，底部 12dp（露出 BG_PAGE 底色） */
    LinearLayout.LayoutParams cardLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.leftMargin = dp(PAGE_PAD);
        lp.rightMargin = dp(PAGE_PAD);
        lp.bottomMargin = dp(GAP_CARD);
        return lp;
    }

    LinearLayout.LayoutParams lpw() {
        return new LinearLayout.LayoutParams(-1, -2);
    }

    /** 1dp HAIRLINE 分割线，左侧按 WhatsApp 习惯内缩 */
    View divider(int leftInsetDp) {
        View v = new View(ctx);
        v.setBackgroundColor(HAIRLINE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Math.max(1, dp(1)));
        lp.leftMargin = dp(leftInsetDp);
        v.setLayoutParams(lp);
        return v;
    }

    void gap(LinearLayout parent, int dpVal) {
        View v = new View(ctx);
        parent.addView(v, new LinearLayout.LayoutParams(-1, dp(dpVal)));
    }

    void hgap(LinearLayout parent, int dpVal) {
        View v = new View(ctx);
        parent.addView(v, new LinearLayout.LayoutParams(dp(dpVal), -1));
    }

    // ---------------- Drawable 工厂 ----------------

    /** 实心圆角矩形 */
    Drawable solid(int color, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(color);
        d.setCornerRadius(radiusDp * density);
        return d;
    }

    /** 实心圆角矩形 + 1dp 描边 */
    Drawable stroke(int fill, int strokeColor, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(fill);
        d.setCornerRadius(radiusDp * density);
        d.setStroke(Math.max(1, Math.round(density)), strokeColor);
        return d;
    }

    /** 半透明描边（banner 用），描边取语义色 30% alpha */
    Drawable strokeAlpha(int fill, int strokeColor, int strokeAlpha, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(fill);
        d.setCornerRadius(radiusDp * density);
        d.setStroke(Math.max(1, Math.round(density)), (strokeColor & 0x00FFFFFF) | (strokeAlpha << 24));
        return d;
    }

    /** 圆角药丸 / 圆形（radiusDp 传 -1 表示圆） */
    Drawable pill(int color, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(radiusDp < 0 ? GradientDrawable.OVAL : GradientDrawable.RECTANGLE);
        d.setColor(color);
        if (radiusDp >= 0) d.setCornerRadius(radiusDp * density);
        return d;
    }

    /** 实心圆点 */
    Drawable circle(int color) {
        return pill(color, -1f);
    }

    /** 只有上面的角是圆角（底部 Sheet） */
    Drawable topRounded(int color, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(color);
        float r = radiusDp * density;
        d.setCornerRadii(new float[]{r, r, r, r, 0f, 0f, 0f, 0f});
        return d;
    }

    /** 空内容水波纹 */
    Drawable ripple(int maskColor, float radiusDp) {
        return ripple(null, maskColor, radiusDp);
    }

    Drawable ripple(Drawable content, int maskColor, float radiusDp) {
        GradientDrawable mask = new GradientDrawable();
        mask.setShape(GradientDrawable.RECTANGLE);
        mask.setCornerRadius(Math.max(0f, radiusDp * density));
        mask.setColor(0xFFFFFFFF);
        return new RippleDrawable(ColorStateList.valueOf(maskColor), content, mask);
    }

    // ---------------- 交互 ----------------

    /** 给可点 View 加水波纹（已有 RippleDrawable 时不重复包裹） */
    void ink(View v, float radiusDp) {
        Drawable bg = v.getBackground();
        if (!(bg instanceof RippleDrawable)) {
            v.setBackground(ripple(bg, RIPPLE, radiusDp));
        }
        v.setClickable(true);
        v.setFocusable(true);
    }
}
