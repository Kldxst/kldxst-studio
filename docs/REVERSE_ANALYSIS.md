# 逆向分析结论（AstroBox / AzkiDeck / Mi Fitness）

> 目标：为 KdSync 2.0 提取可落地的穿戴管理协议，不复制任何账号凭据、authkey 或设备密钥。
> 完整证据见 `_reverse/reports/astrobox-source-analysis.md` 与 `astrobox-protocol-facts.json`。

## 1. AstroBox NG（公开 Rust / Tauri 源码）

下载并分析了 6 个公开 crate：

- `AstroBox-NG-Module-Core`：L1/L2/SAR、auth、设备信息、应用、表盘、通知、MASS
- `AstroBox-NG-Module-Pb`：23 个 `.proto`（WearPacket / Account / System / App / WatchFace / Media / Notification / Mass）
- `AstroBox-NG-Module-Account`：小米登录、STS、RC4/SHA1 云接口
- `AstroBox-NG-Module-Bluetooth` / `BtClassicSpp`：SPP/BLE Android 传输
- `AstroBox-NG-Module-FrontBridge`：Tauri WebView 事件桥，不是 HTTP FetchBridge

关键结论：

1. 真实 L1 是 8 字节头 + CRC16-ARC；旧 KdSync 的 4 字节 `A5` 头完全错误。
2. 真实栈是 L1 SAR（StartReq/Rsp + ACK/NAK + 窗口）→ L2 channel/opcode → protobuf。
3. SPP 必须发 11B 厂商 Hello；BLE 必须用 FE95/005f/005e/0050、NO_RESPONSE + 12ms。
4. authkey 是三步 HMAC-SHA256 KDF + AES-128-CCM，不是单次 HMAC。
5. MASS 格式与数据类型已完全还原；应用/表盘/固件共用 MASS。
6. 业务身份是 `WearPacket.type + id`，不是单字节命令号。
7. 账号登录可还原；设备列表在公开源码只有 DTO，`bledevice_info` 是上报接口。

## 2. AzkiDeck（29.9MB APK）

- 包名 `com.azuma.azkideck`，Compose + 大量 native（`libazvfnative.so` 1.5–2.5MB）。
- `MiWearNative` 暴露了完整管理面：设备信息、电量、存储、应用列表/安装/卸载、
  表盘列表/编辑、通知、音乐、录音、天气、固件、互连端点、网络代理。
- 这证明“管理终端”不只是消息通知：KdSync 2.0 按同一能力面实现核心子集
  （设备/应用/表盘/通知/网桥），并保留后续扩展接口。
- 其 native 协议实现未公开，KdSync 没有复制其二进制；协议事实以 AstroBox 公开源码为准。

## 3. Mi Fitness 3.59.1（`3.59.1_..._mihealth.apk`）

- 包名 `com.mi.health`，版本 3.59.1（versionCode 359001），约 150MB；
  代码高度混淆，大量 `com.xiaomi.wearable.*` / transport / mass 类。
- 静态字符串确证：
  - `https://account.xiaomi.com/pass/serviceLogin`
  - `serviceLoginAuth2`
  - `sts-hlth.io.mi.com/healthapp/sts`
  - `hlth.io.mi.com/healthapp/...`
  - `/healthapp/bledevice_info`、`/healthapp/v2/home/local_device_list`
  - `/api/v1/miwear/`
- 与 AstroBox Account 源码一致：`sid=miothealth`、STS serviceToken、`ssecurity` 签名。
- 设备列表/绑定的实际请求走 Mi Fitness 的 Binder/私有网络层，无法从混淆 Java 层
  直接恢复完整参数；KdSync 因此把“手动 authkey”作为可靠路径。

## 4. KdSync 2.0 的实现边界

| 能力 | 状态 |
|---|---|
| L1/SAR/L2/protobuf | 已实现，单测覆盖帧/CRC/KDF/CCM |
| 三步 authkey 认证 | 已实现，等待真机确认密钥协商 |
| 设备信息/电量/存储/对时 | 已实现 |
| 应用列表/启动/卸载 | 已实现 |
| `.rpk` 应用 MASS 安装 | 已实现 |
| 表盘列表/切换/删除 | 已实现 |
| `.face` 表盘 MASS 安装 | 已实现（id 由用户/资源包提供） |
| 通知推送/系统通知转发 | 已实现 |
| SPP/BLE 精确 UUID/Hello/写模式 | 已实现 |
| 通用网桥 | FetchBridge JSON 承载于 MessageContent |
| 固件 OTA | 只读版本，不提供刷机入口（风险） |
| 音乐/表盘编辑/天气/录音 | 未在 UI 开放，协议/数据层留有扩展点 |

## 5. 参考

- `D:\MyProject\Vela\KdSync\_reverse\reports\astrobox-source-analysis.md`
- `D:\MyProject\Vela\KdSync\_reverse\reports\astrobox-protocol-fields.csv`
- `docs/PROTOCOL.md`
