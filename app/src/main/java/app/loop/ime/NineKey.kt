package app.loop.ime

/** The phone-key mapping shared by the UI and personal dictionary. Rime resolves syllables. */
object NineKey {
    private const val DIGITS="22233344455566677778889999"
    private val letters=listOf("abc","def","ghi","jkl","mno","pqrs","tuv","wxyz")
    fun encode(pinyin: String): String = buildString {
        for(c in pinyin.lowercase().replace('ü','v'))when(c) {
            in 'a'..'z' -> append(DIGITS[c-'a'])
            in '0'..'9' -> append(c)
        }
    }
    fun glob(raw: String): String? {
        val digits=raw.replace("'","")
        if(digits.isEmpty() || digits.any { it !in '2'..'9' })return null
        return digits.map { "[${letters[it-'2']}]" }.joinToString("")
    }
    fun matches(pinyin: String, raw: String, nine: Boolean): Boolean =
        (if(nine)encode(pinyin) else pinyin)==raw.replace("'","")
    fun matchesPrefix(pinyin: String,raw: String,nine: Boolean): Boolean =
        raw.replace("'","").isNotEmpty() && (if(nine)encode(pinyin) else pinyin).startsWith(raw.replace("'",""))
}
