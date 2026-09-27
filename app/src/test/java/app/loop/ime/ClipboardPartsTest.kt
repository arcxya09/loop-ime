package app.loop.ime

import android.app.Application
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[37],application=Application::class)
class ClipboardPartsTest {
    @Test fun bothModesAreLosslessAndKeepGraphemeClusters() {
        val text="你好你好 A123\n👨‍👩‍👧‍👦 e\u0301 🇨🇳"
        for(chars in listOf(false,true)) {
            val result=ClipboardParts.split(text,chars)
            assertFalse(result.truncated);assertEquals(text,result.values.joinToString(""))
            assertTrue("👨‍👩‍👧‍👦" in result.values);assertTrue("e\u0301" in result.values);assertTrue("🇨🇳" in result.values)
        }
        assertEquals(listOf("你","好","你","好"),ClipboardParts.split("你好你好",true).values)
        assertTrue("A123" in ClipboardParts.split("你好 A123").values)
    }
    @Test fun capIsExplicitAndNeverLeavesHalfAnEmoji() {
        assertTrue(ClipboardParts.split("").values.isEmpty())
        val result=ClipboardParts.split("😀".repeat(ClipboardParts.LIMIT+1),true)
        assertTrue(result.truncated);assertEquals(ClipboardParts.LIMIT,result.values.size);assertTrue(result.values.all { it=="😀" })
        assertFalse(ClipboardParts.split("甲".repeat(ClipboardParts.LIMIT),true).truncated)
    }
}
