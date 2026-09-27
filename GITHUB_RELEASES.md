# GitHub 同步与 Release

本项目按用户要求维护：每次交付更新提交、推送源码，并发布带 APK 的 Release。该约定也写入 `AGENTS.md`，供后续维护继续执行。

## 自动发布

`.github/workflows/android-release.yml` 在默认分支收到推送时执行完整主机测试并构建 Release APK。PR 只执行验证，不发布。也可从 Actions 页面手动运行默认分支的工作流。

构建使用 JDK 21、Gradle Wrapper 9.3.1、Android SDK 37.0 和 Build Tools 37.0.0。APK 签名、包名、版本及 16 KB 对齐通过后，打包当前 Git 提交的完整源码。测试阶段只有仓库读取权限，独立发布任务拥有 `contents: write`；使用 GitHub 为任务提供的临时令牌，无需保存个人 Token。[GitHub Token 文档](https://docs.github.com/en/actions/concepts/security/github_token)

Release 包含带版本号的 APK、完整源码 ZIP、`SHA256SUMS.txt`、`release-manifest.json` 和更新说明。manifest 记录源码提交、版本、测试数量、签名及文件哈希。从 0.2.0 起仅发布正式版，版本必须为 `major.minor.patch` 且不低于 0.2.0；打包脚本拒绝 Alpha/Beta/RC 后缀，清单设置 `prerelease=false`，发布脚本公开后更新 Latest。历史 Alpha 附件不修改。

发布时先创建草稿，所有附件上传并通过服务端大小和 SHA-256 检查后才公开。上传失败保留草稿；从 Actions 使用“Re-run failed jobs”可继续使用同一构建产物。已经发布的版本不覆盖。已有标签属于另一提交，或草稿附件与当前构建不同，会停止并要求使用正确的原始产物或提高版本号。[Release API](https://docs.github.com/en/rest/releases/releases)、[附件 API](https://docs.github.com/en/rest/releases/assets)

## 每次更新

1. 修改代码，递增 `versionCode` 和 `versionName`，在 `CHANGELOG.md` 顶部写当前版本说明；更新必要文档和测试。
2. 运行相关检查并提交到用户指定的仓库；默认分支保护要求 PR 时按仓库规则合并。
3. 等待“Android build and release”通过，打开实际 Release 链接核对 APK 和源码附件，然后向用户交付链接。

远程写入需要目标仓库的访问授权。工作流使用默认分支的已推送提交发布，不能代替首次建立 GitHub 连接或选择仓库。GitHub 对未存在于默认分支、且包含工作流修改的提交另有工作流写权限要求；遇到权限拒绝应按仓库授权处理，不修改权限规则绕过。[GitHub 发布权限说明](https://docs.github.com/en/rest/releases/releases#create-a-release)

## 本地打包与手动发布

在干净、已提交的检出中执行，SDK 路径由 `ANDROID_HOME` 提供：

```bash
python3 scripts/materialize-dependencies.py
./gradlew :app:testDebugUnitTest :app:assembleRelease
python3 -m unittest discover -s scripts -p 'test_*.py'
python3 scripts/package-release.py \
  --apk app/build/outputs/apk/release/app-release.apk \
  --test-results app/build/test-results/testDebugUnitTest --out dist
python3 scripts/publish-release.py --bundle dist --dry-run
```

最后一个命令只校验，不联网发布。实际手动发布时使用已授权的安全环境中的 `GH_TOKEN` 和明确的 `--repo owner/name`，去掉 `--dry-run`；不要把 Token 写进源文件、Git 配置或对话。CI 会自动提供这两个环境值。

历史设备验证资料随源码保留。新的 CI 执行不代表重新跑过真机、实际麦克风或用户 API 账号测试。开发签名说明见 `signing/README.md`；0.3.1 起已移除离线语音运行库及模型下载功能。
