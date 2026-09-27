package app.loop.ime

import org.junit.Assert.*
import org.junit.Test

class PredictionTextTest {
    @Test fun noContextOrEchoCannotBecomeAPrediction() {
        assertTrue(PredictionText.continuations("",listOf("好的")).isEmpty())
        assertTrue(PredictionText.continuations("今天天气",listOf("今天天气","天气","今天","天气。","\n泄露")).isEmpty())
    }
    @Test fun fullSentencesAndOverlappingPrefixesBecomeOnlyTheNewSuffix() {
        assertEquals(listOf("很好","不错"),PredictionText.continuations("今天天气",listOf("今天天气很好","天气很好","不错")))
        assertEquals(listOf(" world"),PredictionText.continuations("hello",listOf("hello world","world","Hello")))
        assertEquals(listOf("lo"),PredictionText.continuations("hel",listOf("hello")))
        assertEquals(" world",PredictionText.insertion("hello","world"))
        assertEquals("世界",PredictionText.insertion("你好","世界"))
    }
    @Test fun localTermsMustExtendTheCurrentContext() {
        assertNull(PredictionText.localSuffix("","好的"))
        assertNull(PredictionText.localSuffix("你好","好的"))
        assertNull(PredictionText.localSuffix("你好","你好"))
        assertEquals("世界",PredictionText.localSuffix("我说你好","你好世界"))
        assertTrue(PredictionText.localPrefixes("你好。").isEmpty())
    }
}
