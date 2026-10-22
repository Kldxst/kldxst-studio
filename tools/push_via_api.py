#!/usr/bin/env python3
"""通过 GitHub Git Data API 推送一次提交。

本机代理会间歇性切断 github.com:443 的 CONNECT 隧道（git push 会随机 502 / TLS EOF），
但 api.github.com 稳定可用，所以这里用 gh 的凭据走 REST 接口建 blob → tree → commit → ref。
"""
import base64
import json
import subprocess
import time
import sys

REPO = "Kldxst/kldxst-studio"
BRANCH = "main"


def gh(args, payload=None, retries=6):
    """本机代理不稳，TLS 握手会随机超时 —— 一律重试，指数退避"""
    cmd = ["gh", "api"] + args
    if payload is not None:
        cmd += ["--input", "-"]
    last = ""
    for attempt in range(retries):
        p = subprocess.run(
            cmd,
            input=json.dumps(payload) if payload is not None else None,
            capture_output=True,
            text=True,
            encoding="utf-8",
        )
        if p.returncode == 0:
            return json.loads(p.stdout) if p.stdout.strip() else {}
        last = p.stderr
        # 409（空仓库）/ 401 这类确定性错误不必重试
        if "HTTP 409" in last or "HTTP 401" in last or "HTTP 404" in last:
            break
        time.sleep(2 + attempt * 3)
    raise SystemExit(f"gh api 失败: {' '.join(args)}\n{last}")


def parents():
    """已有分支就接在它后面，避免把历史冲掉"""
    try:
        ref = gh([f"repos/{REPO}/git/ref/heads/{BRANCH}"])
        return [ref["object"]["sha"]]
    except SystemExit:
        return []


def main():
    files = subprocess.run(
        ["git", "ls-files"], capture_output=True, text=True, encoding="utf-8", check=True
    ).stdout.split()
    if not files:
        raise SystemExit("没有可提交的文件")

    entries = []
    for path in files:
        with open(path, "rb") as fh:
            raw = fh.read()
        blob = gh(
            [f"repos/{REPO}/git/blobs", "-X", "POST"],
            {"content": base64.b64encode(raw).decode(), "encoding": "base64"},
        )
        entries.append({"path": path, "mode": "100644", "type": "blob", "sha": blob["sha"]})
        print(f"blob {path} {blob['sha'][:8]}")

    tree = gh([f"repos/{REPO}/git/trees", "-X", "POST"], {"tree": entries})
    print("tree", tree["sha"][:8])

    message = subprocess.run(
        ["git", "log", "-1", "--pretty=%B"], capture_output=True, text=True, encoding="utf-8", check=True
    ).stdout.strip()

    commit = gh(
        [f"repos/{REPO}/git/commits", "-X", "POST"],
        {"message": message, "tree": tree["sha"], "parents": parents()},
    )
    print("commit", commit["sha"])

    try:
        gh(
            [f"repos/{REPO}/git/refs", "-X", "POST"],
            {"ref": f"refs/heads/{BRANCH}", "sha": commit["sha"]},
        )
    except SystemExit:
        gh(
            [f"repos/{REPO}/git/refs/heads/{BRANCH}", "-X", "PATCH"],
            {"sha": commit["sha"], "force": True},
        )
    print(f"已推送到 {REPO}@{BRANCH}")


if __name__ == "__main__":
    sys.exit(main())
