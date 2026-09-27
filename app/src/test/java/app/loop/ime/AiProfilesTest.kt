package app.loop.ime

import android.app.Application
import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import javax.crypto.spec.SecretKeySpec
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[37],application=Application::class)
class AiProfilesTest {
    private val c get()=RuntimeEnvironment.getApplication()
    private fun vault(seed: Int=1)=Vault(c) { _,_ -> SecretKeySpec(ByteArray(32) { seed.toByte() },"AES") }
    @Test fun savedKeySurvivesRepositoryRecreationAndMissingPreferenceIndex() {
        val profiles=AiProfiles(c,vault());profiles.save("default",AiProtocol.deepSeek("sk-retained-test-key"))
        c.getSharedPreferences("loop-preferences",Context.MODE_PRIVATE).edit().clear().commit()
        assertEquals("sk-retained-test-key",AiProfiles(c,vault()).current().key)
        assertEquals("sk-retained-test-key",AiProfiles(c,vault()).deepSeek().key)
        assertFalse(c.getSharedPreferences("loop-vault",Context.MODE_PRIVATE).all.toString().contains("sk-retained"))
    }
    @Test fun blankSavePreservesExistingSecretAndWritesIndexSynchronously() {
        val profiles=AiProfiles(c,vault());profiles.saveDeepSeek("sk-existing")
        assertEquals("sk-existing",AiProfiles(c,vault()).saveDeepSeek(" ").key)
        assertEquals("deepseek",Prefs(c).text("profile"))
        assertEquals(listOf("deepseek"),profiles.names())
    }
    @Test fun unreadableEncryptedRecordIsReportedAndNeverOverwrittenOrRegeneratedOnRead() {
        val good=vault();AiProfiles(c,good).saveDeepSeek("sk-preserve")
        val before=c.getSharedPreferences("loop-vault",Context.MODE_PRIVATE).all.toMap()
        var creations=0
        val unavailable=Vault(c) { _,create -> if(create)creations++;error("key unavailable") }
        try { AiProfiles(c,unavailable).deepSeek();fail("Unreadable key became absent") } catch(_: IllegalStateException) {}
        assertEquals(0,creations);assertEquals(before,c.getSharedPreferences("loop-vault",Context.MODE_PRIVATE).all)
    }
    @Test fun customProviderKeyCannotBecomeDefaultDeepSeekKey() {
        AiProfiles(c,vault()).save("company",AiProfile("https://example.com/chat/completions","model","company-secret"))
        assertEquals("",AiProfiles(c,vault()).deepSeek().key)
        assertEquals("company-secret",AiProfiles(c,vault()).current().key)
    }
    @Test fun encryptedExportRestoresAfterAppStorageAndWrappingKeyHaveBeenRemoved() {
        val profiles=AiProfiles(c,vault());profiles.saveDeepSeek("sk-portable-secret")
        val out=ByteArrayOutputStream();ConnectionBackup.export(profiles.current(),out,"backup-password".toCharArray())
        val bytes=out.toByteArray();assertFalse(bytes.toString(Charsets.UTF_8).contains("sk-portable-secret"))
        c.getSharedPreferences("loop-vault",Context.MODE_PRIVATE).edit().clear().commit()
        c.getSharedPreferences("loop-preferences",Context.MODE_PRIVATE).edit().clear().commit()
        val restored=ConnectionBackup.restore(ByteArrayInputStream(bytes),"backup-password".toCharArray())
        AiProfiles(c,vault(2)).save("deepseek",restored)
        assertEquals("sk-portable-secret",AiProfiles(c,vault(2)).current().key)
    }
    @Test fun wrongPasswordOrModifiedBackupCannotReplaceSavedConnection() {
        val profiles=AiProfiles(c,vault());profiles.saveDeepSeek("sk-current")
        val out=ByteArrayOutputStream();ConnectionBackup.export(AiProtocol.deepSeek("sk-backup"),out,"backup-password".toCharArray())
        val good=out.toByteArray();val tampered=good.clone().apply { this[lastIndex]=(this[lastIndex].toInt() xor 1).toByte() }
        for((bytes,password) in listOf(good to "wrong-password",tampered to "backup-password",good.copyOf(good.size-2) to "backup-password")) {
            try { profiles.save("deepseek",ConnectionBackup.restore(ByteArrayInputStream(bytes),password.toCharArray()));fail("Invalid backup accepted") } catch(_: IllegalStateException) {}
            assertEquals("sk-current",profiles.current().key)
        }
    }
}
