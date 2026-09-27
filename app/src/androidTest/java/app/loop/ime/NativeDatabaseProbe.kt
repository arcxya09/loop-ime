package app.loop.ime

import android.os.Looper
import net.zetetic.database.sqlcipher.SQLiteDatabase
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Runs the delivered app's database code on Android ART/JNI without requiring PackageManager to boot.
 * This covers SQLCipher, not the app sandbox or Android Keystore. The key below is synthetic. */
object NativeDatabaseProbe {
    @JvmStatic fun main(args: Array<String>) {
        try {
            require(args.size==2 && args[0].startsWith("/data/local/tmp/loop-sqlcipher-"))
            if(Looper.getMainLooper()==null)Looper.prepareMainLooper()
            System.loadLibrary("sqlcipher")
            val folder=File(args[0]);check(folder.isDirectory || folder.mkdirs())
            val file=File(folder,"probe.db")
            val caller=ByteArray(32) { (it+7).toByte() }
            val owner=DatabaseKey(caller);caller.fill(0)
            val raw=SQLiteDatabase.openOrCreateDatabase(file,owner.bytes,null,null)
            if(args[1]=="write") {
                check(raw.version==0) { "Write probe requires a fresh directory" }
                legacy(raw)
            } else require(args[1]=="readback" && raw.version==3)
            PersonalStore(raw,owner).use { store ->
                check(store.memories("王小明").single().text=="联系王小明安排会议")
                check(store.terms("wang").single().score==3)
                check(store.terms("926494266464",nineKey=true).single().text=="王小明")
                check(store.terms(cloudOnly=true).isEmpty())
                check(!store.memories("王小明").single().cloud)
                println("PASS real SQLCipher schema 1->3, local words, evidence and permissions")
                if(args[1]=="write") {
                    raw.disableWriteAheadLogging();raw.enableWriteAheadLogging()
                    pooledReads(raw,store)
                    println("PASS caller key wipe, WAL reconfiguration, secondary readers and foreign keys")
                    store.saveMemory("retry","云端整理测试","test",true);store.markLearned("retry")
                    check(store.pendingCloud().any { it.id=="retry" });store.retryLearning("retry")
                    check(store.pendingCloud().none { it.id=="retry" });store.deleteMemory("retry")
                    println("PASS typed cloud-learning query and retry backoff")
                    store.saveMemory("deleted","词频撤回探针","test",false)
                    store.addTerm("词频撤回探针",source="memory",origin="deleted")
                    store.deleteMemory("deleted");check(store.terms("词频撤回探针").isEmpty())
                    store.addTerm("级联探针","jiliantanzhen");store.forgetTerm("级联探针")
                    check(!raw.query("PRAGMA foreign_key_check").use { it.moveToFirst() })
                    check(raw.query("SELECT count(*) FROM evidence WHERE term='级联探针'").use { it.moveToFirst();it.getInt(0)==0 })
                    println("PASS evidence score retraction and foreign-key cascade")
                    val output=ByteArrayOutputStream();val password="loop-synthetic-backup".toCharArray()
                    try {
                        Backup.export(store,output,password)
                        val encrypted=output.toByteArray()
                        Backup.restore(store,encrypted.inputStream(),password)
                        val count=store.count("memories")
                        encrypted[encrypted.lastIndex]=(encrypted.last().toInt() xor 1).toByte()
                        var rejected=false
                        try { Backup.restore(store,encrypted.inputStream(),password) } catch(_: Exception) { rejected=true }
                        check(rejected && store.count("memories")==count)
                    } finally { password.fill('\u0000') }
                    println("PASS authenticated backup merge and rollback on corrupt ciphertext")
                    store.saveMemory("restart","跨进程重启探针","test",false)
                } else {
                    check(store.memories("跨进程重启探针").any { it.id=="restart" })
                    println("PASS encrypted database survives a separate Android process")
                }
                check(owner.bytes.any { it!=0.toByte() })
            }
            check(owner.bytes.all { it==0.toByte() })
            check(!file.inputStream().use { it.readNBytes(16) }.toString(Charsets.US_ASCII).startsWith("SQLite format"))
            println("PASS database close erases retained key; file header is encrypted")
            println("RESULT PASS ${args[1]}")
            kotlin.system.exitProcess(0)
        } catch(t: Throwable) {
            t.printStackTrace();System.err.println("RESULT FAIL");kotlin.system.exitProcess(1)
        }
    }
    private fun pooledReads(raw: SQLiteDatabase,store: PersonalStore) {
        val pool=Executors.newFixedThreadPool(4)
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val writer=pool.submit {
            raw.beginTransactionNonExclusive()
            try { entered.countDown();check(release.await(300,TimeUnit.SECONDS));raw.setTransactionSuccessful() }
            finally { raw.endTransaction() }
        }
        try {
            check(entered.await(90,TimeUnit.SECONDS))
            (0 until 3).map { pool.submit {
                repeat(4) {
                    check(store.terms("wang").isNotEmpty())
                    // A bare PRAGMA requests the primary connection before preparation. The
                    // writer deliberately holds it, so use a SELECT to inspect each reader.
                    check(raw.query("SELECT foreign_keys FROM pragma_foreign_keys").use { c->c.moveToFirst();c.getInt(0)==1 })
                }
            } }.forEach { it.get(240,TimeUnit.SECONDS) }
        } finally {
            release.countDown()
            try { writer.get(90,TimeUnit.SECONDS) } finally { pool.shutdownNow() }
        }
    }
    private fun legacy(db: SQLiteDatabase) {
        db.beginTransaction()
        try {
            db.execSQL("CREATE TABLE memories(id TEXT PRIMARY KEY,text TEXT NOT NULL,time INTEGER NOT NULL,source TEXT NOT NULL,status TEXT NOT NULL,cloud INTEGER NOT NULL,learned INTEGER NOT NULL DEFAULT 0)")
            db.execSQL("CREATE TABLE terms(text TEXT PRIMARY KEY,pinyin TEXT NOT NULL,score INTEGER NOT NULL DEFAULT 1,cloud INTEGER NOT NULL DEFAULT 0,source TEXT NOT NULL,pinned INTEGER NOT NULL DEFAULT 0)")
            db.execSQL("CREATE TABLE evidence(term TEXT NOT NULL REFERENCES terms(text) ON DELETE CASCADE,origin TEXT NOT NULL,kind TEXT NOT NULL,PRIMARY KEY(term,origin))")
            db.execSQL("CREATE TABLE forgotten(text TEXT PRIMARY KEY)")
            db.execSQL("CREATE TABLE clips(id TEXT PRIMARY KEY,text TEXT NOT NULL,time INTEGER NOT NULL,pinned INTEGER NOT NULL DEFAULT 0)")
            db.execSQL("INSERT INTO memories VALUES ('old','联系王小明安排会议',100,'voice','issued',1,1)")
            db.execSQL("INSERT INTO terms VALUES ('王小明','wangxiaoming',999,1,'memory',0)")
            db.execSQL("INSERT INTO evidence VALUES ('王小明','old','memory'),('王小明','contact:old','contacts'),('孤立来源','orphan','memory')")
            db.version=1;db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
}
