package app.loop.ime

import android.app.Application
import android.app.KeyguardManager
import android.content.Context
import android.os.UserManager
import android.security.keystore.UserNotAuthenticatedException
import android.util.Base64
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/** Real AES-GCM and Android preferences, with an old key that reproduces the reported auth failure. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[37],application=Application::class)
class VaultMigrationTest {
    private val c get()=RuntimeEnvironment.getApplication()
    private val prefs get()=c.getSharedPreferences("loop-vault",Context.MODE_PRIVATE)
    private val legacy=SecretKeySpec(ByteArray(32) { 1 },"AES")
    private val current=SecretKeySpec(ByteArray(32) { 2 },"AES")
    private fun provider(alias: String,create: Boolean): SecretKey=when(alias) {
        Vault.LEGACY_ALIAS -> { assertFalse("Legacy alias must never be generated",create);legacy }
        Vault.CURRENT_ALIAS -> current
        else -> error("Unexpected alias")
    }
    private fun oldRecord(name: String,value: ByteArray): String {
        val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,legacy);cipher.updateAAD(name.toByteArray())
        val raw=Base64.encodeToString(cipher.iv+cipher.doFinal(value),Base64.NO_WRAP)
        assertTrue(prefs.edit().putString(name,raw).commit());return raw
    }
    @Test fun newKeyPolicyNeedsNoPerUseAuthenticationOrKeystoreUnlockToken() {
        val spec=Vault.currentKeySpec()
        assertFalse(spec.isUserAuthenticationRequired);assertFalse(spec.isUnlockedDeviceRequired)
        assertTrue(spec.isRandomizedEncryptionRequired);assertEquals(256,spec.keySize)
        assertNotEquals(Vault.LEGACY_ALIAS,spec.keystoreAlias)
    }
    @Test fun previouslyUnauthenticatedKeyCannotBlockExplicitlySavingANewApiKey() {
        oldRecord("api.deepseek",AiProfiles.encode(AiProtocol.deepSeek("sk-old")).toString().toByteArray())
        val db=oldRecord("database",ByteArray(32) { 9 })
        val oldExtra=oldRecord("api.company","company private config".toByteArray())
        val calls=mutableListOf<Pair<String,Boolean>>()
        val vault=Vault(c) { alias,create ->
            calls+=alias to create
            if(alias==Vault.LEGACY_ALIAS)throw UserNotAuthenticatedException()
            current
        }
        try { AiProfiles(c,vault).deepSeek();fail("Old key was not rejected") } catch(e: IllegalStateException) { assertTrue(e.message!!.contains("重新粘贴 Key")) }
        val beforeSave=calls.size
        assertEquals("sk-new",AiProfiles(c,vault).saveDeepSeek("sk-new").key)
        assertTrue(calls.drop(beforeSave).all { it.first==Vault.CURRENT_ALIAS })
        assertEquals("sk-new",AiProfiles(c,Vault(c) { alias,_ -> check(alias==Vault.CURRENT_ALIAS);current }).current().key)
        assertEquals(db,prefs.getString("database",null));assertEquals(oldExtra,prefs.getString("api.company",null))
        assertFalse(prefs.all.toString().contains("sk-new"))
    }
    @Test fun readableOldApiAndDatabaseKeysMigrateWithoutChangingTheirValues() {
        val api=AiProfiles.encode(AiProtocol.deepSeek("sk-migrate")).toString().toByteArray();val database=ByteArray(32) { (it+3).toByte() }
        oldRecord("api.deepseek",api);oldRecord("database",database)
        val vault=Vault(c,::provider)
        assertArrayEquals(api,vault.get("api.deepseek"));assertArrayEquals(database,vault.databaseKey())
        assertTrue(listOf("api.deepseek","database").all { prefs.getString(it,"")!!.startsWith(Vault.CURRENT_PREFIX) })
        val newOnly=Vault(c) { alias,create -> assertEquals(Vault.CURRENT_ALIAS,alias);assertFalse(create);current }
        assertEquals("sk-migrate",AiProfiles(c,newOnly).current().key);assertArrayEquals(database,newOnly.databaseKey())
    }
    @Test fun failedMigrationLeavesLegacyCiphertextReadableAndRetriesLater() {
        val value="retain readable value".toByteArray();val original=oldRecord("api.default",value)
        val unavailable=Vault(c) { alias,create -> if(alias==Vault.CURRENT_ALIAS)throw UserNotAuthenticatedException();provider(alias,create) }
        assertArrayEquals(value,unavailable.get("api.default"));assertEquals(original,prefs.getString("api.default",null))
        assertArrayEquals(value,Vault(c,::provider).get("api.default"));assertTrue(prefs.getString("api.default","")!!.startsWith(Vault.CURRENT_PREFIX))
    }
    @Test fun unreadableDatabaseIsNeverReplacedByAnEmptyOrNewDatabaseKey() {
        val original=oldRecord("database",ByteArray(32) { 7 });var creations=0
        val vault=Vault(c) { _,create -> if(create)creations++;throw UserNotAuthenticatedException() }
        try { vault.databaseKey();fail("Unreadable database key was replaced") } catch(e: IllegalStateException) { assertTrue(e.message!!.contains("原数据保留")) }
        assertEquals(0,creations);assertEquals(original,prefs.getString("database",null))
    }
    @Test fun failedNewEncryptionNeverWritesPlaintextOrOverwritesAnOldRecord() {
        val original=oldRecord("api.deepseek","old encrypted record".toByteArray())
        val vault=Vault(c) { _,_ -> throw UserNotAuthenticatedException() }
        try { vault.put("api.deepseek","sk-new-never-stored".toByteArray());fail("Encryption failure ignored") } catch(e: IllegalStateException) { assertTrue(e.message!!.contains("本次未保存")) }
        assertEquals(original,prefs.getString("api.deepseek",null));assertFalse(prefs.all.toString().contains("sk-new-never-stored"))
    }
    @Test fun lockedAndBeforeFirstUnlockAccessAreRejectedBeforeUsingTheKey() {
        val vault=Vault(c,::provider);vault.put("api.test","secret".toByteArray())
        var calls=0;val guarded=Vault(c) { alias,create -> calls++;provider(alias,create) }
        val keyguard=shadowOf(c.getSystemService(KeyguardManager::class.java));val users=shadowOf(c.getSystemService(UserManager::class.java))
        fun rejected() {
            try { guarded.get("api.test");fail("Locked read allowed") } catch(e: IllegalStateException) { assertTrue(e.message!!.contains("解锁")) }
            try { guarded.put("api.test","changed".toByteArray());fail("Locked write allowed") } catch(e: IllegalStateException) { assertTrue(e.message!!.contains("解锁")) }
        }
        try {
            keyguard.setIsDeviceLocked(true);rejected();keyguard.setIsDeviceLocked(false)
            users.setUserUnlocked(false);rejected();assertEquals(0,calls)
        } finally { users.setUserUnlocked(true);keyguard.setIsDeviceLocked(false) }
        assertArrayEquals("secret".toByteArray(),vault.get("api.test"))
    }
    @Test fun recordsCannotBeReadUnderAnotherNameOrAnAlteredVersion() {
        val vault=Vault(c,::provider);vault.put("api.first","secret".toByteArray())
        val record=prefs.getString("api.first",null)!!
        prefs.edit().putString("api.second",record).commit()
        try { vault.get("api.second");fail("Record was not bound to its name") } catch(_: javax.crypto.AEADBadTagException) {}
        prefs.edit().putString("api.first",record.removePrefix(Vault.CURRENT_PREFIX)).commit()
        try { vault.get("api.first");fail("Version was not authenticated") } catch(_: javax.crypto.AEADBadTagException) {}
    }
}
