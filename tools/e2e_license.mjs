/**
 * 许可证端到端仿真（真签名 → 真验签，含「旧码兜底」路径）
 *
 * 目的：证明统一设备码之后，两类许可证都还能被设备端接受
 *   1. 新码签发的新许可证
 *   2. 统一之前用旧码签发的许可证（手环传 legacy 时才认）
 *
 * 做法：生成一对临时 e=3 密钥 → 用 Node 的 RSASSA-PKCS1-v1_5 签 payload →
 * 把公钥写进一份临时 actkey.js → 用**作品里那份 license.js / rsa.js 的原样副本**验签。
 * 这样验的是真代码，不是另写一份逻辑。
 */
import { createSign, generateKeyPairSync } from 'node:crypto'
import { copyFileSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'
import { pathToFileURL } from 'node:url'

const CORE = 'D:/Vela/Advanture/src/common/core'
const TMP = 'D:/Vela/.tmp_license_sim'

function toLimbs(n) {
  // 设备端用 15 位 limb、**低位在前**（rsa.js 的 BITS=15 / BASE=32768）
  const out = []
  let v = n
  while (v > 0n) {
    out.push(Number(v & 32767n))
    v >>= 15n
  }
  return out.length ? out : [0]
}

function b64url(buf) {
  return Buffer.from(buf).toString('base64')
}

function buildActkey({ n, gameId, epoch }) {
  const limbs = toLimbs(n)
  const k = limbs.length
  // mu = floor(2^(30k) / n)：与 mkkeypair.mjs 完全一致的算法
  const mu = toLimbs((1n << BigInt(30 * k)) / n)
  while (mu.length < k + 1) mu.push(0)
  return `export var LEGACY_DEV_SALT = 'yj.dev|'
export var GAME_ID = '${gameId}'
export var ACT_EPOCH = ${epoch}
export var KEY_BITS = ${k * 15 >= 2048 ? 2048 : 1024}
export var N = [${limbs.join(', ')}]
export var MU = [${mu.join(', ')}]
`
}

async function main() {
  rmSync(TMP, { recursive: true, force: true })
  mkdirSync(TMP, { recursive: true })

  // e=3：与生产一致（设备端只为 e=3 做了快速路径）
  const { publicKey, privateKey } = generateKeyPairSync('rsa', {
    modulusLength: 2048,
    publicExponent: 3
  })
  const jwk = publicKey.export({ format: 'jwk' })
  const n = BigInt('0x' + Buffer.from(jwk.n, 'base64url').toString('hex'))

  for (const f of ['rsa.js', 'license.js', 'actcode.js', 'legacy.js']) {
    copyFileSync(join(CORE, f), join(TMP, f))
  }
  writeFileSync(join(TMP, 'actkey.js'), buildActkey({ n, gameId: 'youjing', epoch: 1 }), 'utf8')

  const mod = await import(pathToFileURL(join(TMP, 'license.js')).href)
  const act = await import(pathToFileURL(join(TMP, 'actcode.js')).href)

  const raw = 'E2E-DEVICE-0001'
  const newCode = act.deviceCodeFrom(raw)
  const oldCode = act.deviceCodeWithSalt('cz.dev|', raw)
  const day = Math.floor(Date.now() / 86400000)

  function sign(deviceCode) {
    const payload = `${deviceCode}|youjing|1|${day}`
    const signer = createSign('RSA-SHA256')
    signer.update(payload)
    signer.end()
    const sig = signer.sign(privateKey)
    return payload + '.' + b64url(sig)
  }

  const results = []
  const check = (name, ok, detail) => results.push({ name, ok, detail })

  // 1) 新码签的许可证，用新码验 —— 必须过
  const licNew = sign(newCode)
  let v = mod.verifyLicense(licNew, newCode, '')
  check('新码许可证 + 新码', v.ok === true, JSON.stringify(v))

  // 2) 新码签的许可证，用旧码验 —— 必须拒（证明绑定没被放松）
  v = mod.verifyLicense(licNew, oldCode, '')
  check('新码许可证 + 旧码（应拒）', v.ok === false, JSON.stringify(v))

  // 3) 旧码签的许可证（统一之前发出的那种），只给新码 —— 必须拒
  const licOld = sign(oldCode)
  v = mod.verifyLicense(licOld, newCode, '')
  check('旧码许可证 + 只给新码（应拒）', v.ok === false, JSON.stringify(v))

  // 4) 旧码签的许可证，新码 + legacy 一起给 —— 必须过（这就是迁移路径）
  v = mod.verifyLicense(licOld, newCode, oldCode)
  check('旧码许可证 + 新码&旧码（迁移路径）', v.ok === true, JSON.stringify(v))

  // 5) 篡改签名 —— 必须拒
  const tampered = licOld.slice(0, -4) + (licOld.endsWith('AAAA') ? 'BBBB' : 'AAAA')
  v = mod.verifyLicense(tampered, newCode, oldCode)
  check('篡改签名（应拒）', v.ok === false, JSON.stringify(v))

  // 6) 错游戏 —— 必须拒
  const wrongGame = `${oldCode}|chaizhao|1|${day}.` + licOld.split('.')[1]
  v = mod.verifyLicense(wrongGame, newCode, oldCode)
  check('错游戏（应拒）', v.ok === false, JSON.stringify(v))

  // 7) 跨作品设备码一致性（7 个作品同一 raw → 同一码）
  const games = {
    Advanture: 'Advanture/tools/_esm/core/actcode.js',
    AsIWrite: 'AsIWrite/tools/_esm/core/actcode.js',
    AfterDoom: 'AfterDoom/src/common/core/actcode.js',
    Gomoku: 'Gomoku/tools/_esm/core/actcode.js',
    StarRing: 'StarRing/tools/_esm/core/actcode.js',
    Card: 'Card/tools/_esm/core/actcode.js',
    LordofMysteries: 'LordofMysteries/tools/_esm/core/actcode.js'
  }
  const codes = {}
  for (const [name, rel] of Object.entries(games)) {
    const m = await import(pathToFileURL('D:/Vela/' + rel).href)
    codes[name] = m.deviceCodeFrom(raw)
  }
  const uniq = new Set(Object.values(codes))
  check('七作设备码一致', uniq.size === 1, JSON.stringify(codes))

  // 8) 历史码：改了盐的四作必须与统一码不同，没改的三作必须相同
  const legacyExpect = {
    Advanture: newCode, AsIWrite: newCode, AfterDoom: newCode,
    Gomoku: 'sz.dev|', StarRing: 'xh.dev|', Card: 'cz.dev|', LordofMysteries: 'lm.dev|'
  }
  let legacyOk = true
  const legacyDetail = {}
  for (const [name, salt] of Object.entries(legacyExpect)) {
    const rel = games[name]
    const m = await import(pathToFileURL('D:/Vela/' + rel).href)
    const legacyFn = await import(pathToFileURL('D:/Vela/' + rel.replace('actcode.js', 'legacy.js')).href)
      .catch(() => null)
    if (!legacyFn) { legacyDetail[name] = 'no legacy.js'; continue }
    const lc = legacyFn.legacyDeviceCodeFrom(raw)
    const expected = salt === newCode ? '' : act.deviceCodeWithSalt(salt, raw)
    legacyDetail[name] = lc || '(空)'
    if (lc !== expected) legacyOk = false
  }
  check('历史码按各作旧盐计算', legacyOk, JSON.stringify(legacyDetail))

  // 输出
  let pass = 0
  for (const r of results) {
    if (r.ok) pass++
    console.log(`${r.ok ? '✅' : '❌'} ${r.name}${r.ok ? '' : '  ← ' + r.detail}`)
  }
  console.log(`\n${pass}/${results.length} 通过`)
  console.log('统一码 =', newCode, '｜历史码 =', oldCode)
  rmSync(TMP, { recursive: true, force: true })
  process.exit(pass === results.length ? 0 : 1)
}

main()
