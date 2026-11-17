package com.pd1610.hifictl;

/**
 * 渠道开关（glass 分支）。
 *
 * 只放一个编译期常量：{@code GLASS = true} 时底栏走液态玻璃（{@link GlassBar}），
 * 并关闭自动更新检查与右上角更新红点（glass 是内部验证版，不该提示升级回正式版）。
 * 回退到平面版由 git 完成，不在这里做运行时分支。
 */
final class Flavor {

    /** 是否为液态玻璃版 */
    static final boolean GLASS = true;

    private Flavor() {
    }
}
