package mebot.nguli.com.feed.auth

import mebot.nguli.com.feed.*
import mebot.nguli.com.feed.FeedJson
import kotlinx.serialization.json.*
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** Documented OpenRouter headless PKCE flow. The verifier never leaves memory or enters a URL. */
class OpenRouterSignIn(private val http:FeedHttp=SecureFeedHttp(),private val now:()->Long=System::currentTimeMillis) {
    private var verifier:String?=null
    private var started=0L
    @Synchronized fun begin():String {
        val bytes=ByteArray(48).also{SecureRandom().nextBytes(it)}
        val value=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        verifier=value;started=now()
        val challenge=Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.US_ASCII)))
        return "https://openrouter.ai/auth?code_challenge=$challenge&code_challenge_method=S256"
    }
    @Synchronized fun cancel(){verifier=null;started=0}
    suspend fun finish(code:String):String {
        val v=synchronized(this){
            val value=verifier ?: throw FeedFailure("unauthorized")
            if(now()-started !in 0..600_000){cancel();throw FeedFailure("unauthorized")}
            if(code.length !in 8..2048 || code.any { it.isWhitespace() || it.isISOControl() })throw FeedFailure("invalid_input")
            verifier=null;value
        }
        val body=buildJsonObject{put("code",code);put("code_verifier",v);put("code_challenge_method","S256")}
        val response=http.request("https://openrouter.ai/api/v1/auth/keys","POST",body.toString())
        return runCatching{FeedJson.parseToJsonElement(response).jsonObject["key"]?.jsonPrimitive?.contentOrNull}
            .getOrNull()?.takeIf{it.length in 8..4096 && it.none(Char::isISOControl)} ?: throw FeedFailure("invalid_response")
    }
}
