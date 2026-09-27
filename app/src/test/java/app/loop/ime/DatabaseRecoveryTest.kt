package app.loop.ime

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import java.io.File
import javax.crypto.KeyGenerator

/** Real SQLite/schema/files and AES-GCM. Only SQLCipher's JNI boundary is substituted. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[37],application=Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class DatabaseRecoveryTest {
    private val c get()=RuntimeEnvironment.getApplication()
    private fun vault(context: Context=c): Vault {
        val wrapping=KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        return Vault(context) { _,_->wrapping }
    }
    private class Driver : DatabaseDriver {
        val keys=mutableMapOf<String,ByteArray>()
        var rejectLegacy=false
        var failRekey=false
        var failVerify=false
        var failReopen=false
        var opens=0
        val retained=mutableListOf<ByteArray>()
        override fun open(file: File,key: ByteArray,create: Boolean): SupportSQLiteDatabase {
            opens++;retained+=key
            if(!create) {
                if(failReopen && keys.containsKey(file.path))error("reopen failed")
                val expected=keys[file.path] ?: ByteArray(32)
                if(rejectLegacy || !expected.contentEquals(key))throw net.zetetic.database.sqlcipher.SQLiteNotADatabaseException("file is not a database")
            } else { check(!file.exists());keys[file.path]=key.clone() }
            val sqlite=SQLiteDatabase.openDatabase(file.path,null,if(create)SQLiteDatabase.CREATE_IF_NECESSARY else SQLiteDatabase.OPEN_READWRITE)
            return ReflectionHelpers.callConstructor(Class.forName("androidx.sqlite.db.framework.FrameworkSQLiteDatabase"),
                ClassParameter.from(SQLiteDatabase::class.java,sqlite)) as SupportSQLiteDatabase
        }
        override fun verify(db: SupportSQLiteDatabase) {
            if(failVerify)error("integrity failed")
            check(db.isDatabaseIntegrityOk)
        }
        override fun rekey(db: SupportSQLiteDatabase,key: ByteArray) {
            if(failRekey)error("rekey failed")
            retained+=key;keys[checkNotNull(db.path)]=key.clone()
        }
    }
    private fun legacy(): File {
        val file=File(c.noBackupFilesDir,"loop.db")
        val driver=Driver();val bytes=ByteArray(32)
        PersonalStore(driver.open(file,bytes,true)).use {
            it.saveMemory("old-memory","联系王小明安排会议","test",false)
            it.addTerm("王小明",source="contacts",origin="contact:1")
            it.addClip("旧剪贴板")
        }
        return file
    }
    @Test fun missingKeyBesideExistingDatabaseNeverCreatesReplacementOrOpensNative() {
        val file=File(c.noBackupFilesDir,"loop.db").apply { writeText("old encrypted bytes") }
        val vault=vault();val driver=Driver();val before=file.readBytes()
        assertTrue(runCatching { DatabaseAccess(c,vault,driver).open() }.isFailure)
        assertEquals(0,driver.opens);assertNull(vault.get("database"));assertArrayEquals(before,file.readBytes())
        assertTrue(runCatching { vault.databaseKey() }.isFailure)
    }
    @Test fun failedProbePreservesSourceWalJournalAndAllExistingKeys() {
        val source=File(c.noBackupFilesDir,"loop.db").apply { writeText("unreadable database") }
        File(source.path+"-wal").writeText("unreadable WAL");File(source.path+"-journal").writeText("old journal")
        val vault=vault();vault.put("database",ByteArray(32) { 7 });vault.put("api.deepseek","saved-key".toByteArray())
        val before=DatabaseLocation.files(source).filter { it.exists() }.associate { it.name to it.readBytes() }
        val keyBefore=c.getSharedPreferences("loop-vault",0).all.toMap()
        val driver=Driver().apply { rejectLegacy=true }
        assertTrue(runCatching { DatabaseAccess(c,vault,driver).recoverLegacy() }.isFailure)
        before.forEach { (name,value)->assertArrayEquals(value,File(c.noBackupFilesDir,name).readBytes()) }
        assertEquals(keyBefore,c.getSharedPreferences("loop-vault",0).all)
        assertEquals("",DatabaseLocation(c).active().id)
        assertTrue(driver.retained.all { b->b.all { it==0.toByte() } })
    }
    @Test fun successfulCopyRecoveryPreservesRowsAndSourceThenReopensWithNewKey() {
        val source=legacy();val original=source.readBytes();val vault=vault()
        vault.put("database",ByteArray(32) { 9 });val sealed=c.getSharedPreferences("loop-vault",0).getString("database",null)
        val driver=Driver();val access=DatabaseAccess(c,vault,driver)
        access.recoverLegacy().use {
            assertEquals("联系王小明安排会议",it.memories().single().text)
            assertEquals("王小明",it.terms().single().text);assertFalse(it.terms().single().cloud)
            assertEquals("旧剪贴板",it.clips().single().text)
        }
        assertArrayEquals(original,source.readBytes());assertEquals(sealed,c.getSharedPreferences("loop-vault",0).getString("database",null))
        val target=DatabaseLocation(c).active();assertNotEquals("",target.id)
        val newKey=vault.get(target.keyName)!!;assertTrue(newKey.any { it!=0.toByte() });newKey.fill(0)
        access.open().use { assertEquals(1L,it.count("memories"));assertEquals(1L,it.count("terms")) }
        assertTrue(driver.retained.all { b->b.all { it==0.toByte() } })
    }
    @Test fun integrityAndRekeyFailuresCannotPublishOrModifyTheOriginal() {
        val source=legacy();val original=source.readBytes();val vault=vault()
        for(mode in 0..2) {
            val driver=Driver().apply { failVerify=mode==0;failRekey=mode==1;failReopen=mode==2 }
            assertTrue(runCatching { DatabaseAccess(c,vault,driver).recoverLegacy() }.isFailure)
            assertEquals("",DatabaseLocation(c).active().id);assertArrayEquals(original,source.readBytes())
            assertTrue(driver.retained.all { b->b.all { it==0.toByte() } })
        }
    }
    @Test fun copiedUncheckpointedWalRowsAreRecoveredWithoutTouchingSourceWal() {
        val source=File(c.noBackupFilesDir,"loop.db")
        StoreFixture().use { f ->
            f.store.saveMemory("wal-only","尚在 WAL 中的记忆","test",false)
            val original=File(f.sqlite.path)
            assertTrue(File(original.path+"-wal").length()>32)
            for(input in DatabaseLocation.files(original).filter { it.exists() && !it.name.endsWith("-shm") })
                input.copyTo(File(source.path+input.path.removePrefix(original.path)))
        }
        val mainBefore=source.readBytes();val wal=File(source.path+"-wal");val walBefore=wal.readBytes()
        DatabaseAccess(c,vault(),Driver()).recoverLegacy().use {
            assertEquals("尚在 WAL 中的记忆",it.memories().single().text)
        }
        assertArrayEquals(mainBefore,source.readBytes());assertArrayEquals(walBefore,wal.readBytes())
    }
    @Test fun separateStoreRetainsOldCiphertextAndKeysAndPersistsNewContactsAcrossReopen() {
        val source=File(c.noBackupFilesDir,"loop.db").apply { writeText("old database bytes") }
        File(source.path+"-wal").writeText("old WAL")
        val vault=vault();vault.put("database",ByteArray(32) { 2 });vault.put("api.deepseek","keep-api-key".toByteArray())
        val original=c.getSharedPreferences("loop-vault",0).all.toMap()
        val driver=Driver();val access=DatabaseAccess(c,vault,driver)
        access.createSeparate().use { s ->
            assertEquals(0L,s.count("memories"));assertEquals(0L,s.count("terms"))
            s.addTerm("王小明",source="contacts",origin="contact:1")
            s.saveMemory("new","新的记忆","test",false)
        }
        original.forEach { (name,value)->assertEquals(value,c.getSharedPreferences("loop-vault",0).all[name]) }
        assertEquals("old database bytes",source.readText());assertEquals("old WAL",File(source.path+"-wal").readText())
        DatabaseAccess(c,vault,driver).open().use { assertEquals("王小明",it.terms().single().text);assertEquals("新的记忆",it.memories().single().text) }
        assertTrue(driver.retained.all { b->b.all { it==0.toByte() } })
    }
    @Test fun failedReopenAndPointerCommitLeaveOldStoreSelected() {
        val source=File(c.noBackupFilesDir,"loop.db").apply { writeText("original") }
        val vault=vault()
        assertTrue(runCatching { DatabaseAccess(c,vault,Driver().apply { failReopen=true }).createSeparate() }.isFailure)
        assertEquals("",DatabaseLocation(c).active().id)
        val prefs=c.getSharedPreferences("loop-database",0)
        var commits=0
        val wrapped=object: ContextWrapper(c) {
            override fun getSharedPreferences(name: String,mode: Int): SharedPreferences {
                if(name!="loop-database")return super.getSharedPreferences(name,mode)
                return object: SharedPreferences by prefs {
                    override fun edit(): SharedPreferences.Editor {
                        val real=prefs.edit()
                        return object: SharedPreferences.Editor by real {
                            override fun putString(k: String?,v: String?): SharedPreferences.Editor { real.putString(k,v);return this }
                            override fun commit(): Boolean { real.commit();return ++commits!=1 }
                        }
                    }
                }
            }
        }
        assertTrue(runCatching { DatabaseAccess(wrapped,vault,Driver()).createSeparate() }.isFailure)
        assertEquals("",DatabaseLocation(c).active().id);assertEquals("original",source.readText())
    }
    @Test fun selectedMissingFileIsNotSilentlyRecreatedAndMalformedPointerCannotEscapeStorage() {
        val location=DatabaseLocation(c);location.publish(location.next())
        val driver=Driver();assertTrue(runCatching { DatabaseAccess(c,vault(),driver).open() }.isFailure)
        assertEquals(0,driver.opens)
        c.getSharedPreferences("loop-database",0).edit().putString("active","../../other").commit()
        assertTrue(runCatching { location.active() }.isFailure)
    }
    @Test fun recoveryPageAndCancelledFreshStoreChoiceDoNotTouchDatabaseOrKeys() {
        val file=File(c.noBackupFilesDir,"loop.db").apply { writeText("keep-old") }
        c.getSharedPreferences("loop-vault",0).edit().putString("database","unreadable-old-key").commit()
        val intent=android.content.Intent(c,SettingsActivity::class.java).putExtra("page","database")
        val life=org.robolectric.Robolectric.buildActivity(SettingsActivity::class.java,intent).setup()
        try {
            life.get().findViewById<android.view.View>(R.id.database_separate).performClick()
            val dialog=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
            assertTrue(dialog.findViewById<android.widget.TextView>(android.R.id.message).text.contains("旧文件和旧密钥仍保存在本机"))
            dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).performClick()
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            assertEquals("keep-old",file.readText());assertEquals("",DatabaseLocation(c).active().id)
            assertEquals("unreadable-old-key",c.getSharedPreferences("loop-vault",0).getString("database",null))
        } finally { life.pause().stop().destroy() }
    }
}
