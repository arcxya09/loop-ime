package app.loop.ime

import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Portable, password-encrypted API configuration. Never depends on a removed app's Keystore. */
object ConnectionBackup {
    private val magic="LOOPAI01".toByteArray(Charsets.US_ASCII)
    private fun key(password: CharArray,salt: ByteArray): SecretKeySpec {
        require(password.size>=8) { "备份密码至少 8 位" }
        val spec=PBEKeySpec(password,salt,210000,256)
        return try { SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded,"AES") } finally { spec.clearPassword() }
    }
    fun export(p: AiProfile,output: OutputStream,password: CharArray) {
        val profile=AiProfiles.validate(p)
        val salt=ByteArray(16).also { SecureRandom().nextBytes(it) };val iv=ByteArray(12).also { SecureRandom().nextBytes(it) }
        val header=magic+salt+iv
        val plain=AiProfiles.encode(profile).put("format",1).toString().toByteArray(Charsets.UTF_8)
        try {
            val c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,key(password,salt),GCMParameterSpec(128,iv));c.updateAAD(header)
            output.write(header);output.write(c.doFinal(plain));output.flush()
        } finally { plain.fill(0) }
    }
    fun restore(input: InputStream,password: CharArray): AiProfile {
        val bytes=input.readNBytes(65537)
        require(bytes.size in 52..65536 && bytes.copyOfRange(0,8).contentEquals(magic)) { "不是有效的 Loop AI 配置备份" }
        val plain=try {
            val c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,key(password,bytes.copyOfRange(8,24)),GCMParameterSpec(128,bytes.copyOfRange(24,36)));c.updateAAD(bytes.copyOfRange(0,36));c.doFinal(bytes.copyOfRange(36,bytes.size))
        } catch(_: javax.crypto.AEADBadTagException) { error("备份密码不正确或文件已损坏，原配置未修改") }
        return try {
            val j=JSONObject(plain.toString(Charsets.UTF_8));require(j.getInt("format")==1) { "请升级 Loop 后恢复此备份" };AiProfiles.validate(AiProfiles.decode(j))
        } finally { plain.fill(0);bytes.fill(0) }
    }
    private val bundleMagic="LOOPAI02".toByteArray(Charsets.US_ASCII)
    private val booleanSettings=setOf("private","cloud","memory","memory_cloud","cloud_learning","learning","ai_t9","predict","autocorrect","punctuation","chinese_t9","speech_cloud","speech_cloud_terms","rotation","clipboard","quick_clip","otp_clip","cursor_gesture")
    fun exportAll(c: android.content.Context,output: OutputStream,password: CharArray,vault: Vault=Vault(c)) {
        val profiles=AiProfiles(c,vault);val p=JSONObject()
        val names=profiles.names();require(names.size<=32) { "配置数量超出备份限制" }
        names.forEach { name -> profiles.load(name)?.let { p.put(name,AiProfiles.encode(AiProfiles.validate(it))) } }
        val speech=JSONObject()
        CloudAsrProfile.REGIONS.forEach { region -> CloudSpeechSettings(c,vault).profile(region)?.let { speech.put(region,it.key) } }
        require(p.length()>0 || speech.length()>0) { "请先保存文本 AI 或百炼语音配置" }
        val prefs=Prefs(c);val settings=JSONObject();booleanSettings.forEach { k -> if(prefs.store.contains(k))settings.put(k,prefs.flag(k)) }
        settings.put("keyboard_height",prefs.keyboardHeight.value)
        settings.put("one_hand",prefs.text("one_hand","off")).put("tool_order",ToolCatalog.order(prefs.text("tool_order")).joinToString(","))
        settings.put("correction_mode",prefs.correctionMode.name).put("local_apps",prefs.text("local_apps"))
        val body=JSONObject().put("format",2).put("profiles",p).put("selected",prefs.text("profile",AiProtocol.DEFAULT_PROFILE))
            .put("speech",speech).put("speech_region",CloudSpeechSettings(c,vault).region()).put("settings",settings)
        val plain=body.toString().toByteArray(Charsets.UTF_8)
        try {
            require(plain.size<=1024*1024) { "配置备份过大" }
            val salt=ByteArray(16).also { SecureRandom().nextBytes(it) };val iv=ByteArray(12).also { SecureRandom().nextBytes(it) };val header=bundleMagic+salt+iv
            val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key(password,salt),GCMParameterSpec(128,iv));cipher.updateAAD(header)
            output.write(header);output.write(cipher.doFinal(plain));output.flush()
        } finally { plain.fill(0) }
    }
    fun restoreAll(c: android.content.Context,input: InputStream,password: CharArray,vault: Vault=Vault(c)): String {
        val bytes=input.readNBytes(1024*1024+53)
        require(bytes.size in 52..(1024*1024+52)) { "配置备份大小无效" }
        try {
            if(bytes.copyOfRange(0,8).contentEquals(magic)) {
                val p=restore(bytes.inputStream(),password);AiProfiles(c,vault).save(if(AiProtocol.isDeepSeek(p))AiProtocol.DEFAULT_PROFILE else "restored",p)
                return "已恢复旧版文本 AI 配置。"
            }
            require(bytes.copyOfRange(0,8).contentEquals(bundleMagic)) { "不是有效的 Loop 配置备份" }
            val plain=try {
                val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key(password,bytes.copyOfRange(8,24)),GCMParameterSpec(128,bytes.copyOfRange(24,36)))
                cipher.updateAAD(bytes.copyOfRange(0,36));cipher.doFinal(bytes.copyOfRange(36,bytes.size))
            } catch(_: javax.crypto.AEADBadTagException) { error("备份密码不正确或文件已损坏，原配置未修改") }
            try {
                val j=JSONObject(plain.toString(Charsets.UTF_8));require(j.getInt("format")==2)
                val profiles=j.getJSONObject("profiles");val speech=j.getJSONObject("speech");val settings=j.getJSONObject("settings")
                require(profiles.length()<=32 && speech.length()<=2)
                val records=mutableMapOf<String,ByteArray>()
                try {
                    profiles.keys().forEach { name -> require(name.matches(Regex("[A-Za-z0-9_-]{1,32}")));records["api.$name"]=AiProfiles.encode(AiProfiles.validate(AiProfiles.decode(profiles.getJSONObject(name)))).toString().toByteArray(Charsets.UTF_8) }
                    speech.keys().forEach { region -> val p=CloudAsrProfile(speech.getString(region),region);records["speech.bailian.$region"]=JSONObject().put("key",p.key).toString().toByteArray(Charsets.UTF_8) }
                    val selected=j.getString("selected");require(selected.matches(Regex("[A-Za-z0-9_-]{1,32}")))
                    val region=j.getString("speech_region");require(region in CloudAsrProfile.REGIONS)
                    val height=KeyboardHeight.from(settings.getString("keyboard_height"))
                    val mode=settings.optString("correction_mode").takeIf { it.isNotBlank() }?.also { value -> require(CorrectionMode.entries.any { it.name==value }) }
                    val localApps=settings.optString("local_apps").also { value -> require(value.length<=20000 && value.split('\n').all { it.isEmpty() || it.matches(Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")) }) }
                    val booleans=booleanSettings.filter { settings.has(it) }.associateWith { require(settings.get(it) is Boolean);settings.getBoolean(it) }
                    val hand=settings.optString("one_hand","off").also { require(it in setOf("off","left","right")) }
                    val tools=ToolCatalog.order(settings.optString("tool_order","")).joinToString(",")
                    // Authentication and every field validation finish before the atomic encrypted write.
                    vault.putMany(records)
                    val prefs=Prefs(c);val edit=prefs.store.edit().putString("profile",selected).putString("profiles",AiProfiles(c,vault).names().joinToString(","))
                        .putString("speech_region",region).putString("keyboard_height",height.value)
                        .putString("one_hand",hand).putString("tool_order",tools)
                    booleans.forEach { (k,v)->edit.putBoolean(k,v) }
                    if(mode!=null)edit.putString("correction_mode",mode) else edit.remove("correction_mode")
                    if(settings.has("local_apps"))edit.putString("local_apps",localApps)
                    check(edit.commit()) { "Key 已恢复，设置索引写入失败；请重新打开设置" }
                    StoreEvents.changed()
                    return "已恢复 ${profiles.length()} 个文本 AI 配置、${speech.length()} 个百炼语音配置及输入设置。"
                } finally { records.values.forEach { it.fill(0) } }
            } finally { plain.fill(0) }
        } finally { bytes.fill(0) }
    }
}
