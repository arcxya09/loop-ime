package app.loop.ime

import android.app.Application
import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import android.net.Uri
import android.content.Intent
import android.app.Activity
import android.app.AlertDialog
import android.widget.EditText
import java.io.ByteArrayOutputStream
import javax.crypto.spec.SecretKeySpec

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[37],application=Application::class)
class ConnectionBundleTest {
    private val c get()=RuntimeEnvironment.getApplication()
    private fun vault(seed: Int)=Vault(c) { _,_->SecretKeySpec(ByteArray(32) { seed.toByte() },"AES") }
    @Test fun allTextAndRegionalSpeechKeysSurviveAnIndependentWrappingKey() {
        val first=vault(5);AiProfiles(c,first).saveDeepSeek("sk-text-example")
        CloudSpeechSettings(c,first).save("sk-bailian-cn","beijing");CloudSpeechSettings(c,first).save("sk-bailian-sg","singapore")
        Prefs(c).keyboardHeight=KeyboardHeight.LOW
        Prefs(c).correctionMode=CorrectionMode.SUGGEST;Prefs(c).setLocalApp("app.private",true)
        val out=ByteArrayOutputStream();ConnectionBackup.exportAll(c,out,"backup-password".toCharArray(),first)
        assertFalse(out.toString(Charsets.UTF_8).contains("sk-text-example"))
        c.getSharedPreferences("loop-vault",Context.MODE_PRIVATE).edit().clear().commit();c.getSharedPreferences("loop-preferences",Context.MODE_PRIVATE).edit().clear().commit()
        val second=vault(9);ConnectionBackup.restoreAll(c,out.toByteArray().inputStream(),"backup-password".toCharArray(),second)
        assertEquals("sk-text-example",AiProfiles(c,second).current().key)
        assertEquals("sk-bailian-cn",CloudSpeechSettings(c,second).profile("beijing")!!.key)
        assertEquals("sk-bailian-sg",CloudSpeechSettings(c,second).profile("singapore")!!.key)
        assertEquals(KeyboardHeight.LOW,Prefs(c).keyboardHeight)
        assertEquals(CorrectionMode.SUGGEST,Prefs(c).correctionMode);assertTrue(Prefs(c).localApp("app.private"))
    }
    @Test fun corruptBundleCannotPartiallyReplaceAnySavedKeyAndLegacyFileStillImports() {
        val v=vault(3);AiProfiles(c,v).saveDeepSeek("sk-original");CloudSpeechSettings(c,v).save("sk-speech-original","beijing")
        val out=ByteArrayOutputStream();ConnectionBackup.exportAll(c,out,"backup-password".toCharArray(),v)
        val bad=out.toByteArray().also { it[it.lastIndex]=(it.last().toInt() xor 1).toByte() }
        try { ConnectionBackup.restoreAll(c,bad.inputStream(),"backup-password".toCharArray(),v);fail("Accepted modified bundle") } catch(_: IllegalStateException) { }
        assertEquals("sk-original",AiProfiles(c,v).current().key);assertEquals("sk-speech-original",CloudSpeechSettings(c,v).profile()!!.key)
        val old=ByteArrayOutputStream();ConnectionBackup.export(AiProtocol.deepSeek("sk-legacy"),old,"backup-password".toCharArray())
        ConnectionBackup.restoreAll(c,old.toByteArray().inputStream(),"backup-password".toCharArray(),v);assertEquals("sk-legacy",AiProfiles(c,v).current().key)
    }
    @Test fun filePickerResultAfterRecreationPromptsForPasswordAndSavesOnlyThePendingUri() {
        val life=Robolectric.buildActivity(SettingsActivity::class.java).setup();val a=life.get()
        var restored: org.robolectric.android.controller.ActivityController<SettingsActivity>?=null
        try {
            ReflectionHelpers.callInstanceMethod<Unit>(a,"onActivityResult",ClassParameter.from(Int::class.javaPrimitiveType,21),ClassParameter.from(Int::class.javaPrimitiveType,Activity.RESULT_OK),ClassParameter.from(Intent::class.java,Intent().setData(Uri.parse("content://loop-test/backup"))))
            val dialog=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();assertTrue(dialog.isShowing)
            assertNotNull(ReflectionHelpers.getField<Pair<Int,Uri>>(a,"pendingBackup"))
            val bundle=android.os.Bundle();life.saveInstanceState(bundle)
            assertEquals("content://loop-test/backup",bundle.getString("pending_backup_uri"));assertFalse(bundle.containsKey("backupPassword"))
            life.pause().stop().destroy()
            val next=Robolectric.buildActivity(SettingsActivity::class.java).create(bundle).start().restoreInstanceState(bundle).resume().visible()
            restored=next
            assertNotNull(ReflectionHelpers.getField<Pair<Int,Uri>>(next.get(),"pendingBackup"))
            val resumed=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();assertTrue(resumed.isShowing)
            resumed.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            assertNull(ReflectionHelpers.getField<Pair<Int,Uri>?>(next.get(),"pendingBackup"))
        } finally { if(!a.isDestroyed)life.pause().stop().destroy();restored?.pause()?.stop()?.destroy() }
    }
}
