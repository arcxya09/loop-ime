package app.loop.ime

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BailianProtocolTest {
    @Test fun usesAsrDuplexTaskAndOnlyExplicitCloudVocabulary() {
        val j=JSONObject(BailianProtocol.start("task-1",listOf("核天体物理","核天体物理")))
        assertEquals("run-task",j.getJSONObject("header").getString("action"))
        assertEquals("duplex",j.getJSONObject("header").getString("streaming"))
        val p=j.getJSONObject("payload");assertEquals("asr",p.getString("task"))
        assertEquals("qwen-audio-3.0-asr-flash-streaming",p.getString("model"))
        val params=p.getJSONObject("parameters")
        assertEquals("pcm",params.getString("format"));assertEquals(16000,params.getInt("sample_rate"))
        assertTrue(params.getBoolean("heartbeat"));assertEquals(1,params.getJSONObject("vocabulary").length())
        assertEquals(0,p.getJSONObject("input").length())
        val finish=JSONObject(BailianProtocol.finish("task-1"))
        assertEquals("task-1",finish.getJSONObject("header").getString("task_id"))
        assertEquals("finish-task",finish.getJSONObject("header").getString("action"))
        assertEquals(0,finish.getJSONObject("payload").getJSONObject("input").length())
    }
    @Test fun pcmIsSignedLittleEndianWithoutWavOrBase64() {
        assertArrayEquals(byteArrayOf(0,0,0xff.toByte(),0x7f,0,0x80.toByte(),0xff.toByte(),0xff.toByte()),BailianProtocol.pcm16(shortArrayOf(0,32767,-32768,-1)))
    }
    @Test fun authenticationAndQuotaAreNotNetworkFailuresAndSecretsAreNeverEchoed() {
        assertEquals(AsrFailureKind.AUTH,BailianProtocol.httpError(401).kind)
        assertEquals(AsrFailureKind.SERVICE,BailianProtocol.httpError(429).kind)
        val failure=BailianProtocol.taskError("BAD_API_KEY","raw-secret-key")
        assertEquals(AsrFailureKind.AUTH,failure.kind);assertFalse(failure.message.contains("raw-secret-key"))
        assertEquals(AsrFailureKind.NETWORK,BailianProtocol.taskError("CLIENT_ERROR","request timeout after 23 seconds").kind)
        assertFalse(CloudAsrProfile("private-key").toString().contains("private-key"))
    }
    @Test fun replayRemovesConfirmedAudioAtExactSampleBoundary() {
        val buffer=PcmReplay(128)
        buffer.append(ShortArray(32) { it.toShort() });buffer.append(ShortArray(32) { (it+32).toShort() })
        buffer.confirm(3);assertEquals(16,buffer.size)
        assertArrayEquals(ShortArray(16) { (it+48).toShort() },buffer.takeTail().single())
        assertEquals(0,buffer.size)
    }
    @Test fun replayOverflowAndInvalidTimestampDoNotSilentlyDiscardTail() {
        val buffer=PcmReplay(32);buffer.append(ShortArray(32) { 7 })
        assertThrows(IllegalStateException::class.java) { buffer.append(shortArrayOf(8)) }
        assertThrows(IllegalArgumentException::class.java) { buffer.confirm(100000) }
        assertEquals(32,buffer.size);assertArrayEquals(ShortArray(32) { 7 },buffer.takeTail().single())
    }
}
