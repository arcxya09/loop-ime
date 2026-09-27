package app.loop.ime

import android.os.Handler
import android.os.Looper
import okhttp3.*
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

internal enum class AsrFailureKind { NETWORK, AUTH, SERVICE, PROTOCOL }
internal data class AsrFailure(val kind: AsrFailureKind,val message: String)
internal interface CloudAsrListener {
    fun ready()
    fun partial(text: String)
    fun final(text: String,endMs: Long)
    fun done()
    fun failed(error: AsrFailure)
}
internal interface CloudAsrStream {
    val queuedBytes: Long get()=0
    fun start()
    fun audio(pcm: ShortArray): Boolean
    fun finish()
    fun cancel()
}

internal object BailianProtocol {
    fun start(id: String,words: List<String>): String {
        val parameters=JSONObject().put("format","pcm").put("sample_rate",16000)
            .put("heartbeat",true).put("semantic_punctuation_enabled",false)
            .put("max_sentence_silence",900).put("multi_threshold_mode_enabled",true)
        val hints=JSONObject()
        words.mapNotNull(TextRules::cleanTerm).distinct().take(64).forEach { hints.put(it,3) }
        if(hints.length()>0)parameters.put("vocabulary",hints)
        return JSONObject().put("header",header("run-task",id))
            .put("payload",JSONObject().put("task_group","audio").put("task","asr").put("function","recognition")
                .put("model",CloudAsrProfile.MODEL).put("parameters",parameters).put("input",JSONObject())).toString()
    }
    fun finish(id: String)=JSONObject().put("header",header("finish-task",id)).put("payload",JSONObject().put("input",JSONObject())).toString()
    private fun header(action: String,id: String)=JSONObject().put("action",action).put("task_id",id).put("streaming","duplex")
    fun pcm16(samples: ShortArray): ByteArray=ByteArray(samples.size*2).also { out ->
        samples.forEachIndexed { i,s -> out[2*i]=(s.toInt() and 255).toByte();out[2*i+1]=(s.toInt() shr 8).toByte() }
    }
    fun httpError(code: Int)=when(code) {
        401 -> AsrFailure(AsrFailureKind.AUTH,"百炼 Key 无效，请在语音设置中检查 Key 和地域")
        403 -> AsrFailure(AsrFailureKind.AUTH,"百炼拒绝访问，请检查地域与模型权限")
        429 -> AsrFailure(AsrFailureKind.SERVICE,"百炼请求过多或额度不足，请稍后重试")
        else -> AsrFailure(AsrFailureKind.SERVICE,"百炼连接失败（HTTP $code），请检查模型服务")
    }
    fun taskError(code: String,detail: String): AsrFailure=when {
        code.contains("AUTH",true) || code.contains("API_KEY",true) -> AsrFailure(AsrFailureKind.AUTH,"百炼鉴权失败，请检查 Key、地域与模型权限")
        code.contains("TIMEOUT",true) || code.contains("NETWORK",true) || (code=="CLIENT_ERROR" && detail.contains("timeout",true)) -> AsrFailure(AsrFailureKind.NETWORK,"云端连接超时")
        code.contains("LIMIT",true) || code.contains("QUOTA",true) || code.contains("BALANCE",true) -> AsrFailure(AsrFailureKind.SERVICE,"百炼额度不足或请求受限，请检查账号")
        else -> AsrFailure(AsrFailureKind.SERVICE,"百炼识别任务失败，请检查账号额度和模型权限")
    }
}

/** All state and application callbacks are confined to the main looper. OkHttp owns socket IO. */
internal class BailianAsr(private val profile: CloudAsrProfile,private val words: List<String>,private val listener: CloudAsrListener,
    private val sockets: WebSocket.Factory=sharedClient) : CloudAsrStream {
    private val main=Handler(Looper.getMainLooper())
    private val taskId=UUID.randomUUID().toString()
    private var socket: WebSocket?=null
    private var closed=false
    private var started=false
    private var finishing=false
    private var lastFinal=0
    override val queuedBytes: Long get()=socket?.queueSize() ?: 0
    private val timeout=Runnable { fail(AsrFailure(AsrFailureKind.NETWORK,if(finishing)"云端收尾超时" else "云端连接超时")) }
    override fun start() {
        check(socket==null && !closed)
        val request=Request.Builder().url(profile.endpoint).header("Authorization","Bearer ${profile.key}").header("User-Agent","LoopIME/0.1.8").build()
        main.postDelayed(timeout,10000)
        socket=sockets.newWebSocket(request,object: WebSocketListener() {
            override fun onOpen(ws: WebSocket,response: Response) { main.post {
                if(closed)ws.cancel() else if(!ws.send(BailianProtocol.start(taskId,words)))fail(AsrFailure(AsrFailureKind.NETWORK,"云端连接中断"))
            } }
            override fun onMessage(ws: WebSocket,text: String) { main.post { if(!closed)receive(text) } }
            override fun onFailure(ws: WebSocket,t: Throwable,response: Response?) { main.post {
                if(closed)return@post
                fail(when {
                    response!=null -> BailianProtocol.httpError(response.code)
                    t is SSLException -> AsrFailure(AsrFailureKind.SERVICE,"百炼连接的安全证书校验失败，请检查手机时间和网络")
                    t is IOException -> AsrFailure(AsrFailureKind.NETWORK,"云端网络连接中断")
                    else -> AsrFailure(AsrFailureKind.PROTOCOL,"云端连接异常")
                })
            } }
            override fun onClosing(ws: WebSocket,code: Int,reason: String) { main.post {
                if(!closed)fail(AsrFailure(if(code==1008)AsrFailureKind.SERVICE else AsrFailureKind.NETWORK,"云端识别连接已关闭"))
            } }
        })
    }
    private fun receive(text: String) {
        try {
            require(text.length<=262144)
            val j=JSONObject(text);val header=j.getJSONObject("header")
            if(header.optString("task_id")!=taskId)return
            when(header.getString("event")) {
                "task-started" -> if(!started) { started=true;main.removeCallbacks(timeout);listener.ready() }
                "result-generated" -> {
                    check(started)
                    val sentence=j.getJSONObject("payload").getJSONObject("output").getJSONObject("sentence")
                    if(sentence.optBoolean("heartbeat"))return
                    val id=sentence.getInt("sentence_id");if(id<=lastFinal)return
                    val value=sentence.getString("text").trim();require(value.length<=16000)
                    if(sentence.getBoolean("sentence_end")) {
                        val end=sentence.getLong("end_time");require(end>=0);lastFinal=id;listener.final(value,end)
                    } else listener.partial(value)
                }
                "task-finished" -> {
                    check(finishing);closed=true;main.removeCallbacks(timeout);socket?.close(1000,null);listener.done()
                }
                "task-failed" -> fail(BailianProtocol.taskError(header.optString("error_code"),header.optString("error_message")))
            }
        } catch(_: Exception) { fail(AsrFailure(AsrFailureKind.PROTOCOL,"百炼返回了无法处理的识别结果")) }
    }
    override fun audio(pcm: ShortArray): Boolean {
        if(closed || !started || finishing)return false
        val ws=socket ?: return false
        // Bound unsent audio to 4 seconds. The controller retains the unconfirmed tail for offline recovery.
        if(ws.queueSize()>128000)return false
        return ws.send(BailianProtocol.pcm16(pcm).toByteString())
    }
    override fun finish() {
        if(closed || finishing)return
        if(!started) { cancel();listener.done();return }
        finishing=true;main.removeCallbacks(timeout);main.postDelayed(timeout,12000)
        if(socket?.send(BailianProtocol.finish(taskId))!=true)fail(AsrFailure(AsrFailureKind.NETWORK,"云端连接中断"))
    }
    private fun fail(error: AsrFailure) { if(closed)return;cancel();listener.failed(error) }
    override fun cancel() { closed=true;main.removeCallbacks(timeout);socket?.cancel();socket=null }
    companion object {
        private val sharedClient=OkHttpClient.Builder().connectTimeout(8,TimeUnit.SECONDS).writeTimeout(8,TimeUnit.SECONDS)
            .readTimeout(0,TimeUnit.MILLISECONDS).pingInterval(5,TimeUnit.SECONDS).retryOnConnectionFailure(false)
            .followRedirects(false).followSslRedirects(false).build()
    }
}
