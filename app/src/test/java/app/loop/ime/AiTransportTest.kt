package app.loop.ime

import android.app.Application
import android.os.Looper
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URL
import java.security.cert.Certificate
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[37],application=Application::class)
class AiTransportTest {
    private class Reply(url: URL,private val status: Int,private val body: String,private val timeout: Boolean=false): HttpsURLConnection(url) {
        val sent=ByteArrayOutputStream()
        override fun connect() {}
        override fun disconnect() {}
        override fun usingProxy()=false
        override fun getCipherSuite()="TLS_TEST"
        override fun getLocalCertificates(): Array<Certificate>?=null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
        override fun getResponseCode(): Int { if(timeout)throw java.net.SocketTimeoutException();return status }
        override fun getOutputStream()=sent
        override fun getInputStream()=ByteArrayInputStream(body.toByteArray())
        override fun getErrorStream()=ByteArrayInputStream(body.toByteArray())
    }
    private val success="""{"choices":[{"finish_reason":"stop","message":{"content":"{\"ok\":true}"}}]}"""
    @Test fun officialKeyIsVerifiedBeforeFlashGenerationWithSavedSnapshot() {
        val requests=CopyOnWriteArrayList<Reply>();val stages=mutableListOf<String>();var result=""
        val client=AiClient(RuntimeEnvironment.getApplication()) { url -> Reply(url,200,if(url.path=="/models")"""{"data":[{"id":"deepseek-flash"}]}""" else success).also { requests+=it } }
        val call=client.test(AiProtocol.deepSeek("sk-fixed-snapshot"),{ result=it },{ stages+=it })
        call.future!!.get(5,TimeUnit.SECONDS);shadowOf(Looper.getMainLooper()).idle()
        assertTrue(result.startsWith("连接成功"));assertEquals(2,requests.size)
        assertEquals("GET",requests[0].requestMethod);assertEquals("/models",requests[0].url.path)
        assertEquals("POST",requests[1].requestMethod)
        for(r in requests)assertEquals("Bearer sk-fixed-snapshot",r.getRequestProperty("Authorization"))
        val body=JSONObject(requests[1].sent.toString("UTF-8"));assertEquals(256,body.getInt("max_tokens"));assertEquals("disabled",body.getJSONObject("thinking").getString("type"))
        assertTrue(stages.any { it.contains("Key 鉴权通过") });assertFalse(requests[1].sent.toString().contains("sk-fixed-snapshot"))
    }
    @Test fun badKeyStopsBeforeGenerationAndDoesNotEchoServerSecrets() {
        val requests=CopyOnWriteArrayList<Reply>();var result=""
        val client=AiClient(RuntimeEnvironment.getApplication()) { url -> Reply(url,401,"""{"error":{"message":"sk-test private data"}}""").also { requests+=it } }
        client.test(AiProtocol.deepSeek("sk-test"),{ result=it }).future!!.get(5,TimeUnit.SECONDS);shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1,requests.size);assertTrue(result.contains("HTTP 401"));assertTrue(result.contains("Key 已保留"));assertFalse(result.contains("sk-test"))
    }
    @Test fun modelTimeoutDoesNotMisreportAValidKeyAsInvalid() {
        var result=""
        val client=AiClient(RuntimeEnvironment.getApplication()) { url -> Reply(url,200,"""{"data":[]}""",url.path!="/models") }
        client.test(AiProtocol.deepSeek("sk-test"),{ result=it }).future!!.get(5,TimeUnit.SECONDS);shadowOf(Looper.getMainLooper()).idle()
        assertTrue(result.contains("Key 鉴权通过"));assertTrue(result.contains("超时"));assertFalse(result.contains("Key 无效"))
    }
    @Test fun customEndpointIsTestedWithoutSendingItsKeyToDeepSeek() {
        val requests=CopyOnWriteArrayList<Reply>();var result=""
        val client=AiClient(RuntimeEnvironment.getApplication()) { url -> Reply(url,200,success).also { requests+=it } }
        client.test(AiProfile("https://example.com/v1/chat/completions","local-model","custom-secret"),{ result=it }).future!!.get(5,TimeUnit.SECONDS);shadowOf(Looper.getMainLooper()).idle()
        assertTrue(result.startsWith("连接成功"));assertEquals(1,requests.size);assertEquals("example.com",requests[0].url.host)
    }
    @Test fun nineKeyUsesExistingProfileKeyWithCodeContextAndProtectedHints() {
        val c=RuntimeEnvironment.getApplication();Prefs(c).set("cloud",true)
        val replies=CopyOnWriteArrayList<Reply>();var result=emptyList<NineKeyCandidate>()
        val body=JSONObject().put("choices",org.json.JSONArray().put(JSONObject().put("finish_reason","stop").put("message",
            JSONObject().put("content","""{"candidates":[{"text":"你好","pinyin":"ni hao"},{"text":"北京","pinyin":"ni hao"}]}""")))).toString()
        val client=AiClient(c,nineKeyData={ AiProtocol.deepSeek("sk-current-key") to listOf(
            Term("你好","nihao",1,true,"choice"),Term("倪浩","nihao",1,false,"contacts")) }) { url -> Reply(url,200,body).also { replies+=it } }
        client.nineKey(NineKeyQuery("64426","早上好")) { result=it.getOrThrow() }.future!!.get(5,TimeUnit.SECONDS)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(NineKeyCandidate("你好","nihao")),result)
        val reply=replies.single();assertEquals("Bearer sk-current-key",reply.getRequestProperty("Authorization"))
        val payload=JSONObject(reply.sent.toString("UTF-8"));assertEquals("deepseek-flash",payload.getString("model"))
        assertEquals("disabled",payload.getJSONObject("thinking").getString("type"))
        val user=JSONObject(payload.getJSONArray("messages").getJSONObject(1).getString("content"))
        assertEquals("64426",user.getString("code"));assertEquals("早上好",user.getString("context"))
        assertFalse(reply.sent.toString("UTF-8").contains("倪浩"));assertFalse(reply.sent.toString("UTF-8").contains("sk-current-key"))
    }
    @Test fun nineKeyDoesNotOpenConnectionWhenCloudOrFeatureIsOffOrInputIsPrivate() {
        val c=RuntimeEnvironment.getApplication();var opened=0;var loaded=0
        val client=AiClient(c,nineKeyData={ loaded++;AiProtocol.deepSeek("test") to emptyList() }) { url -> opened++;Reply(url,200,success) }
        for(mode in 0..2) {
            Prefs(c).set("cloud",mode!=0);Prefs(c).set("ai_t9",mode!=1);Prefs(c).set("private",mode==2)
            client.nineKey(NineKeyQuery("64","")) {}.future!!.get(5,TimeUnit.SECONDS)
            shadowOf(Looper.getMainLooper()).idle()
        }
        assertEquals(0,loaded);assertEquals(0,opened)
    }
    @Test fun unreadableDatabaseStillProducesValidatedCandidatesWithoutContextOrPersonalTerms() {
        val c=RuntimeEnvironment.getApplication();Prefs(c).set("cloud",true)
        val replies=CopyOnWriteArrayList<Reply>();var result=emptyList<NineKeyCandidate>()
        val response=JSONObject().put("choices",org.json.JSONArray().put(JSONObject().put("finish_reason","stop").put("message",
            JSONObject().put("content","""{"candidates":[{"text":"你好","pinyin":"ni hao"},{"text":"北京","pinyin":"ni hao"}]}""")))).toString()
        val client=AiClient(c,candidateStore={ throw DatabaseUnavailable(net.zetetic.database.sqlcipher.SQLiteNotADatabaseException("file is not a database")) },
            candidateProfile={ AiProtocol.deepSeek("sk-existing-key") }) { url -> Reply(url,200,response).also { replies+=it } }
        client.nineKey(NineKeyQuery("64426","联系联系人王小明 SECRET_CONTEXT")) { result=it.getOrThrow() }.future!!.get(5,TimeUnit.SECONDS)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(NineKeyCandidate("你好","nihao")),result)
        val reply=replies.single();assertEquals("Bearer sk-existing-key",reply.getRequestProperty("Authorization"))
        val sent=reply.sent.toString("UTF-8")
        val user=JSONObject(JSONObject(sent).getJSONArray("messages").getJSONObject(1).getString("content"))
        assertEquals("64426",user.getString("code"));assertEquals("",user.getString("context"));assertEquals(0,user.getJSONArray("terms").length())
        assertFalse(sent.contains("王小明"));assertFalse(sent.contains("SECRET_CONTEXT"))
    }
    @Test fun readableLocalOnlyContextRemainsBlockedAndDoesNotTriggerBasicFallback()=StoreFixture().use { f ->
        val c=RuntimeEnvironment.getApplication();Prefs(c).set("cloud",true)
        f.store.addTerm("王小明",source="contacts",origin="contact:1")
        var opened=0;var result: Result<List<NineKeyCandidate>>?=null
        val client=AiClient(c,candidateStore={ f.store },candidateProfile={ AiProtocol.deepSeek("key") }) { url -> opened++;Reply(url,200,success) }
        client.nineKey(NineKeyQuery("64426","联系王小明")) { result=it }.future!!.get(5,TimeUnit.SECONDS)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(result!!.isFailure);assertEquals(0,opened)
    }
    @Test fun fallbackCannotBypassPolicyRevocationOrDeviceLock() {
        val c=RuntimeEnvironment.getApplication();var opened=0
        val client=AiClient(c,candidateStore={ Prefs(c).set("cloud",false);error("database unavailable") },
            candidateProfile={ AiProtocol.deepSeek("key") }) { url -> opened++;Reply(url,200,success) }
        Prefs(c).set("cloud",true)
        client.nineKey(NineKeyQuery("64426","private")) {}.future!!.get(5,TimeUnit.SECONDS)
        assertEquals(0,opened)
        Prefs(c).set("cloud",true)
        val lock=shadowOf(c.getSystemService(android.app.KeyguardManager::class.java))
        lock.setIsDeviceLocked(true)
        try { client.nineKey(NineKeyQuery("64426","private")) {}.future!!.get(5,TimeUnit.SECONDS);assertEquals(0,opened) }
        finally { lock.setIsDeviceLocked(false) }
    }
}
