package app.loop.ime

import android.app.Application
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[37],application=Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class CloudOnlySettingsTest {
    private fun all(v: View): List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap { all(v.getChildAt(it)) } else emptyList()
    @Test fun speechPageAndOldShortcutHaveNoModelControlsAndPreserveExistingData() {
        val c=RuntimeEnvironment.getApplication();val legacy=File(c.noBackupFilesDir,"model-import-existing").apply { mkdirs() }
        val file=File(legacy,"tokens.txt").apply { writeText("legacy-user-model") };Prefs(c).set("custom_model",legacy.path)
        for(page in listOf("speech","offline_model")) {
            val life=Robolectric.buildActivity(SettingsActivity::class.java,Intent(c,SettingsActivity::class.java).putExtra("page",page)).setup()
            try {
                val labels=all(life.get().window.decorView).filterIsInstance<TextView>().map { it.text.toString() }
                assertTrue(labels.any { it.contains("百炼 API Key") });assertTrue(labels.any { it=="启用百炼云端语音" })
                assertFalse(labels.any { it.contains("离线模型") || it.contains("本地识别") || it.contains("云端优先") })
                assertEquals("legacy-user-model",file.readText());assertEquals(legacy.path,Prefs(c).text("custom_model"))
            } finally { life.pause().stop().destroy() }
        }
    }
}
