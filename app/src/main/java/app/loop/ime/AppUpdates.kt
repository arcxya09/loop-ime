package app.loop.ime

import android.Manifest
import android.app.*
import android.app.job.*
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

object AppUpdates {
    internal const val PERIODIC_JOB=106
    internal const val FINISH_JOB=107
    private const val DAY=24*60*60*1000L
    private const val NOTICE=106
    private val executor=Executors.newSingleThreadExecutor { Thread(it,"Loop-updates").apply { priority=2 } }
    private val checking=AtomicBoolean(false)
    private val refreshing=AtomicBoolean(false)
    private fun store(c: Context)=c.getSharedPreferences("loop-updates",Context.MODE_PRIVATE)
    fun enabled(c: Context)=store(c).getBoolean("automatic",true)
    fun autoDownload(c: Context)=store(c).getBoolean("download",true)
    fun previews(c: Context)=store(c).getBoolean("previews",installed(c).versionName.orEmpty().contains('-'))
    private fun installed(c: Context)=c.packageManager.getPackageInfo(c.packageName,PackageManager.GET_SIGNING_CERTIFICATES)
    fun current(c: Context)=installed(c).versionName.orEmpty()
    fun available(c: Context)=runCatching { UpdateRelease.restore(store(c).getString("release","").orEmpty()) }.getOrNull()
    fun status(c: Context)=store(c).getString("status","尚未检查更新").orEmpty()
    fun ready(c: Context)=store(c).getBoolean("ready",false) && File(c.filesDir,"updates/verified.apk").isFile
    private fun status(c: Context,text: String) { store(c).edit().putString("status",text).apply() }
    fun configure(c: Context,key: String,value: Boolean) {
        store(c).edit().putBoolean(key,value).apply()
        if(key=="previews")store(c).edit().remove("attempt").putBoolean("check_ok",false).apply()
        schedule(c)
        executor.execute {
            // Reconcile the latest preferences, not the value captured by an old UI callback.
            if(!enabled(c) || !autoDownload(c) || (!previews(c) && available(c)?.prerelease==true)) {
                cancelDownload(c)
                if(!ready(c))status(c,"自动下载已停止，可手动检查或下载")
            }
            if(!previews(c) && available(c)?.prerelease==true)clear(c)
            if(enabled(c) && autoDownload(c))runCatching { available(c)?.let { enqueue(c,it,false) } }
                .onFailure { status(c,"无法开始自动下载，可手动重试") }
            if(enabled(c) && key=="previews")check(c)
        }
    }
    fun schedule(c: Context) {
        val scheduler=c.getSystemService(JobScheduler::class.java)
        if(!enabled(c)) { scheduler.cancel(PERIODIC_JOB);return }
        if(scheduler.getPendingJob(PERIODIC_JOB)==null) scheduler.schedule(JobInfo.Builder(PERIODIC_JOB,ComponentName(c,UpdateJob::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPeriodic(DAY).setPersisted(true).build())
    }
    fun check(context: Context, manual: Boolean=false, stopped: AtomicBoolean=AtomicBoolean(false), done: ()->Unit={}) {
        val c=context.applicationContext
        if(!checking.compareAndSet(false,true)) { executor.execute { LoopApp.main.post { done() } };return }
        executor.execute {
            try {
                reconcile(c)
                val last=store(c).getLong("attempt",0);val now=System.currentTimeMillis()
                val interval=if(store(c).getBoolean("check_ok",false))DAY else 60*60*1000L
                if(!manual && (!enabled(c) || (now>=last && now-last<interval)))return@execute
                if(stopped.get())return@execute
                store(c).edit().putLong("attempt",now).putBoolean("check_ok",false).apply()
                status(c,"正在检查 GitHub Release…")
                val http=UpdateHttp();val deadline=SystemClock.elapsedRealtime()+90_000
                fun fetch(url: String,limit: Long): ByteArray {
                    if(stopped.get() || SystemClock.elapsedRealtime()>deadline)throw IOException("更新检查已暂停，请重试")
                    return http.get(url,limit)
                }
                val preview=previews(c)
                val r=UpdateRelease.select(fetch(UpdateRelease.API,2*1024*1024).toString(Charsets.UTF_8),installed(c).longVersionCode,preview) { fetch(it,128*1024) }
                if(stopped.get() || (!manual && !enabled(c)) || preview!=previews(c))return@execute
                store(c).edit().putBoolean("check_ok",true).putLong("checked",now).apply()
                if(r==null) { clear(c);status(c,"已是所选更新通道的最新版本") }
                else {
                    if(available(c)?.sha256!=r.sha256)clear(c)
                    store(c).edit().putString("release",r.json()).apply()
                    if(!ready(c) && store(c).getLong("download_id",-1)<0)status(c,"发现新版本 ${r.version}")
                    if(enabled(c) && autoDownload(c))enqueue(c,r,false)
                }
            } catch(e: Exception) { status(c,"更新检查未完成：${safeError(e)}。可手动重试。") }
            finally { checking.set(false);LoopApp.main.post { done() } }
        }
    }
    fun refresh(context: Context, done: (()->Unit)?=null) {
        if(!refreshing.compareAndSet(false,true)) {
            // A JobService caller must stay alive until the existing reconciliation finishes.
            if(done!=null)executor.execute { LoopApp.main.post { done() } }
            return
        }
        val c=context.applicationContext
        executor.execute { try { reconcile(c) } catch(_: Exception) { status(c,"下载状态暂时不可用，请重试") } finally { refreshing.set(false);if(done!=null)LoopApp.main.post { done() } } }
    }
    fun download(context: Context, cellular: Boolean) {
        val c=context.applicationContext
        executor.execute { try { available(c)?.let { enqueue(c,it,cellular) } } catch(e: Exception) { status(c,"无法开始下载：${safeError(e)}") } }
    }
    fun discard(context: Context) {
        val c=context.applicationContext
        executor.execute { clear(c);status(c,"已取消下载并清理安装包，可重新检查更新") }
    }
    private fun manager(c: Context)=c.getSystemService(DownloadManager::class.java)
    private fun cancelDownload(c: Context) {
        val id=store(c).getLong("download_id",-1)
        if(id>=0)manager(c).remove(id)
        store(c).edit().remove("download_id").apply()
    }
    private fun clear(c: Context) {
        cancelDownload(c)
        File(c.filesDir,"updates/verified.apk").delete()
        File(c.filesDir,"updates/staging.apk").delete()
        c.getSystemService(NotificationManager::class.java).cancel(NOTICE)
        store(c).edit().remove("release").putBoolean("ready",false).apply()
    }
    internal fun enqueue(c: Context,r: UpdateRelease,cellular: Boolean) {
        if(r.code<=installed(c).longVersionCode || ready(c))return
        if(store(c).getLong("download_id",-1)>=0) {
            if(!cellular)return
            cancelDownload(c) // Explicitly switching networks restarts the pending Wi-Fi download.
        }
        val directory=c.getExternalFilesDir("updates") ?: throw IOException("下载目录不可用")
        val file=File(directory,"download.apk")
        if(file.exists() && !file.delete())throw IOException("旧安装包无法清理")
        val request=DownloadManager.Request(Uri.parse(r.url)).setTitle("Loop ${r.version}")
            .setDescription("更新下载完成后，请打开 Loop 的软件更新页面安装")
            .setMimeType("application/vnd.android.package-archive")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setAllowedOverMetered(cellular).setAllowedOverRoaming(false)
            .setAllowedNetworkTypes(if(cellular)DownloadManager.Request.NETWORK_WIFI or DownloadManager.Request.NETWORK_MOBILE else DownloadManager.Request.NETWORK_WIFI)
            .setDestinationInExternalFilesDir(c,"updates","download.apk")
        val id=manager(c).enqueue(request)
        if(!store(c).edit().putLong("download_id",id).putBoolean("ready",false).commit()) {
            manager(c).remove(id);throw IOException("无法保存下载任务")
        }
        status(c,if(cellular)"正在下载 ${r.version}（允许移动网络）" else "等待 Wi-Fi 或正在下载 ${r.version}")
    }
    internal fun reconcile(c: Context) {
        val r=available(c) ?: return
        if(r.code<=installed(c).longVersionCode) { clear(c);status(c,"已安装最新下载的版本 ${r.version}");return }
        val id=store(c).getLong("download_id",-1)
        if(id<0)return
        manager(c).query(DownloadManager.Query().setFilterById(id)).use { cursor ->
            if(cursor==null || !cursor.moveToFirst()) { cancelDownload(c);status(c,"下载任务已移除，可重新下载");return }
            when(cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    try {
                        val dir=File(c.filesDir,"updates").apply { mkdirs() }
                        val staging=File(dir,"staging.apk")
                        manager(c).openDownloadedFile(id).use { fd ->
                            android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).use { input ->
                                staging.outputStream().use { out ->
                                    val buffer=ByteArray(65536);var count=0L
                                    while(true) { val n=input.read(buffer);if(n<0)break;count+=n;require(count<=r.bytes);out.write(buffer,0,n) }
                                }
                            }
                        }
                        verifyApk(c,staging,r)
                        val target=File(dir,"verified.apk");target.delete();require(staging.renameTo(target))
                        store(c).edit().putBoolean("ready",true).apply()
                        cancelDownload(c)
                        status(c,"${r.version} 已下载并通过校验，点击安装更新")
                        runCatching { notifyReady(c,r) }
                    } catch(e: Exception) {
                        cancelDownload(c);File(c.filesDir,"updates/staging.apk").delete()
                        store(c).edit().putBoolean("ready",false).apply()
                        status(c,"安装包校验失败，已丢弃；请重新下载")
                    }
                }
                DownloadManager.STATUS_FAILED -> { cancelDownload(c);status(c,"下载失败，可重试或打开 GitHub 发布页面") }
                else -> {
                    val bytes=cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                    if(bytes>r.bytes) { cancelDownload(c);status(c,"下载大小超出发布清单，已取消");return }
                    status(c,"${r.version} 下载中 / 等待网络：${(bytes*100/r.bytes).coerceIn(0,100)}%")
                }
            }
        }
    }
    internal fun verifyApk(c: Context,file: File,r: UpdateRelease) {
        UpdateRelease.verify(file,r)
        val archive=c.packageManager.getPackageArchiveInfo(file.path,PackageManager.GET_SIGNING_CERTIFICATES)
            ?: throw IOException("无法识别安装包")
        val own=installed(c)
        require(archive.packageName==c.packageName && archive.longVersionCode==r.code && r.code>own.longVersionCode && archive.versionName==r.version)
        require((archive.applicationInfo?.minSdkVersion ?: Int.MAX_VALUE)<=android.os.Build.VERSION.SDK_INT)
        val expected=own.signingInfo?.apkContentsSigners?.map { UpdateRelease.digest(it.toByteArray()) }?.toSet().orEmpty()
        val actual=archive.signingInfo?.apkContentsSigners?.map { UpdateRelease.digest(it.toByteArray()) }?.toSet().orEmpty()
        require(expected.isNotEmpty() && actual==expected) { "更新签名与当前应用不一致" }
    }
    fun install(activity: Activity, done: (String?)->Unit) {
        val c=activity.applicationContext
        executor.execute {
            val result=runCatching {
                val r=available(c) ?: throw IOException("请先检查并下载更新")
                val file=File(c.filesDir,"updates/verified.apk")
                verifyApk(c,file,r)
                FileProvider.getUriForFile(c,"${c.packageName}.updates",file)
            }
            if(result.isFailure) {
                store(c).edit().putBoolean("ready",false).apply()
                File(c.filesDir,"updates/verified.apk").delete()
                status(c,"安装包已失效，请重新下载")
            }
            LoopApp.main.post {
                if(activity.isFinishing || activity.isDestroyed)return@post
                result.fold({ uri ->
                    try {
                        if(!activity.packageManager.canRequestPackageInstalls()) {
                            activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:${c.packageName}")))
                            done("请允许 Loop 安装应用，返回后再次点击“安装更新”")
                        } else {
                            activity.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri,"application/vnd.android.package-archive")
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).apply { clipData=ClipData.newRawUri("Loop 更新",uri) })
                            done(null)
                        }
                    } catch(_: Exception) { done("无法打开系统安装界面，请从 GitHub 发布页面下载安装") }
                },{ done("安装包未通过校验或已失效，请重新下载") })
            }
        }
    }
    private fun notifyReady(c: Context,r: UpdateRelease) {
        if(c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)return
        val nm=c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("updates","软件更新",NotificationManager.IMPORTANCE_DEFAULT))
        val pending=PendingIntent.getActivity(c,NOTICE,Intent(c,SettingsActivity::class.java).putExtra("page","updates"),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        nm.notify(NOTICE,Notification.Builder(c,"updates").setSmallIcon(R.drawable.ic_loop).setContentTitle("Loop ${r.version} 可安装")
            .setContentText("安装包已下载并通过校验，点击继续").setContentIntent(pending).setAutoCancel(true).build())
    }
    private fun safeError(e: Exception)=if(e is IOException)e.message.orEmpty().take(100) else "网络或发布信息异常"
}

class UpdateJob: JobService() {
    private val running=mutableMapOf<Int,AtomicBoolean>()
    override fun onStartJob(params: JobParameters): Boolean {
        val token=AtomicBoolean(false);running.put(params.jobId,token)?.set(true)
        val done={ if(!token.get()) { running.remove(params.jobId);jobFinished(params,false) } }
        // Completion reconciliation also runs when automatic checking is disabled.
        if(params.jobId==AppUpdates.FINISH_JOB)AppUpdates.refresh(this,done)
        else AppUpdates.check(this,stopped=token,done=done)
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean { running.remove(params.jobId)?.set(true);return true }
}

class UpdateDownloadReceiver: BroadcastReceiver() {
    override fun onReceive(context: Context,intent: Intent) {
        if(intent.action!=DownloadManager.ACTION_DOWNLOAD_COMPLETE)return
        val id=intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID,-1)
        if(id<0 || id!=context.getSharedPreferences("loop-updates",Context.MODE_PRIVATE).getLong("download_id",-2))return
        context.getSystemService(JobScheduler::class.java).schedule(JobInfo.Builder(AppUpdates.FINISH_JOB,ComponentName(context,UpdateJob::class.java)).build())
    }
}
