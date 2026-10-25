package mebot.nguli.com.updates

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.PackageInfo
import android.content.pm.Signature
import android.content.pm.SigningInfo
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import mebot.nguli.com.BuildConfig
import mebot.nguli.com.core.*
import mebot.nguli.com.feed.*
import java.io.File
import java.security.MessageDigest
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=FeedTestApplication::class)
class SoftwareUpdateManagerTest {
    private lateinit var context:Context
    private val signature=Signature(byteArrayOf(1,2,3,4,5))
    private val certificate get()=hash(signature.toByteArray())
    private val tag="v0.8.3"
    private val url="https://github.com/EmmanuelMmanda/MeBot/releases/download/$tag/release-metadata.json"
    private val payload="test APK bytes; PackageManager parser is independently controlled".toByteArray()
    @Before fun reset(){
        context=ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("mebot.software-updates.v1",0).edit().clear().commit()
        File(context.cacheDir,"software-updates").deleteRecursively()
        val info=shadowOf(context.packageManager).getInternalMutablePackageInfo(context.packageName)
        info.signingInfo=SigningInfo().also{shadowOf(it).setSignatures(arrayOf(signature))}
    }
    private fun metadata(pkg:String=context.packageName,code:Int=BuildConfig.VERSION_CODE+1,cert:String=certificate,minSdk:Int=26)=
        """{"version":"0.8.3","versionCode":$code,"applicationId":"$pkg","minimumSdk":$minSdk,"signerCertificateSha256":"$cert","assets":[{"name":"mebot-0.8.3.apk","bytes":${payload.size},"sha256":"${hash(payload)}"}]}"""
    private fun wire(text:String,fail:Boolean=false)=object:FeedHttp{
        override suspend fun request(url:String,method:String,body:String?,bearer:String?):String {
            assertNull(bearer);assertEquals("GET",method)
            if(fail)throw FeedFailure("offline")
            return if(url==SoftwareUpdates.API)"""[{"tag_name":"$tag","draft":false,"prerelease":false,"body":"Public release notes","assets":[{"name":"release-metadata.json","browser_download_url":"${this@SoftwareUpdateManagerTest.url}"}]}]""" else {assertEquals(this@SoftwareUpdateManagerTest.url,url);text}
        }
    }
    @Test fun checkSelectsMatchingNewVersionAndPersistsWithoutAnyAiCredentials()=runBlocking {
        val manager=SoftwareUpdateManager(context,wire(metadata()));manager.check()
        assertEquals("0.8.3",manager.state.value.release?.version)
        assertEquals(context.packageName,manager.state.value.release?.applicationId)
        assertNull(manager.state.value.error);assertFalse(manager.state.value.checking);assertFalse(manager.state.value.ready)
        assertTrue(context.getSharedPreferences("mebot.software-updates.v1",0).getString("release","")!!.contains("0.8.3"))
    }
    @Test fun mismatchAndLowerVersionsNeverBecomeAnInstallOffer()=runBlocking {
        val other=SoftwareUpdateManager(context,wire(metadata(pkg="other.package")));other.check();assertNull(other.state.value.release)
        val same=SoftwareUpdateManager(context,wire(metadata(code=BuildConfig.VERSION_CODE)));same.check();assertNull(same.state.value.release)
        val wrong=SoftwareUpdateManager(context,wire(metadata(cert="0".repeat(64))));wrong.check();assertEquals("update_metadata",wrong.state.value.error)
    }
    @Test fun serverFailureIsNotReportedAsUpToDateAndKeepsPreviousCandidate()=runBlocking {
        var failure=false
        val good=wire(metadata())
        val manager=SoftwareUpdateManager(context,object:FeedHttp{override suspend fun request(url:String,method:String,body:String?,bearer:String?):String {if(failure)throw FeedFailure("offline");return good.request(url,method,body,bearer)}})
        manager.check();val prior=manager.state.value.release;val checked=manager.state.value.lastChecked
        failure=true;manager.check()
        assertEquals(prior,manager.state.value.release);assertEquals(checked,manager.state.value.lastChecked)
        assertNotNull(manager.state.value.error);assertFalse(manager.state.value.checking)
    }
    @Test fun unsupportedNewAndroidVersionIsExplainedInsteadOfPretendingCurrent()=runBlocking {
        val manager=SoftwareUpdateManager(context,wire(metadata(minSdk=99)));manager.check()
        assertEquals("update_newer_android",manager.state.value.error);assertNull(manager.state.value.release)
    }
    @Test fun verificationRejectsTamperingAndPackageIdentityBeforeInstallation()=runBlocking {
        val manager=SoftwareUpdateManager(context,wire(metadata()));manager.check();val release=manager.state.value.release!!
        val file=File(context.cacheDir,"candidate.apk").apply{writeBytes(payload)}
        val info=PackageInfo().apply{
            packageName=context.packageName;versionName="0.8.3";setLongVersionCode(release.versionCode.toLong())
            applicationInfo=android.content.pm.ApplicationInfo().apply{minSdkVersion=26}
            signingInfo=SigningInfo().also{shadowOf(it).setSignatures(arrayOf(signature))}
        }
        shadowOf(context.packageManager).setPackageArchiveInfo(file.absolutePath,info)
        manager.verify(file,release)
        file.writeBytes("changed bytes".toByteArray())
        assertTrue(runCatching{manager.verify(file,release)}.isFailure)
        file.writeBytes(payload);info.packageName="attacker.app"
        shadowOf(context.packageManager).setPackageArchiveInfo(file.absolutePath,info)
        assertTrue(runCatching{manager.verify(file,release)}.isFailure)
        info.packageName=context.packageName;info.signingInfo=SigningInfo().also{shadowOf(it).setSignatures(arrayOf(Signature(byteArrayOf(9))))}
        shadowOf(context.packageManager).setPackageArchiveInfo(file.absolutePath,info)
        assertTrue(runCatching{manager.verify(file,release)}.isFailure)
    }
    private fun hash(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
}
