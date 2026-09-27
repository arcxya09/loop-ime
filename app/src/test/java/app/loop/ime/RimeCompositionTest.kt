package app.loop.ime

import org.junit.Assert.*
import org.junit.Test

class RimeCompositionTest {
    @Test fun phoneReadingComesFromTheEngineWithoutNumericCodes() {
        val state=RimeState.from("""{"raw":"64426","preedit":"64 426","reading":"ni hao","candidates":["你好"]}""".toByteArray())
        assertEquals("ni hao",state.keyboardComposition(true))
        assertEquals("",state.editorComposition(true))
        assertEquals("",RimeState(reading="old reading").keyboardComposition(true))
    }
    @Test fun incompleteInputAndMissingReadingsRemainVisibleWithoutInventingPinyin() {
        assertEquals("",RimeState(raw="64").keyboardComposition(true))
        assertEquals("ni h",RimeState(raw="nih",preedit="ni h",reading="ni hao").keyboardComposition(false))
        assertEquals("nihao",RimeState(raw="nihao").keyboardComposition(false))
    }
}
