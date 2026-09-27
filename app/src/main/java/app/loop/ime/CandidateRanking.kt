package app.loop.ime

/** Exact pronunciation first; recent explicit choices outrank older frequency evidence. */
object CandidateRanking {
    const val RECENT_WINDOW=7L*24*60*60*1000
    fun inputCode(raw: String,nine: Boolean)=(if(nine)"9:" else "26:")+raw.replace("'","")
    fun validCode(code: String)=Regex("(?:9:[2-9]{1,64}|26:[a-z]{1,64})").matches(code)
    fun exact(term: Term,raw: String,nine: Boolean)=term.inputCode==inputCode(raw,nine) || NineKey.matches(term.pinyin,raw,nine)
    fun recent(time: Long,now: Long)=if(time>0 && time>=now-RECENT_WINDOW && time<=now+60000)time else 0L
    fun sort(terms: List<Term>,now: Long=System.currentTimeMillis())=terms.sortedWith(
        compareByDescending<Term> { recent(it.lastUsed,now) }.thenByDescending { it.score }
    )
}
