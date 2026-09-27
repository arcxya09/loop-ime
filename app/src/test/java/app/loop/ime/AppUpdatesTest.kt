package app.loop.ime

import android.app.Application
import android.app.DownloadManager
import android.app.job.JobScheduler
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.Signature
import android.content.pm.SigningInfo
import androidx.core.content.FileProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[37],application=Application::class)
class AppUpdatesTest {
    private val c get()=RuntimeEnvironment.getApplication()
    private val prefs get()=c.getSharedPreferences("loop-updates",Context.MODE_PRIVATE)
    private fun info(code: Long,signature: String="010203")=PackageInfo().apply {
        packageName=c.packageName;setLongVersionCode(code);versionName="0.2.$code"
        applicationInfo=ApplicationInfo().apply { minSdkVersion=37 }
        signingInfo=ReflectionHelpers.callConstructor(SigningInfo::class.java).also { shadowOf(it).setSignatures(arrayOf(Signature(signature))) }
    }
    private fun release()=UpdateRelease("0.2.16",16,4,UpdateRelease.digest("good".toByteArray()),UpdateRelease.REPO+"/releases/download/v0.2.16/Loop-IME-0.2.16.apk","notes",false)
    @Before fun setup() {
        prefs.edit().clear().commit()
        val own=shadowOf(c.packageManager).getInternalMutablePackageInfo(c.packageName)
        own.setLongVersionCode(15);own.versionName="0.1.15-alpha.15";own.signingInfo=info(15).signingInfo
    }
    @Test fun scheduleIsPersistentAndCanBeDisabled() {
        AppUpdates.schedule(c)
        val scheduler=c.getSystemService(JobScheduler::class.java)
        val job=scheduler.getPendingJob(AppUpdates.PERIODIC_JOB)!!
        assertTrue(job.isPersisted);assertEquals(24*60*60*1000L,job.intervalMillis)
        prefs.edit().putBoolean("automatic",false).commit();AppUpdates.schedule(c)
        assertNull(scheduler.getPendingJob(AppUpdates.PERIODIC_JOB))
    }
    @Test fun stableChannelIgnoresOldPreviewPreferenceAndDiscardsCachedPreview() {
        prefs.edit().putBoolean("previews",true).putString("release",release().copy(prerelease=true).json()).putBoolean("ready",true).commit()
        assertFalse(AppUpdates.previews(c))
        val file=File(c.filesDir,"updates/verified.apk").apply { parentFile!!.mkdirs();writeText("good") }
        AppUpdates.reconcile(c)
        assertNull(AppUpdates.available(c));assertFalse(file.exists());assertFalse(AppUpdates.ready(c))
        AppUpdates.enqueue(c,release().copy(prerelease=true),false)
        assertEquals(-1L,prefs.getLong("download_id",-1))
    }
    @Test fun automaticDownloadUsesWifiAndDoesNotDuplicateTask() {
        val r=release();prefs.edit().putString("release",r.json()).commit()
        AppUpdates.enqueue(c,r,false);val id=prefs.getLong("download_id",-1)
        val request=shadowOf(c.getSystemService(DownloadManager::class.java)).getRequest(id)
        assertEquals(DownloadManager.Request.NETWORK_WIFI,shadowOf(request).allowedNetworkTypes)
        assertFalse(shadowOf(request).allowedOverMetered);assertFalse(shadowOf(request).allowedOverRoaming)
        AppUpdates.enqueue(c,r,false);assertEquals(id,prefs.getLong("download_id",-1))
    }
    @Test fun explicitCellularDownloadReplacesWifiTask() {
        val r=release();prefs.edit().putString("release",r.json()).commit()
        AppUpdates.enqueue(c,r,false);val old=prefs.getLong("download_id",-1)
        AppUpdates.enqueue(c,r,true);val id=prefs.getLong("download_id",-1)
        assertNotEquals(old,id)
        assertTrue(shadowOf(shadowOf(c.getSystemService(DownloadManager::class.java)).getRequest(id)).allowedOverMetered)
    }
    @Test fun enablingAutomaticDownloadStartsAnAlreadyDiscoveredUpdate() {
        prefs.edit().putString("release",release().json()).putBoolean("download",false).commit()
        AppUpdates.configure(c,"download",true)
        val deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(10)
        while(prefs.getLong("download_id",-1)<0 && System.nanoTime()<deadline)Thread.sleep(10)
        assertTrue(prefs.getLong("download_id",-1)>=0)
    }
    @Test fun failedDownloadCanBeRetriedAndStateSurvivesManagerCalls() {
        val r=release();prefs.edit().putString("release",r.json()).commit();AppUpdates.enqueue(c,r,false)
        val id=prefs.getLong("download_id",-1)
        shadowOf(shadowOf(c.getSystemService(DownloadManager::class.java)).getRequest(id)).setStatus(DownloadManager.STATUS_FAILED)
        AppUpdates.reconcile(c);assertEquals(-1L,prefs.getLong("download_id",-1));assertEquals(r,AppUpdates.available(c))
        AppUpdates.enqueue(c,r,false);assertTrue(prefs.getLong("download_id",-1)>=0)
    }
    @Test fun unrelatedCompletionBroadcastDoesNotScheduleWork() {
        UpdateDownloadReceiver().onReceive(c,Intent(DownloadManager.ACTION_DOWNLOAD_COMPLETE).putExtra(DownloadManager.EXTRA_DOWNLOAD_ID,999L))
        assertNull(c.getSystemService(JobScheduler::class.java).getPendingJob(AppUpdates.FINISH_JOB))
        prefs.edit().putLong("download_id",999).commit()
        UpdateDownloadReceiver().onReceive(c,Intent(DownloadManager.ACTION_DOWNLOAD_COMPLETE).putExtra(DownloadManager.EXTRA_DOWNLOAD_ID,999L))
        assertNotNull(c.getSystemService(JobScheduler::class.java).getPendingJob(AppUpdates.FINISH_JOB))
    }
    @Test fun archiveMustMatchInstalledSignerPackageAndExactNewVersion() {
        val file=File(c.filesDir,"test.apk").apply { writeText("good") };val r=release()
        fun verify(archive: PackageInfo) { shadowOf(c.packageManager).setPackageArchiveInfo(file.path,archive);AppUpdates.verifyApk(c,file,r) }
        verify(info(16))
        assertThrows(IllegalArgumentException::class.java) { verify(info(16,"040506")) }
        assertThrows(IllegalArgumentException::class.java) { verify(info(16).apply { packageName="other.app" }) }
        assertThrows(IllegalArgumentException::class.java) { verify(info(15)) }
        assertThrows(IllegalArgumentException::class.java) { verify(info(16).apply { applicationInfo!!.minSdkVersion=38 }) }
    }
    @Test fun successfulUpgradeRemovesObsoletePackageAndNotificationState() {
        prefs.edit().putString("release",release().json()).putBoolean("ready",true).commit()
        val file=File(c.filesDir,"updates/verified.apk").apply { parentFile!!.mkdirs();writeText("good") }
        shadowOf(c.packageManager).installPackage(info(16))
        AppUpdates.reconcile(c);assertFalse(file.exists());assertNull(AppUpdates.available(c));assertFalse(AppUpdates.ready(c))
    }
    @Test fun fileProviderCannotShareDatabaseOrOtherPrivateFiles() {
        val provider=c.packageManager.resolveContentProvider("${c.packageName}.updates",android.content.pm.PackageManager.GET_META_DATA)!!
        assertFalse(provider.exported);assertTrue(provider.grantUriPermissions)
        val xml=provider.loadXmlMetaData(c.packageManager,"android.support.FILE_PROVIDER_PATHS")!!
        val roots=mutableListOf<Pair<String,String>>()
        xml.use { while(it.next()!=org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            if(it.eventType==org.xmlpull.v1.XmlPullParser.START_TAG && it.name!="paths")roots.add(it.name to it.getAttributeValue(null,"path"))
        } }
        assertEquals(listOf("files-path" to "updates/"),roots)
        val file=File(c.filesDir,"private.db").apply { writeText("private") }
        assertThrows(IllegalArgumentException::class.java) { FileProvider.getUriForFile(c,"${c.packageName}.updates",file) }
        val apk=File(c.filesDir,"updates/verified.apk").apply { parentFile!!.mkdirs();writeText("good") }
        // AndroidX uses Android's '/' in its canonical path guard. Exercise URI creation on Linux CI;
        // Windows still validates the real merged provider metadata and rejects unrelated private files.
        if(File.separatorChar=='/')assertEquals("content",FileProvider.getUriForFile(c,"${c.packageName}.updates",apk).scheme)
    }
}
