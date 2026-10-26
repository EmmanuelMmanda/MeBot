package mebot.nguli.com.feed.auth

import android.content.Context
import android.content.res.Configuration
import mebot.nguli.com.R
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * The official public-client flow. A pending browser login never replaces the active account.
 * The application explicitly activates a validated account after its own connection checks.
 */
open class ChatGptAuthManager internal constructor(
    private val store: ChatGptCredentialStore,
    private val transport: ChatGptAuthTransport,
    private val nowMillis: () -> Long,
    private val callbackCopy: ChatGptCallbackCopy,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val signInTimeoutMillis: Long = 10 * 60_000L,
    private val callbackCopyProvider: (suspend () -> ChatGptCallbackCopy)? = null,
    private val callbackFactory: (ChatGptCallbackCopy) -> LoopbackChatGptCallback = { LoopbackChatGptCallback(it) },
) {
    constructor(context: Context, language: suspend () -> String = { "system" }) : this(
        EncryptedChatGptStore(context), SecureChatGptAuthTransport(), System::currentTimeMillis,
        browserCopy(context), callbackCopyProvider = {
            val selected = language()
            val localized = if (selected in setOf("en", "sw")) context.createConfigurationContext(
                Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(selected)) }) else context
            browserCopy(localized)
        },
    )

    private val mutableState = MutableStateFlow(ChatGptAuthState())
    open val state: StateFlow<ChatGptAuthState> = mutableState.asStateFlow()
    private val storageMutex = Mutex()
    private val signInMutex = Mutex()
    private val sessionMutexes = ConcurrentHashMap<String, Mutex>()
    private val verifier = ChatGptIdTokenVerifier(transport, nowMillis)
    private val random = SecureRandom()
    private val lifecycle = AtomicLong(0)
    private val signInCancellation = AtomicLong(0)
    private val diagnosticSequence = AtomicLong(0)
    private var privateState: ChatGptPrivateState? = null
    private val pendingMonitor = Any()
    @Volatile private var pending: PendingSignIn? = null

    private class PendingSignIn(
        val id: String,
        val listener: LoopbackChatGptCallback,
        val result: Deferred<ChatGptAccount>,
    ) {
        override fun toString() = "PendingSignIn([redacted])"
    }

    open suspend fun initialize() = withContext(Dispatchers.IO) {
        storageMutex.withLock {
            if (privateState != null) return@withLock
            val existing = store.read()
            val saved = existing ?: ChatGptPrivateState(hostId = "urn:uuid:${UUID.randomUUID()}")
            if (saved.version != 1 || !saved.hostId.startsWith("urn:uuid:") || saved.hostId.length > 80 ||
                saved.accounts.size > 20 || saved.accounts.map { it.id }.distinct().size != saved.accounts.size)
                throw ChatGptAuthException("chatgpt_storage")
            // Host identity exists durably before any browser request can be made.
            if (existing == null) store.write(saved)
            privateState = saved
            publish(saved)
        }
    }

    open suspend fun beginSignIn(accountId: String? = null): String = withContext(Dispatchers.IO) {
        signInMutex.withLock {
            initialize()
            cancelSignIn()
            val cancellationEpoch = signInCancellation.get()
            val before = snapshot()
            val selected = accountId?.let { id -> before.accounts.find { it.id == id } ?: throw ChatGptAuthException("chatgpt_reauth") }
            if (selected == null && before.accounts.size >= 20) throw ChatGptAuthException("chatgpt_account_limit")
            val copy = callbackCopyProvider?.invoke() ?: callbackCopy
            val listener = try { callbackFactory(copy) }
                catch (_: Exception) { throw ChatGptAuthException("chatgpt_loopback") }
            val expectedState = randomValue()
            val nonce = randomValue()
            val pkce = randomValue()
            val challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256")
                .digest(pkce.toByteArray(Charsets.US_ASCII)))
            val clientId = selected?.clientId ?: ChatGptEndpoints.DYNAMIC_CLIENT
            val parameters = linkedMapOf(
                "client_id" to clientId,
                "ext_agent_host_id" to before.hostId,
                "response_type" to "code",
                "redirect_uri" to listener.redirectUri,
                "scope" to ChatGptEndpoints.SCOPES,
                "resource" to ChatGptEndpoints.RESOURCE,
                "state" to expectedState,
                "nonce" to nonce,
                "code_challenge_method" to "S256",
                "code_challenge" to challenge,
            )
            if (selected == null) parameters["agent_name_hint"] = "MeBot"
            else {
                selected.idToken.takeIf { it.isNotBlank() }?.let { parameters["id_token_hint"] = it }
                selected.email.takeIf { it.isNotBlank() }?.let { parameters["login_hint"] = it }
                if (!selected.publicAccount().planEnabled && selected.subject.isNotBlank()) parameters["prompt"] = "consent"
            }
            val attemptId = UUID.randomUUID().toString()
            val epoch = lifecycle.get()
            val result = scope.async(start = CoroutineStart.LAZY) {
                try {
                    withTimeoutOrNull(signInTimeoutMillis) {
                        val callback = listener.await(expectedState)
                        ensureAttempt(attemptId, epoch)
                        mutableState.update { if (isCurrent(attemptId)) it.copy(phase = "verifying", errorCode = null) else it }
                        callback["error"]?.let { error ->
                            throw ChatGptAuthException(if (error == "access_denied") "chatgpt_cancelled" else "chatgpt_invalid_response")
                        }
                        val code = callback["code"]?.takeIf { it.length in 1..4096 } ?: throw ChatGptAuthException("chatgpt_invalid_response")
                        val issuedClientId = callback["client_id"] ?: selected?.clientId
                            ?: throw ChatGptAuthException("chatgpt_invalid_response")
                        if (!validClientId(issuedClientId) || (selected != null && issuedClientId != selected.clientId))
                            throw ChatGptAuthException("chatgpt_identity_invalid")
                        val existingRegistration = snapshot().accounts.find { it.clientId == issuedClientId }
                        val registrationId = selected?.id ?: existingRegistration?.id ?: registrationId(issuedClientId)
                        val provisional = selected ?: existingRegistration ?: ChatGptCredentialRecord(id = registrationId, clientId = issuedClientId)
                        // Preserve a freshly issued registration even if its single-use code expires.
                        updatePrivate {
                            ensureAttempt(attemptId, epoch)
                            val existing = it.accounts.find { record -> record.id == registrationId }
                            if (existing != null && existing.clientId != issuedClientId) throw ChatGptAuthException("chatgpt_identity_invalid")
                            if (existing == null) it.copy(accounts = it.accounts + provisional) else it
                        }
                        val response = transport.request(ChatGptEndpoints.TOKEN, mapOf(
                            "grant_type" to "authorization_code", "client_id" to issuedClientId, "code" to code,
                            "code_verifier" to pkce, "redirect_uri" to listener.redirectUri, "resource" to ChatGptEndpoints.RESOURCE,
                        ))
                        if (response.status != 200) throw authFailure(response)
                        val body = parseAuthJson(response.body)
                        val idToken = body.string("id_token") ?: throw ChatGptAuthException("chatgpt_identity_invalid")
                        val identity = verifier.verify(idToken, issuedClientId, nonce, provisional.subject.takeIf { it.isNotBlank() })
                        val tokens = tokenRecord(body, provisional, identity, initial = true).copy(models = emptyList(), modelsUpdatedAtMillis = 0)
                        ensureAttempt(attemptId, epoch)
                        // Sign-in is complete only after identity verification and durable token storage.
                        // Model discovery and inference are separate connection stages, owned by the engine.
                        updatePrivate {
                            ensureAttempt(attemptId, epoch)
                            val existing = it.accounts.find { account -> account.id == registrationId }
                            if (existing?.subject?.isNotBlank() == true && existing.subject != identity.subject)
                                throw ChatGptAuthException("chatgpt_identity_invalid")
                            it.copy(accounts = it.accounts.filterNot { record -> record.id == registrationId } + tokens)
                        }
                        tokens.publicAccount()
                    } ?: throw ChatGptAuthException("chatgpt_timeout")
                } catch (cancel: CancellationException) { throw cancel }
                catch (error: ChatGptAuthException) {
                    mutableState.update { if (isCurrent(attemptId)) it.copy(errorCode = error.code,
                        lastDiagnostic = error.diagnostic ?: ChatGptDiagnostic("sign_in")) else it }
                    throw error
                } catch (_: Exception) {
                    mutableState.update { if (isCurrent(attemptId)) it.copy(errorCode = "chatgpt_invalid_response",
                        lastDiagnostic = ChatGptDiagnostic("sign_in")) else it }
                    throw ChatGptAuthException("chatgpt_invalid_response")
                } finally {
                    listener.close()
                    mutableState.update { if (isCurrent(attemptId)) it.copy(phase = "idle") else it }
                }
            }
            val callingJob = currentCoroutineContext().job
            synchronized(pendingMonitor) {
                if (!callingJob.isActive || cancellationEpoch != signInCancellation.get()) {
                    listener.close(); result.cancel(); throw CancellationException()
                }
                pending = PendingSignIn(attemptId, listener, result)
                mutableState.update { it.copy(phase = "waiting", errorCode = null, lastDiagnostic = null) }
                result.start()
            }
            // This URL can contain an ID-token hint. Callers must never persist or log it.
            ChatGptEndpoints.AUTHORIZE + "?" + parameters.entries.joinToString("&") { encodeForm(it.key) + "=" + encodeForm(it.value) }
        }
    }

    open suspend fun awaitSignIn(): ChatGptAccount {
        val attempt = pending ?: throw ChatGptAuthException("chatgpt_cancelled")
        return try { attempt.result.await() }
        catch (cancel: CancellationException) { if (isCurrent(attempt.id)) cancelSignIn(); throw cancel }
    }

    open fun cancelSignIn() {
        val attempt = synchronized(pendingMonitor) {
            signInCancellation.incrementAndGet()
            diagnosticSequence.incrementAndGet()
            pending.also { pending = null; mutableState.update { it.copy(phase = "idle", errorCode = null, lastDiagnostic = null) } }
        }
        attempt?.listener?.close()
        attempt?.result?.cancel()
    }

    open suspend fun activateAccount(id: String) {
        initialize()
        val epoch = lifecycle.get()
        models(id, refresh = true)
        updatePrivate {
            if (epoch != lifecycle.get()) throw ChatGptAuthException("chatgpt_cancelled")
            val record = it.accounts.find { account -> account.id == id } ?: throw ChatGptAuthException("chatgpt_reauth")
            if (!record.publicAccount().connected) throw ChatGptAuthException("chatgpt_reauth")
            if (!record.publicAccount().planEnabled) throw ChatGptAuthException("chatgpt_permission")
            it.copy(activeAccountId = id)
        }
    }

    open suspend fun deactivateAccount() { initialize(); updatePrivate { it.copy(activeAccountId = null) } }

    open suspend fun accessToken(id: String): String {
        initialize()
        return sessionMutex(id).withLock { validTokenLocked(id) }
    }

    private suspend fun validTokenLocked(id: String): String {
        val record = account(id)
        if (!record.publicAccount().connected) throw ChatGptAuthException("chatgpt_reauth")
        if (!record.publicAccount().planEnabled) throw ChatGptAuthException("chatgpt_permission")
        val now = nowMillis()
        if (now < record.expiresAtMillis - 60_000) return record.accessToken
        if (now < record.earliestRefreshAtMillis) {
            if (now < record.expiresAtMillis - 5_000) return record.accessToken
            throw ChatGptAuthException("chatgpt_refresh_wait")
        }
        if (record.refreshToken.isBlank()) throw ChatGptAuthException("chatgpt_reauth")
        val epoch = lifecycle.get()
        // A caller cancelling a feed refresh must not strand an already-rotated refresh token.
        // Each transport operation is size/time bounded; reset still wins through the epoch guard.
        return withContext(NonCancellable) { try {
            val response = transport.request(ChatGptEndpoints.TOKEN, mapOf(
                "grant_type" to "refresh_token", "client_id" to record.clientId,
                "refresh_token" to record.refreshToken, "resource" to ChatGptEndpoints.RESOURCE,
            ))
            if (response.status != 200) throw authFailure(response, "refresh")
            val body = parseAuthJson(response.body)
            val identity = body.string("id_token")?.let { verifier.verify(it, record.clientId, null, record.subject) }
                ?: VerifiedChatGptIdentity(record.issuer, record.subject, record.email, record.name)
            val replacement = tokenRecord(body, record, identity, initial = false)
            updatePrivate {
                if (epoch != lifecycle.get() || it.accounts.find { a -> a.id == id }?.refreshToken != record.refreshToken)
                    throw ChatGptAuthException("chatgpt_cancelled")
                it.copy(accounts = it.accounts.map { old -> if (old.id == id) replacement else old })
            }
            if (!replacement.publicAccount().planEnabled) throw ChatGptAuthException("chatgpt_permission")
            replacement.accessToken
        } catch (failure: ChatGptAuthException) {
            if (failure.code == "chatgpt_reauth") clearSessionLocked(id)
            if (epoch == lifecycle.get()) mutableState.update { it.copy(lastDiagnostic = failure.diagnostic ?: ChatGptDiagnostic("refresh")) }
            throw failure
        } }
    }

    open suspend fun models(id: String, refresh: Boolean = false): List<ChatGptModel> {
        initialize()
        return sessionMutex(id).withLock {
            val epoch = lifecycle.get()
            val request = diagnosticSequence.incrementAndGet()
            mutableState.update { it.copy(lastDiagnostic = null, errorCode = null) }
            try {
                val record = account(id)
                if (!record.publicAccount().connected) throw ChatGptAuthException("chatgpt_reauth")
                if (!record.publicAccount().planEnabled) throw ChatGptAuthException("chatgpt_permission")
                if (!refresh && record.models.isNotEmpty() && nowMillis() - record.modelsUpdatedAtMillis in 0..3_600_000)
                    return@withLock record.models
                val token = validTokenLocked(id)
                val catalog = fetchModels(token)
                updatePrivate {
                    if (epoch != lifecycle.get() || it.accounts.find { a -> a.id == id }?.accessToken != token)
                        throw ChatGptAuthException("chatgpt_cancelled")
                    it.copy(accounts = it.accounts.map { old -> if (old.id == id) old.copy(models = catalog, modelsUpdatedAtMillis = nowMillis()) else old })
                }
                catalog
            } catch (failure: ChatGptAuthException) {
                if (epoch == lifecycle.get() && request == diagnosticSequence.get() && failure.code != "chatgpt_cancelled")
                    mutableState.update { it.copy(lastDiagnostic = failure.diagnostic ?: ChatGptDiagnostic("models")) }
                throw failure
            }
        }
    }

    open suspend fun acknowledgePlanNotice(id: String) {
        initialize()
        updatePrivate { it.copy(accounts = it.accounts.map { old -> if (old.id == id) old.copy(planNoticeAcknowledged = true) else old }) }
    }

    /** A late result from a removed or replaced credential cannot overwrite current support details. */
    open suspend fun recordInferenceDiagnostic(id: String, expectedAccessToken: String, diagnostic: ChatGptDiagnostic) {
        if (expectedAccessToken.isBlank()) return
        initialize()
        storageMutex.withLock {
            if (privateState?.accounts?.any { it.id == id && it.accessToken == expectedAccessToken } != true) return@withLock
            mutableState.update { it.copy(lastDiagnostic = diagnostic.copy(stage = "inference")) }
        }
    }

    open suspend fun invalidateSession(id: String, expectedAccessToken: String? = null): Boolean {
        initialize()
        return sessionMutex(id).withLock { clearSessionLocked(id, expectedAccessToken) }
    }

    private suspend fun clearSessionLocked(id: String, expectedAccessToken: String? = null): Boolean {
        var cleared = false
        updatePrivate {
            val account = it.accounts.find { account -> account.id == id }
            if (account == null || (expectedAccessToken != null && account.accessToken != expectedAccessToken)) it
            else {
                cleared = true
                it.copy(activeAccountId = it.activeAccountId?.takeUnless { active -> active == id },
                    accounts = it.accounts.map { old -> if (old.id == id) old.withoutCredentials() else old })
            }
        }
        return cleared
    }

    /** Local sign-out always completes. False means the user should also disconnect in ChatGPT settings. */
    open suspend fun signOut(id: String): Boolean {
        cancelSignIn()
        initialize()
        return sessionMutex(id).withLock {
            val record = account(id)
            var revoked = record.refreshToken.isBlank()
            try {
                if (record.refreshToken.isNotBlank()) {
                    val endpoint = ChatGptIdTokenVerifier.trustedAuthUrl(verifier.discovery().string("revocation_endpoint"))
                    for (attempt in 0..1) {
                        val response = try { transport.request(endpoint, mapOf("token" to record.refreshToken,
                            "token_type_hint" to "refresh_token", "client_id" to record.clientId)) }
                            catch (cancel: CancellationException) { throw cancel }
                            catch (_: ChatGptAuthException) { null }
                        if (response?.status == 200) { revoked = true; break }
                        if (attempt == 0 && (response == null || response.status in 500..599)) delay(400)
                        else break
                    }
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { /* The local decision to sign out still takes effect. */ }
            finally { withContext(NonCancellable) { clearSessionLocked(id) } }
            revoked
        }
    }

    open suspend fun clearAll() = withContext(Dispatchers.IO) {
        lifecycle.incrementAndGet()
        cancelSignIn()
        storageMutex.withLock {
            store.clear()
            privateState = null
            mutableState.value = ChatGptAuthState()
        }
    }

    private suspend fun fetchModels(token: String): List<ChatGptModel> {
        try {
            val response = transport.request(ChatGptEndpoints.MODELS, bearer = token)
            if (response.status != 200) throw authFailure(response, "models")
            return ChatGptModelCatalog.parse(response)
        } catch (failure: ChatGptAuthException) {
            // A catalog failure cannot invalidate the identity we have already verified.
            if (failure.code == "chatgpt_invalid_response")
                throw ChatGptAuthException("chatgpt_models", failure.diagnostic ?: ChatGptDiagnostic("models"))
            throw failure
        }
    }

    private fun tokenRecord(body: JsonObject, previous: ChatGptCredentialRecord, identity: VerifiedChatGptIdentity, initial: Boolean): ChatGptCredentialRecord {
        fun token(name: String, maximum: Int): String = body.string(name)?.takeIf { it.length in 1..maximum && it.none(Char::isISOControl) }
            ?: throw ChatGptAuthException("chatgpt_invalid_response")
        val access = token("access_token", 32768)
        val refresh = body.string("refresh_token")?.let { token("refresh_token", 16384) }.orEmpty()
        if (!initial && refresh.isBlank()) throw ChatGptAuthException("chatgpt_invalid_response")
        if (!body.string("token_type").equals("Bearer", ignoreCase = true)) throw ChatGptAuthException("chatgpt_invalid_response")
        val expirySeconds = (body["expires_in"] as? JsonPrimitive)?.longOrNull?.takeIf { it in 1..86_400 }
            ?: throw ChatGptAuthException("chatgpt_invalid_response")
        val scopeText = body.string("scope")
        if (initial && scopeText == null) throw ChatGptAuthException("chatgpt_invalid_response")
        if (scopeText != null && scopeText.length > 4096) throw ChatGptAuthException("chatgpt_invalid_response")
        val granted = scopeText?.split(' ')?.filter { it.isNotBlank() }?.toSet() ?: previous.scopes
        if (granted.size > 64 || granted.any { it.length > 128 || it.any(Char::isISOControl) })
            throw ChatGptAuthException("chatgpt_invalid_response")
        if ("offline_access" in granted && refresh.isBlank()) throw ChatGptAuthException("chatgpt_invalid_response")
        val now = nowMillis()
        val earliest = parseEarliestRefresh(body["earliest_refresh_at"], now)
        return previous.copy(issuer = identity.issuer, subject = identity.subject, email = identity.email, name = identity.name,
            accessToken = access, refreshToken = refresh, idToken = body.string("id_token") ?: previous.idToken,
            expiresAtMillis = now + expirySeconds * 1000, earliestRefreshAtMillis = earliest, scopes = granted)
    }

    private suspend fun snapshot(): ChatGptPrivateState = storageMutex.withLock { privateState ?: throw ChatGptAuthException("chatgpt_storage") }
    private suspend fun account(id: String) = snapshot().accounts.find { it.id == id } ?: throw ChatGptAuthException("chatgpt_reauth")
    private fun sessionMutex(id: String) = sessionMutexes.getOrPut(id) { Mutex() }
    private suspend fun updatePrivate(transform: (ChatGptPrivateState) -> ChatGptPrivateState) = withContext(Dispatchers.IO) {
        storageMutex.withLock {
            val before = privateState ?: throw ChatGptAuthException("chatgpt_storage")
            val next = transform(before)
            if (next != before) store.write(next)
            privateState = next
            publish(next)
        }
    }
    private fun publish(saved: ChatGptPrivateState) {
        mutableState.update { it.copy(accounts = saved.accounts.map(ChatGptCredentialRecord::publicAccount), activeAccountId = saved.activeAccountId) }
    }
    private fun randomValue() = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))
    private fun isCurrent(id: String) = pending?.id == id
    private fun ensureAttempt(id: String, epoch: Long) {
        if (!isCurrent(id) || lifecycle.get() != epoch) throw ChatGptAuthException("chatgpt_cancelled")
    }

    companion object {
        private fun browserCopy(context: Context) = ChatGptCallbackCopy(
            context.getString(R.string.chatgpt_browser_title), context.getString(R.string.chatgpt_browser_body),
            context.getString(R.string.chatgpt_browser_invalid), context.getString(R.string.chatgpt_browser_return), context.packageName,
            context.getString(R.string.chatgpt_browser_manual_return), context.resources.configuration.locales[0].language)
        private fun validClientId(value: String) = value != ChatGptEndpoints.DYNAMIC_CLIENT && value.length in 1..256 && value.all { it.code in 0x21..0x7e }
        internal fun registrationId(clientId: String): String = MessageDigest.getInstance("SHA-256")
            .digest((ChatGptEndpoints.ISSUER + "\n" + clientId).toByteArray(Charsets.UTF_8))
            .take(16).joinToString("") { "%02x".format(it) }

        /** Conservative absolute timestamp parsing; unknown formats fail instead of guessing a relative delay. */
        internal fun parseEarliestRefresh(value: JsonElement?, now: Long): Long {
            if (value == null || value is JsonNull) return 0
            val primitive = value as? JsonPrimitive ?: throw ChatGptAuthException("chatgpt_invalid_response")
            val text = primitive.content
            val parsed = text.toLongOrNull()?.let {
                if (it !in 0..Long.MAX_VALUE / 1000) throw ChatGptAuthException("chatgpt_invalid_response")
                it * 1000
            } ?: runCatching { Instant.parse(text).toEpochMilli() }.getOrElse { throw ChatGptAuthException("chatgpt_invalid_response") }
            if (parsed < 0 || parsed > now + 31L * 86_400_000) throw ChatGptAuthException("chatgpt_invalid_response")
            return parsed
        }
    }
}
