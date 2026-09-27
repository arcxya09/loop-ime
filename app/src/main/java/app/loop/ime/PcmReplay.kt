package app.loop.ime

import java.util.ArrayDeque

/** Only unconfirmed audio, in RAM. Absolute sample positions match the cloud's millisecond timestamps. */
internal class PcmReplay(private val maxSamples: Int=16000*120) {
    private val chunks=ArrayDeque<ShortArray>()
    private var base=0L
    private var end=0L
    val size get()=(end-base).toInt()
    fun append(pcm: ShortArray) {
        check(size+pcm.size<=maxSamples) { "识别长时间未确认，已停止以避免丢失音频" }
        chunks.add(pcm);end+=pcm.size
    }
    fun confirm(endMs: Long) {
        require(endMs>=0 && endMs<=end/16+1000) { "云端时间戳超出录音范围" }
        val target=(endMs*16).coerceIn(base,end)
        while(base<target && chunks.isNotEmpty()) {
            val first=chunks.removeFirst();val count=minOf(first.size.toLong(),target-base).toInt()
            if(count<first.size)chunks.addFirst(first.copyOfRange(count,first.size))
            first.fill(0);base+=count
        }
    }
    fun takeTail(): List<ShortArray> = chunks.toList().also { chunks.clear();base=end }
    fun clear() { chunks.forEach { it.fill(0) };chunks.clear();base=0;end=0 }
}
