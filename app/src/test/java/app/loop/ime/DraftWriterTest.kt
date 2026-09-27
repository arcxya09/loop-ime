package app.loop.ime

import android.app.Application
import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.crypto.spec.SecretKeySpec

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[37],application=Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class DraftWriterTest {
    private fun await(test: ()->Boolean) { val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(!test() && System.nanoTime()<end)Thread.yield();assertTrue(test()) }
    @Test fun saturatedStorageQueueKeepsAnEncryptedJournalAndFreshWriterRecoversIt()=StoreFixture().use { f ->
        val key=SecretKeySpec(ByteArray(32) { 7 },"AES");val vault=Vault(f.context) { _,_->key }
        val raw=f.context.getSharedPreferences("loop-vault",Context.MODE_PRIVATE)
        val release=CountDownLatch(1);val entered=CountDownLatch(1)
        LoopApp.io.execute { entered.countDown();release.await(15,TimeUnit.SECONDS) };assertTrue(entered.await(2,TimeUnit.SECONDS))
        val writer=DraftWriter(f.context,vault) { f.store }
        try {
            repeat(256) { LoopApp.io.execute {} }
            writer.offer(DraftSnapshot("busy","你好世界","manual",false,true,1,100))
            await { raw.contains("pending.memory.busy") }
            val ciphertext=raw.getString("pending.memory.busy","")!!;assertTrue(ciphertext.startsWith("v2:"));assertFalse(ciphertext.contains("你好世界"))
            assertTrue(writer.hasPending("busy"));writer.close()
            LoopApp.io.queue.clear();release.countDown()
            DraftWriter(f.context,vault) { f.store }.use { recovered -> recovered.recover();await { !raw.contains("pending.memory.busy") };assertEquals("你好世界",f.store.memories().single().text) }
        } finally { writer.close();LoopApp.io.queue.clear();release.countDown() }
    }
    @Test fun failedJournalWriteRetainsLatestSnapshotAndExplicitDeletionCancelsReplay()=StoreFixture().use { f ->
        var available=false;val key=SecretKeySpec(ByteArray(32) { 8 },"AES")
        val vault=Vault(f.context) { _,_->check(available) { "synthetic unavailable Keystore" };key }
        DraftWriter(f.context,vault) { f.store }.use { writer ->
            writer.offer(DraftSnapshot("retry","旧内容","manual",false,true,1,100));await { writer.lastError.isNotEmpty() }
            writer.offer(DraftSnapshot("retry","最新内容","manual",false,true,2,100));assertTrue(writer.hasPending("retry"))
            available=true;writer.settle();assertEquals("最新内容",f.store.memories().single().text)
            writer.discard("retry").get(3,TimeUnit.SECONDS);f.store.deleteMemory("retry");writer.recover();writer.settle();assertTrue(f.store.memories().isEmpty())
        }
    }
}
