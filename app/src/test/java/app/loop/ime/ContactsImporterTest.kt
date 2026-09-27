package app.loop.ime

import android.Manifest
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.ContactsContract
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[37],application=Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ContactsImporterTest {
    private val c get()=RuntimeEnvironment.getApplication()
    class Provider : ContentProvider() {
        var reply: Cursor?=null
        var failure: RuntimeException?=null
        var projection: Array<out String>?=null
        override fun onCreate()=true
        override fun query(uri: Uri,p: Array<out String>?,selection: String?,args: Array<out String>?,sort: String?): Cursor? { projection=p;failure?.let { throw it };return reply }
        override fun getType(uri: Uri)="vnd.android.cursor.dir/contact"
        override fun insert(uri: Uri,values: ContentValues?): Uri?=null
        override fun delete(uri: Uri,selection: String?,args: Array<out String>?)=0
        override fun update(uri: Uri,values: ContentValues?,selection: String?,args: Array<out String>?)=0
    }
    @Before fun setup() {
        DiagnosticLog.flush();ReflectionHelpers.setStaticField(DiagnosticLog::class.java,"store",null)
        DiagnosticLog.attach(c);DiagnosticLog.clear(c)
        shadowOf(c).grantPermissions(Manifest.permission.READ_CONTACTS)
    }
    @Test fun realImporterReadsOnlyNamesAndPreservesLocalTermsWithoutLoggingNames()=StoreFixture().use { f ->
        val provider=Robolectric.buildContentProvider(Provider::class.java).create("com.android.contacts").get()
        val cursor=MatrixCursor(arrayOf(ContactsContract.Contacts._ID,ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)).apply {
            addRow(arrayOf("1","王小明"));addRow(arrayOf("2",null));addRow(arrayOf("3",""));addRow(arrayOf("4","李小红"))
        }
        provider.reply=cursor
        val result=ContactsImporter(c) { f.store }.run()
        assertTrue(result.contains("2 个有效姓名"));assertTrue(cursor.isClosed)
        assertEquals(setOf("王小明","李小红"),f.store.terms().map { it.text }.toSet())
        assertTrue(f.store.terms(cloudOnly=true).isEmpty())
        assertArrayEquals(arrayOf(ContactsContract.Contacts._ID,ContactsContract.Contacts.DISPLAY_NAME_PRIMARY),provider.projection)
        val log=DiagnosticLog.report(c,true)
        assertTrue(log.contains("CONTACTS"));assertTrue(log.contains("\"imported\":2"))
        assertFalse(log.contains("王小明"));assertFalse(log.contains("李小红"))
    }
    @Test fun nullProviderResultIsDiagnosedAndNeverReportedAsSuccessfulImport()=StoreFixture().use { f ->
        Robolectric.buildContentProvider(Provider::class.java).create("com.android.contacts").get().reply=null
        val error=runCatching { ContactsImporter(c) { f.store }.run() }.exceptionOrNull()
        assertNotNull(error);assertEquals(0L,f.store.count("terms"))
        assertTrue(DiagnosticLog.report(c,true).contains("\"failed_at\":\"PROVIDER_QUERY\""))
    }
    @Test fun permissionAndDatabaseFailuresAreDistinguishableWithoutLeakingMessages() {
        shadowOf(c).denyPermissions(Manifest.permission.READ_CONTACTS)
        var opened=false
        assertTrue(runCatching { ContactsImporter(c) { opened=true;error("unused") }.run() }.exceptionOrNull() is SecurityException)
        assertFalse(opened);assertTrue(DiagnosticLog.report(c,true).contains("PERMISSION_DENIED"))
        shadowOf(c).grantPermissions(Manifest.permission.READ_CONTACTS)
        assertTrue(runCatching { ContactsImporter(c) { throw IllegalStateException("PRIVATE_DATABASE_KEY",android.security.keystore.UserNotAuthenticatedException()) }.run() }.isFailure)
        val log=DiagnosticLog.report(c,true)
        assertTrue(log.contains("\"failed_at\":\"DATABASE_OPEN\""));assertTrue(log.contains("KEYSTORE_AUTH_REQUIRED"));assertFalse(log.contains("PRIVATE_DATABASE_KEY"))
    }
}
