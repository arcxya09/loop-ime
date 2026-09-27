package app.loop.ime

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import org.json.JSONObject
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

class CloudAsrProfile(val key: String,val region: String="beijing") {
    init {
        require(region in REGIONS) { "不支持的百炼地域" }
        require(key.isNotBlank() && key.length<=4096 && key.all { it.code in 33..126 }) { "请填写有效的百炼 API Key" }
    }
    val endpoint get()=if(region=="singapore")"wss://dashscope-intl.aliyuncs.com/api-ws/v1/inference" else "wss://dashscope.aliyuncs.com/api-ws/v1/inference"
    override fun toString()="CloudAsrProfile(region=$region, key=<redacted>)"
    companion object {
        const val MODEL="qwen-audio-3.0-asr-flash-streaming"
        val REGIONS=listOf("beijing","singapore")
    }
}

/** Separate encrypted records per provider and region; never reuses a text-AI key. */
class CloudSpeechSettings(private val c: Context,private val vault: Vault=Vault(c)) {
    fun region()=Prefs(c).text("speech_region","beijing").takeIf { it in CloudAsrProfile.REGIONS } ?: "beijing"
    fun profile(region: String=region()): CloudAsrProfile? {
        require(region in CloudAsrProfile.REGIONS)
        val bytes=vault.get("speech.bailian.$region") ?: return null
        return try { CloudAsrProfile(JSONObject(bytes.toString(Charsets.UTF_8)).getString("key"),region) } finally { bytes.fill(0) }
    }
    fun save(key: String,region: String): CloudAsrProfile {
        val value=key.trim()
        val profile=if(value.isEmpty())profile(region) ?: error("请先填写百炼 API Key") else CloudAsrProfile(value,region)
        val bytes=JSONObject().put("key",profile.key).toString().toByteArray(Charsets.UTF_8)
        try { vault.put("speech.bailian.$region",bytes) } finally { bytes.fill(0) }
        check(Prefs(c).store.edit().putString("speech_region",region).putBoolean("speech_cloud",true).commit()) { "语音设置保存失败，请重试" }
        return profile
    }
    companion object {
        // Independent of dictionary/database work: loading a speech credential cannot queue behind learning.
        val io=ThreadPoolExecutor(1,1,0L,TimeUnit.MILLISECONDS,ArrayBlockingQueue(8),
            { Thread(it,"Loop-speech-settings").apply { isDaemon=true;priority=3 } },ThreadPoolExecutor.AbortPolicy())
        // A pending/blocked Android connectivity probe does not prove that the ASR endpoint is offline.
        internal fun usableNetwork(caps: NetworkCapabilities?): Boolean =
            caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)==true && !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)
        fun online(c: Context): Boolean = runCatching {
            val cm=c.getSystemService(ConnectivityManager::class.java)
            val caps=cm.getNetworkCapabilities(cm.activeNetwork)
            usableNetwork(caps)
        }.getOrDefault(false)
    }
}
