package mebot.nguli.com.feed.auth

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import java.math.BigInteger
import java.net.URI
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.RSAPublicKeySpec
import java.util.Base64

internal data class VerifiedChatGptIdentity(val issuer: String, val subject: String, val email: String, val name: String)

/** OIDC verification is restricted to the issuer's advertised RS256 algorithm, never a JWT-supplied URL. */
internal class ChatGptIdTokenVerifier(private val transport: ChatGptAuthTransport, private val nowMillis: () -> Long) {
    private var discoveryCache: Pair<Long, JsonObject>? = null
    private var jwksCache: Pair<Long, JsonObject>? = null

    suspend fun discovery(): JsonObject {
        discoveryCache?.takeIf { nowMillis() - it.first in 0..3_600_000 }?.let { return it.second }
        val response = transport.request(ChatGptEndpoints.DISCOVERY)
        if (response.status != 200) throw authFailure(response)
        val data = parseAuthJson(response.body)
        if (data.string("issuer") != ChatGptEndpoints.ISSUER ||
            data.string("authorization_endpoint") != ChatGptEndpoints.AUTHORIZE ||
            data.string("token_endpoint") != ChatGptEndpoints.TOKEN)
            throw ChatGptAuthException("chatgpt_identity_invalid")
        trustedAuthUrl(data.string("jwks_uri"))
        data.string("revocation_endpoint")?.let(::trustedAuthUrl)
        val algorithms = data["id_token_signing_alg_values_supported"] as? JsonArray
        if (algorithms == null || algorithms.none { (it as? JsonPrimitive)?.contentOrNull == "RS256" })
            throw ChatGptAuthException("chatgpt_identity_invalid")
        discoveryCache = nowMillis() to data
        return data
    }

    private suspend fun keys(force: Boolean): JsonObject {
        if (!force) jwksCache?.takeIf { nowMillis() - it.first in 0..3_600_000 }?.let { return it.second }
        val url = trustedAuthUrl(discovery().string("jwks_uri"))
        val response = transport.request(url)
        if (response.status != 200) throw authFailure(response)
        return parseAuthJson(response.body).also {
            val keys = it["keys"] as? JsonArray ?: throw ChatGptAuthException("chatgpt_identity_invalid")
            if (keys.size !in 1..32) throw ChatGptAuthException("chatgpt_identity_invalid")
            jwksCache = nowMillis() to it
        }
    }

    suspend fun verify(token: String, clientId: String, expectedNonce: String?, expectedSubject: String? = null): VerifiedChatGptIdentity {
        try {
            if (token.length !in 20..32768) invalid()
            val parts = token.split('.')
            if (parts.size != 3 || parts.any { !it.matches(Regex("[A-Za-z0-9_-]+")) }) invalid()
            val header = parseAuthJson(decode(parts[0]).toString(Charsets.UTF_8))
            val kid = header.string("kid")?.takeIf { it.length in 1..256 } ?: invalid()
            if (header.string("alg") != "RS256" || "crit" in header || "jku" in header || "jwk" in header || "x5u" in header)
                invalid()
            var matches = matching(keys(false), kid)
            if (matches.isEmpty()) matches = matching(keys(true), kid)
            if (matches.size != 1) invalid()
            val key = matches.single()
            if (key.string("kty") != "RSA" || (key.string("use") != null && key.string("use") != "sig") ||
                (key.string("alg") != null && key.string("alg") != "RS256")) invalid()
            val modulusBytes = decode(key.string("n") ?: invalid())
            if (modulusBytes.size !in 256..1024) invalid()
            val modulus = BigInteger(1, modulusBytes)
            val exponentBytes = decode(key.string("e") ?: invalid())
            if (exponentBytes.size !in 1..8) invalid()
            val exponent = BigInteger(1, exponentBytes)
            if (modulus.bitLength() < 2048 || exponent < BigInteger.valueOf(3) || !exponent.testBit(0)) invalid()
            val publicKey = KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(modulus, exponent))
            val signature = Signature.getInstance("SHA256withRSA").apply {
                initVerify(publicKey); update("${parts[0]}.${parts[1]}".toByteArray(Charsets.US_ASCII))
            }
            if (!signature.verify(decode(parts[2]))) invalid()
            val claims = parseAuthJson(decode(parts[1]).toString(Charsets.UTF_8))
            if (claims.string("iss") != ChatGptEndpoints.ISSUER) invalid()
            val audiences = when (val audience = claims["aud"]) {
                is JsonPrimitive -> listOfNotNull(audience.contentOrNull)
                is JsonArray -> audience.map { (it as? JsonPrimitive)?.contentOrNull ?: invalid() }
                else -> invalid()
            }
            if (clientId !in audiences || audiences.size > 16 ||
                (audiences.size > 1 && claims.string("azp") != clientId) ||
                (claims.string("azp") != null && claims.string("azp") != clientId)) invalid()
            val now = nowMillis() / 1000
            val expiry = (claims["exp"] as? JsonPrimitive)?.longOrNull ?: invalid()
            val issued = (claims["iat"] as? JsonPrimitive)?.longOrNull ?: invalid()
            if (expiry <= now - 30 || issued > now + 30 || expiry <= issued || expiry > now + 86_400 * 2) invalid()
            (claims["nbf"] as? JsonPrimitive)?.longOrNull?.let { if (it > now + 30) invalid() }
            if (expectedNonce != null && !constantTimeEqual(expectedNonce, claims.string("nonce").orEmpty())) invalid()
            val subject = claims.string("sub")?.takeIf { it.length in 1..512 } ?: invalid()
            if (expectedSubject != null && subject != expectedSubject) invalid()
            return VerifiedChatGptIdentity(ChatGptEndpoints.ISSUER, subject,
                safeLabel(claims.string("email").orEmpty(), 320), safeLabel(claims.string("name").orEmpty(), 160))
        } catch (cancel: CancellationException) { throw cancel }
        catch (error: ChatGptAuthException) { throw error }
        catch (_: Exception) { invalid() }
    }

    private fun matching(jwks: JsonObject, kid: String): List<JsonObject> =
        (jwks["keys"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.filter { it.string("kid") == kid }

    companion object {
        internal fun trustedAuthUrl(value: String?): String {
            val uri = try { URI(value ?: "") } catch (_: Exception) { invalid() }
            if (uri.scheme != "https" || uri.host != "auth.openai.com" || uri.port !in setOf(-1, 443) ||
                uri.rawUserInfo != null || uri.rawFragment != null || uri.rawQuery != null || !uri.path.startsWith('/')) invalid()
            return uri.toString()
        }
        internal fun constantTimeEqual(a: String, b: String) = MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))
        private fun decode(value: String): ByteArray = Base64.getUrlDecoder().decode(value)
        private fun safeLabel(value: String, limit: Int) = value.filterNot { it.isISOControl() || it in '\u202A'..'\u202E' || it in '\u2066'..'\u2069' }.take(limit)
        private fun invalid(): Nothing = throw ChatGptAuthException("chatgpt_identity_invalid")
    }
}
