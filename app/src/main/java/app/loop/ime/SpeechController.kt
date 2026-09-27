package app.loop.ime

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.media.*
import android.os.*
import java.util.ArrayDeque
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** One microphone session; cloud and local engines share its ordered PCM stream. State lives on main. */
class SpeechController(private val c: Context,private val event: (Int,String)->Unit) {
    private enum class Engine { CONFIG, CLOUD, LOCAL }
    private var engine=Engine.LOCAL
    private var remote: Messenger?=null
    private var bound=false
    @Volatile private var epoch=0L
    private var wantsStart=false
    private var hotwords=""
    private val running=AtomicBoolean(false)
    @Volatile private var recorder: AudioRecord?=null
    private var finalizing=false
    private var active=false
    private var captureStarted=false
    private var captureEnded=false
    private var localReady=false
    private var localModelPath=""
    private var cloudReady=false
    private var cloudPumpScheduled=false
    private var lastCloudSend=0L
    private var capturedSamples=0L
    private var endSent=false
    private var outstanding=0
    private var localSequence=0
    private var lastAck=0L
    private var engineSince=0L
    private var finishDeadline=0L
    private val pending=ArrayDeque<ShortArray>()
    private var pendingSamples=0
    private val replay=PcmReplay()
    private val rotation=RotationLock(c)
    private var cloud: CloudAsrStream?=null
    internal var cloudFactory: (CloudAsrProfile,List<String>,CloudAsrListener)->CloudAsrStream={ p,w,l -> BailianAsr(p,w,l) }
    internal var cloudProfileProvider: ()->CloudAsrProfile?={ CloudSpeechSettings(c).profile() }
    internal var online: ()->Boolean={ CloudSpeechSettings.online(c) }
    internal var localModelProvider: ()->String?={ OfflineModels.activePath(c) }
    val recognitionLabel get()=if(engine==Engine.CLOUD)"百炼云端" else "本地识别"
    val inputRouteLabel: String get()=when(recorder?.routedDevice?.type) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "内置麦克风"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "有线耳麦"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,AudioDeviceInfo.TYPE_BLE_HEADSET -> "蓝牙耳麦"
        AudioDeviceInfo.TYPE_USB_DEVICE,AudioDeviceInfo.TYPE_USB_HEADSET -> "USB 麦克风"
        else -> "系统音频输入"
    }
    private val cloudPump=Runnable { cloudPumpScheduled=false;pumpCloud() }
    private val watchdog=object: Runnable {
        override fun run() {
            if(!active)return
            val now=SystemClock.elapsedRealtime()
            when {
                engine==Engine.CLOUD && !online() -> fallback("网络已断开")
                engine==Engine.CLOUD && cloudReady && pending.isNotEmpty() && now-lastCloudSend>8000 -> fallback("云端音频发送超时")
                engine==Engine.CONFIG && now-engineSince>10000 -> fail("读取百炼配置超时，请重新开始")
                engine==Engine.LOCAL && !localReady && now-engineSince>45000 -> fail("本地模型加载超时，请检查可用内存")
                engine==Engine.LOCAL && localReady && outstanding>0 && now-lastAck>6000 -> fail("本地识别暂时跟不上录音，已停止")
                finalizing && now>finishDeadline -> fail("收尾超时，已保留稳定片段")
            }
            if(active)LoopApp.main.postDelayed(this,1000)
        }
    }
    private val reply=Messenger(object: Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            if(msg.data.getLong("epoch")!=epoch || !active || engine!=Engine.LOCAL)return
            when(msg.what) {
                SpeechWire.READY -> {
                    if(localReady)return
                    localReady=true;lastAck=SystemClock.elapsedRealtime()
                    if(!finalizing)event(SpeechWire.READY,"正在听 · 本地识别")
                    if(active)pumpLocal()
                }
                SpeechWire.ACK -> { outstanding=maxOf(0,outstanding-1);lastAck=SystemClock.elapsedRealtime();pumpLocal() }
                SpeechWire.DONE -> if(endSent)complete() else fail("本地语音服务提前结束")
                SpeechWire.ERROR -> fail(msg.data.getString("text","本地语音错误"))
                SpeechWire.MODEL_REQUIRED -> fail(msg.data.getString("text","离线模型未下载 · 点击下载"),SpeechWire.MODEL_REQUIRED)
                else -> event(msg.what,msg.data.getString("text",""))
            }
        }
    })
    private val connection=object: ServiceConnection {
        override fun onServiceConnected(name: ComponentName,service: IBinder) {
            remote=Messenger(service)
            try { service.linkToDeath({ LoopApp.main.post { if(remote?.binder===service && engine==Engine.LOCAL)fail("语音进程已退出，录音已停止") } },0) }
            catch(_: Exception) { if(engine==Engine.LOCAL)fail("无法连接语音服务");return }
            if(active && engine==Engine.LOCAL && wantsStart)sendStart()
        }
        override fun onServiceDisconnected(name: ComponentName) { remote=null;if(engine==Engine.LOCAL)fail("语音服务断开，录音已停止") }
        override fun onBindingDied(name: ComponentName) { releaseBinding();if(engine==Engine.LOCAL)fail("语音服务已更新，请重新开始") }
        override fun onNullBinding(name: ComponentName) { releaseBinding();if(engine==Engine.LOCAL)fail("语音服务未能启动，请重新开始") }
    }
    private fun releaseBinding() { remote=null;if(bound) { bound=false;runCatching { c.unbindService(connection) } } }
    fun start(words: List<String>,allowCloud: Boolean=true,cloudWords: List<String> = emptyList(),vocabulary: (() -> Pair<List<String>,List<String>>)?=null) {
        if(active)return
        if(c.checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED) { event(SpeechWire.ERROR,"请先在 Loop 设置中允许麦克风");return }
        val cloudAllowed=allowCloud && !Prefs(c).privateMode && Prefs(c).flag("speech_cloud")
        if((!cloudAllowed || !online()) && localModelProvider()==null) {
            event(SpeechWire.MODEL_REQUIRED,"离线模型未下载或文件不完整 · 点击下载");return
        }
        epoch++;active=true;finalizing=false;captureStarted=false;captureEnded=false;endSent=false
        capturedSamples=0;cloudReady=false;localReady=false;engine=Engine.CONFIG;engineSince=SystemClock.elapsedRealtime()
        // Capture first. Key lookup, WebSocket handshake and model loading must not eat the prefix.
        beginCapture()
        if(!active)return
        hotwords=words.take(64).mapNotNull(TextRules::cleanTerm).joinToString("\n")
        if(Prefs(c).flag("rotation") && !runCatching { rotation.acquire() }.getOrDefault(false))event(SpeechWire.READY,"正在准备语音（旋转锁未获授权）")
        if(vocabulary!=null || (cloudAllowed && online())) {
                engine=Engine.CONFIG;engineSince=SystemClock.elapsedRealtime()
                event(SpeechWire.READY,if(cloudAllowed)"正在听 · 连接百炼中" else "正在听 · 准备本地识别")
                val id=epoch
                try { CloudSpeechSettings.io.execute {
                    val hintsRevision=StoreEvents.revision
                    val hints=if(vocabulary==null)words to cloudWords else runCatching { vocabulary() }.getOrDefault(emptyList<String>() to emptyList())
                    val result=runCatching { if(cloudAllowed && online())cloudProfileProvider() else null }
                    LoopApp.main.post {
                        if(id==epoch && active && engine==Engine.CONFIG) {
                            hotwords=hints.first.take(64).mapNotNull(TextRules::cleanTerm).joinToString("\n")
                            result.fold({ p -> if(!cloudAllowed || !online())startLocal("正在听 · 本地模型准备中") else if(p==null)fail("请先在语音设置中保存百炼 Key") else startCloud(p,if(hintsRevision==StoreEvents.revision)hints.second else emptyList()) },
                                { fail("百炼 Key 无法读取，请解锁手机或到语音设置重新保存") })
                        }
                    }
                } } catch(_: RejectedExecutionException) { fail("语音配置暂时繁忙，请稍后重试") }
        } else startLocal("正在听 · 本地模型准备中")
        if(active) { LoopApp.main.removeCallbacks(watchdog);LoopApp.main.postDelayed(watchdog,1000) }
    }
    private fun startCloud(profile: CloudAsrProfile,words: List<String>) {
        engine=Engine.CLOUD;engineSince=SystemClock.elapsedRealtime()
        val id=epoch
        fun current()=active && id==epoch && engine==Engine.CLOUD
        val listener=object: CloudAsrListener {
            override fun ready() {
                if(!current() || cloudReady)return
                cloudReady=true;lastCloudSend=SystemClock.elapsedRealtime()
                if(!finalizing)event(SpeechWire.READY,"正在听 · 百炼云端")
                scheduleCloudPump()
            }
            override fun partial(text: String) { if(current())event(SpeechWire.PARTIAL,text) }
            override fun final(text: String,endMs: Long) {
                if(!current())return
                try { replay.confirm(endMs) } catch(_: Exception) { fail("云端音频时间戳异常，录音已停止");return }
                if(text.isNotBlank())event(SpeechWire.FINAL,text) else event(SpeechWire.PARTIAL,"")
            }
            override fun done() { if(current()) { if(endSent)complete() else fail("云端识别提前结束") } }
            override fun failed(error: AsrFailure) {
                if(!current())return
                if(error.kind==AsrFailureKind.NETWORK)fallback(error.message) else fail(error.message)
            }
        }
        try { cloud=cloudFactory(profile,words,listener);cloud!!.start() }
        catch(_: Exception) { fail("无法启动百炼识别，请检查语音设置") }
    }
    private fun fallback(reason: String) {
        if(!active || engine!=Engine.CLOUD)return
        cloud?.cancel();cloud=null;engine=Engine.LOCAL;cloudReady=false
        LoopApp.main.removeCallbacks(cloudPump);cloudPumpScheduled=false
        event(SpeechWire.PARTIAL,"") // Replace only the unconfirmed tail; confirmed text stays committed.
        // Sent, unconfirmed audio precedes the prefix/backlog which has not yet been sent.
        replay.takeTail().asReversed().forEach { pending.addFirst(it);pendingSamples+=it.size }
        startLocal("$reason · 切换本地识别…")
    }
    private fun startLocal(message: String) {
        localModelPath=localModelProvider() ?: run {
            fail("离线模型未下载或文件不完整 · 点击下载",SpeechWire.MODEL_REQUIRED);return
        }
        engine=Engine.LOCAL;engineSince=SystemClock.elapsedRealtime();localReady=false;wantsStart=true
        localSequence=0;outstanding=0;endSent=false
        if(finalizing)finishDeadline=engineSince+55000
        event(SpeechWire.READY,message)
        if(remote!=null)sendStart() else if(!bound) {
            bound=c.bindService(Intent(c,AsrService::class.java),connection,Context.BIND_AUTO_CREATE)
            if(!bound)fail("无法启动本地语音服务")
        }
    }
    private fun sendStart() {
        wantsStart=false
        if(!send(SpeechWire.START,Bundle().apply { putString("hotwords",hotwords);putString("model",localModelPath) }))fail("无法启动本地识别")
    }
    private fun send(kind: Int,bundle: Bundle=Bundle()): Boolean = try {
        remote?.send(Message.obtain(null,kind).apply { data=bundle.apply { putLong("epoch",epoch) };replyTo=reply })!=null
    } catch(_: RemoteException) { false }
    private fun acceptAudio(pcm: ShortArray) {
        if(!active) { pcm.fill(0);return }
        try {
            check(pendingSamples+replay.size+pcm.size<=16000*120) { "识别长时间未跟上，已停止以避免丢字" }
            pending.add(pcm);pendingSamples+=pcm.size;capturedSamples+=pcm.size
            if(engine==Engine.LOCAL)pumpLocal() else if(engine==Engine.CLOUD)scheduleCloudPump()
        } catch(e: Exception) { pcm.fill(0);fail(e.message ?: "语音处理失败") }
    }
    private fun scheduleCloudPump(delay: Long=0) {
        if(!active || engine!=Engine.CLOUD || !cloudReady || endSent || cloudPumpScheduled)return
        cloudPumpScheduled=true;LoopApp.main.postDelayed(cloudPump,delay)
    }
    private fun pumpCloud() {
        if(!active || engine!=Engine.CLOUD || !cloudReady || endSent)return
        val stream=cloud ?: return
        // Drain a cold-start prefix in bounded bursts, leaving room for UI work and socket writes.
        var budget=5120
        while(pending.isNotEmpty() && budget>0 && stream.queuedBytes<64000) {
            val pcm=pending.removeFirst();pendingSamples-=pcm.size;budget-=pcm.size
            replay.append(pcm)
            if(!stream.audio(pcm)) { fallback("云端网络传输中断");return }
            lastCloudSend=SystemClock.elapsedRealtime()
        }
        if(pending.isNotEmpty())scheduleCloudPump(20)
        else if(finalizing && captureEnded) {
            endSent=true;finishDeadline=SystemClock.elapsedRealtime()+15000;stream.finish()
        }
    }
    private fun pumpLocal() {
        if(!active || !localReady || engine!=Engine.LOCAL)return
        while(pending.isNotEmpty() && outstanding<8) {
            val pcm=pending.removeFirst();pendingSamples-=pcm.size
            val floats=FloatArray(pcm.size) { pcm[it]/32768f };pcm.fill(0)
            outstanding++
            if(!send(SpeechWire.AUDIO,Bundle().apply { putFloatArray("pcm",floats);putInt("seq",++localSequence) })) { fail("本地语音通信失败");return }
        }
        if(finalizing && captureEnded && pending.isEmpty() && outstanding==0 && !endSent) {
            endSent=true;finishDeadline=SystemClock.elapsedRealtime()+15000;if(!send(SpeechWire.END))fail("本地语音收尾失败")
        }
    }
    private fun beginCapture() {
        if(!active || finalizing || captureStarted)return
        if(c.checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED) { fail("麦克风权限已被撤回");return }
        val id=epoch
        try {
            val min=AudioRecord.getMinBufferSize(16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT)
            val ar=AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                .setAudioFormat(AudioFormat.Builder().setSampleRate(16000).setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(maxOf(min,16000)).build()
            check(ar.state==AudioRecord.STATE_INITIALIZED);recorder=ar
            ar.registerAudioRecordingCallback(c.mainExecutor,object: AudioManager.AudioRecordingCallback() {
                override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
                    if(id==epoch && configs.any { it.clientAudioSessionId==ar.audioSessionId && it.isClientSilenced })fail("麦克风被系统静音，录音已暂停")
                }
            })
            ar.startRecording();running.set(true);captureStarted=true;captureEnded=false;lastAck=SystemClock.elapsedRealtime()
            event(SpeechWire.READY,"正在听 · 识别准备中")
            val queued=AtomicInteger(0)
            Thread({
                Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
                val buf=ShortArray(1280)
                try {
                    while(running.get() && id==epoch) {
                        val n=ar.read(buf,0,buf.size,AudioRecord.READ_BLOCKING)
                        if(n<0 && running.get())error("音频读取失败")
                        if(n<=0)break
                        if(queued.incrementAndGet()>8)error("键盘处理繁忙，录音已暂停")
                        val pcm=buf.copyOf(n)
                        LoopApp.main.post {
                            queued.decrementAndGet()
                            if(id==epoch && active)acceptAudio(pcm) else pcm.fill(0)
                        }
                    }
                } catch(e: Exception) { LoopApp.main.post { if(id==epoch)fail(e.message ?: "录音失败") } }
                finally {
                    buf.fill(0);runCatching { ar.stop() };ar.release()
                    LoopApp.main.post {
                        // Compare/clear on main so an old reader cannot clear a newer session's recorder.
                        if(recorder===ar)recorder=null
                        if(id==epoch && active) {
                            captureEnded=true
                            if(finalizing)endAudio() else fail("麦克风已停止，请重新开始语音")
                        }
                    }
                }
            },"Loop-microphone").start()
        } catch(e: Exception) { recorder?.release();recorder=null;fail("麦克风启动失败：${e.javaClass.simpleName}") }
    }
    private fun endAudio() {
        if(capturedSamples==0L) { cancel();event(SpeechWire.DONE,"");return }
        if(engine==Engine.CLOUD)scheduleCloudPump() else if(engine==Engine.LOCAL)pumpLocal()
    }
    fun finish() {
        if(!active || finalizing)return
        finalizing=true
        if(!captureStarted) { cancel();event(SpeechWire.DONE,"");return }
        running.set(false);runCatching { recorder?.stop() };rotation.recover()
        // A released gesture still owns its already captured prefix; no callback may reopen the mic.
        finishDeadline=SystemClock.elapsedRealtime()+60000
        if(captureEnded)endAudio()
    }
    private fun complete() { active=false;cleanup();event(SpeechWire.DONE,"") }
    fun cancel() {
        wantsStart=false
        if(active && engine==Engine.LOCAL)send(SpeechWire.CANCEL)
        active=false;running.set(false);epoch++;finalizing=false;cleanup()
    }
    private fun cleanup() {
        running.set(false);runCatching { recorder?.stop() };rotation.recover();LoopApp.main.removeCallbacks(watchdog)
        LoopApp.main.removeCallbacks(cloudPump);cloudPumpScheduled=false;cloudReady=false
        cloud?.cancel();cloud=null;replay.clear();pending.forEach { it.fill(0) };pending.clear();pendingSamples=0
    }
    private fun fail(message: String,kind: Int=SpeechWire.ERROR) { if(!active)return;DiagnosticLog.event(DiagnosticLog.Area.SPEECH,DiagnosticLog.Step.FAILURE,"reason_code" to kind.toLong());cancel();event(kind,message) }
    fun destroy() { cancel();releaseBinding() }
}
