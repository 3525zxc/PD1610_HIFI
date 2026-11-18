#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""创建 HifiCtl v2.5.4-glass Release 并上传 APK。
凭据取自 git credential manager（仅内存读取，不打印明文）。"""
import subprocess
import urllib.request
import json
import os

OWNER = "3525zxc"
REPO = "PD1610_HIFI"
ROOT = os.path.dirname(os.path.abspath(__file__))
TAG = "v2.5.4-glass"
APK = "HifiCtl-v2.5.4-glass.apk"

BODY = """HifiCtl 液态玻璃版：UI 重做，音频业务层不变（versionCode 14）。

### 本版新增
- **悬浮液态玻璃底栏**：64dp 圆角玻璃条，五层质感（模糊背板 / tint / 顶边高光 / 内发丝线 / 投影），胶囊指示器 180ms 过渡
- **长按放大透镜**：按住底部页签，胶囊原地长成 60dp 玻璃透镜；拖动跟手带阻尼，越过页签中点切换并轻震；松手回缩到目标页签
- 透镜为纯 Canvas 绘制，无 RenderScript / 位图依赖；不可用时回退玻璃-only
- 内容区加底部 inset，末张卡片不再被悬浮栏遮挡

### 保留
音质档（RX HPH Mode 两档 + 开机自恢复）、DSD 档位记忆、应用内更新（本地 / 浏览器）、触感强度、mixer 备份恢复。

### 注意
- 本版是 glass 变体，**关闭了自动更新检查与更新红点**，避免被提示升级回平面稳定版；右上角入口仍可手动检查
- 从 v2.5.3 更新检查进入会安装本 glass 版
- 音频相关限制同 v2.5.3：DSD 需原生 DSD 播放器 + 真实音源；`dsdvol_index` / `hifi_i2s_clock_rate` 调用成功但无回读，听感未双盲验证；切 HIFI 后需重开播放

### 安装
`adb install -r HifiCtl-v2.5.4-glass.apk`
"""


def api(req, expect=200):
    with urllib.request.urlopen(req, timeout=60) as r:
        data = r.read()
    return json.loads(data) if data else None


def main():
    p = subprocess.run(
        ["git", "credential", "fill"],
        input=b"protocol=https\nhost=github.com\n\n",
        capture_output=True,
    )
    cred = dict(
        line.split(b"=", 1)
        for line in p.stdout.strip().splitlines()
        if b"=" in line
    )
    tok = cred.get(b"password", b"").decode()
    if not tok:
        print("NO_TOKEN")
        return 1
    h = {"Authorization": "Bearer " + tok, "User-Agent": "hifictl-release"}

    # 幂等：已存在则跳过创建
    req = urllib.request.Request(
        "https://api.github.com/repos/%s/%s/releases" % (OWNER, REPO),
        headers=h,
    )
    have = {r["tag_name"]: r for r in api(req)}
    if TAG in have:
        rel = have[TAG]
        print("EXISTS", TAG, "id=", rel["id"])
        rid = rel["id"]
        names = [a["name"] for a in rel["assets"]]
    else:
        payload = {
            "tag_name": TAG,
            "name": "v2.5.4-glass · 液态玻璃底栏 + 长按放大透镜（2026-11-18）",
            "body": BODY,
            "draft": False,
            "prerelease": False,
        }
        req = urllib.request.Request(
            "https://api.github.com/repos/%s/%s/releases" % (OWNER, REPO),
            data=json.dumps(payload).encode("utf-8"),
            headers={**h, "Content-Type": "application/json"},
            method="POST",
        )
        rel = api(req, 201)
        rid = rel["id"]
        names = []
        print("CREATED", TAG, "id=", rid)

    if APK not in names:
        data = open(os.path.join(ROOT, "apk", APK), "rb").read()
        up = urllib.request.Request(
            "https://uploads.github.com/repos/%s/%s/releases/%d/assets?name=%s"
            % (OWNER, REPO, rid, APK),
            data=data,
            headers={
                **h,
                "Content-Type": "application/vnd.android.package-archive",
                "Content-Length": str(len(data)),
            },
            method="POST",
        )
        asset = api(up, 201)
        print("UPLOAD", asset["name"], asset["size"], "B")
        print("URL", asset["browser_download_url"])
    else:
        print("SKIP asset (already uploaded)", APK)

    # 验证 releases/latest 就是本版
    latest = api(urllib.request.Request(
        "https://api.github.com/repos/%s/%s/releases/latest" % (OWNER, REPO),
        headers=h,
    ))
    print("LATEST", latest["tag_name"])
    for a in latest["assets"]:
        if a["name"].endswith(".apk"):
            print("LATEST_ASSET", a["name"], a["browser_download_url"])
            print("API_ASSET", a["url"])
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
