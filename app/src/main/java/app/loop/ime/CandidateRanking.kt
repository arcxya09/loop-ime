package app.loop.ime

import org.json.JSONObject
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.ln1p

/** One retractable source, shared by the persisted model and pending journal overlay. */
data class RankingEvidence(val origin: String,val count: Int,val time: Long,val contexts: Map<String,Int> = emptyMap()) {
    // Immutable sufficient statistics: persisted rows are warmed by background ranking.
    internal val suffixCounts: Map<String,Int> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        val counts=mutableMapOf<String,Int>()
        contexts.forEach { (context,n) ->
            val points=context.codePoints().toArray()
            for(order in 1..minOf(4,points.size)) {
                val key=String(points,points.size-order,order);counts[key]=(counts[key] ?: 0)+n
            }
        }
        counts
    }
}

/** On-device cache + interpolated variable-order context model. See RANKING.md. */
object CandidateRanking {
    const val RECENT_WINDOW=7L*24*60*60*1000 // Legacy retrieval window; not a scoring cutoff.
    const val DAY=86400000L
    const val MAX_CONTEXTS=16
    fun inputCode(raw: String,nine: Boolean)=(if(nine)"9:" else "26:")+raw.replace("'","")
    fun validCode(code: String)=Regex("(?:9:[2-9]{1,64}|26:[a-z]{1,64})").matches(code)
    fun exact(term: Term,raw: String,nine: Boolean)=term.inputCode==inputCode(raw,nine) || NineKey.matches(term.pinyin,raw,nine)
    // Never cross punctuation/whitespace; do not split supplementary Han.
    fun context(text: String): String {
        val points=text.codePoints().toArray().takeLast(4).takeLastWhile { Character.isLetter(it) }
        return String(points.toIntArray(),0,points.size).lowercase(java.util.Locale.ROOT)
    }
    fun cleanContexts(values: Map<String,Int>,count: Int): Map<String,Int> {
        var remaining=count.coerceIn(0,100000)
        val out=linkedMapOf<String,Int>()
        values.entries.take(MAX_CONTEXTS).forEach { (key,value) ->
            if(key.isNotEmpty() && key==context(key) && remaining>0 && value>0) {
                val n=minOf(value,remaining);out[key]=n;remaining-=n
            }
        }
        return out
    }
    fun encodeContexts(values: Map<String,Int>,count: Int)=JSONObject(cleanContexts(values,count)).toString()
    fun decodeContexts(raw: String,count: Int): Map<String,Int> = runCatching {
        require(raw.length<=4096)
        val json=JSONObject(raw)
        cleanContexts(json.keys().asSequence().take(MAX_CONTEXTS).associateWith { json.optInt(it,0) },count)
    }.getOrDefault(emptyMap())
    fun decay(time: Long,now: Long,halfLife: Double): Double {
        if(time<=0 || time>now+60000)return 0.0
        return exp(-ln(2.0)*(now-time).coerceAtLeast(0).toDouble()/halfLife)
    }
    fun scores(terms: List<Term>,context: String="",now: Long=System.currentTimeMillis()): List<Double> {
        if(terms.isEmpty())return emptyList()
        val evidence=terms.map { t -> t.evidence.ifEmpty { if(t.lastUsed>0)listOf(RankingEvidence("legacy",(t.score-1).coerceAtLeast(1),t.lastUsed)) else emptyList() } }
        // Log saturation prevents one repeated draft overwhelming the user's other habits.
        val masses=terms.mapIndexed { i,t ->
            1.0+ln1p(t.score.coerceAtLeast(0).toDouble())+evidence[i].sumOf { e ->
                ln1p(e.count.coerceIn(0,100000).toDouble())*(1.2*decay(e.time,now,30.0*DAY)+6.0*decay(e.time,now,0.5*DAY))
            }
        }
        val total=masses.sum();var probabilities=masses.map { it/total }
        val points=context(context).codePoints().toArray()
        val contextRows=if(points.isEmpty())emptyList() else evidence.map { rows -> rows.map { it to decay(it.time,now,30.0*DAY) } }
        // Witten–Bell-style backoff: scarce contexts borrow mass from lower orders.
        for(order in 1..points.size) {
            val suffix=String(points,points.size-order,order)
            val counts=contextRows.map { rows -> rows.sumOf { (e,weight) ->
                (e.suffixCounts[suffix] ?: 0).coerceIn(0,e.count.coerceAtLeast(0)).toDouble()*weight
            } }
            val n=counts.sum();val types=counts.count { it>0 }
            if(n>0) {
                val confidence=(n/(n+2.0*types.coerceAtLeast(1))).coerceAtMost(0.85)
                probabilities=probabilities.mapIndexed { i,p -> (1-confidence)*p+confidence*counts[i]/n }
            }
        }
        return probabilities
    }
    fun sort(terms: List<Term>,now: Long=System.currentTimeMillis(),context: String=""): List<Term> {
        val scores=scores(terms,context,now)
        return terms.indices.sortedWith(compareByDescending<Int> { scores[it] }.thenByDescending { terms[it].lastUsed.takeIf { time -> time in 1..now+60000 } ?: 0 }).map { terms[it] }
    }
}
