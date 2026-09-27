package app.loop.ime

import android.app.Application
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[37],application=Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class StoreRegressionTest {
    @Test fun futureChoiceAndBackupTimesNeverBecomeFreshLearningOnImport()=StoreFixture().use { f ->
        val future=System.currentTimeMillis()+365*CandidateRanking.DAY
        f.store.applyDraft(DraftSnapshot("future","事实","choice",false,false,1,100,listOf(LearnedChoice("事实","shishi",false,1,future,contexts=mapOf("这是" to 1)))))
        assertEquals(0L,f.store.rankedTerms("shishi","这是").single().lastUsed)
        val rows=mutableListOf<JSONObject>();f.store.exportRows(rows::add)
        rows.filter { it.getString("table")=="evidence" }.forEach { it.put("last_used",future) }
        StoreFixture().use { other ->
            other.store.importRows(rows.asSequence())
            val term=other.store.rankedTerms("shishi","这是").single()
            assertEquals(0L,term.lastUsed);assertEquals(0L,term.evidence.single().time)
            assertEquals(2,term.score)
        }
    }
    @Test fun adaptiveContextsSurviveReopenBackupAndRetractWithSources()=StoreFixture().use { f ->
        val now=System.currentTimeMillis()
        fun save(id: String,word: String,context: String,count: Int)=f.store.applyDraft(DraftSnapshot(id,(context+word).repeat(count),"choice",false,true,1,now,
            listOf(LearnedChoice(word,"shishi",false,count,now,"26:shishi",mapOf(context to count)))))
        save("facts","事实","这是",8);save("implement","实施","开始",3)
        fun first(s: PersonalStore,context: String)=s.rankedTerms("shishi",context).first().text
        assertEquals("实施",first(f.store,"开始"));assertEquals("事实",first(f.store,"这是"))
        assertEquals("实施",first(PersonalStore(f.database),"开始"))
        assertEquals("实施",f.store.rankedTerms(NineKey.encode("shishi"),"开始",nineKey=true).first().text)
        val rows=mutableListOf<JSONObject>();f.store.exportRows(rows::add)
        StoreFixture().use { other ->
            other.store.importRows(rows.asSequence());val once=other.store.rankedTerms("shishi","开始")
            other.store.importRows(rows.asSequence());assertEquals(once,other.store.rankedTerms("shishi","开始"))
            assertEquals("实施",first(other.store,"开始"))
            other.store.deleteMemory("implement");assertEquals("事实",first(other.store,"开始"))
            other.store.forgetTerm("事实");assertTrue(other.store.rankedTerms("shishi","这是").isEmpty())
        }
        f.store.replaceMemory("implement","停止实施实施实施")
        assertTrue(f.store.rankedTerms("shishi","开始").single { it.text=="实施" }.evidence.single().contexts.isEmpty())
    }
    @Test fun learningWithoutHistoryRetainsOnlyBoundedContextAndImportDoesNotRefreshIt()=StoreFixture().use { f ->
        val now=System.currentTimeMillis()
        val draft=DraftSnapshot("local-only","开始实施","choice",false,false,1,now,listOf(LearnedChoice("实施","shishi",false,1,now,contexts=mapOf("开始" to 1))))
        f.store.applyDraft(draft);f.store.applyDraft(draft)
        assertTrue(f.store.memories().isEmpty());assertTrue(f.store.cloudHints(listOf("实施")).isEmpty())
        val before=f.store.rankedTerms("shishi","开始").single()
        assertEquals(2,before.score);assertEquals(mapOf("开始" to 1),before.evidence.single().contexts)
        val rows=mutableListOf<JSONObject>();f.store.exportRows(rows::add)
        f.store.importRows(rows.asSequence());assertEquals(before,f.store.rankedTerms("shishi","开始").single())
        f.store.applyDraft(draft.copy(text="",revision=2,choices=emptyList()))
        assertTrue(f.store.rankedTerms("shishi","开始").isEmpty())
    }
    @Test fun adaptivePaginationCrossesPoolBoundaryWithoutDuplicatesAndRecallsContextualWord()=StoreFixture().use { f ->
        repeat(540) { f.store.addTerm("同音"+it.toString().padStart(3,'0'),"shishi","contacts","contact:$it") }
        val now=System.currentTimeMillis()
        f.store.applyDraft(DraftSnapshot("context","开始实施","choice",false,true,1,now,listOf(LearnedChoice("实施","shishi",false,1,now,contexts=mapOf("开始" to 1)))))
        val pages=(0..8).flatMap { f.store.rankedTerms("shishi","开始",offset=it*64).take(64) }
        assertEquals(541,pages.size);assertEquals(541,pages.map { it.text }.distinct().size)
        assertEquals("实施",pages.first().text)
    }
    @Test fun chosenInputCodePreservesPolyphonicRankingAcrossReopenAndBackup()=StoreFixture().use { f ->
        val raw=NineKey.encode("chongqing");val now=System.currentTimeMillis()
        f.store.applyDraft(DraftSnapshot("polyphonic","重庆","choice",false,true,1,now,listOf(LearnedChoice("重庆","zhongqing",false,1,now,CandidateRanking.inputCode(raw,true)))))
        assertEquals("重庆",PersonalStore(f.database).terms(raw,nineKey=true).single().text)
        val rows=mutableListOf<JSONObject>();f.store.exportRows(rows::add)
        StoreFixture().use { other ->
            other.store.importRows(rows.asSequence());other.store.importRows(rows.asSequence())
            val term=other.store.terms(raw,nineKey=true).single()
            assertEquals("重庆",term.text);assertTrue(CandidateRanking.exact(term,raw,true));assertEquals(2,term.score)
            other.store.deleteMemory("polyphonic");assertTrue(other.store.terms(raw,nineKey=true).isEmpty())
        }
    }
    @Test fun recentChoiceOutranksFrequencySurvivesReopenAndRetractsWithItsSource()=StoreFixture().use { f ->
        val now=System.currentTimeMillis();val s=f.store
        s.applyDraft(DraftSnapshot("frequent","晓明".repeat(8),"choice",false,true,1,now,listOf(LearnedChoice("晓明","xiaoming",false,8,now-10000))))
        s.applyDraft(DraftSnapshot("recent","小明","choice",false,true,1,now,listOf(LearnedChoice("小明","xiaoming",false,1,now))))
        for(nine in listOf(false,true))assertEquals("小明",s.terms(if(nine)NineKey.encode("xiaoming") else "xiaoming",nineKey=nine).first().text)
        assertEquals(9,s.terms("xiaoming").last().score)
        val reopened=PersonalStore(f.database)
        assertEquals("小明",reopened.terms("xiaoming").first().text)
        s.deleteMemory("recent");assertEquals("晓明",s.terms("xiaoming").first().text)
        assertFalse(f.database.query("PRAGMA foreign_key_check").use { it.moveToFirst() })
    }
    @Test fun recencyExpiresAndBackupsDoNotRefreshTheLastUseOrDuplicateFrequency()=StoreFixture().use { f ->
        val now=System.currentTimeMillis();val s=f.store
        s.applyDraft(DraftSnapshot("older","晓明".repeat(5),"choice",false,true,1,now,listOf(LearnedChoice("晓明","xiaoming",false,5,now-CandidateRanking.RECENT_WINDOW-2000))))
        s.applyDraft(DraftSnapshot("newer","小明","choice",false,true,1,now,listOf(LearnedChoice("小明","xiaoming",false,1,now-CandidateRanking.RECENT_WINDOW-1000))))
        assertEquals("晓明",s.terms("xiaoming").first().text)
        val rows=mutableListOf<JSONObject>();s.exportRows(rows::add)
        StoreFixture().use { restored ->
            restored.store.importRows(rows.asSequence());restored.store.importRows(rows.asSequence())
            assertEquals(s.terms("xiaoming"),restored.store.terms("xiaoming"))
            assertEquals(now-CandidateRanking.RECENT_WINDOW-1000,restored.store.terms("xiaoming").last().lastUsed)
        }
    }
    @Test fun singleCharactersAndMemoryDisabledChoicesKeepRetractableRecency()=StoreFixture().use { f ->
        val now=System.currentTimeMillis();val s=f.store
        val draft=DraftSnapshot("no-memory","你","choice",false,false,1,now,listOf(LearnedChoice("你","ni",false,1,now)))
        s.applyDraft(draft);s.applyDraft(draft)
        assertEquals(0L,s.count("memories"));assertEquals(2,s.terms("ni").single().score);assertEquals(now,s.terms("ni").single().lastUsed)
        s.applyDraft(draft.copy(text="",revision=2,choices=emptyList()));assertTrue(s.terms("ni").isEmpty())
        s.forgetTerm("你");s.applyDraft(draft);assertTrue(s.terms("ni").isEmpty())
    }
    @Test fun exactPronunciationsStayBeforeRecentlyUsedLongerPhrases()=StoreFixture().use { f ->
        f.store.addTerm("小明","xiaoming",explicit=true)
        val now=System.currentTimeMillis()
        f.store.applyDraft(DraftSnapshot("long","小明同学","choice",false,true,1,now,listOf(LearnedChoice("小明同学","xiaomingtongxue",false,1,now))))
        assertEquals("小明",f.store.terms("xiaoming").first().text)
        assertEquals("小明",f.store.terms(NineKey.encode("xiaoming"),nineKey=true).first().text)
    }
    @Test fun continuationLookupDoesNotFallBackToFrequentWords()=StoreFixture().use { f ->
        f.store.addTerm("好的","haode",explicit=true)
        f.store.addTerm("你好世界","nihaoshijie",explicit=true)
        assertTrue(f.store.continuationTerms("").isEmpty())
        assertTrue(f.store.continuationTerms("今天").isEmpty())
        assertEquals(listOf("你好世界"),f.store.continuationTerms("我说你好").map { it.text })
        assertEquals(2L,f.store.count("terms"))
    }
    @Test fun mergePreviewAuthenticatesAndRollsBackAllChanges()=StoreFixture().use { f ->
        f.store.addTerm("本机词","benjici",explicit=true)
        val before=mutableListOf<String>();f.store.exportRows { before+=it.toString() }
        val term=JSONObject().put("table","terms").put("text","备份词").put("pinyin","beifenci").put("score",2).put("cloud",0).put("source","manual").put("pinned",1)
        val evidence=JSONObject().put("table","evidence").put("term","备份词").put("origin","manual").put("kind","manual")
        assertTrue(f.store.importRows(sequenceOf(term,evidence),preview=true).added>0)
        val after=mutableListOf<String>();f.store.exportRows { after+=it.toString() }
        assertEquals(before,after)
        f.store.importRows(sequenceOf(term,evidence));assertEquals(2L,f.store.count("terms"))
    }
    @Test fun speechHintsRejectCloudWordsContainingNewLocalOnlyNames()=StoreFixture().use { f ->
        f.store.addTerm("张三丰","zhangsanfeng","choice","remote",true)
        assertEquals(listOf("张三丰"),f.store.cloudSpeechHints())
        f.store.addTerm("张三","zhangsan",explicit=true)
        assertTrue(f.store.cloudSpeechHints().isEmpty());assertEquals(2,f.store.terms().size)
    }
    @Test fun oldBackupCannotResurrectRetractedEvidenceEvenWithReorderedRows()=StoreFixture().use { f ->
        val s=f.store
        s.applyDraft(DraftSnapshot("usage","小明小明小明大明","choice",false,true,1,100,listOf(LearnedChoice("小明","xiaoming",false,3),LearnedChoice("大明","daming"))))
        val old=mutableListOf<JSONObject>();s.exportRows(old::add)
        s.applyDraft(DraftSnapshot("usage","小明","choice",false,true,2,100,listOf(LearnedChoice("小明","xiaoming"))))
        s.importRows(old.reversed().asSequence())
        assertEquals("小明",s.memories().single().text);assertEquals(2,s.terms().single().score)
        assertEquals("小明",s.terms().single().text)
    }
    @Test fun newerBackupReplacesCountsAndRepeatedImportIsIdempotent()=StoreFixture().use { f ->
        val s=f.store
        s.applyDraft(DraftSnapshot("usage","小明小明小明","choice",true,true,1,100,listOf(LearnedChoice("小明","xiaoming",true,3))))
        val incoming=mutableListOf<JSONObject>();s.exportRows(incoming::add)
        incoming.filter { it.getString("table")=="memories" }.forEach { it.put("text","小明").put("revision",2).put("updated",it.getLong("updated")+100) }
        incoming.filter { it.getString("table")=="evidence" }.forEach { it.put("uses",1) }
        s.importRows(incoming.asSequence());s.importRows(incoming.asSequence())
        assertEquals("小明",s.memories().single().text);assertEquals(2,s.terms().single().score)
    }
    @Test fun personalCandidatePagesHaveNoGapsOrDuplicates()=StoreFixture().use { f ->
        repeat(140) { f.store.addTerm("姓名"+it.toString().padStart(3,'0'),"xingming","contacts","contact:$it") }
        val expected=f.store.terms("xing",200).map { it.text }
        val paged=(0..2).flatMap { f.store.terms("xing",64,offset=it*64) }.map { it.text }
        assertEquals(140,paged.size);assertEquals(expected,paged)
        assertEquals(paged,(0..2).flatMap { f.store.terms(NineKey.encode("xing"),64,nineKey=true,offset=it*64) }.map { it.text })
    }
    @Test fun selectingAContactChangesHomophoneRankingAndDeletingTheRecordRestoresIt()=StoreFixture().use { f ->
        val s=f.store;s.addTerm("晓明","xiaoming",explicit=true)
        s.addTerm("小明","xiaoming","contacts","contact:1")
        assertEquals("晓明",s.terms("xiaoming").first().text)
        val v=DraftSnapshot("usage","小明小明小明","choice",false,true,2,100,listOf(LearnedChoice("小明","xiaoming",false,3)))
        s.applyDraft(v);s.applyDraft(v)
        assertEquals(5,s.terms("xiaoming").first().score);assertEquals("小明",s.terms("xiaoming").first().text)
        assertEquals("小明",s.terms(NineKey.encode("xiaoming"),nineKey=true).first().text)
        s.applyDraft(v.copy(text="小明",revision=3,choices=listOf(LearnedChoice("小明","xiaoming"))))
        assertEquals(3,s.terms("xiaoming").first().score)
        s.deleteMemory("usage");assertEquals("晓明",s.terms("xiaoming").first().text)
        assertEquals(2,s.terms("xiaoming").last().score);assertTrue(s.terms(cloudOnly=true).isEmpty())
    }
    @Test fun backupRestoresChoiceCountsWithoutDoublingThemOnRepeatedImport()=StoreFixture().use { f ->
        val s=f.store;s.addTerm("小明","xiaoming","contacts","contact:1")
        s.applyDraft(DraftSnapshot("usage","小明小明","choice",false,true,1,100,listOf(LearnedChoice("小明","xiaoming",false,2))))
        val rows=mutableListOf<JSONObject>();s.exportRows(rows::add)
        StoreFixture().use { restored ->
            restored.store.importRows(rows.asSequence());restored.store.importRows(rows.asSequence())
            assertEquals(4,restored.store.terms().single().score)
            restored.store.deleteMemory("usage");assertEquals(2,restored.store.terms().single().score)
        }
    }
    @Test fun legacyUpgradePreservesRecordsAndRepairsEvidenceWithoutResettingDatabase()=StoreFixture { db ->
        db.execSQL("CREATE TABLE memories(id TEXT PRIMARY KEY,text TEXT NOT NULL,time INTEGER NOT NULL,source TEXT NOT NULL,status TEXT NOT NULL,cloud INTEGER NOT NULL,learned INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE TABLE terms(text TEXT PRIMARY KEY,pinyin TEXT NOT NULL,score INTEGER NOT NULL DEFAULT 1,cloud INTEGER NOT NULL DEFAULT 0,source TEXT NOT NULL,pinned INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE TABLE evidence(term TEXT NOT NULL REFERENCES terms(text) ON DELETE CASCADE,origin TEXT NOT NULL,kind TEXT NOT NULL,PRIMARY KEY(term,origin))")
        db.execSQL("CREATE TABLE forgotten(text TEXT PRIMARY KEY)")
        db.execSQL("CREATE TABLE clips(id TEXT PRIMARY KEY,text TEXT NOT NULL,time INTEGER NOT NULL,pinned INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("INSERT INTO memories VALUES ('old','联系王小明',100,'voice','issued',1,1)")
        db.execSQL("INSERT INTO terms VALUES ('王小明','wangxiaoming',999,1,'memory',0)")
        db.execSQL("INSERT INTO evidence VALUES ('王小明','contact:old','contacts'),('王小明','old','memory'),('孤立词','orphan','memory')")
        db.execSQL("INSERT INTO clips VALUES ('clip','保留剪贴板',100,1)")
        db.version=1
    }.use { f ->
        assertEquals(PersonalStore.SCHEMA_VERSION,f.database.version)
        assertEquals("联系王小明",f.store.memories().single().text);assertFalse(f.store.memories().single().cloud)
        assertEquals(3,f.store.terms().single().score);assertTrue(f.store.terms(cloudOnly=true).isEmpty())
        assertEquals("保留剪贴板",f.store.clips().single().text)
        f.database.disableWriteAheadLogging();f.database.enableWriteAheadLogging()
        f.store.forgetTerm("王小明")
        assertEquals(0,f.database.query("SELECT count(*) FROM evidence").use { it.moveToFirst();it.getInt(0) })
        assertFalse(f.database.query("PRAGMA foreign_key_check").use { it.moveToFirst() })
    }
    @Test fun newChoicesAndStaleLearningCannotUpgradeTheirMemoryPermission()=StoreFixture().use { f ->
        val s=f.store;s.saveMemory("m","词库学习","manual",true)
        val stale=s.memories().single();assertTrue(s.canLearnCloud(stale))
        s.applyDraft(DraftSnapshot("m","词库学习","mixed",false,true,2,100,listOf(LearnedChoice("词库学习","cikuxuexi",true))))
        s.addTerm("词库","ciku","memory","m",true)
        assertFalse(s.canLearnCloud(stale));assertTrue(s.terms(cloudOnly=true).isEmpty())
        s.addTerm("离线姓名","lixianxingming","contacts","contact:new",true)
        assertTrue(s.containsLocalOnly("联系离线姓名"));assertTrue(s.terms(cloudOnly=true).isEmpty())
    }
    @Test fun localManualAndContactTermsAppearInBothInputModesAndSpeechButNeverCloud()=StoreFixture().use { f ->
        val s=f.store;s.addTerm("小明","xiaoming",explicit=true);s.addTerm("王小明","wangxiaoming","contacts","contact:1")
        assertEquals(2L,s.count("terms"));assertTrue(s.terms("xiao").any { it.text=="小明" })
        assertTrue(s.terms("926494266464",nineKey=true).any { it.text=="王小明" })
        assertEquals(2,s.terms(limit=64).size);assertTrue(s.terms(cloudOnly=true).isEmpty())
    }
    @Test fun cloudRetryWorksWithBackoffAndCannotDisplaceUnlearnedLocalRows()=StoreFixture().use { f ->
        val s=f.store
        repeat(12) { s.saveMemory("old$it","旧记录$it","manual",true);s.markLearned("old$it") }
        assertEquals(12,s.pending(cloudRetry=true).size)
        s.saveMemory("new","先做新的本地学习","manual",true)
        assertEquals("new",s.pending(cloudRetry=true).first().id)
        s.retryLearning("old0");assertFalse(s.pendingCloud(20).any { it.id=="old0" })
        assertTrue(s.pending().all { it.id=="new" })
    }
    @Test fun changedMemoryConsentAndDerivedWordsAreAtomicallyRestricted()=StoreFixture().use { f ->
        val s=f.store;s.saveMemory("id","联系小明","choice",true);s.addTerm("小明","xiaoming","choice","id",true)
        s.saveMemory("id","明天联系小明","choice",false)
        assertEquals("明天联系小明",s.memories().single().text);assertFalse(s.memories().single().cloud)
        assertTrue(s.terms(cloudOnly=true).isEmpty())
        s.saveMemory("id","","choice",false);assertEquals(0L,s.count("memories"));assertTrue(s.terms().isEmpty())
    }
    @Test fun mergingContactEvidenceAlwaysKeepsTheMoreRestrictivePermission()=StoreFixture().use { f ->
        val s=f.store;s.addTerm("王小明","wangxiaoming","memory","old",true)
        val cached=s.terms(cloudOnly=true).map { it.text };assertEquals(cached,s.cloudHints(cached))
        val term=JSONObject().put("table","terms").put("text","王小明").put("pinyin","wangxiaoming").put("score",200).put("cloud",0).put("source","contacts").put("pinned",0)
        val evidence=JSONObject().put("table","evidence").put("term","王小明").put("origin","contact:1").put("kind","contacts")
        s.importRows(sequenceOf(term,evidence))
        assertTrue(s.containsLocalOnly("联系王小明"));assertTrue(s.terms(cloudOnly=true).isEmpty());assertEquals(3,s.terms().single().score)
        assertTrue(s.cloudHints(cached).isEmpty())
    }
    @Test fun deletingSourceRetractsItsScoreAndForgettingCascades()=StoreFixture().use { f ->
        val s=f.store;s.addTerm("小明","xiaoming");val score=s.terms().single().score
        repeat(10) { s.saveMemory("m$it","小明","manual",false);s.addTerm("小明","xiaoming","memory","m$it") }
        assertEquals(score+10,s.terms().single().score);s.deleteAllMemories();assertEquals(score,s.terms().single().score)
        s.forgetTerm("小明")
        assertEquals(0,f.database.query("SELECT count(*) FROM evidence").use { it.moveToFirst();it.getInt(0) })
        assertFalse(f.database.query("PRAGMA foreign_key_check").use { it.moveToFirst() })
    }
    @Test fun failedImportRollsBackAndRepeatedSnapshotsAreIdempotent()=StoreFixture().use { f ->
        val s=f.store
        try { s.importRows(sequenceOf(JSONObject().put("table","forgotten").put("text","测试"),JSONObject().put("table","bad")));fail("Invalid table accepted") } catch(_: IllegalStateException) { }
        s.addTerm("测试","ceshi");assertEquals(1L,s.count("terms"))
        val v=DraftSnapshot("record","科学研究","choice",false,true,2,100,listOf(LearnedChoice("科学研究","kexueyanjiu")))
        s.applyDraft(v);s.applyDraft(v.copy(revision=1,text="过时内容"));s.applyDraft(v)
        assertEquals("科学研究",s.memories().single().text)
        assertEquals(2,s.terms("kexue").single().score)
    }
}
