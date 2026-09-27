package app.loop.ime

import android.app.KeyguardManager
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.UserNotAuthenticatedException
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Versioned Keystore records. Keep the old alias until all old records are independently readable. */
class Vault(private val c: Context, private val keyProvider: ((String,Boolean)->SecretKey)?=null) {
    private val prefs=c.getSharedPreferences("loop-vault",Context.MODE_PRIVATE)
    private fun requireUnlocked() {
        check(LoopApp.unlocked(c) && !c.getSystemService(KeyguardManager::class.java).isDeviceLocked) { "请解锁手机后再读取或保存加密数据" }
    }
    private fun key(alias: String,create: Boolean): SecretKey {
        keyProvider?.let { return it(alias,create) }
        val ks=KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias,null) as? SecretKey)?.let { return it }
        check(create && alias==CURRENT_ALIAS) { "原加密密钥不可用，请导入备份；API Key 也可重新填写保存" }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").run {
            init(currentKeySpec());generateKey()
        }
    }
    fun names(prefix: String): List<String> = prefs.all.keys.filter { it.startsWith(prefix) }.sorted()
    internal fun removeIfSame(name: String, expected: ByteArray?): Boolean = synchronized(Vault::class.java) {
        if(expected!=null) {
            val current=get(name) ?: return@synchronized true
            val same=try { current.contentEquals(expected) } finally { current.fill(0) }
            if(!same)return@synchronized false
        }
        check(prefs.edit().remove(name).commit()) { "待保存记录清理失败" };true
    }
    fun put(name: String,value: ByteArray) = synchronized(Vault::class.java) { putCurrent(name,value) }
    internal fun putMany(values: Map<String,ByteArray>) = synchronized(Vault::class.java) {
        requireUnlocked()
        val wrappingKey=key(CURRENT_ALIAS,true)
        val sealed=values.mapValues { (name,plain) ->
            val cipher=Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE,wrappingKey);cipher.updateAAD((CURRENT_PREFIX+name).toByteArray(Charsets.UTF_8))
            CURRENT_PREFIX+Base64.encodeToString(cipher.iv+cipher.doFinal(plain),Base64.NO_WRAP)
        }
        requireUnlocked()
        val edit=prefs.edit();sealed.forEach { (name,value)->edit.putString(name,value) }
        check(edit.commit()) { "加密配置写入失败，原配置未删除" }
    }
    private fun putCurrent(name: String,value: ByteArray) {
        requireUnlocked()
        val sealed=try {
            val cipher=Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE,key(CURRENT_ALIAS,true));cipher.updateAAD((CURRENT_PREFIX+name).toByteArray(Charsets.UTF_8))
            CURRENT_PREFIX+Base64.encodeToString(cipher.iv+cipher.doFinal(value),Base64.NO_WRAP)
        } catch(e: Exception) { throw explain(e,name,legacy=false) }
        requireUnlocked()
        // Only publish after encryption succeeds. A failure never erases or recreates existing data.
        check(prefs.edit().putString(name,sealed).commit()) { "加密配置写入失败，请检查手机可用空间后重试" }
    }
    fun get(name: String): ByteArray? = synchronized(Vault::class.java) {
        requireUnlocked()
        val raw=prefs.getString(name,null) ?: return@synchronized null
        val current=raw.startsWith(CURRENT_PREFIX)
        val sealed=Base64.decode(if(current)raw.removePrefix(CURRENT_PREFIX) else raw,Base64.NO_WRAP)
        require(sealed.size>=28) { "已保存的加密配置损坏，请导入配置备份" }
        val plain=try {
            val cipher=Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE,key(if(current)CURRENT_ALIAS else LEGACY_ALIAS,false),GCMParameterSpec(128,sealed.copyOfRange(0,12)))
            cipher.updateAAD((if(current)CURRENT_PREFIX+name else name).toByteArray(Charsets.UTF_8))
            cipher.doFinal(sealed.copyOfRange(12,sealed.size))
        } catch(e: Exception) { throw explain(e,name,legacy=!current) }
        // A migration failure must not destroy a readable old value. Retry on its next successful read.
        // The shared lock also prevents migration from overwriting a concurrently saved newer value.
        if(!current)runCatching { putCurrent(name,plain) }
        plain
    }
    fun databaseKey(name: String="database",allowCreate: Boolean=!DatabaseLocation.hasData(java.io.File(c.noBackupFilesDir,"loop.db"))): ByteArray = synchronized(Vault::class.java) {
        // Never replace a database key that exists but cannot currently be decrypted.
        require(name=="database" || name.matches(Regex("database\\.[a-f0-9]{32}")))
        get(name)?.let { if(it.size!=32) { it.fill(0);error("数据库密钥长度异常，原记录保留") };return@synchronized it }
        check(allowCreate) { "旧数据库存在但密钥记录缺失，不会生成替代密钥；请进入数据库检查与恢复" }
        ByteArray(32).also { SecureRandom().nextBytes(it);putCurrent(name,it) }
    }
    private fun explain(error: Exception,name: String,legacy: Boolean): Exception {
        DiagnosticLog.failure(DiagnosticLog.Area.VAULT,error,"legacy_record" to if(legacy)1L else 0L,"database_record" to if(name=="database" || name.startsWith("database."))1L else 0L)
        if(generateSequence<Throwable>(error) { it.cause }.take(8).none { it is UserNotAuthenticatedException })return error
        val message=if(legacy && name.startsWith("api."))
            "旧版 Key 暂时无法解密，请重新粘贴 Key 保存，或导入 AI 配置备份；其他数据保留"
        else if(legacy)
            "旧版加密数据暂时无法解密，请用锁屏密码解锁后重试；原数据保留，也可恢复备份"
        else "系统安全存储暂时不可用，本次未保存，请解锁手机后重试"
        return IllegalStateException(message,error)
    }
    companion object {
        internal const val LEGACY_ALIAS="loop.wrap.v1"
        internal const val CURRENT_ALIAS="loop.wrap.v2"
        internal const val CURRENT_PREFIX="v2:"
        internal fun currentKeySpec(): KeyGenParameterSpec = KeyGenParameterSpec.Builder(CURRENT_ALIAS,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .setUserAuthenticationRequired(false)
            .setUnlockedDeviceRequired(false)
            .build()
    }
}
