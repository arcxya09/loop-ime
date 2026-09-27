package app.loop.ime

import android.app.Application
import android.app.Notification
import android.os.Looper
import android.os.Process
import android.service.notification.StatusBarNotification
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[37],application=Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class OtpSourcesTest {
    @Test fun notificationsRequireOptInAndAllowedPackageAndRespectPrivacyAndRedaction() {
        val life=Robolectric.buildService(OtpNotificationListener::class.java).create();val service=life.get();val p=Prefs(service)
        try {
            OtpInbox.clear();p.set("otp_packages","example.sms")
            fun post(text: String,pkg: String="example.sms") {
                val n=Notification.Builder(service,"test").setContentText(text).build()
                service.onNotificationPosted(StatusBarNotification(pkg,pkg,1,null,1000,0,0,n,Process.myUserHandle(),System.currentTimeMillis()))
                shadowOf(Looper.getMainLooper()).idle()
            }
            post("验证码 123456");assertTrue(OtpInbox.buffer.values().isEmpty())
            p.set("otp_notifications",true);post("验证码 123456","other.app");assertTrue(OtpInbox.buffer.values().isEmpty())
            post("敏感通知内容已隐藏");assertTrue(OtpInbox.buffer.values().isEmpty())
            p.set("private",true);post("验证码 123456");assertTrue(OtpInbox.buffer.values().isEmpty())
            p.set("private",false);post("验证码 001234");assertEquals("001234",OtpInbox.buffer.values().single().text)
            service.onListenerDisconnected();shadowOf(Looper.getMainLooper()).idle();assertTrue(OtpInbox.buffer.values().isEmpty())
        } finally { OtpInbox.clear();life.destroy() }
    }
}
