/**
 * K社工坊 · 控制台服务端
 *
 * 职责只有三件：
 *   1. 托管控制台页面（静态资源，改完 deploy 即生效，不用重装 APK）
 *   2. 下发配置：功能开关、网桥默认策略、文案、公告
 *   3. 提供需要服务端身份的接口：作品清单、授权查询、临时令牌
 *
 * 所有机密走 wrangler secret，不落仓库：
 *   npx wrangler secret put STUDIO_SECRET
 *   npx wrangler secret put ACT_ORIGIN      # 可选，默认 https://active.kldxst.me
 */

const VERSION = '1.1.0'
const DEFAULT_ACT_ORIGIN = 'https://active.kldxst.me'
const ALLOWED_ORIGIN = 'https://studio.kldxst.me'

/** 网桥默认策略：由控制台读走，用户改完写回本机加密存储 */
const DEFAULT_BRIDGE = {
  allowPackages: null,
  allowHosts: [],
  blockHosts: [],
  allowHttp: false,
  maxResponseBytes: 8388608,
  timeoutMs: 20000,
  maxRequestsPerMinute: 240,
  chunkSize: 4096,
  ackWindow: 4
}

function json(data, init = {}) {
  return new Response(JSON.stringify(data), {
    ...init,
    headers: {
      'content-type': 'application/json; charset=utf-8',
      'cache-control': 'no-store',
      'x-content-type-options': 'nosniff',
      ...(init.headers || {})
    }
  })
}

function no(message, status = 400) {
  return json({ ok: false, message }, { status })
}

function actOrigin(env) {
  return (env.ACT_ORIGIN || DEFAULT_ACT_ORIGIN).replace(/\/$/, '')
}

/* ------------------------------------------------------------------ */
/*  令牌：给「需要服务端身份」的调用签发短期凭证                          */
/* ------------------------------------------------------------------ */

const enc = new TextEncoder()

async function hmacHex(secret, msg) {
  const key = await crypto.subtle.importKey(
    'raw', enc.encode(secret), { name: 'HMAC', hash: 'SHA-256' }, false, ['sign']
  )
  const sig = await crypto.subtle.sign('HMAC', key, enc.encode(msg))
  return [...new Uint8Array(sig)].map((b) => b.toString(16).padStart(2, '0')).join('')
}

async function issueToken(env, device) {
  const secret = env.STUDIO_SECRET
  if (!secret) return null
  const exp = Date.now() + 10 * 60 * 1000
  const sig = await hmacHex(secret, `studio|${device}|${exp}`)
  return { token: `${exp}.${sig}`, exp }
}

function constantTimeEqual(a, b) {
  const x = String(a)
  const y = String(b)
  if (x.length !== y.length) return false
  let diff = 0
  for (let i = 0; i < x.length; i++) diff |= x.charCodeAt(i) ^ y.charCodeAt(i)
  return diff === 0
}

async function verifyToken(env, device, token) {
  const secret = env.STUDIO_SECRET
  if (!secret || !token) return false
  const [expStr, sig] = String(token).split('.')
  const exp = Number(expStr)
  const now = Date.now()
  if (!Number.isFinite(exp) || exp < now || exp > now + 15 * 60 * 1000) return false
  const want = await hmacHex(secret, `studio|${device}|${exp}`)
  return constantTimeEqual(sig, want)
}

/* ------------------------------------------------------------------ */
/*  作品清单与授权查询                                                  */
/* ------------------------------------------------------------------ */

async function fetchGames(env) {
  const r = await fetch(`${actOrigin(env)}/api/games`, {
    headers: { accept: 'application/json' },
    cf: { cacheTtl: 300, cacheEverything: true }
  })
  if (!r.ok) throw new Error(`上游 ${r.status}`)
  const data = await r.json()
  return Array.isArray(data.games) ? data.games : []
}

async function claimStatus(env, device, gameId) {
  const r = await fetch(`${actOrigin(env)}/api/claim`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ device, game: gameId })
  })
  // 404 = 还没兑换，这是正常状态，不是错误
  if (r.status === 404) return false
  if (!r.ok) return null
  const data = await r.json().catch(() => null)
  return !!(data && data.ok)
}

/* ------------------------------------------------------------------ */

export default {
  async fetch(request, env, ctx) {
    const url = new URL(request.url)
    const path = url.pathname

    // 静态资源由 assets 绑定处理，走到这里的都是 API 或未命中
    if (path.startsWith('/api/')) {
      try {
        return await handleApi(request, env, path)
      } catch (e) {
        return json({ ok: false, message: String(e && e.message || e) }, { status: 500 })
      }
    }

    if (path === '/health') {
      return json({ ok: true, version: VERSION, at: Date.now() })
    }

    return env.ASSETS ? env.ASSETS.fetch(request) : new Response('Not Found', { status: 404 })
  }
}

async function handleApi(request, env, path) {
  const method = request.method.toUpperCase()

  if (path === '/api/config') {
    if (method !== 'GET') return no('方法不允许', 405)
    return json({
      ok: true,
      config: {
        version: VERSION,
        notice: null,
        features: {
          wear: true,
          notify: true,
          bridge: true,
          games: true,
          activation: true,
          rpkVerify: true,
          // 安装（把 rpk 写进手环）需要自研蓝牙协议栈，当前版本未开放
          installPush: false
        },
        bridge: DEFAULT_BRIDGE,
        links: {
          site: 'https://active.kldxst.me',
          forum: 'https://www.bandbbs.cn/'
        }
      }
    }, { headers: { 'cache-control': 'public, max-age=60' } })
  }

  if (path === '/api/games') {
    if (method !== 'GET') return no('方法不允许', 405)
    const games = await fetchGames(env)
    return json({ ok: true, games }, {
      headers: { 'cache-control': 'public, max-age=120' }
    })
  }

  if (path === '/api/license-status') {
    if (method !== 'POST') return no('方法不允许', 405)
    const body = await request.json().catch(() => null)
    const device = String((body && body.device) || '').trim().toUpperCase()
    if (!/^[23456789ABCDEFGHJKMNPQRSTVWXYZ]{8}$/.test(device)) return no('设备码无效')

    const games = await fetchGames(env)
    const want = Array.isArray(body && body.games) ? body.games : games.map((g) => g.id)
    const results = []
    for (const g of games) {
      if (!want.includes(g.id)) continue
      const licensed = await claimStatus(env, device, g.id)
      results.push({ id: g.id, name: g.name, licensed: licensed === true, unknown: licensed === null })
    }
    return json({ ok: true, device, results })
  }

  if (path === '/api/token') {
    if (method !== 'POST') return no('方法不允许', 405)
    const body = await request.json().catch(() => null)
    const device = String((body && body.device) || '').trim().toUpperCase()
    if (!/^[23456789ABCDEFGHJKMNPQRSTVWXYZ]{8}$/.test(device)) return no('设备码无效')
    const t = await issueToken(env, device)
    if (!t) return no('服务端未配置 STUDIO_SECRET', 503)
    return json({ ok: true, ...t })
  }

  if (path === '/api/cloud/status') {
    if (method !== 'POST') return no('方法不允许', 405)
    const body = await request.json().catch(() => null)
    const device = String((body && body.device) || '').trim().toUpperCase()
    const token = request.headers.get('x-studio-token') || ''
    if (!(await verifyToken(env, device, token))) return no('令牌无效或已过期', 401)
    const games = await fetchGames(env)
    const results = []
    for (const g of games) {
      results.push({ id: g.id, name: g.name, licensed: (await claimStatus(env, device, g.id)) === true })
    }
    return json({ ok: true, device, results })
  }

  if (path === '/api/manifest') {
    return json({
      ok: true,
      version: VERSION,
      note: '控制台为静态资源，版本号随部署更新；APK 侧只需要能加载本域。'
    })
  }

  return no('未知接口', 404)
}
