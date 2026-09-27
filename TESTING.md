# Loop 验证记录

## 0.1.21-alpha.22 最近选词与动态词频

Windows / JDK 21 / SDK 37.0 / Build Tools 37.0.0：233 项应用主机测试通过（0 失败、0 跳过），16 项 Python 测试通过；Lint 与 Release 构建通过。排序规则为完整拼音/已学编码匹配优先，在匹配组内最近 7 天的选词按时间倒序，其他词按原累计词频；不修改白霜基础词频文件。

新增真实服务模拟回归：不等待数据库写入，选过第二候选后再次输入即成为首选，空格与回车均提交该首选；关闭学习、隐私、受限输入、提交失败不新增优先级；分段上屏不绑定整个原编码。InputHistory 检查最近使用时间、重复次数、序列化和删除后的即时撤回。

真实 SQLite 的 PersonalStore 回归覆盖近期低频词优先于较早高频词、超过 7 天后恢复累计词频排序、重新打开后保留、单字及关闭记忆但开启学习、删除来源/遗忘撤回、完整匹配优先、重复备份导入不叠加或刷新时间，以及多音字按已选编码匹配。旧 schema 1 升级覆盖到 schema 4，保留旧词、剪贴板、权限和外键关系；数据库复制恢复、WAL 数据恢复及旧文件/密钥保留检查通过。首轮发现恢复入口仍只允许 schema 1–3，已统一为当前支持版本后重跑通过。

本轮为主机测试，未连接 Android 17 / MagicOS 11 真机或实际用户数据库。未新建数据库替代旧库、未更换密钥；迁移仅增加证据中的最近使用时间及输入编码，备份对旧字段缺失保持兼容。源码和诊断不包含实际输入历史。

## 0.1.20-alpha.21 候选、续写与长按输入

Windows / JDK 21 / SDK 37.0 / Build Tools 37.0.0：224 项应用主机测试通过（0 失败、0 跳过），16 项 Python 测试通过；Lint 与 Release APK 构建通过。首轮有一项旧断言要求显示数字编码，已按新需求改为引擎拼音；同轮 AI 传输的冷启动测试出现一次 5 秒超时，未修改其超时阈值或跳过测试，随后完整重跑通过。

新增回归覆盖空输入不显示常用词、真实 SQLite 上的上下文词组查找、AI 原文复述/重叠前缀/重复候选过滤、英文空格与词内补全、点击只插入后续内容、编辑后拒绝过期候选，以及本地词条的云端隔离。个人词库内容和存储格式保留；本地联想是个人词组的前缀续写，不宣称完整语义预测。真实 AI 服务的生成质量仍取决于用户配置的模型，本轮使用可控响应测试。

交互回归逐键验证九宫格 0–9 和全部 26 个英文长按字符，检查短按原字符、长按单次输入、Shift、移出、取消、多指及视图移除；服务回归确认长按字符先结束当前拼音并直接插入，撇号不被重新送入拼音引擎。三档高度逐一比较九宫格、英文和符号的总高度、首行顶部及末行底部；候选时确认两侧所有控件为 GONE、候选区域宽度等于工具栏，并验证长按菜单。

已检查 Robolectric API 37 原生渲染预览：`app/build/keyboard-alpha21-english.png` 和 `app/build/keyboard-alpha21-full-width-candidates.png`。这些是主机生成图，不是手机截图，输出不入库。本次未连接 Android 17 / MagicOS 11 真机，也未运行实际麦克风和真实 AI 账号；厂商系统窗口与触摸行为仍需真机验收。原签名及发布校验继续由 CI 检查。

## 0.1.19-alpha.20 统一固定高度与完整语音退出路径

Windows 主机 / JDK 21 / SDK 37.0 / Build Tools 37.0.0：214 项应用主机测试通过（0 失败、0 跳过），16 项 Python 测试通过；Lint 与 Release APK 构建通过。Robolectric API 37 回归覆盖三档高度、九宫格与全键盘、语音结束/尾句/错误/模型缺失/存储失败/纠错/任意新提示，有无拼音与本地/云端候选组合、面板/英文/符号切换均保持总高度。测试同时核对按键和候选位置，避免只验证总高度而遗漏按压目标移动。

新增真实服务方法的模拟事件回归，通过 `LoopImeService.onSpeech` 发送 READY、DONE、ERROR 和 MODEL_REQUIRED，检查退出语音后恢复个人候选。首轮发现 ERROR/MODEL_REQUIRED 退出时清空候选却未恢复，补齐后全量重跑通过；未成功插入语音的恢复面板由既有回归继续保护。另验证云端候选按压期间延迟刷新、语音切换清除过期结果、展开云端词可选中，以及拼音显示期间操作提示经计时后仍可通过工具长按处理。

Robolectric 原生图形渲染截图 `app/build/keyboard-alpha20-voice-finished.png` 已人工检查：结束提示、个人词和 AI 词位于原 44dp 工具栏内，无额外行。截图与构建输出不入库。本次未运行 Android 17 模拟器、实际麦克风、真实 AI 账号或 MagicOS 11 真机；主机测试不代表厂商窗口管理和实际语音链路已验收。发布流程继续强制验证原开发证书、v1/v2/v3 签名及 16KB 对齐。

## 0.1.18-alpha.19 固定高度的拼音显示与语音引导

Windows / JDK 21 / SDK 37.0 / Build Tools 37.0.0：210 项应用主机测试全部通过，16 项 Python 测试通过；Lint、Release 与 Android 测试包构建通过。回归覆盖连续输入→选词→个人候选、退格与删空后不显示额外语音引导栏，拼音和候选不重叠，组合信息不作为已提交文字写入输入框，以及 AI 失败、英文切换和旧操作提示。首次运行发现一项旧断言要求键盘隐藏编码；按新需求改为检查组合信息只在键盘可见，并保留宿主文本未被写入的断言后，全量重跑通过。

Robolectric 原生绘制预览已检查：拼音 16dp、候选 28dp 共用原有 44dp 区域，键盘整体高度不变。它是主机渲染预览，不是 MagicOS 截图。测试产生的 `app/build/keyboard-alpha19-pinyin-candidates.png` 不进入源码。

WSL Ubuntu 22.04 / librime 1.7.3 检查显示：九键原始 preedit 为 `64 426`，开启拼音提示后的首候选 comment 为 `ni hao`；同样检查了中国、输入法、人工智能、计算机及你好世界的读音，以及全拼 preedit。21 组候选与提交检查通过。九键 `9426` 首候选为“小”，读音是 `xiao`，因此 UI 明确显示引擎首候选参考读音并保留实际编码，不将其当作唯一拼音。

两种 ABI 的 JNI 桥通过项目脚本、NDK r28c 重编译；下载包 SHA-1 与官方 SDK 清单的 `a7b54a5de87fecd125a17d54f73c446199e72a64` 一致。摘要已更新至 `third_party/checksums.json`，发布门禁继续核验 16KB 对齐与原签名。Android 原生测试入口新增读音/编码检查，但本轮无 Android 17 真机或模拟器，未执行设备测试。既有白霜数据、数据库、密钥和旧 Rime 目录保留，仅更新方案部署标记。

## 0.1.17-alpha.18 白霜词库与 AI 提示布局

Windows / JDK 21 / SDK 37.0 / Build Tools 37.0.0：完整应用主机测试 206 项通过，0 失败、0 跳过；16 项 Python 词库与发布脚本测试通过；Lint、Release、Debug 和 Android 测试 APK 构建通过。Robolectric 的两项新测试覆盖九键与全键盘在 AI 超时、失败、无匹配时的高度、候选栏宽度、按键位置、候选点击、工具按钮、提示到期，以及不覆盖现有纠错撤销操作。

WSL Ubuntu 22.04 / librime 1.7.3 原生 C API：使用与 APK 相同的原始词表及方案完成冷部署，21 组九键→全拼→九键候选查找和提交通过，包含你好、中国、输入法、人工智能、计算机、西安及整句你好世界。除西安外这些样例均为首候选；西安在本次九键样例为第 10 位、全拼为第 3 位。仅为有限功能样例，不代表总体准确率或手机延迟。工具仅解包到忽略的 `verification/frost-alpha18/`，不加入 APK。

四张白霜词表与固定上游提交逐字节一致，合计 653,191 条字词/读音记录（不是去重后的词数）；摘要、大小和来源见 `third_party/rime-frost.json`。本版使用独立 `rime/frost-v1/` 缓存，不删除原 Rime 目录，不修改个人数据库/密钥；源码附 Android `FrostInstrumentation`，可验证真实应用目录保留和 18 组候选切换/提交。本轮没有可用 Android 17 模拟器或真机，因此该 Android 原生测试未执行，Linux 检查不冒充 APK 真机结果。

首次打开中文键盘需要本机编译新词库，耗时依设备而异；本次没有引入完整白霜方案的 Lua、细胞词库或模型。更新包沿用原开发签名，CI 继续校验 v1/v2/v3 和全部发布资产。

## 0.1.16-alpha.17 签名兼容验证

对从 GitHub 实际下载的 alpha.16 APK 核对 SHA-256：`bbd2f0ebf0cab33c99cd4149446f6b0310497aa96a156ff01a9053a71ba5fcc5`，与公开附件及清单一致。默认 `apksigner verify --verbose --print-certs` 验证成功，v2=true，v1/v3=false，签名证书仍是原开发证书。因此没有将反馈直接归因为漏签或文件损坏。

alpha.17 显式启用 v1/v2/v3，Release 构建通过；按实际最低 SDK 验签通过，按 `--min-sdk-version 23 --max-sdk-version 37` 验签时三种方案均为 true，证书指纹与 alpha.16 一致，16 KB ZIP 对齐通过。13 项 Python 测试通过，其中 5 项新增签名门禁回归。CI 继续运行完整应用回归和原生库对齐检查，最终结果见本版发布清单。

扩大签名验证范围不是降低 minSdk，APK 仍要求 Android 17 / API 37。v1 检查对 META-INF 下的构建和依赖元数据产生常规警告，这些文件仍受 v2/v3 的整包签名保护。尚未取得 MagicOS 11 设备端完整安装错误链，也未完成该设备的实装验证；本次明确作为兼容性修正交付。请直接覆盖安装保留旧数据；若仍失败，需要安装器的完整错误信息继续定位。

## 0.1.15-alpha.16 自动更新验证

本轮使用 Windows / JDK 21 / SDK 37.0 / Build Tools 37.0.0。完整主机回归 204 项通过，0 失败、0 跳过；8 项 Python 脚本测试通过。新增 21 项更新回归，覆盖版本码排序、正式/预发布通道、草稿与降级拒绝、清单和 APK 摘要、错误仓库 URL、网络失败/限流/大小上限、重定向目标和独立请求头、后台计划持久化、Wi-Fi 与移动网络策略、重复下载、失败重试、自动下载开关、完成广播过滤、签名/包名/版本/SDK 校验、覆盖安装后的旧包清理及文件共享边界。

本机 Android 行为验证基于 Robolectric：DownloadManager、JobScheduler 和 PackageManager 使用主机替身；真实文件执行 SHA-256 校验。Windows 的 FileProvider 路径分隔符与 Android 不同，本机校验合并 Manifest 的共享根和拒绝私有文件，Linux CI 额外校验合法安装 URI。网络重定向测试使用 OkHttp 拦截器；另用与客户端相同的公开请求头，实际读取仓库已有 alpha.15 的发布清单，确认下载响应兼容。

未将上述检查当作 Android 17 真机的后台调度、系统 DownloadManager 断点续传、通知授权、未知来源安装授权和安装器覆盖升级验收。设备验收应从本版发现更高 versionCode 的下一版开始，依次检查 Wi-Fi 下载、离线重试、强制停止/重启后的状态恢复、拒绝权限再授权、取消安装后重试，以及安装后词库、旧数据库和 Key 保留。后台自动更新不承诺精确到点执行。

复现：`./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleRelease`；脚本回归：`python3 -m unittest discover -s scripts -p 'test_*.py'`。发布工作流额外校验原签名、版本、APK 与 14 个原生库的 16 KB 对齐。当前 Release 的最终 CI 测试数与提交见附件 `release-manifest.json`。

## 历史：Loop 0.1.12-alpha.13 验证记录

GitHub 接入增加完整主机测试和发布验证流程，当前结果单独保存在 `verification/github-setup/`。该流程不等同于已经在用户仓库运行 GitHub Actions，远程状态需以实际仓库为准。

接入时执行完整 160 项 Android 主机回归，全部通过、0 跳过；5 项发布脚本测试及 actionlint v1.7.12 工作流检查通过。修正一条旧测试对可选择文字的类型比较，改为比较其文本内容；应用运行代码和版本仍为已交付的 alpha.13。发布测试验证附件完成后才公开、上传/哈希失败保留草稿、不同提交的标签拒绝覆盖、已发布版本不修改，以及拒绝向非 GitHub 主机发送认证信息。

验证日期：2026-09-13。当前版本根据设备日志处理加密数据库故障；下面首先列出本次验证，其后保留 alpha.12 / alpha.11 历史资料，历史设备测试未在本次重新运行。

## alpha.13 本次验证

52 项针对性测试全部通过、0 失败、0 跳过：数据库恢复 9 项、AI 传输 9 项、数据库回归 8 项、Keystore 迁移 8 项、九键 AI 8 项、诊断日志 7 项、通讯录导入 3 项。结果保存在 `verification/database-alpha13/`。

新增恢复测试执行生产恢复和切换代码，使用真实 SQLite 引擎、文件、WAL 和 AES-GCM，并替换 SQLCipher 的 Android JNI 边界。覆盖缺失密钥保护、失败探测保留主库/WAL/journal/Key、副本成功恢复与新密钥重开、完整性/换密钥/重开失败不切换、未 checkpoint 的 WAL 记录保留、独立新库保存联系人和记忆后重开、切换提交失败恢复原指针、活动文件缺失/路径异常保护，以及恢复页取消操作不修改数据。

新增 AI 测试验证数据库报错时实际构造的 HTTPS 请求只含编码、空上下文和空词库，仍使用已保存配置并拒绝伪造读音的模型结果；同时验证本地词条隐私限制、关闭授权和锁屏仍生效。已有协议、数据库、Keystore、日志及通讯录测试也在本次执行。

Release 构建和构建所需的 Lint Vital 检查通过；完整 Lint、全套历史测试及设备测试未重复执行。APK 签名、包名、版本和 16 KB 对齐检查结果见同目录资料。

运行环境为 JDK 21、Robolectric Android 17 / SDK 37。没有调用用户账号或读取真实通讯录；没有在 HONOR 真机上完成恢复，也没有将替换 JNI 的主机测试描述为真实 SQLCipher 加密恢复成功。32 字节清零密码是待验证的旧版兼容路径，日志本身不证明用户旧库符合该情况。加密库缺失正确密钥或已损坏的内容，不能保证恢复。

复现本次测试与构建：

```bash
./gradlew :app:testDebugUnitTest \
  --tests app.loop.ime.DatabaseRecoveryTest \
  --tests app.loop.ime.AiTransportTest \
  --tests app.loop.ime.StoreRegressionTest \
  --tests app.loop.ime.VaultMigrationTest \
  --tests app.loop.ime.NineKeyAiTest \
  --tests app.loop.ime.DiagnosticLogTest \
  --tests app.loop.ime.ContactsImporterTest \
  :app:assembleRelease
```

## alpha.12 历史验证记录

以下日期、数量和“本次”指 alpha.12 当时的验证。

验证日期：2026-09-13。本版增加本地诊断日志、状态文字复制及 TXT 导出，用于定位用户设备上的候选流程和通讯录导入故障；尚未取得设备日志，不能据此认定这两个故障已经修复。

## alpha.12 本次验证

本次执行 32 项针对性回归，全部通过、无跳过：新增日志系统 7 项、通讯录导入 3 项，以及已有 AI 网络传输 6 项、九键 AI 8 项、Keystore 迁移 8 项。运行环境为 JDK 21、Robolectric Android 17 / SDK 37。详细结果见 `verification/diagnostics-alpha12/tests.json` 及同目录的 5 份 JUnit XML。

- 日志回归覆盖异常消息与嵌套异常脱敏、数据库不可读时仍能生成报告、主进程与 ASR 日志合并、轮转与大小限制、过期及损坏行处理、清空、实际设置页的复制与文件导出、错误弹窗复制，以及日志目录不可写时不改变业务异常。
- 通讯录回归使用生产导入器、受控 Android ContentProvider 和真实 SQLite，验证本地词条保存、只查询姓名和 ID、日志不含姓名，并区分权限、数据库和 Provider 查询阶段。Provider 返回 null 时不再显示导入成功。
- Release APK 构建及构建所需的 Lint Vital 检查通过。本次未另行执行完整 Lint 或全部历史回归。
- APK ZIP 完整性、v2 签名、16 KB ZIP 对齐及 14 个原生库的 ELF LOAD 对齐通过。包名仍为 `app.loop.ime`，versionCode=12，min/target SDK=37，非 debuggable；签名与上一版相同。69 个原生库与资源条目和上一版逐字节一致，没有内置 ASR 模型权重。APK 摘要、签名及清单见 `verification/diagnostics-alpha12/`。

本次未使用真机、实际麦克风、用户通讯录或真实云端 API Key；文件导出回归在 Robolectric 中执行，厂商文件选择器需要安装后验收。日志功能未改变云端请求协议、数据库格式或现有 Key 的保存方式。覆盖安装后请分别重现候选和通讯录故障，再按 `DIAGNOSTICS.md` 导出日志。

复现本次构建和测试：

```bash
./gradlew :app:testDebugUnitTest \
  --tests app.loop.ime.DiagnosticLogTest \
  --tests app.loop.ime.ContactsImporterTest \
  --tests app.loop.ime.AiTransportTest \
  --tests app.loop.ime.NineKeyAiTest \
  --tests app.loop.ime.VaultMigrationTest \
  :app:assembleRelease
```

## alpha.11 历史验证记录

以下为 0.1.10-alpha.11 随原始源码提供的历史记录，其中的“本次”“本轮”均指 alpha.11；不代表本次重新运行了设备测试或 138 项完整回归。对应的 `verification/` 根目录资料保留用于追溯。

验证日期：2026-09-12。Release APK，debuggable=false；Android 17 / API 37+，arm64-v8a、x86_64。摘要见 `verification/delivered-apk.json`。

## 本次检查

修复版本新增 28 项主机回归，合计 138 项。数据库回归直接调用生产 `PersonalStore`，使用 AndroidX 接口连接真实 SQLite 引擎；没有复制一套 SQL 作为替代实现。真实网络传输使用本机 WebSocket 服务、合成音频及测试 Key，没有调用用户的云端账号。


| 检查 | 结果与范围 | 证据 |
|---|---|---|
| 生产数据库升级与一致性 | 8 项通过：v1→v2 保留记录并修复孤儿来源；本地词条可查询且不进入云端；云端重试与退避；记忆权限收紧；备份冲突；来源删除撤回分数；事务回滚与版本幂等；迟到学习不能放宽权限 | `verification/TEST-app.loop.ime.StoreRegressionTest.xml` |
| 输入编辑与记忆 | 4 项通过：删除到空、选区替换、跨片段纠错、非记忆输入的本地学习与版本跟踪 | `verification/TEST-app.loop.ime.InputHistoryTest.xml` |
| Android 17 原生数据库专项 | 通过：API 37、16 KB 页、x86_64；使用交付 APK 原始 DEX / SQLCipher JNI，验证 schema 1→2、WAL 多连接读取、调用方密钥清零、外键级联、学习退避、词频撤回、备份认证和独立 Android 进程重开。独立于 138 项主机回归统计 | `verification/native-database/` |
| alpha.10 → alpha.11 实际覆盖更新 | 通过：使用原交付的两个 Release APK，经 PackageInstaller 覆盖更新；旧版保存三组 Key 和 schema 1 测试数据，新版读回 DeepSeek、百炼北京/新加坡 Key、记忆、词条、置顶剪贴板、遗忘词和高度设置；迁移后联系人权限与来源分数正确 | `verification/device-upgrade/` |
| 覆盖更新后强制结束进程 | 通过：显式 force-stop 后启动独立的应用进程，再次核对全部升级数据和三组 Key；使用真实应用 UID / Android Keystore，无注入包装密钥 | `verification/device-upgrade/upgrade-restart-readback.json` |
| 已安装 Release 应用的设备集成 | 6 组通过：设置启动、真实 Keystore 保存/重开及 KeyInfo 参数、实际 BaseInputConnection 的旧补丁拒绝和 Unicode 删除、记忆/来源/备份认证、真实 SQLCipher 升级/多连接/外键，以及写入持久化探针。没有使用 Robolectric | `verification/app-device/app-database-suite.json` |
| 应用新记录的进程重启读取 | 通过：写入新的加密记忆和词条，显式 force-stop 后在新应用进程通过真实 Keystore 解密并读回，本地词条不进入云端集合 | `verification/app-device/app-database-readback.json` |
| 密钥、光标和异常生命周期 | 6 项通过：实际 SQLCipher 配置对象保留密钥引用；光标确认被消费；选区删除及拒绝提交；组合替换和取消；永久/空语音绑定恢复；Rime 异常及非法输出仍完成回调 | `verification/TEST-app.loop.ime.StateRegressionTest.xml` |
| 加密待写恢复 | 2 项通过：填满 256 项数据库队列后，密文日志仍落盘并由重建的写入器补写；加密异常保留最新内存版本，显式删除取消待写 | `verification/TEST-app.loop.ime.DraftWriterTest.xml` |
| 全部连接配置备份 | 3 项通过：跨包装密钥恢复文本 AI、两地域百炼和设置；篡改不得部分覆盖且兼容旧版；文件选择返回缺少密码时提示，Activity 重建后继续且不序列化密码 | `verification/TEST-app.loop.ime.ConnectionBundleTest.xml` |
| AI 九键协议与调度 | 8 项通过：真实读音和编码校验、简体与去重、补全边界、多音字与 ü、获准词条和上下文上限、连续按键去抖与旧结果丢弃、授权复核、字段内缓存及清理、超时与失败退避 | `verification/TEST-app.loop.ime.NineKeyAiTest.xml` |
| 九键 IME 与设置 | 10 项通过：数字页切换清空组合、4 秒迟到预测仍可见、宿主拒绝个人词条不学习、语音失败后重试只提交一次；原始数字不进入宿主或候选栏、删键/重输与正常数字输入、AI 点击单次提交并清空 Rime、空格保留本地首选和旧按钮失效、实际隐私策略、全键盘拼音保留及数字提交兜底、设置开关复用原 Key 并持久化 | `verification/TEST-app.loop.ime.NineKeyImeTest.xml` |
| 下载、续传与安装 | 10 项通过：有序文件与完整哈希、断线后重建下载器续传、服务器忽略 Range、错误 Content-Range、哈希失败后重试、暂停续传、HTTP 404 保留缓存、空间不足与并发锁、ready/删除边界、416 重启与超长文件 | `verification/TEST-app.loop.ime.OfflineModelPackageTest.xml` |
| 无模型设置与升级兼容 | 3 项通过：assets 不含模型、打开下载页不自动下载；旧自定义模型保留；缺失提示结束语音并能点击打开下载页；原生 View 渲染 | `verification/TEST-app.loop.ime.OfflineModelSettingsTest.xml`、`verification/offline-model-alpha9.png` |
| 官方源探测（沿用 alpha.9） | 5 个固定版本下载地址可达，实际 Range 响应长度及前缀与旧包一致；tokens.txt 和 bpe.vocab 全文件哈希相同，三份大权重仅读取前 64 KiB | `verification/model-download-source-probes.json` |
| 百炼协议与重放缓冲 | 5 项通过：模型/ASR duplex/PCM/热词/finish 参数、PCM16 小端字节、网络与鉴权错误分类、按精确采样点剔除已确认音频、缓冲溢出和错误时间戳不静默丢音频 | `verification/TEST-app.loop.ime.BailianProtocolTest.xml` |
| 百炼真实 WebSocket 传输 | 2 项通过：实际 HTTP Upgrade、Bearer、run-task → task-started → 二进制音频 → partial/final → finish-task → task-finished；忽略别的任务、心跳、重复 final；HTTP 401 不回显服务端秘密 | `verification/TEST-app.loop.ime.BailianTransportTest.xml` |
| 百炼 Key 与网络判断 | 3 项通过：按地域加密保存、仓库重开、空值保留、北京/新加坡及 DeepSeek Key 隔离；非法请求头字符不能覆盖旧 Key；尚未验证的互联网仍优先尝试云端，无网络/网页登录网络使用本地 | `verification/TEST-app.loop.ime.CloudSpeechSettingsTest.xml` |
| 云端优先与断网接续 | 13 项通过：无离线包时不打开本地麦克风、云端无需离线包、云端断网缺少本地包时保留确认文字并停录提示；离线启动不读云端 Key且保留开头；隐私字段强制本地；鉴权错误不静默回退；旧 READY 不影响新会话；读 Key 时松手仍识别已录开头且不重开麦克风；握手前采集前缀并与实时音频顺序连接；握手期间断网转本地；发送未确认尾句先于未发送帧回放；大缓存等待写队列恢复后完整补发；云端只发一次 finish，先 final 后 done | `verification/TEST-app.loop.ime.SpeechCloudTest.xml` |
| Keystore 认证异常与迁移 | 8 项通过：新密钥无认证令牌要求；旧密钥报同一认证异常仍可保存新 Key；旧 Key 和数据库密钥值不变地迁移；迁移失败保留旧记录；不能解密的数据库密钥不被替换；加密失败不落盘；锁屏与首次解锁前禁止访问；记录名与版本防篡改 | `verification/TEST-app.loop.ime.VaultMigrationTest.xml` |
| Key 保存及卸载恢复 | 6 项通过：重建配置仓库、丢失配置索引后找到已存 Key；空白保存保留 Key；加密记录不能解密时不覆盖、不重新生成包装密钥；供应商 Key 隔离；清空应用配置且更换包装密钥后用密码备份恢复；错误密码、篡改与截断不替换原配置 | `verification/TEST-app.loop.ime.AiProfilesTest.xml` |
| API 请求与错误阶段 | 6 项通过：AI 九键沿用已有 Key/模型，编码、语境与获准词条载荷，关闭开关或隐私模式不建立请求；官方 Key 鉴权 GET 后执行实际模型 POST、使用同一 Key 快照与 256 tokens；401 停止后续生成且不回显服务端秘密；模型超时显示已通过 Key 鉴权；自定义服务不向 DeepSeek 发送 Key | `verification/TEST-app.loop.ime.AiTransportTest.xml` |
| DeepSeek 协议 | 9 项通过：默认 Flash、关闭思考、JSON 输出、测试请求、Key 隔离、响应校验与 HTTP 错误分类 | `verification/TEST-app.loop.ime.AiProtocolTest.xml` |
| 语音完整流程及取消 | 6 项通过：本次热词在引擎启动前更新、热词等待期间开头音频已采集；连接前取消；旧 READY 不影响新会话；存储队列阻塞不延迟采集，模型加载时松手仍保留 PCM 且不重开麦克风；实际控制器开启 AudioRecord、发送 PCM 与序号、接收 partial、停止并将最终“你好。”提交至 InputConnection；旋转锁无权限的提示不进入识别文字 | `verification/TEST-app.loop.ime.SpeechLifecycleTest.xml` |
| 布局与手势 | 16 项通过：候选条只显示文字且保持单行、按住候选时迟到结果不移动按钮；三档高度保存、九键/全键盘/数字/面板几何与单行工具栏；手势期间调整高度推迟到松手；250 毫秒触发与短按；默认中文九键、英文与数字；短按、长按、取消、移除、滑动、第二触点；系统栏主题；参考图左标点、中字母、右双高回车几何关系；语音前后空格键实例和键盘高度保持一致；不存在自建切换栏；候选和工具位于同一 44 dp 行，主体不因状态改变增高；三档均在 320 dp 宽、1.6 倍系统字号下标签单行且横纵不截断；候选存在时存储错误仍可见，提示结束后恢复候选 | `verification/TEST-app.loop.ime.KeyboardInteractionTest.xml` |
| 安全区与设置页 | 4 项通过：caption-only 底部避让、导航区最大值与重复分发、横屏与面板避让；默认 AI 页只有一个 Key 输入框 | `verification/TEST-app.loop.ime.KeyboardInsetsTest.xml` |
| 九键词库与纠错保护 | 6 项通过：拼音与人名九键映射、ü/v、受限查询编码；数字、单位、否定词、专名、大幅改写、HTTPS 与旧锚点保护 | `verification/TEST-app.loop.ime.NineKeyTest.xml`、`verification/TEST-app.loop.ime.TextRulesTest.xml` |
| 界面渲染 | 原生图形模式绘制实际 Android Views：360 dp 三档九键及候选、空闲、英文、云端录音；320 dp / 1.6 倍系统字号三档。目视检查文字、图标、间距和底部；不模拟系统切换栏 | `verification/keyboard-alpha8-high.png`、`verification/keyboard-alpha8-medium.png`、`verification/keyboard-alpha8-low.png`、`verification/keyboard-alpha8-large-font-*.png` |
| 构建 | 138 项测试，0 失败、0 跳过；Release APK 与设备测试 APK 编译成功 | `verification/gradle-build.txt`、`verification/unit-tests-summary.json` |
| Lint | 0 错误、28 警告 | `verification/lint-results.txt` |
| 更新签名与 SDK | v2 验签通过，沿用 alpha.1 / alpha.2 / alpha.3 / alpha.4 / alpha.5 / alpha.6 / alpha.7 / alpha.8 / alpha.9 / alpha.10 证书；versionCode=11，min/target SDK=37，非 debuggable | `verification/apk-signature.txt`、`verification/apk-manifest.txt` |
| 16 KB 与原生资源 | APK 16 KB 对齐通过；14 个 ELF 原生库 LOAD 对齐通过；与 alpha.9 逐文件比较，原生库和 Rime 字典配置均未变化；确认 APK 仍不包含 ASR 数据包 | `verification/apk-alignment.txt`、`verification/native-alignment.json`、`verification/unchanged-native-assets.json` |

## 验证边界

新增数据库回归验证生产 SQL、升级事务和 SQLite 外键行为；SQLCipher 密钥测试使用固定依赖的真实连接配置对象检查数组引用。这些主机测试不等于设备上的 SQLCipher JNI、连接池与 Keystore 集成。

本轮新增设备验证保持交付 APK 的字节内容不变；138 项主机回归沿用该 APK 构建时的通过记录，本轮没有重复计数或宣称重跑主机套件。生产运行文件的逐文件对照见 `verification/source-update.json`。

补充的原生数据库专项已经在 Android 17 的 ART / SQLCipher JNI 上通过。测试从已交付的 Release APK 提取原始 DEX 和原生库，哈希及测试 APK 保存在 `verification/native-database/provenance.json`，没有替换数据库实现。该专项通过 `app_process` 运行，使用合成数据库密钥，单独覆盖真实连接池和进程重启；应用沙箱、Keystore 与 PackageInstaller 升级由另外的设备集成用例验证通过。实际应用的 KeyInfo 也确认 AES-256，isUserAuthenticationRequired=false、isUnlockedDeviceRequired=false；应用每次访问的解锁检查仍由生产 Vault 执行。

自动回归使用 Robolectric 4.17、Android 17 / SDK 37 运行时和 JDK 21。语音测试调用生产 AudioRecord 与 SpeechController，音频由 ShadowAudioRecord 提供，ASR 服务使用受控 Messenger 消息；真实 InputConnection 组合与提交通过 Android BaseInputConnection 执行。它验证正常启动、PCM 传输及停止提交链路，不代表已在真实麦克风或厂商 ROM 上录音，也不执行本地模型推理。

Key 主机测试使用真实 AES-GCM、PBKDF2 和 Android SharedPreferences，并注入测试包装密钥；旧密钥的 UserNotAuthenticatedException 是受控故障注入，未声称在用户手机上复现厂商 Keystore 驱动异常。补充的覆盖更新测试已在真实 Android 17 系统中使用应用 UID 和 Android Keystore：通过旧版生产接口保存 DeepSeek 和百炼两地域 Key，覆盖安装后及显式 force-stop 后均能原值读回，没有注入包装密钥。schema 1 的记忆、词条等是直接写入 SQLCipher 的合成记录，不是通过旧键盘实录的历史；联系人记录只验证来源权限，不代表执行了手机通讯录导入界面。包名和签名一致，升级支持保留原存储；旧版不支持 schema 2 数据库。没有执行 PackageInstaller 卸载后重装恢复测试；卸载后恢复依赖用户事前导出的密码备份，已经删除且没有备份的 Key 无法找回。

九键 IME 检查使用生产 LoopImeService、SafeEditor 和真实 BaseInputConnection，只有 Rime 原生输出采用可控替身；没有将这些回归当作真实模型准确率验证。AI 候选使用真实 ICU 转写/简体转换；不匹配、无法验证的多音字和超时结果被舍弃。

文本 API 测试采用受控 HTTPS 连接，验证真实请求路径、鉴权头、JSON 参数、超时与错误分支。没有用户的 DeepSeek 或百炼 Key，未进行用户账号的实际模型调用。百炼测试调用生产 OkHttp WebSocket 客户端，测试工厂仅将连接改向本机 MockWebServer，实际执行升级、文本与二进制帧传输；生产只使用固定官方 wss 地址和默认 TLS 校验，禁止重定向。断网回退用受控 AudioRecord 和本地 Messenger，不代表已经测得真机切换延迟。

保留上版 `verification/rime-android-16k.txt` 的 8 组真实 Android 17、16 KB 页 x86_64 原生 Rime 检查：九键“你好、中国、汉语、学习、西安”，简体全键盘与往返切换，包含删键重输和候选提交。原生库和字典本版未改动，这 8 组未重复运行。更早的桌面 CPU ASR 公共音频检查保留在 `verification/model-cpu-test.json`，不当作本版真机性能数据。

DraftWriter 的 StaticFieldLeak 提示来自应用级单例持有 Context；生产入口只传入 applicationContext，不持有 Activity 或 IME Service。未通过屏蔽规则隐藏该提示。

Lint 主要提示资源化、图标绘制临时对象、布局工具构造器、删除键触摸无障碍、旋转恢复同步落盘及可更新依赖；没有关闭 Lint 隐藏警告。AGP 9.1.1 的 D8 提示 API 37 高于其识别的 API 36；构建成功，APK 实际 min/target SDK 均为 37。

下载回归使用真实 OkHttp 与本机 HTTP 服务，不会访问作者仓库。沿用 alpha.9 的真实官方下载地址 Range 探测记录，本轮没有用手机完整下载约 190 MiB 模型并执行推理。模型文件只从完整校验后的私有目录加载；完整设备 ASR 用例需先在设置中安装离线模型。

仍需目标 Android 17+ 手机验收：厂商 ROM 上的更新与文件选择器导入导出、系统切换栏、麦克风和权限、模型冷启动、长按松手尾句、长时间语音与发热、第三方编辑器及用户的 DeepSeek / 百炼账号、真实网络断开和模型冷启动接续。本轮已完成独立原生数据库、实际覆盖更新及 7 组应用内专项测试；包括麦克风与下载模型推理的完整设备套件未执行。

## 复现

```bash
# JDK 21、Android SDK 37.0 / Build Tools 37.0.0
./gradlew :app:testDebugUnitTest :app:lintRelease :app:assembleRelease :app:assembleDebugAndroidTest
# 连接已解锁的 Android 17+ 测试设备，并先下载离线模型后：
./gradlew :app:connectedDebugAndroidTest
# 仅运行设置启动与真实 Android Keystore 检查（已安装 debug app 和 test APK）：
adb shell am instrument -w -r -e only keystore app.loop.ime.test/app.loop.ime.LoopInstrumentation
# 数据库专项（无需 ASR 模型），再终止目标进程后检查持久记录：
adb shell am instrument -w -r -e only database app.loop.ime.test/app.loop.ime.LoopInstrumentation
adb shell am force-stop app.loop.ime
adb shell am instrument -w -r -e only database_readback app.loop.ime.test/app.loop.ime.LoopInstrumentation
```

仅运行原生数据库专项，不需要安装应用或离线 ASR 模型：

```bash
python3 scripts/check-native-database.py --serial emulator-5554 \
  --apk ../Loop-IME-0.1.10-alpha.11.apk \
  --tests verification/native-database-tests.apk
```

完整的覆盖更新检查需要专用 Android 17+ 测试设备，旧版应用存储必须为空。脚本不会卸载或清空已有数据，显式指定设备序列号后运行：

```bash
python3 scripts/check-device.py --serial emulator-5554 \
  --apk ../Loop-IME-0.1.10-alpha.11.apk \
  --baseline ../Loop-IME-0.1.9-alpha.10.apk \
  --upgrade-tests verification/upgrade-tests.apk \
  --tests verification/app-device-tests.apk
```

省略 `--baseline` 和 `--upgrade-tests` 可只运行新版应用中的 Keystore / 数据库检查。无硬件加速的模拟器可以添加 `--timeout-scale 10`，仅延长原生并发测试的等待上限，不改应用超时。脚本读取原始 instrumentation 结果，退出码为 0 但没有明确 PASS 时也会判失败。

需要重新生成升级专用测试 APK 时，使用 `./gradlew -PloopTestRunner=app.loop.ime.UpgradeInstrumentation :app:assembleDebugAndroidTest`，保留产物后再不带该参数生成常规设备测试 APK。AGP 只配置一个有效测试入口，两个测试 APK 依次安装；源码同时提供两种已编译产物。复现脚本已通过语法与命令行参数检查；本轮设备流程由相同的 ADB 操作逐阶段执行并核对结果，没有另外重跑整个 Python 脚本流程。

布局测试将十张键盘界面渲染到 `app/build/keyboard-alpha8*.png`。新增候选条渲染到 `app/build/keyboard-alpha10-ai-candidates.png`；下载页另渲染到 `app/build/offline-model-alpha9.png`。交付源码中的对应图像位于 `verification/`。中文九宫格高/中/低为 270/246/222 dp，英文为 266/242/218 dp；实际高度另加 Android 提供的系统安全区。Gradle 内的 Robolectric JVM 参数仅用于主机测试，不进入 APK。词典、原生依赖与公开开发签名均包含在源码 ZIP 中，模型只保留固定下载清单；首次构建需要访问 Google Maven 和 Maven Central。


## 0.1.13-alpha.14 历史验证

- 169 项 Robolectric / JVM 主机测试通过，零失败、零跳过；5 项发布边界测试通过。
- 新增：云端返回和错误不隐藏本地候选、下拉展开、滚动面板高度、125 项引擎候选的原索引选择、超过三个个人候选、联系人前缀匹配、个人词库分页无重复、同句重复选词、删除词频撤回、备份重复导入保留权重。
- 主机数据库测试使用真实 SQLite 与生产数据库迁移代码；不等同于本轮真实 SQLCipher / Keystore 的设备测试。
- NDK r28c 官方 ZIP SHA-1 已与官方 SDK 清单核对；已重编译 arm64-v8a 和 x86_64 的 libloop_rime.so。
- APK 原签名、包名和版本、ZIP 完整性及所有 ELF 的 16 KB 对齐通过发布打包脚本检查。
- 本轮没有连接用户手机或执行新设备的覆盖升级、麦克风与真实账号测试。历史设备证据仍属于对应旧版本。

## 0.1.14-alpha.15 验证（2026-09-27）

- Windows 11 主机，Android Studio JBR 21.0.8、项目 Gradle Wrapper 9.3.1、Google 官方 SDK 37.0 r2 / Build Tools 37.0.0。SDK ZIP 与官方清单校验值匹配，Gradle ZIP 与 Wrapper 固定 SHA-256 匹配。
- 183 项 Robolectric / JVM 回归通过，零失败；5 项发布边界测试通过。实际数据库测试使用 SQLite 框架和生产 PersonalStore，并非设备 SQLCipher / Keystore 测试。
- 另有 3 项依赖重组回归通过（精确还原、损坏拒绝、保留已有修改/阻止越界），Python 测试总计 8 项。仓库内两项大依赖以 16 MiB 分块保存，首次构建前校验并还原；原始文件 SHA-256 记录于 `third_party/multipart.json`。
- 最终复跑修正了旧语音测试的一处竞态：模拟源交付不等于主线程消费，冷启动缓存用例现在等待实际已消费样本数，再交付下一帧；应用的队列保护与超时保持原逻辑。
- 新增覆盖：composing 后移动光标、回车动作标志、单位/币种/英文否定词、云端热词子串和配置期间权限变更、过期及乱序备份、合并预览回滚、存储队列拒绝、关闭历史后的当前剪贴板、英文语音 partial/final 连接、AI 建议/关闭模式、按应用隐私偏好、配置备份及旧模型清理。
- `:app:lintDebug` 通过（0 error、36 warning）；Release APK 构建通过。保留现有 D8 关于 API 37 的兼容性警告，未降低应用 SDK。未将主机通过当作 Android 17 真机兼容性结论。
- APK 包名 `app.loop.ime`，versionCode 15、versionName `0.1.14-alpha.15`；开发签名 SHA-256 为 `8ce80c674b10d7a75f2262e711337f1fd53c6b23e9554fe04fab1bd8a4ae6bcb`，与旧版一致。ZIP 16 KB 对齐通过。
- 未执行本轮模拟器/真机安装、旧 SQLCipher 覆盖更新、真实麦克风/蓝牙路线或真实 API 账号测试。语音路径测试使用模拟音频源与模拟云端。
- GitHub CI 会在推送后重新测试和打包；其运行与 Release 状态应以实际 Actions / Release 页面为准。旧 `verification/` 中的设备日志和生成产物不提交至公开仓库；可按本文命令重新生成测试 APK。
