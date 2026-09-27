package app.loop.ime

import android.app.Instrumentation
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.security.keystore.KeyInfo
import android.text.Editable
import android.text.Selection
import android.text.SpannableStringBuilder
import android.view.View
import android.view.inputmethod.BaseInputConnection
import com.k2fsa.sherpa.onnx.*
import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.security.KeyStore
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory

/** Device-side integration checks; contains no production endpoint, key, or private recordings. */
class LoopInstrumentation : Instrumentation() {
    private val report=StringBuilder()
    private var only=""
    private var timeoutScale=1L
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments);only=arguments?.getString("only").orEmpty()
        timeoutScale=(arguments?.getString("timeoutScale")?.toLongOrNull() ?: 1L).coerceIn(1,10)
        start()
    }
    private fun checkCase(name: String,body: ()->Unit) {
        val start=System.currentTimeMillis();body();report.append("PASS $name (${System.currentTimeMillis()-start} ms)\n")
        sendStatus(0,Bundle().apply { putString("stream",report.lines().lastOrNull { it.isNotBlank() }+"\n") })
    }
    override fun onStart() {
        try {
            if(only=="database_readback") {
                checkCase("SQLCipher and Keystore survive a process restart") {
                    val s=PersonalStore.get(targetContext)
                    check(s.memories("重启持久化探针").any { it.id=="loop-restart-probe" })
                    check(s.terms("chongqi").any { it.text=="重启持久化探针" })
                    check(s.terms("chongqi",cloudOnly=true).isEmpty())
                    s.deleteMemory("loop-restart-probe")
                }
                finish(Activity.RESULT_OK,Bundle().apply { putString("stream",report.toString());putString("result","PASS") });return
            }
            checkCase("settings launch") { startActivitySync(Intent(targetContext,SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));waitForIdleSync() }
            checkCase("real Android Keystore / save and reopen without authentication prompt") {
                val name="test.keystore."+java.util.UUID.randomUUID()
                val plaintext="Loop synthetic keystore probe".toByteArray()
                try {
                    Vault(targetContext).put(name,plaintext)
                    check(plaintext.contentEquals(Vault(targetContext).get(name)))
                    val ks=KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                    val key=ks.getKey(Vault.CURRENT_ALIAS,null) as SecretKey
                    val info=SecretKeyFactory.getInstance("AES","AndroidKeyStore").getKeySpec(key,KeyInfo::class.java) as KeyInfo
                    check(!info.isUserAuthenticationRequired && !info.isUnlockedDeviceRequired && info.keySize==256)
                    val raw=targetContext.getSharedPreferences("loop-vault",0).getString(name,null)!!
                    check(raw.startsWith(Vault.CURRENT_PREFIX) && !raw.contains("synthetic"))
                } finally { plaintext.fill(0);targetContext.getSharedPreferences("loop-vault",0).edit().remove(name).commit() }
            }
            if(only=="keystore") { finish(Activity.RESULT_OK,Bundle().apply { putString("stream",report.toString());putString("result","PASS") });return }
            checkCase("single editor writer / stale patch / Unicode deletion") {
                runOnMainSync {
                    val editable=SpannableStringBuilder();Selection.setSelection(editable,0)
                    val ic=object: BaseInputConnection(View(targetContext),true) { override fun getEditable(): Editable=editable }
                    val e=SafeEditor { ic };e.start(0)
                    check(e.commit("hello worle"));val revision=e.revision;val gen=e.generation
                    check(e.patch("worle","world",revision,gen));check(editable.toString()=="hello world")
                    check(!e.patch("world","wrong",revision,gen))
                    check(e.commit("😊"));e.delete();check(editable.toString()=="hello world")
                    check(e.setComposition("你好世界","voice"));check(e.sealVoice("你好","世界"));check(editable.toString()=="hello world你好世界")
                    check(!e.setComposition("nihao","rime"));e.cancelComposition();check(editable.toString()=="hello world你好")
                    e.selection(0,0,-1,-1);check(!e.patch("你好","您好",e.revision,gen))
                    Selection.setSelection(editable,0,5);e.selection(0,5,-1,-1);check(e.commit("hi"))
                    check(e.patch("hi","hey",e.revision,e.generation));check(editable.toString()=="hey world你好")
                }
            }
            checkCase("encrypted memory / evidence / backup authentication") {
                val s=PersonalStore.get(targetContext);val id="loop-integration-record"
                s.saveMemory(id,"量子计算测试词", "test",false)
                s.addTerm("量子计算测试词",source="memory",origin=id)
                check(s.memories("量子计算测试词").isNotEmpty());check(s.terms("量子计算测试词",cloudOnly=true).isEmpty())
                check(s.terms(NineKey.encode(s.phonetic("量子计算测试词")),nineKey=true).any { it.text=="量子计算测试词" })
                s.addTerm("离线姓名测试",source="contacts",origin="test-contact")
                check(s.containsLocalOnly("联系离线姓名测试"));s.forgetTerm("离线姓名测试")
                val output=ByteArrayOutputStream();val password="test-backup-123".toCharArray();Backup.export(s,output,password)
                val bytes=output.toByteArray();s.deleteMemory(id);check(s.terms("量子计算测试词").isEmpty())
                Backup.restore(s,ByteArrayInputStream(bytes),password);check(s.memories("量子计算测试词").isNotEmpty())
                val count=s.count("memories");val corrupt=bytes.clone();corrupt[corrupt.size-3]=(corrupt[corrupt.size-3].toInt() xor 1).toByte()
                var rejected=false;try { Backup.restore(s,ByteArrayInputStream(corrupt),password) } catch(_: Exception) { rejected=true }
                check(rejected && s.count("memories")==count)
                rejected=false;try { Backup.restore(s,ByteArrayInputStream(bytes.copyOf(bytes.size-20)),password) } catch(_: Exception) { rejected=true }
                check(rejected);s.deleteMemory(id)
                val header=File(targetContext.noBackupFilesDir,"loop.db").inputStream().use { it.readNBytes(16) }
                check(!header.toString(Charsets.US_ASCII).startsWith("SQLite format"))
            }
            if(only=="database") {
                checkCase("real SQLCipher legacy migration / pooled key lifetime / foreign keys") { nativeMigration() }
                checkCase("write encrypted process-restart probe") {
                    val s=PersonalStore.get(targetContext)
                    s.saveMemory("loop-restart-probe","重启持久化探针","test",false)
                    s.addTerm("重启持久化探针","chongqichijiuhuatazhen","memory","loop-restart-probe")
                }
                finish(Activity.RESULT_OK,Bundle().apply { putString("stream",report.toString());putString("result","PASS") });return
            }
            checkCase("real Rime deployment / nine-key candidates / commit") {
                val latch=CountDownLatch(1);var err: String?=null;val engine=RimeEngine(targetContext)
                engine.prepare { err=it;latch.countDown() };check(latch.await(90,TimeUnit.SECONDS));check(err==null) { err ?: "Rime" }
                var state=RimeState()
                for(ch in "64426") { val l=CountDownLatch(1);engine.event(ch.code) { state=it;l.countDown() };check(l.await(15,TimeUnit.SECONDS)) }
                check("你好" in state.candidates) { "Rime candidate missing" }
                val l=CountDownLatch(1);engine.event(state.candidates.indexOf("你好"),1) { state=it;l.countDown() };check(l.await(15,TimeUnit.SECONDS));check(state.commit=="你好")
            }
            checkCase("downloaded streaming ASR / real sample / hotwords") {
                val path=OfflineModels.activePath(targetContext) ?: error("Download an offline model in Loop speech settings before running the full device suite")
                val config=OnlineRecognizerConfig(modelConfig=OnlineModelConfig(transducer=OnlineTransducerModelConfig("$path/encoder.onnx","$path/decoder.onnx","$path/joiner.onnx"),tokens="$path/tokens.txt",numThreads=2,modelType="zipformer",modelingUnit="cjkchar+bpe",bpeVocab="$path/bpe.vocab"),decodingMethod="modified_beam_search",enableEndpoint=false,maxActivePaths=4)
                val r=OnlineRecognizer(null,config);val s=r.createStream("语音识别\n输入法")
                try {
                    val wav=context.assets.open("sample.wav").use { it.readBytes() }
                    val b=ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN);var offset=12;var start=0;var length=0
                    while(offset+8<=wav.size) { val tag=String(wav,offset,4,Charsets.US_ASCII);val size=b.getInt(offset+4);if(tag=="data") { start=offset+8;length=size;break };offset+=8+size+(size%2) }
                    check(start>0 && length>0)
                    var n=0;var partialSeen=false
                    while(n<length/2) { val count=minOf(1280,length/2-n);val pcm=FloatArray(count) { b.getShort(start+2*(n+it))/32768f };n+=count;s.acceptWaveform(pcm,16000);while(r.isReady(s))r.decode(s);if(r.getResult(s).text.isNotBlank())partialSeen=true }
                    s.acceptWaveform(FloatArray(8000),16000);s.inputFinished();while(r.isReady(s))r.decode(s)
                    val text=r.getResult(s).text;check(partialSeen && text.isNotBlank());report.append("ASR public fixture result: $text\n")
                } finally { s.release();r.release() }
            }
            finish(Activity.RESULT_OK,Bundle().apply { putString("stream",report.toString());putString("result","PASS") })
        } catch(t: Throwable) {
            report.append("FAIL ${t.javaClass.name}: ${t.message}\n${t.stackTraceToString()}")
            finish(Activity.RESULT_CANCELED,Bundle().apply { putString("stream",report.toString());putString("result","FAIL") })
        }
    }
    private fun nativeMigration() {
        val file=File(targetContext.noBackupFilesDir,"loop-native-migration-probe.db")
        val secret=ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        try {
            val key=DatabaseKey(secret)
            val raw=net.zetetic.database.sqlcipher.SQLiteDatabase.openOrCreateDatabase(file,key.bytes,null,null)
            raw.execSQL("CREATE TABLE memories(id TEXT PRIMARY KEY,text TEXT NOT NULL,time INTEGER NOT NULL,source TEXT NOT NULL,status TEXT NOT NULL,cloud INTEGER NOT NULL,learned INTEGER NOT NULL DEFAULT 0)")
            raw.execSQL("CREATE TABLE terms(text TEXT PRIMARY KEY,pinyin TEXT NOT NULL,score INTEGER NOT NULL DEFAULT 1,cloud INTEGER NOT NULL DEFAULT 0,source TEXT NOT NULL,pinned INTEGER NOT NULL DEFAULT 0)")
            raw.execSQL("CREATE TABLE evidence(term TEXT NOT NULL REFERENCES terms(text) ON DELETE CASCADE,origin TEXT NOT NULL,kind TEXT NOT NULL,PRIMARY KEY(term,origin))")
            raw.execSQL("CREATE TABLE forgotten(text TEXT PRIMARY KEY)")
            raw.execSQL("CREATE TABLE clips(id TEXT PRIMARY KEY,text TEXT NOT NULL,time INTEGER NOT NULL,pinned INTEGER NOT NULL DEFAULT 0)")
            raw.execSQL("INSERT INTO memories VALUES ('legacy','王小明升级探针',100,'voice','issued',1,1)")
            raw.execSQL("INSERT INTO terms VALUES ('王小明','wangxiaoming',999,1,'memory',0)")
            raw.execSQL("INSERT INTO evidence VALUES ('王小明','contact:probe','contacts'),('王小明','legacy','memory'),('孤立词','orphan','memory')")
            raw.version=1
            PersonalStore(raw,key).use { s ->
                check(raw.version==3 && s.memories().single().text=="王小明升级探针")
                check(s.terms().single().score==3 && s.terms(cloudOnly=true).isEmpty())
                raw.disableWriteAheadLogging();raw.enableWriteAheadLogging()
                val executor=java.util.concurrent.Executors.newFixedThreadPool(4)
                try {
                    (0 until 8).map { executor.submit { repeat(5) { check(s.terms("wang").isNotEmpty()) } } }.forEach { it.get(30*timeoutScale,TimeUnit.SECONDS) }
                } finally { executor.shutdownNow() }
                s.forgetTerm("王小明")
                check(raw.query("SELECT count(*) FROM evidence").use { it.moveToFirst();it.getInt(0)==0 })
                check(!raw.query("PRAGMA foreign_key_check").use { it.moveToFirst() })
                check(key.bytes.contentEquals(secret))
            }
            check(key.bytes.all { it==0.toByte() })
            val again=DatabaseKey(secret)
            PersonalStore(net.zetetic.database.sqlcipher.SQLiteDatabase.openOrCreateDatabase(file,again.bytes,null,null),again).use { s ->
                check(s.memories().single().id=="legacy")
            }
        } finally { secret.fill(0);file.delete();File(file.path+"-wal").delete();File(file.path+"-shm").delete() }
    }
}
