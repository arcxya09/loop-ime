# 覆盖更新与原开发签名

`loop-development.jks` 是已随 Loop 源码交付的公开开发签名。alias 为 `androiddebugkey`，仓库中 Gradle 配置包含其开发密码。0.2 正式版继续保留此签名，使当前 Alpha 安装可覆盖升级，避免用户卸载后丢失应用数据。正式版发布通道不代表商业签名迁移。

证书 SHA-256：`8ce80c674b10d7a75f2262e711337f1fd53c6b23e9554fe04fab1bd8a4ae6bcb`。

CI 只使用这个开发签名，不需要用户提供 AI Key，也不需要上传手机中的任何密钥。此文件不适合作为正式商业版本的私密发布签名。

从 alpha.17 起显式开启 v1 / v2 / v3。实际平台验签和 API 23–37 的签名兼容范围验签必须同时通过，发布清单记录 `signature_schemes`。扩大验签范围仅用于检查三种签名格式，应用最低系统版本仍为 API 37。

不能仅因按 APK 最低 SDK 执行的默认 `apksigner verify` 把 v1/v2 显示为 false，就断言文件未包含它们：默认验证可能只采用 v3。使用 `--min-sdk-version 23 --max-sdk-version 37` 检查完整组合，并继续验证原证书指纹。[apksigner 官方说明](https://developer.android.com/tools/apksigner)
