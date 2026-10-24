package app.mynguli.feed.auth

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.net.Socket
import java.net.URI
import java.time.Instant

class ChatGptIdentityAndCallbackTest {
    private val fixture = AuthFixture()
    @After fun close() { fixture.manager.cancelSignIn(); fixture.scope.cancel() }

    @Test fun issuerSignatureAudienceNonceAndLifetimeAreVerified() = runBlocking {
        val verifier = ChatGptIdTokenVerifier(fixture.transport, fixture.now::get)
        val valid = fixture.claims("fixture-nonce")
        assertEquals(fixture.subject, verifier.verify(AuthFixture.token(valid), fixture.client, "fixture-nonce").subject)
        val mutations = listOf(
            "iss" to JsonPrimitive("https://attacker.example"),
            "aud" to JsonPrimitive("oaiapp_someone_else"),
            "nonce" to JsonPrimitive("replayed-nonce"),
            "exp" to JsonPrimitive(fixture.now.get() / 1000 - 60),
            "iat" to JsonPrimitive(fixture.now.get() / 1000 + 3600),
            "nbf" to JsonPrimitive(fixture.now.get() / 1000 + 3600),
            "sub" to JsonPrimitive(""),
        )
        for ((field, value) in mutations) {
            assertIdentityFailure { verifier.verify(AuthFixture.token(JsonObject(valid + (field to value))), fixture.client, "fixture-nonce") }
        }
        assertIdentityFailure { verifier.verify(AuthFixture.token(valid), fixture.client, "fixture-nonce", "other-subject") }
        val signed = AuthFixture.token(valid)
        val signatureStart = signed.lastIndexOf('.') + 1
        val tampered = signed.substring(0, signatureStart) + (if (signed[signatureStart] == 'A') 'B' else 'A') + signed.substring(signatureStart + 1)
        assertIdentityFailure { verifier.verify(tampered, fixture.client, "fixture-nonce") }
    }

    @Test fun algorithmConfusionAndJwtKeyUrlsAreRejected() = runBlocking {
        val verifier = ChatGptIdTokenVerifier(fixture.transport, fixture.now::get)
        val headers = listOf(
            buildJsonObject { put("alg", "none"); put("kid", "fixture-key") },
            buildJsonObject { put("alg", "HS256"); put("kid", "fixture-key") },
            buildJsonObject { put("alg", "RS256"); put("kid", "fixture-key"); put("jku", "https://attacker.example/keys") },
            buildJsonObject { put("alg", "RS256"); put("kid", "fixture-key"); putJsonArray("crit") { add("b64") } },
            buildJsonObject { put("alg", "RS256"); put("kid", "unknown-key") },
        )
        for (header in headers) assertIdentityFailure { verifier.verify(AuthFixture.token(fixture.claims(), header), fixture.client, fixture.nonce) }
    }

    @Test fun multiAudienceRequiresMatchingAuthorizedParty() = runBlocking {
        val verifier = ChatGptIdTokenVerifier(fixture.transport, fixture.now::get)
        val claims = JsonObject(fixture.claims() + ("aud" to JsonArray(listOf(JsonPrimitive(fixture.client), JsonPrimitive("other-client")))))
        assertIdentityFailure { verifier.verify(AuthFixture.token(claims), fixture.client, fixture.nonce) }
        assertIdentityFailure { verifier.verify(AuthFixture.token(JsonObject(claims + ("azp" to JsonPrimitive("other-client")))), fixture.client, fixture.nonce) }
        assertEquals(fixture.subject, verifier.verify(AuthFixture.token(JsonObject(claims + ("azp" to JsonPrimitive(fixture.client)))), fixture.client, fixture.nonce).subject)
    }

    @Test fun discoveryCannotRedirectKeyFetchToAnotherHost() = runBlocking {
        val malicious = object : ChatGptAuthTransport {
            override suspend fun request(url: String, form: Map<String, String>?, bearer: String?) = AuthHttpResult(200,
                AuthFixture.discovery.replace("https://auth.openai.com/.well-known/jwks.json", "https://attacker.example/keys"))
        }
        assertIdentityFailure { ChatGptIdTokenVerifier(malicious, fixture.now::get).verify(AuthFixture.token(fixture.claims()), fixture.client, fixture.nonce) }
    }

    @Test fun cancellationDuringKeyDiscoveryStaysCancellation() = runBlocking {
        val cancelled = object : ChatGptAuthTransport {
            override suspend fun request(url: String, form: Map<String, String>?, bearer: String?): AuthHttpResult = throw CancellationException("fixture cancellation")
        }
        try {
            ChatGptIdTokenVerifier(cancelled, fixture.now::get).verify(AuthFixture.token(fixture.claims()), fixture.client, fixture.nonce)
            fail("Expected cancellation")
        } catch (_: CancellationException) { /* Cancellation must not become an identity failure. */ }
    }

    @Test fun malformedAndDuplicateQueriesAreRejectedWithoutConsumingValidCallback() = runBlocking {
        val request = AuthFixture.query(fixture.manager.beginSignIn())
        fixture.nonce = request.getValue("nonce")
        val redirect = URI(request.getValue("redirect_uri"))
        for (target in listOf("/auth/callback?state=%ZZ", "/auth/callback?state=a&state=b", "http://evil.test/auth/callback?state=a")) {
            val result = rawRequest(redirect.port, "GET $target HTTP/1.1\r\nHost: 127.0.0.1:${redirect.port}\r\n\r\n")
            assertTrue(result.startsWith("HTTP/1.1 400"))
            assertTrue(result.contains("scheme=app.mynguli.debug.connect;"))
            assertEquals("waiting", fixture.manager.state.value.phase)
        }
        AuthFixture.callback(request.getValue("redirect_uri"), mapOf("state" to request.getValue("state"), "code" to "valid-code", "client_id" to fixture.client))
        assertTrue(fixture.manager.awaitSignIn().connected)
        assertEquals(1, fixture.tokenCalls.get())
        assertTrue(runCatching { Socket("127.0.0.1", redirect.port).close() }.isFailure)
    }

    @Test fun localRequestMustUseExactHostPathMethodAndNoCrossOriginBody() {
        val valid = "GET /auth/callback?state=s&code=c HTTP/1.1\r\nHost: 127.0.0.1:12345\r\n\r\n"
        assertEquals("c", LoopbackChatGptCallback.parseRequest(valid, 12345)["code"])
        val invalid = listOf(
            valid.replace("127.0.0.1", "localhost"),
            valid.replace("/auth/callback", "/callback"),
            valid.replace("GET", "POST"),
            valid.replace("\r\n\r\n", "\r\nOrigin: https://evil.example\r\n\r\n"),
            valid.replace("\r\n\r\n", "\r\nContent-Length: 2\r\n\r\n"),
            valid.replace("\r\n\r\n", "\r\nHost: 127.0.0.1:12345\r\n\r\n"),
            valid.replace("code=c", "code=%0aBearer"),
            valid.replace("code=c", "code=c#fragment"),
        )
        for (request in invalid) assertTrue(runCatching { LoopbackChatGptCallback.parseRequest(request, 12345) }.isFailure)
    }

    @Test fun earliestRefreshTimestampIsBoundedAndUnambiguous() {
        val now = fixture.now.get()
        assertEquals(0L, ChatGptAuthManager.parseEarliestRefresh(null, now))
        assertEquals(now, ChatGptAuthManager.parseEarliestRefresh(JsonPrimitive(now / 1000), now))
        assertEquals(now, ChatGptAuthManager.parseEarliestRefresh(JsonPrimitive(Instant.ofEpochMilli(now).toString()), now))
        listOf(JsonPrimitive(-1), JsonPrimitive(Long.MAX_VALUE), JsonPrimitive("in 30 seconds"), buildJsonObject { put("delay", 30) }).forEach {
            assertTrue(runCatching { ChatGptAuthManager.parseEarliestRefresh(it, now) }.isFailure)
        }
    }

    @Test fun providerFailuresExposeOnlyAllowlistedCodes() {
        val cases = listOf(
            403 to ("subscription_sharing_user_not_eligible" to "chatgpt_ineligible"),
            429 to ("subscription_sharing_usage_limit_exceeded" to "chatgpt_limit"),
            400 to ("subscription_sharing_unsupported_capability" to "chatgpt_capability"),
            403 to ("chatpass_v2_scope_not_authorized" to "chatgpt_permission"),
            503 to ("subscription_sharing_usage_unavailable" to "chatgpt_unavailable"),
        )
        for ((status, code) in cases) {
            val failure = authFailure(AuthHttpResult(status, """{"error":{"code":"${code.first}","message":"fixture-secret-do-not-show"}}"""))
            assertEquals(code.second, failure.code)
            assertEquals(code.second, failure.message)
        }
        assertEquals("chatgpt_policy", authFailure(AuthHttpResult(403, """{"detail":"fixture-secret-do-not-show"}""")).code)
    }

    @Test fun secretContainersHaveRedactedToString() {
        val record = fixture.seed()
        assertFalse(record.toString().contains("fixture-refresh"))
        assertFalse(fixture.store.saved.toString().contains("fixture-refresh"))
        assertFalse(AuthHttpResult(200, "fixture-access").toString().contains("fixture-access"))
    }

    private fun rawRequest(port: Int, request: String): String = Socket("127.0.0.1", port).use { socket ->
        socket.soTimeout = 5000
        socket.getOutputStream().apply { write(request.toByteArray()); flush() }
        socket.getInputStream().bufferedReader().readText()
    }
    private suspend fun assertIdentityFailure(block: suspend () -> Any?) {
        try { block(); fail("Expected invalid identity") }
        catch (error: ChatGptAuthException) { assertEquals("chatgpt_identity_invalid", error.code) }
    }
}
