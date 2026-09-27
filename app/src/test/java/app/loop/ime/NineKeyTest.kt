package app.loop.ime

import org.junit.Assert.*
import org.junit.Test

class NineKeyTest {
    @Test fun fullPinyinNamesAndUmlautUseTheSamePhoneCode() {
        assertEquals("94664486",NineKey.encode("zhongguo"))
        assertEquals("64426",NineKey.encode("ni'hao"))
        assertEquals("58",NineKey.encode("LÜ"));assertEquals("58",NineKey.encode("lv"))
        assertTrue(NineKey.matches("zhangsan","94264'726",true))
        assertFalse(NineKey.matches("zhangsan","94264",true))
        assertTrue(NineKey.matches("zhangsan","zhang'san",false))
    }
    @Test fun queryUsesOnlySafePhoneCharacterClasses() {
        assertEquals("[mno][ghi][ghi][abc][mno]",NineKey.glob("64'426"))
        assertNull(NineKey.glob(""));assertNull(NineKey.glob("123"));assertNull(NineKey.glob("6*"));assertNull(NineKey.glob("6%"))
    }
}
