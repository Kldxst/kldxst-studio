# K社工坊 · Kldxst Studio

手环工作台：**自己占蓝牙直连设备**，管理作品、代发联网、查授权。
APK 只打包一次，界面与流程改动都在服务端（`studio.kldxst.me`）完成。

```
┌────────────────────────── APK（固定，不再重发） ──────────────────────────┐
│  MainActivity ── WebView ──► https://studio.kldxst.me   ← 界面/流程/清单     │
│        │                                                                   │
│        └─ ConsoleBridge（来源白名单 + 会话令牌）                            │
│              ├─ MiWear      直连：BLE(FE95) / SPP(0x1101) + authkey         │
│              ├─ BridgeEngine 网桥（FetchBridge v1–v3）                      │
│              └─ Vault       Keystore AES-GCM                               │
└────────────────────────────────────────────────────────────────────────────┘
                                    │
                     ┌──────────────┴───────────────┐
                     │  Worker: kldxst-studio        │
                     │  /            控制台单页       │
                     │  /api/config  功能开关+策略     │
                     │  /api/games   作品清单（代理）  │
                     │  /api/license-status 授权查询  │
                     └───────────────────────────────┘
```

## 连接方式：直连，无侧链

不经过小米运动健康，不依赖任何第三方 App 代转。手机自己占蓝牙：

```
扫描（BLE，识别 FE95 广播与产品号）
   → 连接（SPP RFCOMM 0x1101 或 BLE GATT FE95）
   → authkey 握手（AES-128-ECB）
   → 帧收发（magic + 类型 + 长度 + 载荷）
```

使用前必须让小米运动健康让出蓝牙，否则 SPP 端口被占、GATT 连上也不通。

## 能力现状

| 能力 | 状态 |
|---|---|
| BLE 扫描（FE95 识别、产品号、RSSI 排序） | ✅ |
| SPP 连接（RFCOMM 0x1101） | ✅ |
| BLE 连接（FE95 服务发现、CCCD 订阅、按 MTU 切片写） | ✅ |
| authkey 握手（AES-128-ECB） | ⚠️ 帧已发出，回包语义待真机校准 |
| 帧编解码（magic/长度字节序/头长可配） | ✅ |
| 命令层（消息号由服务端下发） | ⚠️ 消息号未校准，界面标「未校准」 |
| 收包记录 / 手动发帧 / 按命令发空包 | ✅ |
| 通知转发（系统通知 → 手环） | ⚠️ 依赖 notify 消息号 |
| 网桥（代发 HTTP，v1–v3） | ✅ |
| 网桥协议自检（无需手环） | ✅ |
| 安装包体检（manifest / SHA-256 / 路径穿越） | ✅ |
| 授权状态查询（全作品） | ✅ |
| 安装 / 卸载 / 表盘 / 音乐 / 固件 | ⚠️ 命令位已留，等消息号 |

### 为什么有一列是「待校准」

AzkiDeck 把 MiWear 协议编译进 2.2MB 的 `libazvfnative.so`，AstroBox 的 core 闭源，
两者都没有公开消息集。本仓库把**能确定的部分**（扫描、连接、帧结构、authkey 加密、
命令分发、网桥、哈希校验）全部实现，把**不能确定的部分**（每个业务消息的类型字节与载荷格式）
做成服务端下发的参数：

```
设置 → 协议参数 → 命令消息号：{"notify":16,"applist":32,"install":33,…}
```

真机上抓到一帧就能填一个，填完立即生效，**不需要重新打包**。这是本项目对「协议未知」
唯一诚实的处理方式：不假装功能已经可用。

## 界面

亮色主题，蓝 `#2563EB` / 黄 `#F5B400` / 粉 `#FF6FA5`，四页：设备 / 应用 / 网桥 / 设置。

## 安全设计

- 只走 TLS，关闭明文、不信任用户 CA。
- 控制台三道闸：来源白名单 → 会话令牌 → 每个方法自行校验入参。
- 网桥 SSRF 防护：只允许 https、禁 userinfo、禁非 443 端口、内网/回环/链路本地一律拒绝；
  校验过的 IP 钉进 OkHttp 的 `Dns`，消除 rebinding 窗口。
- 跨源重定向剥凭据，最多 10 跳。
- authkey、协议参数、网桥策略存 Keystore AES-256-GCM 加密后的密文；仓库无任何私钥。

## 构建

```bash
gradle assembleRelease        # 需要 JDK 17；keystore.properties 存在则用正式签名
gradle testDebugUnitTest      # 网桥协议用例，不需要手环
```

推送到 `main` 后 GitHub Actions 自动跑单测 + 打包，产物在 Actions 的 `kldxst-studio-apk`。

签名 secrets：`ANDROID_KEYSTORE_BASE64` / `ANDROID_STORE_PASSWORD` / `ANDROID_KEY_ALIAS` /
`ANDROID_KEY_PASSWORD`。

## 部署控制台

```bash
cd console
npx wrangler secret put STUDIO_SECRET
npx wrangler deploy                     # 绑定 studio.kldxst.me
```

改完 `console/public/index.html` 再 `deploy` 一次即可，用户无需重装。

## 目录

```
app/src/main/java/com/kldxst/studio/
  MainActivity.kt            WebView 容器 + 来源/令牌注入 + 离线兜底页
  console/ConsoleBridge.kt   页面 ⇄ 原生 的唯一入口（三道闸）
  bt/MiWear.kt               直连：扫描 / SPP / BLE / authkey / 命令
  bt/FrameCodec.kt           帧编解码与协议参数
  bridge/BridgeEngine.kt     FetchBridge v1–v3 协议引擎（可单测）
  bridge/SsrfGuard.kt        出口守卫
  install/Rpk.kt             资源包体检
  secure/Vault.kt            Keystore AES-GCM
  service/StudioService.kt   前台服务（直连常驻 + 网桥）
  service/NotifyRelay.kt     通知转发
console/
  public/index.html          控制台单页（改这里不用重装 APK）
  src/index.js               Worker API
docs/
  TEXT-RULES.md              文案硬性要求
  shots/                     界面图与流程图
```
