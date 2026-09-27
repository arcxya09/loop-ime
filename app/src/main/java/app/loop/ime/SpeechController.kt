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

/** One microphone session; cloud recognition consumes its ordered PCM stream. State lives on main. */
class SpeechController(private val c: Context,private val event: (Int,String)->Unit) {
    private enum class Engine { CONFIG, CLOUD }
    private var engine=Engine.CONFIG
    @Volatile private var epoch=0L
    private val running=AtomicBoolean(false)
    @Volatile private var recorder: AudioRecord?=null
    private var finalizing=false
    private var active=false
    private var captureStarted=false
    private var captureEnded=false
    private var cloudReady=false
    private var cloudPumpScheduled=false
    private var lastCloudSend=0L
    private var sentSamples=0L
    private var confirmedSamples=0L
    private fun cloudPermitted()=LoopApp.unlocked(c) && !Prefs(c).privateMode && Prefs(c).flag("speech_cloud")
    private var capturedSamples=0L
    private var endSent=false
    private var engineSince=0L
    private var finishDeadline=0L
    private val pending=ArrayDeque<ShortArray>()
    private var pendingSamples=0
    private val rotation=RotationLock(c)
    private var cloud: CloudAsrStream?=null
    internal var cloudFactory: (CloudAsrProfile,List<String>,CloudAsrListener)->CloudAsrStream={ p,w,l -> BailianAsr(p,w,l) }
    internal var cloudProfileProvider: ()->CloudAsrProfile?={ CloudSpeechSettings(c).profile() }
    internal var online: ()->Boolean={ CloudSpeechSettings.online(c) }
    val recognitionLabel get()="百炼云端"
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
                !cloudPermitted() -> fail("语音授权已关闭，录音已停止")
                !online() -> fail("网络已断开，录音已停止；请联网后重试")
                engine==Engine.CLOUD && cloudReady && pending.isNotEmpty() && now-lastCloudSend>8000 -> fail("云端音频发送超时，录音已停止")
                engine==Engine.CONFIG && now-engineSince>10000 -> fail("读取百炼配置超时，请重新开始")
                finalizing && now>finishDeadline -> fail("收尾超时，已保留稳定片段")
            }
            if(active)LoopApp.main.postDelayed(this,1000)
        }
    }
    fun start(cloudWords: List<String> = emptyList(),allowCloud: Boolean=true,vocabulary: (() -> List<String>)?=null) {
        if(active)return
        if(!allowCloud || !LoopApp.unlocked(c) || Prefs(c).privateMode) { event(SpeechWire.ERROR,"隐私输入不启用云端语音");return }
        if(!Prefs(c).flag("speech_cloud")) { event(SpeechWire.ERROR,"请先在语音设置中保存百炼 Key 并启用云端语音");return }
        if(!online()) { event(SpeechWire.ERROR,"当前无网络，语音输入需要联网");return }
        if(c.checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED) { event(SpeechWire.ERROR,"请先在 Loop 设置中允许麦克风");return }
        epoch++;active=true;finalizing=false;captureStarted=false;captureEnded=false;endSent=false
        capturedSamples=0;sentSamples=0;confirmedSamples=0;cloudReady=false;engine=Engine.CONFIG;engineSince=SystemClock.elapsedRealtime()
        // Capture first so asynchronous credential lookup and the handshake keep opening words.
        beginCapture()
        if(!active)return
        if(Prefs(c).flag("rotation") && !runCatching { rotation.acquire() }.getOrDefault(false))event(SpeechWire.READY,"正在准备语音（旋转锁未获授权）")
        event(SpeechWire.READY,"正在听 · 连接百炼中")
        val id=epoch
        try { CloudSpeechSettings.io.execute {
            val hintsRevision=StoreEvents.revision
            val hints=runCatching { vocabulary?.invoke() ?: cloudWords }.getOrDefault(emptyList())
            val result=runCatching { if(cloudPermitted())cloudProfileProvider() else null }
            LoopApp.main.post {
                if(id==epoch && active && engine==Engine.CONFIG) {
                    if(!cloudPermitted())fail("语音授权已关闭，录音已停止")
                    else if(!online())fail("网络已断开，录音已停止；请联网后重试")
                    else result.fold({ p -> if(p==null)fail("请先在语音设置中保存百炼 Key") else startCloud(p,if(hintsRevision==StoreEvents.revision)hints else emptyList()) },
                        { fail("百炼 Key 无法读取，请解锁手机或到语音设置重新保存") })
                }
            }
        } } catch(_: RejectedExecutionException) { fail("语音配置暂时繁忙，请稍后重试") }
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
                try { require(endMs>=0 && endMs<=sentSamples/16+1000);confirmedSamples=maxOf(confirmedSamples,minOf(sentSamples,endMs*16)) } catch(_: Exception) { fail("云端音频时间戳异常，录音已停止");return }
                if(text.isNotBlank())event(SpeechWire.FINAL,text) else event(SpeechWire.PARTIAL,"")
            }
            override fun done() { if(current()) { if(endSent)complete() else fail("云端识别提前结束") } }
            override fun failed(error: AsrFailure) {
                if(!current())return
                fail(error.message)
            }
        }
        try { cloud=cloudFactory(profile,words,listener);cloud!!.start() }
        catch(_: Exception) { fail("无法启动百炼识别，请检查语音设置") }
    }
    private fun acceptAudio(pcm: ShortArray) {
        if(!active) { pcm.fill(0);return }
        try {
            check(pendingSamples.toLong()+sentSamples-confirmedSamples+pcm.size<=16000*120) { "识别长时间未跟上，已停止以避免丢字" }
            pending.add(pcm);pendingSamples+=pcm.size;capturedSamples+=pcm.size
            if(engine==Engine.CLOUD)scheduleCloudPump()
        } catch(e: Exception) { pcm.fill(0);fail(e.message ?: "语音处理失败") }
    }
    private fun scheduleCloudPump(delay: Long=0) {
        if(!active || engine!=Engine.CLOUD || !cloudReady || endSent || cloudPumpScheduled)return
        cloudPumpScheduled=true;LoopApp.main.postDelayed(cloudPump,delay)
    }
    private fun pumpCloud() {
        if(!active || engine!=Engine.CLOUD || !cloudReady || endSent)return
        if(!cloudPermitted()) { fail("语音授权已关闭，录音已停止");return }
        val stream=cloud ?: return
        // Drain a cold-start prefix in bounded bursts, leaving room for UI work and socket writes.
        var budget=5120
        while(pending.isNotEmpty() && budget>0 && stream.queuedBytes<64000) {
            val pcm=pending.removeFirst();pendingSamples-=pcm.size;budget-=pcm.size
            sentSamples+=pcm.size
            val sent=try { stream.audio(pcm) } finally { pcm.fill(0) }
            if(!sent) { fail("云端网络传输中断，录音已停止");return }
            lastCloudSend=SystemClock.elapsedRealtime()
        }
        if(pending.isNotEmpty())scheduleCloudPump(20)
        else if(finalizing && captureEnded) {
            endSent=true;finishDeadline=SystemClock.elapsedRealtime()+15000;stream.finish()
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
            ar.startRecording();running.set(true);captureStarted=true;captureEnded=false
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
        if(engine==Engine.CLOUD)scheduleCloudPump()
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
        active=false;running.set(false);epoch++;finalizing=false;cleanup()
    }
    private fun cleanup() {
        running.set(false);runCatching { recorder?.stop() };rotation.recover();LoopApp.main.removeCallbacks(watchdog)
        LoopApp.main.removeCallbacks(cloudPump);cloudPumpScheduled=false;cloudReady=false
        cloud?.cancel();cloud=null;sentSamples=0;confirmedSamples=0;pending.forEach { it.fill(0) };pending.clear();pendingSamples=0
    }
    private fun fail(message: String,kind: Int=SpeechWire.ERROR) { if(!active)return;DiagnosticLog.event(DiagnosticLog.Area.SPEECH,DiagnosticLog.Step.FAILURE,"reason_code" to kind.toLong());cancel();event(kind,message) }
    fun destroy() { cancel() }
}
