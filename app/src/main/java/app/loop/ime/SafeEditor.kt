package app.loop.ime

import android.os.SystemClock
import android.view.inputmethod.InputConnection
import java.util.ArrayDeque

/** This main-thread object is the sole writer. Async consumers never receive InputConnection. */
class SafeEditor(private val connection: ()->InputConnection?) {
    var generation=0L;private set
    var revision=0L;private set
    var owner="";private set
    var composition="";private set
    var cursor=-1;private set
    var lastEdit: TextEdit?=null;private set
    private var selectionEnd=-1
    private var composeStart=-1
    private var composeEnd=-1
    private var replacedSelection=""
    private data class OwnedUndo(val inserted: String,val replaced: String,val revision: Long,val generation: Long)
    private var ownedUndo: OwnedUndo?=null
    val canUndo get()=ownedUndo?.let { u -> u.revision==revision && u.generation==generation && owner.isEmpty() && connection()?.let { range(it)==(cursor to cursor) }==true }==true
    fun undoLast(): Boolean { val u=ownedUndo ?: return false;ownedUndo=null;return patch(u.inserted,u.replaced,u.revision,u.generation) }
    private val expected=ArrayDeque<Pair<Int,Long>>()
    fun start(initial: Int) { generation++;revision++;cursor=initial;selectionEnd=initial;owner="";composition="";composeStart=-1;composeEnd=-1;replacedSelection="";expected.clear();lastEdit=null;ownedUndo=null }
    private fun expect(p: Int) { cursor=p;selectionEnd=p;if(p>=0)expected.add(p to SystemClock.uptimeMillis());while(expected.size>64)expected.removeFirst() }
    private fun range(ic: InputConnection): Pair<Int,Int> {
        val s=ic.getSurroundingText(0,0,0)
        return if(s!=null && s.offset>=0)(s.offset+minOf(s.selectionStart,s.selectionEnd)) to (s.offset+maxOf(s.selectionStart,s.selectionEnd))
        else cursor to maxOf(cursor,selectionEnd)
    }
    fun setComposition(text: String, who: String): Boolean {
        if(owner.isNotEmpty() && owner!=who)return false
        val ic=connection() ?: return false
        val bounds=if(owner.isEmpty())range(ic) else composeStart to composeEnd
        val replacement=if(owner.isEmpty())ic.getSelectedText(0)?.toString().orEmpty() else replacedSelection
        val ok=ic.setComposingText(text,1)
        if(ok) { composeStart=bounds.first;composeEnd=bounds.second;replacedSelection=replacement;owner=who;composition=text;expect(if(composeStart<0)-1 else composeStart+text.length) }
        return ok
    }
    fun commit(text: String): Boolean {
        lastEdit=null
        val ic=connection() ?: return false
        val bounds=if(owner.isNotEmpty())composeStart to composeEnd else range(ic)
        val start=bounds.first
        val replaced=if(owner.isNotEmpty())replacedSelection else ic.getSelectedText(0)?.toString().orEmpty()
        val ok=ic.commitText(text,1)
        if(ok) { lastEdit=TextEdit(start,bounds.second,text);revision++;owner="";composition="";composeStart=-1;composeEnd=-1;replacedSelection="";expect(if(start<0)-1 else start+text.length) }
        if(ok)ownedUndo=if(start>=0 && text.length<=4096 && replaced.length<=4096)OwnedUndo(text,replaced,revision,generation) else null
        return ok
    }
    fun sealVoice(head: String, tail: String): Boolean {
        val ic=connection() ?: return false
        if(owner.isNotEmpty() && owner!="voice")return false
        ic.beginBatchEdit()
        try { val ok=commit(head);if(ok && tail.isNotEmpty())setComposition(tail,"voice");return ok } finally { ic.endBatchEdit() }
    }
    fun finishComposition() {
        connection()?.finishComposingText();owner="";composition="";composeStart=-1;composeEnd=-1;replacedSelection="";revision++
    }
    fun cancelComposition() {
        if(owner.isNotEmpty()) { val start=composeStart;connection()?.setComposingText(replacedSelection,1);connection()?.finishComposingText();expect(if(start<0)-1 else start+replacedSelection.length) }
        owner="";composition="";composeStart=-1;composeEnd=-1;replacedSelection="";revision++
    }
    fun delete(): Boolean {
        lastEdit=null
        val ic=connection() ?: return false
        // Code-point deletion preserves surrogate pairs (emoji), Android handles a selected range with commitText.
        val selected=ic.getSelectedText(0)
        val bounds=range(ic)
        val before=if(selected.isNullOrEmpty())ic.getTextBeforeCursor(2,0)?.toString().orEmpty() else ""
        val count=if(before.isEmpty())0 else Character.charCount(before.codePointBefore(before.length))
        val ok=if(!selected.isNullOrEmpty())ic.commitText("",1) else ic.deleteSurroundingTextInCodePoints(1,0)
        if(ok) {
            val start=if(bounds.first<0)-1 else if(selected.isNullOrEmpty())maxOf(0,bounds.first-count) else bounds.first
            lastEdit=if(start>=0)TextEdit(start,bounds.second,"") else null
            revision++;expected.clear();expect(start)
            val removed=selected?.toString()?.takeIf { it.isNotEmpty() } ?: before.takeLast(count)
            ownedUndo=if(start>=0 && removed.isNotEmpty() && removed.length<=4096)OwnedUndo("",removed,revision,generation) else null
        }
        return ok
    }
    fun deleteToStart(): Boolean {
        lastEdit=null
        if(owner.isNotEmpty())return false
        val ic=connection() ?: return false
        // Require an actual absolute, collapsed selection; never guess from stale callbacks.
        val s=ic.getSurroundingText(0,0,0) ?: return false
        if(s.offset<0 || s.selectionStart!=s.selectionEnd)return false
        val end=s.offset+s.selectionStart
        if(end<=0)return false
        val removed=if(end<=4096)ic.getTextBeforeCursor(end,0)?.toString()?.takeIf { it.length==end } else null
        if(!ic.deleteSurroundingText(end,0))return false
        revision++;expected.clear();expect(0);lastEdit=TextEdit(0,end,"")
        ownedUndo=removed?.let { OwnedUndo("",it,revision,generation) }
        return true
    }
    fun patch(original: String, replacement: String, expectedRevision: Long, expectedGeneration: Long): Boolean {
        lastEdit=null
        if(owner.isNotEmpty() || revision!=expectedRevision || generation!=expectedGeneration)return false
        val ic=connection() ?: return false
        // Final anchor read is mandatory. No patch when a host refuses surrounding text.
        val surrounding=ic.getSurroundingText(original.length,0,0) ?: return false
        if(surrounding.selectionStart!=surrounding.selectionEnd || surrounding.offset<0 || cursor<0)return false
        if(surrounding.offset+surrounding.selectionEnd!=cursor)return false
        if(surrounding.text.subSequence(0,surrounding.selectionEnd).toString().takeLast(original.length)!=original)return false
        val p=cursor
        ic.beginBatchEdit()
        try {
            if(!ic.deleteSurroundingText(original.length,0))return false
            if(!ic.commitText(replacement,1)) { ic.commitText(original,1);revision++;return false }
            lastEdit=TextEdit(p-original.length,p,replacement);revision++;expect(if(p<0)-1 else p-original.length+replacement.length);return true
        } finally { ic.endBatchEdit() }
    }
    fun selection(newStart: Int, newEnd: Int, candidatesStart: Int, candidatesEnd: Int): Boolean {
        val now=SystemClock.uptimeMillis()
        while(expected.isNotEmpty() && now-expected.first.second>1500)expected.removeFirst()
        if(cursor<0 && owner.isNotEmpty() && candidatesStart>=0 && candidatesEnd-candidatesStart==composition.length && newStart==newEnd && newEnd==candidatesEnd) {
            composeStart=candidatesStart;cursor=newEnd;return false
        }
        if(newStart==newEnd) {
            val index=expected.indexOfLast { it.first==newStart }
            if(index>=0) { repeat(index+1) { expected.removeFirst() };return false }
            if(newStart==cursor && newEnd==selectionEnd)return false
        }
        // This callback still belongs to this connection. Remove the host's composing span before
        // dropping our ownership, otherwise the next commit replaces text at the old caret.
        if(owner.isNotEmpty())connection()?.finishComposingText()
        // Keep the user's new selection; cancelling composition would move it back.
        generation++;revision++;cursor=minOf(newStart,newEnd);selectionEnd=maxOf(newStart,newEnd);owner="";composition="";composeStart=-1;composeEnd=-1;replacedSelection="";expected.clear();lastEdit=null;return true
    }
    fun navigate(left: Boolean) {
        finishComposition();val ic=connection() ?: return
        val event=if(left)android.view.KeyEvent.KEYCODE_DPAD_LEFT else android.view.KeyEvent.KEYCODE_DPAD_RIGHT
        ic.sendKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN,event));ic.sendKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP,event));generation++;revision++;cursor=-1
    }
    fun action(action: Int): Boolean = connection()?.performEditorAction(action) ?: false
}
