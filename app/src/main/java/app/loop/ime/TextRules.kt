package app.loop.ime

/** Pure rules; requests are data, never instructions from the input field. */
object TextRules {
    fun distance(a: String, b: String): Int {
        var row = IntArray(b.length + 1) { it }
        for (i in a.indices) {
            val next = IntArray(b.length + 1); next[0] = i + 1
            for (j in b.indices) next[j + 1] = minOf(next[j] + 1, row[j + 1] + 1, row[j] + if (a[i] == b[j]) 0 else 1)
            row = next
        }
        return row[b.length]
    }
    // Match the entire quantity before bare numbers. A one-character unit/negation edit can
    // reverse the meaning even when the edit distance is small.
    private val number = "[+＋\\-−]?[0-9０-９]+(?:[.,，:：/年日月\\-][0-9０-９]+)*"
    private val unit = "(?:公斤|千克|毫克|微克|千米|厘米|毫米|毫升|升|小时|分钟|秒钟|万元|元|块|米|秒|分|克|度|天|周|月|年|[%％°℃℉]|[a-zA-Zμµ]+)"
    private val protected = Regex("[a-zA-Z]+://\\S+|[\\w.+-]+@[\\w.-]+|[¥￥$€£]?$number(?:[ \\t]*$unit)?|[一二三四五六七八九十百千万两]+$unit|不|没|无|未|非|别|勿|(?i:\\b(?:no|not|never|neither|nor|without|cannot|[a-z]+n['’]t)\\b)")
    fun safeCorrection(original: String, proposed: String, protectedTerms: List<String> = emptyList()): Boolean {
        if (original.isBlank() || original.length > 200 || proposed.isBlank() || proposed.length > 220) return false
        if (proposed.any { it == '\n' || it == '\r' || it == '\u0000' }) return false
        if (protected.findAll(original).map { it.value }.toList() != protected.findAll(proposed).map { it.value }.toList()) return false
        if (protectedTerms.any { original.contains(it) && !proposed.contains(it) }) return false
        return distance(original, proposed) <= minOf(3,maxOf(1, (original.length * .1).toInt()))
    }
    fun cleanTerm(s: String) = s.trim().takeIf { it.length in 2..32 && it.none { ch -> ch.isISOControl() || ch in "\"{}[]\\/:@" } }
    fun validPrediction(s: String) = s.isNotBlank() && s.length <= 60 && s.none { it == '\n' || it == '\r' || it.isISOControl() }
    fun safeEndpoint(raw: String): java.net.URI {
        val u = java.net.URI(raw.trim())
        require(u.scheme == "https" && !u.host.isNullOrBlank() && u.userInfo == null && u.fragment == null) { "API 必须是无账号信息的 HTTPS 完整地址" }
        return u
    }
    fun suffixPatchAllowed(snapshot: String, current: String, original: String): Boolean =
        snapshot.endsWith(original) && current == snapshot && original.isNotEmpty()
}
