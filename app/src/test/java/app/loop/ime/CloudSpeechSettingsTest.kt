package app.loop.ime

import android.app.Application
import android.net.NetworkCapabilities
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import javax.crypto.KeyGenerator

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[37],application=Application::class)
class CloudSpeechSettingsTest {
    @Test fun unvalidatedInternetStillAttemptsCloudButAbsentOrCaptiveNetworksUseOffline() {
        val caps=NetworkCapabilities();shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        assertTrue(CloudSpeechSettings.usableNetwork(caps))
        assertFalse(CloudSpeechSettings.usableNetwork(null))
        assertFalse(CloudSpeechSettings.usableNetwork(NetworkCapabilities()))
        shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)
        assertFalse(CloudSpeechSettings.usableNetwork(caps))
    }
    @Test fun keysSurviveReopeningAndStayIsolatedByProviderAndRegion() {
        val c=RuntimeEnvironment.getApplication();val wrapping=KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val vault=Vault(c) { _,_ -> wrapping }
        vault.put("api.default","unchanged-deepseek".toByteArray())
        val settings=CloudSpeechSettings(c,vault)
        settings.save("test-beijing","beijing");assertTrue(Prefs(c).flag("speech_cloud"))
        assertNull(settings.profile("singapore"))
        settings.save("test-singapore","singapore")
        val reopened=CloudSpeechSettings(c,Vault(c) { _,_ -> wrapping })
        assertEquals("test-beijing",reopened.profile("beijing")!!.key)
        assertEquals("test-singapore",reopened.save(" ","singapore").key)
        assertEquals("unchanged-deepseek",vault.get("api.default")!!.toString(Charsets.UTF_8))
        assertTrue(reopened.profile()!!.endpoint.contains("dashscope-intl"))
        val raw=c.getSharedPreferences("loop-vault",0).getString("speech.bailian.beijing","")!!
        assertTrue(raw.startsWith("v2:"));assertFalse(raw.contains("test-beijing"))
    }
    @Test fun invalidKeyCannotReplaceExistingCloudCredential() {
        val c=RuntimeEnvironment.getApplication();val wrapping=KeyGenerator.getInstance("AES").generateKey()
        val settings=CloudSpeechSettings(c,Vault(c) { _,_ -> wrapping })
        settings.save("saved-key","beijing")
        assertThrows(IllegalArgumentException::class.java) { settings.save("bad\nheader","beijing") }
        assertEquals("saved-key",settings.profile()!!.key)
    }
}
