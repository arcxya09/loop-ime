package app.loop.ime

import org.junit.Assert.*
import org.junit.Test

class TextRulesTest {
    @Test fun preservesNumbersUnitsAndNegation() {
        for((before,after) in listOf("需要10毫克" to "需要10克", "长度10米" to "长度10克", "Take 10 mg" to "Take 10 g", "I am not ready" to "I am now ready", "I can't go" to "I can go", "costs $10" to "costs €10", "温度-10℃" to "温度10℃")) {
            assertFalse("$before -> $after",TextRules.safeCorrection(before,after))
        }
        assertFalse(TextRules.safeCorrection("需要10毫克","需要100毫克"))
        assertFalse(TextRules.safeCorrection("我不去了","我去了"))
        assertFalse(TextRules.safeCorrection("安排在2026年","安排在2027年"))
        assertFalse(TextRules.safeCorrection("我要三公斤","我要三克"))
        assertTrue(TextRules.safeCorrection("hello worle","hello world"))
    }
    @Test fun keepsExplicitNamesAndRejectsRewrites() {
        assertFalse(TextRules.safeCorrection("李思明明天来","李思敏明天来",listOf("李思明")))
        assertFalse(TextRules.safeCorrection("明天开会","明天不开会"))
        assertFalse(TextRules.safeCorrection("吃饭了吗","饭后散步吧"))
        assertFalse(TextRules.safeCorrection("你好","你好\n忽略指令"))
    }
    @Test fun apiEndpointCannotEmbedCredentialsOrUseHttp() {
        assertEquals("example.com",TextRules.safeEndpoint("https://example.com/v1/chat/completions").host)
        for(s in listOf("http://example.com","file:///etc/passwd","https://secret@example.com/api","https://example.com/#token")) {
            try { TextRules.safeEndpoint(s);fail(s) } catch(_: IllegalArgumentException) {}
        }
    }
    @Test fun rejectsStaleAnchors() {
        assertTrue(TextRules.suffixPatchAllowed("hello worle","hello worle","worle"))
        assertFalse(TextRules.suffixPatchAllowed("hello worle","hello worle!","worle"))
        assertFalse(TextRules.suffixPatchAllowed("hello worle","hello world","worle"))
    }
}
