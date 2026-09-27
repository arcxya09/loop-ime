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
        assertEquals(3,f.database.version)
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
