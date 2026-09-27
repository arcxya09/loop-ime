# 百炼云端语音

默认模型：`qwen-audio-3.0-asr-flash-streaming`。语音页选择地域并保存相应 Key 后开启云端语音；文本 AI 的 DeepSeek 配置独立保留。

## 接口与实现

| 项目 | 本版实现 |
|---|---|
| 地域 | 北京 `wss://dashscope.aliyuncs.com/api-ws/v1/inference`；新加坡 `wss://dashscope-intl.aliyuncs.com/api-ws/v1/inference` |
| 鉴权 | WebSocket 握手头 `Authorization: Bearer …`，默认 TLS 校验，禁止重定向 |
| 任务 | 同一 UUID 的 run-task、task-started、result-generated、finish-task、task-finished |
| 音频 | 单声道 16 kHz、16 位有符号小端 PCM；80 ms 帧，按二进制发送；语音触发后立即开麦克风，在内存缓冲；收到 task-started 后才发送音频 |
| 分句 | 服务端 VAD，静音阈值 900 ms，多阈值模式及静音心跳开启；不做本机固定 10 秒云端任务切割 |
| 结果 | partial 替换当前组合；sentence_end 才提交稳定结果；句子 ID 去重，忽略其他任务及心跳 |
| 热词 | 最多 64 个已允许云端的词条，即时 vocabulary，权重 3；不上传通讯录、仅本地词条或历史上下文 |
| Key | 独立 `speech.bailian.<region>` Vault v2 加密记录；空白保存保留旧值；非法值不覆盖；覆盖安装保留 |
| 连接测试 | 实际开启模型任务并传输半秒合成静音后正常收尾；不录麦克风、不上传输入历史 |

官方已推荐业务空间专属域名，同时明确旧域名仍可使用。本版采用仍受支持的固定地域地址，用户只需 Key，无需另填 Workspace ID。[官方 WebSocket 接口](https://help.aliyun.com/zh/model-studio/fun-asr-realtime-websocket-api)

参数、即时热词与停止事件依据[客户端事件](https://help.aliyun.com/zh/model-studio/fun-asr-client-events)，稳定句、句子编号与时间戳依据[服务端事件](https://help.aliyun.com/zh/model-studio/fun-asr-server-events)。地域支持见[实时语音识别指南](https://help.aliyun.com/zh/model-studio/real-time-speech-recognition-user-guide)。核对日期：2026-09-12。

## 云端语音与隐私边界

0.3.1 移除离线识别、独立识别进程和模型管理。未联网、未开启云端语音或隐私限制时，不读取 Key、不打开麦克风。密码、应用隐私、全局隐私及系统标记不允许个性化学习的字段禁用语音。旧版关闭云端的偏好不会自动开启；历史 Key、个人数据及用户导入文件保留。

已开始的云端会话每秒检查连接和授权，发送音频前再次校验授权。网络中断、传输超时、Key/额度/权限错误会停止录音并提示，已确认文字保留。未确认音频及临时识别结果不保证保留，不会重发到其他识别引擎；联网后可重新开始。输入框拒收已识别文字时仍提供原有内存恢复入口。

## 启动缓存与收尾

麦克风先于异步 Key 读取和 WebSocket 握手启动，按顺序缓冲开头 PCM；task-started 只允许发送，不会重新打开麦克风。每次最多补发 5120 采样点，剩余帧间隔 20 ms 调度，写队列达 64 KB 时等待，连续 8 秒无法发送则停止录音。控制器发送完每帧后清零原数组，不再留存供离线回放的音频副本。

松手立即停止采集，Key/握手回调可处理已录下的本次音频。等待最后一帧、发送完队列后只发一次 finish-task。取消、隐藏或切换字段清除缓冲，旧回调不影响新会话。未发送与已发送未确认的时间窗口仍限制为 120 秒；云端确认仅更新采样点计数，异常时间戳会停止会话。

## 验证范围

主机回归覆盖联网流式输入、开头缓存、松手收尾、握手/录音中断网、写队列背压、Key 错误、关闭/撤销授权、隐私字段和旧回调隔离；协议和 WebSocket 使用 MockWebServer，麦克风使用受控 AudioRecord。设备测试 APK 保留真实 Rime、数据库与密钥检查，不再加载离线模型。未使用真实百炼账号或 MagicOS 真机；详细证据见 `TESTING.md`。

连接配置加密备份可包含各地域百炼 Key，覆盖升级继续保留原加密记录。
