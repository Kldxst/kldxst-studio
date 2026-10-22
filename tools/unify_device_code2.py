#!/usr/bin/env python3
"""统一设备码（第二版）：共享文件保持逐字节一致，旧盐只放在各作独有的 actkey.js。

上一版把 LEGACY_SALT 写进了 actcode.js，破坏了「共享模块必须与幽径一致」的工程约定
（Gomoku/StarRing/Card 的 sync-core 测试会红）。这一版改成：

  actcode.js   共享：只留统一盐 + 通用的 deviceCodeWithSalt()
  legacy.js    共享：用「各作的 LEGACY_DEV_SALT」算历史码
  actkey.js    各作独有：加一行 LEGACY_DEV_SALT
  deviceid.js  共享：缓存最近一次读到的原始标识，供 legacy.js 使用
"""
import os
import re
import shutil

ROOT = os.environ.get("VELA_ROOT", "D:/Vela")
UNIFIED = "yj.dev|"
GAMES = {
    "Advanture": "yj.dev|",
    "AsIWrite": "yj.dev|",
    "AfterDoom": "yj.dev|",
    "Card": "cz.dev|",
    "Gomoku": "sz.dev|",
    "StarRing": "xh.dev|",
    "LordofMysteries": "lm.dev|",
}

ACTCODE_OLD = re.compile(
    r"/\*\*\n \* 设备码的盐。\*\*全作品统一\*\*.*?^}\n",
    re.S | re.M,
)

ACTCODE_NEW = '''/**
 * 设备码的盐。**全作品统一**：同一台手环在任何作品里算出同一个码，
 * 用户只需记一个码，客服也只需问一次。改它等于让所有设备换码，所以别改。
 *
 * 历史遗留的每作不同盐不再写在这里 —— 那是「各作独有的量」，
 * 放在 actkey.js 的 LEGACY_DEV_SALT 里，由 legacy.js 使用。
 */
var DEV_SALT = 'yj.dev|'

/**
 * 原始设备标识 → 8 位设备码。
 * 同一台设备永远算出同一个码（纯函数，没有随机、没有时间）；
 * 卸载重装也不变，因为输入只有硬件标识。
 */
export function deviceCodeFrom(rawId) {
  return deviceCodeWithSalt(DEV_SALT, rawId)
}

/**
 * 用指定盐算设备码。
 * 统一之后只有一处会用到别的盐：legacy.js 用它算「历史码」，
 * 好让统一之前发出的许可证仍能被认回来。
 */
export function deviceCodeWithSalt(salt, rawId) {
  if (!rawId) return ''
  var digest = sha256(utf8Bytes(salt + String(rawId)))
  var s = ''
  for (var i = 0; i < DEVICE_CODE_LEN; i++) {
    s += CODE_ALPHABET.charAt(digest[i] % CODE_ALPHABET.length)
  }
  return s
}
'''

LEGACY_JS = '''/**
 * 历史设备码（共享模块，逐字节同步）
 *
 * 背景：统一之前每个作品用自己的盐算设备码，同一只手环在六款游戏里显示六个码。
 * 统一之后新码一致，但**已经发出去的许可证仍然绑在旧码上** —— 直接换算法会让
 * 所有老用户掉授权。
 *
 * 解决办法：取授权时把「新码 + 旧码」一起报给激活站，服务器按两者之一查库；
 * 手环侧 license.js 也同时接受两者。旧码只在迁移期用得上，算不出来就返回空串。
 */
import { deviceCodeWithSalt } from './actcode.js'
import { LEGACY_DEV_SALT } from './actkey.js'

/** 统一后所有作品共用的盐。与 actcode.js 的 DEV_SALT 必须一致 */
var UNIFIED_SALT = 'yj.dev|'

/**
 * 用历史盐算设备码。
 * @param {string} rawId 原始硬件标识（device.getDeviceId 或序列号）
 * @returns {string} 8 位旧码；本作本来就是统一盐、或没有原始标识时返回 ''
 */
export function legacyDeviceCodeFrom(rawId) {
  if (!rawId) return ''
  if (!LEGACY_DEV_SALT || LEGACY_DEV_SALT === UNIFIED_SALT) return ''
  return deviceCodeWithSalt(LEGACY_DEV_SALT, rawId)
}
'''


def read(p):
    with open(p, encoding="utf-8") as fh:
        return fh.read()


def write(p, s):
    with open(p, "w", encoding="utf-8", newline="\n") as fh:
        fh.write(s)


def fix_actcode(path):
    src = read(path)
    if "deviceCodeWithSalt" in src and "LEGACY_SALT" not in src:
        return False
    m = ACTCODE_OLD.search(src)
    if not m:
        raise SystemExit(f"actcode 块不匹配：{path}")
    src = src[: m.start()] + ACTCODE_NEW + src[m.end():]
    # 文件头公式注释
    src = re.sub(
        r"设备码 = base30\( SHA256\('yj\.dev\|' \+ 原始设备标识\) 的前 8 字节 \)\n \* （全作品统一；旧盐只用于把历史授权认回来，见 LEGACY_SALT）",
        "设备码 = base30( SHA256('yj.dev|' + 原始设备标识) 的前 8 字节 )\n * （全作品统一；历史盐见 actkey.js 的 LEGACY_DEV_SALT）",
        src,
    )
    write(path, src)
    return True


def fix_deviceid(path):
    src = read(path)
    if "lastRawDeviceId" in src:
        return False
    # 在第一个 export function 前插入缓存与读取器
    marker = "export function readDeviceCode(cb) {"
    if marker not in src:
        raise SystemExit(f"deviceid 结构不符：{path}")
    src = src.replace(
        marker,
        "/** 最近一次读到的原始硬件标识：统一设备码后，legacy.js 要用它算历史码 */\n"
        "var lastRaw = null\n"
        "\n"
        "/** 给 legacy.js 用；没读过时返回 '' */\n"
        "export function lastRawDeviceId() {\n"
        "  return lastRaw === null ? '' : lastRaw\n"
        "}\n"
        "\n" + marker,
        1,
    )
    # 两处拿到 raw 的地方都记下来
    src = re.sub(
        r"(var raw = data && data\.deviceId\n)",
        r"\1        if (raw) lastRaw = String(raw)\n",
        src,
    )
    src = re.sub(
        r"(var raw = data && data\.serial\n)",
        r"\1        if (raw) lastRaw = String(raw)\n",
        src,
    )
    if "lastRaw = String(raw)" not in src:
        raise SystemExit(f"没找到 raw 赋值点：{path}")
    write(path, src)
    return True


def fix_pull(path):
    """共享 pull.js：旧码从 legacy.js + deviceid.js 取，不再从 actcode 取"""
    src = read(path)
    src = src.replace(
        "import { legacyDeviceCode } from './actcode.js'",
        "import { legacyDeviceCodeFrom } from './legacy.js'\nimport { lastRawDeviceId } from './deviceid.js'",
    )
    src = src.replace("legacyDeviceCode()", "legacyDeviceCodeFrom(lastRawDeviceId())")
    write(path, src)
    return True


def fix_cloud(path):
    src = read(path)
    src = src.replace(
        "import { legacyDeviceCode } from './actcode.js'",
        "import { legacyDeviceCodeFrom } from './legacy.js'\nimport { lastRawDeviceId } from './deviceid.js'",
    )
    src = src.replace("legacyDeviceCode()", "legacyDeviceCodeFrom(lastRawDeviceId())")
    write(path, src)
    return True


def add_legacy_salt(actkey_path, salt):
    src = read(actkey_path)
    if "LEGACY_DEV_SALT" in src:
        return False
    marker = "export var GAME_ID"
    if marker not in src:
        raise SystemExit(f"actkey 结构不符：{actkey_path}")
    block = (
        "/**\n"
        " * 统一设备码之前本作使用的盐。\n"
        " * 只用于算「历史设备码」，好让老用户的许可证在更新后仍能被认回来。\n"
        " * 与统一盐相同时 legacy.js 会直接返回空串（说明本作本来就没错过码）。\n"
        " */\n"
        f"export var LEGACY_DEV_SALT = '{salt}'\n\n"
    )
    src = src.replace(marker, block + marker, 1)
    write(actkey_path, src)
    return True


def main():
    changes = []
    # 1) 幽径为源：写共享文件
    adv = os.path.join(ROOT, "Advanture/src/common/core")
    if fix_actcode(os.path.join(adv, "actcode.js")):
        changes.append("actcode.js (Advanture)")
    write(os.path.join(adv, "legacy.js"), LEGACY_JS)
    changes.append("legacy.js (new)")
    if fix_deviceid(os.path.join(adv, "deviceid.js")):
        changes.append("deviceid.js (Advanture)")
    fix_pull(os.path.join(adv, "pull.js"))
    changes.append("pull.js (Advanture)")
    fix_cloud(os.path.join(adv, "cloud.js"))
    changes.append("cloud.js (Advanture)")
    add_legacy_salt(os.path.join(adv, "actkey.js"), GAMES["Advanture"])
    changes.append("actkey.js (Advanture)")

    # 2) 其余作品：共享文件直接照搬，actkey 各写各的
    shared = ["actcode.js", "legacy.js", "deviceid.js", "license.js", "pull.js"]
    for game, salt in GAMES.items():
        if game == "Advanture":
            continue
        core = os.path.join(ROOT, game, "src/common/core")
        if not os.path.isdir(core):
            changes.append(f"缺失 {core}")
            continue
        for name in shared:
            src = os.path.join(adv, name)
            dst = os.path.join(core, name)
            # 只覆盖本作已有的文件；没有 pull.js 的（末日之后）跳过
            if name == "pull.js" and not os.path.exists(dst):
                continue
            shutil.copyfile(src, dst)
        if os.path.exists(os.path.join(core, "cloud.js")):
            fix_cloud(os.path.join(core, "cloud.js"))
        add_legacy_salt(os.path.join(core, "actkey.js"), salt)
        changes.append(f"{game}: 共享模块同步 + LEGACY_DEV_SALT={salt}")

    # 3) 末日之后：device.js 自己实现读取，补缓存
    ad_device = os.path.join(ROOT, "AfterDoom/src/common/core/device.js")
    if os.path.exists(ad_device):
        src = read(ad_device)
        if "lastRawDeviceId" not in src:
            src = src.replace(
                "export function readDeviceCode(cb) {",
                "var lastRaw = null\n"
                "export function lastRawDeviceId() { return lastRaw === null ? '' : lastRaw }\n\n"
                "export function readDeviceCode(cb) {",
                1,
            )
            src = re.sub(r"finish\(deviceCodeFrom\(raw\)", r"lastRaw = String(raw); finish(deviceCodeFrom(raw)", src)
            src = re.sub(r"cb\(deviceCodeFrom\(raw\)", r"lastRaw = String(raw); cb(deviceCodeFrom(raw)", src)
            write(ad_device, src)
            changes.append("AfterDoom/device.js 缓存原始标识")
        act = os.path.join(ROOT, "AfterDoom/src/common/core/activation.js")
        s = read(act)
        s = s.replace(
            "import { legacyDeviceCode } from './actcode.js'",
            "import { legacyDeviceCodeFrom } from './legacy.js'\nimport { lastRawDeviceId } from './device.js'",
        )
        s = s.replace("legacyDeviceCode()", "legacyDeviceCodeFrom(lastRawDeviceId())")
        write(act, s)
        changes.append("AfterDoom/activation.js 用共享 legacy")

    # 4) 如我所书：net.js + .ux
    net = os.path.join(ROOT, "AsIWrite/src/common/core/net.js")
    s = read(net)
    s = s.replace(
        "import { legacyDeviceCode } from './actcode.js'",
        "import { legacyDeviceCodeFrom } from './legacy.js'\nimport { lastRawDeviceId } from './deviceid.js'",
    )
    s = s.replace("legacyDeviceCode()", "legacyDeviceCodeFrom(lastRawDeviceId())")
    write(net, s)
    changes.append("AsIWrite/net.js")
    for rel in ("AsIWrite/src/pages/index/index.ux", "AsIWrite/src/pages/settings/settings.ux"):
        p = os.path.join(ROOT, rel)
        s = read(p)
        s = s.replace(
            "import { legacyDeviceCode } from '../../common/core/actcode.js'",
            "import { legacyDeviceCodeFrom } from '../../common/core/legacy.js'\n"
            "  import { lastRawDeviceId } from '../../common/core/deviceid.js'",
        )
        s = s.replace("legacyDeviceCode()", "legacyDeviceCodeFrom(lastRawDeviceId())")
        write(p, s)
        changes.append(rel)

    print("\n".join(changes))


if __name__ == "__main__":
    main()
