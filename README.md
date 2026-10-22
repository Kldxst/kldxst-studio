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
| 枚举/连接已配对设备 | ✅ | 官方 SDK（小米运动健康代管蓝牙） |
| 设备状态（电量/充电/佩戴/睡眠） | ✅ | `NodeApi.query` |
| 查询快应用是否安装 | ✅ | `NodeApi.isWearAppInstalled` |
| 拉起快应用 | ✅ | `NodeApi.launchWearApp` |
| 推送通知到手环 | ✅ | `NotifyApi.sendNotify` |
| 设备管理授权 | ✅ | `AuthApi` |
| 网桥（代发 HTTP，v1–v3） | ✅ | 自有引擎 + SDK 消息通道 |
| 网桥协议自检（无需手环） | ✅ | 引擎内置 4 条主路径用例 |
| 安装包体检（manifest/哈希/路径穿越） | ✅ | 本地 zip 解析 + SHA-256 |
| 授权状态查询（全作品） | ✅ | Worker → `active.kldxst.me/api/claim` |
| 授权发放 | ✅ | 手环自取（`@system.fetch`）+ 兑换码 |
| **把 rpk 安装进手环** | ❌ | 官方 SDK **没有**安装 API |
| **通用网桥（任意包名快应用）** | ❌ | interconnect 身份绑定，见下 |

### 两条硬约束（不是没做，是做不到）

**1. 官方 SDK 没有「安装 rpk」的接口。**
`NodeApi` 只有 `getConnectedNodes / isWearAppInstalled / launchWearApp / query / subscribe / unsubscribe`。
把资源包写进手环要走小米私有蓝牙协议（AzkiDeck 用 2.2MB 的 `libazvfnative.so` 实现，
AstroBox 用闭源 Rust core 实现）。要覆盖这一步只能自研 BLE/SPP 协议栈，
本仓库把位置留好了（`install/Rpk.kt` 已完成体检，缺的只是传输层），但不假装已实现。

**2. interconnect 的身份是「包名 + 签名」双绑定。**
快应用通过 `@system.interconnect` 只能找到 **包名相同、签名相同** 的安卓端。
一个 APK 只有一个 `applicationId`，因此**无法**用一个包同时服务六个作品。
AstroBox 能桥任意包名，是因为它自己实现了蓝牙协议栈，绕过了系统路由。

**结论**：本 APK 的网桥对「与本应用同包名的快应用」生效；其余作品走
**寄存自取**——手环自己 `POST /api/claim` 取回许可证，这条路不需要网桥，
在支持 `@system.fetch` 的机型（9 Pro）上开箱可用。

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
