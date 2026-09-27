package app.loop.ime

import android.content.Context
import org.json.JSONObject

/** Encrypted records are authoritative; the selected-name preference is only an index. */
class AiProfiles(c: Context, private val vault: Vault=Vault(c)) {
    private val prefs=Prefs(c)
    fun names(): List<String> = vault.names("api.").map { it.removePrefix("api.") }
    fun load(name: String): AiProfile? {
        val bytes=vault.get("api.$name") ?: return null
        return try { decode(JSONObject(bytes.toString(Charsets.UTF_8))) } finally { bytes.fill(0) }
    }
    fun current(): AiProfile {
        val candidates=(listOf(prefs.text("profile",AiProtocol.DEFAULT_PROFILE),AiProtocol.DEFAULT_PROFILE,"default")+names()).distinct()
        for(name in candidates)load(name)?.let { return AiProtocol.upgradeOfficialAlias(it) }
        return AiProtocol.deepSeek()
    }
    fun deepSeek(): AiProfile {
        val saved=load(AiProtocol.DEFAULT_PROFILE)
        if(saved!=null && AiProtocol.isDeepSeek(saved) && saved.key.isNotBlank())return AiProtocol.deepSeek(saved.key)
        return AiProtocol.defaultProfile(saved,current())
    }
    fun saveDeepSeek(raw: String): AiProfile {
        // Empty edits never clear a usable saved secret.
        val value=if(raw.isBlank())deepSeek().key else raw
        val profile=AiProtocol.deepSeek(AiProtocol.normalizeKey(value))
        save(AiProtocol.DEFAULT_PROFILE,profile);return profile
    }
    fun save(name: String,profile: AiProfile) {
        require(name.matches(Regex("[A-Za-z0-9_-]{1,32}"))) { "配置名限字母、数字、下划线" }
        val p=validate(profile)
        val bytes=encode(p).toString().toByteArray(Charsets.UTF_8)
        try { vault.put("api.$name",bytes) } finally { bytes.fill(0) }
        // Commit both index fields together after the encrypted record has reached disk.
        check(prefs.store.edit().putString("profile",name).putString("profiles",names().joinToString(",")).commit()) { "配置已加密保存，索引更新失败；重新打开设置即可恢复" }
    }
    companion object {
        fun encode(p: AiProfile)=JSONObject().put("url",p.url).put("model",p.model).put("key",p.key).put("headers",p.headers)
        fun decode(j: JSONObject)=AiProfile(j.getString("url"),j.getString("model"),j.getString("key"),j.optString("headers","{}"))
        fun headers(raw: String): Map<String,String> {
            val j=JSONObject(raw.ifBlank { "{}" });require(j.length()<=12) { "附加请求头过多" }
            return j.keys().asSequence().associateWith { k ->
                require(k.matches(Regex("[A-Za-z0-9-]{1,80}")) && k.lowercase() !in setOf("host","content-length","connection","transfer-encoding","content-type","authorization")) { "不允许的请求头" }
                j.getString(k).also { require(it.length<=2000 && '\n' !in it && '\r' !in it) { "请求头格式无效" } }
            }
        }
        fun validate(p: AiProfile): AiProfile {
            TextRules.safeEndpoint(p.url);require(p.model.isNotBlank() && p.model.length<=200) { "请填写有效模型名" };headers(p.headers)
            val key=if(p.key.isBlank() && !AiProtocol.isDeepSeek(p))"" else AiProtocol.normalizeKey(p.key)
            return p.copy(url=p.url.trim(),model=p.model.trim(),key=key)
        }
    }
}
