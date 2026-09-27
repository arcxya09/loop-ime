package app.loop.ime

import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal data class ModelFile(val name: String,val remote: String,val bytes: Long,val sha256: String)

/** Pinned upstream weights; only these five files are downloaded, with no account or API key. */
internal object OfflineModelCatalog {
    const val ID="zipformer-zh-en-98590b7-v1"
    const val REPOSITORY="https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20"
    const val REVISION="98590b7ed6443e77b714204da2757d75e1a642f4"
    val files=listOf(
        ModelFile("encoder.onnx","encoder-epoch-99-avg-1.int8.onnx",181895032,"8fa764187a261844f859d7143ebaa563af5d10adfece4c18a8f414c88cba2a9b"),
        ModelFile("decoder.onnx","decoder-epoch-99-avg-1.onnx",13876452,"2e3b5ec371f8899ee6acd829fd753ba45772df57a91bdf37cde3136354e7db7d"),
        ModelFile("joiner.onnx","joiner-epoch-99-avg-1.int8.onnx",3228404,"1ed689c5ed19dbaa725d9d191bb4822b5f4855a39e1ffd28cbc1f340d25b2ee0"),
        ModelFile("tokens.txt","tokens.txt",56317,"a8e0e4ec53810e433789b54a5c0134a7eaa2ffca595a6334d54c00da858841d3"),
        ModelFile("bpe.vocab","bpe.vocab",12564,"d0b642f3a2eacd5fadefdeff9e0e1358cab729647cbb7fe58cf738e1f7407029")
    )
    val bytes=files.sumOf { it.bytes }
    fun url(file: ModelFile)="$REPOSITORY/resolve/$REVISION/${file.remote}?download=true"
}

/** App-private, same-filesystem staging. A ready directory is published only after every hash passes. */
internal class OfflineModelPackage(val root: File,val files: List<ModelFile> = OfflineModelCatalog.files,
    val id: String=OfflineModelCatalog.ID) {
    val directory=File(root,id)
    val staging=File(root,"$id.partial")
    val total=files.sumOf { it.bytes }
    init {
        require(id.matches(Regex("[a-zA-Z0-9-]+")))
        require(files.map { it.name }.distinct().size==files.size && files.isNotEmpty())
        require(files.all { it.name.matches(Regex("[a-zA-Z0-9_-]+\\.[a-zA-Z0-9]+")) && it.bytes>0 && it.sha256.matches(Regex("[a-f0-9]{64}")) })
    }
    fun installed(): Boolean=runCatching {
        val ready=File(directory,"ready")
        ready.length()==id.toByteArray().size.toLong() && ready.readText()==id && files.all { File(directory,it.name).let { f -> f.isFile && f.length()==it.bytes } }
    }.getOrDefault(false)
    fun cachedBytes()=files.sumOf { File(staging,it.name).length().coerceIn(0,it.bytes) }
    fun verifyDirectory(dir: File,checkRunning: ()->Unit={})=files.all { valid(File(dir,it.name),it,checkRunning) }
    fun valid(file: File,spec: ModelFile,checkRunning: ()->Unit={}): Boolean {
        if(!file.isFile || file.length()!=spec.bytes)return false
        val digest=MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer=ByteArray(65536)
            while(true) { checkRunning();val n=input.read(buffer);if(n<0)break;digest.update(buffer,0,n) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }==spec.sha256
    }
    fun <T> locked(action: ()->T): T {
        check(root.isDirectory || root.mkdirs()) { "无法创建模型目录" }
        RandomAccessFile(File(root,"download.lock"),"rw").use { f ->
            val lock=runCatching { f.channel.tryLock() }.getOrNull() ?: throw IOException("模型正在处理，请稍后重试")
            lock.use { return action() }
        }
    }
    fun remove()=locked {
        for(dir in listOf(directory,staging,File(root,"$id.previous")))check(!dir.exists() || dir.deleteRecursively()) { "模型文件暂时无法删除" }
    }
    fun publish() {
        FileOutputStream(File(staging,"ready")).use { it.write(id.toByteArray());it.fd.sync() }
        val old=File(root,"$id.previous")
        check(!old.exists() || old.deleteRecursively()) { "无法清理旧模型" }
        if(directory.exists())check(directory.renameTo(old)) { "无法替换旧模型" }
        if(!staging.renameTo(directory)) { if(old.exists())old.renameTo(directory);throw IOException("无法安装模型，请检查存储空间") }
        old.deleteRecursively()
    }
}

/** A cancellable foreground-page transfer. Part files survive app/process exit and use HTTP Range. */
internal class ModelDownload(private val model: OfflineModelPackage,private val client: Call.Factory=downloadClient,
    private val url: (ModelFile)->String=OfflineModelCatalog::url,private val freeBytes: ()->Long={ model.root.usableSpace }) {
    private val cancelled=AtomicBoolean(false)
    private val call=AtomicReference<Call?>(null)
    fun cancel() { cancelled.set(true);call.get()?.cancel() }
    private fun checkRunning() { if(cancelled.get())throw InterruptedIOException("下载已暂停，下次可继续") }
    fun run(progress: (Long,String)->Unit) = model.locked {
        checkRunning()
        if(model.installed() && model.verifyDirectory(model.directory,::checkRunning)) { progress(model.total,"离线模型已就绪");return@locked }
        check(model.staging.isDirectory || model.staging.mkdirs()) { "无法创建下载目录" }
        check(freeBytes()>=model.total-model.cachedBytes()+16L*1024*1024) { "存储空间不足，请清理空间后继续下载" }
        var completed=0L
        for(spec in model.files) {
            checkRunning();val file=File(model.staging,spec.name)
            if(model.valid(file,spec,::checkRunning)) { completed+=spec.bytes;progress(completed,"下载中");continue }
            if(file.length()>=spec.bytes)check(file.delete()) { "无法清理损坏的下载文件" }
            transfer(spec,file) { received -> progress(completed+received,"下载中") }
            progress(completed+spec.bytes,"正在校验模型")
            if(!model.valid(file,spec,::checkRunning)) { file.delete();throw IOException("模型校验失败，请重试下载") }
            completed+=spec.bytes
        }
        checkRunning();model.publish();progress(model.total,"离线模型已就绪")
    }
    private fun transfer(spec: ModelFile,file: File,progress: (Long)->Unit) {
        var restarted=false
        while(file.length()<spec.bytes) {
            checkRunning()
            var offset=file.length()
            val request=Request.Builder().url(url(spec)).header("Accept-Encoding","identity").header("User-Agent","LoopIME/0.1.8-model-download")
            if(offset>0)request.header("Range","bytes=$offset-")
            val current=client.newCall(request.build());call.set(current)
            try {
                checkRunning()
                current.execute().use { response ->
                    if(response.code==416 && offset>0 && !restarted) {
                        check(file.delete()) { "无法重置下载文件" };restarted=true;return@use
                    }
                    if(response.code!=200 && response.code!=206)throw IOException("下载源暂不可用（HTTP ${response.code}），请稍后重试")
                    var end=spec.bytes
                    if(response.code==206) {
                        val range=Regex("bytes (\\d+)-(\\d+)/(\\d+)").matchEntire(response.header("Content-Range","")!!) ?: throw IOException("下载源不支持正确续传，请稍后重试")
                        val (first,last,total)=range.destructured
                        check(first.toLong()==offset && total.toLong()==spec.bytes && last.toLong() in offset until spec.bytes) { "下载续传范围不匹配" }
                        end=last.toLong()+1
                    } else offset=0 // Server may ignore Range; overwrite from byte zero instead of appending.
                    val body=response.body ?: throw IOException("下载源返回空文件")
                    val length=body.contentLength()
                    check(length<0 || length==end-offset) { "模型文件大小与预期不符" }
                    FileOutputStream(file,offset>0).use { output ->
                        body.byteStream().use { input ->
                            val buffer=ByteArray(65536);var received=offset
                            while(true) {
                                checkRunning();val n=input.read(buffer);if(n<0)break
                                if(received+n>end) { output.close();file.delete();throw IOException("下载文件超出预期大小") }
                                output.write(buffer,0,n);received+=n;progress(received)
                            }
                            output.fd.sync()
                            check(received==end) { "网络中断，已保存下载进度，可继续下载" }
                        }
                    }
                }
            } finally { call.compareAndSet(current,null) }
        }
    }
    companion object {
        private val downloadClient=OkHttpClient.Builder().connectTimeout(15,TimeUnit.SECONDS).readTimeout(30,TimeUnit.SECONDS)
            .followRedirects(true).followSslRedirects(false).retryOnConnectionFailure(true).build()
    }
}
