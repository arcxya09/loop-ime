package app.loop.ime

import org.json.JSONArray
import org.json.JSONObject
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

data class AiProfile(val url: String, val model: String, val key: String, val headers: String = "{}")

/** Provider defaults and wire validation shared by real requests and connection tests. */
object AiProtocol {
    const val DEFAULT_PROFILE="deepseek"
    const val DEEPSEEK_URL="https://api.deepseek.com/chat/completions"
    const val DEEPSEEK_MODEL="deepseek-flash"

    fun deepSeek(key: String="")=AiProfile(DEEPSEEK_URL,DEEPSEEK_MODEL,key)
    fun isDeepSeek(p: AiProfile): Boolean = runCatching {
        val u=TextRules.safeEndpoint(p.url)
        u.host.equals("api.deepseek.com",ignoreCase=true) && u.port in setOf(-1,443)
    }.getOrDefault(false)

    fun normalizeKey(raw: String): String {
        val key=raw.trim().replaceFirst(Regex("^Bearer\\s+",RegexOption.IGNORE_CASE),"").trim()
        require(key.isNotEmpty()) { "请先填写 API Key" }
        require(key.length<=2048 && key.all { it.code in 33..126 }) { "API Key 含空格或异常字符，请重新完整粘贴密钥" }
        return key
    }

    // Reuse keys only from the official host. A saved key for another provider stays with that provider.
    fun defaultProfile(saved: AiProfile?, active: AiProfile?): AiProfile =
        deepSeek(listOf(active,saved).firstOrNull { it!=null && isDeepSeek(it) && it.key.isNotBlank() }?.key.orEmpty())

    fun upgradeOfficialAlias(p: AiProfile): AiProfile =
        if(isDeepSeek(p) && p.model in setOf("","deepseek-chat","deepseek-reasoner","deepseek-v4-flash","deepseek-flash"))
            p.copy(url=DEEPSEEK_URL,model=DEEPSEEK_MODEL) else p

    fun body(p: AiProfile,system: String,user: JSONObject,connectionTest: Boolean=false): JSONObject {
        val messages=JSONArray().put(JSONObject().put("role","system").put("content",system))
            .put(JSONObject().put("role","user").put("content",user.toString()))
        return JSONObject().put("model",p.model).put("messages",messages).put("temperature",0.1)
            .put("max_tokens",if(connectionTest)256 else 1024).put("stream",false).apply {
                if(isDeepSeek(p)) {
                    // DeepSeek Flash enables thinking by default. IME correction needs the final text promptly.
                    put("thinking",JSONObject().put("type","disabled"))
                    put("response_format",JSONObject().put("type","json_object"))
                }
            }
    }

    fun content(raw: String): String {
        val json=try { JSONObject(raw) } catch(_: Exception) { error("API 返回格式无效，请检查接口地址") }
        val choice=json.optJSONArray("choices")?.optJSONObject(0) ?: error("API 未返回有效的模型结果")
        when(choice.optString("finish_reason")) {
            "length" -> error("模型结果被截断，本次不应用修改，请重试")
            "content_filter" -> error("本次请求被服务商过滤")
            "insufficient_system_resource","aborted" -> error("模型服务繁忙，本次结果不完整，请稍后重试")
        }
        val message=choice.optJSONObject("message") ?: error("API 未返回有效的模型正文")
        val content=message.opt("content") as? String ?: error("模型未返回正文，请确认关闭思考模式后重试")
        return content.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            .also { require(it.isNotEmpty()) { "模型返回了空正文，请稍后重试" } }
    }

    fun httpError(status: Int,raw: String): String {
        // Only examine a known error code; do not echo arbitrary server messages, user text or keys.
        val code=runCatching { JSONObject(raw).optJSONObject("error")?.optString("code") }.getOrNull()
        val message=when {
            code in setOf("model_not_found","invalid_model") -> "模型不可用，请检查自定义模型名称"
            status==401 -> "API Key 无效或已失效，请使用对应开放平台的 API 密钥"
            status==402 || code=="insufficient_balance" -> "API 账户余额不足，请充值后重试"
            status==403 -> "当前 Key 没有访问权限，请检查模型授权或访问限制"
            status==404 -> "API 地址或模型不存在，请检查自定义设置"
            status==429 -> "请求频率或账户额度受限，请稍后重试"
            status in 500..599 -> "API 服务暂时不可用，请稍后重试"
            status in 300..399 -> "API 地址发生重定向，请填写最终 HTTPS 接口地址"
            status in 400..499 -> "API 拒绝了请求参数，请检查自定义接口配置"
            else -> "API 返回异常状态"
        }
        return "$message（HTTP $status）"
    }

    fun failure(t: Throwable): String = when(t) {
        is SocketTimeoutException -> "连接超时，请检查网络后重试"
        is UnknownHostException -> "无法解析 API 域名，请检查网络和 DNS"
        is ConnectException -> "无法连接 API 服务，请检查网络"
        is SSLException -> "无法验证服务器证书，请检查手机时间和网络"
        else -> t.message?.takeIf { it.isNotBlank() }?.take(160) ?: "连接失败，请稍后重试"
    }
}
