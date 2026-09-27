package app.loop.ime

import android.content.Context
import java.io.File
import java.util.concurrent.Executors
import javax.net.ssl.SSLException

internal object OfflineModels {
    data class Status(val busy: Boolean=false,val bytes: Long=0,val message: String="")
    @Volatile var status=Status();private set
    private var transfer: ModelDownload?=null
    private val worker=Executors.newSingleThreadExecutor { r -> Thread(r,"Loop-model-download").apply { priority=3 } }
    fun pack(c: Context)=OfflineModelPackage(File(c.noBackupFilesDir,"offline-models"))
    fun activePath(c: Context): String? {
        val custom=Prefs(c).text("custom_model")
        if(custom.isNotBlank() && OfflineModelCatalog.files.all { File(custom,it.name).let { f -> f.isFile && f.length()>0 } })return custom
        return pack(c).takeIf { it.installed() }?.directory?.absolutePath
    }
    @Synchronized fun download(c: Context) {
        if(status.busy)return
        val model=pack(c.applicationContext);val task=ModelDownload(model);transfer=task
        status=Status(true,model.cachedBytes(),"准备下载")
        worker.execute {
            var message="离线模型已就绪"
            try { task.run { bytes,phase -> status=Status(true,bytes,phase) } }
            catch(e: Exception) { message=when {
                e is SSLException -> "下载连接证书校验失败，请检查手机时间和网络"
                e.message?.contains("Canceled",true)==true || e is java.io.InterruptedIOException -> "下载已暂停或网络超时，点击继续下载"
                e is java.io.IOException -> if(listOf("模型校验失败","下载源暂不可用","下载源不支持","下载文件超出","无法安装模型").any { e.message?.startsWith(it)==true })e.message!! else "下载未完成，请检查网络后继续；已保存进度"
                else -> e.message ?: "下载未完成，请稍后重试"
            } }
            synchronized(this) { transfer=null;status=Status(false,if(model.installed())model.total else model.cachedBytes(),message) }
        }
    }
    @Synchronized fun pause() { transfer?.cancel() }
    @Synchronized fun remove(c: Context) {
        if(status.busy)return
        val model=pack(c.applicationContext);status=Status(true,0,"正在删除模型")
        worker.execute {
            val result=runCatching { model.remove() }
            synchronized(this) { status=Status(false,model.cachedBytes(),result.fold({ "离线模型已删除，需要时可重新下载" },{ "模型暂时无法删除，请稍后重试" })) }
        }
    }
}
