package app.loop.ime

import android.Manifest
import android.app.Application
import android.app.AppOpsManager
import android.content.Context
import android.media.AudioRecord
import android.os.Looper
import android.os.Process
import android.text.Editable
import android.text.Selection
import android.text.SpannableStringBuilder
import android.view.View
import android.view.inputmethod.BaseInputConnection
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import org.robolectric.shadows.ShadowAudioRecord
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[37],application=Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class SpeechLifecycleTest {
    private fun idleUntil(condition: ()->Boolean) {
        val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5)
        while(!condition() && System.nanoTime()<deadline) { shadowOf(Looper.getMainLooper()).idle();Thread.yield() }
        assertTrue("Speech stage timed out",condition())
    }
    private class Stream(val listener: CloudAsrListener): CloudAsrStream {
        val audio=mutableListOf<ShortArray>();var ends=0;var cancelled=false
        override val queuedBytes=0L
        override fun start() { listener.ready() }
        override fun audio(pcm: ShortArray): Boolean { audio+=pcm.copyOf();listener.partial("你");return true }
        override fun finish() { ends++;listener.final("你好",audio.sumOf { it.size }.toLong()/16);listener.done() }
        override fun cancel() { cancelled=true }
    }
    private class Fixture: AutoCloseable {
        val life=Robolectric.buildService(LoopImeService::class.java).create();val service=life.get()
        val source=SpeechAudioSource();val streams=mutableListOf<Stream>()
        val text=SpannableStringBuilder().apply { Selection.setSelection(this,0) }
        val ic=object: BaseInputConnection(View(service),true) { override fun getEditable(): Editable=text }
        val editor=SafeEditor { ic }.apply { start(0) }
        val keyboard=KeyboardView(service) { press(it) }
        val speech=SpeechController(service) { kind,text -> ReflectionHelpers.callInstanceMethod<Unit>(service,"onSpeech",ClassParameter.from(Int::class.javaPrimitiveType!!,kind),ClassParameter.from(String::class.java,text)) }.apply {
            online={ true };cloudProfileProvider={ CloudAsrProfile("synthetic-key") }
            cloudFactory={ _,_,listener -> Stream(listener).also { streams+=it } }
        }
        init {
            source.install();shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.RECORD_AUDIO)
            Prefs(service).set("speech_cloud",true);Prefs(service).set("private",false);Prefs(service).set("learning",false);Prefs(service).set("memory",false)
            ReflectionHelpers.setField(service,"visible",true);ReflectionHelpers.setField(service,"keyboard",keyboard)
            ReflectionHelpers.setField(service,"speech",speech);ReflectionHelpers.setField(service,"editor",editor)
        }
        fun press(key: String)=ReflectionHelpers.callInstanceMethod<Unit>(service,"enqueue",ClassParameter.from(String::class.java,key))
        override fun close() { life.destroy();ShadowAudioRecord.clearSource() }
    }
    @Test fun toolbarStreamsPartialAndCommitsFinalTextOnStop()=Fixture().use { f ->
        f.source.frames.add(ShortArray(1280) { 16384 });f.keyboard.findViewWithTag<View>("mic").performClick()
        idleUntil { f.text.toString()=="你" };assertEquals(AudioRecord.RECORDSTATE_RECORDING,f.source.recorders.single().recordingState)
        f.keyboard.findViewWithTag<View>("mic").performClick();idleUntil { !ReflectionHelpers.getField<Boolean>(f.service,"voice") }
        assertEquals(1,f.streams.single().ends);assertEquals("你好。",f.text.toString());assertEquals("",f.editor.owner)
        assertEquals(AudioRecord.RECORDSTATE_STOPPED,f.source.recorders.single().recordingState)
    }
    @Test fun blockedVocabularyKeepsOpeningAudioAndReleaseNeverReopensTheMicrophone()=Fixture().use { f ->
        val reading=CountDownLatch(1);val release=CountDownLatch(1);var hints=emptyList<String>()
        f.speech.cloudFactory={ _,words,listener -> hints=words;Stream(listener).also { f.streams+=it } }
        try {
            f.source.frames.add(ShortArray(1280) { 4321 })
            f.speech.start(vocabulary={ reading.countDown();check(release.await(4,TimeUnit.SECONDS));listOf("许可热词") })
            assertTrue(reading.await(2,TimeUnit.SECONDS));idleUntil { f.source.delivered.get()==1 }
            f.speech.finish();release.countDown();idleUntil { f.streams.singleOrNull()?.ends==1 }
            assertEquals(listOf("许可热词"),hints);assertArrayEquals(ShortArray(1280) { 4321 },f.streams.single().audio.single())
            assertEquals(1,f.source.opened);assertTrue(f.source.recorders.all { it.recordingState==AudioRecord.RECORDSTATE_STOPPED })
        } finally { release.countDown() }
    }
    @Test fun privateAndRestrictedFieldsDoNotStartVoiceOrConsumeComposition()=Fixture().use { f ->
        for(flag in listOf("passwordField","restricted")) {
            ReflectionHelpers.setField(f.service,flag,true);f.press("mic");assertFalse(ReflectionHelpers.getField(f.service,"voice"));ReflectionHelpers.setField(f.service,flag,false)
        }
        Prefs(f.service).set("private",true);f.press("mic")
        assertFalse(ReflectionHelpers.getField(f.service,"voice"));assertEquals(0,f.source.opened);assertTrue(f.streams.isEmpty());assertEquals("",f.text.toString())
    }
    @Test fun unavailableRotationPermissionStaysAStatusAndDoesNotBecomeText()=Fixture().use { f ->
        Prefs(f.service).set("rotation",true)
        shadowOf(f.service.getSystemService(AppOpsManager::class.java)).setMode(AppOpsManager.OPSTR_WRITE_SETTINGS,Process.myUid(),f.service.packageName,AppOpsManager.MODE_ERRORED)
        val events=mutableListOf<Pair<Int,String>>()
        val speech=SpeechController(f.service) { kind,text -> events+=kind to text }.apply { online={ true };cloudProfileProvider={ CloudAsrProfile("synthetic-key") };cloudFactory={ _,_,listener -> Stream(listener) } }
        try { speech.start();assertTrue(events.any { it.second.contains("旋转锁未获授权") });assertTrue(events.all { it.first==SpeechWire.READY }) }
        finally { speech.destroy() }
    }
}
