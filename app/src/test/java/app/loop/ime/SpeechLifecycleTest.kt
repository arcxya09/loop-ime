package app.loop.ime

import android.Manifest
import android.app.Application
import android.app.AppOpsManager
import android.content.*
import android.content.pm.PackageManager
import android.os.*
import android.media.AudioRecord
import android.text.Editable
import android.text.Selection
import android.text.SpannableStringBuilder
import android.view.View
import android.view.inputmethod.BaseInputConnection
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.After
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
    private fun preparedSpeech(c: Context,event: (Int,String)->Unit)=SpeechController(c,event).apply { localModelProvider={ "/test/downloaded-model" } }
    private lateinit var source: SpeechAudioSource
    @Before fun microphone() { source=SpeechAudioSource();source.install() }
    @After fun cleanup() { ShadowAudioRecord.clearSource() }
    private fun idleUntil(condition: ()->Boolean) {
        val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(4)
        while(!condition() && System.nanoTime()<deadline) { shadowOf(Looper.getMainLooper()).idle();Thread.yield() }
        assertTrue("Speech stage timed out",condition())
    }
    private class DelayedSpeechContext : ContextWrapper(RuntimeEnvironment.getApplication()) {
        lateinit var connection: ServiceConnection
        var bindCount=0
        var permissionChecks=0
        val starts=mutableListOf<Message>()
        val audio=mutableListOf<Message>()
        var ends=0
        private val server=Messenger(object: Handler(Looper.getMainLooper()) {
            override fun handleMessage(msg: Message) {
                when(msg.what) {
                    SpeechWire.START -> starts+=Message.obtain(msg)
                    SpeechWire.AUDIO -> { audio+=Message.obtain(msg);reply(msg,SpeechWire.PARTIAL,"你");reply(msg,SpeechWire.ACK) }
                    SpeechWire.END -> { ends++;reply(msg,SpeechWire.FINAL,"你好");reply(msg,SpeechWire.DONE) }
                }
            }
        })
        private fun reply(request: Message,kind: Int,text: String="") {
            request.replyTo.send(Message.obtain(null,kind).apply { data=Bundle().apply { putLong("epoch",request.data.getLong("epoch"));putString("text",text) } })
        }
        override fun checkSelfPermission(permission: String): Int { permissionChecks++;return PackageManager.PERMISSION_GRANTED }
        override fun bindService(intent: Intent,conn: ServiceConnection,flags: Int): Boolean { bindCount++;connection=conn;return true }
        override fun unbindService(conn: ServiceConnection) {}
        fun connect() { connection.onServiceConnected(ComponentName(this,AsrService::class.java),server.binder);shadowOf(Looper.getMainLooper()).idle() }
        fun ready(request: Message) {
            request.replyTo.send(Message.obtain(null,SpeechWire.READY).apply { data=Bundle().apply { putLong("epoch",request.data.getLong("epoch")) } })
            shadowOf(Looper.getMainLooper()).idle()
        }
    }
    @Test fun cancellationBeforeServiceConnectPreventsAnyStartRequest() {
        val c=DelayedSpeechContext();val events=mutableListOf<Int>();val speech=preparedSpeech(c) { kind,_ -> events+=kind }
        try {
            speech.start(emptyList());idleUntil { source.opened==1 };speech.cancel();c.connect()
            assertTrue(c.starts.isEmpty());assertEquals(2,c.permissionChecks)
            assertTrue(source.recorders.all { it.recordingState==AudioRecord.RECORDSTATE_STOPPED })
        } finally { speech.destroy() }
    }
    @Test fun freshVocabularyAppliesToThisUtteranceWhileItsPrefixIsAlreadyRecorded() {
        val c=DelayedSpeechContext();val speech=preparedSpeech(c) { _,_-> }
        val reading=CountDownLatch(1);val release=CountDownLatch(1)
        try {
            source.frames.add(ShortArray(1280) { 4321 })
            speech.start(listOf("旧姓名"),allowCloud=false,vocabulary={
                reading.countDown();check(release.await(5,TimeUnit.SECONDS));listOf("新姓名") to emptyList()
            })
            assertTrue(reading.await(2,TimeUnit.SECONDS));idleUntil { source.delivered.get()==1 }
            assertEquals(0,c.bindCount)
            release.countDown();idleUntil { c.bindCount==1 };c.connect()
            assertEquals("新姓名",c.starts.single().data.getString("hotwords"))
            c.ready(c.starts.single());idleUntil { c.audio.isNotEmpty() }
            assertEquals(4321/32768f,c.audio.first().data.getFloatArray("pcm")!!.first(),0f)
        } finally { release.countDown();speech.destroy() }
    }
    @Test fun modelReadyAfterCancellationCannotReopenMicrophoneOrAffectANewerSession() {
        val c=DelayedSpeechContext();val events=mutableListOf<Pair<Int,String>>();val speech=preparedSpeech(c) { kind,text -> events+=kind to text }
        try {
            speech.start(emptyList());c.connect();val old=c.starts.single();idleUntil { source.opened==1 }
            speech.cancel();c.ready(old);assertEquals(2,c.permissionChecks)
            speech.start(emptyList());shadowOf(Looper.getMainLooper()).idle();assertEquals(2,c.starts.size)
            idleUntil { source.opened==2 };c.ready(old);assertEquals(4,c.permissionChecks);assertTrue(c.audio.isEmpty())
            speech.cancel();c.ready(c.starts.last());assertEquals(2,source.opened)
            assertTrue(source.recorders.all { it.recordingState==AudioRecord.RECORDSTATE_STOPPED })
        } finally { speech.destroy() }
    }
    @Test fun blockedDictionaryDoesNotDelayCaptureAndReleaseKeepsAudioDuringModelLoad() {
        val controller=Robolectric.buildService(LoopImeService::class.java).create()
        val service=controller.get();val c=DelayedSpeechContext();val speech=preparedSpeech(c) { kind,text ->
            ReflectionHelpers.callInstanceMethod<Unit>(service,"onSpeech",ClassParameter.from(Int::class.javaPrimitiveType!!,kind),ClassParameter.from(String::class.java,text))
        }
        val blocked=CountDownLatch(1);val release=CountDownLatch(1);val drained=CountDownLatch(1)
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.RECORD_AUDIO)
        Prefs(service).set("private",true)
        ReflectionHelpers.setField(service,"visible",true)
        ReflectionHelpers.setField(service,"keyboard",KeyboardView(service){})
        ReflectionHelpers.setField(service,"speech",speech)
        LoopApp.io.execute { blocked.countDown();release.await(10,TimeUnit.SECONDS) }
        try {
            assertTrue(blocked.await(2,TimeUnit.SECONDS))
            source.frames.add(ShortArray(1280) { 1234 })
            ReflectionHelpers.callInstanceMethod<Unit>(service,"enqueue",ClassParameter.from(String::class.java,"voice_hold_start"))
            assertTrue(ReflectionHelpers.getField<Boolean>(service,"voice"))
            assertEquals(1,c.bindCount)
            idleUntil { source.delivered.get()==1 }
            ReflectionHelpers.callInstanceMethod<Unit>(service,"enqueue",ClassParameter.from(String::class.java,"voice_hold_end"))
            assertTrue(ReflectionHelpers.getField<Boolean>(service,"stoppingVoice"))
            assertTrue(source.recorders.all { it.recordingState==AudioRecord.RECORDSTATE_STOPPED })
            release.countDown();LoopApp.io.execute { drained.countDown() };assertTrue(drained.await(3,TimeUnit.SECONDS))
            shadowOf(Looper.getMainLooper()).idle()
            c.connect();c.ready(c.starts.single())
            idleUntil { !ReflectionHelpers.getField<Boolean>(service,"voice") }
            assertEquals(1,c.audio.size);assertEquals(1234/32768f,c.audio.single().data.getFloatArray("pcm")!!.first(),0f)
            assertEquals(1,c.ends);assertEquals(1,source.opened)
        } finally { release.countDown();controller.destroy();Prefs(service).set("private",false) }
    }
    @Test fun toolbarStartsAudioStreamsPartialAndCommitsFinalTextOnStop() {
        val controller=Robolectric.buildService(LoopImeService::class.java).create()
        val service=controller.get();val c=DelayedSpeechContext()
        val releaseRead=CountDownLatch(1);val firstRead=CountDownLatch(1)
        var recorder: AudioRecord?=null
        val content=SpannableStringBuilder();Selection.setSelection(content,0)
        val connection=object: BaseInputConnection(View(service),true) { override fun getEditable(): Editable=content }
        val editor=SafeEditor { connection }.apply { start(0) }
        val speech=preparedSpeech(c) { kind,text ->
            ReflectionHelpers.callInstanceMethod<Unit>(service,"onSpeech",ClassParameter.from(Int::class.javaPrimitiveType!!,kind),ClassParameter.from(String::class.java,text))
        }
        val keyboard=KeyboardView(service) { code -> ReflectionHelpers.callInstanceMethod<Unit>(service,"enqueue",ClassParameter.from(String::class.java,code)) }
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.RECORD_AUDIO)
        Prefs(service).set("private",true)
        ReflectionHelpers.setField(service,"visible",true);ReflectionHelpers.setField(service,"keyboard",keyboard)
        ReflectionHelpers.setField(service,"speech",speech);ReflectionHelpers.setField(service,"editor",editor)
        val source=object: ShadowAudioRecord.AudioRecordSource {
                var reads=0
                override fun readInShortArray(buffer: ShortArray,offset: Int,size: Int,isBlocking: Boolean): Int {
                    if(reads++==0) { buffer.fill(16384,offset,offset+size);firstRead.countDown();return size }
                    releaseRead.await(5,TimeUnit.SECONDS);return 0
                }
        }
        ShadowAudioRecord.setSourceProvider { ar -> recorder=ar;source }
        fun idleUntil(condition: ()->Boolean) {
            val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3)
            while(!condition() && System.nanoTime()<deadline) { shadowOf(Looper.getMainLooper()).idle();Thread.yield() }
            assertTrue("Speech stage did not complete",condition())
        }
        try {
            keyboard.findViewWithTag<View>("mic").performClick();c.connect();c.ready(c.starts.single())
            assertTrue("AudioRecord never opened",firstRead.await(2,TimeUnit.SECONDS))
            idleUntil { content.toString()=="你" }
            assertEquals(AudioRecord.RECORDSTATE_RECORDING,recorder!!.recordingState)
            assertEquals(1,c.audio.single().data.getInt("seq"));assertEquals(0.5f,c.audio.single().data.getFloatArray("pcm")!!.first(),0f)
            keyboard.findViewWithTag<View>("mic").performClick();releaseRead.countDown()
            idleUntil { !ReflectionHelpers.getField<Boolean>(service,"voice") }
            assertEquals(1,c.ends);assertEquals("你好。",content.toString());assertEquals("",editor.owner)
            assertEquals(AudioRecord.RECORDSTATE_STOPPED,recorder!!.recordingState)
        } finally { releaseRead.countDown();controller.destroy();ShadowAudioRecord.clearSource();Prefs(service).set("private",false) }
    }
    @Test fun unavailableRotationPermissionIsAStatusAndNeverRecognitionText() {
        val c=DelayedSpeechContext();Prefs(c).set("rotation",true)
        shadowOf(c.getSystemService(AppOpsManager::class.java)).setMode(AppOpsManager.OPSTR_WRITE_SETTINGS,Process.myUid(),c.packageName,AppOpsManager.MODE_ERRORED)
        val events=mutableListOf<Pair<Int,String>>();val speech=preparedSpeech(c) { kind,text -> events+=kind to text }
        try {
            speech.start(emptyList())
            assertTrue(events.any { it.second.contains("旋转锁未获授权") })
            assertTrue(events.all { it.first==SpeechWire.READY });assertEquals(1,c.bindCount)
        } finally { speech.destroy();Prefs(c).set("rotation",false) }
    }
}
