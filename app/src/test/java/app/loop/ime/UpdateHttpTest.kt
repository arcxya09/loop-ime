package app.loop.ime

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class UpdateHttpTest {
    @Test fun shortJsonResponseAndGitHubAssetRedirectWorkWithoutCredentials() {
        val seen=mutableListOf<String>()
        val client=OkHttpClient.Builder().followRedirects(false).addInterceptor { chain ->
            val request=chain.request();seen.add(request.url.host)
            assertNull(request.header("Authorization"));assertNull(request.header("Cookie"))
            assertEquals("Loop-IME-Updater",request.header("User-Agent"))
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).message("ok")
                .apply { if(seen.size==1) { code(302);header("Location","https://release-assets.githubusercontent.com/asset");body("".toResponseBody()) }
                    else { code(200);body("{}".toResponseBody()) } }.build()
        }.build()
        assertEquals("{}",UpdateHttp(client).get(UpdateRelease.REPO+"/releases/download/v1/file",128*1024).toString(Charsets.UTF_8))
        assertEquals(listOf("github.com","release-assets.githubusercontent.com"),seen)
    }
    @Test fun redirectToUntrustedOrCleartextHostIsRejectedBeforeSecondRequest() {
        for(location in listOf("https://evil.example/file","http://github.com/file","https://user:pass@github.com/file")) {
            var count=0
            val client=OkHttpClient.Builder().followRedirects(false).addInterceptor { chain ->
                count++;Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).message("redirect").code(302)
                    .header("Location",location).body("".toResponseBody()).build()
            }.build()
            assertThrows(IllegalArgumentException::class.java) { UpdateHttp(client).get(UpdateRelease.API,100) }
            assertEquals(1,count)
        }
    }
    @Test fun oversizedMetadataAndRateLimitsAreReported() {
        for(code in listOf(200,403,429)) {
            val client=OkHttpClient.Builder().addInterceptor { chain ->
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).message("response").code(code)
                    .body("too large".toResponseBody()).build()
            }.build()
            assertThrows(IOException::class.java) { UpdateHttp(client).get(UpdateRelease.API,2) }
        }
    }
}
