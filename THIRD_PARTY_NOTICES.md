# 第三方组件与来源

Loop 自有代码以 GPL-3.0-or-later 提供。完整许可证见根目录 `LICENSE`。下面组件和模型保持各自的许可与版权声明。

| 组件 | 固定版本 / 来源 | 许可 |
|---|---|---|
| Trime 原生 Rime 库 | [osfans/trime v3.3.12](https://github.com/osfans/trime/tree/v3.3.12)，arm64-v8a / x86_64 release APK 内的 `librime_jni.so` | GPL-3.0-or-later；完整对应源码包随附 |
| librime C API 头文件 | [rime/librime 1.17.0](https://github.com/rime/librime/tree/1.17.0) | BSD-3-Clause |
| 白霜中文词库 | [gaboolic/rime-frost 固定提交 3ad2cb3](https://github.com/gaboolic/rime-frost/tree/3ad2cb34e3c5763ba3f8da0a617fcaa221b355aa)；原样收录 8105、41448、base、ext 四张表 | 上游 GPL-3.0；完整 LICENSE 内置于 `assets/licenses/rime-frost-GPL-3.0.txt`，每张表原始作者、数据来源说明均保留；摘要及条数见 `third_party/rime-frost.json` |
| 朙月拼音、Prelude、Essay、Stroke | Trime v3.3.12 固定的 Rime 数据子模块；提交编号见 `third_party/trime-submodules.txt` | 原仓库 LGPL-3.0 声明，保留于源码归档 |
| OpenCC、Lua、LevelDB、Marisa、yaml-cpp、Snappy、glog 等 | Trime v3.3.12 的固定源码子模块，见归档内各 `LICENSE` 和 `COPYING` | 各自的 Apache、BSD、MIT 等许可 |
| Boost | 1.89.0，Trime 原生构建依赖 | Boost Software License 1.0 |
| sherpa-onnx | [k2-fsa/sherpa-onnx v1.13.8](https://github.com/k2-fsa/sherpa-onnx/tree/v1.13.8)；官方 Android AAR | Apache-2.0 |
| ONNX Runtime | Sherpa 官方 AAR 内随附的运行库 | Microsoft MIT |
| 中英流式模型 | [sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20](https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20/tree/98590b7ed6443e77b714204da2757d75e1a642f4)，提交 `98590b7ed6443e77b714204da2757d75e1a642f4` | 模型卡标注 Apache-2.0 |
| 公开音频测试样本 | 同一模型仓库 `test_wavs/1.wav`，仅打包到测试 APK | 随该模型仓库分发 |
| SQLCipher Android Community | `net.zetetic:sqlcipher-android:4.10.0` | [SQLCipher 原许可](https://github.com/sqlcipher/sqlcipher-android/tree/v4.10.0) |
| Robolectric（仅测试） | `org.robolectric:robolectric:4.17`，Android 17 测试运行时 | MIT；不打包进入交付 APK |
| JSON-java（仅测试） | `org.json:json:20240303` | 公有领域；不打包进入交付 APK |
| OkHttp / Okio | `com.squareup.okhttp3:okhttp:4.12.0` / `com.squareup.okio:okio-jvm:3.6.0`，WebSocket 传输 | Apache-2.0，Square, Inc. |
| MockWebServer（仅测试） | `com.squareup.okhttp3:mockwebserver:4.12.0` | Apache-2.0；不打包进入交付 APK |
| AndroidX SQLite | `androidx.sqlite:sqlite:2.5.2` | Apache-2.0 |
| Kotlin / Gradle / Android 构建工具 | AGP 内置 Kotlin；Gradle 9.3.1；AGP 9.1.1 | 各项目许可；Android SDK 另有官方 SDK 许可 |

`third_party/trime-v3.3.12-corresponding-source.tar.gz` 包括原始 Trime 以及递归子模块源码、CMake/Gradle 构建文件与各组件原许可，剔除了 Git 数据库。解压后可依上游构建步骤重建原始 Rime 库；包含的 `Boost.cmake` 固定了 Boost 版本与 SHA-256。`third_party/boost-1.89.0-cmake.tar.xz` 另附 Boost 1.89.0 对应源码，SHA-256 为 `67acec02d0d118b5de9eb441f5fb707b3a1cdd884be00ca24b9a73c995511f74`。Loop 使用该库公开的 `rime_get_api`，自有 JNI 桥不调用 Trime 应用的 JNI 注册入口。

要重建完整 Rime 二进制，可在该归档解压目录安装其 Gradle 配置规定的 SDK/NDK/CMake，运行上游 `:app:assembleRelease`，然后从生成 APK 提取目标 ABI 的 `librime_jni.so`。自有 JNI 桥的重建命令见 `scripts/build-native.sh`。

模型权重不再打包进 APK 或源码 ZIP，五个文件按需从上方作者仓库的固定提交下载；`encoder.onnx` 和 `joiner.onnx` 为原始 INT8 文件重命名，`decoder.onnx` 为原始 FP32 文件重命名。未修改权重。`tests/model-manifest-example.json` 可用于生成兼容模型 ZIP 的 manifest 示例。

依赖二进制和自有 JNI 库的 SHA-256 见 `third_party/checksums.json`；可选模型的文件大小、原始文件名和 SHA-256 见 `third_party/offline-model-manifest.json`；APK 内同时包含 `assets/NOTICE.txt` 和 `assets/licenses/` 中的完整许可文本。没有包含用户 API Key、通讯录、私人录音或输入历史。
