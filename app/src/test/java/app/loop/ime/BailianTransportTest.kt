package app.loop.ime

import android.app.Application
import android.os.Looper
import okhttp3.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[37],application=Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class BailianTransportTest {
    private fun idleUntil(condition: ()->Boolean) {
        val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5)
        while(!condition() && System.nanoTime()<deadline) { shadowOf(Looper.getMainLooper()).idle();Thread.yield() }
        assertTrue("WebSocket stage timed out",condition())
    }
    private fun message(id: String,event: String,sentence: JSONObject?=null)=JSONObject().put("header",JSONObject().put("task_id",id).put("event",event))
        .put("payload",if(sentence==null)JSONObject() else JSONObject().put("output",JSONObject().put("sentence",sentence))).toString()
    @Test fun realWebSocketSendsBearerTaskPcmAndFinishAndIgnoresForeignDuplicateAndHeartbeatResults() {
        val server=MockWebServer();val client=OkHttpClient();val frames=java.util.concurrent.CopyOnWriteArrayList<ByteString>()
        val commands=java.util.concurrent.CopyOnWriteArrayList<String>();var id=""
        val serverClosed=CountDownLatch(1)
        server.enqueue(MockResponse().withWebSocketUpgrade(object: WebSocketListener() {
            override fun onClosing(webSocket: WebSocket,code: Int,reason: String) { webSocket.close(code,reason) }
            override fun onClosed(webSocket: WebSocket,code: Int,reason: String) { serverClosed.countDown() }
            override fun onMessage(webSocket: WebSocket,text: String) {
                val json=JSONObject(text);val header=json.getJSONObject("header");val action=header.getString("action");commands+=action
                if(action=="run-task") {
                    id=header.getString("task_id")
                    assertEquals(CloudAsrProfile.MODEL,json.getJSONObject("payload").getString("model"))
                    webSocket.send(message("foreign-task","task-started"));webSocket.send(message(id,"task-started"))
                } else if(action=="finish-task")webSocket.send(message(id,"task-finished"))
            }
            override fun onMessage(webSocket: WebSocket,bytes: ByteString) {
                frames+=bytes
                webSocket.send(message(id,"result-generated",JSONObject().put("heartbeat",true)))
                webSocket.send(message(id,"result-generated",JSONObject().put("sentence_id",1).put("text","你").put("sentence_end",false)))
                val final=message(id,"result-generated",JSONObject().put("sentence_id",1).put("text","你好").put("sentence_end",true).put("end_time",80))
                webSocket.send(final);webSocket.send(final)
            }
        }));server.start()
        val events=mutableListOf<String>();lateinit var stream: BailianAsr
        val factory=object: WebSocket.Factory {
            override fun newWebSocket(request: Request,listener: WebSocketListener): WebSocket {
                assertEquals("dashscope.aliyuncs.com",request.url.host)
                assertEquals("Bearer test-key",request.header("Authorization"))
                return client.newWebSocket(request.newBuilder().url(server.url("/api-ws/v1/inference")).build(),listener)
            }
        }
        stream=BailianAsr(CloudAsrProfile("test-key"),emptyList(),object: CloudAsrListener {
            override fun ready() { events+="ready";assertTrue(stream.audio(ShortArray(1280) { -32768 })) }
            override fun partial(text: String) { events+="partial:$text" }
            override fun final(text: String,endMs: Long) { events+="final:$text";assertEquals(80L,endMs);stream.finish() }
            override fun done() { events+="done" }
            override fun failed(error: AsrFailure) { events+="error:${error.message}" }
        },factory)
        try {
            stream.start();idleUntil { events.lastOrNull()=="done" }
            assertEquals(listOf("ready","partial:你","final:你好","done"),events)
            assertEquals(listOf("run-task","finish-task"),commands)
            assertEquals(2560,frames.single().size);assertArrayEquals(byteArrayOf(0,0x80.toByte()),frames.single().substring(0,2).toByteArray())
            val request=server.takeRequest(2,TimeUnit.SECONDS)!!
            assertEquals("websocket",request.getHeader("Upgrade"));assertEquals("Bearer test-key",request.getHeader("Authorization"))
            assertTrue("Server did not finish the close handshake",serverClosed.await(3,TimeUnit.SECONDS))
        } finally { stream.cancel();client.dispatcher.executorService.shutdown();client.connectionPool.evictAll();server.close() }
    }
    @Test fun handshake401ReportsAuthenticationAndDoesNotEchoServerBody() {
        val server=MockWebServer();val client=OkHttpClient();server.enqueue(MockResponse().setResponseCode(401).setBody("leaked-secret"));server.start()
        var failure: AsrFailure?=null
        val factory=object: WebSocket.Factory { override fun newWebSocket(request: Request,listener: WebSocketListener)=client.newWebSocket(request.newBuilder().url(server.url("/ws")).build(),listener) }
        val stream=BailianAsr(CloudAsrProfile("test-key"),emptyList(),object: CloudAsrListener {
            override fun ready() { fail("Unauthenticated socket became ready") }
            override fun partial(text: String) { fail("Unexpected partial") }
            override fun final(text: String,endMs: Long) { fail("Unexpected final") }
            override fun done() { fail("Unexpected done") }
            override fun failed(error: AsrFailure) { failure=error }
        },factory)
        try { stream.start();idleUntil { failure!=null };assertEquals(AsrFailureKind.AUTH,failure!!.kind);assertFalse(failure!!.message.contains("leaked-secret")) }
        finally { stream.cancel();client.dispatcher.executorService.shutdown();client.connectionPool.evictAll();server.close() }
    }
}
