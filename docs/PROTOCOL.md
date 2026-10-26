# KdSync 协议速查（源码确证）

来源：AstroBox NG 公开 crate（Core / Pb / Account / Bluetooth / BtClassicSpp）+
Mi Fitness 3.59.1 静态分析。行号与证据见 `_reverse/reports/astrobox-source-analysis.md`。

## 1. 传输

| 项 | 值 |
|---|---|
| SPP UUID | `00001101-0000-1000-8000-00805F9B34FB` |
| SPP 连接 | insecure → secure；SDP 失败 fallback channel 5，再 1 |
| SPP Hello | `BA DC FE 00 C0 03 00 00 01 00 EF`（11B） |
| BLE 服务 | `0000FE95-0000-1000-8000-00805F9B34FB` |
| BLE 写 / 通知 / 探测 | `005f` / `005e` / `0050` |
| BLE 写入 | WRITE_TYPE_NO_RESPONSE，每片 12 ms；MTU 请求 247 |
| 分片 | SPP 666B（可合并到 60KiB），BLE 244B |

## 2. L1 帧

```
offset 0  A5 A5                 magic (u16 LE)
offset 2  type | (frx << 4)     Nak=0 Ack=1 Cmd=2 Data=3
offset 3  seq (u8)
offset 4  length (u16 LE)
offset 6  CRC16-ARC(payload) (u16 LE)
offset 8  payload
```

CRC16-ARC：初值 0，poly 0xA001，只覆盖 payload。

SAR 控制命令 payload：`cmd(1) + [configKey(1) + len(2 LE) + value]...`：

| cmd | 值 |
|---|---|
| L1StartReq / Rsp | 1 / 2 |
| L1StopReq / Rsp | 3 / 4 |

config keys：1 version(3B), 2 mps(u16), 3 tx_win(u16), 4 send_timeout(u16),
5 device_type, 6 device_name, 7 os_version。

## 3. L2

`channel(1) | opcode(1) | payload`。

- Channel：PB=1，MASS=2，MassVoice=3，FileSensor=4，FileFitness=5，OTA=6，
  Network=7，Lyra=8，Research=9，MultiModal=10，Canopus=0x43。
- Opcode：Write=1，WriteEnc=2，Read=3。
- 认证后 PB 的 WriteEnc 用 AES-128-CTR；key 同时作为 IV。
- 认证三步本身走 plain Write。

## 4. 认证

authkey 为 16 字节（32 hex）。

```
hmac_key = HMAC-SHA256(phone_nonce || watch_nonce, authkey)
okm = HMAC-SHA256(hmac_key, prev || "miwear-auth" || counter)   counter=1..3
dec_key=okm[0..16], enc_key=okm[16..32], dec_nonce=okm[32..36], enc_nonce=okm[36..40]
```

消息：

1. `WearPacket{type=ACCOUNT(1), id=AUTH_VERIFY(26), payload=Account.auth_app_verify}`
   - `Auth.AppVerify{app_random=phone_nonce}`。
2. 设备回 `Auth.DeviceVerify{device_random, device_sign}`。
   - 验证 `HMAC(dec_key, watch_nonce || phone_nonce) == device_sign`。
3. App 回 `WearPacket{type=1,id=AUTH_CONFIRM(27), payload=Account.auth_app_confirm}`
   - `Auth.AppConfirm{app_sign=HMAC(enc_key, phone_nonce||watch_nonce),
     encrypt_companion_device=AES-128-CCM(enc_key, enc_nonce||8 zeros, CompanionDevice)}`。
4. 设备回 `Auth.DeviceConfirm{confirm_result}`。

CompanionDevice：device_type=ANDROID，device_name="KdSync"，app_capability=0xFFFFFFFF。

## 5. 设备/应用/表盘/通知

| 业务 | WearPacket.type | id |
|---|---|---|
| 设备状态 | SYSTEM 2 | 1 |
| 设备信息 | SYSTEM 2 | 2 |
| 对时 | SYSTEM 2 | 3 |
| 存储 | SYSTEM 2 | 62 |
| 应用列表 | THIRDPARTY_APP 20 | 0 |
| 应用安装准备 | 20 | 1 (version_code=114514) |
| 应用安装结果 | 20 | 2 |
| 应用卸载 / 启动 | 20 | 3 / 4 |
| 表盘列表 / 设置 / 删除 / 安装准备 / 结果 | WATCH_FACE 4 | 0 / 1 / 2 / 4 / 5 |
| 通知添加 / 删除 | NOTIFICATION 7 | 0 / 1 |

`NotifyData` 必填：app_id, app_name, title, sub_title, text, date, uid,
app_group, call_number, key。`date` 为本地 `yyyyMMdd'T'HHmmss`；编码 ≤16KiB。

## 6. MASS

1. `WearPacket{type=MASS(22), id=PREPARE(0), payload=Mass.prepare_request}`。
2. 设备回 `PrepareResponse{prepare_status, expected_slice_length}`。
3. 整包 inner：
   `00 | data_type | md5(16) | remaining_len(u32 LE) | data | crc32(u32 LE)`。
4. 分片：`total_parts(u16 LE) | current_part(u16 LE) | fragment`；
   L2 channel=MASS(2)，opcode=Write(1)。
5. `fragmentMax = expected_slice_length - 6`。

MassDataType：16 表盘，32 固件，48 表盘图，50 通知图标，52 音乐，
53 表盘字体，64 快应用。

## 7. 账号

```
GET  https://account.xiaomi.com/pass/serviceLogin?sid=miothealth&_json=true
POST https://account.xiaomi.com/pass/serviceLoginAuth2
     sid=miothealth, hash=MD5(password) uppercase, callback=.../healthapp/sts,
     qs, user, _sign, _json=true
GET  <location>  → Set-Cookie serviceToken / cUserId
```

后续云接口：nonce = base64(8B random + 4B minutes BE)，signed_nonce =
base64(SHA256(ssecurity_dec + nonce_dec))，参数 RC4（丢前 1024B）后
SHA1 签名。公开 Account 源码没有设备列表请求函数；`bledevice_info` 是上报接口。
