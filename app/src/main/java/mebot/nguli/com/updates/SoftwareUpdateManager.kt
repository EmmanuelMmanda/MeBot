package mebot.nguli.com.updates

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import mebot.nguli.com.BuildConfig
import mebot.nguli.com.core.*
import mebot.nguli.com.feed.*
import java.io.File
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URI
import java.security.MessageDigest

/** Update IO and state have no relationship with AI queues, tokens or context. */
data class SoftwareUpdateState(val checking:Boolean=false,val downloading:Boolean=false,val bytes:Long=0,
    val release:SoftwareRelease?=null,val lastChecked:Long=0,val error:String?=null,val ready:Boolean=false,
    val automaticChecks:Boolean=true,val downloadOnWifi:Boolean=false,val awaitingInstall:Boolean=false)

class SoftwareUpdateManager(private val context:Context,private val http:FeedHttp=SecureFeedHttp()) {
    private val prefs=context.getSharedPreferences("mebot.software-updates.v1",Context.MODE_PRIVATE)
    private val _state=MutableStateFlow(SoftwareUpdateState(lastChecked=prefs.getLong("checked",0),automaticChecks=prefs.getBoolean("automatic",true),downloadOnWifi=prefs.getBoolean("wifi",false)))
    val state=_state.asStateFlow()
    private val lock=Mutex()
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private var task:Job?=null
    @Volatile private var transfer: Job? = null
    @Volatile private var installProtectedUntil = 0L
    private val dir get()=File(context.cacheDir,"software-updates").apply{mkdirs()}
    private val target get()=File(dir,"verified-update.apk")
    fun configure(automatic:Boolean,wifi:Boolean){prefs.edit().putBoolean("automatic",automatic).putBoolean("wifi",wifi).apply();_state.update{it.copy(automaticChecks=automatic,downloadOnWifi=wifi)};SoftwareUpdateWorker.schedule(context,automatic)}
    fun enter(){
        if(task?.isActive==true)return
        task=scope.launch {
            restore()
            if(System.currentTimeMillis()-_state.value.lastChecked>=6*3600_000L || _state.value.lastChecked==0L)check()
        }
    }
    fun checkNow(){if(task?.isActive==true)return;task=scope.launch{check()}}
    fun downloadNow(){if(task?.isActive==true)return;task=scope.launch{download()}}
    fun cancel(){transfer?.cancel();task?.cancel();_state.update{it.copy(downloading=false,checking=false)}}
    fun startup(){SoftwareUpdateWorker.schedule(context,_state.value.automaticChecks);scope.launch { restore();if(_state.value.automaticChecks && System.currentTimeMillis()-_state.value.lastChecked>=24*3600_000L)check() }}
    private suspend fun restore()=lock.withLock { withContext(Dispatchers.IO){
        val release=prefs.getString("release",null)?.let{runCatching{FeedJson.decodeFromString<SoftwareRelease>(it)}.getOrNull()}
        if(release!=null && release.applicationId==context.packageName && release.versionCode>BuildConfig.VERSION_CODE && release.certificateSha256==installedCertificate()){
            val ready=target.isFile && runCatching{verify(target,release)}.isSuccess
            if(!ready)target.delete()
            _state.update{it.copy(release=release,ready=ready)}
        }else{prefs.edit().remove("release").apply();target.delete()}
        dir.listFiles()?.filter{it.name.endsWith(".part")}?.forEach{it.delete()}
    }
    }
    suspend fun check()=lock.withLock {
        if(_state.value.downloading || System.currentTimeMillis()<installProtectedUntil)return@withLock
        _state.update{it.copy(checking=true,error=null)}
        try {
            val listing=FeedJson.parseToJsonElement(http.request(SoftwareUpdates.API)).jsonArray
            val cert=installedCertificate()
            var found:SoftwareRelease?=null;var metadataFailure=false;var newerOsRequired=false
            for(entry in listing.take(10)){
                currentCoroutineContext().ensureActive()
                val o=entry.jsonObject
                if(o["draft"]?.jsonPrimitive?.booleanOrNull!=false || o["prerelease"]?.jsonPrimitive?.booleanOrNull!=false)continue
                val tag=o["tag_name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                if(!tag.matches(Regex("v[0-9]+\\.[0-9]+\\.[0-9]+")))continue
                val assets=(o["assets"] as? JsonArray).orEmpty().mapNotNull{it as? JsonObject}
                val metadata=assets.firstOrNull{it["name"]?.jsonPrimitive?.content=="release-metadata.json"} ?: continue
                val url=metadata["browser_download_url"]?.jsonPrimitive?.contentOrNull ?: continue
                if(!SoftwareUpdates.releaseAsset(url,tag))continue
                try {
                    val candidate=SoftwareUpdates.parse(http.request(url),tag,context.packageName,BuildConfig.VERSION_CODE,cert,o["body"]?.jsonPrimitive?.contentOrNull.orEmpty())
                    if(candidate!=null && candidate.minimumSdk>Build.VERSION.SDK_INT)newerOsRequired=true
                    if(candidate!=null && candidate.minimumSdk<=Build.VERSION.SDK_INT && (found==null||candidate.versionCode>found.versionCode))found=candidate
                }catch(e:CancellationException){throw e}catch(e:Exception){metadataFailure=true}
            }
            if(found==null && newerOsRequired)throw FeedFailure("update_newer_android")
            if(found==null && metadataFailure)throw FeedFailure("update_metadata")
            val now=System.currentTimeMillis();val previous=_state.value.release
            if(found==null || previous==null || !SoftwareUpdates.sameArtifact(previous,found))target.delete()
            val ready=found!=null && target.isFile && runCatching{verify(target,found)}.isSuccess
            prefs.edit().putLong("checked",now).apply{if(found==null)remove("release")else putString("release",FeedJson.encodeToString(found))}.apply()
            _state.update{it.copy(release=found,lastChecked=now,ready=ready,error=null)}
        }catch(e:CancellationException){throw e}
        catch(e:Exception){_state.update{it.copy(error=if(e is FeedFailure)e.code else "update_check_failed")}}
        finally{_state.update{it.copy(checking=false)}}
    }
    suspend fun download()=lock.withLock {
        val release=_state.value.release ?: return@withLock
        if(_state.value.ready)return@withLock
        _state.update{it.copy(downloading=true,bytes=0,error=null,ready=false)}
        val partial=File(dir,"update.part")
        transfer=currentCoroutineContext()[Job]
        try{
            fetchApk(release,partial){n->_state.update{it.copy(bytes=n)}}
            verify(partial,release)
            target.delete();if(!partial.renameTo(target))throw FeedFailure("update_download_failed")
            target.setReadOnly()
            _state.update{it.copy(ready=true)}
        }catch(e:CancellationException){throw e}
        catch(e:Exception){_state.update{it.copy(error=if(e is FeedFailure)e.code else "update_download_failed")}}
        finally{transfer=null;partial.delete();_state.update{it.copy(downloading=false)}}
    }
    private suspend fun fetchApk(release:SoftwareRelease,file:File,onProgress:(Long)->Unit)=withContext(Dispatchers.IO){
        var url=release.apkUrl;val deadline=System.nanoTime()+180_000_000_000L
        repeat(5){attempt->
            currentCoroutineContext().ensureActive();val uri=URI(url);val host=uri.host.orEmpty()
            if(uri.scheme!="https"||uri.userInfo!=null||uri.port !in setOf(-1,443)||!(host=="github.com"||host=="release-assets.githubusercontent.com"||host=="objects.githubusercontent.com"))throw FeedFailure("update_metadata")
            val addresses=InetAddress.getAllByName(host)
            if(addresses.isEmpty()||addresses.any{it.isSiteLocalAddress||it.isLinkLocalAddress||it.isLoopbackAddress||it.isAnyLocalAddress||it.isMulticastAddress||(it.address.size==16 && (it.address[0].toInt() and 0xfe)==0xfc)})throw FeedFailure("update_metadata")
            val c=uri.toURL().openConnection() as HttpURLConnection
            val stop=currentCoroutineContext()[Job]?.invokeOnCompletion{c.disconnect()}
            try{
                c.instanceFollowRedirects=false;c.connectTimeout=12000;c.readTimeout=20000;c.setRequestProperty("User-Agent","MeBot/0.8.2");c.setRequestProperty("Accept-Encoding","identity")
                if(c.responseCode in 300..399 && attempt<4){url=uri.resolve(c.getHeaderField("Location")?:throw FeedFailure("update_download_failed")).toString();return@repeat}
                if(c.responseCode!=200 || (c.contentLengthLong>0 && c.contentLengthLong!=release.sizeBytes))throw FeedFailure("update_download_failed")
                var total=0L;var last=0L
                c.inputStream.use{input->file.outputStream().use{output->val buffer=ByteArray(64*1024);while(true){
                    currentCoroutineContext().ensureActive();if(System.nanoTime()>deadline)throw FeedFailure("update_download_failed")
                    val n=input.read(buffer);if(n<0)break;total+=n
                    if(total>release.sizeBytes||total>SoftwareUpdates.MAX_APK_BYTES)throw FeedFailure("update_integrity")
                    output.write(buffer,0,n);if(total-last>256*1024){onProgress(total);last=total}
                }}}
                if(total!=release.sizeBytes)throw FeedFailure("update_integrity");onProgress(total);return@withContext
            }finally{stop?.dispose();c.disconnect()}
        }
        throw FeedFailure("update_download_failed")
    }
    @Suppress("DEPRECATION")
    fun installedCertificate():String {
        val flags=if(Build.VERSION.SDK_INT>=28)PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val info=context.packageManager.getPackageInfo(context.packageName,flags)
        val signatures=if(Build.VERSION.SDK_INT>=28)info.signingInfo?.apkContentsSigners else info.signatures
        require(signatures?.size==1)
        return hash(signatures!![0].toByteArray())
    }
    @Suppress("DEPRECATION")
    fun verify(file:File,release:SoftwareRelease){
        require(file.length()==release.sizeBytes && release.applicationId==context.packageName && release.versionCode>BuildConfig.VERSION_CODE)
        val digest=MessageDigest.getInstance("SHA-256");file.inputStream().use{input->val b=ByteArray(65536);while(true){val n=input.read(b);if(n<0)break;digest.update(b,0,n)}}
        if(hex(digest.digest())!=release.sha256)throw FeedFailure("update_integrity")
        val flags=if(Build.VERSION.SDK_INT>=28)PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val info=context.packageManager.getPackageArchiveInfo(file.absolutePath,flags)?:throw FeedFailure("update_integrity")
        val version=if(Build.VERSION.SDK_INT>=28)info.longVersionCode else info.versionCode.toLong()
        val signatures=if(Build.VERSION.SDK_INT>=28)info.signingInfo?.apkContentsSigners else info.signatures
        if(info.packageName!=context.packageName || version!=release.versionCode.toLong() || info.versionName!=release.version || signatures?.size!=1 || hash(signatures[0].toByteArray())!=installedCertificate() || release.certificateSha256!=installedCertificate() || (info.applicationInfo?.minSdkVersion?:999)>Build.VERSION.SDK_INT)throw FeedFailure("update_integrity")
    }
    /** Android always controls final approval. No silent installation or success claim. */
    suspend fun install()=lock.withLock {
        val release=_state.value.release ?: return@withLock
        try{withContext(Dispatchers.IO){verify(target,release)}
            withContext(Dispatchers.Main){
                if(!context.packageManager.canRequestPackageInstalls()){
                    context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    _state.update{it.copy(awaitingInstall=true)}
                }else{
                    installProtectedUntil=System.currentTimeMillis()+10*60_000L
                    val uri=FileProvider.getUriForFile(context,"${context.packageName}.updates",target)
                    context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri,"application/vnd.android.package-archive").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
                    _state.update{it.copy(awaitingInstall=true)}
                }
            }
        }catch(e:CancellationException){throw e}catch(e:Exception){_state.update{it.copy(error="update_install_failed")}}
    }
    private fun hash(b:ByteArray)=hex(MessageDigest.getInstance("SHA-256").digest(b))
    private fun hex(b:ByteArray)=b.joinToString(""){"%02x".format(it)}
}
