package app.loop.ime

import android.content.Context
import android.provider.Settings
import android.view.WindowManager

/** Journal before mutating; restore only values still matching our own writes. */
class RotationLock(private val c: Context) {
    private val journal=c.createDeviceProtectedStorageContext().getSharedPreferences("rotation-recovery",Context.MODE_PRIVATE)
    private val resolver=c.contentResolver
    fun acquire(): Boolean {
        if(!Settings.System.canWrite(c))return false
        recover()
        val rotation=c.getSystemService(WindowManager::class.java).defaultDisplay.rotation
        val auto=Settings.System.getInt(resolver,Settings.System.ACCELEROMETER_ROTATION,1)
        val old=Settings.System.getInt(resolver,Settings.System.USER_ROTATION,0)
        if(!journal.edit().putBoolean("active",true).putInt("auto",auto).putInt("rotation",old).putInt("written",rotation).commit())return false
        return try {
            val a=Settings.System.putInt(resolver,Settings.System.USER_ROTATION,rotation)
            val b=Settings.System.putInt(resolver,Settings.System.ACCELEROMETER_ROTATION,0)
            if(!a||!b) { recover();false } else true
        } catch(e: Exception) { recover();false }
    }
    fun recover() {
        if(!journal.getBoolean("active",false) || !Settings.System.canWrite(c))return
        try {
            if(Settings.System.getInt(resolver,Settings.System.USER_ROTATION,0)==journal.getInt("written",0))
                Settings.System.putInt(resolver,Settings.System.USER_ROTATION,journal.getInt("rotation",0))
            if(Settings.System.getInt(resolver,Settings.System.ACCELEROMETER_ROTATION,1)==0)
                Settings.System.putInt(resolver,Settings.System.ACCELEROMETER_ROTATION,journal.getInt("auto",1))
            journal.edit().clear().commit()
        } catch(_: Exception) { /* retain journal so next launch can restore */ }
    }
}
