# AstroBox 2.2.0 逆向记录

分析对象：`KdSync/AstroBox_2.2.0.apk`（Tauri 应用，核心在 `lib/arm64-v8a/libapp_lib.so`，178 MB）。
方法：解包 + 提取字符串（446786 条）+ 结构化归类。以下都是**证据原文**，不是推测。

## 一、账号与设备授权

| 用途 | 证据 |
|---|---|
| 登录入口 | `https://account.xiaomi.com/pass/serviceLogin?sid=` |
| 密码校验 | `https://account.xiaomi.com/pass/serviceLoginAuth2` |
| 服务票据 | `https://sts-hlth.io.mi.com/healthapp/sts?sid=miothealth&_json=true` |
| 设备列表 | `https://hlth.io.mi.com/healthapp/device/bledevice_info` |
| 资源清单 | `https://hlth.io.mi.com/app/v1/source/get_source_list` |

保存的凭据结构（Rust 侧 `StoredXiaomiCredentials`，8 个字段）：

```
user_id  device_id  ssecurity  service_token  c_user_id  pass_token  psecurity  updated_at
```

日志原文：

```
[MiAccount.Login] serviceLogin succeeded for username=
[MiAccount.Login] serviceLogin returned authenticated session directly code=
serviceLoginAuth2 returned credential challenge again; body=
[MiAccount.Login] using raw serviceToken captured from verified 2FA webview as last-resort fallback
```

结论：登录走标准小米账号流程，`sid=miothealth`；二次验证（code 70016）需要额外处理。

## 二、设备授权（authkey）

证据：

```
miwear-auth
Auth HMAC mismatch, This usually means your AuthKey is wrong.
phone nonce length mismatch
nonce/hmac length mismatch
invalid authkey hex len
HMAC key length fixed
basE91 alphabet is ASCII
```

结论：**授权是 HMAC，不是 AES**。authkey 是 16 字节（32 位十六进制）作为 HMAC 密钥，
手机发 nonce，双方用 HMAC 校验；消息里有 base91 字母表，说明 auth 报文用 base91 编码。
我此前按 AES-128-ECB 实现是错的，已改。

## 三、连接与业务命令

`device_connect` 的入参（Tauri 命令，前端直接调）：

```
authkey  sarVersion  txWinOverrunAllowance  connectType  forceAndroid  unpairBeforeConnect
```

其余相关命令：

```
device_disconnect / device_get_data / device_get_watchface_id / device_get_file_type
device_get_file_size / device_install_res_pack{resourceType} / device_music_upload_song
device_log_export / device_transport_run_smoke / device_transport_profiler_start|stop
thirdpartyapp_install_by_url / thirdpartyapp_uninstall / thirdpartyapp_launch
thirdpartyapp_get_list / thirdpartyapp_send_message / thirdpartyapp_cancel_install_by_url
watchface_get_list / watchface_set_current / watchface_uninstall / watchface_edit_*
network_http_request / network_http_persist_download / plugsys_* （插件系统）
```

## 四、设备数据与能力

`DeviceInfo` 字段：`serialNumber firmwareVersion model productDevice macAddress
versionType hardVersion osVersion`，另有 `BatteryData{chargeStatus, chargeInfo}`、
`StorageInfo{total, models, n, product_device}`、`watchfaceCount / quickAppCount`。

能力枚举（`capabilities`）：

```
BLUETOOTH NFC ACC MAG LIGHT PPG CAP CHARGE BATTERY DPT P KEY ROTARY MOTOR STORAGE
AUDIO DOU MIC PA CAMERA IMU INDICATOR_LIGHT PRIVACY_LIGHT RANGE HEART_RATE BLOOD_OXYGEN ECG
```

功能分组：`OTHER ACCOUNT SYSTEM WATCH_FACE NOTIFICATION FITNESS LPA WEATHER STOCK
FACTORY MARKET CLOCK MEDIA ALEXA THIRDPARTY_APP CONTACT INTERCONNECTION`。

## 五、传输层

```
corelib::device::xiaomi::packet::cipher
corelib::device::xiaomi::components::mass          （MASS 分片与反向通道）
failed to assemble reverse MASS payload
reverse MASS receive requires at least one channel
Calculated mass_fragment_max_len is 0. Device limit (
[Mass] send setup: tx_window_soft_hint=
corelib::device::xiaomi::components::thirdparty_app
ThirdpartyAppSystem missing owner; interconnect message dropped
msgManager message envelope is missing string
msgManager protocol packet exceeds maxTextChars
maxWindow is outside 1..=4
```

结论：传输是 **eRPC 帧 + MASS 分片**，interconnect 由 `ThirdpartyAppSystem` 承载，
窗口 `maxWindow` 限 1–4，文本通道有 `maxTextChars` 上限。这与 FetchBridge 的
滑动窗口模型一致，但底层帧不是 JSON 直传。

## 六、对我们的影响

1. **认证改 HMAC**（已改）。
2. **登录是必需前置**：authkey 只能通过小米账号从 `bledevice_info` 取，不能凭空生成。
3. **业务消息仍需逐条校准**：MASS/eRPC 的帧头与消息号在 .so 里是编译后的常量，
   字符串层拿不到；这一步只能真机抓包填表。本项目的 `ProtocolProfile` 就是这张表。
4. 设备能力、命令清单、字段名已按上表对齐，界面按这些项组织，不再自造功能名。
