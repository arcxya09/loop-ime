# Alpha 开发签名

`loop-development.jks` 是已随 Loop 源码交付的公开开发签名。alias 为 `androiddebugkey`，仓库中 Gradle 配置包含其开发密码。保留此签名可使当前 Alpha APK 覆盖更新，避免用户卸载后丢失应用数据。

证书 SHA-256：`8ce80c674b10d7a75f2262e711337f1fd53c6b23e9554fe04fab1bd8a4ae6bcb`。

CI 只使用这个开发签名，不需要用户提供 AI Key，也不需要上传手机中的任何密钥。此文件不适合作为正式商业版本的私密发布签名。
