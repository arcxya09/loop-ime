package app.loop.ime

import android.app.Application
import android.content.*
import android.text.Editable
import android.text.Selection
import android.text.SpannableStringBuilder
import android.view.View
import android.view.inputmethod.BaseInputConnection
import net.zetetic.database.sqlcipher.SQLiteDatabaseConfiguration
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[37],application=Application::class)
class StateRegressionTest {
    @Test fun ownedUndoRestoresInsertDeleteAndRefusesAnExternalEdit() {
        val text=SpannableStringBuilder().apply { Selection.setSelection(this,0) }
        val ic=object: BaseInputConnection(View(RuntimeEnvironment.getApplication()),true) { override fun getEditable(): Editable=text }
        val editor=SafeEditor { ic };editor.start(0)
        assertTrue(editor.commit("你好"));assertTrue(editor.canUndo);assertTrue(editor.undoLast());assertEquals("",text.toString())
        editor.commit("😀");editor.delete();assertEquals("",text.toString());assertTrue(editor.undoLast());assertEquals("😀",text.toString())
        editor.commit("AB");Selection.setSelection(text,0);editor.selection(0,0,-1,-1)
        assertFalse(editor.canUndo);assertFalse(editor.undoLast());assertEquals("😀AB",text.toString())
    }
    @Test fun perAppPrivacyPersistsAndDoesNotMatchPackagePrefixes() {
        val context=RuntimeEnvironment.getApplication();val prefs=Prefs(context)
        prefs.setLocalApp("app.private",true)
        assertTrue(Prefs(context).localApp("app.private"));assertFalse(prefs.localApp("app.private.other"))
        prefs.setLocalApp("app.private",false);assertFalse(prefs.localApp("app.private"))
    }
    @Test fun movingCaretFinishesHostCompositionWithoutMovingSelectionBack() {
        for(owner in listOf("voice","rime")) {
            val text=SpannableStringBuilder("AB").apply { Selection.setSelection(this,2) }
            val ic=object: BaseInputConnection(View(RuntimeEnvironment.getApplication()),true) { override fun getEditable(): Editable=text }
            val editor=SafeEditor { ic };editor.start(2);editor.setComposition("ni",owner)
            assertFalse(editor.selection(4,4,2,4))
            Selection.setSelection(text,0);assertTrue(editor.selection(0,0,2,4))
            assertEquals(-1,BaseInputConnection.getComposingSpanStart(text))
            assertTrue(editor.commit("X"));assertEquals("XABni",text.toString());assertEquals(TextEdit(0,0,"X"),editor.lastEdit)
        }
    }
    @Test fun enterLabelAndActionRespectNoEnterActionIncludingCustomActions() {
        val actions=listOf(android.view.inputmethod.EditorInfo.IME_ACTION_SEND,android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH,android.view.inputmethod.EditorInfo.IME_ACTION_GO,android.view.inputmethod.EditorInfo.IME_ACTION_NEXT,android.view.inputmethod.EditorInfo.IME_ACTION_PREVIOUS,android.view.inputmethod.EditorInfo.IME_ACTION_DONE)
        for(action in actions) {
            val info=android.view.inputmethod.EditorInfo().apply { imeOptions=action }
            assertEquals(action,EnterKey.forEditor(info).action)
            info.imeOptions=action or android.view.inputmethod.EditorInfo.IME_FLAG_NO_ENTER_ACTION
            assertEquals(EnterKey("换行",null),EnterKey.forEditor(info))
        }
        val custom=android.view.inputmethod.EditorInfo().apply { actionLabel="提交";actionId=42 }
        assertEquals(EnterKey("提交",42),EnterKey.forEditor(custom))
        custom.imeOptions=android.view.inputmethod.EditorInfo.IME_FLAG_NO_ENTER_ACTION
        assertNull(EnterKey.forEditor(custom).action)
    }
    @Test fun nativeExceptionsAndInvalidResultsStillFinishTheirKeyCallbacks() {
        var index=0
        val engine=RimeEngine(RuntimeEnvironment.getApplication()) { _,_->
            when(index++) { 0 -> error("native bridge failure");1 -> "invalid JSON".toByteArray();else -> "{\"commit\":\"恢复\"}".toByteArray() }
        }
        ReflectionHelpers.setField(engine,"ready",true)
        val results=mutableListOf<RimeState>()
        repeat(3) { engine.event(0,callback={ results+=it }) }
        val deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(3)
        while(results.size<3 && System.nanoTime()<deadline) { org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();Thread.yield() }
        assertEquals(3,results.size);assertNotNull(results[0].error);assertNotNull(results[1].error)
        assertEquals("恢复",results[2].commit)
    }
    @Test fun retainedKeySurvivesCallerWipeAndIsErasedAfterDatabaseOwnerCloses() {
        val original=ByteArray(32) { (it+1).toByte() };val caller=original.clone();val owner=DatabaseKey(caller)
        val pool=SQLiteDatabaseConfiguration(SQLiteDatabaseConfiguration("/tmp/fixed.db",0,owner.bytes,null))
        caller.fill(0);assertArrayEquals(original,pool.password);owner.close();assertArrayEquals(ByteArray(32),pool.password)
    }
    @Test fun acknowledgedCaretPositionsCannotMaskANewUserMove() {
        val c=RuntimeEnvironment.getApplication();val text=SpannableStringBuilder().apply { Selection.setSelection(this,0) }
        val connection=object: BaseInputConnection(View(c),true) { override fun getEditable(): Editable=text }
        val editor=SafeEditor { connection };editor.start(0)
        editor.commit("你好");assertFalse(editor.selection(2,2,-1,-1));editor.commit("世界");assertFalse(editor.selection(4,4,-1,-1))
        Selection.setSelection(text,2);assertTrue(editor.selection(2,2,-1,-1))
    }
    @Test fun successfulSelectedDeletionReportsFullRangeAndRejectedCommitReportsNoEdit() {
        val c=RuntimeEnvironment.getApplication();val text=SpannableStringBuilder("你好世界").apply { Selection.setSelection(this,0,4) }
        var reject=false
        val connection=object: BaseInputConnection(View(c),true) {
            override fun getEditable(): Editable=text
            override fun commitText(value: CharSequence?,newCursorPosition: Int): Boolean=if(reject)false else super.commitText(value,newCursorPosition)
        }
        val editor=SafeEditor { connection };editor.start(0)
        assertTrue(editor.delete());assertEquals(TextEdit(0,4,""),editor.lastEdit);assertEquals("",text.toString())
        reject=true;assertFalse(editor.commit("丢失的字"));assertNull(editor.lastEdit)
        assertFalse(editor.sealVoice("保留前段","后段"));assertEquals("",text.toString());assertEquals("",editor.owner)
    }
    @Test fun compositionReplacingSelectionRecordsTheOriginalRangeAndCancelRestoresSelectionText() {
        val c=RuntimeEnvironment.getApplication();val text=SpannableStringBuilder("旧文字").apply { Selection.setSelection(this,0,3) }
        val ic=object: BaseInputConnection(View(c),true) { override fun getEditable(): Editable=text }
        val e=SafeEditor { ic };e.start(0);assertTrue(e.setComposition("暂存","voice"));e.cancelComposition();assertEquals("旧文字",text.toString())
        Selection.setSelection(text,0,3);e.selection(0,3,-1,-1);assertTrue(e.setComposition("新字","voice"));assertTrue(e.sealVoice("新字",""))
        assertEquals(TextEdit(0,3,"新字"),e.lastEdit);assertEquals("新字",text.toString())
    }
    @Test fun permanentOrNullBindingAllowsTheNextSessionToReconnect() {
        var binds=0;var unbinds=0
        val c=object: ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun bindService(i: Intent,connection: ServiceConnection,flags: Int): Boolean { binds++;return true }
            override fun unbindService(connection: ServiceConnection) { unbinds++ }
        }
        val speech=SpeechController(c) { _,_-> };speech.localModelProvider={ "/test-model" }
        fun start()=ReflectionHelpers.callInstanceMethod<Unit>(speech,"startLocal",ClassParameter.from(String::class.java,"测试"))
        try {
            val connection=ReflectionHelpers.getField<ServiceConnection>(speech,"connection")
            start();connection.onBindingDied(ComponentName(c,AsrService::class.java));start()
            assertEquals(2,binds);assertEquals(1,unbinds)
            connection.onNullBinding(ComponentName(c,AsrService::class.java));start();assertEquals(3,binds)
        } finally { speech.destroy() }
    }
}
