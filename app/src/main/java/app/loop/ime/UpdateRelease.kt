package app.loop.ime

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

data class UpdateRelease(val version: String, val code: Long, val bytes: Long, val sha256: String,
                         val url: String, val notes: String, val prerelease: Boolean) {
    fun json() = JSONObject().put("version",version).put("code",code).put("bytes",bytes)
        .put("sha256",sha256).put("url",url).put("notes",notes).put("prerelease",prerelease).toString()
    companion object {
        const val REPO="https://github.com/arcxya09/loop-ime"
        const val API="https://api.github.com/repos/arcxya09/loop-ime/releases?per_page=20"
        const val MAX_APK=300L*1024*1024
        fun restore(text: String): UpdateRelease {
            val j=JSONObject(text)
            return checked(UpdateRelease(j.getString("version"),j.getLong("code"),j.getLong("bytes"),
                j.getString("sha256"),j.getString("url"),j.optString("notes").take(12000),j.getBoolean("prerelease")))
        }
        private fun checked(r: UpdateRelease): UpdateRelease {
            require(Regex("[0-9]+\\.[0-9]+\\.[0-9]+(?:-[A-Za-z0-9.-]+)?").matches(r.version) && r.version.length<=80)
            require(r.code>0 && r.bytes in 1..MAX_APK && Regex("[a-f0-9]{64}").matches(r.sha256))
            require(r.url=="$REPO/releases/download/v${r.version}/Loop-IME-${r.version}.apk")
            return r
        }
        fun select(list: String, installed: Long, includePreview: Boolean, fetch: (String)->ByteArray): UpdateRelease? {
            val releases=JSONArray(list)
            var newest: UpdateRelease?=null
            var invalid=false
            for(i in 0 until minOf(releases.length(),20)) {
                val release=releases.getJSONObject(i)
                if(release.optBoolean("draft",true) || (!includePreview && release.optBoolean("prerelease")))continue
                val tag=release.optString("tag_name")
                if(!tag.startsWith("v"))continue
                val version=tag.substring(1)
                if(!Regex("[0-9]+\\.[0-9]+\\.[0-9]+(?:-[A-Za-z0-9.-]+)?").matches(version))continue
                try {
                    val assets=release.getJSONArray("assets")
                    fun asset(name: String): JSONObject = (0 until assets.length()).map { assets.getJSONObject(it) }
                        .single { it.optString("name")==name && it.optString("state")=="uploaded" }
                    val manifest=asset("release-manifest.json")
                    val manifestUrl="$REPO/releases/download/$tag/release-manifest.json"
                    require(manifest.getString("browser_download_url")==manifestUrl)
                    val raw=fetch(manifestUrl)
                    require(raw.size<=128*1024 && manifest.getString("digest")=="sha256:"+digest(raw))
                    val m=JSONObject(raw.toString(Charsets.UTF_8))
                    require(m.getString("version")==version && m.getString("tag")==tag)
                    require(m.getBoolean("prerelease")==release.getBoolean("prerelease"))
                    val name="Loop-IME-$version.apk"
                    val apk=asset(name);val info=m.getJSONObject("assets").getJSONObject(name)
                    val r=checked(UpdateRelease(version,m.getLong("version_code"),info.getLong("bytes"),
                        info.getString("sha256"),apk.getString("browser_download_url"),
                        release.optString("body").take(12000),release.getBoolean("prerelease")))
                    require(apk.getLong("size")==r.bytes && apk.getString("digest")=="sha256:"+r.sha256)
                    if(r.code>installed && r.code>(newest?.code ?: 0))newest=r
                } catch(e: IOException) { throw e } catch(_: Exception) { invalid=true }
            }
            if(newest==null && invalid)throw IOException("发布信息不完整或校验失败，请稍后重试")
            return newest
        }
        fun digest(raw: ByteArray)=MessageDigest.getInstance("SHA-256").digest(raw).joinToString("") { "%02x".format(it) }
        fun verify(file: File, r: UpdateRelease) {
            require(file.isFile && file.length()==r.bytes) { "安装包大小不符" }
            val digest=MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input -> val buffer=ByteArray(65536);while(true) { val n=input.read(buffer);if(n<0)break;digest.update(buffer,0,n) } }
            require(digest.digest().joinToString("") { "%02x".format(it) }==r.sha256) { "安装包摘要不符" }
        }
    }
}

/** Dedicated public GitHub client: never shares AI keys, headers, cookies or input data. */
internal class UpdateHttp(private val client: OkHttpClient=OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(15,TimeUnit.SECONDS).readTimeout(20,TimeUnit.SECONDS).callTimeout(25,TimeUnit.SECONDS).build()) {
    fun get(start: String, limit: Long): ByteArray {
        var url=start.toHttpUrl()
        repeat(6) {
            require(url.isHttps && url.username.isEmpty() && url.password.isEmpty() && url.port==443 &&
                url.host in setOf("api.github.com","github.com","release-assets.githubusercontent.com","objects.githubusercontent.com"))
            client.newCall(Request.Builder().url(url).header("User-Agent","Loop-IME-Updater")
                .header("Accept","application/json").build()).execute().use { response ->
                if(response.code in listOf(301,302,303,307,308)) {
                    url=url.resolve(response.header("Location") ?: throw IOException("更新下载地址缺失"))
                        ?: throw IOException("更新下载地址无效")
                } else {
                    if(!response.isSuccessful)throw IOException(if(response.code==403 || response.code==429)"GitHub 请求受限，请稍后重试" else "GitHub 检查失败（${response.code}）")
                    val body=response.body ?: throw IOException("更新响应为空")
                    if(body.contentLength()>limit)throw IOException("更新响应过大")
                    val buffer=body.byteStream().readNBytes((limit+1).toInt())
                    if(buffer.size>limit)throw IOException("更新响应过大")
                    return buffer
                }
            }
        }
        throw IOException("更新下载重定向过多")
    }
}
