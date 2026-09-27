package app.loop.ime

import org.junit.Assert.*
import org.junit.Test

class SpeechTextTest {
    @Test fun boundariesSurvivePunctuationSettingsAndDoNotSpaceChinese() {
        assertEquals("hello world",SpeechText.join("",listOf("hello","world")))
        assertEquals("Hello. World.",SpeechText.join("",listOf("Hello.","World.")))
        assertEquals("你好世界",SpeechText.join("",listOf("你好","世界")))
        assertEquals("你好。世界。",SpeechText.join("",listOf("你好。","世界。")))
        assertEquals("hello world",SpeechText.join("",listOf("hello ","world")))
        assertEquals(" world",SpeechText.join("hello",listOf("world")))
    }
}
