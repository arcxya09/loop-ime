package app.loop.ime

import android.icu.text.BreakIterator
import java.util.Locale

/** Local, lossless segmentation. Repeated fragments keep distinct positions. */
object ClipboardParts {
    const val LIMIT=1000
    data class Parts(val values: List<String>,val truncated: Boolean)
    fun split(text: String,characters: Boolean=false): Parts {
        val iterator=if(characters)BreakIterator.getCharacterInstance(Locale.CHINA) else BreakIterator.getWordInstance(Locale.CHINA)
        iterator.setText(text)
        val values=mutableListOf<String>();var start=iterator.first();var end=iterator.next()
        while(end!=BreakIterator.DONE && values.size<LIMIT) {
            values+=text.substring(start,end);start=end;end=iterator.next()
        }
        return Parts(values,end!=BreakIterator.DONE)
    }
    fun label(text: String)=when(text) { " "->"空格";"\n"->"换行";"\r\n"->"换行";"\t"->"制表";else->text }
}
