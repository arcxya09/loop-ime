package app.loop.ime

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.widget.Button
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[37],application=Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class OfflineModelSettingsTest {
    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE) @Config(qualifiers="zh-rCN-w360dp-h800dp-xxhdpi")
    fun freshInstallHasNoModelAndShowsOptionalDownloadWithoutAutomaticallyStartingOne() {
        val c=RuntimeEnvironment.getApplication()
        assertTrue(c.assets.list("asr").isNullOrEmpty());assertNull(OfflineModels.activePath(c))
        val activity=Robolectric.buildActivity(SettingsActivity::class.java,Intent(c,SettingsActivity::class.java).putExtra("page","offline_model")).setup().visible()
        try {
            assertEquals("只用云端语音时，可跳过下载",activity.get().findViewById<TextView>(R.id.offline_model_status).text.toString())
            val download=activity.get().findViewById<Button>(R.id.offline_model_download)
            assertTrue(download.isEnabled);assertTrue(download.text.contains("190 MiB"));assertFalse(OfflineModels.status.busy)
            val view=activity.get().window.decorView
            view.measure(View.MeasureSpec.makeMeasureSpec(1080,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(2400,View.MeasureSpec.EXACTLY));view.layout(0,0,1080,2400)
            val bitmap=Bitmap.createBitmap(1080,2400,Bitmap.Config.ARGB_8888);view.draw(Canvas(bitmap))
            val file=File("build/offline-model-alpha9.png");file.parentFile!!.mkdirs();file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it)) };bitmap.recycle()
        } finally { activity.pause().stop().destroy() }
    }
    @Test fun previouslyImportedCustomModelRemainsSelectedWithoutDownloadingStandardModel() {
        val c=RuntimeEnvironment.getApplication();val custom=File(c.noBackupFilesDir,"model-import-test").apply { mkdirs() }
        OfflineModelCatalog.files.forEach { File(custom,it.name).writeText("test-file") }
        Prefs(c).set("custom_model",custom.absolutePath)
        assertEquals(custom.absolutePath,OfflineModels.activePath(c));assertFalse(OfflineModels.pack(c).installed())
        File(custom,"tokens.txt").delete();assertNull(OfflineModels.activePath(c))
    }
    @Test fun missingModelHintStopsVoiceAndOpensDownloadPageWhenTapped() {
        val life=Robolectric.buildService(LoopImeService::class.java).create();val service=life.get()
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(android.Manifest.permission.RECORD_AUDIO)
        val keyboard=KeyboardView(service) {}
        val speech=SpeechController(service) { kind,text ->
            ReflectionHelpers.callInstanceMethod<Unit>(service,"onSpeech",ClassParameter.from(Int::class.javaPrimitiveType!!,kind),ClassParameter.from(String::class.java,text))
        }
        Prefs(service).set("private",true)
        ReflectionHelpers.setField(service,"visible",true);ReflectionHelpers.setField(service,"keyboard",keyboard);ReflectionHelpers.setField(service,"speech",speech)
        try {
            ReflectionHelpers.callInstanceMethod<Unit>(service,"enqueue",ClassParameter.from(String::class.java,"mic"))
            assertFalse(ReflectionHelpers.getField<Boolean>(service,"voice"))
            val status=keyboard.findViewWithTag<TextView>("keyboard_status")
            assertTrue(status.text.contains("离线模型未下载"));status.performClick()
            assertEquals("offline_model",shadowOf(service).nextStartedActivity.getStringExtra("page"))
        } finally { life.destroy() }
    }
}
