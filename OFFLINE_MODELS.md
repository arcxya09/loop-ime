# 可选离线语音模型

0.1.8-alpha.9 起，APK 和源码 ZIP 不携带 ASR 权重。拼音词典、sherpa-onnx/ONNX Runtime 原生运行库保留，基础输入和百炼云端语音可独立使用。

## 用户操作

语音设置 → 离线模型：下载与管理 → 下载离线模型。模型约 190 MiB，无需 Key。下载页显示进度，支持暂停和手动继续；离开页面、退出设置或切换到其他应用会请求暂停。部分文件保存在应用私有目录，进程重启后可续传。完成下载后可以断网识别；同页可删除推荐模型或未完成的缓存。

下载不会自动开启麦克风、更换云端 Key 或修改用户导入模型的选择。启用百炼时，云端优先策略保持不变；断网接续以离线模型已安装为前提。缺少模型时停止录音，保留已确认文字并提供下载入口；未确认尾句清除，不会延迟写入其他输入框。

## 固定来源与校验

模型：`sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20`。固定提交 `98590b7ed6443e77b714204da2757d75e1a642f4`，与上一版内置权重一致；使用 INT8 encoder/joiner、FP32 decoder，支持中英流式 transducer 与热词。

来源：[模型作者仓库](https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20/tree/98590b7ed6443e77b714204da2757d75e1a642f4)，[sherpa 官方使用说明](https://k2-fsa.github.io/sherpa/onnx/pretrained_models/online-transducer/zipformer-transducer-models.html#csukuangfj-sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20-bilingual-chinese-english)。模型许可为 Apache-2.0，原模型卡及相关许可保留在 `third_party/` 和 APK 许可目录。

| 本地文件 | 原始文件 | 字节数 |
|---|---|---:|
| encoder.onnx | encoder-epoch-99-avg-1.int8.onnx | 181,895,032 |
| decoder.onnx | decoder-epoch-99-avg-1.onnx | 13,876,452 |
| joiner.onnx | joiner-epoch-99-avg-1.int8.onnx | 3,228,404 |
| tokens.txt | tokens.txt | 56,317 |
| bpe.vocab | bpe.vocab | 12,564 |
| 合计 | | 199,068,769 |

每个文件的 SHA-256 见 `third_party/offline-model-manifest.json`，编译时固定在 `OfflineModelCatalog` 中。下载使用 HTTPS GET，无 Authorization、无用户 Key、无输入文本或录音；允许作者仓库跳转到 HTTPS 文件 CDN，禁止降级到 HTTP。

## 实现与故障恢复

- `ModelDownload` 使用独立工作线程，不占用词库/记忆线程。请求带 `Accept-Encoding: identity`，部分文件使用 HTTP Range；206 必须匹配起点、末点、总长度，200 忽略 Range 时从头覆盖，416 最多重置一次。限制每个文件的写入长度，检查剩余空间。
- `OfflineModelPackage` 在 `noBackupFilesDir/offline-models/<版本>.partial` 暂存。文件锁防止并发写入，暂停/网络失败保留进度。全包逐文件校验后写入 ready 标记，并在同一文件系统重命名为最终目录；损坏、缺失、下载未完成的文件不会成为可用模型。
- `AsrService` 从已选择的绝对路径加载，不再使用 assets。推荐模型在首次初始化时于 ASR 工作线程重新校验哈希。校验失败撤销 ready 标记，显示重新下载入口；普通识别仍保留启动音频缓存。
- 自定义 ZIP 导入方式不变。删除推荐模型只操作推荐目录及对应下载缓存，不修改自定义目录、词库、记忆或 Key。APK 覆盖更新保留应用私有目录中的下载包；从带内置模型的旧版本升级，需先下载才能继续离线语音。

验证包括真实本机 HTTP 的断线、Range、取消、哈希和发布时序；官方下载地址做了 Range 和内容探测。没有在用户手机上完整下载并测量推理性能，具体证据和范围见 `TESTING.md`。
