package app.mynguli.feed.auth

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.net.Socket
import java.net.URI
import java.security.MessageDigest
import java.util.Base64

class ChatGptAuthManagerTest {
    private val fixture = AuthFixture()
    @After fun close() { fixture.manager.cancelSignIn(); fixture.scope.cancel() }

    @Test fun firstRegistrationBindsLoopbackBeforeBrowserAndExchangesExactPkce() = runBlocking {
        val url = fixture.manager.beginSignIn()
        val request = AuthFixture.query(url)
        assertTrue(url.startsWith(ChatGptEndpoints.AUTHORIZE + "?"))
        assertEquals("dynamic_agent_client", request["client_id"])
        assertEquals("MeBot", request["agent_name_hint"])
        assertEquals(fixture.store.saved!!.hostId, request["ext_agent_host_id"])
        assertEquals("waiting", fixture.manager.state.value.phase)
        fixture.nonce = request.getValue("nonce")
        val callback = AuthFixture.callback(request.getValue("redirect_uri"), mapOf("state" to request.getValue("state"), "code" to "fixture-code", "client_id" to fixture.client))
        assertTrue(callback.startsWith("HTTP/1.1 200"))
        assertTrue(callback.contains("launchFlags=0x30020000"))
        assertTrue(callback.contains("scheme=app.mynguli.debug.connect;"))
        assertTrue(callback.contains("category=android.intent.category.BROWSABLE;"))
        assertFalse(callback.contains("component="))
        assertFalse(callback.contains("0x24000000"))
        assertFalse(callback.contains("fixture-code"))
        assertFalse(callback.contains(request.getValue("state")))
        val style = Regex("<style>([\\s\\S]*?)</style>").find(callback)!!.groupValues[1]
        val expectedHash = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(style.toByteArray()))
        assertTrue(callback.contains("style-src 'sha256-$expectedHash'"))
        assertTrue(style.contains("prefers-color-scheme:dark"))
        val account = fixture.manager.awaitSignIn()
        assertTrue(account.connected && account.planEnabled)
        assertTrue(account.models.isEmpty())
        assertEquals(0, fixture.modelCalls.get())
        assertEquals(listOf("model-b", "model-a"), fixture.manager.models(account.id).map { it.slug })
        assertNull(fixture.manager.state.value.activeAccountId)
        val exchanged = fixture.forms.single()
        assertEquals(fixture.client, exchanged["client_id"])
        assertEquals(request["redirect_uri"], exchanged["redirect_uri"])
        assertEquals(request["code_challenge"], Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(exchanged.getValue("code_verifier").toByteArray(Charsets.US_ASCII))))
        assertFalse(exchanged.containsKey("client_secret"))
        assertEquals(1, fixture.tokenCalls.get())
    }

    @Test fun returningAccountUsesIssuedClientAndHintAndChecksOmittedCallbackClient() = runBlocking {
        val old = fixture.seed(expiry = fixture.now.get() + 3_600_000)
        val request = AuthFixture.query(fixture.manager.beginSignIn(old.id))
        assertEquals(old.clientId, request["client_id"])
        assertFalse(request.containsKey("agent_name_hint"))
        assertEquals(old.idToken, request["id_token_hint"])
        fixture.nonce = request.getValue("nonce")
        AuthFixture.callback(request.getValue("redirect_uri"), mapOf("state" to request.getValue("state"), "code" to "fixture-code"))
        assertEquals(old.id, fixture.manager.awaitSignIn().id)
        assertEquals(1, fixture.store.saved!!.accounts.size)
    }

    @Test fun changedReturningClientIsRejectedBeforeCodeExchange() = runBlocking {
        val old = fixture.seed()
        val request = AuthFixture.query(fixture.manager.beginSignIn(old.id))
        AuthFixture.callback(request.getValue("redirect_uri"), mapOf("state" to request.getValue("state"), "code" to "fixture-code", "client_id" to "oaiapp_other"))
        assertFailure("chatgpt_identity_invalid") { fixture.manager.awaitSignIn() }
        assertEquals(0, fixture.tokenCalls.get())
        assertEquals(old, fixture.store.saved!!.accounts.single())
    }

    @Test fun returningDifferentSubjectNeverOverwritesExistingCredentials() = runBlocking {
        val old = fixture.seed()
        val request = AuthFixture.query(fixture.manager.beginSignIn(old.id))
        fixture.nonce = request.getValue("nonce"); fixture.subject = "different-subject-same-email"
        AuthFixture.callback(request.getValue("redirect_uri"), mapOf("state" to request.getValue("state"), "code" to "fixture-code"))
        assertFailure("chatgpt_identity_invalid") { fixture.manager.awaitSignIn() }
        assertEquals(old, fixture.store.saved!!.accounts.single())
    }

    @Test fun sameEmailInDifferentRegistrationStaysSeparateAndDoesNotSwitchActive() = runBlocking {
        val old = fixture.seed()
        fixture.client = "oaiapp_second_workspace"
        val request = AuthFixture.query(fixture.manager.beginSignIn())
        fixture.nonce = request.getValue("nonce")
        AuthFixture.callback(request.getValue("redirect_uri"), mapOf("state" to request.getValue("state"), "code" to "fixture-code", "client_id" to fixture.client))
        val new = fixture.manager.awaitSignIn()
        assertNotEquals(old.id, new.id)
        assertEquals(2, fixture.store.saved!!.accounts.size)
        assertEquals(old.id, fixture.manager.state.value.activeAccountId)
        assertEquals(2, fixture.manager.state.value.accounts.map { it.label }.distinct().size)
    }

    @Test fun addingAlreadyRegisteredAccountPreservesNoticeAndIdentityBinding() = runBlocking {
        val old = fixture.seed()
        fixture.manager.acknowledgePlanNotice(old.id)
        val request = AuthFixture.query(fixture.manager.beginSignIn())
        fixture.nonce = request.getValue("nonce")
        AuthFixture.callback(request.getValue("redirect_uri"), mapOf("state" to request.getValue("state"), "code" to "code", "client_id" to fixture.client))
        val account = fixture.manager.awaitSignIn()
        assertEquals(old.id, account.id)
        assertTrue(account.planNoticeAcknowledged)
        assertEquals(1, fixture.store.saved!!.accounts.size)

        val another = AuthFixture.query(fixture.manager.beginSignIn())
        fixture.nonce = another.getValue("nonce")
        fixture.subject = "different-subject"
        AuthFixture.callback(another.getValue("redirect_uri"), mapOf("state" to another.getValue("state"), "code" to "code", "client_id" to fixture.client))
        assertFailure("chatgpt_identity_invalid") { fixture.manager.awaitSignIn() }
        assertEquals(old.subject, fixture.store.saved!!.accounts.single().subject)
        assertTrue(fixture.store.saved!!.accounts.single().planNoticeAcknowledged)
    }

    @Test fun mismatchedStateCannotConsumePendingLoginAndDeclineNeverExchangesCode() = runBlocking {
        val request = AuthFixture.query(fixture.manager.beginSignIn())
        val wrong = AuthFixture.callback(request.getValue("redirect_uri"), mapOf("state" to "wrong-state", "code" to "attacker-code", "client_id" to "oaiapp_attacker"))
        assertTrue(wrong.startsWith("HTTP/1.1 400"))
        assertEquals("waiting", fixture.manager.state.value.phase)
        assertEquals(0, fixture.tokenCalls.get())
        AuthFixture.callback(request.getValue("redirect_uri"), mapOf("state" to request.getValue("state"), "error" to "access_denied"))
        assertFailure("chatgpt_cancelled") { fixture.manager.awaitSignIn() }
        assertEquals(0, fixture.tokenCalls.get())
        assertTrue(fixture.store.saved!!.accounts.isEmpty())
    }

    @Test fun cancelClosesSocketAndNextAttemptUsesFreshSecretsButSameHost() = runBlocking {
        val old = AuthFixture.query(fixture.manager.beginSignIn())
        val port = URI(old.getValue("redirect_uri")).port
        fixture.manager.cancelSignIn()
        assertTrue(runCatching { Socket("127.0.0.1", port).close() }.isFailure)
        assertFailure("chatgpt_cancelled") { fixture.manager.awaitSignIn() }
        val next = AuthFixture.query(fixture.manager.beginSignIn())
        assertEquals(old["ext_agent_host_id"], next["ext_agent_host_id"])
        listOf("state", "nonce", "code_challenge").forEach { assertNotEquals(old[it], next[it]) }
    }

    @Test fun expiredBrowserAttemptClosesListenerAndExposesRetryState() = runBlocking {
        val manager = ChatGptAuthManager(fixture.store, fixture.transport, fixture.now::get,
            ChatGptCallbackCopy("Return", "Body", "Invalid", "Open", "app.mynguli"), fixture.scope, signInTimeoutMillis = 100)
        val request = AuthFixture.query(manager.beginSignIn())
        assertFailure("chatgpt_timeout") { manager.awaitSignIn() }
        assertEquals("idle", manager.state.value.phase)
        assertEquals("chatgpt_timeout", manager.state.value.errorCode)
        assertTrue(runCatching { Socket("127.0.0.1", URI(request.getValue("redirect_uri")).port).close() }.isFailure)
    }

    @Test fun callbackCopyIsResolvedForEachAttemptAndEscaped() = runBlocking {
        var language = "English"
        val manager = ChatGptAuthManager(fixture.store, fixture.transport, fixture.now::get,
            ChatGptCallbackCopy("Return", "Body", "Invalid", "Open", "app.mynguli"), fixture.scope,
            callbackCopyProvider = { ChatGptCallbackCopy("Return $language", "Use <MeBot> & continue", "Invalid", "Open", "app.mynguli") })
        manager.beginSignIn()
        manager.cancelSignIn()
        language = "Kiswahili"
        val request = AuthFixture.query(manager.beginSignIn())
        val page = AuthFixture.callback(request.getValue("redirect_uri"), mapOf("state" to request.getValue("state"), "error" to "access_denied"))
        assertTrue(page.contains("Return Kiswahili"))
        assertTrue(page.contains("Use &lt;MeBot&gt; &amp; continue"))
        assertFailure("chatgpt_cancelled") { manager.awaitSignIn() }
    }

    @Test fun cancellationDuringPreparationCannotPublishLateWaitingState() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val proceed = CompletableDeferred<Unit>()
        val manager = ChatGptAuthManager(fixture.store, fixture.transport, fixture.now::get,
            ChatGptCallbackCopy("Return", "Body", "Invalid", "Open", "app.mynguli"), fixture.scope,
            callbackCopyProvider = {
                started.complete(Unit); proceed.await()
                ChatGptCallbackCopy("Return", "Body", "Invalid", "Open", "app.mynguli")
            })
        val preparation = async(Dispatchers.Default) { runCatching { manager.beginSignIn() } }
        started.await()
        manager.cancelSignIn()
        proceed.complete(Unit)
        assertTrue(preparation.await().isFailure)
        assertEquals("idle", manager.state.value.phase)
        assertNull(manager.state.value.errorCode)
        assertFailure("chatgpt_cancelled") { manager.awaitSignIn() }
    }

    @Test fun cancelAlsoClosesAcceptedSlowBrowserConnection() = runBlocking {
        val request = AuthFixture.query(fixture.manager.beginSignIn())
        val port = URI(request.getValue("redirect_uri")).port
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 1000
            socket.getOutputStream().apply { write("GET /auth/".toByteArray()); flush() }
            delay(40)
            fixture.manager.cancelSignIn()
            val closed = runCatching { socket.getInputStream().read() }.fold(
                onSuccess = { it == -1 },
                onFailure = { it is java.io.IOException && it !is java.net.SocketTimeoutException },
            )
            assertTrue(closed)
        }
        assertEquals("idle", fixture.manager.state.value.phase)
    }

    @Test fun expiredCodeRetainsIssuedRegistrationForFreshReauthorization() = runBlocking {
        fixture.tokenResult = AuthHttpResult(400, """{"error":"invalid_grant"}""")
        val request = AuthFixture.query(fixture.manager.beginSignIn())
        AuthFixture.callback(request.getValue("redirect_uri"), mapOf("state" to request.getValue("state"), "code" to "expired-code", "client_id" to fixture.client))
        assertFailure("chatgpt_reauth") { fixture.manager.awaitSignIn() }
        val saved = fixture.store.saved!!.accounts.single()
        assertEquals(fixture.client, saved.clientId)
        assertEquals("", saved.accessToken)
        assertEquals(fixture.client, AuthFixture.query(fixture.manager.beginSignIn(saved.id))["client_id"])
    }

    @Test fun temporaryCatalogFailureKeepsValidatedSessionAndPreviousSelection() = runBlocking {
        val old = fixture.seed()
        fixture.client = "oaiapp_second"
        fixture.modelsResult = AuthHttpResult(503, """{"error":{"code":"subscription_sharing_usage_unavailable"}}""")
        val request = AuthFixture.query(fixture.manager.beginSignIn())
        fixture.nonce = request.getValue("nonce")
        AuthFixture.callback(request.getValue("redirect_uri"), mapOf("state" to request.getValue("state"), "code" to "code", "client_id" to fixture.client))
        val account = fixture.manager.awaitSignIn()
        assertTrue(account.connected && account.planEnabled)
        assertEquals(0, fixture.modelCalls.get())
        assertFailure("chatgpt_unavailable") { fixture.manager.models(account.id) }
        assertEquals(old.id, fixture.store.saved!!.activeAccountId)
        assertTrue(fixture.store.saved!!.accounts.last().accessToken.isNotBlank())
        assertEquals(ChatGptDiagnostic("models", 503, responseShape = "error_object"), fixture.manager.state.value.lastDiagnostic)
        fixture.modelsResult = null
        assertEquals(2, fixture.manager.models(fixture.store.saved!!.accounts.last().id, true).size)
        assertNull(fixture.manager.state.value.lastDiagnostic)
    }

    @Test fun malformedModelReplyIsAConnectionStageFailureAndCanRetryWithoutSigningIn() = runBlocking {
        val record = fixture.seed(expiry = fixture.now.get() + 3_600_000)
        fixture.modelsResult = AuthHttpResult(200, """{"unexpected":"fixture-secret-do-not-display"}""", "req_fixture_123")
        assertFailure("chatgpt_models") { fixture.manager.models(record.id, true) }
        assertTrue(fixture.manager.state.value.accounts.single().connected)
        assertEquals("fixture-access-old", fixture.store.saved!!.accounts.single().accessToken)
        assertEquals(ChatGptDiagnostic("models", 200, "req_fixture_123", "other_json"), fixture.manager.state.value.lastDiagnostic)
        assertFalse(fixture.manager.state.value.lastDiagnostic.toString().contains("fixture-secret-do-not-display"))
        fixture.modelsResult = AuthHttpResult(200, """{"object":"list","data":[{"object":"model","id":"fixture-response-model"}]}""")
        assertEquals("fixture-response-model", fixture.manager.models(record.id, true).single().slug)
        assertEquals(0, fixture.tokenCalls.get())
        assertNull(fixture.manager.state.value.lastDiagnostic)
    }

    @Test fun inferenceDiagnosticsDoNotChangeCredentialsAndIgnoreRemovedSessions() = runBlocking {
        val record = fixture.seed(expiry = fixture.now.get() + 3_600_000)
        val before = fixture.store.saved
        val diagnostic = ChatGptDiagnostic("inference", 200, "req_fixture", "error_object",
            responseMediaType = "application/json", responseStatus = "failed",
            providerCode = "subscription_sharing_unsupported_capability", providerParam = "tools")
        fixture.manager.recordInferenceDiagnostic(record.id, record.accessToken, diagnostic)
        assertEquals(diagnostic, fixture.manager.state.value.lastDiagnostic)
        assertEquals(before, fixture.store.saved)
        fixture.manager.recordInferenceDiagnostic(record.id, "different-credential", diagnostic.copy(httpStatus = 500))
        assertEquals(diagnostic, fixture.manager.state.value.lastDiagnostic)
        fixture.manager.invalidateSession(record.id, record.accessToken)
        fixture.manager.recordInferenceDiagnostic(record.id, record.accessToken, diagnostic.copy(httpStatus = 503))
        assertEquals(diagnostic, fixture.manager.state.value.lastDiagnostic)
        assertFalse(fixture.manager.state.value.accounts.single().connected)
    }

    @Test fun grantedScopesNotCallbackScopesDeterminePlanAccess() = runBlocking {
        val request = AuthFixture.query(fixture.manager.beginSignIn())
        fixture.nonce = request.getValue("nonce")
        fixture.tokenResult = AuthHttpResult(200, fixture.tokenBody(scopes = "openid profile email offline_access"))
        AuthFixture.callback(request.getValue("redirect_uri"), mapOf("state" to request.getValue("state"), "code" to "code", "client_id" to fixture.client, "scope" to ChatGptEndpoints.SCOPES))
        val account = fixture.manager.awaitSignIn()
        assertTrue(account.connected)
        assertFalse(account.planEnabled)
        assertEquals(0, fixture.modelCalls.get())
        assertFailure("chatgpt_permission") { fixture.manager.accessToken(account.id) }
    }

    @Test fun refreshIsSingleFlightAndAtomicallyStoresRotatedCredentials() = runBlocking {
        val old = fixture.seed()
        val results = (1..20).map { async(Dispatchers.Default) { fixture.manager.accessToken(old.id) } }.awaitAll()
        assertEquals(setOf("fixture-access-next"), results.toSet())
        assertEquals(1, fixture.tokenCalls.get())
        val saved = fixture.store.saved!!.accounts.single()
        assertEquals("fixture-refresh-next", saved.refreshToken)
        assertEquals(fixture.now.get() + 3_600_000, saved.expiresAtMillis)
        assertFalse(fixture.forms.single().containsKey("scope"))
        assertEquals(old.clientId, fixture.forms.single()["client_id"])
    }

    @Test fun refreshRespectsEarliestTimestampWithoutDiscardingSession() = runBlocking {
        val old = fixture.seed(expiry = fixture.now.get() - 1000, earliest = fixture.now.get() + 30_000)
        assertFailure("chatgpt_refresh_wait") { fixture.manager.accessToken(old.id) }
        assertEquals(0, fixture.tokenCalls.get())
        assertEquals(old, fixture.store.saved!!.accounts.single())
    }

    @Test fun cancellingCallerDoesNotStrandRotatedRefreshToken() = runBlocking {
        val old = fixture.seed()
        fixture.tokenStarted = CompletableDeferred()
        fixture.tokenContinue = CompletableDeferred()
        val caller = launch(Dispatchers.Default) { fixture.manager.accessToken(old.id) }
        fixture.tokenStarted!!.await()
        caller.cancel()
        fixture.tokenContinue!!.complete(Unit)
        caller.join()
        assertEquals("fixture-refresh-next", fixture.store.saved!!.accounts.single().refreshToken)
        assertEquals("fixture-access-next", fixture.store.saved!!.accounts.single().accessToken)
        assertEquals(1, fixture.tokenCalls.get())
    }

    @Test fun staleUnauthorizedResponseCannotInvalidateNewSession() = runBlocking {
        val old = fixture.seed()
        assertEquals("fixture-access-next", fixture.manager.accessToken(old.id))
        assertFalse(fixture.manager.invalidateSession(old.id, expectedAccessToken = old.accessToken))
        assertEquals("fixture-access-next", fixture.store.saved!!.accounts.single().accessToken)
        assertTrue(fixture.manager.invalidateSession(old.id, expectedAccessToken = "fixture-access-next"))
        assertEquals("", fixture.store.saved!!.accounts.single().accessToken)
    }

    @Test fun resetWinsOverRefreshAlreadyInFlight() = runBlocking {
        val old = fixture.seed()
        fixture.tokenStarted = CompletableDeferred()
        fixture.tokenContinue = CompletableDeferred()
        val caller = async(Dispatchers.Default) { runCatching { fixture.manager.accessToken(old.id) } }
        fixture.tokenStarted!!.await()
        fixture.manager.clearAll()
        fixture.tokenContinue!!.complete(Unit)
        assertTrue(caller.await().isFailure)
        assertNull(fixture.store.saved)
        assertTrue(fixture.manager.state.value.accounts.isEmpty())
    }

    @Test fun terminalRefreshErrorClearsOnlyCredentialsAndKeepsRegistration() = runBlocking {
        val old = fixture.seed()
        fixture.tokenResult = AuthHttpResult(400, """{"error":"refresh_token_reused","error_description":"secret should not leak"}""")
        assertFailure("chatgpt_reauth") { fixture.manager.accessToken(old.id) }
        val saved = fixture.store.saved!!.accounts.single()
        assertEquals(old.clientId, saved.clientId)
        assertEquals(old.subject, saved.subject)
        assertEquals("", saved.accessToken)
        assertEquals("", saved.refreshToken)
        assertNull(fixture.store.saved!!.activeAccountId)
    }

    @Test fun temporaryRefreshFailureDoesNotLoseCredentialPair() = runBlocking {
        val old = fixture.seed()
        fixture.tokenResult = AuthHttpResult(503, """{"error":"temporarily_unavailable"}""")
        assertFailure("chatgpt_unavailable") { fixture.manager.accessToken(old.id) }
        assertEquals(old, fixture.store.saved!!.accounts.single())
    }

    @Test fun incompleteRefreshNeverCommitsMixedCredentialPair() = runBlocking {
        val old = fixture.seed()
        fixture.tokenResult = AuthHttpResult(200, fixture.tokenBody(refresh = ""))
        assertFailure("chatgpt_invalid_response") { fixture.manager.accessToken(old.id) }
        assertEquals(old, fixture.store.saved!!.accounts.single())
    }

    @Test fun signOutRevokesAndKeepsAccountMappingAndHost() = runBlocking {
        val old = fixture.seed()
        val host = fixture.store.saved!!.hostId
        assertTrue(fixture.manager.signOut(old.id))
        assertEquals(1, fixture.revokeCalls.get())
        val saved = fixture.store.saved!!
        assertEquals(host, saved.hostId)
        assertEquals(old.subject, saved.accounts.single().subject)
        assertEquals("", saved.accounts.single().idToken)
        assertNull(saved.activeAccountId)
    }

    @Test fun remoteRevocationFailureRetriesThenTruthfullyReportsUnconfirmedLocalSignOut() = runBlocking {
        val old = fixture.seed()
        fixture.revocationResult = AuthHttpResult(503, "")
        assertFalse(fixture.manager.signOut(old.id))
        assertEquals(2, fixture.revokeCalls.get())
        assertEquals("", fixture.store.saved!!.accounts.single().refreshToken)
    }

    @Test fun clearAllInvalidatesPendingLoginAndRemovesCredentials() = runBlocking {
        fixture.seed()
        fixture.manager.beginSignIn()
        fixture.manager.clearAll()
        assertNull(fixture.store.saved)
        assertTrue(fixture.manager.state.value.accounts.isEmpty())
        assertFailure("chatgpt_cancelled") { fixture.manager.awaitSignIn() }
    }

    @Test fun planNoticePersistsPerRegistrationAcrossManagerRestart() = runBlocking {
        val old = fixture.seed()
        fixture.manager.acknowledgePlanNotice(old.id)
        val restarted = ChatGptAuthManager(fixture.store, fixture.transport, fixture.now::get,
            ChatGptCallbackCopy("Return", "Body", "Invalid", "Open", "app.mynguli"), fixture.scope)
        restarted.initialize()
        assertTrue(restarted.state.value.accounts.single().planNoticeAcknowledged)
        assertEquals("idle", restarted.state.value.phase)
        assertFailure("chatgpt_cancelled") { restarted.awaitSignIn() }
    }

    private suspend fun assertFailure(code: String, block: suspend () -> Any?) {
        try { block(); fail("Expected safe auth error: $code") }
        catch (failure: ChatGptAuthException) { assertEquals(code, failure.code); assertEquals(code, failure.message) }
    }
}
