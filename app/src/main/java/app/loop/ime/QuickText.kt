package app.loop.ime

/** Local, bounded parsing. No message text is sent to a model or retained in storage. */
object QuickText {
    private val keyword=Regex("验证码|校验码|动态码|短信密码|一次性密码|verification\\s*code|security\\s*code|one[- ]time\\s*(?:code|password)|\\botp\\b",RegexOption.IGNORE_CASE)
    private val token=Regex("(?<![A-Za-z0-9])(?:[0-9]{4,8}|[A-Za-z0-9]{4,10})(?![A-Za-z0-9])")
    fun sensitive(text: String)=keyword.containsMatchIn(text.take(20000))
    fun codes(text: String): List<String> {
        if(text.length>20000)return emptyList()
        val anchors=keyword.findAll(text).toList()
        if(anchors.isEmpty())return emptyList()
        return token.findAll(text).filter { m ->
            m.value.any(Char::isDigit) && anchors.any { a ->
                val gap=when { m.range.first>a.range.last -> text.substring(a.range.last+1,m.range.first)
                    a.range.first>m.range.last -> text.substring(m.range.last+1,a.range.first)
                    else -> return@any false }
                val listGap=gap.replace(Regex("[0-9]{4,8}"),"")
                gap.length<=24 && (!Regex("[0-9]|订单|手机|电话|金额|分钟|元|有效期|order|phone|amount",RegexOption.IGNORE_CASE).containsMatchIn(gap) ||
                    Regex("[\\s:：,，/或]*(?:or[\\s:：,，/或]*)?",RegexOption.IGNORE_CASE).matches(listGap))
            } && !Regex("^\\s*(?:年|月|日|分钟|秒|元|minutes?|seconds?)",RegexOption.IGNORE_CASE).containsMatchIn(text.substring(m.range.last+1).take(12))
        }.map { it.value }.distinct().take(3).toList()
    }
    fun extracts(text: String): List<Pair<String,String>> {
        if(text.length>20000 || sensitive(text))return emptyList()
        val patterns=listOf("邮箱" to Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"),
            "链接" to Regex("https?://[A-Za-z0-9][^\\s<>\"，。；！？]*"),
            "号码" to Regex("(?<![0-9])(?:\\+?86[- ]?)?1[3-9][0-9]{9}(?![0-9])"))
        return patterns.flatMap { (kind,re) -> re.findAll(text).take(3).map { kind to it.value.trimEnd('.',',',';',')') }.toList() }.distinct().take(6)
    }
}

data class QuickSuggestion(val id: String,val text: String,val label: String,val source: String,val time: Long,val sensitive: Boolean)

/** Consumed event ids survive hiding/reopening the keyboard, but never a process restart. */
class SuggestionBuffer(private val now: ()->Long=System::currentTimeMillis) {
    private val entries=linkedMapOf<String,QuickSuggestion>()
    private val seen=linkedMapOf<String,Long>()
    fun offer(event: String,text: String,source: String,time: Long,otpOnly: Boolean=false) {
        prune();if(event in seen || time>now()+5000 || now()-time !in 0..TTL || text.length>20000)return
        seen[event]=time
        if(entries.values.any { it.source==source && it.time>time })return
        val sensitive=QuickText.sensitive(text)
        val values=if(sensitive)QuickText.codes(text).map { "验证码" to it } else if(otpOnly)emptyList() else listOf("粘贴" to text)+QuickText.extracts(text)
        if(values.isEmpty() && otpOnly)return
        entries.entries.removeAll { it.value.source==source }
        values.take(7).forEachIndexed { i,(label,value) -> entries["$event:$i"]=QuickSuggestion("$event:$i",value,label,source,time,sensitive) }
        while(entries.size>12)entries.remove(entries.keys.first())
    }
    fun values(): List<QuickSuggestion> { prune();return entries.values.sortedByDescending { it.time } }
    fun consume(id: String): QuickSuggestion? { prune();val s=entries[id] ?: return null;entries.entries.removeAll { it.value.source==s.source };return s }
    fun clear() { entries.clear();seen.clear() }
    fun removeSource(source: String) { entries.entries.removeAll { it.value.source==source } }
    private fun prune() { val time=now();entries.entries.removeAll { time-it.value.time !in 0..TTL };seen.entries.removeAll { time-it.value !in 0..TTL };while(seen.size>128)seen.remove(seen.keys.first()) }
    companion object { const val TTL=5*60*1000L }
}
