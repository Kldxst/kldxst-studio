# K社工坊 · Kldxst Studio

面向 Kldxst 全部手环作品的手机端工作台：连接设备、管理作品、代理联网、发放授权。
**APK 只打包一次**，之后所有界面与流程改动都在服务端（`studio.kldxst.me`）完成。

```
┌─────────────────────────── APK（固定，不再重发） ───────────────────────────┐
│  MainActivity ── WebView ──► https://studio.kldxst.me   ← 界面/流程/清单都在这 │
│        │                                                                     │
│        └─ ConsoleBridge（来源白名单 + 会话令牌）                              │
│              ├─ WearLink      设备通道（官方 XMS Wearable SDK）               │
│              ├─ BridgeEngine  网桥（FetchBridge v1–v3 协议）                  │
│              └─ Vault         Keystore AES-GCM 本地加密                       │
└──────────────────────────────────────────────────────────────────────────────┘
                                    │
                     ┌──────────────┴───────────────┐
                     │  Worker: kldxst-studio        │
                     │  /            控制台单页       │
                     │  /api/config  功能开关+策略     │
                     │  /api/games   作品清单（代理）  │
                     │  /api/license-status 授权查询  │
                     │  /api/token   临时令牌         │
                     └───────────────────────────────┘
```

## 为什么这样切

手机端的「会变的东西」远多于「不会变的东西」：

| 会变 | 不会变 |
|---|---|
| 界面、文案、流程 | 与手环通信 |
| 作品清单、下载地址、版本号 | 代快应用发 HTTP |
| 网桥白名单/限额/分片参数 | 本地加密存储 |
| 授权文案与购买链接 | 控制台地址 |

前者放进 Worker，后者留在 APK。所以**加一个作品、改一次界面、调一个网桥参数，都不需要用户重装**。

## 能力现状

| 能力 | 状态 | 通道 |
|---|---|---|
| 枚举/连接已配对设备（官方通道） | ✅ | XMS Wearable SDK（小米运动健康代管蓝牙） |
| 设备状态（电量/充电/佩戴/睡眠） | ✅ | `NodeApi.query` |
| 查询快应用是否安装 / 拉起快应用 | ✅ | `NodeApi` |
| 推送通知到手环 | ✅ | `NotifyApi.sendNotify` |
| 设备管理授权 | ✅ | `AuthApi` |
| **直连扫描（BLE，识别 FE95）** | ✅ | 自建 `DirectLink` |
| **直连连接（SPP RFCOMM 0x1101）** | ✅ | 自建 `DirectLink` |
| **authkey 握手（AES-128-ECB）** | ⚠️ 已实现，回包语义待真机校准 | 自建 `DirectLink` |
| 网桥（代发 HTTP，v1–v3） | ✅ | 自有引擎 + 消息通道 |
| 网桥协议自检（无需手环） | ✅ | 引擎内置 4 条主路径用例 |
| 安装包体检（manifest/哈希/路径穿越） | ✅ | 本地 zip 解析 + SHA-256 |
| 授权状态查询（全作品） | ✅ | Worker → `active.kldxst.me/api/claim` |
| 授权发放 | ✅ | 手环自取（`@system.fetch`）+ 兑换码 |
| 把 rpk 安装进手环 | ❌ | 需完整 MiWear 业务层，见下 |

### 连接方式：直连为主，官方 SDK 兜底

AzkiDeck 与 AstroBox 都不是「让小米运动健康代转」——它们**自己占蓝牙**：
登录小米账号取回设备的 authkey，用 SPP（RFCOMM `0x1101`）或 BLE（服务 `FE95`）直连，
authkey 做握手。前提是**先让小米运动健康让出蓝牙**（否则端口被它占着，连接必失败）。

本仓库按这条路径实现了 `bt/DirectLink.kt`：扫描 → SPP 连接 → authkey 握手 → 帧收发。
帧编码参数（magic、长度字节序、auth 帧类型）**由服务端下发**，校准时只改服务端、不重新打包。

官方 SDK 通道保留下来做兜底：它不需要抢蓝牙，通知推送、拉起快应用、查询安装状态这些
不涉及私有协议的操作可以立刻用。

### 一条仍然存在的硬约束

**把 rpk 写进手环**需要完整的 MiWear 业务层（protobuf 消息集 + 安装编排 + 分片确认），
AzkiDeck 用 2.2MB 的 `libazvfnative.so` 实现，AstroBox 用闭源 Rust core 实现。
本仓库已完成传输与握手，缺的是业务消息层 —— 这一层必须对着真机逐条校准，
所以不在没有设备的情况下假装实现。`install/Rpk.kt` 已把「装之前该检查什么」做完。

### 设备码：全作品统一

2026-10 起七个作品的设备码用同一个盐（`yj.dev|`）推导，同一只手环在任何作品里是**同一个码**。
历史盐（`cz.dev|` / `sz.dev|` / `xh.dev|` / `lm.dev|`）挪到各作 `actkey.js` 的
`LEGACY_DEV_SALT`，只用于把统一之前发出的许可证认回来：

```
手环取授权 → 报 { device: 新码, legacy: 旧码 }
激活站     → 按「新码或旧码」查库（老行仍绑旧码）
手环验签   → 载荷里的设备码等于新码或旧码都接受
```

许可证本身、每作的私钥、epoch、gameId 全部保持独立 —— 统一的只有设备码。

## 安全设计

- **只走 TLS**，`network_security_config` 关闭明文、不信任用户 CA（防抓包改页面）。
- **控制台三道闸**：来源白名单（WebViewClient 维护，不信页面自述）→ 会话令牌（进程级随机，
  仅注入白名单页面）→ 每个方法自行校验入参。
- **网桥 SSRF 防护**：只允许 https、禁 userinfo、禁非 443 端口、解析结果落在
  私有/回环/链路本地/组播一律拒绝；校验过的 IP 直接钉进 OkHttp 的 `Dns`，消除 rebinding 窗口。
- **跨源重定向剥凭据**（`Authorization` / `Cookie` / `Host`），最多 10 跳。
- **机密不落盘明文**：authkey、网桥策略存 Keystore AES-256-GCM 加密后的密文；
  仓库里没有任何私钥，签名材料只存在于 CI secrets 与本机（`.gitignore` 已挡）。
- **安装包体检**：zip 路径穿越、manifest 解析、SHA-256 比对，哈希不符即删文件。

## 构建

```bash
# 本机（需要 JDK 17）
./gradlew assembleRelease        # keystore.properties 存在则用正式签名
./gradlew testDebugUnitTest      # 网桥协议用例，不需要手环
```

推送到 `main` 后 GitHub Actions 自动跑单测 + 打包，产物在 Actions 的
`kldxst-studio-apk` artifact。

签名 secrets（`gh secret set`）：`ANDROID_KEYSTORE_BASE64` / `ANDROID_STORE_PASSWORD` /
`ANDROID_KEY_ALIAS` / `ANDROID_KEY_PASSWORD`。

## 部署控制台

```bash
cd console
npx wrangler secret put STUDIO_SECRET   # 临时令牌签名密钥
npx wrangler deploy                     # 绑定 studio.kldxst.me
```

改完 `console/public/index.html` 再 `deploy` 一次即可，**用户无需重装**。

## 目录

```
app/src/main/java/com/kldxst/studio/
  MainActivity.kt            WebView 容器 + 来源/令牌注入 + 离线兜底页
  console/ConsoleBridge.kt   页面 ⇄ 原生 的唯一入口（含三道闸）
  wear/WearLink.kt           官方 SDK 封装（设备/消息/通知/状态）
  bridge/BridgeEngine.kt     FetchBridge v1–v3 协议引擎（可单测）
  bridge/SsrfGuard.kt        出口守卫
  bridge/OkHttpFetcher.kt    真实出口（手动重定向 + 钉死 DNS）
  install/Rpk.kt             资源包体检
  secure/Vault.kt            Keystore AES-GCM
  service/StudioService.kt   前台服务（网桥常驻）
console/
  public/index.html          控制台单页（改这里不用重装 APK）
  src/index.js               Worker API
```
