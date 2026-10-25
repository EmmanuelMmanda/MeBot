package mebot.nguli.com.feed

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import mebot.nguli.com.MeBotApplication
import mebot.nguli.com.core.*
import mebot.nguli.com.feed.auth.ChatGptAuthException
import mebot.nguli.com.feed.auth.ChatGptAuthState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

data class FeedUiState(
    val state: FeedState? = null, val loading: Boolean = true,
    val refreshing: Boolean = false, val refreshingModuleId: String? = null, val connecting: Boolean = false,
    val connected: Boolean = false, val error: String? = null, val notice: String? = null,
    val asking: Boolean = false, val answer: String? = null,
    val answerCitations: List<FeedCitation> = emptyList(),
    val chatGpt: ChatGptAuthState = ChatGptAuthState(),
    val chatGptVerifying: Boolean = false,
    val question: String? = null,
    val activeConversationId: String? = null,
    val contextSaving: Boolean = false,
    val deliverySaving: Boolean = false,
    val moduleSavingId: String? = null,
    val importWorking: Boolean = false,
    val importPreview: FeedImportPreview? = null,
    val importSummary: String? = null,
    val diagnostics: List<OperationDiagnostic> = emptyList(),
    val detailItemId: String? = null,
    val detailLoading: Boolean = false,
    val detailText: String = "",
    val detailTurns: List<AiTurn> = emptyList(),
    val detailCitations: List<FeedCitation> = emptyList(),
    val detailError: String? = null,
    val searchQuery: String = "",
    val searching: Boolean = false,
    val searchCompleted: Boolean = false,
    val searchResults: List<FeedItem> = emptyList(),
    val searchModuleId: String? = null,
    val searchError: String? = null,
    val activities: List<AiActivity> = emptyList(),
    val refreshProgress: RefreshProgress = RefreshProgress(),
    val providerModels: List<String> = emptyList(),
    val providerModelsLoading: Boolean = false,
    val providerModelError: String? = null,
    val openRouterPending: Boolean = false,
    val providerAuthBusy: Boolean = false,
)

data class FeedImportPreview(val kind: String, val name: String, val entries: Int, val modules: Int, val saved: Int = 0, val conversations: Int = 0)

class FeedViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as MeBotApplication
    private val repository = app.feedRepository
    private val engine = app.feedEngine
    private val chatGpt = app.chatGptAuth
    private val openRouterAuth = mebot.nguli.com.feed.auth.OpenRouterSignIn()
    private var catalogJob: Job? = null
    private var catalogGeneration = 0
    private var providerAuthJob: Job? = null
    private val apiConnected = MutableStateFlow(false)
    private val browserChannel = Channel<String>(Channel.RENDEZVOUS)
    val browserLaunches = browserChannel.receiveAsFlow()
    private val _ui = MutableStateFlow(FeedUiState())
    val ui = _ui.asStateFlow()
    private var refreshJob: Job? = null
    private var askJob: Job? = null
    private var connectJob: Job? = null
    private var importJob: Job? = null
    private var detailJob: Job? = null
    private var searchJob: Job? = null
    private var detailGeneration = 0L
    private var searchGeneration = 0L
    private var importGeneration = 0L
    private var pendingContextImport: PersonalContextImport? = null
    private var pendingBackupImport: FeedState? = null
    private var detailConversationId: String? = null
    private var refreshGeneration = 0L
    private var askGeneration = 0L
    private var connectGeneration = 0L
    private var lastAutomaticAttemptMillis = 0L

    init {
        viewModelScope.launch { engine.progress.collect { p -> _ui.update { it.copy(refreshProgress=p) } } }
        viewModelScope.launch { AiActivityRegistry.state.collect { tasks -> _ui.update { it.copy(activities = tasks.values.toList()) } } }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { repository.applyOptionalSeed(app) }
                apiConnected.value = withContext(Dispatchers.IO) { runCatching { app.feedSecrets.read() != null }.getOrDefault(false) }
                // A damaged AI vault cannot prevent access to alarms, cached reading or local settings.
                val authError = try { withContext(Dispatchers.IO) { chatGpt.initialize() }; null }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (e: Exception) { (e as? ChatGptAuthException)?.code ?: "chatgpt_storage" }
                combine(repository.state, chatGpt.state, apiConnected) { state, auth, hasKey -> Triple(state, auth, hasKey) }
                    .collect { (state, auth, hasKey) ->
                        val selected = state.connection.takeIf { it.mode == "chatgpt" }?.accountId
                        val account = auth.accounts.find { it.id == selected }
                        _ui.update { it.copy(state = state, loading = false,
                            connected = if (state.connection.mode == "chatgpt") account?.let { a -> a.connected && a.planEnabled } == true else hasKey,
                            chatGpt = auth.copy(activeAccountId = selected),
                            error = it.error ?: authError.takeIf { state.connection.mode == "chatgpt" && auth.accounts.isEmpty() }
                                ?: state.aiPauseCode.takeIf { code -> code.isNotBlank() }) }
                    }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { _ui.update { it.copy(error = "storage_error", loading = false) } }
        }
    }

    private fun work(action: suspend () -> Unit) = viewModelScope.launch {
        try { action() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (e: Exception) { _ui.update { it.copy(error = failureCode(e, "storage_error")) } }
    }

    private fun failureCode(error: Exception, fallback: String = "provider_error") = when (error) {
        is FeedFailure -> error.code
        is ChatGptAuthException -> error.code
        is IllegalArgumentException -> "invalid_input"
        else -> fallback
    }

    fun clearMessage() { _ui.update { it.copy(error = null, notice = null) } }
    fun clearNotice() { _ui.update { it.copy(notice = null) } }
    fun clearAnswer() { askGeneration++; askJob?.cancel(); _ui.update { it.copy(asking = false, answer = null, question = null, answerCitations = emptyList()) } }
    private fun cancelRefresh() { refreshGeneration++; refreshJob?.cancel(); _ui.update { it.copy(refreshing = false, refreshingModuleId = null) } }
    private fun cancelConnection() { connectGeneration++; connectJob?.cancel(); chatGpt.cancelSignIn(); _ui.update { it.copy(connecting = false, chatGptVerifying = false) } }
    private fun cancelAiWork() { clearAnswer(); cancelDetail(); cancelSearch() }
    private fun diagnose(operation: String, error: Exception, moduleId: String? = null) {
        val entry = operationDiagnostic(operation, error, moduleId)
        _ui.update { it.copy(diagnostics = (it.diagnostics + entry).takeLast(20)) }
    }
    fun finishOnboarding() = work {
        repository.update { it.copy(onboardingComplete = true) }
        refresh()
    }
    fun refreshIfStale() = work {
        if (refreshJob?.isActive == true || connectJob?.isActive == true) return@work
        val state = repository.current()
        if (!state.onboardingComplete) return@work
        val now = System.currentTimeMillis()
        val last = state.lastRefreshMillis
        val stale = last == 0L || last > now || now - last >= state.refreshHours * 3_600_000L
        val retriedRecently = lastAutomaticAttemptMillis > 0 && now - lastAutomaticAttemptMillis in 0 until 300_000L
        if (stale && !retriedRecently) {
            lastAutomaticAttemptMillis = now
            refreshInternal(manual = false)
        }
    }
    fun refresh() = refreshInternal(manual = true)
    fun refreshModule(moduleId: String?) = refreshInternal(manual = true, moduleId = moduleId)
    private fun refreshInternal(manual: Boolean, moduleId: String? = null) {
        if (refreshJob?.isActive == true) return
        val generation = ++refreshGeneration
        refreshJob = viewModelScope.launch {
            _ui.update { it.copy(refreshing = true, refreshingModuleId = moduleId, error = null, notice = null) }
            try {
                if (manual) repository.update { it.copy(editionSequence = (it.editionSequence + 1) % 1_000_000_000L) }
                val result = withContext(Dispatchers.IO) { if (moduleId == null) engine.refresh(allowPlanRetry = manual) else engine.refreshModule(moduleId, allowPlanRetry = manual) }
                if (generation == refreshGeneration) _ui.update { it.copy(error = result.error, notice = result.notice) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (e: Exception) { if (generation == refreshGeneration) _ui.update { it.copy(error = (e as? FeedFailure)?.code ?: "provider_error") } }
            finally { if (generation == refreshGeneration) _ui.update { it.copy(refreshing = false, refreshingModuleId = null) } }
        }
    }
    fun connect(key: String, model: String = "gpt-6-luna", mode: String = "direct", serviceUrl: String = "") =
        connectProvider(AiConnection(mode, model.trim(), serviceUrl.trim().trimEnd('/')), key)

    fun connectProvider(connection: AiConnection, key: String) {
        if (connectJob?.isActive == true) return
        val generation = ++connectGeneration
        connectJob = viewModelScope.launch {
            _ui.update { it.copy(connecting = true, error = null, notice = null) }
            try {
                cancelRefresh(); cancelAiWork()
                withContext(Dispatchers.IO) { engine.connect(connection, key.trim().ifBlank { "local-no-key" }) }
                apiConnected.value = true
                if (generation == connectGeneration) _ui.update { it.copy(connected = true, notice = "connected", error = null) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (e: Exception) { if (generation == connectGeneration) _ui.update { it.copy(error = (e as? FeedFailure)?.code ?: "invalid_input") } }
            finally { if (generation == connectGeneration) _ui.update { it.copy(connecting = false) } }
        }
    }
    fun loadProviderModels(connection: AiConnection, key: String) {
        catalogJob?.cancel()
        val generation = ++catalogGeneration
        _ui.update { it.copy(providerModels=emptyList(), providerModelsLoading=true, providerModelError=null) }
        catalogJob=viewModelScope.launch {
            try { val models=withContext(Dispatchers.IO) { AlternativeAiGateway().models(connection,key) };if (generation == catalogGeneration) _ui.update { it.copy(providerModels=models) } }
            catch(e:CancellationException){throw e}
            catch(e:Exception){if (generation == catalogGeneration) _ui.update { it.copy(providerModelError=failureCode(e)) }}
            finally {if (generation == catalogGeneration) _ui.update { it.copy(providerModelsLoading=false) }}
        }
    }
    fun beginOpenRouterSignIn() {
        if (providerAuthJob?.isActive==true || connectJob?.isActive==true) return
        work { val url=openRouterAuth.begin();_ui.update { it.copy(openRouterPending=true,error=null) };browserChannel.send(url) }
    }
    fun cancelOpenRouterSignIn() {catalogGeneration++;catalogJob?.cancel();providerAuthJob?.cancel();openRouterAuth.cancel();_ui.update { it.copy(openRouterPending=false,providerAuthBusy=false,providerModels=emptyList(),providerModelsLoading=false,providerModelError=null) }}
    fun finishOpenRouterSignIn(code:String,model:String) {
        if(providerAuthJob?.isActive==true || connectJob?.isActive==true)return
        providerAuthJob=viewModelScope.launch {
            _ui.update { it.copy(providerAuthBusy=true,error=null) }
            try { val key=withContext(Dispatchers.IO){openRouterAuth.finish(code)};_ui.update { it.copy(openRouterPending=false) };connectProvider(AiConnection(mode="openrouter",model=model),key) }
            catch(e:CancellationException){throw e}
            catch(e:Exception){_ui.update { it.copy(error=failureCode(e),openRouterPending=false) }}
            finally {_ui.update { it.copy(providerAuthBusy=false) }}
        }
    }
    fun disconnect() = work {
        cancelConnection(); cancelRefresh(); cancelAiWork()
        withContext(Dispatchers.IO) { engine.disconnect() }
        if (repository.current().connection.mode != "chatgpt") apiConnected.value = false
        _ui.update { it.copy(connected = false, connecting = false, error = null, notice = null) }
    }

    fun beginChatGptSignIn(accountId: String? = null) {
        if (connectJob?.isActive == true) return
        val generation = ++connectGeneration
        connectJob = viewModelScope.launch {
            _ui.update { it.copy(connecting = true, error = null, notice = null) }
            try {
                cancelRefresh(); cancelAiWork()
                val browserUrl = withContext(Dispatchers.IO) { chatGpt.beginSignIn(accountId) }
                browserChannel.send(browserUrl)
                val account = chatGpt.awaitSignIn()
                if (!account.planEnabled) throw FeedFailure("chatgpt_permission")
                _ui.update { it.copy(chatGptVerifying = true) }
                withContext(Dispatchers.IO) { engine.connectChatGpt(account.id) }
                if (generation == connectGeneration) _ui.update { it.copy(connected = true, notice = "connected", error = null) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (e: Exception) { if (generation == connectGeneration) _ui.update { it.copy(error = failureCode(e)) } }
            finally { if (generation == connectGeneration) _ui.update { it.copy(connecting = false, chatGptVerifying = false) } }
        }
    }

    fun cancelChatGptSignIn() {
        cancelConnection()
        _ui.update { it.copy(error = null, notice = null) }
    }

    fun browserLaunchFailed() {
        cancelConnection()
        _ui.update { it.copy(error = "chatgpt_browser") }
    }

    fun selectChatGptAccount(id: String) = useChatGptAccount(id, null)

    fun switchModel(slug: String) {
        if(slug.isBlank() || slug.length > 128) return
        val connection = _ui.value.state?.connection ?: return
        if(connection.mode == "chatgpt") { selectChatGptModel(slug); return }
        work {
            val secret = withContext(Dispatchers.IO) { app.feedSecrets.read() } ?: return@work
            connectProvider(connection.copy(model=slug),secret)
        }
    }

    fun savePrefetchBatches(batches: Int) = work {
        require(batches in 1..3)
        repository.update { it.copy(prefetchBatches = batches) }
    }

    fun configureOnboarding(kinds: Set<String>, region: String, interests: String, onDone: (Boolean) -> Unit) = work {
        var succeeded = false
        try {
            require(kinds.all { it in ModuleCatalog.kinds } && region.length <= 100 && interests.length <= 500)
            repository.update { s ->
                val choices = kinds.ifEmpty { setOf("news") }
                val notes = s.context.filterNot { it.id == "onboarding-interests" } + if(interests.isBlank()) emptyList() else listOf(
                    ContextEntry(id="onboarding-interests", text=interests.trim(), category="interest", scopes=choices, origin="onboarding"))
                s.copy(interestsConfigured=true, region=region.trim(), timezone=s.timezone.ifBlank { java.time.ZoneId.systemDefault().id },
                    modules=s.modules.map { it.copy(enabled=it.kind in choices) }, context=notes, lastRefreshMillis=0)
            }
            succeeded = true
        } finally { onDone(succeeded) }
    }

    fun selectChatGptModel(slug: String) {
        val connection = _ui.value.state?.connection ?: return
        if (connection.mode != "chatgpt" || connection.accountId.isBlank() || connection.model == slug) return
        useChatGptAccount(connection.accountId, slug)
    }

    fun useChatGptAccount(id: String, model: String?) {
        if (connectJob?.isActive == true) return
        val generation = ++connectGeneration
        connectJob = viewModelScope.launch {
            _ui.update { it.copy(connecting = true, chatGptVerifying = true, error = null, notice = null) }
            try {
                cancelRefresh(); cancelAiWork()
                withContext(Dispatchers.IO) { engine.connectChatGpt(id, model) }
                if (generation == connectGeneration) _ui.update { it.copy(connected = true, notice = "connected", error = null) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (e: Exception) { if (generation == connectGeneration) _ui.update { it.copy(error = failureCode(e)) } }
            finally { if (generation == connectGeneration) _ui.update { it.copy(connecting = false, chatGptVerifying = false) } }
        }
    }

    fun acknowledgeChatGptPlan() = work {
        val connection = repository.current().connection
        if (connection.mode == "chatgpt" && connection.accountId.isNotBlank())
            withContext(Dispatchers.IO) { chatGpt.acknowledgePlanNotice(connection.accountId) }
    }

    fun disconnectChatGptAccount(id: String) = work {
        cancelConnection(); cancelRefresh(); cancelAiWork()
        val revoked = withContext(Dispatchers.IO) { engine.disconnectChatGpt(id) }
        _ui.update { it.copy(error = null, notice = if (revoked) null else "chatgpt_local_signout") }
    }
    fun toggleSaved(id: String) = work {
        repository.update { s ->
            require(s.items.any { it.id == id })
            s.copy(savedIds = if (id in s.savedIds) s.savedIds - id else s.savedIds + id)
        }
    }
    fun hideItem(id: String) = work { repository.update { it.copy(hiddenIds = (it.hiddenIds + id).toList().takeLast(1000).toSet()) } }
    fun rateRelevance(id: String, relevant: Boolean?) = work {
        repository.update { RelevanceFeedback.record(it, id, relevant, System.currentTimeMillis()) }
    }
    fun resetRelevanceFeedback() = work { repository.update { it.copy(relevanceSignals = emptyList()) } }
    fun dismissSetupNotice(key: String) = work {
        require(key in setOf("connection", "preferences"))
        repository.update { it.copy(dismissedNotices = it.dismissedNotices + (key to System.currentTimeMillis())) }
    }
    fun completeQuickGuide() = work { repository.update { it.copy(quickGuideSeen = true) } }
    fun saveRequestLimit(limit: Int) = work {
        require(RequestBudget.isValidLimit(limit))
        repository.update { it.copy(dailyRequestLimit = limit) }
        _ui.update { it.copy(notice = "saved", error = null) }
    }
    fun restoreHidden() = work { repository.update { it.copy(hiddenIds = emptySet()) } }
    fun markViewed(id: String) = work { repository.update { FeedLogic.markViewed(it, id, System.currentTimeMillis()) } }
    fun reorderModules(ids: List<String>) = work { repository.update { it.copy(modules = ModuleOrder.reorder(it.modules, ids)) } }
    fun setReadingHistory(enabled: Boolean) = work { repository.update { it.copy(readingHistoryEnabled = enabled) } }
    fun clearReadingHistory() = work { repository.update { it.copy(viewedAtMillis = emptyMap()) }; _ui.update { it.copy(notice = "saved") } }
    fun setModuleEnabled(id: String, enabled: Boolean) = work {
        cancelRefresh(); cancelAiWork(); lastAutomaticAttemptMillis = 0L
        repository.update { s -> s.copy(modules = s.modules.map { if (it.id == id) it.copy(enabled = enabled) else it },
            lastRefreshMillis = if (enabled) 0 else s.lastRefreshMillis) }
    }
    private fun changeModule(id: String, transform: (FeedState) -> FeedState): Job? {
        if (_ui.value.moduleSavingId != null) return null
        _ui.update { it.copy(moduleSavingId = id, error = null) }
        return work {
            try {
                cancelRefresh(); cancelAiWork(); lastAutomaticAttemptMillis = 0L
                val saved = repository.update(transform)
                _ui.update { it.copy(state = saved, notice = "saved", error = null) }
            } finally {
                _ui.update { it.copy(moduleSavingId = null) }
            }
        }
    }
    fun saveModule(module: FeedModule, sharedContextIds: Set<String>? = null) = changeModule(module.id) { s ->
        FeedLogic.validateModule(module)
        if (sharedContextIds != null) require(sharedContextIds.all { id -> s.context.any { it.id == id && it.confirmed } })
        val context = if (sharedContextIds == null) s.context else s.context.map { entry ->
            if (!entry.confirmed) entry else entry.copy(scopes = if (entry.id in sharedContextIds) entry.scopes + module.kind else entry.scopes - module.kind)
        }
        s.copy(modules = if (s.modules.any { it.id == module.id }) s.modules.map { if (it.id == module.id) module else it } else s.modules + module,
            context = context, lastRefreshMillis = 0,
            items = if (context == s.context) s.items else s.items.map { if (it.kind == module.kind) it.copy(why = "", relevance = 0) else it })
    }
    fun removeModule(id: String) = changeModule(id) { s -> s.copy(modules = s.modules.filterNot { it.id == id }) }
    fun saveContext(name: String, region: String, timezone: String, entries: List<ContextEntry>): Job? {
        if (_ui.value.contextSaving) return null
        _ui.update { it.copy(contextSaving = true, error = null) }
        return work {
            try {
                cancelRefresh(); cancelAiWork(); lastAutomaticAttemptMillis = 0L
                val saved = repository.update { s -> s.copy(name = name.trim(), region = region.trim(), timezone = timezone.trim(), context = entries,
                    items = s.items.map { it.copy(why = "", relevance = 0) }, lastAiRefreshMillis = 0, lastRefreshMillis = 0) }
                _ui.update { it.copy(state = saved, notice = "saved", error = null) }
            } finally {
                _ui.update { it.copy(contextSaving = false) }
            }
        }
    }
    fun saveBackgroundPreferences(background: Boolean, notifications: Boolean, hours: Int, start: Int, end: Int,
        wifi: Boolean, battery: Boolean): Job? {
        if (_ui.value.deliverySaving) return null
        _ui.update { it.copy(deliverySaving = true, error = null) }
        return work {
            try {
                val saved = repository.update { it.copy(backgroundEnabled = background, notifications = background && notifications,
                    refreshHours = hours, quietStartHour = start, quietEndHour = end,
                    backgroundWifiOnly = wifi, backgroundBatteryNotLow = battery) }
                FeedWorker.schedule(app, saved)
                _ui.update { it.copy(notice = "saved", error = null) }
            } finally { _ui.update { it.copy(deliverySaving = false) } }
        }
    }
    fun saveDelivery(enabled: Boolean, refreshHours: Int, dailyLimit: Int, quietStart: Int = 22, quietEnd: Int = 7): Job? {
        if (_ui.value.deliverySaving) return null
        _ui.update { it.copy(deliverySaving = true, error = null) }
        return work {
            try {
                val saved = repository.update { it.copy(notifications = enabled, refreshHours = refreshHours,
                    dailyRequestLimit = dailyLimit, quietStartHour = quietStart, quietEndHour = quietEnd) }
                // Persist settings before replacing background work. The form observes this exact result.
                FeedWorker.schedule(app, saved)
                // Acknowledge these settings without replacing newer feed items or usage that
                // may have arrived from the repository collector while work was scheduled.
                _ui.update { latest -> latest.copy(state = latest.state?.copy(
                    notifications = saved.notifications, refreshHours = saved.refreshHours,
                    dailyRequestLimit = saved.dailyRequestLimit, quietStartHour = saved.quietStartHour,
                    quietEndHour = saved.quietEndHour), notice = "saved", error = null) }
            } finally {
                _ui.update { it.copy(deliverySaving = false) }
            }
        }
    }
    fun ask(itemId: String?, question: String) {
        if (askJob?.isActive == true) return
        val generation = ++askGeneration
        askJob = viewModelScope.launch {
            _ui.update { it.copy(asking = true, answer = null, question = question, answerCitations = emptyList(), error = null) }
            try {
                val result = withContext(Dispatchers.IO) { engine.ask(itemId, question) }
                if (generation == askGeneration) _ui.update { it.copy(answer = result.answer, answerCitations = result.citations, error = null) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (e: Exception) { if (generation == askGeneration) _ui.update { it.copy(error = (e as? FeedFailure)?.code ?: "provider_error") } }
            finally { if (generation == askGeneration) _ui.update { it.copy(asking = false) } }
        }
    }

    private suspend fun createConversation(moduleId: String? = null, itemId: String? = null, seed: List<AiTurn> = emptyList()): AiConversation {
        val thread = AiConversation(moduleId = moduleId, itemId = itemId, turns = seed)
        repository.update { state ->
            if (state.conversations.size >= ConversationLogic.maxConversations) throw FeedFailure("conversation_limit")
            require(moduleId == null || state.modules.any { it.id == moduleId })
            require(itemId == null || state.items.any { it.id == itemId })
            state.copy(conversations = state.conversations + thread)
        }
        _ui.update { it.copy(activeConversationId = thread.id, error = null) }
        return thread
    }

    fun newConversation(moduleId: String? = null) = work { clearAnswer(); createConversation(moduleId) }
    fun selectConversation(id: String) = work {
        require(repository.current().conversations.any { it.id == id })
        clearAnswer(); _ui.update { it.copy(activeConversationId = id, error = null) }
    }
    fun deleteConversation(id: String) = work {
        if (_ui.value.activeConversationId == id) clearAnswer()
        if (detailConversationId == id) { cancelDetail(); detailConversationId = null }
        repository.update { it.copy(conversations = it.conversations.filterNot { thread -> thread.id == id }) }
        _ui.update { it.copy(activeConversationId = it.activeConversationId.takeUnless { active -> active == id }, error = null) }
    }

    fun openConversation(itemId: String?, question: String? = null) = work {
        clearAnswer()
        val state = repository.current()
        val item = itemId?.let { id -> state.items.find { it.id == id } ?: throw FeedFailure("invalid_input") }
        val existing = itemId?.let { id -> state.conversations.filter { it.itemId == id }.maxByOrNull { it.updatedAtMillis } }
        val thread = existing ?: createConversation(item?.moduleId, itemId)
        _ui.update { it.copy(activeConversationId = thread.id, error = null) }
        if (!question.isNullOrBlank()) sendConversation(question)
    }

    private suspend fun appendQuestion(id: String, question: String): AiConversation {
        val clean = question.trim()
        if (clean.isBlank() || clean.length > 2000) throw FeedFailure("invalid_input")
        val updated = repository.update { state -> state.copy(conversations = state.conversations.map { thread ->
            if (thread.id != id) thread else {
                val duplicateRetry = thread.turns.lastOrNull()?.let { it.role == "user" && it.text == clean } == true
                if (thread.turns.size > ConversationLogic.maxTurns - (if (duplicateRetry) 1 else 2)) throw FeedFailure("conversation_full")
                thread.copy(title = thread.title.ifBlank { clean.take(160) },
                    turns = if (duplicateRetry) thread.turns else thread.turns + AiTurn(role = "user", text = clean),
                    updatedAtMillis = maxOf(System.currentTimeMillis(), thread.createdAtMillis))
            }
        }) }
        return updated.conversations.find { it.id == id } ?: throw FeedFailure("invalid_input")
    }

    private fun sameRequestContext(current: FeedState, expected: FeedState): Boolean =
        current.connection == expected.connection && current.context == expected.context && current.name == expected.name &&
            current.region == expected.region && current.timezone == expected.timezone

    private suspend fun appendAnswer(thread: AiConversation, result: AiAnswer, expected: FeedState, stillCurrent: () -> Boolean): AiConversation {
        val next = thread.copy(turns = thread.turns + AiTurn(role = "assistant", text = result.answer, citations = result.citations, model = expected.connection.model),
            updatedAtMillis = maxOf(System.currentTimeMillis(), thread.createdAtMillis))
        repository.update { state ->
            if (!stillCurrent() || !sameRequestContext(state, expected) || state.conversations.find { it.id == thread.id } != thread ||
                state.modules.find { it.id == thread.moduleId } != expected.modules.find { it.id == thread.moduleId }) throw FeedFailure("invalid_input")
            state.copy(conversations = state.conversations.map { if (it.id == next.id) next else it })
        }
        return next
    }

    fun sendConversation(question: String) {
        if (askJob?.isActive == true || _ui.value.importWorking) return
        val generation = ++askGeneration
        askJob = viewModelScope.launch {
            _ui.update { it.copy(asking = true, answer = null, answerCitations = emptyList(), question = question, error = null) }
            try {
                val state = repository.current()
                val selected = state.conversations.find { it.id == _ui.value.activeConversationId } ?: createConversation()
                val thread = appendQuestion(selected.id, question)
                val requestState = repository.current()
                val result = withContext(Dispatchers.IO) { engine.askConversation(thread.id, question) { text ->
                    if (generation == askGeneration) _ui.update { it.copy(answer = text) }
                } }
                if (generation == askGeneration) {
                    appendAnswer(thread, result, requestState) { generation == askGeneration }
                    _ui.update { it.copy(answer = null, answerCitations = emptyList(), error = null) }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (e: Exception) { if (generation == askGeneration) {
                diagnose("conversation", e); _ui.update { it.copy(error = failureCode(e), answer = null) }
            } }
            finally { if (generation == askGeneration) _ui.update { it.copy(asking = false) } }
        }
    }

    fun cancelDetail() { detailGeneration++; detailJob?.cancel(); _ui.update { it.copy(detailLoading = false, detailText = "") } }
    fun retryDetail(itemId: String) {
        val last = _ui.value.detailTurns.takeIf { _ui.value.detailItemId == itemId }?.lastOrNull()
        if (last?.role == "user") followUpDetail(itemId, last.text) else loadDetail(itemId, force = true)
    }

    private suspend fun briefLanguage(): String = when (val selected = app.repository.current().settings.language) {
        "en", "sw" -> selected
        else -> if (app.resources.configuration.locales[0]?.language == "sw") "sw" else "en"
    }

    fun loadDetail(itemId: String, force: Boolean = false) {
        if (_ui.value.detailItemId == itemId && detailJob?.isActive == true) return
        cancelDetail()
        val generation = detailGeneration
        detailJob = viewModelScope.launch {
            _ui.update { it.copy(detailItemId = itemId, detailError = null, detailTurns = emptyList(), detailCitations = emptyList()) }
            try {
                val state = repository.current()
                val item = state.items.find { it.id == itemId } ?: throw FeedFailure("invalid_input")
                val briefLanguageValue = briefLanguage()
                val fingerprint = BriefCache.fingerprint(state, item, briefLanguageValue)
                val cached = state.briefs.find { it.itemId == itemId && BriefCache.reusable(it, fingerprint, System.currentTimeMillis()) }
                val thread = state.conversations.filter { it.itemId == itemId }.maxByOrNull { it.updatedAtMillis }
                if (cached != null && !force) {
                    detailConversationId = thread?.takeIf { it.updatedAtMillis >= cached.generatedAtMillis }?.id
                    val turns = thread?.takeIf { it.id == detailConversationId }?.turns?.takeIf { it.isNotEmpty() }
                        ?: listOf(AiTurn(role = "assistant", text = cached.text, citations = cached.citations, model = cached.model, createdAtMillis = cached.generatedAtMillis))
                    _ui.update { it.copy(detailTurns = turns, detailCitations = cached.citations) }
                    return@launch
                }
                detailConversationId = null
                if (!_ui.value.connected) {
                    // Previously completed text remains readable without a network connection.
                    state.briefs.find { it.itemId == itemId }?.let { old -> _ui.update { it.copy(detailTurns = listOf(AiTurn(role = "assistant", text = old.text, citations = old.citations, model = old.model, createdAtMillis = old.generatedAtMillis))) } }
                    return@launch
                }
                _ui.update { it.copy(detailLoading = true) }
                val result = withContext(Dispatchers.IO) { engine.explain(itemId) { text ->
                    if (generation == detailGeneration) _ui.update { it.copy(detailText = text) }
                } }
                if (generation != detailGeneration) return@launch
                if (result.answer.length > 12000) throw FeedFailure("invalid_response")
                val brief = AiItemBrief(itemId, result.answer, result.citations, System.currentTimeMillis(), state.connection.model, fingerprint)
                val currentLanguage = briefLanguage()
                repository.update { latest ->
                    val latestItem = latest.items.find { it.id == itemId } ?: throw FeedFailure("invalid_input")
                    if (generation != detailGeneration || BriefCache.fingerprint(latest, latestItem, currentLanguage) != fingerprint) throw FeedFailure("invalid_input")
                    latest.copy(briefs = (latest.briefs.filterNot { it.itemId == itemId } + brief).sortedByDescending { it.generatedAtMillis }.take(24))
                }
                _ui.update { it.copy(detailText = "", detailTurns = listOf(AiTurn(role = "assistant", text = brief.text, citations = brief.citations, model = brief.model, createdAtMillis = brief.generatedAtMillis)), detailCitations = brief.citations) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (e: Exception) { if (generation == detailGeneration) { diagnose("inference", e); _ui.update { it.copy(detailError = failureCode(e), detailText = "") } } }
            finally { if (generation == detailGeneration) _ui.update { it.copy(detailLoading = false) } }
        }
    }

    fun followUpDetail(itemId: String, question: String) {
        if (_ui.value.detailLoading) return
        _ui.update { it.copy(detailLoading = true) }
        val generation = ++detailGeneration
        detailJob = viewModelScope.launch {
            _ui.update { it.copy(detailItemId = itemId, detailLoading = true, detailText = "", detailError = null) }
            try {
                val state = repository.current()
                val item = state.items.find { it.id == itemId } ?: throw FeedFailure("invalid_input")
                val selected = state.conversations.find { it.id == detailConversationId && it.itemId == itemId }
                    ?: createConversation(item.moduleId, itemId, _ui.value.detailTurns.filter { it.role == "assistant" }.take(1))
                detailConversationId = selected.id
                val thread = appendQuestion(selected.id, question)
                _ui.update { it.copy(detailTurns = thread.turns) }
                val result = withContext(Dispatchers.IO) { engine.askConversation(thread.id, question) { text ->
                    if (generation == detailGeneration) _ui.update { it.copy(detailText = text) }
                } }
                if (generation == detailGeneration) {
                    val completed = appendAnswer(thread, result, state) { generation == detailGeneration }
                    _ui.update { it.copy(detailTurns = completed.turns, detailText = "", detailCitations = result.citations) }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (e: Exception) { if (generation == detailGeneration) { diagnose("inference", e); _ui.update { it.copy(detailError = failureCode(e), detailText = "") } } }
            finally { if (generation == detailGeneration) _ui.update { it.copy(detailLoading = false) } }
        }
    }

    fun cancelSearch() { searchGeneration++; searchJob?.cancel(); _ui.update { it.copy(searching = false) } }
    fun search(query: String, moduleId: String? = null) {
        cancelSearch()
        val generation = searchGeneration
        val clean = query.trim()
        searchJob = viewModelScope.launch {
            _ui.update { it.copy(searchQuery = clean, searchModuleId = moduleId, searching = true, searchCompleted = false,
                searchResults = emptyList(), searchError = null) }
            try {
                val expected = repository.current()
                val results = withContext(Dispatchers.IO) { engine.search(clean, moduleId) }
                if (generation == searchGeneration) {
                    repository.update { s ->
                        val selected = expected.modules.filter { it.enabled && (moduleId == null || it.id == moduleId) && it.kind !in setOf("weather", "horoscope") }
                        if (generation != searchGeneration || !sameRequestContext(s, expected) || selected.any { old -> s.modules.find { it.id == old.id } != old }) throw FeedFailure("invalid_input")
                        FeedLogic.merge(s, results, System.currentTimeMillis()).copy(lastRefreshMillis = s.lastRefreshMillis, lastAiRefreshMillis = s.lastAiRefreshMillis)
                    }
                    _ui.update { it.copy(searchResults = results, searchCompleted = true) }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (e: Exception) { if (generation == searchGeneration) { diagnose("discovery", e, moduleId); _ui.update { it.copy(searchError = failureCode(e)) } } }
            finally { if (generation == searchGeneration) _ui.update { it.copy(searching = false) } }
        }
    }

    private suspend fun displayName(uri: Uri): String = withContext(Dispatchers.IO) {
        app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }?.take(160) ?: uri.lastPathSegment?.substringAfterLast('/')?.take(160) ?: "file"
    }

    fun importTextAttachment(uri: Uri) {
        if (_ui.value.importWorking || _ui.value.asking) return
        importJob = viewModelScope.launch {
            _ui.update { it.copy(importWorking = true, error = null) }
            try {
                val name = displayName(uri)
                if (!name.endsWith(".txt", true) && !name.endsWith(".md", true)) throw FeedFailure("attachment_type")
                val text = read(uri, ConversationLogic.maxAttachmentBytes)
                if (text.isBlank() || '\u0000' in text || text.length > ConversationLogic.maxAttachmentCharacters) throw FeedFailure("attachment_size")
                val state = repository.current()
                val selected = state.conversations.find { it.id == _ui.value.activeConversationId } ?: createConversation()
                repository.update { s -> s.copy(conversations = s.conversations.map { thread ->
                    if (thread.id != selected.id || thread.attachments.any { it.text == text && it.name == name }) thread else {
                        if (thread.attachments.size >= 3 || thread.attachments.sumOf { it.text.length } + text.length > ConversationLogic.maxAttachmentCharacters) throw FeedFailure("attachment_size")
                        thread.copy(attachments = thread.attachments + AiAttachment(name = name, text = text), updatedAtMillis = maxOf(System.currentTimeMillis(), thread.createdAtMillis))
                    }
                }) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (e: Exception) { diagnose("attachment", e); _ui.update { it.copy(error = failureCode(e, "invalid_input")) } }
            finally { _ui.update { it.copy(importWorking = false) } }
        }
    }

    fun removeAttachment(id: String) = work {
        clearAnswer()
        val active = _ui.value.activeConversationId
        repository.update { s -> s.copy(conversations = s.conversations.map { if (it.id == active) it.copy(attachments = it.attachments.filterNot { file -> file.id == id }) else it }) }
    }

    private suspend fun read(uri: Uri, maximum: Int): String = withContext(Dispatchers.IO) {
        app.contentResolver.openInputStream(uri)?.use { stream ->
            val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
            while (true) { val n = stream.read(buffer); if (n < 0) break; if (out.size() + n > maximum) throw FeedFailure("file_too_large"); out.write(buffer, 0, n) }
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(out.toByteArray())).toString().removePrefix("\uFEFF")
        } ?: throw FeedFailure("invalid_input")
    }
    private suspend fun write(uri: Uri, text: String) = withContext(Dispatchers.IO) {
        app.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) } ?: throw FeedFailure("storage_error")
    }
    private fun decodeContext(text: String): PersonalContextImport {
        val json = Json { ignoreUnknownKeys = false }
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrElse { throw FeedFailure("import_format") }
        if (root["format"]?.jsonPrimitive?.content != "mebot-context-v1") throw FeedFailure("import_format")
        val imported = runCatching { json.decodeFromString<PersonalContextImport>(text) }.getOrElse { throw FeedFailure("import_invalid") }
        try { FeedLogic.validate(FeedState(name = imported.name, region = imported.region, timezone = imported.timezone,
            context = imported.entries, modules = imported.modules)) }
        catch (_: IllegalArgumentException) { throw FeedFailure("import_invalid") }
        return imported
    }
    private fun decodeBackup(text: String): FeedState {
        val json = Json { ignoreUnknownKeys = false }
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrElse { throw FeedFailure("import_format") }
        if (root["schemaVersion"]?.jsonPrimitive?.content != "1" || "modules" !in root || "connection" !in root) throw FeedFailure("import_format")
        return runCatching { FeedLogic.validate(json.decodeFromString<FeedState>(text)) }.getOrElse { throw FeedFailure("import_invalid") }
    }
    private suspend fun applyContext(imported: PersonalContextImport) {
        cancelRefresh(); cancelAiWork(); lastAutomaticAttemptMillis = 0L
        repository.update { s -> s.copy(name = imported.name, region = imported.region, timezone = imported.timezone,
            context = imported.entries.map { it.copy(origin = "imported") },
            modules = ModuleCatalog.mergeCatalog(imported.modules.ifEmpty { s.modules }), items = s.items.map { it.copy(why = "", relevance = 0) },
            briefs = emptyList(), lastAiRefreshMillis = 0, lastRefreshMillis = 0) }
        _ui.update { it.copy(notice = "imported", error = null) }
    }
    private suspend fun applyBackup(candidate: FeedState) {
        cancelConnection(); cancelRefresh(); cancelAiWork()
        // Commit the data first. A failed store write must not erase a working account.
        // A blank plan account prevents old API credentials being used if cleanup fails.
        repository.update { candidate.copy(notifications = false, personalSeedApplied = true, modules = ModuleCatalog.mergeCatalog(candidate.modules),
            connection = AiConnection(mode = "chatgpt", model = candidate.connection.model), aiPauseCode = "") }
        apiConnected.value = false
        FeedWorker.cancel(app)
        withContext(Dispatchers.IO) { engine.clearAllCredentials() }
        _ui.update { it.copy(connected = false, activeConversationId = null, detailItemId = null, detailTurns = emptyList(),
            searchResults = emptyList(), searchCompleted = false, notice = "imported", error = null) }
    }
    fun prepareContextImport(uri: Uri) = prepareImport(uri, "context")
    fun prepareFeedBackup(uri: Uri) = prepareImport(uri, "backup")
    private fun prepareImport(uri: Uri, kind: String) {
        prepareImportValue(kind) { displayName(uri) to read(uri, if (kind == "context") 512 * 1024 else 8 * 1024 * 1024) }
    }
    fun prepareContextText(text: String) {
        prepareImportValue("context") {
            if (text.toByteArray(Charsets.UTF_8).size > 512 * 1024) throw FeedFailure("file_too_large")
            // Accept one copied JSON code block, while rejecting prose or several blocks.
            val trimmed = text.trim().removePrefix("\uFEFF")
            val json = if (trimmed.startsWith("```json\n") && trimmed.endsWith("```"))
                trimmed.removePrefix("```json\n").removeSuffix("```").trim()
            else if (trimmed.startsWith("```\n") && trimmed.endsWith("```"))
                trimmed.removePrefix("```\n").removeSuffix("```").trim()
            else trimmed
            "mebot-context.json" to json
        }
    }
    private fun prepareImportValue(kind: String, load: suspend () -> Pair<String, String>) {
        val generation = ++importGeneration
        importJob?.cancel(); pendingContextImport = null; pendingBackupImport = null
        _ui.update { it.copy(importWorking = true, importPreview = null, importSummary = null, error = null) }
        importJob = viewModelScope.launch {
            try {
                val (name, text) = load()
                val preview = if (kind == "context") {
                    val value = withContext(Dispatchers.Default) { decodeContext(text) }
                    if (generation != importGeneration) return@launch
                    pendingContextImport = value
                    FeedImportPreview(kind, name, value.entries.size, value.modules.size)
                } else {
                    val value = withContext(Dispatchers.Default) { decodeBackup(text) }
                    if (generation != importGeneration) return@launch
                    pendingBackupImport = value
                    FeedImportPreview(kind, name, value.context.size, value.modules.size, value.savedIds.size, value.conversations.size)
                }
                if (generation == importGeneration) _ui.update { it.copy(importPreview = preview) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (e: Exception) { if (generation == importGeneration) {
                diagnose(if (kind == "context") "context_import" else "backup_import", e)
                _ui.update { it.copy(error = failureCode(e, "import_invalid")) }
            } }
            finally { if (generation == importGeneration) _ui.update { it.copy(importWorking = false) } }
        }
    }
    fun cancelImport() {
        importGeneration++
        importJob?.cancel(); pendingContextImport = null; pendingBackupImport = null
        _ui.update { it.copy(importPreview = null, importWorking = false) }
    }
    fun confirmImport() {
        if (_ui.value.importWorking) return
        val context = pendingContextImport; val backup = pendingBackupImport
        if (context == null && backup == null) return
        val generation = ++importGeneration
        _ui.update { it.copy(importWorking = true, error = null) }
        importJob = viewModelScope.launch {
            try {
                if (context != null) applyContext(context) else applyBackup(requireNotNull(backup))
                if (generation == importGeneration) {
                    pendingContextImport = null; pendingBackupImport = null
                    _ui.update { it.copy(importPreview = null, importSummary = if (context != null) "context" else "backup") }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (e: Exception) { if (generation == importGeneration) {
                diagnose(if (context != null) "context_import" else "backup_import", e)
                _ui.update { it.copy(error = failureCode(e, "storage_error")) }
            } }
            finally { if (generation == importGeneration) _ui.update { it.copy(importWorking = false) } }
        }
    }
    fun importContext(uri: Uri) {
        importJob?.cancel()
        importJob = work {
        val imported = decodeContext(read(uri, 512 * 1024))
        // Reject invalid profile data before interrupting a working refresh or answer.
        FeedLogic.validate(FeedState(name = imported.name, region = imported.region, timezone = imported.timezone,
            context = imported.entries, modules = imported.modules))
        applyContext(imported)
        }
    }
    fun exportContext(uri: Uri) = work {
        val s = repository.current()
        write(uri, Json { prettyPrint = true; encodeDefaults = true }.encodeToString(PersonalContextImport(name = s.name, region = s.region, timezone = s.timezone, entries = s.context, modules = s.modules)))
        _ui.update { it.copy(notice = "saved", error = null) }
    }
    fun exportFeedBackup(uri: Uri) = work {
        val state = repository.current()
        write(uri, Json { prettyPrint = true; encodeDefaults = true }.encodeToString(state.copy(
            connection = state.connection.copy(accountId = ""), aiPauseCode = "")))
        _ui.update { it.copy(notice = "saved", error = null) }
    }
    fun importFeedBackup(uri: Uri) {
        importJob?.cancel()
        importJob = work {
        val candidate = decodeBackup(read(uri, 8 * 1024 * 1024))
        applyBackup(candidate)
        }
    }
    fun reset() = work {
        importJob?.cancel(); cancelRefresh(); cancelConnection(); cancelAiWork()
        withContext(Dispatchers.IO) { engine.clearAllCredentials() }
        apiConnected.value = false
        repository.erase(); FeedWorker.cancel(app)
        _ui.update { it.copy(connected = false, error = null, notice = null) }
    }

    override fun onCleared() {
        chatGpt.cancelSignIn()
        browserChannel.close()
        super.onCleared()
    }
}
