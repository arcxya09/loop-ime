package app.loop.ime

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

object RimeNative {
    init { System.loadLibrary("loop_rime") }
    external fun init(shared: String, user: String): Boolean
    external fun event(key: Int, kind: Int): ByteArray
}
data class RimeState(val raw: String="", val preedit: String="", val commit: String="", val candidates: List<String> = emptyList(), val caret: Int=0, val selStart: Int=0,val error: String?=null,val hasMore: Boolean=false,val reading: String="") {
    fun keyboardComposition(nine: Boolean): String {
        if(raw.isEmpty())return ""
        val input=preedit.ifBlank { raw }
        // A phone code is ambiguous: show Rime's first-candidate reading alongside the actual code.
        return if(nine && reading.isNotBlank())"$reading · $input" else input
    }
    fun editorComposition(nine: Boolean)=if(nine || raw.isEmpty())"" else preedit.ifBlank { raw }
    fun editorCommit(nine: Boolean)=if(nine && commit.matches(Regex("[0-9' ]+")))"" else commit
    companion object {
        fun from(bytes: ByteArray): RimeState {
            val j=JSONObject(bytes.toString(Charsets.UTF_8));val arr=j.optJSONArray("candidates")
            return RimeState(j.optString("raw"),j.optString("preedit"),j.optString("commit"),if(arr==null)emptyList() else (0 until arr.length()).map { arr.getString(it) },j.optInt("caret"),j.optInt("selStart"),hasMore=j.optBoolean("hasMore",false),reading=j.optString("reading"))
        }
    }
}
class RimeEngine(private val c: Context,private val nativeEvent: (Int,Int)->ByteArray = { key,kind -> RimeNative.event(key,kind) }) {
    @Volatile var ready=false; private set
    @Volatile private var preparing=false
    @Volatile private var nineKey=true
    fun prepare(callback: (String?)->Unit) {
        synchronized(this) { if(ready) { LoopApp.main.post { callback(null) };return };if(preparing)return;preparing=true }
        serial.execute {
            val error=runCatching {
                // Separate compiled dictionary caches; preserve all earlier Rime and personal data.
                val shared=File(c.noBackupFilesDir,"rime/frost-v1/shared");val user=File(c.noBackupFilesDir,"rime/frost-v1/user");user.mkdirs()
                val marker=File(shared,".loop-frost-v2")
                if(!marker.exists()) {
                    copy("rime",shared)
                    File(shared,"default.custom.yaml").writeText("patch:\n  schema_list:\n    - schema: loop_t9\n    - schema: luna_pinyin_simp\n  menu/page_size: 9\n")
                    File(shared,"luna_pinyin_simp.custom.yaml").writeText("patch:\n  translator/enable_user_dict: false\n  translator/enable_sentence: true\n  switches/@0/reset: 0\n")
                }
                check(RimeNative.init(shared.path,user.path)) { "拼音字典部署失败" }
                nativeEvent(if(nineKey)1 else 0,4);marker.writeText("2");ready=true
            }.exceptionOrNull()?.let { "拼音加载失败：${it.javaClass.simpleName}" }
            preparing=false
            LoopApp.main.post { callback(error) }
        }
    }
    private fun copy(asset: String, target: File) {
        val names=c.assets.list(asset) ?: emptyArray()
        if(names.isEmpty()) { target.parentFile?.mkdirs();c.assets.open(asset).use { input -> target.outputStream().use { input.copyTo(it) } } }
        else { target.mkdirs();names.forEach { copy("$asset/$it",File(target,it)) } }
    }
    fun event(key: Int, kind: Int=0, callback: (RimeState)->Unit) {
        serial.execute {
            val state=runCatching { check(ready);RimeState.from(nativeEvent(key,kind)) }.getOrElse { RimeState(error="拼音引擎暂不可用，请重试") }
            LoopApp.main.post { callback(state) }
        }
    }
    fun clear() { serial.execute { if(ready) nativeEvent(0,2) } }
    fun layout(nine: Boolean, callback: (RimeState)->Unit = {}) {
        nineKey=nine
        serial.execute {
            val state=runCatching { if(ready)RimeState.from(nativeEvent(if(nine)1 else 0,4)) else RimeState() }.getOrElse { RimeState(error="拼音布局切换失败，请重试") }
            LoopApp.main.post { callback(state) }
        }
    }
    companion object { private val serial=Executors.newSingleThreadExecutor { Thread(it,"Loop-Rime") } }
}
