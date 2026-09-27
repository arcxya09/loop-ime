package app.loop.ime

import android.app.Application
import android.content.*
import android.content.pm.PackageManager
import android.media.AudioRecord
import android.os.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowAudioRecord
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[37],application=Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class SpeechCloudTest {
    @Test fun changedVocabularyPermissionDuringConfigurationDropsCloudHints() {
        val f=Fixture()
        var uploaded: List<String>?=null
        f.controller.cloudFactory={ _,words,listener -> uploaded=words;FakeCloud(listener).also { f.clouds+=it } }
        f.controller.cloudProfileProvider={ StoreEvents.changed();CloudAsrProfile("test-cloud-key") }
        try {
            f.controller.start(emptyList(),cloudWords=listOf("过期热词"))
            idleUntil { uploaded!=null }
            assertTrue(uploaded!!.isEmpty())
        } finally { f.close() }
    }
    private class LocalContext: ContextWrapper(RuntimeEnvironment.getApplication()) {
        lateinit var connection: ServiceConnection
        val starts=mutableListOf<Message>()
        val frames=mutableListOf<FloatArray>()
        val sequences=mutableListOf<Int>()
        var binds=0
        var ends=0
        var permissionChecks=0
        private val server=Messenger(object: Handler(Looper.getMainLooper()) {
            override fun handleMessage(msg: Message) {
                when(msg.what) {
                    SpeechWire.START -> starts+=Message.obtain(msg)
                    SpeechWire.AUDIO -> { frames+=msg.data.getFloatArray("pcm")!!;sequences+=msg.data.getInt("seq");reply(msg,SpeechWire.ACK) }
                    SpeechWire.END -> { ends++;reply(msg,SpeechWire.FINAL,"离线尾句");reply(msg,SpeechWire.DONE) }
                }
            }
        })
        fun reply(msg: Message,kind: Int,text: String="") {
            msg.replyTo.send(Message.obtain(null,kind).apply { data=Bundle().apply { putLong("epoch",msg.data.getLong("epoch"));putString("text",text) } })
        }
        override fun checkSelfPermission(permission: String): Int { permissionChecks++;return PackageManager.PERMISSION_GRANTED }
        override fun bindService(intent: Intent,conn: ServiceConnection,flags: Int): Boolean { binds++;connection=conn;return true }
        override fun unbindService(conn: ServiceConnection) {}
        fun connect() { connection.onServiceConnected(ComponentName(this,AsrService::class.java),server.binder);shadowOf(Looper.getMainLooper()).idle() }
    }
    private class FakeCloud(val listener: CloudAsrListener): CloudAsrStream {
        var cancelled=false
        var finishes=0
        override var queuedBytes=0L
        val audio=mutableListOf<ShortArray>()
        override fun start() {}
        override fun audio(pcm: ShortArray): Boolean { audio+=pcm.copyOf();return !cancelled }
        override fun finish() { finishes++ }
        override fun cancel() { cancelled=true }
    }
    private fun idleUntil(condition: ()->Boolean) {
        val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(4)
        while(!condition() && System.nanoTime()<deadline) { shadowOf(Looper.getMainLooper()).idle();Thread.yield() }
        assertTrue("Speech stage timed out",condition())
    }
    private class Fixture {
        val c=LocalContext()
        val events=mutableListOf<Pair<Int,String>>()
        val clouds=mutableListOf<FakeCloud>()
        val source=SpeechAudioSource()
        var online=true
        val controller=SpeechController(c) { kind,text -> events+=kind to text }.apply {
            localModelProvider={ "/test/downloaded-model" }
            cloudProfileProvider={ CloudAsrProfile("test-cloud-key") }
            cloudFactory={ _,_,listener -> FakeCloud(listener).also { clouds+=it } }
            online={ this@Fixture.online }
        }
        init { source.install();Prefs(c).set("speech_cloud",true);Prefs(c).set("private",false) }
        fun close() { controller.destroy();Prefs(c).set("speech_cloud",false);ShadowAudioRecord.clearSource() }
    }
    @Test fun offlineAtStartUsesLocalWithoutReadingOrSendingAnyCloudKey() {
        val f=Fixture()
        try {
            f.online=false;f.controller.cloudProfileProvider={ error("Credential must not be read offline") }
            f.source.frames.add(shortArrayOf(100,200))
            f.controller.start(emptyList());assertEquals(1,f.c.binds);assertTrue(f.clouds.isEmpty())
            idleUntil { f.source.delivered.get()==1 }
            f.controller.finish();f.c.connect();f.c.reply(f.c.starts.single(),SpeechWire.READY)
            idleUntil { f.events.any { it.first==SpeechWire.DONE } }
            assertEquals(listOf(100/32768f,200/32768f),f.c.frames.flatMap { it.toList() })
            assertTrue(f.source.recorders.all { it.recordingState==AudioRecord.RECORDSTATE_STOPPED })
            assertEquals(SpeechWire.DONE,f.events.last().first)
        } finally { f.close() }
    }
    @Test fun noOfflinePackagePromptsDownloadWithoutOpeningMicrophoneOrService() {
        val f=Fixture()
        try {
            f.online=false;f.controller.localModelProvider={ null }
            f.controller.start(emptyList())
            assertEquals(listOf(SpeechWire.MODEL_REQUIRED),f.events.map { it.first })
            assertEquals(0,f.source.opened);assertEquals(0,f.c.binds);assertTrue(f.clouds.isEmpty())
        } finally { f.close() }
    }
    @Test fun onlineCloudWorksWithoutEverRequiringTheOfflinePackage() {
        val f=Fixture()
        try {
            f.controller.localModelProvider={ error("Cloud must not require offline weights") }
            f.controller.start(emptyList());idleUntil { f.clouds.size==1 };val cloud=f.clouds.single();cloud.listener.ready()
            f.source.frames.add(ShortArray(1280) { 21 });idleUntil { cloud.audio.size==1 }
            f.controller.finish();idleUntil { cloud.finishes==1 };cloud.listener.final("云端可用",80);cloud.listener.done()
            assertEquals(0,f.c.binds);assertTrue(f.events.none { it.first==SpeechWire.MODEL_REQUIRED || it.first==SpeechWire.ERROR })
        } finally { f.close() }
    }
    @Test fun disconnectWithoutOfflinePackageKeepsConfirmedTextAndStopsMicrophone() {
        val f=Fixture()
        try {
            f.controller.localModelProvider={ null }
            f.controller.start(emptyList());idleUntil { f.clouds.size==1 };val cloud=f.clouds.single();cloud.listener.ready()
            f.source.frames.add(ShortArray(1280) { 21 });idleUntil { cloud.audio.size==1 };cloud.listener.final("已确认",80)
            cloud.listener.failed(AsrFailure(AsrFailureKind.NETWORK,"网络断开"));cloud.listener.ready()
            assertEquals(listOf("已确认"),f.events.filter { it.first==SpeechWire.FINAL }.map { it.second })
            assertEquals(SpeechWire.MODEL_REQUIRED,f.events.last().first);assertEquals(0,f.c.binds)
            assertTrue(f.source.recorders.all { it.recordingState==AudioRecord.RECORDSTATE_STOPPED });assertEquals(1,f.source.opened)
        } finally { f.close() }
    }
    @Test fun privacyFieldForcesLocalEvenWhenCloudKeyIsEnabledAndNetworkIsOnline() {
        val f=Fixture()
        try {
            f.controller.cloudProfileProvider={ error("Privacy field read a cloud key") }
            f.controller.start(emptyList(),allowCloud=false)
            assertEquals(1,f.c.binds);assertTrue(f.clouds.isEmpty())
        } finally { f.close() }
    }
    @Test fun keyErrorIsVisibleAndNeverSilentlyFallsBackToOffline() {
        val f=Fixture()
        try {
            f.controller.start(emptyList());idleUntil { f.clouds.isNotEmpty() }
            val cloud=f.clouds.single()
            cloud.listener.failed(BailianProtocol.httpError(401));cloud.listener.ready()
            assertEquals(0,f.c.binds);assertEquals(2,f.c.permissionChecks)
            assertTrue(f.events.any { it.first==SpeechWire.ERROR && it.second.contains("Key") });assertTrue(cloud.cancelled)
        } finally { f.close() }
    }
    @Test fun cancelledCloudSessionIgnoresLateReadyAndCannotAffectANewerSession() {
        val f=Fixture()
        try {
            f.controller.start(emptyList());idleUntil { f.clouds.size==1 && f.source.opened==1 };val old=f.clouds[0]
            f.controller.cancel();old.listener.ready();assertTrue(old.audio.isEmpty())
            assertTrue(f.source.recorders.all { it.recordingState==AudioRecord.RECORDSTATE_STOPPED })
            f.controller.start(emptyList());idleUntil { f.clouds.size==2 && f.source.opened==2 };old.listener.ready()
            assertTrue(f.clouds.all { it.audio.isEmpty() });assertEquals(4,f.c.permissionChecks)
            f.controller.cancel();f.clouds[1].listener.ready();assertEquals(2,f.source.opened)
            assertTrue(f.source.recorders.all { it.recordingState==AudioRecord.RECORDSTATE_STOPPED })
        } finally { f.close() }
    }
    @Test fun releaseDuringCredentialReadPreservesOpeningAudioAndNeverReopensTheMic() {
        val f=Fixture();val reading=CountDownLatch(1);val release=CountDownLatch(1);val drained=CountDownLatch(1)
        f.controller.cloudProfileProvider={ reading.countDown();release.await(3,TimeUnit.SECONDS);CloudAsrProfile("late-key") }
        try {
            f.source.frames.add(ShortArray(1280) { if(it<640)101 else 202 })
            f.controller.start(emptyList());assertTrue(reading.await(2,TimeUnit.SECONDS))
            idleUntil { f.source.delivered.get()==1 }
            assertEquals(1,f.source.opened);assertTrue(f.clouds.isEmpty())
            f.controller.finish()
            assertTrue(f.source.recorders.all { it.recordingState==AudioRecord.RECORDSTATE_STOPPED })
            release.countDown();CloudSpeechSettings.io.execute { drained.countDown() };assertTrue(drained.await(2,TimeUnit.SECONDS))
            idleUntil { f.clouds.size==1 };val cloud=f.clouds.single()
            assertTrue(cloud.audio.isEmpty());assertEquals(0,cloud.finishes)
            cloud.listener.ready();idleUntil { cloud.finishes==1 }
            assertArrayEquals(ShortArray(1280) { if(it<640)101 else 202 },cloud.audio.single())
            assertEquals(1,f.source.opened);assertEquals(0,f.c.binds)
            cloud.listener.final("开头完整",80);cloud.listener.done()
            assertEquals(SpeechWire.DONE,f.events.last().first)
        } finally { release.countDown();f.close() }
    }
    @Test fun disconnectReplaysOnlyUnconfirmedTailKeepsMicrophoneAndFinishesAfterReleaseDuringModelLoad() {
        val f=Fixture();val source=f.source
        try {
            f.controller.start(listOf("本地词"));idleUntil { f.clouds.isNotEmpty() }
            val cloud=f.clouds.single();cloud.listener.ready()
            source.frames.add(ShortArray(1280) { if(it<640)1000 else 2000 })
            idleUntil { cloud.audio.size==1 }
            cloud.listener.final("已确认",40)
            cloud.queuedBytes=64000 // Include unsent backlog after the unconfirmed, already-sent tail.
            source.frames.add(ShortArray(1280) { 3000 });idleUntil { source.delivered.get()==2 }
            cloud.listener.partial("未确认")
            f.online=false;shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1001))
            assertEquals(1,f.c.binds);assertTrue(cloud.cancelled);assertEquals("本地识别",f.controller.recognitionLabel)
            cloud.listener.final("重复的旧结果",80)
            f.controller.finish();source.frames.add(ShortArray(0))
            f.c.connect();assertEquals(1,f.c.starts.size)
            f.c.reply(f.c.starts.single(),SpeechWire.READY)
            idleUntil { f.events.any { it.first==SpeechWire.DONE } }
            val audio=f.c.frames.flatMap { it.toList() }
            assertEquals(1920,audio.size)
            assertTrue(audio.take(640).all { it==2000/32768f });assertTrue(audio.drop(640).all { it==3000/32768f })
            assertEquals(listOf(1,2),f.c.sequences);assertEquals(1,f.c.ends);assertEquals(1,source.opened)
            assertEquals(listOf("已确认","离线尾句"),f.events.filter { it.first==SpeechWire.FINAL }.map { it.second })
            assertTrue(f.events.any { it.first==SpeechWire.PARTIAL && it.second.isEmpty() })
            assertTrue(f.events.none { it.first==SpeechWire.ERROR })
        } finally { source.frames.add(ShortArray(0));f.close() }
    }
    @Test fun cloudStopSendsFinishOnceAfterLastAudioAndReceivesFinalBeforeDone() {
        val f=Fixture();val source=f.source
        try {
            f.controller.start(emptyList());idleUntil { f.clouds.isNotEmpty() }
            val cloud=f.clouds.single();cloud.listener.ready();source.frames.add(ShortArray(1280) { 100 })
            idleUntil { cloud.audio.size==1 }
            f.controller.finish();f.controller.finish();source.frames.add(ShortArray(0));idleUntil { cloud.finishes==1 }
            cloud.listener.final("云端尾句",80);cloud.listener.done()
            assertEquals(listOf(SpeechWire.FINAL,SpeechWire.DONE),f.events.takeLast(2).map { it.first })
            assertEquals(0,f.c.binds);assertTrue(cloud.cancelled)
        } finally { source.frames.add(ShortArray(0));f.close() }
    }
    @Test fun handshakeDelayKeepsOpeningWordsBeforeLiveAudioAndOnlySendsAfterReady() {
        val f=Fixture()
        try {
            f.controller.start(emptyList());idleUntil { f.clouds.size==1 };val cloud=f.clouds.single()
            f.source.frames.add(ShortArray(1280) { 11 });idleUntil { f.source.delivered.get()==1 }
            f.source.frames.add(ShortArray(1280) { 22 });idleUntil { f.source.delivered.get()==2 }
            assertTrue(cloud.audio.isEmpty());assertEquals(0,cloud.finishes)
            cloud.listener.ready();idleUntil { cloud.audio.size==2 }
            f.source.frames.add(ShortArray(1280) { 33 });idleUntil { cloud.audio.size==3 }
            f.controller.finish();idleUntil { cloud.finishes==1 }
            assertEquals(listOf<Short>(11,22,33),cloud.audio.map { it.first() })
            assertTrue(cloud.audio.all { frame -> frame.all { it==frame.first() } })
            assertEquals(1,f.source.opened);assertEquals(0,f.c.binds)
            cloud.listener.final("开头和后续",240);cloud.listener.done()
            assertTrue(f.events.none { it.first==SpeechWire.ERROR })
        } finally { f.close() }
    }
    @Test fun bufferedPrefixSurvivesNetworkFailureBeforeTaskStarted() {
        val f=Fixture()
        try {
            f.controller.start(emptyList());idleUntil { f.clouds.size==1 };val cloud=f.clouds.single()
            f.source.frames.add(ShortArray(1280) { 71 });idleUntil { f.source.delivered.get()==1 }
            cloud.listener.failed(AsrFailure(AsrFailureKind.NETWORK,"连接断开"))
            f.controller.finish();f.c.connect();f.c.reply(f.c.starts.single(),SpeechWire.READY)
            idleUntil { f.events.any { it.first==SpeechWire.DONE } }
            assertTrue(cloud.audio.isEmpty());assertEquals(1,f.source.opened);assertEquals(1280,f.c.frames.sumOf { it.size })
            assertTrue(f.c.frames.flatMap { it.toList() }.all { it==71/32768f })
            assertEquals(1,f.c.ends);assertTrue(f.events.none { it.first==SpeechWire.ERROR })
        } finally { f.close() }
    }
    @Test fun largeColdStartPrefixWaitsForSocketCapacityThenDrainsWithoutDroppingOrFalseFallback() {
        val f=Fixture()
        try {
            f.controller.start(emptyList());idleUntil { f.clouds.size==1 };val cloud=f.clouds.single()
            cloud.queuedBytes=64000
            repeat(20) { index ->
                f.source.frames.add(ShortArray(1280) { (index+1).toShort() })
                // AudioRecord delivery precedes the main-thread callback. Await consumption so
                // a fast test producer cannot accidentally exercise the separate queue-overflow guard.
                idleUntil { org.robolectric.util.ReflectionHelpers.getField<Long>(f.controller,"capturedSamples")==1280L*(index+1) }
            }
            f.controller.finish();cloud.listener.ready();shadowOf(Looper.getMainLooper()).idle()
            assertTrue(cloud.audio.isEmpty());assertEquals(0,cloud.finishes)
            cloud.queuedBytes=0
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))
            idleUntil { cloud.finishes==1 }
            assertEquals((1..20).map { it.toShort() },cloud.audio.map { it.first() })
            assertEquals(25600,cloud.audio.sumOf { it.size });assertEquals(0,f.c.binds)
            cloud.listener.final("完整缓存",1600);cloud.listener.done()
            assertTrue(f.events.none { it.first==SpeechWire.ERROR })
        } finally { f.close() }
    }
}
