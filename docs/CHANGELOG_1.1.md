# K社工坊 1.1.0

## 修复

- 控制台脚本在 `onPageFinished` 之前启动，原注入方式拿不到会话令牌，所有原生调用都会返回
  `E_TOKEN`。现在优先用 WebView `document-start` 注入，并以 `Kldxst.sessionToken()` 兜底。
- 首次启动补齐通知、蓝牙扫描/连接（Android 12+）或定位（Android 11 及以下）权限；
  权限缺失时设备页给出授权与系统设置入口。
- 账号登录状态、协议参数、网桥策略现在会在进程重启后从加密存储恢复。
- 控制台策略/协议保存改为局部合并，不再把 `allowPackages`、`chunkSize`、`ackWindow` 等未提交字段重置。
- 用户主动断开或停止网桥后，不再被前台服务自动重连；蓝牙意外断开时服务按退避间隔重连。

## 蓝牙

- GATT 写入改为串行等待 `onCharacteristicWrite`，按协商 MTU 切片；CCCD 写入等回调后才算就绪。
- 修复 BLE `connected` 在断开后仍为 true 的状态错误。
- 扫描等到完整扫描窗口结束后再返回结果，不再拿到第一台设备就提前结束。
- 认证状态拆成 `sending/replied/no_reply/failed`，收到 auth 类型帧不再冒充“已认证”。

## 安全与校验

- SSRF 守卫一次完成策略校验与 DNS 解析，返回的地址直接钉入 OkHttp；补上 IPv6 ULA、
  IPv4-mapped、CGNAT、保留段与域名后缀检查。
- 响应体按上限流式读取，超限标记 `truncated`，避免先 `bytes()` 整包进内存。
- RPK 下载复用网桥出口守卫；资源包改用 `ZipFile` 检查路径穿越、条目数量和解压上限。
- 小米登录跳转只接受 HTTPS 的小米域名。

## 协议与测试

- `ProtocolProfile` 增加 `lengthOffset`、可配置 auth 模式/nonce 长度；帧字段重叠配置会拒绝。
- 新增帧编解码、半包、配置校验、RPK 路径穿越、策略局部合并与 IPv6 SSRF 用例。
