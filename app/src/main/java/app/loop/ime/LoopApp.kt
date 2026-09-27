package app.loop.ime

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.UserManager
import java.util.concurrent.Executors
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException

class LoopApp : Application() {
    override fun onCreate() {
        super.onCreate()
        runCatching { DiagnosticLog.attach(this,installCrashHandler=true) }
        if (!getProcessName().endsWith(":asr") && unlocked(this)) {
            RotationLock(this).recover()
            DraftWriter.get(this).recover()
        }
    }
    companion object {
        @Volatile var keyboardVisible=false
        val main = Handler(Looper.getMainLooper())
        val io = ThreadPoolExecutor(1,1,0L,TimeUnit.MILLISECONDS,ArrayBlockingQueue(256),
            { Thread(it, "Loop-storage").apply { priority = 3 } },ThreadPoolExecutor.AbortPolicy())
        val files = Executors.newSingleThreadExecutor { Thread(it,"Loop-file-operations").apply { priority=2 } }
        fun unlocked(c: Context) = c.getSystemService(UserManager::class.java).isUserUnlocked
        fun background(c: Context, action: () -> Unit, done: ((String?) -> Unit)? = null) {
            try { io.execute {
                val error = try { action(); null } catch (e: Exception) { DiagnosticLog.failure(DiagnosticLog.Area.STORAGE,e);e.javaClass.simpleName + ": " + (e.message ?: "操作失败").take(100) }
                if (done != null) main.post { done(error) }
            } } catch(e: RejectedExecutionException) { DiagnosticLog.failure(DiagnosticLog.Area.STORAGE,e);if(done!=null)main.post { done("本地数据队列已满，本次操作未保存") } }
        }
    }
}

class Prefs(c: Context) {
    val store = c.getSharedPreferences("loop-preferences", Context.MODE_PRIVATE)
    fun flag(k: String, default: Boolean = false) = store.getBoolean(k, default)
    fun set(k: String, value: Boolean) { store.edit().putBoolean(k, value).apply() }
    fun text(k: String, default: String = "") = store.getString(k, default) ?: default
    fun set(k: String, value: String) { store.edit().putString(k, value).apply() }
    val privateMode get() = flag("private")
    val cloud get() = flag("cloud") && !privateMode
    val memory get() = flag("memory") && !privateMode
    val learning get() = flag("learning", true) && !privateMode
    var correctionMode: CorrectionMode
        get() = CorrectionMode.entries.firstOrNull { it.name==text("correction_mode") }
            ?: if(flag("autocorrect",true))CorrectionMode.CONSERVATIVE else CorrectionMode.SUGGEST
        set(value) { set("correction_mode",value.name);set("autocorrect",value==CorrectionMode.CONSERVATIVE) }
    fun localApp(packageName: String)=packageName.isNotBlank() && packageName in text("local_apps").split('\n')
    fun setLocalApp(packageName: String, local: Boolean) {
        if(packageName.isBlank())return
        val apps=text("local_apps").split('\n').filter { it.isNotBlank() }.toMutableSet()
        if(local)apps.add(packageName) else apps.remove(packageName)
        set("local_apps",apps.sorted().joinToString("\n"))
    }
    var keyboardHeight: KeyboardHeight
        get() = KeyboardHeight.from(text("keyboard_height"))
        set(value) { set("keyboard_height",value.value) }
}

enum class CorrectionMode(val label: String) {
    OFF("关闭纠错"), SUGGEST("只显示建议"), CONSERVATIVE("保守自动纠错")
}
