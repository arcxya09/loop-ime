package app.loop.ime

import org.junit.Assert.*
import org.junit.Test

class InputHistoryTest {
    @Test fun repeatedChoicesCountEachSelectionAndDeletionRetractsOnlyRemovedOccurrences() {
        val writes=mutableListOf<DraftSnapshot>();val h=InputHistory(writes::add)
        repeat(3) { h.apply(TextEdit(it*2,it*2,"小明"),"choice",true,false);h.learn("小明","xiaoming",false) }
        assertEquals(3,writes.last().choices.single().count)
        assertEquals(writes.last(),DraftSnapshot.decode(writes.last().encode()))
        h.apply(TextEdit(4,6,""),"manual",true,false)
        assertEquals(2,writes.last().choices.single().count)
        h.apply(TextEdit(0,4,""),"manual",true,false);assertTrue(writes.last().choices.isEmpty())
    }
    @Test fun deletingAWholeSelectionAndDeletingToEmptyPublishAnEmptyVersion() {
        val writes=mutableListOf<DraftSnapshot>();val h=InputHistory(writes::add)
        h.apply(TextEdit(0,0,"你好世界"),"manual",true,false)
        val id=writes.last().id;h.separate()
        h.apply(TextEdit(0,4,""),"manual",true,false)
        assertEquals(id,writes.last().id);assertEquals("",writes.last().text)
        assertEquals("",h.context(0))
    }
    @Test fun mixedSourcesAndCorrectionsKeepTheSameRecordAndDowngradeConsent() {
        val writes=mutableListOf<DraftSnapshot>();val h=InputHistory(writes::add)
        h.apply(TextEdit(0,0,"科学"),"manual",true,true)
        h.apply(TextEdit(2,2,"研究"),"choice",true,false);h.learn("研究","yanjiu",false)
        h.apply(TextEdit(0,4,"科研"),"corrected",true,true)
        assertEquals("科研",writes.last().text);assertFalse(writes.last().cloud);assertTrue(writes.last().choices.isEmpty())
        assertEquals(1,writes.map { it.id }.distinct().size)
    }
    @Test fun replacingAcrossFragmentsUpdatesBothOldRecordsAndAddsReplacement() {
        val writes=mutableListOf<DraftSnapshot>();val h=InputHistory(writes::add)
        h.apply(TextEdit(0,0,"甲乙"),"manual",true,true);h.separate();h.apply(TextEdit(2,2,"丙丁"),"voice",true,true)
        h.apply(TextEdit(1,3,"新"),"corrected",true,true)
        assertEquals("甲新丁",h.context(3))
        assertEquals(setOf("甲","新","丁"),writes.groupBy { it.id }.values.map { it.last().text }.toSet())
    }
    @Test fun disabledMemoryDoesNotJournalOrdinaryTypingButExplicitLearningIsRetractable() {
        val writes=mutableListOf<DraftSnapshot>();val h=InputHistory(writes::add)
        h.apply(TextEdit(0,0,"测试"),"choice",false,false);assertTrue(writes.isEmpty())
        h.learn("测试","ceshi",false);assertEquals(1,writes.last().choices.size);assertFalse(writes.last().remember)
        h.apply(TextEdit(0,2,""),"manual",false,false);assertTrue(writes.last().choices.isEmpty())
    }
}
