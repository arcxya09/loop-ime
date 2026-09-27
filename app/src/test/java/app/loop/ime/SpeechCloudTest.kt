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
            f.controller.start(cloudWords=listOf("过期热词"))
            idleUntil { uploaded!=null }
            assertTrue(uploaded!!.isEmpty())
        } finally { f.close() }
    }
    private class LocalContext: ContextWrapper(RuntimeEnvironment.getApplication()) {
        var binds=0
        var permissionChecks=0
        override fun checkSelfPermission(permission: String): Int { permissionChecks++;return PackageManager.PERMISSION_GRANTED }
        override fun bindService(intent: Intent,conn: ServiceConnection,flags: Int): Boolean { binds++;error("Cloud-only speech must not bind a local service") }
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
            cloudProfileProvider={ CloudAsrProfile("test-cloud-key") }
            cloudFactory={ _,_,listener -> FakeCloud(listener).also { clouds+=it } }
            online={ this@Fixture.online }
        }
        init { source.install();Prefs(c).set("speech_cloud",true);Prefs(c).set("private",false) }
        fun close() { controller.destroy();Prefs(c).set("speech_cloud",false);ShadowAudioRecord.clearSource() }
    }
    @Test fun onlineCloudStreamsAndFinishesWithoutLocalService() {
        val f=Fixture()
        try {
            f.controller.start(emptyList());idleUntil { f.clouds.size==1 };val cloud=f.clouds.single();cloud.listener.ready()
            f.source.frames.add(ShortArray(1280) { 21 });idleUntil { cloud.audio.size==1 }
            f.controller.finish();idleUntil { cloud.finishes==1 };cloud.listener.final("云端可用",80);cloud.listener.done()
            assertEquals(0,f.c.binds);assertTrue(f.events.none { it.first==SpeechWire.ERROR })
        } finally { f.close() }
    }
    @Test fun disconnectKeepsConfirmedTextAndStopsMicrophone() {
        val f=Fixture()
        try {
            f.controller.start(emptyList());idleUntil { f.clouds.size==1 };val cloud=f.clouds.single();cloud.listener.ready()
            f.source.frames.add(ShortArray(1280) { 21 });idleUntil { cloud.audio.size==1 };cloud.listener.final("已确认",80)
            cloud.listener.failed(AsrFailure(AsrFailureKind.NETWORK,"网络断开"));cloud.listener.ready()
            assertEquals(listOf("已确认"),f.events.filter { it.first==SpeechWire.FINAL }.map { it.second })
            assertEquals(SpeechWire.ERROR,f.events.last().first);assertEquals(0,f.c.binds)
            assertTrue(f.source.recorders.all { it.recordingState==AudioRecord.RECORDSTATE_STOPPED });assertEquals(1,f.source.opened)
        } finally { f.close() }
    }
    @Test fun keyErrorIsVisibleAndStopsRecording() {
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
    @Test fun largeColdStartPrefixWaitsForSocketCapacityThenDrainsWithoutDropping() {
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
    @Test fun offlineDisabledAndPrivateSessionsNeverReadCredentialsOrOpenTheMic() {
        val f=Fixture()
        try {
            f.controller.cloudProfileProvider={ error("Blocked speech must not read a key") }
            f.online=false;f.controller.start();assertTrue(f.events.last().second.contains("网络"))
            f.online=true;Prefs(f.c).set("speech_cloud",false);f.controller.start();assertTrue(f.events.last().second.contains("启用"))
            Prefs(f.c).set("speech_cloud",true);f.controller.start(allowCloud=false)
            Prefs(f.c).set("private",true);f.controller.start()
            assertEquals(4,f.events.count { it.first==SpeechWire.ERROR });assertEquals(0,f.source.opened);assertTrue(f.clouds.isEmpty());assertEquals(0,f.c.binds)
        } finally { Prefs(f.c).set("private",false);f.close() }
    }
    @Test fun permissionRevokedDuringCredentialReadPreventsOpeningCloudConnection() {
        val f=Fixture();val reading=CountDownLatch(1);val release=CountDownLatch(1)
        f.controller.cloudProfileProvider={ reading.countDown();release.await(3,TimeUnit.SECONDS);CloudAsrProfile("test-key") }
        try {
            f.controller.start();assertTrue(reading.await(2,TimeUnit.SECONDS));Prefs(f.c).set("private",true);release.countDown()
            idleUntil { f.events.any { it.first==SpeechWire.ERROR } }
            assertTrue(f.clouds.isEmpty());assertTrue(f.source.recorders.all { it.recordingState==AudioRecord.RECORDSTATE_STOPPED })
        } finally { release.countDown();Prefs(f.c).set("private",false);f.close() }
    }
    @Test fun networkWatchdogStopsRecordingAndIgnoresLateResultsWithoutFallback() {
        val f=Fixture()
        try {
            f.controller.start();idleUntil { f.clouds.size==1 };val cloud=f.clouds.single();cloud.listener.ready()
            f.source.frames.add(ShortArray(1280) { 100 });idleUntil { cloud.audio.size==1 };cloud.listener.final("已确认",80)
            f.online=false;shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1001));cloud.listener.final("过时内容",80)
            assertTrue(cloud.cancelled);assertEquals(0,f.c.binds);assertEquals(listOf("已确认"),f.events.filter { it.first==SpeechWire.FINAL }.map { it.second })
            assertTrue(f.events.any { it.first==SpeechWire.ERROR });assertTrue(f.source.recorders.all { it.recordingState==AudioRecord.RECORDSTATE_STOPPED })
        } finally { f.close() }
    }
    @Test fun invalidCloudTimestampFailsAndClearsQueuedAudio() {
        val f=Fixture()
        try {
            f.controller.start();idleUntil { f.clouds.size==1 };val cloud=f.clouds.single();cloud.listener.ready()
            f.source.frames.add(ShortArray(1280) { 100 });idleUntil { cloud.audio.size==1 };cloud.listener.final("不可提交",100000)
            assertTrue(cloud.cancelled);assertTrue(f.events.none { it.first==SpeechWire.FINAL });assertEquals(SpeechWire.ERROR,f.events.last().first)
        } finally { f.close() }
    }

}
