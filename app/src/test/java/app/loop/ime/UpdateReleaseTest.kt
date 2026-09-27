package app.loop.ime

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class UpdateReleaseTest {
    private val manifests=mutableMapOf<String,ByteArray>()
    private fun release(code: Long, preview: Boolean=true, draft: Boolean=false): JSONObject {
        val version="0.1.$code"+if(preview)"-alpha.$code" else ""
        val tag="v$version";val base=UpdateRelease.REPO+"/releases/download/$tag/"
        val name="Loop-IME-$version.apk";val sha="a".repeat(64)
        val manifest=JSONObject().put("version",version).put("version_code",code).put("tag",tag).put("prerelease",preview)
            .put("assets",JSONObject().put(name,JSONObject().put("bytes",10).put("sha256",sha))).toString().toByteArray()
        manifests[base+"release-manifest.json"]=manifest
        fun asset(n: String,size: Int,digest: String)=JSONObject().put("name",n).put("size",size)
            .put("state","uploaded").put("browser_download_url",base+n).put("digest","sha256:$digest")
        return JSONObject().put("draft",draft).put("prerelease",preview).put("tag_name",tag).put("body","notes")
            .put("assets",JSONArray().put(asset(name,10,sha)).put(asset("release-manifest.json",manifest.size,UpdateRelease.digest(manifest))))
    }
    private fun select(vararg items: JSONObject, installed: Long=15, preview: Boolean=true)=
        UpdateRelease.select(JSONArray(items.toList()).toString(),installed,preview) { manifests.getValue(it) }
    @Test fun newestIsChosenByVersionCodeNotReleaseListOrder() {
        assertEquals(18L,select(release(16),release(18),release(17))!!.code)
    }
    @Test fun alphaChannelIncludesPreviewsAndStableChannelSkipsThem() {
        assertEquals(18L,select(release(18),release(17,false))!!.code)
        assertEquals(17L,select(release(18),release(17,false),preview=false)!!.code)
    }
    @Test fun draftsAndSameOrOlderVersionsNeverUpdate() {
        assertNull(select(release(30,draft=true),release(15),release(14)))
    }
    @Test fun foreignRepositoryDownloadIsRejected() {
        val r=release(16);r.getJSONArray("assets").getJSONObject(0).put("browser_download_url","https://github.com/other/repo/evil.apk")
        assertThrows(java.io.IOException::class.java) { select(r) }
    }
    @Test fun manifestTamperingIsRejected() {
        val r=release(16);val url=r.getJSONArray("assets").getJSONObject(1).getString("browser_download_url")
        manifests[url]="{}".toByteArray()
        assertThrows(java.io.IOException::class.java) { select(r) }
    }
    @Test fun incompleteReleaseAndWrongAssetDigestAreRejected() {
        val r=release(16);r.getJSONArray("assets").getJSONObject(0).put("digest","sha256:"+"b".repeat(64))
        assertThrows(java.io.IOException::class.java) { select(r) }
        r.getJSONArray("assets").getJSONObject(0).put("state","new")
        assertThrows(java.io.IOException::class.java) { select(r) }
    }
    @Test fun cachedMetadataRejectsUnsafeUrlsAndRoundTrips() {
        val r=select(release(16))!!
        assertEquals(r,UpdateRelease.restore(r.json()))
        assertThrows(IllegalArgumentException::class.java) { UpdateRelease.restore(JSONObject(r.json()).put("url","http://github.com/file.apk").toString()) }
        assertThrows(IllegalArgumentException::class.java) { UpdateRelease.restore(JSONObject(r.json()).put("bytes",UpdateRelease.MAX_APK+1).toString()) }
    }
    @Test fun apkHashAndSizeMustBothMatch() {
        val file=Files.createTempFile("update-test",".apk").toFile()
        try {
            file.writeText("good")
            val r=UpdateRelease("0.1.16-alpha.16",16,4,UpdateRelease.digest(file.readBytes()),"","",true)
            UpdateRelease.verify(file,r)
            file.writeText("evil")
            assertThrows(IllegalArgumentException::class.java) { UpdateRelease.verify(file,r) }
            file.writeText("truncated")
            assertThrows(IllegalArgumentException::class.java) { UpdateRelease.verify(file,r) }
        } finally { file.delete() }
    }
    @Test fun githubNetworkFailureIsReportedInsteadOfClaimingLatest() {
        val r=release(16)
        assertThrows(java.io.IOException::class.java) { UpdateRelease.select(JSONArray().put(r).toString(),15,true) { throw java.io.IOException("offline") } }
    }
}
