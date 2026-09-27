package app.loop.ime

/** Predictions are insertions after the current context, never another copy of that context. */
object PredictionText {
    fun continuations(context: String,values: List<String>): List<String> {
        val typed=context.trimEnd()
        if(typed.isBlank())return emptyList()
        return values.mapNotNull { raw ->
            if(!TextRules.validPrediction(raw))return@mapNotNull null
            var value=raw.trim()
            // Some providers return the complete sentence despite the continuation-only prompt.
            val overlap=(minOf(typed.length,value.length) downTo 2).firstOrNull { n ->
                typed.endsWith(value.take(n),ignoreCase=true)
            } ?: 0
            if(overlap>0)value=value.drop(overlap) else value=insertion(context,value)
            if(context.last().isWhitespace())value=value.trimStart()
            if(value.isBlank() || typed.contains(value.trim(),ignoreCase=true) || value.none { it.isLetterOrDigit() })null else value
        }.distinctBy { it.lowercase() }.take(3)
    }
    fun localPrefixes(context: String): List<String> {
        if(context.isBlank() || context.last().isWhitespace() || !context.last().isLetterOrDigit())return emptyList()
        val tail=context.takeLastWhile { it.isLetterOrDigit() }.takeLast(16)
        return (tail.length downTo 2).map { tail.takeLast(it) }
    }
    fun localSuffix(context: String,term: String): String? = localPrefixes(context)
        .firstOrNull { term.startsWith(it,ignoreCase=true) && term.length>it.length }
        ?.let { continuations(context,listOf(term)).firstOrNull() }
    fun insertion(context: String,suffix: String): String =
        if(context.lastOrNull()?.let { it in 'a'..'z' || it in 'A'..'Z' }==true &&
            suffix.firstOrNull()?.let { it in 'a'..'z' || it in 'A'..'Z' }==true)" $suffix" else suffix
}
