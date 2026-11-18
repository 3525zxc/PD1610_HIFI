#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""为 v1.0 至 v2.5.4-glass 全部 Release 追加统一致谢段。
@AlexIllinois2 为真实 GitHub 账号，GitHub 会在发布页将其显示为 Contributor。
幂等：已含标记则跳过。凭据取自 git credential manager，不打印明文。"""
import subprocess
import urllib.request
import json

OWNER = "3525zxc"
REPO = "PD1610_HIFI"
MARK = "<!-- credit-alexillinois2 -->"

CREDIT = """

---

### 致谢 / Credits

""" + MARK + """
- @AlexIllinois2 — 提供 [xplay6-tmproot](https://github.com/AlexIllinois2/xplay6-tmproot) 参考资源；本项目的 HIFI 通路与参数移植方案源自该仓库，特此致谢。
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
    h = {"Authorization": "Bearer " + tok, "User-Agent": "hifictl-credits",
         "Accept": "application/vnd.github+json"}
    base = "https://api.github.com/repos/%s/%s" % (OWNER, REPO)

    rels = api(urllib.request.Request(base + "/releases?per_page=100", headers=h))
    print("RELEASES", len(rels))

    for rel in rels:
        tag = rel["tag_name"]
        body = rel.get("body") or ""
        if MARK in body:
            print("SKIP", tag)
            continue
        new = body.rstrip() + CREDIT
        req = urllib.request.Request(
            base + "/releases/%d" % rel["id"],
            data=json.dumps({"body": new}).encode("utf-8"),
            headers={**h, "Content-Type": "application/json"},
            method="PATCH",
        )
        api(req)
        print("PATCHED", tag)

    # 复核
    rels = api(urllib.request.Request(base + "/releases?per_page=100", headers=h))
    bad = [r["tag_name"] for r in rels if MARK not in (r.get("body") or "")]
    print("VERIFY total=", len(rels), "missing=", bad)
    return 0 if not bad else 1


if __name__ == "__main__":
    raise SystemExit(main())
