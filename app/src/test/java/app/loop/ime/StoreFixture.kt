package app.loop.ime

import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import org.robolectric.RuntimeEnvironment
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import java.io.File
import java.util.UUID

/** Real SQLite engine + the production PersonalStore, without copying its SQL or methods. */
internal class StoreFixture(beforeOpen: (SQLiteDatabase)->Unit = {}): AutoCloseable {
    val context=RuntimeEnvironment.getApplication()
    private val file=File(context.noBackupFilesDir,"store-test-${UUID.randomUUID()}.db")
    val sqlite=SQLiteDatabase.openOrCreateDatabase(file,null).also(beforeOpen)
    val database=ReflectionHelpers.callConstructor(Class.forName("androidx.sqlite.db.framework.FrameworkSQLiteDatabase"),
        ClassParameter.from(SQLiteDatabase::class.java,sqlite)) as SupportSQLiteDatabase
    val store=PersonalStore(database)
    fun install() { ReflectionHelpers.setStaticField(PersonalStore::class.java,"instance",store) }
    override fun close() { ReflectionHelpers.setStaticField(PersonalStore::class.java,"instance",null);store.close();SQLiteDatabase.deleteDatabase(file) }
}
