package mebot.nguli.com.feed

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
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
import java.util.concurrent.atomic.AtomicLong

data class SuggestedConversationDraft(val id: String, val conversationId: String, val text: String)

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
    val conversationOpening: Boolean = false,
    val conversationReady: Boolean = true,
    val suggestionDraft: SuggestedConversationDraft? = null,
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

class FeedViewModel(application: Application, private val savedStateHandle: SavedStateHandle) : AndroidViewModel(application) {
    /** Keeps existing non-Activity callers (including test factories) on the same Application. */
    constructor(application: Application) : this(application, SavedStateHandle())

    private val restoredSelectionMode = savedStateHandle.get<String>(KEY_SELECTION_MODE)
    @Volatile private var pendingConversationOpen = restoredSelectionMode == SELECTION_PENDING_OPEN
    private var savedSelectionRestored = restoredSelectionMode == null
    @Volatile private var intendedConversationItemId: String? = savedStateHandle.get(KEY_SELECTION_ITEM_ID)
    private val app = application as MeBotApplication
    private val repository = app.feedRepository
    private val engine = app.feedEngine
    private val chatGpt = app.chatGptAuth
    private val openRouterAuth = mebot.nguli.com.feed.auth.OpenRouterSignIn()
    private var catalogJob: Job? = null
    private var catalogGeneration = 0
    private var providerAuthJob: Job? = null
    private val apiPresence = CredentialPresence()
    private val apiConnected = apiPresence.state
    private val authStartupError = MutableStateFlow<String?>(null)
    private val browserChannel = Channel<String>(Channel.RENDEZVOUS)
    val browserLaunches = browserChannel.receiveAsFlow()
    private val suggestionGeneration = AtomicLong(0)
    private val _ui = MutableStateFlow(FeedUiState(
        activeConversationId = if (restoredSelectionMode == SELECTION_ACTIVE || restoredSelectionMode == SELECTION_PENDING_SELECT)
            savedStateHandle.get(KEY_SELECTION_CONVERSATION_ID) else null,
        conversationReady = restoredSelectionMode == null,
    ))
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
    @Volatile private var conversationSelectionGeneration = 0L
    /** Deterministic test seam; production leaves the pre-open step as a no-op. */
    internal var beforeConversationOpen: suspend (String?) -> Unit = {}
    private var refreshGeneration = 0L
    private var askGeneration = 0L
    private var connectGeneration = 0L
    private var lastAutomaticAttemptMillis = 0L
    private var lastLoadMoreAttemptMillis = 0L

    internal companion object {
        const val KEY_SELECTION_MODE = "feed.conversation.selection.mode"
        const val KEY_SELECTION_CONVERSATION_ID = "feed.conversation.selection.id"
        const val KEY_SELECTION_ITEM_ID = "feed.conversation.selection.item"
        const val SELECTION_PENDING_OPEN = "pending-open"
        const val SELECTION_PENDING_SELECT = "pending-select"
        const val SELECTION_ACTIVE = "active"
        const val SELECTION_BLOCKED = "blocked"
    }

    init {
        viewModelScope.launch { engine.progress.collect { p -> _ui.update { it.copy(refreshProgress=p) } } }
        viewModelScope.launch { AiActivityRegistry.state.collect { tasks -> _ui.update { it.copy(activities = tasks.values.toList()) } } }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { repository.applyOptionalSeed(app) }
                // Seed migration precedes the first visible state; account storage must not.
                // These independent children cannot hold cached/public reading behind a slow Keystore.
                launch {
                    val presenceEpoch = apiPresence.snapshotEpoch()
                    val present = withContext(Dispatchers.IO) { runCatching { app.feedSecrets.read() != null }.getOrDefault(false) }
                    apiPresence.publishSnapshot(presenceEpoch, present)
                }
                launch {
                    try { withContext(Dispatchers.IO) { chatGpt.initialize() } }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (e: Exception) { authStartupError.value = (e as? ChatGptAuthException)?.code ?: "chatgpt_storage" }
                }
                combine(repository.state, chatGpt.state, apiConnected, authStartupError) { state, auth, hasKey, authError ->
                    Triple(state, auth, hasKey) to authError
                }
                    .collect { (snapshot, authError) ->
                        val (state, auth, hasKey) = snapshot
                        val selected = state.connection.takeIf { it.mode == "chatgpt" }?.accountId
                        val account = auth.accounts.find { it.id == selected }
                        _ui.update { it.copy(state = state, loading = false,
                            connected = if (state.connection.mode == "chatgpt") account?.let { a -> a.connected && a.planEnabled } == true else hasKey,
                            chatGpt = auth.copy(activeAccountId = selected),
                            error = it.error ?: authError.takeIf { state.connection.mode == "chatgpt" && auth.accounts.isEmpty() }
                                ?: state.aiPauseCode.takeIf { code -> code.isNotBlank() }) }
                        restoreConversationSelection(state)
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
    fun clearImportSummary() { _ui.update { it.copy(importSummary = null) } }
    fun clearNotice() { _ui.update { it.copy(notice = null) } }
    fun clearAnswer() { suggestionGeneration.incrementAndGet(); _ui.update { it.copy(suggestionDraft = null) }; askGeneration++; askJob?.cancel(); _ui.update { it.copy(asking = false, answer = null, question = null, answerCitations = emptyList()) } }

    /** Validate the saved conversation intent against the persisted snapshot before enabling sends. */
    private fun restoreConversationSelection(state: FeedState) {
        if (savedSelectionRestored) return
        savedSelectionRestored = true
        val mode = restoredSelectionMode
        when (mode) {
            SELECTION_PENDING_OPEN -> {
                if (savedStateHandle.contains(KEY_SELECTION_ITEM_ID)) {
                    pendingConversationOpen = true
                    intendedConversationItemId = savedStateHandle.get(KEY_SELECTION_ITEM_ID)
                    _ui.update { it.copy(activeConversationId = null, conversationOpening = false,
                        conversationReady = false, error = null) }
                } else failRestoredConversationSelection()
            }
            SELECTION_ACTIVE, SELECTION_PENDING_SELECT -> {
                val id = savedStateHandle.get<String>(KEY_SELECTION_CONVERSATION_ID)
                val expectedItemId = savedStateHandle.get<String>(KEY_SELECTION_ITEM_ID)
                val savedScopeComplete = savedStateHandle.contains(KEY_SELECTION_CONVERSATION_ID) &&
                    savedStateHandle.contains(KEY_SELECTION_ITEM_ID)
                val thread = id?.takeIf { savedScopeComplete }?.let { key -> state.conversations.find { it.id == key } }
                if (thread != null && thread.itemId == expectedItemId) {
                    saveActiveConversationSelection(thread)
                    _ui.update { it.copy(activeConversationId = thread.id, conversationOpening = false,
                        conversationReady = true, error = null) }
                } else {
                    failRestoredConversationSelection(expectedItemId)
                }
            }
            SELECTION_BLOCKED -> failRestoredConversationSelection(savedStateHandle.get(KEY_SELECTION_ITEM_ID))
            else -> {
                // Unknown saved selection metadata cannot safely be treated as a general chat.
                failRestoredConversationSelection(savedStateHandle.get(KEY_SELECTION_ITEM_ID))
            }
        }
    }

    private fun failRestoredConversationSelection(itemId: String? = null) {
        intendedConversationItemId = itemId
        pendingConversationOpen = false
        _ui.update { it.copy(activeConversationId = null, conversationOpening = false,
            conversationReady = false, error = "invalid_input") }
    }

    private fun savePendingConversationOpen(itemId: String?) {
        savedSelectionRestored = true
        savedStateHandle[KEY_SELECTION_MODE] = SELECTION_PENDING_OPEN
        savedStateHandle[KEY_SELECTION_CONVERSATION_ID] = null
        savedStateHandle[KEY_SELECTION_ITEM_ID] = itemId
        pendingConversationOpen = true
        intendedConversationItemId = itemId
    }

    private fun savePendingConversationSelect(id: String, itemId: String?) {
        savedSelectionRestored = true
        savedStateHandle[KEY_SELECTION_MODE] = SELECTION_PENDING_SELECT
        savedStateHandle[KEY_SELECTION_CONVERSATION_ID] = id
        savedStateHandle[KEY_SELECTION_ITEM_ID] = itemId
        pendingConversationOpen = false
        intendedConversationItemId = itemId
    }

    private fun saveActiveConversationSelection(thread: AiConversation) {
        savedSelectionRestored = true
        savedStateHandle[KEY_SELECTION_MODE] = SELECTION_ACTIVE
        savedStateHandle[KEY_SELECTION_CONVERSATION_ID] = thread.id
        savedStateHandle[KEY_SELECTION_ITEM_ID] = thread.itemId
        pendingConversationOpen = false
        intendedConversationItemId = thread.itemId
    }

    private fun saveBlockedConversationSelection(itemId: String?) {
        savedSelectionRestored = true
        savedStateHandle[KEY_SELECTION_MODE] = SELECTION_BLOCKED
        savedStateHandle[KEY_SELECTION_CONVERSATION_ID] = null
        savedStateHandle[KEY_SELECTION_ITEM_ID] = itemId
        pendingConversationOpen = false
        intendedConversationItemId = itemId
    }

    private fun clearSavedConversationSelection() {
        savedSelectionRestored = true
        savedStateHandle.remove<String>(KEY_SELECTION_MODE)
        savedStateHandle.remove<String>(KEY_SELECTION_CONVERSATION_ID)
        savedStateHandle.remove<String>(KEY_SELECTION_ITEM_ID)
        pendingConversationOpen = false
        intendedConversationItemId = null
    }

    private fun beginConversationSelection(itemId: String?): Long {
        val generation = ++conversationSelectionGeneration
        savePendingConversationOpen(itemId)
        _ui.update { it.copy(activeConversationId = null, conversationOpening = true,
            conversationReady = false, error = null) }
        return generation
    }
    private fun finishConversationSelection(generation: Long, thread: AiConversation) {
        if (generation != conversationSelectionGeneration) return
        saveActiveConversationSelection(thread)
        _ui.update { it.copy(activeConversationId = thread.id, conversationOpening = false,
            conversationReady = true, error = null) }
    }
    private fun failConversationSelection(generation: Long, error: Exception) {
        if (generation != conversationSelectionGeneration) return
        _ui.update { it.copy(activeConversationId = null, conversationOpening = false,
            conversationReady = false, error = failureCode(error, "storage_error")) }
    }
    /** Invalidate an in-flight selection when leaving the workspace; late work is generation-fenced. */
    fun cancelConversationOpen() {
        if (!_ui.value.conversationOpening) return
        conversationSelectionGeneration++
        _ui.update { it.copy(activeConversationId = null, conversationOpening = false,
            // Keep the pending intent durably bound while hidden; it can be resumed on return.
            conversationReady = false, error = null) }
    }
    /** Reopen a cancelled item target when the user returns to the workspace. */
    fun resumeConversationOpenIfNeeded() {
        val current = _ui.value
        if (current.conversationOpening || current.conversationReady || current.activeConversationId != null) return
        if (pendingConversationOpen) openConversation(intendedConversationItemId)
    }
    private fun cancelRefresh() { refreshGeneration++; refreshJob?.cancel(); _ui.update { it.copy(refreshing = false, refreshingModuleId = null) } }
    private fun cancelConnection() { connectGeneration++; connectJob?.cancel(); chatGpt.cancelSignIn(); _ui.update { it.copy(connecting = false, chatGptVerifying = false) } }
    private fun cancelAiWork() { cancelConversationOpen(); clearAnswer(); cancelDetail(); cancelSearch() }
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
    fun loadMore(moduleId: String?) {
        val now = System.currentTimeMillis()
        if (lastLoadMoreAttemptMillis > 0 && now - lastLoadMoreAttemptMillis in 0 until 60_000L) return
        if (refreshJob?.isActive == true || connectJob?.isActive == true) return
        lastLoadMoreAttemptMillis = now
        refreshInternal(manual = false, moduleId = moduleId)
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
                apiPresence.definitive(true)
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
    fun disconnect(): Job {
        cancelConversationOpen()
        clearAnswer()
        return work {
            cancelConnection(); cancelRefresh(); cancelAiWork()
            withContext(Dispatchers.IO) { engine.disconnect() }
            if (repository.current().connection.mode != "chatgpt") apiPresence.definitive(false)
            _ui.update { it.copy(connected = false, connecting = false, error = null, notice = null) }
        }
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
            require(kinds.all { it in ModuleCatalog.kinds } && region.length <= 100 && PersonalProfile085.valid(interests))
            repository.update { s ->
                val choices = kinds.ifEmpty { setOf("news") }
                // An absent/empty restored onboarding draft must not erase an existing note.
                // Deliberate removal remains an explicit action in the context editor.
                val notes = if (interests.isBlank()) s.context
                    else PersonalProfile085.onboarding(s.context, interests, kinds - "weather")
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

    fun disconnectChatGptAccount(id: String): Job {
        cancelConversationOpen(); clearAnswer()
        return work {
            cancelConnection(); cancelRefresh(); cancelAiWork()
            val revoked = withContext(Dispatchers.IO) { engine.disconnectChatGpt(id) }
            _ui.update { it.copy(error = null, notice = if (revoked) null else "chatgpt_local_signout") }
        }
    }
    fun removeChatGptAccount(id: String): Job {
        cancelConversationOpen(); clearAnswer()
        return work {
            cancelConnection(); cancelRefresh(); cancelAiWork()
            withContext(Dispatchers.IO) { engine.removeSavedChatGptAccount(id) }
            _ui.update { it.copy(error = null, notice = null) }
        }
    }

    fun toggleSaved(id: String) = work {
        repository.update { s ->
            require(s.items.any { it.id == id })
            s.copy(savedIds = if (id in s.savedIds) s.savedIds - id else s.savedIds + id,
                savedCollections = if (id in s.savedIds) s.savedCollections - id else s.savedCollections)
        }
    }
    fun updateSpeechPreferences(rate: Float, voice: String) = work { repository.update { it.copy(speechRate = rate, speechVoice = voice) } }
    fun toggleStar(id: String) = work { repository.update { StarredStories.toggle(it, id, System.currentTimeMillis()) } }
    fun configureStar(id: String, paused: Boolean, hours: Int) = work { repository.update { StarredStories.configure(it, id, paused, hours) } }
    fun setSavedCollection(id: String, name: String) = work { repository.update { StarredStories.collection(it, id, name) } }
    fun saveReadingPreferences(update: (ReadingPreferences) -> ReadingPreferences) = work {
        repository.update { it.copy(readingPreferences = update(it.readingPreferences)) }
    }
    fun hideItem(id: String) = work { repository.update { it.copy(hiddenIds = (it.hiddenIds + id).toList().takeLast(1000).toSet(),
        starredStories = it.starredStories.map { star -> if (star.itemId == id) star.copy(paused = true) else star }) } }
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
        val priorModule = s.modules.firstOrNull { it.id == module.id }
        if (sharedContextIds != null) require(sharedContextIds.all { id -> s.context.any { it.id == id && it.confirmed && !PersonalProfile085.isGlobal(it) } })
        val context = if (sharedContextIds == null) s.context else s.context.map { entry ->
            if (!entry.confirmed || PersonalProfile085.isGlobal(entry)) entry else entry.copy(scopes = if (entry.id in sharedContextIds) entry.scopes + module.kind else entry.scopes - module.kind)
        }
        val scopeChanged = context != s.context || priorModule != module
        s.copy(modules = if (s.modules.any { it.id == module.id }) s.modules.map { if (it.id == module.id) module else it } else s.modules + module,
            context = context, lastRefreshMillis = 0,
            items = if (!scopeChanged) s.items else s.items.map { item ->
                val contextAffected = context != s.context && item.kind == module.kind
                val moduleAffected = priorModule != module && item.moduleId == module.id
                if (contextAffected || moduleAffected) AiSuggestedInteractions095.clear(
                    if (contextAffected) item.copy(why = "", relevance = 0) else item)
                else item
            })
    }
    fun removeModule(id: String) = changeModule(id) { s -> s.copy(modules = s.modules.filterNot { it.id == id },
        items = s.items.map { if (it.moduleId == id) AiSuggestedInteractions095.clear(it) else it }) }
    fun saveContext(name: String, region: String, timezone: String, entries: List<ContextEntry>): Job? {
        if (_ui.value.contextSaving) return null
        _ui.update { it.copy(contextSaving = true, error = null) }
        return work {
            try {
                cancelRefresh(); cancelAiWork(); lastAutomaticAttemptMillis = 0L
                val saved = repository.update { s -> s.copy(name = name.trim(), region = region.trim(), timezone = timezone.trim(), context = entries,
                    items = s.items.map { AiSuggestedInteractions095.clear(it.copy(why = "", relevance = 0)) }, lastAiRefreshMillis = 0, lastRefreshMillis = 0) }
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

    private suspend fun createConversation(moduleId: String? = null, itemId: String? = null,
        seed: List<AiTurn> = emptyList(), stillCurrent: (() -> Boolean)? = null): AiConversation? {
        val thread = AiConversation(moduleId = moduleId, itemId = itemId, turns = seed)
        var inserted = false
        repository.update { state ->
            // Check inside DataStore's serialized transform as well as before calling updateData.
            // A reset or newer selection may arrive while the disk transaction is queued.
            if (stillCurrent?.invoke() == false) return@update state
            if (state.conversations.size >= ConversationLogic.maxConversations) throw FeedFailure("conversation_limit")
            require(moduleId == null || state.modules.any { it.id == moduleId })
            require(itemId == null || state.items.any { it.id == itemId })
            if (stillCurrent?.invoke() == false) return@update state
            inserted = true
            state.copy(conversations = state.conversations + thread)
        }
        return thread.takeIf { inserted }
    }

    fun newConversation(moduleId: String? = null): Job {
        clearAnswer()
        val generation = beginConversationSelection(itemId = null)
        return viewModelScope.launch {
            try {
                val thread = createConversation(moduleId = moduleId) { generation == conversationSelectionGeneration }
                    ?: return@launch
                finishConversationSelection(generation, thread)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (e: Exception) { failConversationSelection(generation, e) }
        }
    }
    fun selectConversation(id: String): Job {
        clearAnswer()
        val known = _ui.value.state?.conversations?.find { it.id == id }
        val generation = ++conversationSelectionGeneration
        savePendingConversationSelect(id, known?.itemId)
        _ui.update { it.copy(activeConversationId = id, conversationOpening = false,
            conversationReady = known != null, error = null) }
        return viewModelScope.launch {
            try {
                val thread = repository.current().conversations.find { it.id == id } ?: throw FeedFailure("invalid_input")
                if (generation == conversationSelectionGeneration) {
                    finishConversationSelection(generation, thread)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (e: Exception) {
                if (generation == conversationSelectionGeneration) {
                    _ui.update { it.copy(activeConversationId = null, conversationOpening = false,
                        conversationReady = false, error = failureCode(e, "storage_error")) }
                }
            }
        }
    }
    fun deleteConversation(id: String): Job {
        val removingActive = _ui.value.activeConversationId == id
        if (removingActive) {
            val deletedItemId = intendedConversationItemId
            conversationSelectionGeneration++
            // A stale composed send callback must not treat deleted scope as an intentional new chat.
            saveBlockedConversationSelection(deletedItemId)
            clearAnswer()
            _ui.update { it.copy(activeConversationId = null, conversationOpening = false,
                conversationReady = false, error = null) }
        }
        return work {
            if (detailConversationId == id) { cancelDetail(); detailConversationId = null }
            repository.update { it.copy(conversations = it.conversations.filterNot { thread -> thread.id == id }) }
            if (!removingActive) _ui.update { it.copy(error = null) }
        }
    }

    fun openConversation(itemId: String?, question: String? = null): Job {
        clearAnswer()
        val generation = beginConversationSelection(itemId)
        return viewModelScope.launch {
            try {
                beforeConversationOpen(itemId)
                if (generation != conversationSelectionGeneration) return@launch
                val state = repository.current()
                if (generation != conversationSelectionGeneration) return@launch
                val item = itemId?.let { id -> state.items.find { it.id == id } ?: throw FeedFailure("invalid_input") }
                val existing = itemId?.let { id -> state.conversations.filter { it.itemId == id }.maxByOrNull { it.updatedAtMillis } }
                val thread = existing ?: createConversation(item?.moduleId, itemId) { generation == conversationSelectionGeneration }
                    ?: return@launch
                if (generation != conversationSelectionGeneration) return@launch
                if (itemId != null && thread.itemId != itemId) throw FeedFailure("invalid_input")
                finishConversationSelection(generation, thread)
                if (!question.isNullOrBlank()) sendConversation(question)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (e: Exception) { failConversationSelection(generation, e) }
        }
    }

    /** Resolve against current persisted content, then prepare a new scoped draft; never infer here. */
    fun prepareSuggestion(itemId: String, stableSuggestionId: String, appLanguage: String): Job {
        clearAnswer()
        val suggestionToken = suggestionGeneration.get()
        val selectionToken = beginConversationSelection(itemId)
        return viewModelScope.launch {
            try {
                val language = appLanguage.takeIf { it == "sw" } ?: "en"
                val configuration = android.content.res.Configuration(app.resources.configuration).apply {
                    setLocale(java.util.Locale.forLanguageTag(language))
                }
                val localized = app.createConfigurationContext(configuration)
                var prepared: SuggestedConversationDraft? = null
                var preparedThread: AiConversation? = null
                repository.update { state ->
                    if (suggestionToken != suggestionGeneration.get() || selectionToken != conversationSelectionGeneration) return@update state
                    val item = state.items.find { it.id == itemId } ?: return@update state
                    val prompt = if (stableSuggestionId.startsWith("local:")) {
                        val resource = localSuggestionPromptResource095(item.kind, stableSuggestionId) ?: return@update state
                        localized.getString(resource, item.title)
                    } else {
                        if (!_ui.value.connected) return@update state
                        AiSuggestedInteractions095.resolve(state, itemId, stableSuggestionId,
                            System.currentTimeMillis(), language)?.prompt ?: return@update state
                    }
                    if (state.conversations.size >= ConversationLogic.maxConversations) throw FeedFailure("conversation_limit")
                    // A new thread preserves any unsent text in the previous conversation.
                    val thread = AiConversation(moduleId = item.moduleId, itemId = item.id)
                    val draft = SuggestedConversationDraft(java.util.UUID.randomUUID().toString(), thread.id, prompt)
                    if (suggestionToken != suggestionGeneration.get() || selectionToken != conversationSelectionGeneration) return@update state
                    prepared = draft
                    preparedThread = thread
                    state.copy(conversations = state.conversations + thread)
                }
                if (selectionToken != conversationSelectionGeneration || suggestionToken != suggestionGeneration.get()) return@launch
                val draft = prepared ?: throw FeedFailure("invalid_input")
                val thread = preparedThread ?: throw FeedFailure("invalid_input")
                saveActiveConversationSelection(thread)
                _ui.update { it.copy(activeConversationId = draft.conversationId, conversationOpening = false,
                    conversationReady = true, suggestionDraft = draft, error = null) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (e: Exception) { failConversationSelection(selectionToken, e) }
        }
    }

    fun suggestionDraftApplied(id: String) {
        _ui.update { if (it.suggestionDraft?.id == id) it.copy(suggestionDraft = null) else it }
    }

    private suspend fun appendQuestion(id: String, question: String, stillCurrent: () -> Boolean): AiConversation {
        val clean = question.trim()
        if (clean.isBlank() || clean.length > 2000) throw FeedFailure("invalid_input")
        var appended: AiConversation? = null
        val updated = repository.update { state ->
            if (!stillCurrent()) return@update state
            var found = false
            var candidate: AiConversation? = null
            val conversations = state.conversations.map { thread ->
                if (thread.id != id) thread else {
                    found = true
                    val duplicateRetry = thread.turns.lastOrNull()?.let { it.role == "user" && it.text == clean } == true
                    if (thread.turns.size > ConversationLogic.maxTurns - (if (duplicateRetry) 1 else 2)) throw FeedFailure("conversation_full")
                    val next = thread.copy(title = thread.title.ifBlank { clean.take(160) },
                        turns = if (duplicateRetry) thread.turns else thread.turns + AiTurn(role = "user", text = clean),
                        attachments = thread.attachments.map { if (it.usedAtMillis > 0) it else it.copy(usedAtMillis = maxOf(System.currentTimeMillis(), thread.createdAtMillis)) },
                        updatedAtMillis = maxOf(System.currentTimeMillis(), thread.createdAtMillis))
                    candidate = next
                    next
                }
            }
            if (!found || !stillCurrent()) return@update state
            appended = candidate
            state.copy(conversations = conversations)
        }
        if (appended == null) throw CancellationException("Conversation selection changed before the question was saved")
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
        val selectedUi = _ui.value
        if (askJob?.isActive == true || selectedUi.importWorking || selectedUi.conversationOpening || !selectedUi.conversationReady) return
        val targetConversationId = selectedUi.activeConversationId
        val targetItemId = intendedConversationItemId
        val selectionGeneration = conversationSelectionGeneration
        val generation = ++askGeneration
        askJob = viewModelScope.launch {
            if (selectionGeneration != conversationSelectionGeneration || targetConversationId != _ui.value.activeConversationId) return@launch
            _ui.update { it.copy(asking = true, answer = null, answerCitations = emptyList(), question = question, error = null) }
            try {
                val state = repository.current()
                if (selectionGeneration != conversationSelectionGeneration) return@launch
                val selected = if (targetConversationId != null) {
                    state.conversations.find { it.id == targetConversationId } ?: throw FeedFailure("invalid_input")
                } else {
                    // A missing non-null selection must never be silently downgraded to a
                    // general conversation. Null is only valid for an intentional general ask.
                    if (targetItemId != null) throw FeedFailure("invalid_input")
                    createConversation(stillCurrent = { selectionGeneration == conversationSelectionGeneration })
                        ?: return@launch
                }
                if (targetItemId != null && selected.itemId != targetItemId) throw FeedFailure("invalid_input")
                if (targetConversationId == null) {
                    if (selectionGeneration != conversationSelectionGeneration) return@launch
                    saveActiveConversationSelection(selected)
                    _ui.update { current ->
                        if (selectionGeneration == conversationSelectionGeneration && current.activeConversationId == null)
                            current.copy(activeConversationId = selected.id, conversationReady = true, error = null)
                        else current
                    }
                    if (selectionGeneration != conversationSelectionGeneration || _ui.value.activeConversationId != selected.id) return@launch
                }
                val thread = appendQuestion(selected.id, question) {
                    generation == askGeneration && selectionGeneration == conversationSelectionGeneration
                }
                val requestState = repository.current()
                if (selectionGeneration != conversationSelectionGeneration) return@launch
                val result = withContext(Dispatchers.IO) { engine.askConversation(thread.id, question) { text ->
                    if (generation == askGeneration && selectionGeneration == conversationSelectionGeneration) _ui.update { it.copy(answer = text) }
                } }
                if (generation == askGeneration && selectionGeneration == conversationSelectionGeneration) {
                    appendAnswer(thread, result, requestState) {
                        generation == askGeneration && selectionGeneration == conversationSelectionGeneration
                    }
                    _ui.update { it.copy(answer = null, answerCitations = emptyList(), error = null) }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (e: Exception) { if (generation == askGeneration && selectionGeneration == conversationSelectionGeneration) {
                diagnose("conversation", e); _ui.update { it.copy(error = failureCode(e), answer = null) }
            } }
            finally { if (generation == askGeneration && selectionGeneration == conversationSelectionGeneration) _ui.update { it.copy(asking = false) } }
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
                    ?: createConversation(item.moduleId, itemId, _ui.value.detailTurns.filter { it.role == "assistant" }.take(1)) {
                        generation == detailGeneration
                    } ?: return@launch
                detailConversationId = selected.id
                val thread = appendQuestion(selected.id, question) { generation == detailGeneration }
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

    fun importTextAttachment(uri: Uri) = importTextAttachment(uri, _ui.value.activeConversationId)

    /** The picker owner is captured before provider I/O, never selected from a later screen. */
    fun importTextAttachment(uri: Uri, expectedTarget: String?) {
        val current = _ui.value
        if (current.conversationOpening || !current.conversationReady || current.importWorking || current.asking || current.activeConversationId != expectedTarget) return
        val targetGeneration = askGeneration
        val pendingImportGeneration = importGeneration
        val selectionGeneration = conversationSelectionGeneration
        fun targetUnchanged() = _ui.value.activeConversationId == expectedTarget &&
            !_ui.value.conversationOpening && _ui.value.conversationReady &&
            askGeneration == targetGeneration && importGeneration == pendingImportGeneration &&
            selectionGeneration == conversationSelectionGeneration
        importJob = viewModelScope.launch {
            _ui.update { it.copy(importWorking = true, error = null) }
            try {
                val name = displayName(uri)
                if (!name.endsWith(".txt", true) && !name.endsWith(".md", true)) throw FeedFailure("attachment_type")
                val text = read(uri, ConversationLogic.maxAttachmentBytes)
                if (text.isBlank() || '\u0000' in text || text.length > ConversationLogic.maxAttachmentCharacters) throw FeedFailure("attachment_size")
                if (!targetUnchanged()) return@launch
                val newThread = if (expectedTarget == null) AiConversation() else null
                repository.update { state ->
                    if (!targetUnchanged()) return@update state
                    val selected = if (expectedTarget == null) newThread!! else
                        state.conversations.find { it.id == expectedTarget } ?: throw FeedFailure("invalid_input")
                    if (newThread != null && state.conversations.size >= ConversationLogic.maxConversations) throw FeedFailure("conversation_limit")
                    val updated = if (selected.attachments.any { it.text == text && it.name == name }) selected else {
                        if (selected.attachments.size >= 3 || selected.attachments.sumOf { it.text.length } + text.length > ConversationLogic.maxAttachmentCharacters) throw FeedFailure("attachment_size")
                        selected.copy(attachments = selected.attachments + AiAttachment(name = name, text = text),
                            updatedAtMillis = maxOf(System.currentTimeMillis(), selected.createdAtMillis))
                    }
                    state.copy(conversations = if (newThread != null) state.conversations + updated else
                        state.conversations.map { if (it.id == expectedTarget) updated else it })
                }
                if (newThread != null && targetUnchanged()) {
                    saveActiveConversationSelection(newThread)
                    _ui.update { it.copy(activeConversationId = newThread.id, conversationReady = true) }
                }
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
        val saved = repository.update { s -> s.copy(name = imported.name, region = imported.region, timezone = imported.timezone,
            context = imported.entries.map { it.copy(origin = "imported", global = false) },
            modules = ModuleCatalog.mergeCatalog(imported.modules.ifEmpty { s.modules }),
            items = s.items.map { AiSuggestedInteractions095.clear(it.copy(why = "", relevance = 0, aiRelevance = null)) },
            briefs = emptyList(), lastAiRefreshMillis = 0, lastRefreshMillis = 0) }
        // Publish the exact DataStore result before the completion signal returns to the editor.
        // The repository collector will converge on the same snapshot; this avoids briefly
        // restoring a stale saveable context draft as the user lands on the notes list.
        _ui.update { it.copy(state = saved, notice = "imported", error = null) }
    }
    private suspend fun applyBackup(candidate: FeedState) {
        conversationSelectionGeneration++
        clearSavedConversationSelection()
        cancelConnection(); cancelRefresh(); cancelAiWork()
        // Commit the data first. A failed store write must not erase a working account.
        // A blank plan account prevents old API credentials being used if cleanup fails.
        repository.update { candidate.copy(notifications = false, personalSeedApplied = true, modules = ModuleCatalog.mergeCatalog(candidate.modules),
            items = candidate.items.map { AiSuggestedInteractions095.clear(it.copy(aiRelevance = null)) },
            connection = AiConnection(mode = "chatgpt", model = candidate.connection.model), aiPauseCode = "") }
        apiPresence.definitive(false)
        FeedWorker.cancel(app)
        withContext(Dispatchers.IO) { engine.clearAllCredentials() }
        _ui.update { it.copy(connected = false, activeConversationId = null, conversationOpening = false,
            conversationReady = true, detailItemId = null, detailTurns = emptyList(),
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
        _ui.update { it.copy(importPreview = null, importWorking = false, importSummary = null) }
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
    fun reset(): Job {
        // Invalidate staged objects synchronously; a later confirmation must not resurrect erased data.
        cancelImport()
        clearAnswer()
        conversationSelectionGeneration++
        clearSavedConversationSelection()
        _ui.update { it.copy(activeConversationId = null, conversationOpening = false, conversationReady = true) }
        return work {
            cancelRefresh(); cancelConnection(); cancelAiWork()
            withContext(Dispatchers.IO) { engine.clearAllCredentials() }
            apiPresence.definitive(false)
            repository.erase(); FeedWorker.cancel(app)
            clearSavedConversationSelection()
            _ui.update { it.copy(connected = false, activeConversationId = null, conversationOpening = false,
                conversationReady = true, error = null, notice = null) }
        }
    }

    override fun onCleared() {
        chatGpt.cancelSignIn()
        browserChannel.close()
        super.onCleared()
    }
}
