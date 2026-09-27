package app.loop.ime

import org.junit.Assert.*
import org.junit.Test

class QuickTextTest {
    @Test fun extractsCodesWithoutConfusingOtherNumbers() {
        assertEquals(listOf("001234"),QuickText.codes("【服务】验证码：001234，5分钟内有效。"))
        assertEquals(listOf("482731"),QuickText.codes("482731 is your verification code."))
        assertEquals(listOf("A8B29X"),QuickText.codes("Your security code is A8B29X."))
        assertTrue(QuickText.codes("订单 123456，金额 1234 元，联系电话 13812345678").isEmpty())
        assertTrue(QuickText.codes("验证码有效期 1234 秒，订单 345678").isEmpty())
        assertTrue(QuickText.codes("验证码请联系 13812345678").isEmpty())
        assertEquals(listOf("123456","654321"),QuickText.codes("验证码 123456 或 654321"))
        assertTrue(QuickText.extracts("验证码 123456，联系电话13812345678").isEmpty())
        assertEquals(listOf("邮箱" to "name@example.com","链接" to "https://example.com/path","号码" to "13812345678"),QuickText.extracts("name@example.com https://example.com/path 13812345678"))
    }
    @Test fun bufferExpiresDeduplicatesAndRejectsOldAndFutureMessages() {
        var time=1_000_000L;val b=SuggestionBuffer { time }
        b.offer("new","验证码 001234","短信",time)
        b.offer("old","验证码 999999","短信",time-1000)
        assertEquals("001234",b.values().single().text)
        val id=b.values().single().id;b.consume(id);b.offer("new","验证码 001234","短信",time)
        assertTrue(b.values().isEmpty())
        b.offer("future","验证码 001234","短信",time+10000);assertTrue(b.values().isEmpty())
        b.offer("next","验证码 123456","短信",time);time+=SuggestionBuffer.TTL+1;assertTrue(b.values().isEmpty())
        b.offer("expired","验证码 654321","短信",time-SuggestionBuffer.TTL-1);assertTrue(b.values().isEmpty())
    }
    @Test fun toolOrderIsBoundedUniqueAndRepairsUnknownEntries() {
        assertEquals(8,ToolCatalog.order("edit,edit,invalid,phrases").size)
        assertEquals(listOf("edit","phrases"),ToolCatalog.order("edit,edit,invalid,phrases").take(2))
        assertEquals(ToolCatalog.defaults,ToolCatalog.order(""))
    }
}
