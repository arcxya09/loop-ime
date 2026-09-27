package app.loop.ime

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import net.zetetic.database.sqlcipher.SQLiteDatabase
import java.io.File

/** Run only on an isolated emulator. Every key and row below is a synthetic test fixture. */
class UpgradeInstrumentation : Instrumentation() {
    private var phase=""
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments);phase=arguments?.getString("phase").orEmpty();start() }
    override fun onStart() {
        try {
            val version=targetContext.packageManager.getPackageInfo(targetContext.packageName,0).longVersionCode
            when(phase) {
                "seed" -> {
                    check(version==10L) { "Seed requires the delivered alpha.10 APK" }
                    val file=File(targetContext.noBackupFilesDir,"loop.db")
                    check(!file.exists()) { "Seed requires an empty test installation; existing data is never cleared" }
                    check(targetContext.getSharedPreferences("loop-vault",0).all.isEmpty()) { "Seed never overwrites existing encrypted credentials" }
                    AiProfiles(targetContext).saveDeepSeek(TEXT_KEY)
                    CloudSpeechSettings(targetContext).save(SPEECH_KEY,"beijing")
                    CloudSpeechSettings(targetContext).save(SPEECH_SG_KEY,"singapore")
                    check(Prefs(targetContext).store.edit().putBoolean("cloud",false).putBoolean("speech_cloud",false)
                        .putBoolean("memory",true).putBoolean("chinese_t9",false).putString("keyboard_height","medium")
                        .putString("upgrade_probe","loop-upgrade-fixture").commit())
                    System.loadLibrary("sqlcipher")
                    val key=Vault(targetContext).databaseKey()
                    try {
                        SQLiteDatabase.openOrCreateDatabase(file,key,null,null).use { db ->
                            db.beginTransaction()
                            try {
                                db.execSQL("CREATE TABLE memories(id TEXT PRIMARY KEY,text TEXT NOT NULL,time INTEGER NOT NULL,source TEXT NOT NULL,status TEXT NOT NULL,cloud INTEGER NOT NULL,learned INTEGER NOT NULL DEFAULT 0)")
                                db.execSQL("CREATE TABLE terms(text TEXT PRIMARY KEY,pinyin TEXT NOT NULL,score INTEGER NOT NULL DEFAULT 1,cloud INTEGER NOT NULL DEFAULT 0,source TEXT NOT NULL,pinned INTEGER NOT NULL DEFAULT 0)")
                                db.execSQL("CREATE TABLE evidence(term TEXT NOT NULL REFERENCES terms(text) ON DELETE CASCADE,origin TEXT NOT NULL,kind TEXT NOT NULL,PRIMARY KEY(term,origin))")
                                db.execSQL("CREATE TABLE forgotten(text TEXT PRIMARY KEY)")
                                db.execSQL("CREATE TABLE clips(id TEXT PRIMARY KEY,text TEXT NOT NULL,time INTEGER NOT NULL,pinned INTEGER NOT NULL DEFAULT 0)")
                                db.execSQL("INSERT INTO memories VALUES ('upgrade-memory','联系王小明安排会议',100,'voice','issued',1,1)")
                                db.execSQL("INSERT INTO terms VALUES ('王小明','wangxiaoming',999,1,'memory',0)")
                                db.execSQL("INSERT INTO evidence VALUES ('王小明','upgrade-memory','memory'),('王小明','contact:upgrade','contacts'),('孤立来源','orphan','memory')")
                                db.execSQL("INSERT INTO clips VALUES ('upgrade-clip','覆盖安装保留的剪贴板',100,1)")
                                db.execSQL("INSERT INTO forgotten VALUES ('已遗忘词')")
                                db.version=1
                                db.setTransactionSuccessful()
                            } finally { db.endTransaction() }
                        }
                    } finally { key.fill(0) }
                    credentials()
                }
                "readback" -> {
                    check(version>=11L) { "Readback requires a fixed APK" }
                    credentials()
                    val prefs=Prefs(targetContext)
                    check(prefs.text("upgrade_probe")=="loop-upgrade-fixture")
                    check(prefs.memory && !prefs.flag("chinese_t9",true) && prefs.keyboardHeight==KeyboardHeight.MEDIUM)
                    val store=PersonalStore.get(targetContext)
                    val memory=store.memories().single { it.id=="upgrade-memory" }
                    check(memory.text=="联系王小明安排会议" && !memory.cloud)
                    val term=store.terms("wang").single { it.text=="王小明" }
                    check(term.score==3 && !term.cloud)
                    check(store.terms("926494266464",nineKey=true).any { it.text=="王小明" })
                    check(store.terms(cloudOnly=true).isEmpty())
                    check(store.clips().any { it.id=="upgrade-clip" && it.pinned && it.text=="覆盖安装保留的剪贴板" })
                    val rows=mutableListOf<org.json.JSONObject>();store.exportRows { rows+=it }
                    check(rows.any { it.optString("table")=="forgotten" && it.optString("text")=="已遗忘词" })
                    check(rows.none { it.optString("table")=="evidence" && it.optString("origin")=="orphan" })
                }
                else -> error("Expected phase seed or readback")
            }
            finish(Activity.RESULT_OK,Bundle().apply { putString("stream","PASS upgrade $phase on versionCode=$version\n");putString("result","PASS") })
        } catch(t: Throwable) {
            finish(Activity.RESULT_CANCELED,Bundle().apply { putString("stream","FAIL upgrade $phase: ${t.javaClass.name}\n${t.stackTraceToString()}");putString("result","FAIL") })
        }
    }
    private fun credentials() {
        check(AiProfiles(targetContext).current().key==TEXT_KEY)
        check(CloudSpeechSettings(targetContext).profile("beijing")?.key==SPEECH_KEY)
        check(CloudSpeechSettings(targetContext).profile("singapore")?.key==SPEECH_SG_KEY)
        val raw=targetContext.getSharedPreferences("loop-vault",0).all.values.filterIsInstance<String>()
        check(raw.none { it.contains(TEXT_KEY) || it.contains(SPEECH_KEY) || it.contains(SPEECH_SG_KEY) })
    }
    companion object {
        private const val TEXT_KEY="sk-loop-upgrade-synthetic-only"
        private const val SPEECH_KEY="sk-loop-bailian-cn-synthetic-only"
        private const val SPEECH_SG_KEY="sk-loop-bailian-sg-synthetic-only"
    }
}
