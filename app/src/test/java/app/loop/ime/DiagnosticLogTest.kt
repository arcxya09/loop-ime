package app.loop.ime

import android.app.Application
import android.app.Activity
import android.content.ClipboardManager
import android.content.ClipDescription
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.widget.TextView
import android.security.keystore.UserNotAuthenticatedException
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[37],application=Application::class)
class DiagnosticLogTest {
    private val c get()=RuntimeEnvironment.getApplication()
    @Before fun setup() {
        DiagnosticLog.flush()
        ReflectionHelpers.setStaticField(DiagnosticLog::class.java,"store",null)
        DiagnosticLog.attach(c);DiagnosticLog.clear(c)
    }
    @Test fun exceptionCauseAndLocationSurviveWithoutMessagesSqlNamesKeysOrUrls() {
        val cause=UserNotAuthenticatedException("sk-private-secret 王小明 13800138000")
        val error=IllegalStateException("SQL SELECT '敏感输入' FROM contacts; https://secret.example/key=hidden",cause)
        error.stackTrace=arrayOf(StackTraceElement("app.loop.ime.PersonalStore","open","PersonalStore.kt",250))
        error.addSuppressed(IllegalArgumentException("backup-password-secret"))
        val trace=DiagnosticLog.begin(DiagnosticLog.Area.CONTACTS)
        trace.mark(DiagnosticLog.Step.DATABASE_OPEN);trace.failure(error)
        val report=DiagnosticLog.report(c,true)
        for(secret in listOf("sk-private-secret","王小明","13800138000","敏感输入","secret.example","backup-password-secret","SELECT"))assertFalse(secret,report.contains(secret))
        assertTrue(report.contains("KEYSTORE_AUTH_REQUIRED"));assertTrue(report.contains("DATABASE_OPEN"))
        assertTrue(report.contains("app.loop.ime.PersonalStore.open:250"));assertTrue(report.contains("UserNotAuthenticatedException"))
    }
    @Test fun loggerWorksWithUnreadableDatabaseAndVaultWithoutChangingEither() {
        val database=File(c.noBackupFilesDir,"loop.db").apply { writeText("unreadable-existing-database") }
        val vault=c.getSharedPreferences("loop-vault",Context.MODE_PRIVATE)
        vault.edit().putString("database","legacy-inaccessible-secret").putString("api.deepseek","secret-api-record").commit()
        val report=DiagnosticLog.report(c)
        assertTrue(report.contains("database_key_format=legacy"));assertTrue(report.contains("deepseek_record_present=true"))
        assertFalse(report.contains("legacy-inaccessible-secret"));assertFalse(report.contains("secret-api-record"))
        assertEquals("unreadable-existing-database",database.readText());assertEquals("legacy-inaccessible-secret",vault.getString("database",null))
    }
    @Test fun boundsAndRotationPreserveNewestEntriesAcrossReopenAndProcesses() {
        val dir=Files.createTempDirectory("loop-log").toFile()
        try {
            val store=DiagnosticFiles(dir,"main",450)
            repeat(30) { store.append(JSONObject().put("time",Instant.now().toString()).put("row",it).put("padding","x".repeat(60)).toString()) }
            DiagnosticFiles(dir,"asr",450).append(JSONObject().put("time",Instant.now().toString()).put("asr",true).toString())
            assertTrue(dir.listFiles()!!.sumOf { it.length() }<=1350)
            val reopened=DiagnosticFiles(dir,"main",450).snapshot(1000)
            assertTrue(reopened.contains("\"row\":29"));assertFalse(reopened.contains("\"row\":0,"));assertTrue(reopened.contains("\"asr\":true"))
            assertTrue(reopened.toByteArray().size<=1000)
            assertTrue(store.snapshot(220).toByteArray().size<=220)
        } finally { dir.deleteRecursively() }
    }
    @Test fun expiredAndIncompleteRecordsAreExcludedAndClearCoversBothProcesses() {
        val dir=Files.createTempDirectory("loop-log-expiry").toFile()
        try {
            val old=Instant.now().minusSeconds(8L*24*3600).toString()
            File(dir,"main.jsonl").writeText("{\"time\":\"$old\",\"old\":true}\n{broken\n")
            DiagnosticFiles(dir,"asr").append(JSONObject().put("time",Instant.now().toString()).put("recent",true).toString())
            val store=DiagnosticFiles(dir,"main")
            assertFalse(store.snapshot(1000).contains("old"));assertTrue(store.snapshot(1000).contains("recent"))
            store.clear();assertEquals("",store.snapshot(1000))
        } finally { dir.deleteRecursively() }
    }
    @Test fun diagnosticPageCopiesAndExportsWithoutReadingKeysOrDatabase() {
        c.getSharedPreferences("loop-vault",Context.MODE_PRIVATE).edit().putString("database","cannot-decrypt").commit()
        DiagnosticLog.failure(DiagnosticLog.Area.CONTACTS,SecurityException("PRIVATE_CONTACT"))
        val life=Robolectric.buildActivity(SettingsActivity::class.java,Intent(c,SettingsActivity::class.java).putExtra("page","diagnostics")).setup()
        try {
            DiagnosticLog.reports.submit {}.get(5,TimeUnit.SECONDS);shadowOf(Looper.getMainLooper()).idle()
            val activity=life.get()
            assertTrue(activity.findViewById<TextView>(R.id.diagnostics_content).isTextSelectable)
            activity.findViewById<android.view.View>(R.id.diagnostics_copy).performClick()
            DiagnosticLog.reports.submit {}.get(5,TimeUnit.SECONDS);shadowOf(Looper.getMainLooper()).idle()
            val clipboard=activity.getSystemService(ClipboardManager::class.java).primaryClip!!
            assertTrue(clipboard.getItemAt(0).text.contains("CONTACTS"));assertFalse(clipboard.getItemAt(0).text.contains("PRIVATE_CONTACT"))
            assertTrue(clipboard.description.extras!!.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE))
            activity.findViewById<android.view.View>(R.id.diagnostics_export).performClick()
            val request=shadowOf(activity).nextStartedActivityForResult
            assertEquals(40,request.requestCode);assertEquals(Intent.ACTION_CREATE_DOCUMENT,request.intent.action)
            assertEquals("text/plain",request.intent.type)
            val file=File(c.cacheDir,"test-diagnostic-export.txt")
            val data=Intent().setData(android.net.Uri.fromFile(file))
            ReflectionHelpers.callInstanceMethod<Unit>(activity,"onActivityResult",ClassParameter.from(Int::class.javaPrimitiveType,40),ClassParameter.from(Int::class.javaPrimitiveType,Activity.RESULT_OK),ClassParameter.from(Intent::class.java,data))
            DiagnosticLog.reports.submit {}.get(5,TimeUnit.SECONDS);shadowOf(Looper.getMainLooper()).idle()
            assertTrue(file.isFile);assertTrue(file.readText().contains("CONTACTS"));assertFalse(file.readText().contains("PRIVATE_CONTACT"))
            assertEquals("cannot-decrypt",c.getSharedPreferences("loop-vault",Context.MODE_PRIVATE).getString("database",null))
        } finally { life.pause().stop().destroy() }
    }
    @Test fun regularErrorDialogHasAnExplicitCopyButton() {
        val life=Robolectric.buildActivity(SettingsActivity::class.java,Intent(c,SettingsActivity::class.java).putExtra("page","diagnostics")).setup()
        try {
            ReflectionHelpers.callInstanceMethod<Unit>(life.get(),"message",ClassParameter.from(String::class.java,"操作失败：UserNotAuthenticatedException"))
            val dialog=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
            assertEquals("复制",dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).text)
            dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("操作失败：UserNotAuthenticatedException",life.get().getSystemService(ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString())
        } finally { life.pause().stop().destroy() }
    }
    @Test fun logFailureCannotReplaceBusinessFailure() {
        val bad=File(c.cacheDir,"diagnostic-not-directory").apply { writeText("file") }
        ReflectionHelpers.setStaticField(DiagnosticLog::class.java,"store",DiagnosticFiles(bad,"main"))
        val original=IllegalStateException("original")
        DiagnosticLog.failure(DiagnosticLog.Area.DATABASE,original);DiagnosticLog.flush()
        assertEquals("original",original.message)
        assertEquals("file",bad.readText())
        assertTrue(DiagnosticLog.report(c).contains("writer_error=true"))
    }
}
