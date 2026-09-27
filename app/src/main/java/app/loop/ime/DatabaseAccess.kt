package app.loop.ime

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import net.zetetic.database.DatabaseErrorHandler
import net.zetetic.database.sqlcipher.SQLiteDatabase
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

internal class DatabaseUnavailable(cause: Throwable) : IllegalStateException(
    "个人数据库暂时无法打开，原文件和 Key 已保留。请进入设置 → 数据库检查与恢复；九宫格可继续使用基础 AI 候选。",cause)

/** A committed pointer selects a fully verified database. Old files and key records never move. */
internal class DatabaseLocation(private val c: Context) {
    private val prefs=c.getSharedPreferences("loop-database",Context.MODE_PRIVATE)
    data class Entry(val id: String,val file: File) {
        val keyName get()=if(id.isEmpty())"database" else "database.$id"
    }
    fun active(): Entry = entry(prefs.getString("active","")!!)
    private fun entry(id: String): Entry {
        require(id.isEmpty() || id.matches(Regex("[a-f0-9]{32}"))) { "数据库位置记录损坏，原文件保留" }
        return Entry(id,File(c.noBackupFilesDir,if(id.isEmpty())"loop.db" else "loop-$id.db"))
    }
    fun next(): Entry=entry(UUID.randomUUID().toString().replace("-",""))
    fun publish(entry: Entry) {
        val old=prefs.getString("active","")!!
        if(!prefs.edit().putString("active",entry.id).commit()) {
            // SharedPreferences updates memory even when persisting fails.
            prefs.edit().putString("active",old).commit()
            error("数据库切换未完成，原库继续保留")
        }
    }
    companion object {
        fun files(file: File)=listOf(file,File(file.path+"-wal"),File(file.path+"-journal"),File(file.path+"-shm"))
        fun hasData(file: File)=files(file).any { it.length()>0 }
    }
}

/** Injectable only at the native boundary; migration, validation and publication use production code. */
internal interface DatabaseDriver {
    fun open(file: File,key: ByteArray,create: Boolean): SupportSQLiteDatabase
    fun verify(db: SupportSQLiteDatabase)
    fun rekey(db: SupportSQLiteDatabase,key: ByteArray)
}

internal object SqlCipherDriver : DatabaseDriver {
    // The library's default corruption handler may delete a database. Loop must preserve it.
    internal val preserve=DatabaseErrorHandler { _,error -> DiagnosticLog.failure(DiagnosticLog.Area.DATABASE,error) }
    override fun open(file: File,key: ByteArray,create: Boolean): SupportSQLiteDatabase {
        System.loadLibrary("sqlcipher")
        return SQLiteDatabase.openDatabase(file.path,key,null,
            if(create)SQLiteDatabase.CREATE_IF_NECESSARY else SQLiteDatabase.OPEN_READWRITE,preserve,null)
    }
    override fun verify(db: SupportSQLiteDatabase) {
        check(db.query("PRAGMA cipher_integrity_check").use { !it.moveToFirst() }) { "数据库加密页校验失败，原文件保留" }
        check(db.query("PRAGMA integrity_check").use { it.moveToFirst() && it.getString(0)=="ok" && !it.moveToNext() }) { "数据库完整性校验失败，原文件保留" }
    }
    override fun rekey(db: SupportSQLiteDatabase,key: ByteArray) {
        // Checkpoint the private copy before changing its encryption. No SQL string contains a key.
        db.disableWriteAheadLogging()
        (db as SQLiteDatabase).changePassword(key)
    }
}

internal class DatabaseAccess(private val c: Context,private val vault: Vault=Vault(c),
    private val driver: DatabaseDriver=SqlCipherDriver) {
    private val location=DatabaseLocation(c)

    fun open(trace: DiagnosticLog.Trace?=null): PersonalStore {
        val entry=location.active()
        if(entry.id.isNotEmpty())check(entry.file.isFile) { "已选数据库文件缺失，原密钥保留" }
        trace?.mark(DiagnosticLog.Step.KEY_READ)
        return withKey(entry) { key ->
            trace?.mark(DiagnosticLog.Step.DATABASE_OPEN)
            val raw=driver.open(entry.file,key.bytes,!DatabaseLocation.hasData(entry.file))
            trace?.mark(DiagnosticLog.Step.SCHEMA)
            PersonalStore(raw,key)
        }
    }

    private fun <T> withKey(entry: DatabaseLocation.Entry,action: (DatabaseKey)->T): T {
        val plain=vault.databaseKey(entry.keyName,!DatabaseLocation.hasData(entry.file) && entry.id.isEmpty())
        val key=try { DatabaseKey(plain) } finally { plain.fill(0) }
        // openStore transfers ownership to PersonalStore; failure always releases the key.
        try { return action(key) } catch(t: Throwable) { key.close();throw t }
    }
    private fun openStore(entry: DatabaseLocation.Entry,key: DatabaseKey,create: Boolean): PersonalStore {
        val raw=driver.open(entry.file,key.bytes,create)
        return PersonalStore(raw,key)
    }

    /** Called under the same lock as PersonalStore.get; no live source pool can race the snapshot. */
    fun recoverLegacy(): PersonalStore {
        val trace=DiagnosticLog.begin(DiagnosticLog.Area.DATABASE_RECOVERY)
        val source=location.active()
        val target=location.next()
        try {
            trace.mark(DiagnosticLog.Step.SNAPSHOT)
            require(source.file.isFile && source.file.length()>0) { "没有可检查的旧数据库，原文件保留" }
            snapshot(source.file,target.file)
            trace.mark(DiagnosticLog.Step.LEGACY_PROBE)
            // Historical array clearing can leave a 32-byte zero password. Probe only a copy,
            // never use it for a fresh database, and never treat a failed probe as an empty store.
            val legacy=DatabaseKey(ByteArray(32))
            try {
                val raw=driver.open(target.file,legacy.bytes,false)
                var next: DatabaseKey?=null
                try {
                    trace.mark(DiagnosticLog.Step.INTEGRITY)
                    validateLoop(raw);driver.verify(raw)
                    val plain=vault.databaseKey(target.keyName,true)
                    next=try { DatabaseKey(plain) } finally { plain.fill(0) }
                    trace.mark(DiagnosticLog.Step.REKEY)
                    driver.rekey(raw,next.bytes)
                    driver.verify(raw)
                } finally {
                    // The reconfigured native pool also retains the new key array.
                    try { raw.close() } finally { next?.close() }
                }
            } finally { legacy.close() }
            trace.mark(DiagnosticLog.Step.REOPEN)
            val store=withKey(target) { key ->
                val raw=driver.open(target.file,key.bytes,false)
                try { validateLoop(raw);driver.verify(raw) } catch(t: Throwable) { raw.close();throw t }
                PersonalStore(raw,key)
            }
            try {
                trace.mark(DiagnosticLog.Step.PUBLISH)
                location.publish(target)
            } catch(t: Throwable) { store.close();throw t }
            trace.success();return store
        } catch(t: Throwable) {
            trace.failure(t)
            // Only our unpublished working copy can be removed; original DB/WAL/key stay intact.
            if(location.active().id!=target.id)DatabaseLocation.files(target.file).forEach { it.delete() }
            throw IllegalStateException("旧库未通过兼容恢复校验，原数据库和 Key 已保留。可以保留旧库并启用新库，再重新导入通讯录；旧记忆需可用密钥或备份才能恢复。",t)
        }
    }

    /** Explicit UI choice, never an automatic response to a database or key error. */
    fun createSeparate(): PersonalStore {
        val trace=DiagnosticLog.begin(DiagnosticLog.Area.DATABASE_RECOVERY)
        val target=location.next()
        try {
            trace.mark(DiagnosticLog.Step.KEY_WRITE)
            val plain=vault.databaseKey(target.keyName,true)
            val key=try { DatabaseKey(plain) } finally { plain.fill(0) }
            try {
                trace.mark(DiagnosticLog.Step.DATABASE_OPEN)
                openStore(target,key,true).use { /* Commit complete schema before reopening. */ }
            } catch(t: Throwable) { key.close();throw t }
            trace.mark(DiagnosticLog.Step.REOPEN)
            val store=withKey(target) { owner ->
                val raw=driver.open(target.file,owner.bytes,false)
                try { validateLoop(raw);driver.verify(raw) } catch(t: Throwable) { raw.close();throw t }
                PersonalStore(raw,owner)
            }
            try {
                trace.mark(DiagnosticLog.Step.PUBLISH)
                location.publish(target)
            } catch(t: Throwable) { store.close();throw t }
            trace.success();return store
        } catch(t: Throwable) {
            trace.failure(t)
            if(location.active().id!=target.id)DatabaseLocation.files(target.file).forEach { it.delete() }
            throw t
        }
    }
    private fun validateLoop(db: SupportSQLiteDatabase) {
        require(db.version in 1..3) { "旧库版本无法识别，原文件保留" }
        val tables=mutableSetOf<String>()
        db.query("SELECT name FROM sqlite_master WHERE type='table'").use { while(it.moveToNext())tables+=it.getString(0) }
        require(tables.containsAll(listOf("memories","terms","evidence","forgotten","clips"))) { "不是完整的 Loop 数据库，原文件保留" }
    }
    private fun snapshot(source: File,target: File) {
        val inputs=DatabaseLocation.files(source).filter { it.exists() && !it.name.endsWith("-shm") }
        val needed=inputs.sumOf { it.length() }
        check(target.parentFile!!.usableSpace>needed*2+4*1024*1024) { "空间不足，未开始恢复；原文件保留" }
        for(input in inputs) {
            val output=File(target.path+input.path.removePrefix(source.path))
            input.inputStream().use { from -> FileOutputStream(output).use { to -> from.copyTo(to);to.fd.sync() } }
            check(output.length()==input.length()) { "旧库副本不完整，原文件保留" }
        }
    }
}
