# KdSync 2.0.0 发布记录

- 版本：`2.0.0`（versionCode 3）
- 包名：`com.kldxst.studio`
- 显示名：KdSync
- 构建时间：2026-10-26（GitHub Actions run `40214401150`，JDK 17 / Gradle 8.7 / compileSdk 35）
- APK：`out/app-release.apk`（GitHub Actions 产物）
- 大小：4,832,228 bytes
- SHA-256：`A6899BFC3F8BE7CE17827CA94CB9E3CD2C61F90FAEBEAE7C881A63E89A7910B7`
- 签名：APK Signature Scheme v2，证书 `CN=Kldxst Studio, O=Kldxst, C=CN`
- 证书 SHA-256：`c8765110630acc2e32713a6dd6b32311060eb47db8e0b374287c6b5ba6a8f631`

## 本地验证

```powershell
gradle --no-daemon :app:testDebugUnitTest
gradle --no-daemon :app:assembleRelease
```

单元测试覆盖：

- L1 8 字节头 / CRC16-ARC / 分片重组
- authkey KDF 参考向量（HMAC-SHA256 / `miwear-auth`）
- AES-128-CTR 往返
- AES-128-CCM（4B tag）加解密往返
- 既有 FetchBridge / Rpk 测试

## 主要变更

- 本地控制台（`assets/console/index.html`）替代远端页面，离线可用
- 真实 MiWear L1 SAR / L2 / protobuf 协议
- 三步 authkey 认证 + AES-128-CCM CompanionDevice
- 设备信息 / 状态 / 存储 / 对时
- 应用列表 / 启动 / 卸载 / RPK MASS 安装
- 表盘列表 / 切换 / 删除 / `.face` MASS 安装
- 通知 NotifyData protobuf / 系统通知转发
- SPP Hello / BLE FE95 + 005f/005e/0050 + NO_RESPONSE 12ms
- 粉蓝黄亮色主题、KdSync 品牌图标与名称

详见 `docs/PROTOCOL.md`、`docs/REVERSE_ANALYSIS.md` 与根目录 `README.md`。
