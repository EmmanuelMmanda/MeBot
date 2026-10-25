package mebot.nguli.com.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.net.URI

@Serializable
data class SoftwareRelease(val version:String,val versionCode:Int,val applicationId:String,val minimumSdk:Int,
    val apkUrl:String,val sha256:String,val sizeBytes:Long,val certificateSha256:String,val notes:String="",val releaseUrl:String="")

object SoftwareUpdates {
    const val OWNER="EmmanuelMmanda"
    const val REPO="MeBot"
    const val API="https://api.github.com/repos/$OWNER/$REPO/releases?per_page=10"
    const val MAX_APK_BYTES=100L*1024*1024
    fun releaseAsset(url:String,tag:String):Boolean=runCatching{
        val u=URI(url)
        u.scheme=="https" && u.host=="github.com" && u.port==-1 && u.userInfo==null && u.query==null && u.fragment==null &&
            u.rawPath.substringBeforeLast('/') == "/$OWNER/$REPO/releases/download/$tag" &&
            u.rawPath.substringAfterLast('/').matches(Regex("[A-Za-z0-9._-]+"))
    }.getOrDefault(false)
    fun parse(raw:String,tag:String,packageName:String,currentCode:Int,certificate:String,notes:String=""):SoftwareRelease? {
        require(raw.length<=1024*1024)
        require(tag.matches(Regex("v[0-9]+\\.[0-9]+\\.[0-9]+")))
        val o=Json.parseToJsonElement(raw).jsonObject
        // Legacy releases may have no package metadata. They are not updates to this app.
        val pkg=o["applicationId"]?.jsonPrimitive?.contentOrNull ?: return null
        if(pkg!=packageName)return null
        val version=o.getValue("version").jsonPrimitive.content
        val code=o.getValue("versionCode").jsonPrimitive.int
        if(code<=currentCode)return null
        require(version==tag.removePrefix("v"))
        val cert=(o["signerCertificateSha256"]?:o["certificateSha256"])?.jsonPrimitive?.content.orEmpty().lowercase()
        require(cert.matches(Regex("[a-f0-9]{64}")) && cert==certificate.lowercase())
        val min=o.getValue("minimumSdk").jsonPrimitive.int
        require(min in 26..100)
        val name="mebot-$version.apk"
        val asset=o.getValue("assets").jsonArray.map{it.jsonObject}.single{it["name"]?.jsonPrimitive?.content==name}
        val digest=asset.getValue("sha256").jsonPrimitive.content.lowercase()
        val size=asset.getValue("bytes").jsonPrimitive.long
        val url="https://github.com/$OWNER/$REPO/releases/download/$tag/$name"
        require(digest.matches(Regex("[a-f0-9]{64}")) && size in 1..MAX_APK_BYTES && releaseAsset(url,tag))
        return SoftwareRelease(version,code,pkg,min,url,digest,size,cert,notes.take(12000),"https://github.com/$OWNER/$REPO/releases/tag/$tag")
    }
    fun sameArtifact(a:SoftwareRelease,b:SoftwareRelease)=a.versionCode==b.versionCode && a.applicationId==b.applicationId && a.sha256==b.sha256 && a.certificateSha256==b.certificateSha256 && a.sizeBytes==b.sizeBytes
}
