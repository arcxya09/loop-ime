package app.loop.ime

import android.media.AudioRecord
import org.robolectric.shadows.ShadowAudioRecord
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Controlled microphone with blocking reads and Android-like stop unblocking. Never synthesizes silence. */
internal class SpeechAudioSource {
    val frames=LinkedBlockingQueue<ShortArray>()
    val recorders=ConcurrentHashMap.newKeySet<AudioRecord>()
    val delivered=AtomicInteger()
    val opened get()=recorders.size
    fun install() {
        ShadowAudioRecord.setSourceProvider { ar ->
            recorders.add(ar)
            object: ShadowAudioRecord.AudioRecordSource {
                override fun readInShortArray(buffer: ShortArray,offset: Int,size: Int,isBlocking: Boolean): Int {
                    while(ar.recordingState==AudioRecord.RECORDSTATE_RECORDING) {
                        val frame=frames.poll(20,TimeUnit.MILLISECONDS) ?: continue
                        require(frame.size<=size);frame.copyInto(buffer,offset);delivered.incrementAndGet();return frame.size
                    }
                    return 0
                }
            }
        }
    }
}
