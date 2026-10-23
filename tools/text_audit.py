#!/usr/bin/env python3
"""扫描 Vela 各项目里面向用户的文案，标出冗余与套话。

判据（见 docs/TEXT-RULES.md）：
  1. 铺垫与过渡词：值得注意的是 / 需要强调的是 / 总的来说 / 简单来说 / 其实 / 顺便
  2. 跨作品或跨应用比较：与《…》一致 / 同一条路 / 不受…限制
  3. 自夸与空话：更安全 / 更稳定 / 大幅提升 / 极致 / 一站式
  4. 解释实现：加密 / 存储 / 协议 / 服务端 / 写进 / 落盘
  5. 破折号解释（一句里出现 —— 且后半句是解释）
  6. 过长句（>42 字且不含句号）

只扫源码与页面，跳过 node_modules / dist / build / 生成的 _esm。
"""
import os
import re
import sys

ROOTS = [
    "ActiveWorkers/src",
    "AsIWrite/web/public",
    "AsIWrite/worker/src",
    "KdSync/KldxstStudio/console/public",
    "KdSync/KldxstStudio/app/src/main/res",
    "AndroidActivateApplication/app/src/main/java",
    "Advanture/src/pages",
    "AfterDoom/src/pages",
    "Gomoku/src/pages",
    "Card/src/pages",
    "StarRing/src/pages",
    "LordofMysteries/src/pages",
]

EXTS = (".js", ".html", ".ux", ".xml", ".json", ".mjs")

RULES = [
    ("铺垫", re.compile(r"值得注意的是|需要强调的是|总的来说|简单来说|换句话说|其实|顺便说|首先|其次|最后一点")),
    ("比较", re.compile(r"与《[^》]+》[^。；\n]{0,12}(一致|相同|同一个)|同一条路|不受[^。；\n]{0,8}限制")),
    ("自夸", re.compile(r"更安全|更稳定|更高效|大幅提升|极致|一站式|完美|强大|丝滑")),
    ("实现", re.compile(r"加密存储|写进|落盘|协议版本|服务端结构|Keystore|数据库|字段")),
    ("破折号", re.compile(r"——")),
]

CJK = re.compile(r"[\u4e00-\u9fa5]")


def strings_in(path):
    try:
        text = open(path, encoding="utf-8", errors="replace").read()
    except Exception:
        return
    # 抓中文片段（含标点），够用即可
    for m in re.finditer(r"[^\n\"'`<>]{0,60}[\u4e00-\u9fa5][^\n\"'`<>]{0,60}", text):
        s = m.group().strip()
        if len(s) < 8:
            continue
        yield s


def scan(root):
    hits = []
    base = os.path.join("D:/Vela", root)
    if not os.path.isdir(base):
        return hits
    for dirpath, dirnames, filenames in os.walk(base):
        dirnames[:] = [d for d in dirnames if d not in
                       ("node_modules", "dist", "build", "_esm", "_views", ".git", "assets")]
        for fn in filenames:
            if not fn.endswith(EXTS):
                continue
            p = os.path.join(dirpath, fn)
            for s in strings_in(p):
                if not CJK.search(s):
                    continue
                reasons = [name for name, rx in RULES if rx.search(s)]
                if not reasons and len(s) > 42 and "。" not in s:
                    reasons = ["过长"]
                if reasons:
                    hits.append((os.path.relpath(p, "D:/Vela"), ",".join(reasons), s[:90]))
    return hits


def main():
    all_hits = []
    for r in ROOTS:
        all_hits.extend(scan(r))
    # 去重
    seen = set()
    uniq = []
    for h in all_hits:
        k = (h[0], h[2])
        if k in seen:
            continue
        seen.add(k)
        uniq.append(h)
    by_reason = {}
    for f, why, s in uniq:
        for w in why.split(","):
            by_reason[w] = by_reason.get(w, 0) + 1
    print(f"共 {len(uniq)} 条候选\n")
    for k, v in sorted(by_reason.items(), key=lambda x: -x[1]):
        print(f"  {k}: {v}")
    print()
    for f, why, s in uniq[:80]:
        print(f"[{why}] {f}\n    {s}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
