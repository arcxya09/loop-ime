# 软件更新

从 0.1.15-alpha.16 开始，设置首页提供“软件更新”。更早版本需先从 [GitHub Release](https://github.com/arcxya09/loop-ime/releases) 手动安装这一版，覆盖安装保留原签名与应用数据。

默认每天自动检查一次，发现新版后通过 Wi-Fi 下载。后台任务由 Android 调度，省电、休眠、强制停止或无网络可能推迟执行；打开更新页会检查距离上次成功检查是否已过一天。失败后自动检查退避一小时，手动“立即检查更新”可重试。

Alpha 版默认包含预发布，也可以关闭“接收 Alpha / 预发布版本”。关闭后只检查正式 Release，不会降级已经安装的版本。自动检查和自动下载可以分别关闭；关闭会停止当前自动下载，已验证的安装包仍可手动安装。手动移动网络下载需要单次确认，原 Wi-Fi 任务会重新开始。

下载完成后，设置首页显示“已下载，点击安装”。可在更新页授予通知权限以接收完成提示；拒绝通知不影响检查和下载。点击“安装更新”后，如系统要求，先允许 Loop 安装应用，返回再点击一次，然后在系统安装界面确认。安装过程中 Android 可能重启输入法进程，请先结束正在进行的输入或录音。

更新使用固定仓库 `arcxya09/loop-ime` 的公开 Release API，检查最近 20 个发布（包括预发布，跳过草稿），从完整、通过清单校验的发布中选择更高的 versionCode。维护时保持版本码递增，不要将旧标签重新发布成“新版”。GitHub 限流或无法访问会显示错误；可重试或通过发布页面手动下载。

安装包先由系统 DownloadManager 下载，再复制到应用私有目录。每次安装前校验大小、SHA-256、包名、准确版本、最低 SDK 及与当前安装一致的签名；拒绝损坏、换签或降级包。FileProvider 仅共享更新目录，系统安装器通过临时只读权限读取。数据库、原密钥及个人数据不参与更新文件操作。

更新客户端不复用 AI 网络配置，不需要 GitHub Token，不发送输入文本、词库、API Key 或个人日志。GitHub 仍会收到正常网络请求的 IP 和固定客户端标识。公开开发签名仅用于 Alpha 延续更新，不能代替正式商业签名；未来签名迁移需另行设计。

Android 接口参考：[DownloadManager](https://developer.android.com/reference/android/app/DownloadManager)、[JobScheduler](https://developer.android.com/reference/android/app/job/JobScheduler)、[FileProvider](https://developer.android.com/reference/androidx/core/content/FileProvider)。
