package app.loop.ime

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract

/** Logs stages and counts only. The resolver projection never requests numbers, email or photos. */
internal class ContactsImporter(private val c: Context,private val store: ()->PersonalStore={ PersonalStore.get(c) }) {
    fun run(): String {
        val trace=DiagnosticLog.begin(DiagnosticLog.Area.CONTACTS)
        var read=0L;var accepted=0L;var skipped=0L
        try {
            val granted=c.checkSelfPermission(Manifest.permission.READ_CONTACTS)==PackageManager.PERMISSION_GRANTED
            trace.mark(DiagnosticLog.Step.PERMISSION,"permission_granted" to if(granted)1L else 0L)
            if(!granted)throw SecurityException("请先允许 Loop 读取通讯录姓名")
            trace.mark(DiagnosticLog.Step.DATABASE_OPEN)
            val s=store()
            trace.mark(DiagnosticLog.Step.PROVIDER_QUERY)
            val columns=arrayOf(ContactsContract.Contacts._ID,ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)
            val cursor=c.contentResolver.query(ContactsContract.Contacts.CONTENT_URI,columns,null,null,null)
                ?: error("系统通讯录未返回可读结果，请检查通讯录权限后重试")
            cursor.use { rows ->
                val id=rows.getColumnIndexOrThrow(ContactsContract.Contacts._ID)
                val display=rows.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)
                s.transaction {
                    while(true) {
                        trace.at(DiagnosticLog.Step.CURSOR_READ)
                        if(!rows.moveToNext())break
                        read++
                        val name=rows.getString(display)
                        if(name==null || TextRules.cleanTerm(name)==null || name.none { it.isLetter() }) { skipped++;continue }
                        trace.at(DiagnosticLog.Step.TERM_WRITE)
                        if(accepted%100L==0L)trace.mark(DiagnosticLog.Step.TERM_WRITE,"rows_read" to read,"skipped" to skipped)
                        s.addTerm(name,source="contacts",origin="contact:"+rows.getString(id),cloud=false)
                        accepted++
                    }
                }
            }
            trace.success("rows_read" to read,"imported" to accepted,"skipped" to skipped)
            return if(read==0L)"系统通讯录返回 0 条记录。请确认手机上有联系人，并检查是否只授予了空通讯录访问权限。"
                else "已处理 $accepted 个有效姓名，跳过 $skipped 条。已遗忘的词条保持排除；多音字可在个人词库中修改拼音。"
        } catch(t: Throwable) { trace.failure(t);throw t }
    }
}
