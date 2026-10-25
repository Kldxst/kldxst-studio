# KdSync · 智能穿戴管理终端

**KdSync** 是 Kldxst 作品的独立穿戴管理终端：手机自己占蓝牙，直连小米手环/手表，
不依赖小米运动健康代转。当前版本 `2.0.0`（versionCode 3）。

- 包名：`com.kldxst.studio`
- 显示名：`KdSync`
- 主题：粉 `#FF6FA5` / 蓝 `#2563EB` / 黄 `#F5B400` 亮色系
- 最低 Android 7.0（API 24），targetSdk 34，compileSdk 35

## 1. 这次 2.0 做了什么

1.0 的控制台依赖远端页面，协议层还是“magic + 单字节命令”的占位实现。2.0 把控制台
打进 APK（`assets/console/index.html`，WebViewAssetLoader 本地加载），并把 MiWear
协议按 AstroBox NG 公开源码完整重写：

```
L1 SAR  A5 A5 | type/frx | seq | len(u16 LE) | CRC16-ARC(payload)
  ↓
L2      channel + opcode + payload
  ↓
PB      WearPacket{type, id, oneof payload}  (proto2, protobuf-lite)
```

- **SPP**：`00001101-...`，先 insecure 再 secure，SDP 失败 fallback RFCOMM 5/1；
  连接后先发 12 字节 Hello `BA DC FE 00 C0 03 00 00 01 00 EF`。
- **BLE**：服务 `FE95`，写 `005f`，通知 `005e`，探测 `0050`；MTU 请求 247；
  `WRITE_TYPE_NO_RESPONSE`，每片 12 ms。
- **认证**：3 步 protobuf（Account 26/27）；HMAC-SHA256 KDF（tag `miwear-auth`）
  派生 enc/dec key + nonce；AES-128-CCM（12B nonce / 4B tag）加密 CompanionDevice。
- **L2 加密**：AES-128-CTR，key 同时作为 IV。
- **MASS**：Prepare（type=22/id=0）→ 设备 `expected_slice_length` →
  L2 Mass `total_parts|current_part|fragment`，fragment 为
  `00|data_type|md5(16)|remaining_len(u32 LE)|data|crc32(u32 LE)`。
- **业务消息**：设备信息 System 1/2/62；应用 ThirdpartyApp 0/1/2/3/4/8/9；
  表盘 WatchFace 0/1/2/4/5；通知 Notification 0/1；时间 System 3。
- **通知**：真实 `NotifyData` protobuf，`date` 固定本地 `yyyyMMdd'T'HHmmss`，
  编码后 ≤16 KiB；通知转发与撤回使用同一 `NotifyId`。
- **网桥**：`ThirdpartyApp.MessageContent`（type=20/id=9 收，id=8 发）承载
  FetchBridge JSON；HTTP 出口继续走 SSRF/白名单/限流策略。

> 设备列表接口在公开 AstroBox Account 源码里只有 DTO，没有请求实现；
> KdSync 保留“账号登录 + 手动 authkey”两条路径，设备列表接口失败时可直接粘贴
> authkey 连接。这是能力边界，不是伪装成功。

## 2. 功能

| 模块 | 能力 |
|---|---|
| 设备 | BLE 扫描、SPP/BLE 连接、authkey 认证、设备信息/电量/存储、对时、协议日志 |
| 应用 | 读取已安装快应用、启动、卸载；本地 `.rpk` 体检并通过 MASS 安装 |
| 表盘 | 读取/切换/删除表盘；本地 `.face` 通过 MASS 安装（需提供表盘 id） |
| 通知 | 手动推送、系统通知转发/撤回（需通知使用权） |
| 网桥 | FetchBridge v1–v4（流式不声明）、域名白名单、SSRF 防护、自检 |
| Kldxst | 内置作品入口（幽径/拆招/神之一手/星环/末日之后/诡秘之主），统一 RPK 安装流程 |
| 账号 | 小米 serviceLogin → serviceLoginAuth2 → STS → serviceToken；可选手动 authkey |
| 安全 | Keystore AES-GCM 保存凭据；控制台来源白名单 + 会话令牌；只允许 HTTPS |

## 3. 本地构建（已配置）

本仓库现在同时支持本地 Android SDK 和 GitHub Actions：

```powershell
$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot'
$env:Path="$env:JAVA_HOME\bin;C:\Users\16152\Tools\gradle\gradle-8.7\bin;$env:Path"
gradle --no-daemon :app:testDebugUnitTest
gradle --no-daemon :app:assembleRelease
```

`local.properties` 指向 `C:\Users\16152\AppData\Local\Android\Sdk`。release 签名读取
`keystore.properties`（已被 `.gitignore` 排除；CI 由 secrets 还原）。

GitHub Actions 仍是主发布路径：push `main` → unit test → assembleRelease →
下载 `kldxst-studio-apk` artifact。

## 4. 目录

```
app/src/main/
  assets/console/index.html   本地控制台（KdSync UI，粉蓝黄亮色）
  proto/*.proto               AstroBox 同源 protobuf（生成 com.kldxst.studio.proto）
  java/com/kldxst/studio/
    wear/MiWearFrame.kt        L1 帧 / L2 包 / CRC16-ARC
    wear/MiWearCrypto.kt       KDF / AES-CTR / AES-CCM
    wear/MiWearSession.kt      SAR 会话 / auth / PB 请求分发
    wear/WearCommands.kt       设备信息 / 应用 / 通知 / MASS
    wear/WatchFaceCommands.kt  表盘列表 / 切换 / 安装
    bt/MiWear.kt               SPP/BLE 原生传输（FE95/005f/005e/0050）
    core/WearController.kt     设备管理门面（UI/服务共用）
    console/ConsoleBridge.kt   JS ⇄ 原生唯一入口
    bridge/BridgeEngine.kt     FetchBridge 引擎
    service/StudioService.kt   前台服务：重连 + 网桥 + 通知
    service/NotifyRelay.kt     通知转发/撤回
docs/
  PROTOCOL.md                  可直接落地的协议摘要
  REVERSE_ANALYSIS.md          AstroBox / AzkiDeck / Mi Fitness 逆向结论
```

## 5. 已知边界

- 没有真机/真实账号的情况下，无法验证 authkey 与具体型号的兼容性；协议帧、KDF、
  CCM、MASS 均有单元测试覆盖，但设备侧仍需一次真机握手确认。
- `.face` 的 id 需要从资源包 manifest 读取；KdSync 不猜测 id，避免覆盖现有表盘。
- 固件 `.bin` 的 OTA 流程风险高，2.0 只读取固件版本，不提供一键刷机入口。
- 小米设备列表接口受服务端/风控影响；失败时使用手动 authkey。
- FetchBridge 与 AstroBox 的 `FrontBridge` 不是同一个东西；KdSync 的网桥是
  自定义 JSON 代理，承载在 `ThirdpartyApp.MessageContent` 上。

## 6. 版本记录

- `1.1.0`：远端控制台 + 直连占位协议，见 `docs/RELEASE_1.1.0.md`。
- `2.0.0`：本地控制台、真实 L1/L2/PB、authkey 三步认证、MASS 安装、表盘、
  通知 protobuf、设备管理门面、单元测试。
