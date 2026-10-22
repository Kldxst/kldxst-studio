#!/usr/bin/env python3
"""统一设备码：把各作品的盐收敛到同一个，并保留旧码做授权迁移。

改动点（每个作品一致）：
  1. actcode.js：DEV_SALT 统一为 'yj.dev|'，原盐降级为 LEGACY_SALT，
     新增 legacyDeviceCode()（用旧盐算历史码）
  2. license.js：verifyLicense 增加第三个参数 legacyCode，两个码都认
  3. 取授权的请求体加 legacy 字段，验签时把旧码一起传进去

这样：老用户更新后取授权，服务器按「新码或旧码」都能查到，
许可证（仍绑旧码）也能验过 —— 不掉授权。
"""
import os
import re
import sys

ROOT = os.environ.get("VELA_ROOT", "D:/Vela")
GAMES = {
    "Advanture": "yj.dev|",
    "AsIWrite": "yj.dev|",
    "AfterDoom": "yj.dev|",
    "Card": "cz.dev|",
    "Gomoku": "sz.dev|",
    "StarRing": "xh.dev|",
    "LordofMysteries": "lm.dev|",
}
UNIFIED = "yj.dev|"

report = []


def read(p):
    with open(p, encoding="utf-8") as fh:
        return fh.read()


def write(p, text):
    with open(p, "w", encoding="utf-8", newline="\n") as fh:
        fh.write(text)


def patch_actcode(path, legacy_salt):
    src = read(path)
    if "LEGACY_SALT" in src:
        report.append(f"跳过（已统一） {path}")
        return
    old_block = re.search(
        r"/\*\* 设备码的盐。改它等于让所有设备换码，所以别改。 \*/\nvar DEV_SALT = '([^']+)'",
        src,
    )
    if not old_block:
        raise SystemExit(f"找不到 DEV_SALT 块：{path}")
    cur_salt = old_block.group(1)
    if cur_salt != legacy_salt:
        raise SystemExit(f"{path} 的盐与预期不符：{cur_salt} != {legacy_salt}")

    new_block = (
        "/**\n"
        " * 设备码的盐。**全作品统一**：同一台手环在任何作品里算出同一个码，\n"
        " * 用户只需记一个码，客服也只需问一次。改它等于让所有设备换码，所以别改。\n"
        " */\n"
        f"var DEV_SALT = '{UNIFIED}'\n"
        "\n"
        "/**\n"
        " * 历史盐：本作品在统一之前用过的那一个。\n"
        " * 只用来算「旧码」把已发出的授权认回来，新逻辑一律走 DEV_SALT。\n"
        " */\n"
        f"var LEGACY_SALT = '{legacy_salt}'\n"
        "var lastRawId = null"
    )
    src = src.replace(old_block.group(0), new_block, 1)

    old_fn = (
        "export function deviceCodeFrom(rawId) {\n"
        "  if (!rawId) return ''\n"
        "  var digest = sha256(utf8Bytes(DEV_SALT + String(rawId)))\n"
        "  var s = ''\n"
        "  for (var i = 0; i < DEVICE_CODE_LEN; i++) {\n"
        "    s += CODE_ALPHABET.charAt(digest[i] % CODE_ALPHABET.length)\n"
        "  }\n"
        "  return s\n"
        "}"
    )
    new_fn = (
        "export function deviceCodeFrom(rawId) {\n"
        "  if (!rawId) return ''\n"
        "  lastRawId = String(rawId)\n"
        "  return codeFromSalt(DEV_SALT, lastRawId)\n"
        "}\n"
        "\n"
        "/**\n"
        " * 旧盐算出的历史设备码。\n"
        " *\n"
        " * 统一设备码之后，老用户的许可证仍绑在旧码上。取授权时把旧码一并报给服务器，\n"
        " * 服务器按「新码或旧码」查库，license.js 也同时接受两者 —— 老授权因此不会丢。\n"
        " * 没读过原始标识时返回空串（这时本来也取不到授权）。\n"
        " */\n"
        "export function legacyDeviceCode() {\n"
        "  if (lastRawId === null) return ''\n"
        "  if (LEGACY_SALT === DEV_SALT) return codeFromSalt(DEV_SALT, lastRawId)\n"
        "  return codeFromSalt(LEGACY_SALT, lastRawId)\n"
        "}\n"
        "\n"
        "function codeFromSalt(salt, rawId) {\n"
        "  var digest = sha256(utf8Bytes(salt + rawId))\n"
        "  var s = ''\n"
        "  for (var i = 0; i < DEVICE_CODE_LEN; i++) {\n"
        "    s += CODE_ALPHABET.charAt(digest[i] % CODE_ALPHABET.length)\n"
        "  }\n"
        "  return s\n"
        "}"
    )
    if old_fn not in src:
        raise SystemExit(f"deviceCodeFrom 结构不符：{path}")
    src = src.replace(old_fn, new_fn, 1)

    # 文件头注释里的算法说明同步
    src = re.sub(
        r"设备码 = base30\( SHA256\('" + re.escape(legacy_salt) + r"' \+ 原始设备标识\) 的前 8 字节 \)",
        "设备码 = base30( SHA256('" + UNIFIED + "' + 原始设备标识) 的前 8 字节 )\n"
        " * （全作品统一；旧盐只用于把历史授权认回来，见 LEGACY_SALT）",
        src,
        count=1,
    )
    write(path, src)
    report.append(f"actcode  {path}  {legacy_salt} → {UNIFIED}")


def patch_license(path):
    src = read(path)
    if "legacyCode" in src:
        report.append(f"跳过（已支持旧码） {path}")
        return
    src2 = src.replace(
        "export function verifyLicense(lic, deviceCode) {",
        "export function verifyLicense(lic, deviceCode, legacyCode) {",
        1,
    )
    if src2 == src:
        raise SystemExit(f"verifyLicense 签名不符：{path}")
    old_check = "  if (parts[0] !== deviceCode) return bad('许可证与本机设备码不符')"
    new_check = (
        "  // 统一设备码之后，老许可证仍绑在旧码上：两个码都认，避免更新后掉授权\n"
        "  if (parts[0] !== deviceCode && !(legacyCode && parts[0] === legacyCode)) {\n"
        "    return bad('许可证与本机设备码不符')\n"
        "  }"
    )
    if old_check not in src2:
        raise SystemExit(f"设备码比对语句不符：{path}")
    src2 = src2.replace(old_check, new_check, 1)
    write(path, src2)
    report.append(f"license {path}  接受旧码")


def add_import(path, imp):
    src = read(path)
    if imp in src:
        return
    anchor = "import { b64decode, verifyLicense } from './license.js'"
    if anchor in src:
        src = src.replace(anchor, anchor + "\n" + imp, 1)
    else:
        m = re.search(r"^import .*verifyLicense.*\n", src, re.M)
        if not m:
            # 这个文件根本没有验签逻辑（各作品 cloud.js 实现不一），跳过
            return
        src = src[: m.end()] + imp + "\n" + src[m.end():]
    write(path, src)
    report.append(f"import  {path}")


def patch_claim(path, body_old, body_new):
    src = read(path)
    if "legacy: legacyDeviceCode()" in src:
        report.append(f"跳过（已带旧码） {path}")
        return
    if body_old not in src:
        raise SystemExit(f"取授权请求体不符：{path}\n期望: {body_old}")
    src = src.replace(body_old, body_new)
    write(path, src)
    report.append(f"claim   {path}")


def patch_verify_calls(path, pairs):
    src = read(path)
    hit = 0
    for old, new in pairs:
        if old not in src:
            continue
        src = src.replace(old, new)
        hit += 1
    if hit == 0:
        report.append(f"跳过（无匹配调用） {path}")
        return
    write(path, src)
    report.append(f"verify  {path}")


def main():
    for game, salt in GAMES.items():
        base = os.path.join(ROOT, game)
        if not os.path.isdir(base):
            report.append(f"缺失 {base}")
            continue
        for rel in ("src/common/core/actcode.js", "tools/_esm/core/actcode.js"):
            p = os.path.join(base, rel)
            if os.path.exists(p):
                patch_actcode(p, salt)
        for rel in ("src/common/core/license.js", "tools/_esm/core/license.js"):
            p = os.path.join(base, rel)
            if os.path.exists(p):
                patch_license(p)

        # 取授权 + 验签
        for rel in ("src/common/core/pull.js", "tools/_esm/core/pull.js"):
            p = os.path.join(base, rel)
            if not os.path.exists(p):
                continue
            add_import(p, "import { legacyDeviceCode } from './actcode.js'")
            patch_claim(
                p,
                "{ device: device, game: gameId }",
                "{ device: device, game: gameId, legacy: legacyDeviceCode() }",
            )
            patch_verify_calls(
                p,
                [("verifyLicense(lic, device)", "verifyLicense(lic, device, legacyDeviceCode())")],
            )

        for rel in ("src/common/core/cloud.js", "tools/_esm/core/cloud.js"):
            p = os.path.join(base, rel)
            if not os.path.exists(p):
                continue
            add_import(p, "import { legacyDeviceCode } from './actcode.js'")
            patch_verify_calls(
                p,
                [
                    ("verifyLicense(lic, device)", "verifyLicense(lic, device, legacyDeviceCode())"),
                    ("verifyLicense(lic, data.device)", "verifyLicense(lic, data.device, legacyDeviceCode())"),
                ],
            )

    # 如我所书：claim 在 net.js，验签在 .ux 页面
    p = os.path.join(ROOT, "AsIWrite/src/common/core/net.js")
    if os.path.exists(p):
        add_import(p, "import { legacyDeviceCode } from './actcode.js'")
        patch_claim(
            p,
            "body: { device: device, game: GAME_ID }",
            "body: { device: device, game: GAME_ID, legacy: legacyDeviceCode() }",
        )
    for rel in ("AsIWrite/src/pages/index/index.ux", "AsIWrite/src/pages/settings/settings.ux"):
        p = os.path.join(ROOT, rel)
        if not os.path.exists(p):
            continue
        src = read(p)
        if "legacyDeviceCode" in src:
            continue
        src = src.replace(
            "import { verifyLicense } from '../../common/core/license.js'",
            "import { verifyLicense } from '../../common/core/license.js'\n"
            "  import { legacyDeviceCode } from '../../common/core/actcode.js'",
            1,
        )
        src = re.sub(r"verifyLicense\(lic, code\)", "verifyLicense(lic, code, legacyDeviceCode())", src)
        src = re.sub(r"verifyLicense\(lic, code \|\| ''\)", "verifyLicense(lic, code || '', legacyDeviceCode())", src)
        write(p, src)
        report.append(f"verify  {p}")

    # 末日之后：claim 在 activation.js
    p = os.path.join(ROOT, "AfterDoom/src/common/core/activation.js")
    if os.path.exists(p):
        add_import(p, "import { legacyDeviceCode } from './actcode.js'")
        patch_claim(
            p,
            "{ device: device, game: 'afterdoom' }",
            "{ device: device, game: 'afterdoom', legacy: legacyDeviceCode() }",
        )
        patch_verify_calls(
            p,
            [
                ("verifyLicense(lic, device)", "verifyLicense(lic, device, legacyDeviceCode())"),
                ("verifyLicense(data.lic, device)", "verifyLicense(data.lic, device, legacyDeviceCode())"),
            ],
        )

    print("\n".join(report))
    print(f"\n共 {len(report)} 处")


if __name__ == "__main__":
    sys.exit(main())
