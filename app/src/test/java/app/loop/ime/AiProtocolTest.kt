package app.loop.ime

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AiProtocolTest {
    @Test fun flashRequestDisablesDefaultThinkingAndReservesEnoughOutput() {
        val p=AiProtocol.deepSeek("test-secret")
        val body=AiProtocol.body(p,"Return JSON only",JSONObject().put("text","明天开会"))
        assertEquals("https://api.deepseek.com/chat/completions",p.url)
        assertEquals("deepseek-flash",body.getString("model"))
        assertEquals("disabled",body.getJSONObject("thinking").getString("type"))
        assertEquals("json_object",body.getJSONObject("response_format").getString("type"))
        assertEquals(1024,body.getInt("max_tokens"))
        assertFalse(body.getBoolean("stream"))
        assertFalse(body.toString().contains("test-secret"))
        assertEquals("明天开会",JSONObject(body.getJSONArray("messages").getJSONObject(1).getString("content")).getString("text"))
    }
    @Test fun testIsSmallAndContainsNoInputHistory() {
        val body=AiProtocol.body(AiProtocol.deepSeek("test-key"),"Reply with JSON only: {\"ok\":true}",JSONObject().put("test","Loop connection test; no personal data"),true)
        assertEquals(256,body.getInt("max_tokens"))
        assertFalse(body.toString().contains("test-key"))
        assertEquals(2,body.getJSONArray("messages").length())
    }
    @Test fun customProvidersDoNotReceiveDeepSeekSpecificParameters() {
        for(url in listOf("https://example.com/v1/chat/completions","https://api.deepseek.com.example.com/chat/completions","https://api.deepseek.com:4443/chat/completions")) {
            val p=AiProfile(url,"custom-model","custom-key")
            val body=AiProtocol.body(p,"Return JSON",JSONObject())
            assertFalse(body.has("thinking"));assertFalse(body.has("response_format"))
            assertEquals(p,AiProtocol.upgradeOfficialAlias(p))
        }
    }
    @Test fun neverReusesAnotherProvidersKeyForDeepSeek() {
        val other=AiProfile("https://example.com/chat/completions","deepseek-flash","other-provider-key")
        assertEquals("",AiProtocol.defaultProfile(null,other).key)
        assertEquals("saved-key",AiProtocol.defaultProfile(AiProtocol.deepSeek("saved-key"),other).key)
        assertEquals("saved-key",AiProtocol.defaultProfile(AiProtocol.deepSeek("saved-key"),AiProtocol.deepSeek()).key)
        assertEquals("active-key",AiProtocol.defaultProfile(AiProtocol.deepSeek("saved-key"),AiProtocol.deepSeek("active-key")).key)
    }
    @Test fun upgradesOfficialLegacyAliasesWithoutChangingCustomModels() {
        for(model in listOf("deepseek-chat","deepseek-reasoner","deepseek-v4-flash")) {
            assertEquals(AiProtocol.deepSeek("saved-key"),AiProtocol.upgradeOfficialAlias(AiProfile("https://api.deepseek.com/v1/chat/completions",model,"saved-key")))
        }
        val pro=AiProfile("https://api.deepseek.com/chat/completions","deepseek-v4-pro","key")
        assertEquals(pro,AiProtocol.upgradeOfficialAlias(pro))
    }
    @Test fun rejectsThinkingOnlyEmptyOrTruncatedSuccessResponses() {
        for(response in listOf(
            """{"choices":[{"finish_reason":"stop","message":{"content":null,"reasoning_content":"reasoning only"}}]}""",
            """{"choices":[{"finish_reason":"stop","message":{"content":" "}}]}""",
            """{"choices":[{"finish_reason":"length","message":{"content":"{\"ok\":true}"}}]}""",
            """{"choices":[]}""","<html>error</html>"
        )) { try { AiProtocol.content(response);fail("Expected invalid response to be rejected") } catch(_: IllegalStateException) {} catch(_: IllegalArgumentException) {} }
    }
    @Test fun acceptsActualFinalContentAndJsonFences() {
        val response=JSONObject().put("choices",org.json.JSONArray().put(JSONObject().put("finish_reason","stop").put("message",JSONObject().put("content","```json\n{\"ok\":true}\n```"))))
        assertTrue(JSONObject(AiProtocol.content(response.toString())).getBoolean("ok"))
    }
    @Test fun errorsExplainActionWithoutEchoingServerData() {
        val raw="""{"error":{"message":"echo test-secret and private-text","code":"unknown"}}"""
        for(status in listOf(400,401,402,403,404,429,503)) {
            val message=AiProtocol.httpError(status,raw)
            assertTrue(message.contains("HTTP $status"))
            assertFalse(message.contains("test-secret"));assertFalse(message.contains("private-text"))
        }
        assertTrue(AiProtocol.httpError(402,raw).contains("余额不足"))
        assertTrue(AiProtocol.httpError(401,raw).contains("Key 无效"))
        assertTrue(AiProtocol.failure(java.net.SocketTimeoutException()).contains("超时"))
    }
    @Test fun keyPasteCleanupNeverIncludesBadKeyInError() {
        assertEquals("sk-test",AiProtocol.normalizeKey("  Bearer sk-test\n"))
        for(raw in listOf("","sk-\nprivate","sk-私密","sk-private\u200b")) {
            try { AiProtocol.normalizeKey(raw);fail("Invalid key accepted") } catch(e: IllegalArgumentException) { assertFalse(e.message.orEmpty().contains("private")) }
        }
    }
}
