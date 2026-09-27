package app.loop.ime

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class OfflineModelPackageTest {
    @get:Rule val temporary=TemporaryFolder()
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private val data=listOf(ByteArray(196608) { (it%239).toByte() },"词库样本 abc\n".toByteArray())
    private fun hash(bytes: ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private val specs get()=data.mapIndexed { i,bytes -> ModelFile(if(i==0)"encoder.onnx" else "tokens.txt","remote-$i",bytes.size.toLong(),hash(bytes)) }
    private fun pack()=OfflineModelPackage(File(temporary.root,"models"),specs,"fixture-v1")
    private fun body(bytes: ByteArray)=MockResponse().setBody(Buffer().write(bytes))
    private fun task(model: OfflineModelPackage=pack())=ModelDownload(model,client,{ server.url(it.remote).toString() })
    private fun expectFailure(action: ()->Unit) { var thrown=false;try { action() } catch(_: Exception) { thrown=true };assertTrue("Expected an incomplete download",thrown) }
    @Before fun setup() {
        server=MockWebServer();server.start()
        client=OkHttpClient.Builder().dns(object: okhttp3.Dns { override fun lookup(hostname: String)=listOf(java.net.InetAddress.getByName("127.0.0.1")) })
            .retryOnConnectionFailure(false).readTimeout(2,TimeUnit.SECONDS).build()
    }
    @After fun close() { server.shutdown();client.dispatcher.executorService.shutdown();client.connectionPool.evictAll() }
    @Test fun downloadsOnlyExpectedFilesVerifiesEveryHashAndPublishesAtomically() {
        val model=pack();data.forEach { server.enqueue(body(it)) }
        assertFalse(model.installed())
        var verifiedBeforePublish=false
        task(model).run { _,phase -> if(phase=="正在校验模型") { assertFalse(model.installed());verifiedBeforePublish=true } }
        assertTrue(verifiedBeforePublish);assertTrue(model.installed());assertFalse(model.staging.exists())
        assertTrue(model.verifyDirectory(model.directory))
        for(i in data.indices) {
            assertArrayEquals(data[i],File(model.directory,specs[i].name).readBytes())
            val request=server.takeRequest(1,TimeUnit.SECONDS)!!
            assertEquals("/remote-$i",request.path);assertNull(request.getHeader("Authorization"));assertEquals("identity",request.getHeader("Accept-Encoding"))
        }
    }
    @Test fun interruptedConnectionResumesAfterRecreatingDownloaderAndModelStore() {
        var model=pack();server.enqueue(body(data[0]).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
        expectFailure { task(model).run { _,_-> } }
        val offset=File(model.staging,"encoder.onnx").length().toInt()
        assertTrue(offset in 1 until data[0].size);assertFalse(model.installed());server.takeRequest()
        model=pack()
        server.enqueue(body(data[0].copyOfRange(offset,data[0].size)).setResponseCode(206).addHeader("Content-Range","bytes $offset-${data[0].lastIndex}/${data[0].size}"))
        server.enqueue(body(data[1]));task(model).run { _,_-> }
        assertEquals("bytes=$offset-",server.takeRequest().getHeader("Range"));assertTrue(model.installed())
        assertTrue(model.verifyDirectory(model.directory))
    }
    @Test fun serverIgnoringRangeOverwritesPartialFileInsteadOfDuplicatingPrefix() {
        val model=pack();model.staging.mkdirs();File(model.staging,"encoder.onnx").writeBytes(data[0].copyOf(400))
        data.forEach { server.enqueue(body(it)) };task(model).run { _,_-> }
        assertEquals("bytes=400-",server.takeRequest().getHeader("Range"))
        assertArrayEquals(data[0],File(model.directory,"encoder.onnx").readBytes())
    }
    @Test fun invalidContentRangeNeverAppendsToPartialOrEnablesASR() {
        val model=pack();model.staging.mkdirs();val partial=File(model.staging,"encoder.onnx");partial.writeBytes(data[0].copyOf(400))
        server.enqueue(body(data[0].copyOfRange(401,data[0].size)).setResponseCode(206).addHeader("Content-Range","bytes 401-${data[0].lastIndex}/${data[0].size}"))
        expectFailure { task(model).run { _,_-> } }
        assertEquals(400,partial.length().toInt());assertFalse(model.installed())
    }
    @Test fun checksumFailureCannotPublishIncompletePackageAndCanBeRetried() {
        val model=pack();server.enqueue(body(data[0]));server.enqueue(body(ByteArray(data[1].size)))
        expectFailure { task(model).run { _,_-> } }
        assertFalse(model.installed());assertFalse(model.directory.exists());assertFalse(File(model.staging,"tokens.txt").exists())
        assertTrue(File(model.staging,"encoder.onnx").exists())
        server.enqueue(body(data[1]));task(model).run { _,_-> }
        assertTrue(model.installed());assertEquals(3,server.requestCount)
    }
    @Test fun cancellingRetainsPartialAndReentryDownloadsOnlyRemainingBytes() {
        val model=pack();server.enqueue(body(data[0]));val download=task(model)
        expectFailure { download.run { bytes,_-> if(bytes>0)download.cancel() } }
        val offset=File(model.staging,"encoder.onnx").length().toInt();server.takeRequest()
        assertTrue(offset in 1 until data[0].size);assertFalse(model.installed())
        server.enqueue(body(data[0].copyOfRange(offset,data[0].size)).setResponseCode(206).addHeader("Content-Range","bytes $offset-${data[0].lastIndex}/${data[0].size}"));server.enqueue(body(data[1]))
        task(model).run { _,_-> };assertTrue(model.installed())
    }
    @Test fun unavailableSourceKeepsExistingProgressAndNeverCreatesReadyMarker() {
        val model=pack();model.staging.mkdirs();File(model.staging,"encoder.onnx").writeBytes(data[0].copyOf(400))
        server.enqueue(MockResponse().setResponseCode(404));expectFailure { task(model).run { _,_-> } }
        assertEquals(400,model.cachedBytes().toInt());assertFalse(model.installed())
    }
    @Test fun insufficientSpaceAndConcurrentTransferCannotStartARequest() {
        val model=pack();expectFailure { ModelDownload(model,client,{ server.url(it.remote).toString() },{ 0 }).run { _,_-> } }
        model.locked { expectFailure { task(model).run { _,_-> } } }
        assertEquals(0,server.requestCount);assertFalse(model.installed())
    }
    @Test fun readyMarkerRequiresAllFilesAndRemovalLeavesOtherModelsUntouched() {
        val model=pack();model.staging.mkdirs();specs.forEachIndexed { i,s -> File(model.staging,s.name).writeBytes(data[i]) }
        assertFalse(model.installed());model.publish();assertTrue(model.installed())
        File(model.directory,"tokens.txt").delete();assertFalse(model.installed())
        val custom=File(model.root,"custom-import").apply { mkdirs() };File(custom,"user-model").writeText("keep")
        model.remove();assertFalse(model.directory.exists());assertEquals("keep",File(custom,"user-model").readText())
    }
    @Test fun rangeNotSatisfiableRestartsOnceAndRejectsOversizedFileBeforeWriting() {
        val model=pack();model.staging.mkdirs();File(model.staging,"encoder.onnx").writeBytes(data[0].copyOf(400))
        server.enqueue(MockResponse().setResponseCode(416));data.forEach { server.enqueue(body(it)) }
        task(model).run { _,_-> };assertTrue(model.installed());assertEquals(3,server.requestCount)
        model.remove();server.enqueue(body(ByteArray(data[0].size+1)))
        expectFailure { task(model).run { _,_-> } };assertFalse(model.installed());assertEquals(0L,model.cachedBytes())
    }
}
