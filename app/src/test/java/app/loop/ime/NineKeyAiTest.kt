package app.loop.ime

import android.app.Application
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[37],application=Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class NineKeyAiTest {
    private fun json(vararg words: Pair<String,String>)=JSONObject().put("candidates",JSONArray(words.map {
        JSONObject().put("text",it.first).put("pinyin",it.second)
    })).toString()
    @Test fun verifiesRealPronunciationAndCodeInsteadOfTrustingModelSpelling() {
        val result=NineKeyAiProtocol.parse(json("你好" to "nǐ hǎo","天气" to "ni hao","北京" to "bei jing","123" to "nihao","你好！" to "nihao"),"64426")
        assertEquals(listOf(NineKeyCandidate("你好","nihao")),result)
    }
    @Test fun acceptsBoundedCompletionsSimplifiesChineseAndDeduplicates() {
        val result=NineKeyAiProtocol.parse(json("學校" to "xue xiao","学校" to "xuexiao","学生" to "xue sheng"),"983")
        assertEquals(listOf("学校","学生"),result.map { it.text })
        assertTrue(NineKeyAiProtocol.parse(json("你好" to "ni hao"),"64").isNotEmpty())
        assertTrue(NineKeyAiProtocol.parse(json("中华人民共和国" to "zhong hua ren min gong he guo"),"94").isEmpty())
        assertFalse(NineKeyAiProtocol.matches("nihao","644267"))
        assertFalse(NineKeyAiProtocol.matches("ni2hao","642"))
    }
    @Test fun knownCloudPronunciationSupportsPolyphonicWordsAndUmlauts() {
        val term=Term("重庆","chongqing",8,true,"manual")
        val code=NineKey.encode(term.pinyin)
        assertEquals(listOf(NineKeyCandidate(term.text,term.pinyin)),NineKeyAiProtocol.parse(json("重庆" to "chong qing"),code,listOf(term)))
        assertEquals("lv",NineKeyAiProtocol.normalizePinyin("LǙ"))
        assertEquals(listOf("女"),NineKeyAiProtocol.parse(json("女" to "nǚ"),"68").map { it.text })
    }
    @Test fun payloadBoundsContextAndIncludesOnlyMatchingCloudHints() {
        val payload=NineKeyAiProtocol.payload(NineKeyQuery("64","前".repeat(200)),listOf(
            Term("你好","nihao",9,true,"choice"),Term("倪浩","nihao",10,false,"contacts"),Term("北京","beijing",10,true,"choice")))
        assertEquals("64",payload.getString("code"));assertEquals(120,payload.getString("context").length)
        assertEquals(1,payload.getJSONArray("terms").length());assertFalse(payload.toString().contains("倪浩"))
        for(raw in listOf("","6","123","64'426","6*","6".repeat(49)))assertFalse(NineKeyAiProtocol.eligible(raw))
    }
    private class Pending(val query: NineKeyQuery,val callback: (Result<List<NineKeyCandidate>>)->Unit,val call: AiCall=AiCall())
    private class Fixture {
        var allowed=true
        var updates=0
        val sent=mutableListOf<Pending>()
        val session=NineKeyAiSession({ query,cb -> Pending(query,cb).also { sent+=it }.call },{ allowed },{ updates++ })
        fun advance(ms: Long)=shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
        fun answer(index: Int=sent.lastIndex)=sent[index].callback(Result.success(listOf(NineKeyCandidate("你好","nihao"))))
    }
    @Test fun burstTypingUsesOnlyLatestCodeAndOldResponsesCannotReappearAfterBackspace() {
        val f=Fixture();f.session.update(NineKeyQuery("64",""));f.advance(200)
        f.session.update(NineKeyQuery("644",""));f.advance(319);assertTrue(f.sent.isEmpty())
        f.advance(1);assertEquals("644",f.sent.single().query.raw)
        f.session.update(NineKeyQuery("64426",""));assertTrue(f.sent[0].call.cancelled.get())
        f.answer(0);assertTrue(f.session.candidates.isEmpty())
        f.advance(800);f.answer(1);val revision=f.session.revision
        assertEquals("你好",f.session.choose(0,revision)?.text)
        f.session.update(NineKeyQuery("644",""));f.answer(0)
        assertTrue(f.session.candidates.isEmpty());assertNull(f.session.choose(0,revision))
        f.session.cancel()
    }
    @Test fun cloudConsentIsCheckedBeforeRequestAfterResponseAndOnSelection() {
        val f=Fixture();f.session.update(NineKeyQuery("64",""));f.allowed=false;f.advance(320)
        assertTrue(f.sent.isEmpty())
        f.session.cancel();f.allowed=true;f.session.update(NineKeyQuery("64",""));f.advance(320)
        f.allowed=false;f.answer();assertTrue(f.session.candidates.isEmpty())
        assertNull(f.session.choose(0,f.session.revision));f.session.cancel()
    }
    @Test fun cacheIsContextSensitiveAndIsErasedWhenLeavingTheField() {
        val f=Fixture();val query=NineKeyQuery("64","早上")
        f.session.update(query);f.advance(320);f.answer();f.session.cancel();f.session.update(query)
        assertEquals(1,f.sent.size);assertEquals(1,f.session.candidates.size)
        f.session.update(query.copy(context="晚上"));f.advance(800);assertEquals(2,f.sent.size)
        f.session.cancel(clearCache=true);f.session.update(query);assertTrue(f.session.candidates.isEmpty())
        f.advance(320);assertEquals(3,f.sent.size);f.session.cancel()
    }
    @Test fun timeoutAndFailureBackOffWithoutRemovingLocalInputOrAcceptingLateResults() {
        val f=Fixture();f.session.update(NineKeyQuery("64",""));f.advance(6820)
        assertTrue(f.sent[0].call.cancelled.get());f.answer(0);assertTrue(f.session.candidates.isEmpty())
        f.session.update(NineKeyQuery("644",""));f.advance(4999);assertEquals(1,f.sent.size)
        f.advance(1);assertEquals(2,f.sent.size)
        f.sent[1].callback(Result.failure(java.net.UnknownHostException()))
        f.session.update(NineKeyQuery("64426",""));f.advance(4999);assertEquals(2,f.sent.size)
        f.advance(1);assertEquals(3,f.sent.size);f.session.cancel()
    }
}
