package app.mynguli.feed

import app.mynguli.core.*
import app.mynguli.feed.auth.ChatGptAuthException
import app.mynguli.feed.auth.ChatGptAuthManager
import app.mynguli.feed.auth.ChatGptDiagnostic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicLong

data class RefreshOutcome(val error: String? = null, val notice: String? = null, val newItems: Int = 0)

open class FeedEngine(
    private val repository: FeedRepository,
    private val vault: SecretVault,
    private val sources: SourceReader = RssSourceReader(),
    private val provider: AiProvider = OpenAiFeedProvider(),
    private val language: suspend () -> String = { "en" },
    private val chatGpt: ChatGptAuthManager? = null,
    private val discovery: PublicDiscovery? = null,
    private val enricher: FeedEnricher? = null,
) {
    private val refreshLock = Mutex()
    private val credentialsLock = Mutex()
    private val credentialGeneration = AtomicLong(0)

    private fun samePersonalization(a: FeedState, b: FeedState): Boolean =
        a.context == b.context && a.name == b.name && a.region == b.region && a.timezone == b.timezone

    open suspend fun connect(connection: AiConnection, secret: String) = credentialsLock.withLock {
        if (connection.mode == "chatgpt") throw FeedFailure("invalid_input")
        FeedLogic.validate(FeedState(connection = connection))
        if (secret.length !in 8..4096 || secret.contains('\n')) throw FeedFailure("invalid_input")
        reserveRequest() // An explicit model check is inference in every AI connection mode.
        provider.check(connection, secret)
        val previous = vault.read()
        credentialGeneration.incrementAndGet()
        // Verify first. Roll back the secret if durable configuration cannot be committed.
        withContext(NonCancellable) {
            try {
                vault.write(secret)
                repository.update { it.copy(connection = connection, aiPauseCode = "") }
            } catch (error: Exception) {
                if (previous == null) vault.clear() else vault.write(previous)
                throw error
            }
        }
    }

    open suspend fun connectChatGpt(accountId: String, model: String? = null) = credentialsLock.withLock {
        val manager = chatGpt ?: throw FeedFailure("chatgpt_reauth")
        credentialGeneration.incrementAndGet()
        try {
            val choices = manager.models(accountId, refresh = true)
            val preferred = repository.current().connection.model
            val selected = if (model == null) choices.find { it.slug == preferred } ?: choices.firstOrNull()
                else choices.find { it.slug == model }
            if (selected == null) throw FeedFailure("chatgpt_models")
            val connection = AiConnection(mode = "chatgpt", model = selected.slug, accountId = accountId)
            FeedLogic.validate(FeedState(connection = connection))
            val token = manager.accessToken(accountId)
            reserveRequest() // A connection check is a real, small request and counts toward the local cap.
            try { provider.check(connection, token) }
            catch (failure: FeedFailure) {
                currentCoroutineContext().ensureActive()
                manager.recordInferenceDiagnostic(accountId, token, failure.diagnostic ?: ChatGptDiagnostic("inference"))
                throw failure
            }
            currentCoroutineContext().ensureActive()
            withContext(NonCancellable) {
                repository.update { it.copy(connection = connection, aiPauseCode = "") }
            }
        } catch (error: ChatGptAuthException) { throw FeedFailure(error.code) }
    }

    /** Invalidates in-flight results before changing any credential or selected connection. */
    open suspend fun disconnect() = credentialsLock.withLock {
        credentialGeneration.incrementAndGet()
        val connection = repository.current().connection
        if (connection.mode == "chatgpt" && connection.accountId.isNotBlank()) chatGpt?.signOut(connection.accountId)
        else vault.clear()
        repository.update { it.copy(aiPauseCode = "") }
        Unit
    }

    open suspend fun disconnectChatGpt(accountId: String): Boolean = credentialsLock.withLock {
        if (repository.current().connection.accountId == accountId) credentialGeneration.incrementAndGet()
        val revoked = chatGpt?.signOut(accountId) ?: true
        repository.update { if (it.connection.accountId == accountId) it.copy(aiPauseCode = "") else it }
        revoked
    }

    open suspend fun clearAllCredentials() = credentialsLock.withLock {
        credentialGeneration.incrementAndGet()
        withContext(NonCancellable) {
            vault.clear()
            chatGpt?.clearAll()
        }
    }

    private suspend fun credential(connection: AiConnection): String? = try {
        if (connection.mode == "chatgpt") {
            if (connection.accountId.isBlank()) throw FeedFailure("chatgpt_reauth")
            chatGpt?.accessToken(connection.accountId) ?: throw FeedFailure("chatgpt_reauth")
        } else vault.read()
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (error: ChatGptAuthException) { throw FeedFailure(error.code) }

    private suspend fun recordFailure(connection: AiConnection, generation: Long, code: String, token: String, diagnostic: ChatGptDiagnostic? = null): String {
        if (connection.mode != "chatgpt") return code
        return credentialsLock.withLock {
            if (credentialGeneration.get() != generation || repository.current().connection != connection) return@withLock code
            if (diagnostic != null) chatGpt?.recordInferenceDiagnostic(connection.accountId, token, diagnostic)
            if (code !in pausedErrors) return@withLock code
            if (code == "chatgpt_reauth" && chatGpt?.invalidateSession(connection.accountId, expectedAccessToken = token) == false &&
                chatGpt.state.value.accounts.any { it.id == connection.accountId && it.connected })
                return@withLock "chatgpt_interrupted"
            repository.update { it.copy(aiPauseCode = code) }
            code
        }
    }

    private suspend fun reserveRequest(): FeedState = repository.update { state ->
        RequestBudget.reserve(state) ?: throw FeedFailure("limit_reached")
    }

    /** Explicit searches share one request budget, regardless of the number of selected modules. */
    open suspend fun search(query: String, moduleId: String? = null): List<FeedItem> {
        if (query.isBlank() || query.length > 2000 || moduleId?.isBlank() == true) throw FeedFailure("invalid_input")
        fun selected(state: FeedState) = state.modules.filter {
            it.enabled && it.kind !in setOf("weather", "horoscope") && (moduleId == null || it.id == moduleId)
        }
        val snapshot = credentialsLock.withLock {
            val current = repository.current()
            if (selected(current).isEmpty()) throw FeedFailure("invalid_input")
            val secret = credential(current.connection) ?: throw FeedFailure("unauthorized")
            Triple(secret, credentialGeneration.get(), reserveRequest())
        }
        val secret = snapshot.first; val generation = snapshot.second; val current = snapshot.third
        val modules = selected(current)
        if (modules.isEmpty()) throw FeedFailure("invalid_input")
        val allowed = modules.associateBy { it.id }
        val items = try { provider.search(current, modules, query.trim(), secret, language()) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: FeedFailure) {
            throw FeedFailure(recordFailure(current.connection, generation, error.code, secret, error.diagnostic), error.diagnostic)
        }
        currentCoroutineContext().ensureActive()
        val latest = repository.current()
        if (credentialGeneration.get() != generation || latest.connection != current.connection ||
            !samePersonalization(latest, current) || selected(latest).associateBy { it.id } != allowed) throw FeedFailure("invalid_input")
        if (items.size > 8 || items.any { item ->
            val module = allowed[item.moduleId]
            module == null || item.kind != module.kind || !FeedLogic.safeUrl(item.sourceUrl) ||
                item.citations.none { FeedLogic.canonicalUrl(it.url) == FeedLogic.canonicalUrl(item.sourceUrl) } ||
                runCatching { FeedLogic.validateItem(item) }.isFailure
        }) throw FeedFailure("invalid_response")
        if (latest.aiPauseCode.isNotBlank()) repository.update { state ->
            if (credentialGeneration.get() == generation && state.connection == current.connection) state.copy(aiPauseCode = "") else state
        }
        return items.distinctBy { it.id }
    }

    open suspend fun refresh(allowPlanRetry: Boolean = false): RefreshOutcome = refreshLock.withLock {
        val startingGeneration = credentialGeneration.get()
        val initial = repository.current()
        val now = System.currentTimeMillis()
        val beforeIds = initial.items.map { it.id }.toSet()
        val active = initial.modules.filter { it.enabled }
        val languageCode = language()
        var success = 0
        var failure: String? = null
        val fetchedIds = mutableSetOf<String>()
        val aiServed = mutableSetOf<String>()
        val publicRead = mutableSetOf<String>()

        // Resolve the selected connection before choosing discovery sources. A paused or
        // unavailable plan never switches to a stored API credential.
        val credential = credentialsLock.withLock {
            val latest = repository.current()
            val secret = if (latest.connection != initial.connection) null
            else if (latest.connection.mode == "chatgpt" && latest.aiPauseCode.isNotBlank() && !allowPlanRetry) {
                failure = latest.aiPauseCode; null
            } else try { credential(latest.connection) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { failure = (error as? FeedFailure)?.code ?: "unauthorized"; null }
            secret to credentialGeneration.get()
        }
        val secret = credential.first
        val generation = credential.second

        suspend fun acceptPublic(items: List<FeedItem>) {
            items.forEach(FeedLogic::validateItem)
            repository.update { current ->
                if (credentialGeneration.get() != startingGeneration || !samePersonalization(current, initial)) current
                else {
                    val unchanged = current.modules.filter { it.enabled && it in initial.modules }.map { it.id }.toSet()
                    val eligible = items.filter { it.moduleId in unchanged }.map { item ->
                        val module = initial.modules.firstOrNull { it.id == item.moduleId }
                        val interests = module?.let { FeedLogic.scopedContext(initial, it) }.orEmpty()
                            .joinToString(" ") { it.text }.lowercase().split(Regex("[^\\p{L}\\p{N}]+"))
                            .filter { it.length >= 4 && it !in setOf("with", "that", "this", "from", "about", "have", "want", "interested") }.toSet()
                        val body = (item.title + " " + item.summary).lowercase()
                        item.copy(relevance = if (item.weather != null) item.relevance else
                            (item.relevance + interests.count { body.contains(it) }.times(4).coerceAtMost(20)).coerceAtMost(75))
                    }
                    fetchedIds += eligible.map { it.id }
                    FeedLogic.merge(current, eligible, now)
                }
            }
        }

        suspend fun readPublic(modules: List<FeedModule>, includeRss: Boolean) = coroutineScope {
            val semaphore = Semaphore(3)
            val calls = mutableListOf<suspend () -> List<FeedItem>>()
            modules.filter { discovery != null && it.kind in PublicDiscoveryProvider.supportedKinds && publicRead.add(it.id) }.forEach { module ->
                calls += { discovery!!.discover(initial, module, languageCode, now).onEach {
                    FeedLogic.validateItem(it)
                    require(it.moduleId == module.id && it.kind == module.kind)
                } }
            }
            if (includeRss) modules.flatMap { module -> module.sources.map { module to it } }.take(24).forEach { (module, source) ->
                calls += { sources.read(source, module, now).onEach {
                    FeedLogic.validateItem(it)
                    require(it.moduleId == module.id && it.kind == module.kind)
                } }
            }
            val results = Channel<Result<List<FeedItem>>>(Channel.BUFFERED)
            calls.forEach { request -> launch {
                val result = semaphore.withPermit {
                    try { Result.success(request()) }
                    catch (cancelled: CancellationException) { results.close(cancelled); throw cancelled }
                    catch (error: Exception) { Result.failure<List<FeedItem>>(error) }
                }
                results.send(result)
            } }
            // Consume completion order: a slow optional RSS source cannot hold back
            // a completed weather, repository or current-story result.
            repeat(calls.size) {
                val result = results.receive()
                val items = result.getOrNull()
                if (items != null) {
                    acceptPublic(items)
                    success++
                } else failure = failure ?: (result.exceptionOrNull() as? FeedFailure)?.code ?: "provider_error"
            }
            results.close()
        }

        // Current weather and open-source discoveries use their own public APIs.
        // RSS is a fallback for disconnected modules, never a prerequisite for AI.
        readPublic(if (secret == null) active else active.filter { it.kind in setOf("weather", "github") }, includeRss = secret == null)
        if (credentialGeneration.get() != startingGeneration) return@withLock RefreshOutcome()
        if (secret != null) {
            // Serve least recently refreshed modules first so a local request cap cannot
            // indefinitely starve modules lower in the user's catalog.
            val ordered = active.filter { it.kind != "weather" }.sortedBy { module ->
                initial.items.filter { it.moduleId == module.id && it.aiGenerated }.maxOfOrNull { it.fetchedAtMillis } ?: 0L
            }
            val batches = (0 until initial.prefetchBatches).flatMap { ordered }
            for (module in batches) {
                if (module.kind == "horoscope" && module.zodiacSign.isBlank()) continue
                val current = repository.current()
                if (credentialGeneration.get() != generation || !samePersonalization(current, initial) || current.modules != initial.modules || current.connection != initial.connection) break
                var requestSecret = secret
                try {
                    if (initial.connection.mode == "chatgpt") requestSecret = credentialsLock.withLock {
                        if (credentialGeneration.get() != generation || repository.current().connection != initial.connection) null
                        else credential(initial.connection)
                    } ?: break
                    val reserved = reserveRequest()
                    if (credentialGeneration.get() != generation) break
                    val items = AiActivityRegistry.run(module.id, module.kind, "discovery") { provider.feed(reserved, module, requestSecret, languageCode) }
                    items.forEach { FeedLogic.validateItem(it); require(it.moduleId == module.id && it.kind == module.kind) }
                    repository.update { latest ->
                        if (credentialGeneration.get() != generation || !samePersonalization(latest, initial) || latest.modules != initial.modules || latest.connection != initial.connection) latest
                        else {
                            fetchedIds += items.map { it.id }
                            FeedLogic.merge(latest, items, System.currentTimeMillis()).copy(lastAiRefreshMillis = System.currentTimeMillis(), aiPauseCode = "")
                        }
                    }
                    if (items.isNotEmpty()) aiServed += module.id
                    success++
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    failure = (error as? FeedFailure)?.code ?: "invalid_response"
                    failure = recordFailure(initial.connection, generation, failure!!, requestSecret, (error as? FeedFailure)?.diagnostic)
                    operationDiagnostic("discovery", error, module.id)
                    if (failure in stopErrors) break
                }
            }
            if (credentialGeneration.get() == generation && samePersonalization(repository.current(), initial)) {
                readPublic(active.filter { it.id !in aiServed && it.kind != "horoscope" }, includeRss = true)
            }
        }

        // Show text as soon as it is confirmed, then enrich only the current edition.
        // Optional publisher media never turns a usable article into a failed refresh.
        val snapshot = repository.current()
        val mediaCandidates = FeedLogic.edition(snapshot).filter { it.id in fetchedIds && it.media == null && it.contentType != "weather" }.take(8)
        if (enricher != null && mediaCandidates.isNotEmpty() && credentialGeneration.get() == generation) {
            try {
                val enriched = enricher.enrich(mediaCandidates).onEach(FeedLogic::validateItem).associateBy { it.id }
                repository.update { current ->
                    if (credentialGeneration.get() != generation || !samePersonalization(current, initial) || current.modules != initial.modules) current
                    else current.copy(items = current.items.map { item ->
                        if (mediaCandidates.any { it == item }) enriched[item.id] ?: item else item
                    })
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Images are optional; retain verified source content. */ }
        }
        val current = repository.current()
        val newCount = current.items.count { it.id !in beforeIds }
        when {
            failure != null && success > 0 -> RefreshOutcome(error = failure, notice = "partial_refresh", newItems = newCount)
            failure != null -> RefreshOutcome(error = failure)
            FeedLogic.visible(current).isEmpty() -> RefreshOutcome(notice = "no_results")
            else -> RefreshOutcome(newItems = newCount)
        }
    }

    open suspend fun ask(itemId: String?, question: String): AiAnswer = runAnswer(itemId, null, question, null)

    open suspend fun askConversation(conversationId: String, question: String, onText: (String) -> Unit): AiAnswer =
        runAnswer(null, conversationId, question, onText)

    open suspend fun explain(itemId: String, onText: (String) -> Unit): AiAnswer = runAnswer(itemId, null,
        "Explain this item so I can understand it here. In about 220 words, state what happened or what this resource offers, " +
            "the useful background, and what it changes or what I can do next. Open and verify the linked source using web search where available. " +
            "Cite the sources you actually checked, distinguish reporting from interpretation, and say what remains uncertain. " +
            "If only an excerpt is available, be explicit; never pretend to have read the full article. Paraphrase rather than reproduce it. " +
            "Use my explicitly shared module context only when relevant. For horoscope content, treat it as entertainment without factual predictions.", onText)

    private suspend fun runAnswer(itemId: String?, conversationId: String?, question: String, onText: ((String) -> Unit)?): AiAnswer {
        if (question.isBlank() || question.length > 2000) throw FeedFailure("invalid_input")
        val snapshot = credentialsLock.withLock {
            val current = repository.current()
            if (itemId != null && current.items.none { it.id == itemId }) throw FeedFailure("invalid_input")
            if (conversationId != null && current.conversations.none { it.id == conversationId }) throw FeedFailure("invalid_input")
            val secret = credential(current.connection) ?: throw FeedFailure("unauthorized")
            Triple(secret, credentialGeneration.get(), reserveRequest())
        }
        val secret = snapshot.first; val generation = snapshot.second; val current = snapshot.third
        val savedThread = conversationId?.let { id -> current.conversations.find { it.id == id } ?: throw FeedFailure("invalid_input") }
        val selectedItemId = savedThread?.itemId ?: itemId
        val item = selectedItemId?.let { id -> current.items.find { it.id == id } }
        val thread = savedThread ?: AiConversation(itemId = selectedItemId, moduleId = item?.moduleId)
        val module = thread.moduleId?.let { id -> current.modules.find { it.id == id } }
        val answer = try { AiActivityRegistry.run(selectedItemId ?: savedThread?.id ?: "general", module?.kind ?: item?.kind ?: "general", "answer") {
            if (onText == null) provider.ask(current, item, question.trim(), secret, language())
            else provider.streamConversation(current, item, thread, question.trim(), secret, language()) { text ->
                if (credentialGeneration.get() == generation) onText(text.take(24000))
            }
        } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: FeedFailure) { throw FeedFailure(recordFailure(current.connection, generation, error.code, secret, error.diagnostic), error.diagnostic) }
        if (answer.answer.isBlank() || answer.answer.length > 24000 || answer.citations.size > 20 ||
            answer.citations.any { !FeedLogic.safeUrl(it.url) || it.title.length > 300 }) throw FeedFailure("invalid_response")
        val latest = repository.current()
        if (credentialGeneration.get() != generation || !samePersonalization(latest, current) || latest.connection != current.connection ||
            (module != null && latest.modules.find { it.id == module.id } != module) ||
            (savedThread != null && latest.conversations.find { it.id == savedThread.id } != savedThread)) throw FeedFailure("invalid_input")
        if (latest.aiPauseCode.isNotBlank()) repository.update { state ->
            if (credentialGeneration.get() == generation && state.connection == current.connection) state.copy(aiPauseCode = "") else state
        }
        return answer
    }

    companion object {
        private val pausedErrors = setOf("chatgpt_limit", "chatgpt_permission", "chatgpt_ineligible", "chatgpt_policy", "chatgpt_capability", "chatgpt_reauth")
        private val stopErrors = pausedErrors + setOf("unauthorized", "quota", "rate_limit", "limit_reached", "chatgpt_unavailable", "chatgpt_interrupted", "offline")
    }
}
