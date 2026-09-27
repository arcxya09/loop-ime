package app.loop.ime

/** Segment boundaries exist even when the recognizer strips whitespace/punctuation. */
internal object SpeechText {
    fun append(previous: String, next: String): String {
        if(previous.isEmpty() || next.isEmpty() || previous.last().isWhitespace() || next.first().isWhitespace())return next
        val left=previous.trimEnd('.', '!', '?', ',', ';', ':', '"', '”', ')').lastOrNull()
        val right=next.trimStart('"', '“', '(').firstOrNull()
        fun western(c: Char?)=c!=null && (c in 'a'..'z' || c in 'A'..'Z' || c.isDigit())
        return if(western(left) && western(right))" $next" else next
    }
    fun join(previous: String, segments: List<String>): String {
        val out=StringBuilder()
        var last=previous
        segments.forEach { segment -> val piece=append(last,segment);out.append(piece);if(piece.isNotEmpty())last=piece }
        return out.toString()
    }
}
