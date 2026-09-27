package app.loop.ime

import android.app.Service
import android.content.Intent
import android.os.*
import com.k2fsa.sherpa.onnx.*

object SpeechWire { const val START=1;const val AUDIO=2;const val END=3;const val CANCEL=4;const val READY=10;const val ACK=11;const val PARTIAL=12;const val FINAL=13;const val DONE=14;const val ERROR=15;const val MODEL_REQUIRED=16 }

/** Model lifetime and stream lifetime are both confined to this worker looper. */
class AsrService : Service() {
    private lateinit var thread: HandlerThread
    private lateinit var handler: Handler
    private lateinit var messenger: Messenger
    private var recognizer: OnlineRecognizer?=null
    private var stream: OnlineStream?=null
    private var client: Messenger?=null
    private var epoch=0L
    private var lastSequence=0
    private var lastText=""
    private var hotwords=""
    private var loadedModel=""
    override fun onCreate() {
        super.onCreate();thread=HandlerThread("Loop-ASR",Process.THREAD_PRIORITY_MORE_FAVORABLE);thread.start()
        handler=object: Handler(thread.looper) {
            override fun handleMessage(msg: Message) {
                try { handle(msg) } catch(t: Throwable) { send(SpeechWire.ERROR,"本地语音引擎异常：${t.javaClass.simpleName}");releaseStream() }
            }
        };messenger=Messenger(handler)
    }
    override fun onBind(intent: Intent): IBinder = messenger.binder
    private fun send(kind: Int, text: String="", seq: Int=0) {
        try { client?.send(Message.obtain(null,kind).apply { data=Bundle().apply { putLong("epoch",epoch);putString("text",text);putInt("seq",seq) } }) }
        catch(_: RemoteException) { releaseStream() }
    }
    private fun handle(msg: Message) {
        val b=msg.data
        if(msg.what==SpeechWire.START) {
            releaseStream();client=msg.replyTo;epoch=b.getLong("epoch");lastSequence=0
            hotwords=b.getString("hotwords","").take(4096)
            val custom=b.getString("model","") ?: ""
            if(custom.isBlank() || !OfflineModelCatalog.files.all { java.io.File(custom,it.name).isFile }) {
                send(SpeechWire.MODEL_REQUIRED,"离线模型未下载或文件不完整 · 点击下载");return
            }
            if(recognizer!=null && custom!=loadedModel) { recognizer?.release();recognizer=null }
            if(recognizer==null) {
                val downloaded=OfflineModels.pack(this)
                if(custom==downloaded.directory.absolutePath && (!downloaded.installed() || !downloaded.verifyDirectory(downloaded.directory))) {
                    java.io.File(downloaded.directory,"ready").delete()
                    send(SpeechWire.MODEL_REQUIRED,"离线模型校验失败 · 点击重新下载");return
                }
                val path=custom
                val conf=OnlineRecognizerConfig(
                    modelConfig=OnlineModelConfig(transducer=OnlineTransducerModelConfig(encoder="$path/encoder.onnx",decoder="$path/decoder.onnx",joiner="$path/joiner.onnx"),tokens="$path/tokens.txt",numThreads=2,provider="cpu",modelType="zipformer",modelingUnit="cjkchar+bpe",bpeVocab="$path/bpe.vocab"),
                    endpointConfig=EndpointConfig(EndpointRule(false,2.4f,0f),EndpointRule(true,0.65f,0f),EndpointRule(false,0f,10f)),
                    enableEndpoint=true,decodingMethod="modified_beam_search",maxActivePaths=4,hotwordsScore=1.5f)
                recognizer=OnlineRecognizer(null,conf)
                loadedModel=custom
            }
            stream=recognizer!!.createStream(hotwords);lastText="";send(SpeechWire.READY);return
        }
        if(b.getLong("epoch")!=epoch)return
        val r=recognizer ?: return
        val s=stream ?: return
        when(msg.what) {
            SpeechWire.AUDIO -> {
                val seq=b.getInt("seq");require(seq==lastSequence+1) { "音频序列中断" };lastSequence=seq
                val pcm=b.getFloatArray("pcm") ?: return;require(pcm.size<=1600)
                s.acceptWaveform(pcm,16000)
                while(r.isReady(s))r.decode(s)
                val text=r.getResult(s).text.trim()
                if(r.isEndpoint(s)) { if(text.isNotBlank())send(SpeechWire.FINAL,text);r.reset(s);lastText="" }
                else if(text!=lastText) { lastText=text;send(SpeechWire.PARTIAL,text) }
                send(SpeechWire.ACK,seq=seq)
            }
            SpeechWire.END -> {
                s.acceptWaveform(FloatArray(8000),16000);s.inputFinished()
                while(r.isReady(s))r.decode(s)
                val text=r.getResult(s).text.trim();if(text.isNotBlank())send(SpeechWire.FINAL,text)
                releaseStream();send(SpeechWire.DONE)
            }
            SpeechWire.CANCEL -> { releaseStream();send(SpeechWire.DONE) }
        }
    }
    private fun releaseStream() { stream?.release();stream=null;lastText="" }
    override fun onUnbind(intent: Intent): Boolean { handler.post { releaseStream();recognizer?.release();recognizer=null;stopSelf() };return false }
    override fun onDestroy() { handler.post { releaseStream();recognizer?.release();recognizer=null;thread.quitSafely() };super.onDestroy() }
}
