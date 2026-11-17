#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""为 PD1610_HIFI 创建 v1.0 -> v1.1-glass -> v1.2 -> v2.3 四个 GitHub Release 并上传对应 APK。
仅使用 git 凭据管理器中已缓存的 token，不读取/打印明文。"""
import subprocess, urllib.request, json, os, sys

OWNER = "3525zxc"
REPO  = "PD1610_HIFI"
ROOT  = os.path.dirname(os.path.abspath(__file__))

RELEASES = [
    {
        "tag": "v1.0", "name": "v1.0 · 初版（2026-11-17 上午）", "prerelease": False,
        "apk": "HifiCtl-v1.0.apk",
        "body": """HifiCtl 首个版本：**HIFI 参数可调 APP 从零到能运行**。

### 本轮新增
- **三页框架**：状态 / 调节 / 关于，纯 Java + 原生 View 代码构建 UI（无 XML 布局、无第三方依赖）
- **HAL 参数下发**：反射调用 `AudioManager.setParameters()`（@hide API），实时切换无需重启
- **模式切换**：Off / HiFi / DSD 三档
- **DSD 音量滑块**：0–31（官方默认 19）
- **I2S 时钟档位**：`hifi_i2s_clock_rate` 调节入口
- **设备树读取**：`DtReader` 大端 u32 解析，只读展示 ES9038 DAC 参数表、耳机增益 `{4,3,1,1}`、阻抗阈值 `{4,24,38,70}Ω`
- **root 封装**：`RootShell` 通过 `su` 执行，提供 mixer 备份 / 恢复、恢复默认值

### 已知问题（后续版本修复）
- 当时依据 `strings` 输出把 `hifi-playback` 当成开关，**实机验证无效**（HAL 收到但 value 解析为空，不进 HIFI 状态机）
- 真正生效的 key 是 `enable_hifi`，见 v1.1-glass

### 安装
`adb install -r HifiCtl-v1.0.apk`（debug 签名，minSdk 27 / targetSdk 28，Android 9 PD1610 实测）
""",
    },
    {
        "tag": "v1.1-glass", "name": "v1.1-glass · 玻璃态稳定版（2026-11-17 13:15）", "prerelease": False,
        "apk": "HifiCtl-v1.1-glass.apk",
        "body": """**关键修正版**：把错误参数换成实测有效的开关，并换上玻璃态 UI。这是 v1.2 闪退后设备回退并长期使用的稳定版。

### 本轮新增 / 修复
- **修正 HIFI 开关**：`hifi-playback=<any>` → **`enable_hifi=0/1`**。实测 logcat 出现 `hifi state change from 0 to 1`，`HiFi Mode Switch` 由 Off 变 HiFi
- **状态回读**：`getParameters("enable_hifi")` 返回 `enable_hifi=true/false`，状态页显示当前开关
- **修正 DSD 档写法**：`enable_hifi=1` 为基础，`DSDEnable` 仅作为尝试（本版仍无法真正触发 DSD 输出，见下）
- **玻璃态 UI**：顶部玻璃胶囊 tab、原生 `GradientDrawable` 玻璃卡片、蓝紫光晕背景（不依赖 RenderEffect，兼容 Android 9）
- **实机三页验证通过**：状态页 / 调节页 / 关于页均无崩溃，参数显示正确
- **交互提示**：切换 HIFI 后需重新开始播放（HAL 在开流时才选 `headphone-hifi` 路由）

### 重要结论（本轮锁定）
- **HIFI 通路确实生效**，客观证据：`TERT_MI2S Bit Format` S16_LE→S24_LE、`TERT_MI2S mode` Master→Slave、`Headphone Rx Mixer I2S_RX` Off→On、耳机音量 16→20
- **DSD 档不是参数开关**：由输出流自身是否为 DSD 格式决定，`DSDEnable=1` 无效，Java `AudioTrack` 直接拒绝 `AUDIO_FORMAT_DSD`
- **听感陷阱**：HIFI ON 时音量自动 +4 档，A/B 对比须先对齐响度

### 安装
`adb install -r HifiCtl-v1.1-glass.apk`
""",
    },
    {
        "tag": "v1.2", "name": "v1.2 · GLES 折射尝试版（已废弃 · 会闪退）", "prerelease": True,
        "apk": "HifiCtl-v1.2.apk",
        "body": """**⚠️ 已废弃版本，仅作留档，请勿安装（实机闪退）。**

### 本轮尝试新增（AndroidLiquidGlass 风格三件套）
- **背景毛玻璃虚化**：GLES 3.0 离屏模糊，替代 v1.1 的 `GradientDrawable` 近似
- **透镜折射 + 高光边**：GLSL 采样偏移做折射，边缘高光
- **底部导航栏整体玻璃样式**：底部 tab 改为玻璃质感条

### 为什么废弃
- AndroidLiquidGlass 的 blur 依赖 **API 31 `RenderEffect`**、lens 依赖 **API 33 `RuntimeShader`**；本机 Android 9 (API 28) 无系统级支持，GLES 手写方案在真机上**闪退**
- 用户决定取消这一版，源码回滚到 v1.1，设备重新装回 v1.1-glass

### 说明
- 保留 APK 与 tag 只为记录探索路径；**日常使用请装 v2.3 或 v1.1-glass**
- 设计思路后来在 v2.0 用原生 View 重做（Wupp / WhatsApp 深色系），不再走 GLES
""",
    },
    {
        "tag": "v2.3", "name": "v2.3 · 当前推荐版（2026-11-17 22:28）", "prerelease": False,
        "apk": "HifiCtl-v2.3.apk",
        "body": """**当前推荐版本**，共 19 个 Java 类，四页签完整重构版（versionCode 7）。

### v2.0 起的整体重构（v2.1/v2.2 为中间迭代，未单独发 APK）
- **四页签骨架**：控制 / 状态 / 设置 / 关于，`MainActivity` 带返回键栈（不再是最初三页）
- **Wds 设计系统**（Wupp / WhatsApp 深色系）：统一色板、组件 `Widgets` / `Icons` / `Rows` / `SeekSkin` / `BottomNav`
- **控制页**：Off / HiFi / DSD 模式面板重做，DSD 音量与 I2S 时钟档位
- **状态页**：DT 参数 + HAL 回读，按新设计系统排版
- **设置页（全新）**：**触感强度连续可调 0–100%**（`Haptics`）、**mixer 备份 / 恢复**、恢复默认值（root）
- **确认层**：`ConfirmSheet` 二次确认，防止误触恢复默认值
- **HAL 写串行化**：参数下发排队，避免并发调用互相覆盖

### v2.3 本轮新增
- **关于页完善**：常见问题、参数有效性结论、**三位致谢者头像 + 昵称**（`assets/avatars`，用户主动加入并同意公开）
- 固化 DSD 三层限制的说明文字，APP 不谎报 DSD 生效
- 通用 `build.sh`：从 `ANDROID_SDK` 环境变量推导工具路径，不写死本机目录；干净编译 dex 哈希与仓库预编译 v2.3 一致（`e7bb3b87…`）

### 权限与前提
- 切 HIFI 只需 `MODIFY_AUDIO_SETTINGS`，**不需要 root**
- 读设备树 / mixer 备份恢复需 Magisk root，首次启动弹授权
- Android 9 / API 28（minSdk 27 / targetSdk 28），PD1610 + LOS16 + 3.18.71 prebuilt 内核实测

### 已知限制
1. DSD 档不可由参数触发，需原生 DSD 输出播放器 + 真实 DSD 音源
2. `dsdvol_index` / `hifi_i2s_clock_rate` 调用成功但 HAL 无 readback，音质影响未做双盲听测
3. HIFI ON 时耳机音量自动 +4，A/B 对比需先对齐响度
4. 切换 HIFI 后需重开播放才生效

### 安装
`adb install -r HifiCtl-v2.3.apk`
""",
    },
]

def api(req, expect=200):
    with urllib.request.urlopen(req, timeout=60) as r:
        return json.load(r)

def main():
    raw = subprocess.run(["git", "credential", "fill"],
                         input="protocol=https\nhost=github.com\n\n",
                         capture_output=True, text=True).stdout
    d = dict(l.split("=", 1) for l in raw.strip().splitlines() if "=" in l)
    tok = d.get("password", "")
    if not tok:
        print("NO_TOKEN"); return 1
    h = {"Authorization": "Bearer " + tok, "User-Agent": "hifictl-release"}

    # 检查是否已存在，避免重复创建
    req = urllib.request.Request(
        "https://api.github.com/repos/%s/%s/releases" % (OWNER, REPO), headers=h)
    existing = api(req)
    have = {r["tag_name"]: r for r in existing}

    for rel in RELEASES:
        tag = rel["tag"]
        if tag in have:
            print("SKIP existing release", tag, "id=", have[tag]["id"])
            continue
        payload = {
            "tag_name": tag,
            "name": rel["name"],
            "body": rel["body"],
            "draft": False,
            "prerelease": rel["prerelease"],
        }
        req = urllib.request.Request(
            "https://api.github.com/repos/%s/%s/releases" % (OWNER, REPO),
            data=json.dumps(payload).encode("utf-8"),
            headers={**h, "Content-Type": "application/json"}, method="POST")
        created = api(req, 201)
        rid = created["id"]
        print("CREATED release", tag, "id=", rid)

        apk = os.path.join(ROOT, "apk", rel["apk"])
        with open(apk, "rb") as f:
            data = f.read()
        up = urllib.request.Request(
            "https://uploads.github.com/repos/%s/%s/releases/%d/assets?name=%s"
            % (OWNER, REPO, rid, rel["apk"]),
            data=data,
            headers={**h, "Content-Type": "application/vnd.android.package-archive",
                     "Content-Length": str(len(data))}, method="POST")
        asset = api(up, 201)
        print("  UPLOAD", asset["name"], asset["size"], "B ->", asset["browser_download_url"])
    print("DONE")
    return 0

if __name__ == "__main__":
    sys.exit(main())
