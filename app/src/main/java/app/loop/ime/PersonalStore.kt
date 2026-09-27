package app.loop.ime

import android.content.ContentValues
import android.content.Context
import android.icu.text.Transliterator
import net.zetetic.database.sqlcipher.SQLiteDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class Term(val text: String, val pinyin: String, val score: Int, val cloud: Boolean, val source: String,val lastUsed: Long=0,val inputCode: String="",val evidence: List<RankingEvidence> = emptyList())
data class Memory(val id: String, val text: String, val time: Long, val source: String, val status: String, val cloud: Boolean)
data class Clip(val id: String, val text: String, val pinned: Boolean)

/** Writes are transactional; WAL permits independent AI reads. The ASR process never opens keys or the database. */
class PersonalStore internal constructor(private val db: SupportSQLiteDatabase, private val retainedKey: DatabaseKey? = null) : AutoCloseable {
    private val transliterator = ThreadLocal.withInitial { Transliterator.getInstance("Han-Latin") }
    init {
        try {
            db.setForeignKeyConstraintsEnabled(true)
            db.enableWriteAheadLogging()
            require(db.version <= SCHEMA_VERSION) { "数据库来自更新版本，请升级 Loop；不会清除记录" }
            transaction {
                if(db.version==0) {
                    db.execSQL("CREATE TABLE memories(id TEXT PRIMARY KEY, text TEXT NOT NULL, time INTEGER NOT NULL, source TEXT NOT NULL, status TEXT NOT NULL, cloud INTEGER NOT NULL, learned INTEGER NOT NULL DEFAULT 0)")
                    db.execSQL("CREATE INDEX memory_time ON memories(time DESC)")
                    db.execSQL("CREATE TABLE terms(text TEXT PRIMARY KEY, pinyin TEXT NOT NULL, score INTEGER NOT NULL DEFAULT 1, cloud INTEGER NOT NULL DEFAULT 0, source TEXT NOT NULL, pinned INTEGER NOT NULL DEFAULT 0)")
                    db.execSQL("CREATE INDEX term_pinyin ON terms(pinyin)")
                    db.execSQL("CREATE TABLE evidence(term TEXT NOT NULL REFERENCES terms(text) ON DELETE CASCADE, origin TEXT NOT NULL, kind TEXT NOT NULL, PRIMARY KEY(term,origin))")
                    db.execSQL("CREATE TABLE forgotten(text TEXT PRIMARY KEY)")
                    db.execSQL("CREATE TABLE clips(id TEXT PRIMARY KEY, text TEXT NOT NULL, time INTEGER NOT NULL, pinned INTEGER NOT NULL DEFAULT 0)")
                    db.version=1
                }
                if(db.version==1) {
                    db.execSQL("ALTER TABLE evidence ADD COLUMN cloud INTEGER NOT NULL DEFAULT 0")
                    db.execSQL("ALTER TABLE memories ADD COLUMN revision INTEGER NOT NULL DEFAULT 0")
                    db.execSQL("ALTER TABLE memories ADD COLUMN updated INTEGER NOT NULL DEFAULT 0")
                    db.execSQL("ALTER TABLE memories ADD COLUMN retry_after INTEGER NOT NULL DEFAULT 0")
                    db.execSQL("ALTER TABLE memories ADD COLUMN retry_count INTEGER NOT NULL DEFAULT 0")
                    db.execSQL("UPDATE memories SET updated=time")
                    db.execSQL("DELETE FROM evidence WHERE term NOT IN (SELECT text FROM terms)")
                    db.execSQL("UPDATE evidence SET cloud=coalesce((SELECT cloud FROM terms WHERE text=term),0) WHERE kind NOT IN ('contacts','manual')")
                    db.execSQL("UPDATE memories SET cloud=0 WHERE EXISTS (SELECT 1 FROM evidence e WHERE e.kind IN ('contacts','manual') AND instr(memories.text,e.term)>0)")
                    db.execSQL("CREATE INDEX evidence_origin ON evidence(origin)")
                    db.execSQL("CREATE INDEX memory_learning ON memories(learned,retry_after,time)")
                    db.execSQL("CREATE TRIGGER evidence_insert AFTER INSERT ON evidence BEGIN UPDATE terms SET score=min(1+(SELECT count(*) FROM evidence WHERE term=NEW.term),100000),cloud=min(cloud,NEW.cloud) WHERE text=NEW.term; END")
                    db.execSQL("CREATE TRIGGER evidence_delete AFTER DELETE ON evidence BEGIN UPDATE terms SET score=min(1+(SELECT count(*) FROM evidence WHERE term=OLD.term),100000) WHERE text=OLD.term; DELETE FROM terms WHERE text=OLD.term AND NOT EXISTS (SELECT 1 FROM evidence WHERE term=OLD.term); END")
                    db.execSQL("CREATE TRIGGER evidence_permission AFTER UPDATE OF cloud ON evidence BEGIN UPDATE terms SET cloud=min(cloud,NEW.cloud) WHERE text=NEW.term; END")
                    db.version=2
                }
                if(db.version==2) {
                    db.execSQL("ALTER TABLE evidence ADD COLUMN uses INTEGER NOT NULL DEFAULT 1")
                    db.execSQL("DROP TRIGGER IF EXISTS evidence_insert")
                    db.execSQL("DROP TRIGGER IF EXISTS evidence_delete")
                    db.execSQL("CREATE TRIGGER evidence_insert AFTER INSERT ON evidence BEGIN UPDATE terms SET score=min(1+coalesce((SELECT sum(uses) FROM evidence WHERE term=NEW.term),0),100000),cloud=min(cloud,NEW.cloud) WHERE text=NEW.term; END")
                    db.execSQL("CREATE TRIGGER evidence_delete AFTER DELETE ON evidence BEGIN UPDATE terms SET score=min(1+coalesce((SELECT sum(uses) FROM evidence WHERE term=OLD.term),0),100000) WHERE text=OLD.term; DELETE FROM terms WHERE text=OLD.term AND NOT EXISTS (SELECT 1 FROM evidence WHERE term=OLD.term); END")
                    db.execSQL("CREATE TRIGGER evidence_uses AFTER UPDATE OF uses ON evidence BEGIN UPDATE terms SET score=min(1+coalesce((SELECT sum(uses) FROM evidence WHERE term=NEW.term),0),100000) WHERE text=NEW.term; END")
                    repairDerived();db.version=3
                }
                if(db.version==3) {
                    db.execSQL("ALTER TABLE evidence ADD COLUMN last_used INTEGER NOT NULL DEFAULT 0")
                    db.execSQL("ALTER TABLE evidence ADD COLUMN input_code TEXT NOT NULL DEFAULT ''")
                    db.version=4
                }
                if(db.version==4) {
                    db.execSQL("ALTER TABLE evidence ADD COLUMN contexts TEXT NOT NULL DEFAULT '{}'")
                    db.execSQL("CREATE INDEX evidence_choice_recent ON evidence(kind,last_used DESC)")
                    db.version=5
                }
            }
            check(db.query("PRAGMA foreign_key_check").use { !it.moveToFirst() }) { "词库来源校验失败，原记录保留" }
        } catch(t: Throwable) { close();throw t }
    }
    override fun close() { try { db.close() } finally { retainedKey?.close() } }
    fun <T> transaction(action: () -> T): T {
        db.beginTransaction(); try { val result = action(); db.setTransactionSuccessful(); return result } finally { db.endTransaction() }
    }
    fun phonetic(s: String): String = java.text.Normalizer.normalize(transliterator.get()!!.transliterate(s),java.text.Normalizer.Form.NFD)
        .lowercase().replace("u\u0308","v").replace(Regex("\\p{M}+"),"").replace(Regex("[^a-z0-9]"), "")
    private fun cv(vararg values: Pair<String, Any>) = ContentValues().also { v -> values.forEach { (k,x) -> when (x) { is Int -> v.put(k,x); is Long -> v.put(k,x); else -> v.put(k,x.toString()) } } }
    fun saveMemory(id: String, text: String, source: String, cloud: Boolean, status: String = "issued", revision: Long? = null, time: Long = System.currentTimeMillis()) = transaction {
        val old=db.query("SELECT text,cloud,revision FROM memories WHERE id=?",arrayOf<Any>(id)).use { c ->
            if(c.moveToFirst())Triple(c.getString(0),c.getInt(1),c.getLong(2)) else null
        }
        if(revision!=null && old!=null && revision<=old.third)return@transaction
        val consent=cloud && (old==null || old.second==1) && !containsLocalOnly(text)
        val next=revision ?: ((old?.third ?: 0)+1)
        if(old==null)db.insert("memories",SQLiteDatabase.CONFLICT_ABORT,cv("id" to id,"text" to text,"time" to time,"source" to source,"status" to status,"cloud" to if(consent)1 else 0,"revision" to next,"updated" to System.currentTimeMillis()))
        else db.execSQL("UPDATE memories SET text=?,source=?,status=?,cloud=?,revision=?,updated=?,learned=CASE WHEN text<>? THEN 0 ELSE learned END,retry_after=0,retry_count=0 WHERE id=?",arrayOf<Any>(text,source,status,if(consent)1 else 0,next,System.currentTimeMillis(),text,id))
        if(text.isEmpty())db.execSQL("UPDATE memories SET learned=2,cloud=0 WHERE id=?",arrayOf<Any>(id))
        if(old==null || old.first!=text)trimEvidence(id,text)
        if(!consent)db.execSQL("UPDATE evidence SET cloud=0 WHERE origin=?",arrayOf<Any>(id))
        StoreEvents.changed()
    }
    fun confirm(id: String) { db.execSQL("UPDATE memories SET status='observed' WHERE id=?", arrayOf<Any>(id)) }
    fun replaceMemory(id: String, value: String, status: String = "corrected") {
        val old=memoriesById(id) ?: return
        saveMemory(id,value,old.source,old.cloud,status)
    }
    private fun memoriesById(id: String): Memory? = db.query("SELECT id,text,time,source,status,cloud FROM memories WHERE id=?",arrayOf<Any>(id)).use { c ->
        if(c.moveToFirst())Memory(c.getString(0),c.getString(1),c.getLong(2),c.getString(3),c.getString(4),c.getInt(5)==1) else null
    }
    private fun trimEvidence(id: String,text: String) {
        // Edits can change a preceding context even when the selected word survives.
        db.execSQL("UPDATE evidence SET contexts='{}' WHERE origin=?",arrayOf<Any>(id))
        db.execSQL("DELETE FROM evidence WHERE origin=? AND (kind='memory' OR instr(?,term)=0)",arrayOf<Any>(id,text))
        val counts=mutableListOf<Pair<String,Int>>()
        db.query("SELECT term,uses FROM evidence WHERE origin=? AND kind='choice'",arrayOf<Any>(id)).use { c ->
            while(c.moveToNext())counts+=c.getString(0) to minOf(c.getInt(1),InputHistory.occurrences(text,c.getString(0)))
        }
        for((term,count) in counts)db.execSQL("UPDATE evidence SET uses=? WHERE term=? AND origin=?",arrayOf<Any>(count,term,id))
    }
    fun applyDraft(snapshot: DraftSnapshot) = transaction {
        if(snapshot.remember && db.query("SELECT revision FROM memories WHERE id=?",arrayOf<Any>(snapshot.id)).use { it.moveToFirst() && it.getLong(0)>=snapshot.revision })return@transaction
        if(snapshot.remember)saveMemory(snapshot.id,snapshot.text,snapshot.source,snapshot.cloud,revision=snapshot.revision,time=snapshot.time)
        else trimEvidence(snapshot.id,snapshot.text)
        for(choice in snapshot.choices)if(snapshot.text.contains(choice.text)) {
            addTerm(choice.text,choice.pinyin,"choice",snapshot.id,choice.cloud)
            val used=CandidateRanking.validTime(choice.lastUsed)
            val code=choice.inputCode.takeIf(CandidateRanking::validCode).orEmpty()
            db.execSQL("UPDATE evidence SET kind='choice',uses=?,last_used=max(last_used,?),input_code=CASE WHEN ?<>'' AND ?>=last_used THEN ? ELSE input_code END WHERE term=? AND origin=?",arrayOf<Any>(minOf(choice.count.coerceIn(1,100000),InputHistory.occurrences(snapshot.text,choice.text)),used,code,used,code,choice.text,snapshot.id))
            db.execSQL("UPDATE evidence SET contexts=? WHERE term=? AND origin=?",arrayOf<Any>(CandidateRanking.encodeContexts(choice.contexts,minOf(choice.count,InputHistory.occurrences(snapshot.text,choice.text))),choice.text,snapshot.id))
        }
        StoreEvents.changed()
    }
    fun memories(query: String = "", offset: Int = 0, limit: Int = 60): List<Memory> {
        val out = mutableListOf<Memory>()
        db.query("SELECT id,text,time,source,status,cloud FROM memories WHERE text<>'' AND instr(text,?)>0 ORDER BY time DESC LIMIT ? OFFSET ?", arrayOf<Any>(query, limit.coerceIn(1,200).toString(), offset.toString())).use { c ->
            while(c.moveToNext()) out += Memory(c.getString(0),c.getString(1),c.getLong(2),c.getString(3),c.getString(4),c.getInt(5)==1)
        }; return out
    }
    fun pending(limit: Int = 12,cloudRetry: Boolean=false): List<Memory> {
        val retry=if(cloudRetry)" OR (learned=1 AND cloud=1 AND retry_after<=?)" else ""
        val args=if(cloudRetry)arrayOf<Any>(System.currentTimeMillis(),limit) else arrayOf<Any>(limit)
        val out=mutableListOf<Memory>()
        db.query("SELECT id,text,time,source,status,cloud FROM memories WHERE text<>'' AND (learned=0$retry) ORDER BY CASE WHEN learned=0 THEN 0 ELSE 1 END,retry_after,time LIMIT ?",args).use { c -> while(c.moveToNext())out+=Memory(c.getString(0),c.getString(1),c.getLong(2),c.getString(3),c.getString(4),c.getInt(5)==1) }
        return out
    }
    fun pendingCloud(limit: Int=4): List<Memory> {
        val out=mutableListOf<Memory>()
        db.query("SELECT id,text,time,source,status,cloud FROM memories WHERE text<>'' AND learned=1 AND cloud=1 AND retry_after<=? ORDER BY retry_after,time LIMIT ?",arrayOf<Any>(System.currentTimeMillis(),limit)).use { c -> while(c.moveToNext())out+=Memory(c.getString(0),c.getString(1),c.getLong(2),c.getString(3),c.getString(4),c.getInt(5)==1) }
        return out
    }
    fun markLearned(id: String,cloudDone: Boolean=false) { db.execSQL("UPDATE memories SET learned=?,retry_after=0,retry_count=0 WHERE id=?",arrayOf<Any>(if(cloudDone)2 else 1,id)) }
    fun retryLearning(id: String) { db.execSQL("UPDATE memories SET retry_count=min(retry_count+1,10),retry_after=?+min(30000*(1<<min(retry_count,10)),21600000) WHERE id=?",arrayOf<Any>(System.currentTimeMillis(),id)) }
    fun learningOutstanding(cloud: Boolean): Boolean = db.query("SELECT 1 FROM memories WHERE text<>'' AND (learned=0${if(cloud)" OR (learned=1 AND cloud=1)" else ""}) LIMIT 1").use { it.moveToFirst() }
    fun exists(id: String, text: String): Boolean = db.query("SELECT 1 FROM memories WHERE id=? AND text=?",arrayOf<Any>(id,text)).use { it.moveToFirst() }
    fun canLearnCloud(memory: Memory): Boolean = db.query("SELECT 1 FROM memories WHERE id=? AND text=? AND cloud=1",arrayOf<Any>(memory.id,memory.text)).use { it.moveToFirst() } && !containsLocalOnly(memory.text)
    fun count(table: String): Long {
        require(table in setOf("memories","terms","clips"))
        return db.query("SELECT count(*) FROM $table"+if(table=="memories")" WHERE text<>''" else "",emptyArray<String>()).use { it.moveToFirst();it.getLong(0) }
    }
    // Fresh user typing has its own consent. Explicitly local names/terms stay protected even when retyped.
    // Local memory-derived terms are never uploaded as hints; their mere spelling must not taint unrelated fresh text.
    fun containsLocalOnly(text: String): Boolean = db.query("SELECT 1 FROM terms t WHERE t.cloud=0 AND instr(?,t.text)>0 AND EXISTS (SELECT 1 FROM evidence e WHERE e.term=t.text AND e.kind IN ('contacts','manual')) LIMIT 1",arrayOf<Any>(text)).use { it.moveToFirst() }
    fun cloudHints(values: List<String>, limit: Int = 32): List<String> {
        val requested=values.distinct().take(limit.coerceIn(1,64))
        if(requested.isEmpty())return emptyList()
        val permitted=mutableSetOf<String>()
        db.query("SELECT text FROM terms WHERE cloud=1 AND text IN (${requested.joinToString(",") { "?" }})",requested.map { it as Any }.toTypedArray()).use { c -> while(c.moveToNext())permitted+=c.getString(0) }
        return requested.filter { it in permitted && !containsLocalOnly(it) }
    }
    fun cloudSpeechHints(): List<String> = cloudHints(terms(limit=64,cloudOnly=true).map { it.text },64)
    private fun removeEvidence(id: String) {
        db.execSQL("DELETE FROM evidence WHERE origin=?",arrayOf<Any>(id))
        StoreEvents.changed()
    }
    fun deleteMemory(id: String) = transaction { removeEvidence(id);db.execSQL("DELETE FROM memories WHERE id=?",arrayOf<Any>(id)) }
    fun deleteAllMemories() = transaction {
        db.execSQL("DELETE FROM evidence WHERE kind='memory' OR origin IN (SELECT id FROM memories)");StoreEvents.changed();db.execSQL("DELETE FROM memories")
    }
    fun deleteContacts() = transaction {
        db.execSQL("DELETE FROM evidence WHERE kind='contacts'");StoreEvents.changed()
    }
    fun addTerm(raw: String, pinyin: String = "", source: String = "manual", origin: String = "manual", cloud: Boolean = false, explicit: Boolean = false) {
        val text = TextRules.cleanTerm(raw) ?: raw.trim().takeIf { source=="choice" && it.length==1 && it[0].code in 0x3400..0x9fff } ?: return
        if (explicit) db.execSQL("DELETE FROM forgotten WHERE text=?",arrayOf<Any>(text))
        if (db.query("SELECT 1 FROM forgotten WHERE text=?",arrayOf<Any>(text)).use { it.moveToFirst() }) return
        val py = (if(pinyin.isBlank())phonetic(text) else pinyin.lowercase().replace("ü","v").replace(Regex("[^a-z0-9]"),""))
        transaction {
            val consent=cloud && source !in setOf("contacts","manual") && !db.query("SELECT 1 FROM memories WHERE id=? AND cloud=0",arrayOf<Any>(origin)).use { it.moveToFirst() }
            db.insert("terms",SQLiteDatabase.CONFLICT_IGNORE,cv("text" to text,"pinyin" to py,"score" to 1,"cloud" to if(consent)1 else 0,"source" to source,"pinned" to if(explicit)1 else 0))
            if(explicit) db.execSQL("UPDATE terms SET pinyin=?,pinned=1 WHERE text=?",arrayOf<Any>(py,text))
            // The most restrictive origin governs all derived use. Importing a contact cannot expose its name to the API.
            if(!consent) db.execSQL("UPDATE terms SET cloud=0 WHERE text=?",arrayOf<Any>(text))
            db.insert("evidence",SQLiteDatabase.CONFLICT_IGNORE,cv("term" to text,"origin" to origin,"kind" to source,"cloud" to if(consent)1 else 0))
            if(!consent)db.execSQL("UPDATE evidence SET cloud=0 WHERE term=? AND origin=?",arrayOf<Any>(text,origin))
            StoreEvents.changed()
        }
    }
    fun learnChoice(text: String, id: String, cloud: Boolean, pinyin: String="") { addTerm(text,pinyin=pinyin,source="choice",origin=id,cloud=cloud) }
    private val rankedColumns="text,pinyin,score,cloud,source,coalesce((SELECT max(last_used) FROM evidence WHERE term=terms.text AND kind='choice'),0) AS last_used"
    private fun rankingOrder(): String {
        val now=System.currentTimeMillis()
        return "CASE WHEN last_used BETWEEN ${now-CandidateRanking.RECENT_WINDOW} AND ${now+60000} THEN last_used ELSE 0 END DESC,score DESC,pinned DESC,length(text),text"
    }
    fun terms(prefix: String = "", limit: Int = 100, cloudOnly: Boolean = false, nineKey: Boolean = false, offset: Int = 0, rankingContext: String = ""): List<Term> {
        val out=mutableListOf<Term>()
        // Only this validated ASCII code is used in the SQL expression; free-form prefixes remain bound.
        val code=CandidateRanking.inputCode(prefix,nineKey).takeIf(CandidateRanking::validCode).orEmpty()
        val alias=if(code.isEmpty())"0" else "EXISTS(SELECT 1 FROM evidence WHERE term=terms.text AND kind='choice' AND input_code='$code')"
        val columns="$rankedColumns,CASE WHEN $alias THEN '$code' ELSE '' END AS input_code"
        // Context keys contain only Unicode letters. Encode as a bound UTF-8 hex literal,
        // so no user-controlled character is ever interpolated as SQL syntax.
        val ctx=CandidateRanking.context(rankingContext)
        val key=("\""+ctx+"\":").toByteArray(Charsets.UTF_8).joinToString("") { "%02x".format(it.toInt() and 255) }
        val contextOrder=if(ctx.isEmpty())"" else "CASE WHEN EXISTS(SELECT 1 FROM evidence e WHERE e.term=terms.text AND e.kind='choice' AND instr(e.contexts,CAST(X'$key' AS TEXT))>0) THEN 0 ELSE 1 END,"
        if(nineKey && prefix.isNotEmpty()) {
            // GLOB classes resolve the phone keys against existing encrypted pinyin. No destructive
            // migration or plaintext copy; exact phonetic matches rank ahead of longer completions.
            val pattern=NineKey.glob(prefix) ?: return out
            db.query("SELECT $columns FROM terms WHERE (pinyin GLOB ? OR $alias) ${if(cloudOnly)"AND cloud=1" else ""} ORDER BY CASE WHEN pinyin GLOB ? OR $alias THEN 0 ELSE 1 END,$contextOrder${rankingOrder()} LIMIT ? OFFSET ?",arrayOf<Any>(pattern+"*",pattern,limit.coerceIn(1,513),offset.coerceAtLeast(0))).use { c ->
                while(c.moveToNext())out+=Term(c.getString(0),c.getString(1),c.getInt(2),c.getInt(3)==1,c.getString(4),c.getLong(5),c.getString(6))
            }
            return out
        }
        db.query("SELECT $columns FROM terms WHERE (pinyin LIKE ? ESCAPE '\\' OR instr(text,?)>0 OR $alias) ${if(cloudOnly)"AND cloud=1" else ""} ORDER BY CASE WHEN pinyin=? OR $alias THEN 0 ELSE 1 END,$contextOrder${rankingOrder()} LIMIT ? OFFSET ?",arrayOf<Any>(prefix.replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%",prefix,prefix,limit.coerceIn(1,513),offset.coerceAtLeast(0))).use { c -> while(c.moveToNext()) out+=Term(c.getString(0),c.getString(1),c.getInt(2),c.getInt(3)==1,c.getString(4),c.getLong(5),c.getString(6)) }
        return out
    }
    /** Candidate retrieval is bounded; all sorting happens before pagination within each pool. */
    fun rankedTerms(prefix: String,context: String,nineKey: Boolean=false,offset: Int=0,limit: Int=65): List<Term> {
        val poolStart=(offset.coerceAtLeast(0)/512)*512
        val pool=terms(prefix,513,nineKey=nineKey,offset=poolStart,rankingContext=context)
        val ranked=CandidateRanking.sort(withRankingEvidence(pool.take(512)),context=context)
            .sortedBy { if(CandidateRanking.exact(it,prefix,nineKey))0 else 1 }
        val take=limit.coerceIn(1,65)
        val page=ranked.drop(offset.coerceAtLeast(0)-poolStart).take(take)
        return if(page.size<take && pool.size>512)page+rankedTerms(prefix,context,nineKey,poolStart+512,take-page.size) else page
    }
    private fun withRankingEvidence(terms: List<Term>): List<Term> {
        if(terms.isEmpty())return terms
        val rows=mutableMapOf<String,MutableList<RankingEvidence>>()
        // A single indexed batch; at most 32 recent origins per candidate. Older frequency
        // remains in terms.score even after this bounded adaptation window is full.
        val marks=terms.joinToString(",") { "?" }
        db.query("SELECT term,origin,uses,last_used,contexts FROM (SELECT term,origin,uses,last_used,contexts,row_number() OVER(PARTITION BY term ORDER BY last_used DESC,origin) AS rn FROM evidence WHERE kind='choice' AND term IN ($marks)) WHERE rn<=32",terms.map { it.text as Any }.toTypedArray()).use { c ->
            while(c.moveToNext())rows.getOrPut(c.getString(0)) { mutableListOf() }+=RankingEvidence(c.getString(1),c.getInt(2),c.getLong(3),CandidateRanking.decodeContexts(c.getString(4),c.getInt(2)))
        }
        return terms.map { it.copy(evidence=rows[it.text].orEmpty()) }
    }
    fun forgetTerm(text: String) = transaction {
        db.execSQL("DELETE FROM terms WHERE text=?",arrayOf<Any>(text));db.insert("forgotten",SQLiteDatabase.CONFLICT_IGNORE,cv("text" to text));StoreEvents.changed()
    }
    fun continuationTerms(context: String): List<Term> {
        val prefixes=PredictionText.localPrefixes(context)
        if(prefixes.isEmpty())return emptyList()
        val out=mutableListOf<Term>()
        // Prefixes contain only letters/digits. Bound parameters still keep user text out of SQL.
        val where=prefixes.joinToString(" OR ") { "text LIKE ?" }
        db.query("SELECT $rankedColumns FROM terms WHERE $where ORDER BY ${rankingOrder()} LIMIT 64",prefixes.map { "$it%" }.toTypedArray()).use { c ->
            while(c.moveToNext())out+=Term(c.getString(0),c.getString(1),c.getInt(2),c.getInt(3)==1,c.getString(4),c.getLong(5))
        }
        return out
    }
    fun addClip(text: String) {
        if(text.isBlank() || text.length>20000)return
        db.execSQL("DELETE FROM clips WHERE text=? AND pinned=0",arrayOf<Any>(text))
        db.insert("clips",SQLiteDatabase.CONFLICT_ABORT,cv("id" to UUID.randomUUID().toString(),"text" to text,"time" to System.currentTimeMillis()))
        pruneClips()
    }
    fun pruneClips() {
        db.execSQL("DELETE FROM clips WHERE pinned=0 AND time<?",arrayOf<Any>(System.currentTimeMillis()-7*86400000L))
        db.execSQL("DELETE FROM clips WHERE pinned=0 AND id NOT IN (SELECT id FROM clips WHERE pinned=0 ORDER BY time DESC LIMIT 200)")
    }
    fun clips(): List<Clip> {
        pruneClips();val out=mutableListOf<Clip>()
        db.query("SELECT id,text,pinned FROM clips ORDER BY pinned DESC,time DESC LIMIT 250",emptyArray<String>()).use { c -> while(c.moveToNext())out+=Clip(c.getString(0),c.getString(1),c.getInt(2)==1) };return out
    }
    fun clipAction(id: String, pin: Boolean?) { if(pin==null)db.execSQL("DELETE FROM clips WHERE id=?",arrayOf<Any>(id)) else db.execSQL("UPDATE clips SET pinned=? WHERE id=?",arrayOf<Any>(if(pin)1 else 0,id)) }
    fun clearClips() { db.execSQL("DELETE FROM clips") }
    fun exportRows(write: (JSONObject)->Unit) {
        transaction {
            for(table in listOf("memories","terms","evidence","forgotten","clips")) {
                db.query("SELECT * FROM $table",emptyArray<String>()).use { c -> while(c.moveToNext()) {
                    val j=JSONObject().put("table",table)
                    c.columnNames.forEachIndexed { i,n -> if(c.getType(i)==android.database.Cursor.FIELD_TYPE_INTEGER)j.put(n,c.getLong(i)) else j.put(n,c.getString(i)) }
                    write(j)
                } }
            }
        }
    }
    data class ImportResult(var added: Int=0,var merged: Int=0,var skipped: Int=0,var keptLocal: Int=0,var replacedLocal: Int=0) {
        override fun toString()="新增 $added 条，合并 $merged 条，跳过 $skipped 条；记忆冲突保留本机 $keptLocal 条、采用备份 $replacedLocal 条"
    }
    fun importRows(rows: Sequence<JSONObject>, preview: Boolean = false): ImportResult {
        db.beginTransaction()
        try {
        // Stage on the encrypted connection, not in a plaintext file or an unbounded heap list.
        // Resolve every memory before its evidence, including backups with reordered rows.
        db.execSQL("CREATE TABLE loop_import_rows(position INTEGER PRIMARY KEY,kind TEXT NOT NULL,payload TEXT NOT NULL)")
        db.execSQL("CREATE TABLE loop_import_origins(id TEXT PRIMARY KEY,accepted INTEGER NOT NULL)")
        val result=try {
            rows.forEach { row ->
                val kind=row.getString("table")
                check(kind in setOf("memories","terms","evidence","forgotten","clips")) { "无效备份表" }
                db.insert("loop_import_rows",SQLiteDatabase.CONFLICT_ABORT,cv("kind" to kind,"payload" to row.toString()))
            }
            val ordered=sequence {
                for(kind in listOf("memories","terms","evidence","forgotten","clips")) {
                    db.query("SELECT payload FROM loop_import_rows WHERE kind=? ORDER BY position",arrayOf<Any>(kind)).use { c ->
                        while(c.moveToNext())yield(JSONObject(c.getString(0)))
                    }
                }
            }
            mergeRows(ordered)
        } finally {
            db.execSQL("DROP TABLE loop_import_origins")
            db.execSQL("DROP TABLE loop_import_rows")
        }
        if(!preview)db.setTransactionSuccessful()
        return result
        } finally { db.endTransaction() }
    }
    private fun mergeRows(rows: Sequence<JSONObject>): ImportResult {
        val result=ImportResult()
        val fields=mapOf("memories" to setOf("id","text","time","source","status","cloud","learned"),"terms" to setOf("text","pinyin","score","cloud","source","pinned"),"evidence" to setOf("term","origin","kind"),"forgotten" to setOf("text"),"clips" to setOf("id","text","time","pinned"))
        rows.forEach { j ->
            val t=j.getString("table");val cols=fields[t] ?: error("无效备份表")
            val values=ContentValues();cols.forEach { k -> val v=j.get(k);require(v.toString().length<=200000);if(v is Number)values.put(k,v.toLong()) else values.put(k,v.toString()) }
            if(t=="memories") {
                values.put("updated",j.optLong("updated",j.getLong("time")))
                values.put("revision",j.optLong("revision",0))
                val old=memoriesById(j.getString("id"))
                if(old!=null) {
                    val local=db.query("SELECT updated,revision FROM memories WHERE id=?",arrayOf<Any>(old.id)).use { it.moveToFirst();it.getLong(0) to it.getLong(1) }
                    val allow=old.cloud && j.getInt("cloud")==1
                    val newer=values.getAsLong("updated")>local.first || (values.getAsLong("updated")==local.first && values.getAsLong("revision")>local.second)
                    db.insert("loop_import_origins",SQLiteDatabase.CONFLICT_ABORT,cv("id" to old.id,"accepted" to if(newer)1 else 0))
                    if(newer) {
                        removeEvidence(old.id);values.remove("id");values.put("cloud",if(allow)1 else 0);values.put("learned",0)
                        db.update(t,SQLiteDatabase.CONFLICT_ABORT,values,"id=?",arrayOf<Any>(old.id));result.merged++;result.replacedLocal++
                    } else { db.execSQL("UPDATE memories SET cloud=min(cloud,?) WHERE id=?",arrayOf<Any>(if(allow)1 else 0,old.id));result.skipped++;result.keptLocal++ }
                    return@forEach
                }
                db.insert("loop_import_origins",SQLiteDatabase.CONFLICT_ABORT,cv("id" to j.getString("id"),"accepted" to 1))
            }
            if(t=="evidence") {
                values.put("cloud",if(j.getString("kind") in setOf("contacts","manual"))0 else j.optInt("cloud",0).coerceIn(0,1))
                values.put("uses",j.optInt("uses",1).coerceIn(1,100000))
                values.put("last_used",CandidateRanking.validTime(j.optLong("last_used",0)))
                values.put("input_code",j.optString("input_code","").takeIf(CandidateRanking::validCode).orEmpty())
                values.put("contexts",CandidateRanking.encodeContexts(CandidateRanking.decodeContexts(j.optString("contexts","{}"),values.getAsInteger("uses")),values.getAsInteger("uses")))
                val origin=j.getString("origin")
                val decision=db.query("SELECT accepted FROM loop_import_origins WHERE id=?",arrayOf<Any>(origin)).use { if(it.moveToFirst())it.getInt(0) else null }
                val memory=memoriesById(origin)
                if(decision==0 || (decision==null && memory!=null)) {
                    db.execSQL("UPDATE evidence SET cloud=min(cloud,?) WHERE term=? AND origin=?",arrayOf<Any>(values.getAsInteger("cloud"),j.getString("term"),origin))
                    result.skipped++;return@forEach
                }
                if(memory!=null) {
                    val uses=InputHistory.occurrences(memory.text,j.getString("term"))
                    if(uses==0) { result.skipped++;return@forEach }
                    values.put("uses",minOf(values.getAsInteger("uses"),uses))
                    values.put("contexts",CandidateRanking.encodeContexts(CandidateRanking.decodeContexts(values.getAsString("contexts"),values.getAsInteger("uses")),values.getAsInteger("uses")))
                }
            }
            val inserted=db.insert(t,SQLiteDatabase.CONFLICT_IGNORE,values)
            if(inserted!=-1L)result.added++ else when(t) {
                "terms" -> { db.execSQL("UPDATE terms SET cloud=min(cloud,?),pinyin=CASE WHEN pinned=0 AND ?=1 THEN ? ELSE pinyin END,pinned=max(pinned,?) WHERE text=?",arrayOf<Any>(j.getInt("cloud"),j.getInt("pinned"),j.getString("pinyin"),j.getInt("pinned"),j.getString("text")));result.merged++ }
                "evidence" -> { db.execSQL("UPDATE evidence SET cloud=min(cloud,?),uses=max(uses,?),last_used=max(last_used,?),input_code=CASE WHEN ?<>'' AND ?>last_used THEN ? ELSE input_code END,contexts=CASE WHEN ?>last_used THEN ? ELSE contexts END WHERE term=? AND origin=?",arrayOf<Any>(values.getAsInteger("cloud"),values.getAsInteger("uses"),values.getAsLong("last_used"),values.getAsString("input_code"),values.getAsLong("last_used"),values.getAsString("input_code"),values.getAsLong("last_used"),values.getAsString("contexts"),j.getString("term"),j.getString("origin")));result.merged++ }
                "clips" -> { db.execSQL("UPDATE clips SET pinned=max(pinned,?) WHERE id=?",arrayOf<Any>(j.getInt("pinned"),j.getString("id")));result.merged++ }
                else -> result.skipped++
            }
        }
        db.execSQL("DELETE FROM terms WHERE text IN (SELECT text FROM forgotten)")
        db.execSQL("UPDATE memories SET cloud=0 WHERE EXISTS (SELECT 1 FROM evidence e WHERE e.kind IN ('contacts','manual') AND instr(memories.text,e.term)>0)")
        repairDerived();pruneClips()
        return result
    }
    private fun repairDerived() {
        db.execSQL("DELETE FROM evidence WHERE term NOT IN (SELECT text FROM terms)")
        db.execSQL("UPDATE evidence SET cloud=0 WHERE kind IN ('contacts','manual') OR origin IN (SELECT id FROM memories WHERE cloud=0)")
        db.execSQL("DELETE FROM terms WHERE text NOT IN (SELECT term FROM evidence)")
        db.execSQL("UPDATE terms SET score=min(1+coalesce((SELECT sum(uses) FROM evidence WHERE term=text),0),100000),cloud=min(cloud,coalesce((SELECT min(cloud) FROM evidence WHERE term=text),0))")
        StoreEvents.changed()
    }
    companion object {
        const val SCHEMA_VERSION=5
        private var instance: PersonalStore? = null
        private var lastFailure: DatabaseUnavailable?=null
        private var failedUntil=0L
        @Synchronized fun get(c: Context): PersonalStore {
            instance?.let { return it }
            lastFailure?.let { if(android.os.SystemClock.elapsedRealtime()<failedUntil)throw it }
            return try { open(c.applicationContext).also { instance=it;lastFailure=null } }
            catch(e: Exception) { val error=DatabaseUnavailable(e);lastFailure=error;failedUntil=android.os.SystemClock.elapsedRealtime()+30000;throw error }
        }
        @Synchronized fun repair(c: Context): String {
            lastFailure=null;failedUntil=0
            val working=runCatching { get(c) }.getOrNull()
            if(working!=null)return "数据库已可正常打开，无需切换。请重新尝试导入通讯录。"
            instance=DatabaseAccess(c.applicationContext).recoverLegacy()
            lastFailure=null;failedUntil=0;StoreEvents.changed()
            return "旧库已通过校验，已恢复至新的加密数据库。旧文件与旧 Key 保留。请重新尝试导入通讯录。"
        }
        @Synchronized fun startSeparate(c: Context): String {
            lastFailure=null;failedUntil=0
            if(runCatching { get(c) }.isSuccess)return "数据库已可用，无需启用新库。"
            instance=DatabaseAccess(c.applicationContext).createSeparate()
            lastFailure=null;failedUntil=0;StoreEvents.changed()
            return "已启用新的加密数据库。API Key 和设置保留，旧数据库与旧密钥保留在本机。请重新导入通讯录；旧记忆尚未恢复，可从加密备份合并导入。"
        }
        private fun open(c: Context): PersonalStore {
            val trace=DiagnosticLog.begin(DiagnosticLog.Area.DATABASE)
            try {
                trace.mark(DiagnosticLog.Step.POLICY);check(LoopApp.unlocked(c))
                return DatabaseAccess(c).open(trace).also { trace.success("schema" to SCHEMA_VERSION.toLong()) }
            } catch(t: Throwable) { trace.failure(t);throw t }
        }
    }
}

/** SQLCipher keeps this array by reference. Its lifetime must match all pooled connections. */
internal class DatabaseKey(source: ByteArray): AutoCloseable {
    val bytes=source.copyOf()
    override fun close() { bytes.fill(0) }
}

internal object StoreEvents {
    private val listeners=java.util.concurrent.CopyOnWriteArraySet<()->Unit>()
    private val scheduled=java.util.concurrent.atomic.AtomicBoolean()
    private val version=java.util.concurrent.atomic.AtomicLong()
    val revision get()=version.get()
    fun add(listener: ()->Unit) { listeners.add(listener) }
    fun remove(listener: ()->Unit) { listeners.remove(listener) }
    fun changed() {
        version.incrementAndGet()
        if(scheduled.compareAndSet(false,true))LoopApp.main.postDelayed({ scheduled.set(false);listeners.forEach { it() } },100)
    }
}
