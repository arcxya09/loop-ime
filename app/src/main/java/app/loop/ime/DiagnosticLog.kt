package app.loop.ime

import android.Manifest
import android.app.Application
import android.app.KeyguardManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.util.Collections
import java.util.IdentityHashMap
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Metadata only. Never accepts request text, keys, contact names, URLs or exception messages. */
internal object DiagnosticLog {
    enum class Area { APP, SETTINGS, STORAGE, DATABASE, DATABASE_RECOVERY, VAULT, NINE_KEY, CANDIDATE_TEST, AI_COMPLETE, AI_TEST, CONTACTS, CONTACTS_PERMISSION, LOG_EXPORT, SPEECH }
    enum class Step { START, POLICY, NATIVE_LOAD, KEY_READ, KEY_WRITE, DATABASE_OPEN, SCHEMA, PRIVACY_CHECK, TERMS_READ, PROFILE_READ, ENCODE, HTTP_CONNECT, HTTP_WRITE, HTTP_RESPONSE, HTTP_BODY, DECODE, PINYIN, CALLBACK, PROVIDER_QUERY, CURSOR_READ, TERM_WRITE, PERMISSION, SNAPSHOT, LEGACY_PROBE, INTEGRITY, REKEY, REOPEN, PUBLISH, BASIC_CANDIDATES, SUCCESS, FAILURE, CANCELLED, TIMEOUT, CRASH }
    private val countNames=setOf("elapsed_ms","http_status","official_provider","cloud_enabled","t9_enabled","private_mode","key_present","legacy_record","database_record","permission_granted","candidate_count","hint_count","rows_read","imported","skipped","schema","queued","timed_out","reason_code")
    private val session=UUID.randomUUID().toString().take(8)
    private val ids=AtomicLong()
    private val dropped=AtomicLong()
    private val writer=ThreadPoolExecutor(1,1,0L,TimeUnit.MILLISECONDS,ArrayBlockingQueue(256),
        { Thread(it,"Loop-diagnostics-writer").apply { isDaemon=true } },ThreadPoolExecutor.AbortPolicy())
    val reports=ThreadPoolExecutor(1,1,0L,TimeUnit.MILLISECONDS,ArrayBlockingQueue(8),
        { Thread(it,"Loop-diagnostics-report").apply { isDaemon=true } },ThreadPoolExecutor.AbortPolicy())
    @Volatile private var store: DiagnosticFiles?=null
    @Volatile private var writeFailed=false
    private var crashHandlerInstalled=false

    @Synchronized fun attach(c: Context,installCrashHandler: Boolean=false) {
        if(store==null && LoopApp.unlocked(c)) {
            val process=if(Application.getProcessName().endsWith(":asr"))"asr" else "main"
            store=DiagnosticFiles(File(c.noBackupFilesDir,"diagnostics"),process)
            event(Area.APP,Step.START)
        }
        if(installCrashHandler && !crashHandlerInstalled) {
            crashHandlerInstalled=true
            val previous=Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread,error ->
                try {
                    // A synchronous final record survives process termination; normal writes are queued.
                    store?.append(record(Area.APP,Step.CRASH,"crash",emptyArray(),error))
                } catch(_: Throwable) { /* Never replace the original crash. */ }
                finally { previous?.uncaughtException(thread,error) }
            }
        }
    }

    class Trace internal constructor(private val area: Area) {
        private val id="$session-${ids.incrementAndGet()}"
        private val started=System.nanoTime()
        @Volatile private var current=Step.START
        @Volatile private var finished=false
        init { emit(area,current,id,emptyArray()) }
        fun mark(step: Step,vararg counts: Pair<String,Long>) { current=step;emit(area,step,id,counts) }
        fun at(step: Step) { current=step }
        fun success(vararg counts: Pair<String,Long>) { finished=true;emit(area,Step.SUCCESS,id,(counts.toList()+elapsed()).toTypedArray()) }
        fun failure(error: Throwable) { if(finished)return;finished=true;emit(area,Step.FAILURE,id,arrayOf(elapsed()),error,current) }
        fun cancelled(timedOut: Boolean=false) { if(finished)return;finished=true;emit(area,if(timedOut)Step.TIMEOUT else Step.CANCELLED,id,arrayOf(elapsed()),failedStep=current) }
        private fun elapsed()="elapsed_ms" to TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started)
    }
    fun begin(area: Area)=Trace(area)
    fun event(area: Area,step: Step,vararg counts: Pair<String,Long>)=emit(area,step,"$session-event",counts)
    fun failure(area: Area,error: Throwable,vararg counts: Pair<String,Long>)=emit(area,Step.FAILURE,"$session-event",counts,error)
    private fun emit(area: Area,step: Step,id: String,counts: Array<out Pair<String,Long>>,error: Throwable?=null,failedStep: Step?=null) {
        val target=store ?: return
        // Serialize before queuing: the queue never retains an exception or arbitrary caller data.
        try {
            val line=record(area,step,id,counts,error,failedStep)
            writer.execute { try { target.append(line);writeFailed=false } catch(_: Exception) { writeFailed=true;dropped.incrementAndGet() } }
        } catch(_: Exception) { dropped.incrementAndGet() }
    }
    internal fun record(area: Area,step: Step,id: String,counts: Array<out Pair<String,Long>>,error: Throwable?=null,failedStep: Step?=null): String {
        val row=JSONObject().put("time",Instant.now().toString()).put("area",area.name).put("step",step.name)
            .put("trace",id.take(48).replace(Regex("[^a-zA-Z0-9-]"),"_"))
        failedStep?.let { row.put("failed_at",it.name) }
        counts.filter { it.first in countNames }.forEach { (key,value)->row.put(key,value) }
        if(error!=null)row.put("error",safeException(error))
        return row.toString()
    }
    /** No message/toString/printStackTrace: those can embed full SQL, names, keys or server bodies. */
    internal fun safeException(error: Throwable): JSONObject {
        val seen=Collections.newSetFromMap(IdentityHashMap<Throwable,Boolean>())
        fun describe(t: Throwable,depth: Int): JSONObject {
            if(depth>=6 || seen.size>=12 || !seen.add(t))return JSONObject().put("truncated",true)
            val j=JSONObject().put("type",symbol(t.javaClass.name)).put("reason",reason(t))
            val frames=org.json.JSONArray()
            t.stackTrace.filter { f -> listOf("app.loop.","android.","java.","javax.","net.zetetic.","org.json.","okhttp3.","androidx.").any(f.className::startsWith) }
                .take(12).forEach { f -> frames.put("${symbol(f.className)}.${symbol(f.methodName)}:${f.lineNumber}") }
            j.put("frames",frames)
            t.cause?.let { j.put("cause",describe(it,depth+1)) }
            if(t.suppressed.isNotEmpty())j.put("suppressed",org.json.JSONArray(t.suppressed.take(2).map { describe(it,depth+1) }))
            return j
        }
        return describe(error,0)
    }
    private fun symbol(s: String)=s.take(160).replace(Regex("[^a-zA-Z0-9_.$<>]"),"_")
    private fun reason(t: Throwable): String {
        val name=t.javaClass.simpleName
        // Match locally, emit only fixed codes. Never persist the inspected message.
        val message=t.message.orEmpty().lowercase(java.util.Locale.ROOT)
        return when {
            name=="UserNotAuthenticatedException" -> "KEYSTORE_AUTH_REQUIRED"
            name=="KeyPermanentlyInvalidatedException" -> "KEY_INVALIDATED"
            name in setOf("AEADBadTagException","BadPaddingException") -> "DECRYPT_AUTH_FAILED"
            t is java.net.SocketTimeoutException -> "NETWORK_TIMEOUT"
            t is java.net.UnknownHostException -> "DNS_FAILURE"
            t is javax.net.ssl.SSLException -> "TLS_FAILURE"
            t is java.net.ConnectException -> "CONNECTION_FAILED"
            t is SecurityException -> "PERMISSION_DENIED"
            t is java.util.concurrent.RejectedExecutionException -> "QUEUE_FULL"
            t is org.json.JSONException -> "JSON_FORMAT"
            "sqlite" in name.lowercase(java.util.Locale.ROOT) && "not a database" in message -> "DATABASE_KEY_OR_FORMAT"
            "sqlite" in name.lowercase(java.util.Locale.ROOT) && "no such table" in message -> "DATABASE_MISSING_TABLE"
            "sqlite" in name.lowercase(java.util.Locale.ROOT) && "no such column" in message -> "DATABASE_MISSING_COLUMN"
            "sqlite" in name.lowercase(java.util.Locale.ROOT) && "locked" in message -> "DATABASE_LOCKED"
            "sqlite" in name.lowercase(java.util.Locale.ROOT) && "constraint" in message -> "DATABASE_CONSTRAINT"
            "enospc" in message || "no space left" in message -> "STORAGE_FULL"
            "transliterator" in message || "invalid id" in message -> "TRANSLITERATOR_UNAVAILABLE"
            else -> "SEE_TYPE_AND_STAGE"
        }
    }
    internal fun flush() { writer.submit {}.get(3,TimeUnit.SECONDS) }
    fun clear(c: Context) { attach(c);flush();store?.clear() ?: error("日志尚未初始化");dropped.set(0);writeFailed=false }
    fun report(c: Context,full: Boolean=false): String {
        attach(c)
        val flushed=runCatching { flush() }.isSuccess
        val files=store ?: error("请解锁手机后生成日志")
        val events=files.snapshot(if(full)DiagnosticFiles.MAX_REPORT else 64*1024)
        return buildString {
            appendLine("Loop 输入法诊断日志 · ${Instant.now()}")
            appendLine("只包含运行阶段、状态码、异常类型及代码位置；不包含 API Key、联系人姓名、输入内容、音频、请求或响应正文。")
            appendLine("日志仅保存在本机，自动轮换，容量上限约 512 KiB；报告仅包含最近 7 天的记录。")
            appendLine("候选：NINE_KEY / CANDIDATE_TEST；通讯录：CONTACTS；数据库：DATABASE；加密：VAULT。failed_at 表示失败阶段。")
            appendLine("---- 环境 ----")
            val version=runCatching { c.packageManager.getPackageInfo(c.packageName,0).versionName }.getOrNull()
            appendLine("app=${version.orEmpty().take(40)} sdk=${Build.VERSION.SDK_INT} android=${Build.VERSION.RELEASE.take(32)}")
            appendLine("manufacturer=${Build.MANUFACTURER.take(64)} model=${Build.MODEL.take(64)} abi=${Build.SUPPORTED_ABIS.joinToString(",").take(128)}")
            val p=Prefs(c)
            appendLine("cloud=${p.cloud} t9=${p.flag("ai_t9",true)} private=${p.privateMode}")
            appendLine("contacts_permission=${c.checkSelfPermission(Manifest.permission.READ_CONTACTS)==PackageManager.PERMISSION_GRANTED} microphone_permission=${c.checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED}")
            appendLine("user_unlocked=${LoopApp.unlocked(c)} device_locked=${c.getSystemService(KeyguardManager::class.java).isDeviceLocked}")
            val network=runCatching { val cm=c.getSystemService(ConnectivityManager::class.java);cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)==true }.getOrDefault(false)
            appendLine("network_validated=$network")
            val vault=c.getSharedPreferences("loop-vault",Context.MODE_PRIVATE)
            val active=runCatching { DatabaseLocation(c).active() }.getOrNull()
            val dbFile=active?.file ?: File(c.noBackupFilesDir,"loop.db")
            val dbKey=vault.getString(active?.keyName ?: "database",null)
            appendLine("database_file_present=${dbFile.exists()} database_key_format=${if(dbKey==null)"missing" else if(dbKey.startsWith("v2:"))"v2" else "legacy"} deepseek_record_present=${vault.contains("api.deepseek")}")
            val header=runCatching { dbFile.inputStream().use { it.readNBytes(16) } }.getOrNull()
            appendLine("database_bytes=${dbFile.length()} wal_bytes=${File(dbFile.path+"-wal").length()} journal_bytes=${File(dbFile.path+"-journal").length()} database_location_valid=${active!=null} database_separate=${active?.id?.isNotEmpty()==true}")
            appendLine("database_header=${if(header==null)"unreadable" else if(header.size<16)"short" else if(header.contentEquals("SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)))"sqlite" else "encrypted_or_unknown"}")
            appendLine("writer_flushed=$flushed writer_error=$writeFailed dropped_events=${dropped.get()}")
            appendLine(if(full)"---- 完整保留日志（按时间排序） ----" else "---- 最近日志（最多 64 KiB；更多记录请导出 TXT） ----")
            append(events.ifEmpty { "暂无记录。请先重现一次失败，再刷新或导出。\n" })
        }
    }
}

/** Separate files per Android process; bounded append-only metadata, independent of SQLCipher/Vault. */
internal class DiagnosticFiles(private val directory: File,private val process: String,private val maxBytes: Int=128*1024,private val now: ()->Long=System::currentTimeMillis) {
    private val active get()=File(directory,"$process.jsonl")
    private val old get()=File(directory,"$process.previous.jsonl")
    init { require(process in setOf("main","asr")) }
    @Synchronized fun append(line: String) {
        check(directory.isDirectory || directory.mkdirs()) { "诊断日志目录不可写" }
        listOf(active,old).filter { it.exists() && now()-it.lastModified()>RETENTION }.forEach { check(it.delete()) }
        val bytes=(line+"\n").toByteArray(Charsets.UTF_8)
        if(bytes.size>maxBytes)return
        if(active.length()+bytes.size>maxBytes) {
            if(old.exists())check(old.delete())
            if(active.exists())check(active.renameTo(old)) { "日志轮换失败" }
        }
        active.appendBytes(bytes)
    }
    @Synchronized fun snapshot(limit: Int): String {
        val entries=mutableListOf<Pair<Long,String>>()
        for(p in listOf("main","asr"))for(suffix in listOf(".previous.jsonl",".jsonl")) {
            val f=File(directory,p+suffix)
            if(!f.isFile || now()-f.lastModified()>RETENTION)continue
            val text=runCatching { f.inputStream().use { it.readNBytes(maxBytes+1).toString(Charsets.UTF_8) } }.getOrNull() ?: continue
            for(line in text.lineSequence())runCatching {
                val time=Instant.parse(JSONObject(line).getString("time")).toEpochMilli()
                if(time>=now()-RETENTION)entries+=time to line
            }
        }
        val out=ArrayDeque<String>();var bytes=0
        for((_,line) in entries.sortedBy { it.first }.asReversed()) {
            val size=line.toByteArray(Charsets.UTF_8).size+1
            if(bytes+size>limit)break
            out.addFirst(line);bytes+=size
        }
        return out.joinToString("\n").let { if(it.isEmpty())it else it+"\n" }
    }
    @Synchronized fun clear() {
        for(p in listOf("main","asr"))for(suffix in listOf(".previous.jsonl",".jsonl")) {
            val f=File(directory,p+suffix);if(f.exists())check(f.delete()) { "部分日志未能删除" }
        }
    }
    companion object { const val MAX_REPORT=512*1024;private const val RETENTION=7L*24*60*60*1000 }
}
