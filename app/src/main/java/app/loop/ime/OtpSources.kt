package app.loop.ime

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.app.KeyguardManager
import android.provider.Telephony
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.app.Notification
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArraySet

object OtpInbox {
    val buffer=SuggestionBuffer()
    val listeners=CopyOnWriteArraySet<()->Unit>()
    private val expiry=object: Runnable { override fun run() {
        buffer.values();listeners.forEach { it() }
        if(buffer.values().isNotEmpty())LoopApp.main.postDelayed(this,15000)
    } }
    fun allowed(c: Context)=LoopApp.unlocked(c) && !c.getSystemService(KeyguardManager::class.java).isDeviceLocked && !Prefs(c).privateMode
    fun event(source: String,text: String,time: Long)=MessageDigest.getInstance("SHA-256").digest("$source\u0000$time\u0000$text".toByteArray()).joinToString("") { "%02x".format(it) }
    fun offer(c: Context,text: String,source: String,time: Long) {
        if(!allowed(c))return
        LoopApp.main.post { if(allowed(c)) { buffer.offer(event(source,text,time),text,source,time,true);listeners.forEach { it() };LoopApp.main.removeCallbacks(expiry);LoopApp.main.postDelayed(expiry,15000) } }
    }
    fun clear() { LoopApp.main.removeCallbacks(expiry);buffer.clear();listeners.forEach { it() } }
    fun recentSms(c: Context) {
        if(!allowed(c) || !Prefs(c).flag("otp_sms") || c.checkSelfPermission(Manifest.permission.READ_SMS)!=PackageManager.PERMISSION_GRANTED)return
        LoopApp.background(c,{
            // A bounded, recent inbox query only; an unavailable/redacted provider is a supported state.
            try {
                c.contentResolver.query(Telephony.Sms.Inbox.CONTENT_URI,arrayOf("body","date"),"date>=?",arrayOf((System.currentTimeMillis()-SuggestionBuffer.TTL).toString()),"date DESC")?.use { cursor ->
                    var n=0;val rows=mutableListOf<Triple<String,String,Long>>()
                    while(n++<20 && cursor.moveToNext())rows+=Triple(cursor.getString(0).orEmpty(),"短信",cursor.getLong(1))
                    rows.asReversed().forEach { (text,source,time) -> if(Prefs(c).flag("otp_sms"))offer(c,text,source,time) }
                }
            } catch(_: SecurityException) { } catch(_: IllegalArgumentException) { }
        })
    }
}

class OtpSmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context,intent: Intent) {
        if(intent.action!=Telephony.Sms.Intents.SMS_RECEIVED_ACTION || !LoopApp.unlocked(context) || !Prefs(context).flag("otp_sms"))return
        val messages=Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if(messages.isEmpty())return
        OtpInbox.offer(context,messages.joinToString("") { it.messageBody.orEmpty() },"短信",messages.maxOf { it.timestampMillis })
    }
}

class OtpNotificationListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if(!OtpInbox.allowed(this))return
        val prefs=Prefs(this)
        if(!prefs.flag("otp_notifications"))return
        val allowed=prefs.text("otp_packages",Telephony.Sms.getDefaultSmsPackage(this).orEmpty()).split('\n').filter { it.isNotBlank() }
        if(sbn.packageName !in allowed || sbn.isOngoing || sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY!=0)return
        val extras=sbn.notification.extras
        val text=(extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        // System-redacted notifications contain no usable code; no workaround or broad app scraping.
        OtpInbox.offer(this,text,"通知：${sbn.packageName}",sbn.postTime)
    }
    override fun onListenerDisconnected() { LoopApp.main.post { OtpInbox.clear() } }
}
