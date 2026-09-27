package app.loop.ime

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class TextEdit(val start: Int,val end: Int,val text: String)
data class LearnedChoice(val text: String,val pinyin: String="",val cloud: Boolean=false,val count: Int=1)
data class DraftSnapshot(val id: String,val text: String,val source: String,val cloud: Boolean,
    val remember: Boolean,val revision: Long,val time: Long,val choices: List<LearnedChoice> = emptyList()) {
    fun encode(): ByteArray=JSONObject().put("id",id).put("text",text).put("source",source).put("cloud",cloud)
        .put("remember",remember).put("revision",revision).put("time",time).put("choices",JSONArray(choices.map {
            JSONObject().put("text",it.text).put("pinyin",it.pinyin).put("cloud",it.cloud).put("count",it.count)
        })).toString().toByteArray(Charsets.UTF_8)
    companion object {
        fun decode(bytes: ByteArray): DraftSnapshot {
            val j=JSONObject(bytes.toString(Charsets.UTF_8));val a=j.getJSONArray("choices")
            return DraftSnapshot(j.getString("id"),j.getString("text"),j.getString("source"),j.getBoolean("cloud"),j.getBoolean("remember"),j.getLong("revision"),j.getLong("time"),
                (0 until a.length()).map { val x=a.getJSONObject(it);LearnedChoice(x.getString("text"),x.getString("pinyin"),x.getBoolean("cloud"),x.optInt("count",1).coerceIn(1,100000)) })
        }
    }
}

/** Tracks only successful edits made by this IME, in the current editor's coordinates. */
internal class InputHistory(private val write: (DraftSnapshot)->Unit) {
    private data class Chunk(var start: Int,var snapshot: DraftSnapshot,var hadChoice: Boolean=false)
    private val chunks=mutableListOf<Chunk>()
    private var active: Chunk?=null
    val lastId get()=active?.snapshot?.id.orEmpty()
    private fun publish(chunk: Chunk,text: String=chunk.snapshot.text) {
        chunk.snapshot=chunk.snapshot.copy(text=text,revision=chunk.snapshot.revision+1,
            choices=chunk.snapshot.choices.map { it.copy(count=minOf(it.count,occurrences(text,it.text))) }.filter { it.count>0 })
        if(chunk.snapshot.remember || chunk.hadChoice)write(chunk.snapshot)
    }
    fun clear() { chunks.clear();active=null }
    fun separate() { active=null }
    fun apply(edit: TextEdit?,source: String,remember: Boolean,cloud: Boolean) {
        if(edit==null)return
        val start=edit.start;val end=maxOf(start,edit.end);val delta=edit.text.length-(end-start)
        // In-place edits retain their record and conservatively combine consent.
        val containing=chunks.firstOrNull { c -> start>=0 && c.start>=0 && start>=c.start && end<=c.start+c.snapshot.text.length &&
            c.snapshot.remember==remember && (end>start || (c===active && c.start+c.snapshot.text.length==start && c.snapshot.text.length+edit.text.length<=1800)) }
        if(containing!=null) {
            val oldEnd=containing.start+containing.snapshot.text.length
            val value=containing.snapshot.text.replaceRange(start-containing.start,end-containing.start,edit.text)
            containing.snapshot=containing.snapshot.copy(cloud=containing.snapshot.cloud && cloud,source=if(containing.snapshot.source==source)source else "mixed")
            publish(containing,value)
            chunks.filter { it!==containing && it.start>=oldEnd }.forEach { it.start+=delta }
            active=containing;return
        }
        for(chunk in chunks.toList()) {
            if(chunk.start<0 || start<0)continue
            val oldStart=chunk.start;val oldEnd=oldStart+chunk.snapshot.text.length
            if(oldEnd<=start)continue
            if(oldStart>=end) { chunk.start+=delta;continue }
            val before=chunk.snapshot.text.take((start-oldStart).coerceIn(0,chunk.snapshot.text.length))
            val after=chunk.snapshot.text.drop((end-oldStart).coerceIn(0,chunk.snapshot.text.length))
            if(before.isNotEmpty() && after.isNotEmpty() && edit.text.isNotEmpty()) {
                val original=chunk.snapshot
                publish(chunk,before)
                val right=Chunk(start+edit.text.length,original.copy(id=UUID.randomUUID().toString(),text=after,revision=0,
                    choices=original.choices.filter { after.contains(it.text) }),chunk.hadChoice)
                chunks.add(right);publish(right)
            } else {
                if(before.isEmpty())chunk.start=start+edit.text.length
                publish(chunk,before+after)
            }
        }
        if(edit.text.isNotEmpty()) {
            val chunk=Chunk(start,DraftSnapshot(UUID.randomUUID().toString(),edit.text,source,cloud,remember,0,System.currentTimeMillis()))
            chunks.add(chunk);active=chunk;publish(chunk)
        } else active=null
    }
    fun learn(text: String,pinyin: String,cloud: Boolean) {
        val chunk=active ?: return
        if(!chunk.snapshot.text.contains(text))return
        chunk.hadChoice=true
        val old=chunk.snapshot.choices.firstOrNull { it.text==text }
        val choice=LearnedChoice(text,pinyin.ifBlank { old?.pinyin.orEmpty() },cloud && (old?.cloud ?: true),(old?.count ?: 0)+1)
        chunk.snapshot=chunk.snapshot.copy(choices=chunk.snapshot.choices.filterNot { it.text==text }+choice)
        publish(chunk)
    }
    fun context(cursor: Int): String {
        if(cursor<0)return active?.snapshot?.text.orEmpty().takeLast(120)
        var at=cursor;var result=""
        for(chunk in chunks.filter { it.start>=0 && it.snapshot.text.isNotEmpty() }.sortedByDescending { it.start }) {
            val end=chunk.start+chunk.snapshot.text.length
            if(at>chunk.start && at<=end) { result=chunk.snapshot.text.take(at-chunk.start)+result;at=chunk.start }
            if(result.length>=120)break
        }
        return result.takeLast(120)
    }
    companion object {
        fun occurrences(text: String,term: String): Int {
            if(term.isEmpty())return 0
            var count=0;var from=0
            while(from<=text.length-term.length) {
                val at=text.indexOf(term,from);if(at<0)break
                count++;from=at+term.length
            }
            return count
        }
    }
}
