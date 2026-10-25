package mebot.nguli.com.feed.auth

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.*
import java.net.Socket
import java.net.URI
import java.net.URLDecoder
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

internal class MemoryChatGptStore(var saved: ChatGptPrivateState? = null) : ChatGptCredentialStore {
    var writes = 0
    @Synchronized override fun read() = saved
    @Synchronized override fun write(state: ChatGptPrivateState) { saved = state; writes++ }
    @Synchronized override fun clear() { saved = null }
}

internal class AuthFixture {
    val now = AtomicLong(1_792_730_000_000L)
    val store = MemoryChatGptStore()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    var subject = "fixture-subject"
    var email = "alex@example.com"
    var nonce = ""
    var client = "oaiapp_fixture"
    val tokenCalls = AtomicInteger()
    val modelCalls = AtomicInteger()
    val revokeCalls = AtomicInteger()
    val forms = java.util.Collections.synchronizedList(mutableListOf<Map<String, String>>())
    var tokenResult: AuthHttpResult? = null
    var modelsResult: AuthHttpResult? = null
    var revocationResult = AuthHttpResult(200, "")
    var tokenStarted: CompletableDeferred<Unit>? = null
    var tokenContinue: CompletableDeferred<Unit>? = null
    val transport = object : ChatGptAuthTransport {
        override suspend fun request(url: String, form: Map<String, String>?, bearer: String?): AuthHttpResult = when (url) {
            ChatGptEndpoints.DISCOVERY -> AuthHttpResult(200, discovery)
            "https://auth.openai.com/.well-known/jwks.json" -> AuthHttpResult(200, jwks)
            ChatGptEndpoints.TOKEN -> {
                tokenCalls.incrementAndGet()
                forms += (form ?: emptyMap())
                tokenStarted?.complete(Unit)
                tokenContinue?.await()
                tokenResult ?: AuthHttpResult(200, tokenBody(if (form?.get("grant_type") == "refresh_token") null else nonce))
            }
            ChatGptEndpoints.MODELS -> {
                modelCalls.incrementAndGet()
                modelsResult ?: AuthHttpResult(200, """{"models":[{"slug":"hidden-model","display_name":"Hidden","visibility":"hide"},{"slug":"model-b","display_name":"Model B","visibility":"list"},{"slug":"model-a","display_name":"Model A","visibility":"list"}]}""")
            }
            "https://auth.openai.com/api/accounts/oauth/revoke" -> { revokeCalls.incrementAndGet(); revocationResult }
            else -> error("Unexpected fixture request")
        }
    }
    val manager = ChatGptAuthManager(store, transport, now::get,
        ChatGptCallbackCopy("Return", "Return to fixture app", "Invalid link", "Open app", "mebot.nguli.com.debug"), scope)

    fun tokenBody(expectedNonce: String? = nonce, refresh: String = "fixture-refresh-next", scopes: String = ChatGptEndpoints.SCOPES): String = buildJsonObject {
        put("access_token", "fixture-access-next")
        put("refresh_token", refresh)
        put("token_type", "Bearer")
        put("expires_in", 3600)
        put("scope", scopes)
        put("earliest_refresh_at", now.get() / 1000)
        if (expectedNonce != null) put("id_token", token(claims(expectedNonce)))
    }.toString()

    fun claims(expectedNonce: String = nonce) = buildJsonObject {
        put("iss", ChatGptEndpoints.ISSUER)
        put("sub", subject)
        put("aud", client)
        put("iat", now.get() / 1000)
        put("exp", now.get() / 1000 + 3600)
        put("nonce", expectedNonce)
        put("email", email)
        put("name", "Alex")
    }

    fun seed(expiry: Long = now.get() - 1000, earliest: Long = 0, plan: Boolean = true): ChatGptCredentialRecord {
        val record = ChatGptCredentialRecord(id = ChatGptAuthManager.registrationId(client), clientId = client,
            subject = subject, email = email, name = "Alex", accessToken = "fixture-access-old", refreshToken = "fixture-refresh-old",
            idToken = token(claims()), expiresAtMillis = expiry, earliestRefreshAtMillis = earliest,
            scopes = if (plan) ChatGptEndpoints.SCOPES.split(' ').toSet() else setOf("openid", "profile", "email"))
        store.saved = ChatGptPrivateState(hostId = "urn:uuid:11111111-1111-4111-a111-111111111111", accounts = listOf(record), activeAccountId = record.id)
        return record
    }

    companion object {
        private val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        private val publicKey = pair.public as RSAPublicKey
        val discovery = """{"issuer":"https://auth.openai.com","authorization_endpoint":"https://auth.openai.com/api/accounts/authorize","token_endpoint":"https://auth.openai.com/api/accounts/oauth/token","revocation_endpoint":"https://auth.openai.com/api/accounts/oauth/revoke","jwks_uri":"https://auth.openai.com/.well-known/jwks.json","id_token_signing_alg_values_supported":["RS256"]}"""
        val jwks = buildJsonObject { putJsonArray("keys") { add(buildJsonObject {
            put("kty", "RSA"); put("kid", "fixture-key"); put("alg", "RS256"); put("use", "sig")
            put("n", encode(publicKey.modulus.toByteArray().let { if (it[0] == 0.toByte()) it.copyOfRange(1, it.size) else it }))
            put("e", encode(publicKey.publicExponent.toByteArray()))
        }) } }.toString()

        fun token(claims: JsonObject, header: JsonObject = buildJsonObject { put("alg", "RS256"); put("kid", "fixture-key") }): String {
            val content = encode(header.toString().toByteArray()) + "." + encode(claims.toString().toByteArray())
            val signature = Signature.getInstance("SHA256withRSA").apply { initSign(pair.private); update(content.toByteArray(Charsets.US_ASCII)) }.sign()
            return content + "." + encode(signature)
        }
        private fun encode(value: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(value)
        fun query(url: String): Map<String, String> = URI(url).rawQuery.split('&').associate {
            val parts = it.split('=', limit = 2)
            URLDecoder.decode(parts[0], "UTF-8") to URLDecoder.decode(parts[1], "UTF-8")
        }
        fun callback(redirectUri: String, params: Map<String, String>): String {
            val uri = URI(redirectUri)
            Socket(uri.host, uri.port).use { socket ->
                socket.soTimeout = 5000
                val query = params.entries.joinToString("&") { encodeForm(it.key) + "=" + encodeForm(it.value) }
                socket.getOutputStream().apply {
                    write("GET ${uri.path}?$query HTTP/1.1\r\nHost: 127.0.0.1:${uri.port}\r\nConnection: close\r\n\r\n".toByteArray())
                    flush()
                }
                return socket.getInputStream().bufferedReader().readText()
            }
        }
    }
}
