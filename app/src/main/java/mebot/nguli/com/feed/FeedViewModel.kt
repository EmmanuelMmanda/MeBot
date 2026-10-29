package mebot.nguli.com.feed

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import mebot.nguli.com.MeBotApplication
import mebot.nguli.com.R
import mebot.nguli.com.core.*
import mebot.nguli.com.feed.auth.ChatGptAuthException
import mebot.nguli.com.feed.auth.ChatGptAuthState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.atomic.AtomicLong

data class SuggestedConversationDraft(val id: String, val conversationId: String, val text: String)

/** Durable result for an optional-interests autosave; only SAVED authorizes navigation/merge. */
data class PersonalInterestsSaveResult099(
    val status: Status,
    val state: FeedState? = null,
    val errorCode: String? = null,
) {
    enum class Status { SAVED, SUPERSEDED, FAILED }
}

private data class PersonalInterestsPayload099(val kinds: Set<String>, val customText: String)

private class PersonalInterestsSubmission099(
    val sessionToken: Long,
    val revision: Long,
    val payload: PersonalInterestsPayload099,
    val completion: CompletableDeferred<PersonalInterestsSaveResult099> = CompletableDeferred(),
) {
    @Volatile var completedResult: PersonalInterestsSaveResult099? = null
}

/** Handle returned synchronously for the one explicit, budgeted generation request. */
data class SuggestionGenerationHandle097(
    val requestId: String,
    val job: Job,
    val cancelOnDismiss: Boolean = true,
    /** Per-route lease for shared hidden preparation; explicit sheet requests have no lease. */
    val preparationLeaseId: String? = null,
)

private data class SuggestionSelectionSnapshot097(
    val conversationId: String?,
    val itemId: String?,
    val ready: Boolean,
)

private data class SuggestionRollback097(
    val createdThreadId: String?,
    val originalThread: AiConversation?,
    val appendedThread: AiConversation?,
    val previousSelection: SuggestionSelectionSnapshot097?,
    val epoch: Long,
)

data class FeedUiState(
    val state: FeedState? = null, val loading: Boolean = true,
    val appLanguage: String = "en",
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
    val suggestionWorking: Boolean = false,
    val suggestionDraft: SuggestedConversationDraft? = null,
    val suggestionGeneration: SuggestionGenerationState097? = null,
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
    private val dailySelectionLock098 = Any()
    private var dailySelectionJob098: Job? = null
    private var dailySelectionPending098 = false
    /** Deterministic test barrier for the ensure/coalescing regression; production leaves it inert. */
    internal var afterPersonalDailySelectionCommit098: (suspend () -> Unit)? = null
    private var askJob: Job? = null
    private val suggestionActionLock = Any()
    private val suggestionGenerationLock = Any()
    private var suggestionActionJob: Job? = null
    private var suggestionGenerationJob: Job? = null
    private var suggestionGenerationRequestId: String? = null
    private var suggestionGenerationTarget: SuggestionTargetSnapshot097? = null
    private var suggestionGenerationAppending = false
    private var suggestionGenerationPrefetch = false
    private var suggestionGenerationPrefetchAttemptKey: String? = null
    private val suggestionPrefetchLeases097 = linkedSetOf<String>()
    private val suggestionPrefetchAttempts097 = linkedSetOf<String>()
    private var suggestionAskJob: Job? = null
    private var suggestionDispatchStarted = false
    private var pendingSuggestionThreadId: String? = null
    private var suggestionPreviousSelection: SuggestionSelectionSnapshot097? = null
    private var pendingSuggestionOriginalThread: AiConversation? = null
    private var pendingSuggestionAppendedThread: AiConversation? = null
    /** Fences delayed suggestion cleanup from restoring/deleting rows after backup/reset replacement. */
    @Volatile private var suggestionRollbackEpoch = 0L
    private var lastSuggestionSendKey: String? = null
    private var lastSuggestionSendAtElapsed: Long = 0L
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
    /** Serializes interest autosave commits without canceling an already-started DataStore write. */
    private val personalInterestsWriteMutex099 = Mutex()
    private val personalInterestsEditLock099 = Any()
    private var personalInterestsSessionSequence099 = 0L
    private var activePersonalInterestsSession099 = 0L
    private var activePersonalInterestsRevision099 = -1L
    private var activePersonalInterestsSubmission099: PersonalInterestsSubmission099? = null
    /** Deterministic ordering seam; production leaves this as a no-op. */
    internal var beforePersonalInterestsCommit099: suspend (Long, Long) -> Unit = { _, _ -> }

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
        viewModelScope.launch {
            app.appLock.state.collect { protection ->
                if (protection.locked) {
                    cancelPersonalDailySelectionEnsure098()
                    cancelActiveSuggestionGeneration097()
                }
            }
        }
        viewModelScope.launch { engine.progress.collect { p -> _ui.update { it.copy(refreshProgress=p) } } }
        viewModelScope.launch { AiActivityRegistry.state.collect { tasks -> _ui.update { it.copy(activities = tasks.values.toList()) } } }
        viewModelScope.launch {
            app.repository.state.collect { appState ->
                val selectedLanguage = when (val language = appState.settings.language) {
                    "en", "sw" -> language
                    else -> if (app.resources.configuration.locales[0]?.language == "sw") "sw" else "en"
                }
                _ui.update { it.copy(appLanguage = selectedLanguage) }
            }
        }
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

    /** Starts a fresh, opaque editor generation. Opening the screen itself never writes state. */
    fun beginPersonalInterestsEdit099(): Long = synchronized(personalInterestsEditLock099) {
        check(personalInterestsSessionSequence099 < Long.MAX_VALUE) { "Personal interests editor generation exhausted" }
        val token = ++personalInterestsSessionSequence099
        activePersonalInterestsSession099 = token
        activePersonalInterestsRevision099 = -1L
        activePersonalInterestsSubmission099 = null
        token
    }

    /** One-shot convenience for callers without a retained editor; UI autosaves should pass a token. */
    fun savePersonalInterests099(kinds: Set<String>, customText: String): Job {
        val token = beginPersonalInterestsEdit099()
        return savePersonalInterests099(kinds, customText, token, 0L)
    }

    /**
     * Persist the newest deliberate interest edit. Revisions are ordered only within the opaque
     * editor session, so a delayed disposal from an older screen cannot overwrite a reopened one.
     * The returned Job waits for this caller's result; canceling it does not cancel a DataStore
     * commit already owned by the ViewModel.
     */
    fun savePersonalInterests099(
        kinds: Set<String>,
        customText: String,
        sessionToken: Long,
        revision: Long,
        onComplete: (PersonalInterestsSaveResult099) -> Unit = {},
    ): Job {
        val normalizedKinds = kinds.toSet()
        val normalizedText = customText.trim()
        val allowedKinds = ModuleCatalog.presets.map { it.kind }.toSet()
        if (revision < 0L || !normalizedKinds.all { it in allowedKinds } || !PersonalProfile085.valid(customText)) {
            val failed = PersonalInterestsSaveResult099(
                PersonalInterestsSaveResult099.Status.FAILED,
                errorCode = "invalid_input",
            )
            return deliverPersonalInterestsResult099(CompletableDeferred(failed), onComplete)
        }

        val payload = PersonalInterestsPayload099(normalizedKinds, normalizedText)
        var immediate: PersonalInterestsSaveResult099? = null
        var submission: PersonalInterestsSubmission099? = null
        var startWorker: Job? = null
        synchronized(personalInterestsEditLock099) {
            if (sessionToken == 0L || sessionToken != activePersonalInterestsSession099) {
                immediate = PersonalInterestsSaveResult099(PersonalInterestsSaveResult099.Status.SUPERSEDED)
            } else if (revision < activePersonalInterestsRevision099) {
                immediate = PersonalInterestsSaveResult099(PersonalInterestsSaveResult099.Status.SUPERSEDED)
            } else {
                val current = activePersonalInterestsSubmission099
                if (revision == activePersonalInterestsRevision099 && current != null && current.payload != payload) {
                    immediate = PersonalInterestsSaveResult099(PersonalInterestsSaveResult099.Status.SUPERSEDED)
                } else if (revision == activePersonalInterestsRevision099 && current?.payload == payload &&
                    current.completedResult?.status != PersonalInterestsSaveResult099.Status.FAILED
                ) {
                    // Debounce and explicit Done for the same tuple share the same durable result.
                    submission = current
                } else {
                    val accepted = PersonalInterestsSubmission099(
                        sessionToken = sessionToken,
                        revision = revision,
                        payload = payload,
                    )
                    activePersonalInterestsRevision099 = revision
                    activePersonalInterestsSubmission099 = accepted
                    activePersonalInterestsSession099 = sessionToken
                    startWorker = viewModelScope.launch(start = CoroutineStart.LAZY) {
                        runPersonalInterestsSave099(accepted)
                    }
                    submission = accepted
                }
            }
        }

        immediate?.let { return deliverPersonalInterestsResult099(CompletableDeferred(it), onComplete) }
        val accepted = requireNotNull(submission)
        startWorker?.start()
        return deliverPersonalInterestsResult099(accepted.completion, onComplete)
    }

    private fun deliverPersonalInterestsResult099(
        result: CompletableDeferred<PersonalInterestsSaveResult099>,
        onComplete: (PersonalInterestsSaveResult099) -> Unit,
    ): Job = viewModelScope.launch {
        val completed = result.await()
        runCatching { onComplete(completed) }
    }

    private fun isCurrentPersonalInterestsSubmission099(submission: PersonalInterestsSubmission099): Boolean =
        synchronized(personalInterestsEditLock099) {
            submission.sessionToken == activePersonalInterestsSession099 &&
                activePersonalInterestsSubmission099 === submission &&
                activePersonalInterestsRevision099 == submission.revision
        }

    private suspend fun runPersonalInterestsSave099(submission: PersonalInterestsSubmission099) {
        try {
            val result = personalInterestsWriteMutex099.withLock {
                if (!isCurrentPersonalInterestsSubmission099(submission)) {
                    PersonalInterestsSaveResult099(PersonalInterestsSaveResult099.Status.SUPERSEDED)
                } else {
                    beforePersonalInterestsCommit099(submission.sessionToken, submission.revision)
                    if (!isCurrentPersonalInterestsSubmission099(submission)) {
                        PersonalInterestsSaveResult099(PersonalInterestsSaveResult099.Status.SUPERSEDED)
                    } else {
                        val before = repository.current()
                        val projected = applyPersonalInterests099(before, submission.payload)
                        val contextOrModulesChanged = projected.context != before.context || projected.modules != before.modules
                        if (contextOrModulesChanged) {
                            cancelRefresh()
                            cancelAiWork()
                            lastAutomaticAttemptMillis = 0L
                        }
                        val committed = repository.update { current ->
                            synchronized(personalInterestsEditLock099) {
                                if (submission.sessionToken != activePersonalInterestsSession099 ||
                                    activePersonalInterestsSubmission099 !== submission ||
                                    activePersonalInterestsRevision099 != submission.revision
                                ) current else applyPersonalInterests099(current, submission.payload)
                            }
                        }
                        if (isCurrentPersonalInterestsSubmission099(submission))
                            PersonalInterestsSaveResult099(PersonalInterestsSaveResult099.Status.SAVED, committed)
                        else PersonalInterestsSaveResult099(PersonalInterestsSaveResult099.Status.SUPERSEDED)
                    }
                }
            }
            finishPersonalInterestsSave099(submission, result)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            val result = PersonalInterestsSaveResult099(
                PersonalInterestsSaveResult099.Status.FAILED,
                errorCode = failureCode(error, "storage_error"),
            )
            // The route owns this edit's recoverable error state. Do not leave a stale global
            // FeedUiState error behind after its next retry succeeds or overwrite another error.
            finishPersonalInterestsSave099(submission, result)
        }
    }

    private fun finishPersonalInterestsSave099(
        submission: PersonalInterestsSubmission099,
        result: PersonalInterestsSaveResult099,
    ) {
        synchronized(personalInterestsEditLock099) {
            val currentResult = if (
                result.status != PersonalInterestsSaveResult099.Status.SUPERSEDED &&
                (submission.sessionToken != activePersonalInterestsSession099 ||
                    activePersonalInterestsSubmission099 !== submission ||
                    activePersonalInterestsRevision099 != submission.revision)
            ) PersonalInterestsSaveResult099(PersonalInterestsSaveResult099.Status.SUPERSEDED) else result
            submission.completedResult = currentResult
            submission.completion.complete(currentResult)
        }
    }

    private fun applyPersonalInterests099(
        current: FeedState,
        payload: PersonalInterestsPayload099,
    ): FeedState {
        val presetsById = ModuleCatalog.defaults.associateBy { it.id }
        val selected = payload.kinds
        val updatedModules = current.modules.map { module ->
            val preset = presetsById[module.id]?.takeIf { it.kind == module.kind }
            if (preset != null) module.copy(enabled = preset.id in selected) else module
        }
        val existingPresetIds = updatedModules.mapNotNull { module ->
            presetsById[module.id]?.takeIf { it.kind == module.kind }?.id
        }.toSet()
        val missingSelected = selected - existingPresetIds
        val conflictingIds = updatedModules.asSequence().filter { module ->
            val expectedKind = presetsById[module.id]?.kind
            expectedKind != null && expectedKind != module.kind
        }.map { it.id }.toSet()
        if (missingSelected.any { it in conflictingIds }) throw FeedFailure("module_identity_conflict")
        require(updatedModules.size + missingSelected.size <= ModuleCatalog.maxModules) { "Module capacity reached" }
        val modules = updatedModules + ModuleCatalog.defaults.filter { it.id in missingSelected }.map { it.copy(enabled = true) }

        // Empty edits preserve an existing note: the older helper treats blank as explicit removal.
        val context = if (payload.customText.isBlank()) current.context else {
            val scopes = selected - "weather"
            val old = current.context.firstOrNull { it.id == "onboarding-interests" }
            if (old?.text == payload.customText && old.scopes == scopes && old.confirmed) current.context
            else PersonalProfile085.onboarding(current.context, payload.customText, scopes)
        }
        val setup = Setup099Logic.contextConfirmed(current.setup099, context)
        val personalizationChanged = modules != current.modules || context != current.context
        val configured = current.copy(
            modules = modules,
            context = context,
            setup099 = setup,
            interestsConfigured = true,
        )
        if (!personalizationChanged) return configured
        return configured.copy(
            items = current.items.map { item ->
                AiSuggestedInteractions095.clear(item.copy(why = "", relevance = 0, aiRelevance = null))
            },
            briefs = emptyList(),
            suggestionSets097 = emptyList(),
            personalDailySelection098 = null,
            lastRefreshMillis = 0L,
            lastAiRefreshMillis = 0L,
        )
    }

    fun clearMessage() { _ui.update { it.copy(error = null, notice = null) } }
    fun clearImportSummary() { _ui.update { it.copy(importSummary = null) } }
    fun clearNotice() { _ui.update { it.copy(notice = null) } }
    fun clearAnswer() = clearAnswerInternal(suppressSuggestionRollback = false)
    /** Route transitions may hand off the currently visible non-chat preparation to another route owner. */
    fun clearAnswerForNavigation() = clearAnswerInternal(suppressSuggestionRollback = false, preserveRoutePrefetch = true)
    private fun clearAnswerInternal(suppressSuggestionRollback: Boolean, preserveRoutePrefetch: Boolean = false) {
        suggestionGeneration.incrementAndGet()
        cancelActiveSuggestionGeneration097(preserveRoutePrefetch)
        cancelSuggestionAction(pendingOnly = false,
            restorePreviousSelection = !suppressSuggestionRollback,
            rollbackOnCancel = !suppressSuggestionRollback)
        _ui.update { it.copy(suggestionDraft = null) }
        askGeneration++; askJob?.cancel()
        _ui.update { it.copy(asking = false, answer = null, question = null, answerCitations = emptyList()) }
    }

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
    fun cancelConversationOpen(): Job? =
        cancelConversationOpenInternal(restoreSuggestionSelection = true, rollbackSuggestion = true)

    /** Navigation-only variant; chat-target preparation is stale when leaving, but Home may hand its lease to Detail. */
    fun cancelConversationOpenForNavigation(): Job? =
        cancelConversationOpenInternal(restoreSuggestionSelection = true, rollbackSuggestion = true, preserveRoutePrefetch = true)

    private fun cancelConversationOpenInternal(
        restoreSuggestionSelection: Boolean,
        rollbackSuggestion: Boolean,
        preserveRoutePrefetch: Boolean = false,
    ): Job? {
        cancelActiveSuggestionGeneration097(preserveRoutePrefetch)
        val rollbackJob = cancelSuggestionAction(pendingOnly = true, restorePreviousSelection = restoreSuggestionSelection,
            rollbackOnCancel = rollbackSuggestion)
        if (!_ui.value.conversationOpening) return rollbackJob
        conversationSelectionGeneration++
        _ui.update { it.copy(activeConversationId = null, conversationOpening = false,
            // Keep the pending intent durably bound while hidden; it can be resumed on return.
            conversationReady = false, error = null) }
        return rollbackJob
    }
    /** Reopen a cancelled item target when the user returns to the workspace. */
    fun resumeConversationOpenIfNeeded() {
        val current = _ui.value
        val handlingSuggestion = synchronized(suggestionActionLock) { suggestionActionJob?.isActive == true }
        if (handlingSuggestion || current.suggestionWorking || current.conversationOpening || current.conversationReady || current.activeConversationId != null) return
        if (pendingConversationOpen) openConversation(intendedConversationItemId)
    }
    private fun cancelRefresh() { refreshGeneration++; refreshJob?.cancel(); _ui.update { it.copy(refreshing = false, refreshingModuleId = null) } }
    private fun cancelConnection() { connectGeneration++; connectJob?.cancel(); chatGpt.cancelSignIn(); _ui.update { it.copy(connecting = false, chatGptVerifying = false) } }
    private fun cancelAiWork(discardSuggestionRollback: Boolean = false) {
        if (discardSuggestionRollback) suggestionRollbackEpoch++
        cancelConversationOpenInternal(restoreSuggestionSelection = !discardSuggestionRollback,
            rollbackSuggestion = !discardSuggestionRollback)
        clearAnswerInternal(suppressSuggestionRollback = discardSuggestionRollback)
        cancelDetail(); cancelSearch()
    }
    private fun diagnose(operation: String, error: Exception, moduleId: String? = null) {
        val entry = operationDiagnostic(operation, error, moduleId)
        _ui.update { it.copy(diagnostics = (it.diagnostics + entry).takeLast(20)) }
    }
    fun finishOnboarding() = work {
        repository.update { it.copy(onboardingComplete = true) }
        refresh()
    }
    /** Commit both first-run completion markers together, then begin the ordinary public-feed refresh. */
    fun finishSetup099(onCommitted: () -> Unit = {}) = work {
        repository.update { current ->
            val freshOnboarding = !current.onboardingComplete
            current.copy(
                onboardingComplete = true,
                setup099 = Setup099Logic.finish(
                    current.setup099,
                    preferChatGptIfUnknown = freshOnboarding,
                ),
            )
        }
        onCommitted()
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
    /** Local projection only: no provider call, quota reservation, or source timestamp mutation. */
    fun ensurePersonalDailySelection098() {
        if (app.appLock.state.value.locked) return
        synchronized(dailySelectionLock098) {
            dailySelectionPending098 = true
            if (dailySelectionJob098?.isActive == true) return
            val job = viewModelScope.launch(start = CoroutineStart.LAZY) { drainPersonalDailySelectionEnsures098() }
            dailySelectionJob098 = job
            job.start()
        }
    }

    /** Coalesces UI invalidations that arrive while a prior DataStore projection is finishing. */
    private suspend fun drainPersonalDailySelectionEnsures098() {
        val owner = kotlinx.coroutines.currentCoroutineContext()[Job]
        try {
            while (true) {
                val shouldEnsure = synchronized(dailySelectionLock098) {
                    when {
                        dailySelectionJob098 !== owner -> false
                        app.appLock.state.value.locked -> {
                            dailySelectionPending098 = false
                            dailySelectionJob098 = null
                            false
                        }
                        dailySelectionPending098 -> {
                            dailySelectionPending098 = false
                            true
                        }
                        else -> {
                            // Clear atomically with the empty-queue check so a concurrent request
                            // either becomes pending for this worker or starts a new one.
                            dailySelectionJob098 = null
                            false
                        }
                    }
                }
                if (!shouldEnsure) return
                repository.update { state ->
                    if (app.appLock.state.value.locked) state
                    else PersonalDailySelectionLogic098.ensure(state, System.currentTimeMillis())
                }
                afterPersonalDailySelectionCommit098?.invoke()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            synchronized(dailySelectionLock098) {
                if (dailySelectionJob098 === owner) {
                    // Detach this failed batch atomically; a new request after this point must
                    // start its own worker and must not be erased by the old worker's finally.
                    dailySelectionPending098 = false
                    dailySelectionJob098 = null
                    _ui.update { it.copy(error = failureCode(error, "storage_error")) }
                }
            }
        } finally {
            synchronized(dailySelectionLock098) {
                if (dailySelectionJob098 === owner) {
                    dailySelectionPending098 = false
                    dailySelectionJob098 = null
                }
            }
        }
    }

    private fun cancelPersonalDailySelectionEnsure098() {
        synchronized(dailySelectionLock098) {
            dailySelectionPending098 = false
            dailySelectionJob098?.cancel()
            dailySelectionJob098 = null
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
                if (generation == refreshGeneration) {
                    repository.update { state -> PersonalDailySelectionLogic098.ensure(state, System.currentTimeMillis(), refresh = manual) }
                    _ui.update { it.copy(error = result.error, notice = result.notice) }
                }
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
                if (generation == connectGeneration && connection.mode != "chatgpt") {
                    repository.update { current ->
                        if (current.connection == connection) current.copy(setup099 = Setup099Logic.recordPreferredConnection(
                            current.setup099, Setup099ConnectionEntry.USER_SELECTED,
                        )) else current
                    }
                }
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
                if (generation == connectGeneration) {
                    repository.update { current ->
                        if (current.connection.mode == "chatgpt" && current.connection.accountId == account.id)
                            current.copy(setup099 = Setup099Logic.recordPreferredConnection(
                                current.setup099, Setup099ConnectionEntry.CHATGPT,
                            )) else current
                    }
                    _ui.update { it.copy(connected = true, notice = "connected", error = null) }
                }
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
                if (generation == connectGeneration) {
                    repository.update { current ->
                        if (current.connection.mode == "chatgpt" && current.connection.accountId == id)
                            current.copy(setup099 = Setup099Logic.recordPreferredConnection(
                                current.setup099, Setup099ConnectionEntry.CHATGPT,
                            )) else current
                    }
                    _ui.update { it.copy(connected = true, notice = "connected", error = null) }
                }
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
    /**
     * Toggle a follow and optionally report the committed snapshot. The callback runs only
     * after persistence succeeds so UI feedback never promises a follow that failed to save.
     */
    fun toggleStar(id: String, onCommitted: ((FeedState) -> Unit)? = null) = work {
        val updated = repository.update { StarredStories.toggle(it, id, System.currentTimeMillis()) }
        onCommitted?.invoke(updated)
    }
    /** Idempotent desired-state write for rapid taps; success feedback receives only the durable snapshot. */
    fun setStarred099(id: String, desired: Boolean, onCommitted: ((FeedState) -> Unit)? = null) = work {
        var changed = false
        val updated = repository.update { current ->
            require(current.items.any { it.id == id })
            val alreadyStarred = current.starredStories.any { it.itemId == id }
            if (alreadyStarred == desired) current
            else if (desired) {
                require(current.starredStories.size < 100)
                changed = true
                current.copy(starredStories = current.starredStories + StarredStory(id,
                    starredAtMillis = System.currentTimeMillis()))
            } else {
                changed = true
                current.copy(starredStories = current.starredStories.filterNot { it.itemId == id })
            }
        }
        if (changed) onCommitted?.invoke(updated)
    }
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

    /** Called only after a ChatGPT account has been verified; callers must opt into eligible setup. */
    fun recordSetup099ChatGptConfirmed(accountKey: String, setupEligible: Boolean = false) = work {
        val presented = _ui.value
        repository.update { current ->
            val verifiedActiveAccount = setupEligible && accountKey.isNotBlank() &&
                current.connection.mode == "chatgpt" && current.connection.accountId == accountKey &&
                presented.connected && !presented.connecting && !presented.chatGptVerifying &&
                !presented.providerAuthBusy && presented.error == null &&
                presented.chatGpt.activeAccountId == accountKey &&
                presented.chatGpt.accounts.any { it.id == accountKey && it.connected && it.planEnabled }
            val setup = if (verifiedActiveAccount) Setup099Logic.recordChatGptSuccess(
                state = current.setup099,
                accountKey = accountKey,
                confirmed = true,
                hasConfirmedContext = Setup099Logic.hasConfirmedContext(current.context),
                setupEligible = true,
            ) else current.setup099
            current.copy(setup099 = setup)
        }
    }

    /** Consume only the pending first-account import route after its destination was entered. */
    fun acknowledgeSetup099ImportRoute(accountKey: String) = work {
        repository.update { current -> current.copy(
            setup099 = Setup099Logic.acknowledgeImportRoute(current.setup099, accountKey),
        ) }
    }

    /** Advance only when at least one confirmed, nonblank context entry is durably present. */
    fun advanceSetup099ToStart() = work {
        repository.update { current -> current.copy(
            setup099 = Setup099Logic.contextConfirmed(current.setup099, current.context),
        ) }
    }

    /** Explicitly continue without adding personal context; public feed reading remains available. */
    fun skipSetup099Context() = work {
        repository.update { current -> current.copy(setup099 = Setup099Logic.skipContext(current.setup099)) }
    }

    /** Invalid stage values and transitions out of completion are rejected by the pure state logic. */
    fun setSetup099Stage(stage: String) = work {
        repository.update { current -> current.copy(setup099 = Setup099Logic.setStage(current.setup099, stage)) }
    }

    fun dismissSetupNotice(key: String) = work {
        require(key in Setup099Logic.tipKeys + setOf("connection", "preferences"))
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
                    setup099 = Setup099Logic.contextConfirmed(s.setup099, entries),
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

    /** Legacy convenience seam: only a current provider-authored option may populate a draft. */
    fun prepareSuggestion(itemId: String, stableSuggestionId: String, appLanguage: String): Job {
        clearAnswer()
        val suggestionToken = suggestionGeneration.get()
        val selectionToken = beginConversationSelection(itemId)
        return viewModelScope.launch {
            try {
                val language = appLanguage.takeIf { it == "sw" } ?: "en"
                if (briefLanguage() != language) throw FeedFailure("suggestion_changed")
                var prepared: SuggestedConversationDraft? = null
                var preparedThread: AiConversation? = null
                repository.update { state ->
                    if (suggestionToken != suggestionGeneration.get() || selectionToken != conversationSelectionGeneration) return@update state
                    val item = state.items.find { it.id == itemId } ?: return@update state
                    val target = SuggestionTargets097.item(state, itemId, language) ?: return@update state
                    val prompt = resolveSuggestion097(state, target, stableSuggestionId, language) ?: return@update state
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

    /** Read-only prepared snapshot. This never contacts the provider or changes a draft. */
    fun suggestionSheet097(target: SuggestionTargetSnapshot097, appLanguage: String): SuggestionSheetSnapshot097 {
        val uiState = _ui.value
        val state = uiState.state ?: return SuggestionSheetSnapshot097(target, SuggestionSheetStatus097.UNAVAILABLE,
            failureCode = "suggestion_unavailable")
        val language = appLanguage.takeIf { it in setOf("en", "sw") } ?: "en"
        if (uiState.appLanguage != language || !SuggestionTargets097.current(state, target, language))
            return SuggestionSheetSnapshot097(target, SuggestionSheetStatus097.STALE_TARGET, failureCode = "suggestion_changed")
        val now = System.currentTimeMillis()
        val batches = cachedSuggestionSets097(state, target, language, now)
        val prepared = optionsFromBatches097(batches)
        val capacity = batches.size < AiSuggestionCache097.MAX_BATCHES_PER_TARGET
        val generation = uiState.suggestionGeneration?.takeIf { it.target == target }
        if (prepared.isNotEmpty()) {
            val moreIsRunning = generation?.appending == true && generation.status == SuggestionSheetStatus097.GENERATING_MORE
            val moreFailed = generation?.appending == true && generation.status != SuggestionSheetStatus097.GENERATING_MORE
            return SuggestionSheetSnapshot097(target, SuggestionSheetStatus097.READY, prepared,
                requestId = generation?.requestId,
                validUntilMillis = batches.minOfOrNull { it.generatedAtMillis + AiSuggestionCache097.CACHE_TTL_MILLIS },
                canLoadMore = capacity,
                loadingMore = moreIsRunning,
                loadMoreFailureCode = if (moreFailed) generation?.failureCode else null)
        }
        if (generation?.status == SuggestionSheetStatus097.GENERATING)
            return SuggestionSheetSnapshot097(target, SuggestionSheetStatus097.GENERATING,
                requestId = generation.requestId)
        generation?.let { return SuggestionSheetSnapshot097(target, it.status, requestId = it.requestId,
            failureCode = it.failureCode) }
        if (!uiState.connected || app.appLock.state.value.locked)
            return SuggestionSheetSnapshot097(target, SuggestionSheetStatus097.UNAVAILABLE, failureCode = "suggestion_unavailable")
        return SuggestionSheetSnapshot097(target, SuggestionSheetStatus097.NEEDS_GENERATION)
    }

    /** Compatibility accessor for non-sheet clients; options are only AI-generated questions. */
    fun suggestionOptions097(target: SuggestionTargetSnapshot097, appLanguage: String): List<SuggestionOptionSnapshot097> =
        suggestionSheet097(target, appLanguage).options

    private fun optionsForState097(
        state: FeedState,
        target: SuggestionTargetSnapshot097,
        appLanguage: String,
        now: Long,
    ): List<SuggestionOptionSnapshot097> = if (!SuggestionTargets097.current(state, target, appLanguage)) emptyList()
        else optionsFromBatches097(cachedSuggestionSets097(state, target, appLanguage, now))

    private fun optionsFromBatches097(batches: List<AiSuggestionSet097>): List<SuggestionOptionSnapshot097> {
        val seen = mutableSetOf<String>()
        return batches.flatMap { set -> set.questions.mapNotNull { question ->
            val normalized = question.trim().lowercase(java.util.Locale.ROOT).replace(Regex("\\s+"), " ")
            if (!seen.add(normalized)) null else SuggestionOptionSnapshot097(
                AiSuggestionCache097.optionId(set, question), question, set.generatedAtMillis)
        } }
    }

    private fun cachedSuggestionSets097(
        state: FeedState,
        target: SuggestionTargetSnapshot097,
        appLanguage: String,
        now: Long,
    ): List<AiSuggestionSet097> {
        val kind = target.kind.name.lowercase(java.util.Locale.ROOT)
        val stored = state.suggestionSets097.filter { record ->
            AiSuggestionCache097.isFresh(record, kind, target.targetId, target.revision, now)
        }.sortedBy { it.generatedAtMillis }
        val itemCache = if (target.kind == SuggestionTargetKind097.ITEM) {
            state.items.singleOrNull { it.id == target.itemId }?.takeIf {
                AiSuggestedInteractions095.isApplicable(state, it, now, appLanguage)
            }?.let { item -> AiSuggestionSet097(kind, target.targetId, target.revision,
                item.suggestedInteractions.map { it.prompt }, item.suggestedInteractionsGeneratedAtMillis) }
        } else if (target.kind == SuggestionTargetKind097.CONVERSATION) {
            val thread = state.conversations.singleOrNull { it.id == target.targetId }
            val item = thread?.takeIf { it.turns.isEmpty() && it.attachments.isEmpty() }?.itemId?.let { id ->
                state.items.singleOrNull { it.id == id }
            }
            item?.takeIf { AiSuggestedInteractions095.isApplicable(state, it, now, appLanguage) }
                ?.let { AiSuggestionSet097(kind, target.targetId, target.revision,
                    it.suggestedInteractions.map { interaction -> interaction.prompt }, it.suggestedInteractionsGeneratedAtMillis) }
        } else null
        return (stored + listOfNotNull(itemCache)).filter(AiSuggestionCache097::isValid)
            .distinctBy(AiSuggestionCache097::recordIdentity).sortedBy { it.generatedAtMillis }
    }

    /** Prepare once at a stable route/feed-ready transition; a failed prefetch never self-retries. */
    fun prepareSuggestions097(target: SuggestionTargetSnapshot097, appLanguage: String): SuggestionGenerationHandle097? {
        val language = appLanguage.takeIf { it in setOf("en", "sw") } ?: "en"
        val connectionKey = _ui.value.state?.connection?.let { "${it.mode}:${it.accountId}:${it.model}:${it.serviceUrl}" }.orEmpty()
        val attemptKey = "$connectionKey:${target.kind}:${target.targetId}:${target.revision}:$language"
        synchronized(suggestionGenerationLock) {
            suggestionGenerationJob?.takeIf { it.isActive }?.let { active ->
                if (suggestionGenerationTarget == target && !suggestionGenerationAppending) {
                    val isPrefetch = suggestionGenerationPrefetch
                    val lease = if (isPrefetch) java.util.UUID.randomUUID().toString() else null
                    lease?.let(suggestionPrefetchLeases097::add)
                    return SuggestionGenerationHandle097(
                        suggestionGenerationRequestId ?: return null,
                        active,
                        cancelOnDismiss = !isPrefetch,
                        preparationLeaseId = lease,
                    )
                }
                if (suggestionGenerationTarget == target || !suggestionGenerationPrefetch) return null
                // A different stable target supersedes a hidden preparation, never explicit work.
                cancelActiveSuggestionGeneration097()
            }
            if (attemptKey in suggestionPrefetchAttempts097) return null
            val state = _ui.value.state ?: return null
            if (cachedSuggestionSets097(state, target, language, System.currentTimeMillis()).isNotEmpty()) return null
            val handle = requestSuggestions097(target, language, append = false, prefetch = true,
                prefetchAttemptKey = attemptKey) ?: return null
            if (!handle.cancelOnDismiss) {
                suggestionPrefetchAttempts097 += attemptKey
                while (suggestionPrefetchAttempts097.size > 256) suggestionPrefetchAttempts097.remove(suggestionPrefetchAttempts097.first())
                if (!handle.job.isActive) return handle
                val lease = java.util.UUID.randomUUID().toString()
                suggestionPrefetchLeases097 += lease
                return handle.copy(preparationLeaseId = lease)
            }
            return handle
        }
    }

    /**
     * Bridge a currently-running Home/route preparation across a navigation transition before
     * the old route's lifecycle lease is released. This is deliberately adoption-only: it never
     * starts generation, reserves budget, or calls the provider. The destination may subsequently
     * acquire its ordinary route lease through [prepareSuggestions097].
     */
    fun retainActiveSuggestionPreparationForNavigation097(
        target: SuggestionTargetSnapshot097,
    ): SuggestionGenerationHandle097? = synchronized(suggestionGenerationLock) {
        val job = suggestionGenerationJob?.takeIf { it.isActive } ?: return@synchronized null
        if (!suggestionGenerationPrefetch || suggestionGenerationAppending || suggestionGenerationTarget != target)
            return@synchronized null
        val requestId = suggestionGenerationRequestId ?: return@synchronized null
        val lease = java.util.UUID.randomUUID().toString()
        suggestionPrefetchLeases097 += lease
        SuggestionGenerationHandle097(
            requestId = requestId,
            job = job,
            cancelOnDismiss = false,
            preparationLeaseId = lease,
        )
    }

    /** Release one route's ownership; a handoff keeps shared preparation alive while another route owns it. */
    fun releaseSuggestionPreparation097(handle: SuggestionGenerationHandle097) {
        val lease = handle.preparationLeaseId ?: return
        val canceled = synchronized(suggestionGenerationLock) {
            if (!suggestionPrefetchLeases097.remove(lease) || suggestionPrefetchLeases097.isNotEmpty() ||
                suggestionGenerationRequestId != handle.requestId || !suggestionGenerationPrefetch) return
            suggestionGenerationJob.also {
                suggestionGenerationJob = null
                suggestionGenerationRequestId = null
                suggestionGenerationTarget = null
                suggestionGenerationAppending = false
                suggestionGenerationPrefetch = false
                suggestionGenerationPrefetchAttemptKey?.let(suggestionPrefetchAttempts097::remove)
                suggestionGenerationPrefetchAttemptKey = null
            }
        }
        canceled?.cancel()
        if (canceled != null) _ui.update {
            if (it.suggestionGeneration?.requestId == handle.requestId) it.copy(suggestionGeneration = null) else it
        }
    }

    /** Explicit icon-tap fallback for an empty cache; duplicate taps coalesce on the same target. */
    fun generateSuggestions097(target: SuggestionTargetSnapshot097, appLanguage: String): SuggestionGenerationHandle097? =
        requestSuggestions097(target, appLanguage, append = false, prefetch = false)

    /** Explicit Load more action appends one new provider batch and never replaces earlier choices. */
    fun loadMoreSuggestions097(target: SuggestionTargetSnapshot097, appLanguage: String): SuggestionGenerationHandle097? =
        requestSuggestions097(target, appLanguage, append = true, prefetch = false)

    private fun requestSuggestions097(
        target: SuggestionTargetSnapshot097,
        appLanguage: String,
        append: Boolean,
        prefetch: Boolean,
        prefetchAttemptKey: String? = null,
    ): SuggestionGenerationHandle097? {
        val language = appLanguage.takeIf { it in setOf("en", "sw") } ?: "en"
        synchronized(suggestionGenerationLock) {
            suggestionGenerationJob?.takeIf { it.isActive }?.let { active ->
                if (suggestionGenerationTarget == target && suggestionGenerationAppending == append)
                    return SuggestionGenerationHandle097(suggestionGenerationRequestId ?: return null, active,
                        cancelOnDismiss = !suggestionGenerationPrefetch)
                // A stable new active target supersedes only hidden preparation. Never cancel
                // an explicit user generation for another target behind their back.
                if (!suggestionGenerationPrefetch) return null
                cancelActiveSuggestionGeneration097()
            }
            val currentUi = _ui.value
            val state = currentUi.state ?: return null
            val actionAlreadyWorking = synchronized(suggestionActionLock) { suggestionActionJob?.isActive == true }
            if (!currentUi.connected || currentUi.appLanguage != language || currentUi.asking || currentUi.importWorking || currentUi.conversationOpening ||
                currentUi.suggestionWorking || actionAlreadyWorking || app.appLock.state.value.locked ||
                !SuggestionTargets097.current(state, target, language)) return null
            val cached = cachedSuggestionSets097(state, target, language, System.currentTimeMillis())
            if (append && cached.isEmpty()) return null
            if (!append && cached.isNotEmpty()) return null
            if (append && cached.size >= AiSuggestionCache097.MAX_BATCHES_PER_TARGET) return null
            val requestId = java.util.UUID.randomUUID().toString()
            val busyStatus = if (append) SuggestionSheetStatus097.GENERATING_MORE else SuggestionSheetStatus097.GENERATING
            lateinit var launched: Job
            launched = viewModelScope.launch(start = CoroutineStart.LAZY) {
                try {
                    _ui.update { it.copy(suggestionGeneration = SuggestionGenerationState097(
                        requestId, target, busyStatus, appending = append, prefetch = prefetch)) }
                    if (briefLanguage() != language || app.appLock.state.value.locked) throw FeedFailure("suggestion_changed")
                    val initial = repository.current()
                    if (!SuggestionTargets097.current(initial, target, language)) throw FeedFailure("suggestion_changed")
                    if (target.kind == SuggestionTargetKind097.CONVERSATION && _ui.value.activeConversationId != target.targetId)
                        throw FeedFailure("suggestion_changed")
                    withContext(Dispatchers.IO) {
                        engine.generateSuggestionSet097(
                            target = target,
                            expectedLanguage = language,
                            expectedCurrent = { latest ->
                                val latestUi = _ui.value
                                latestUi.appLanguage == language && !app.appLock.state.value.locked &&
                                    (target.kind != SuggestionTargetKind097.CONVERSATION ||
                                        (latestUi.activeConversationId == target.targetId && latestUi.conversationReady &&
                                            !latestUi.conversationOpening)) &&
                                    !latestUi.asking && !latestUi.importWorking &&
                                    synchronized(suggestionGenerationLock) {
                                        suggestionGenerationRequestId == requestId && suggestionGenerationJob?.isActive == true
                                    } && synchronized(suggestionActionLock) { suggestionActionJob?.isActive != true } &&
                                    SuggestionTargets097.current(latest, target, language)
                            },
                            onRequestDispatch = {
                                synchronized(suggestionGenerationLock) {
                                    if (suggestionGenerationRequestId != requestId || suggestionGenerationJob?.isActive != true ||
                                        app.appLock.state.value.locked)
                                        throw CancellationException("Suggestion generation cancelled before dispatch")
                                }
                            },
                        )
                    }
                    val updated = repository.current()
                    if (!SuggestionTargets097.current(updated, target, language) || briefLanguage() != language ||
                        app.appLock.state.value.locked) throw FeedFailure("suggestion_changed")
                    _ui.update { it.copy(state = updated, suggestionGeneration = null, error = null) }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    val rawCode = failureCode(error, "suggestion_failed")
                    val code = when (rawCode) {
                        "suggestion_incomplete", "suggestion_changed", "suggestion_unavailable", "suggestion_limit" -> rawCode
                        "unauthorized", "chatgpt_reauth", "quota", "limit_reached", "rate_limit", "offline" -> rawCode
                        else -> "suggestion_failed"
                    }
                    _ui.update { current ->
                        val sameGeneration = current.suggestionGeneration?.requestId == requestId
                        if (sameGeneration) current.copy(suggestionGeneration = SuggestionGenerationState097(
                            requestId, target,
                            when (code) {
                                "suggestion_incomplete" -> SuggestionSheetStatus097.INCOMPLETE
                                "suggestion_limit" -> SuggestionSheetStatus097.LIMIT_REACHED
                                "suggestion_unavailable", "unauthorized", "chatgpt_reauth", "offline" -> SuggestionSheetStatus097.UNAVAILABLE
                                else -> SuggestionSheetStatus097.ERROR
                            }, code, appending = append, prefetch = prefetch)) else current
                    }
                } finally {
                    val released = synchronized(suggestionGenerationLock) {
                        if (suggestionGenerationRequestId == requestId) {
                            suggestionGenerationJob = null
                            suggestionGenerationRequestId = null
                            suggestionGenerationTarget = null
                            suggestionGenerationAppending = false
                            suggestionGenerationPrefetch = false
                            suggestionGenerationPrefetchAttemptKey = null
                            suggestionPrefetchLeases097.clear()
                            true
                        } else false
                    }
                    if (released) _ui.update { current ->
                        val generation = current.suggestionGeneration
                        if (generation?.requestId == requestId && generation.status in setOf(
                            SuggestionSheetStatus097.GENERATING, SuggestionSheetStatus097.GENERATING_MORE))
                            current.copy(suggestionGeneration = null) else current
                    }
                }
            }
            suggestionGenerationJob = launched
            suggestionGenerationRequestId = requestId
            suggestionGenerationTarget = target
            suggestionGenerationAppending = append
            suggestionGenerationPrefetch = prefetch
            suggestionGenerationPrefetchAttemptKey = prefetchAttemptKey.takeIf { prefetch }
            _ui.update { it.copy(suggestionGeneration = SuggestionGenerationState097(
                requestId, target, busyStatus, appending = append, prefetch = prefetch)) }
            launched.start()
            return SuggestionGenerationHandle097(requestId, launched, cancelOnDismiss = !prefetch)
        }
    }

    fun cancelSuggestionGeneration097(requestId: String) {
        val job = synchronized(suggestionGenerationLock) {
            if (suggestionGenerationRequestId != requestId) return
            val current = suggestionGenerationJob
            suggestionGenerationJob = null
            suggestionGenerationRequestId = null
            suggestionGenerationTarget = null
            suggestionGenerationAppending = false
            suggestionGenerationPrefetch = false
            suggestionGenerationPrefetchAttemptKey?.let(suggestionPrefetchAttempts097::remove)
            suggestionGenerationPrefetchAttemptKey = null
            suggestionPrefetchLeases097.clear()
            current
        }
        job?.cancel()
        _ui.update { if (it.suggestionGeneration?.requestId == requestId) it.copy(suggestionGeneration = null) else it }
    }

    /** ON_STOP must invalidate every hidden route lease, including a navigation bridge, without
     * canceling work the person explicitly opened and is actively using. */
    fun cancelSuggestionPreparationsOnBackground099() {
        cancelActiveSuggestionGeneration097(prefetchOnly = true)
    }

    private fun cancelActiveSuggestionGeneration097(
        preserveRoutePrefetch: Boolean = false,
        prefetchOnly: Boolean = false,
    ) {
        val requestId = synchronized(suggestionGenerationLock) {
            if (prefetchOnly && !suggestionGenerationPrefetch) return
            if (preserveRoutePrefetch && suggestionGenerationPrefetch &&
                suggestionPrefetchLeases097.isNotEmpty() &&
                suggestionGenerationTarget?.kind != SuggestionTargetKind097.CONVERSATION) return
            suggestionGenerationRequestId
        } ?: return
        val job = synchronized(suggestionGenerationLock) {
            if (suggestionGenerationRequestId != requestId) return
            suggestionGenerationJob.also {
                suggestionGenerationJob = null
                suggestionGenerationRequestId = null
                suggestionGenerationTarget = null
                suggestionGenerationAppending = false
                suggestionGenerationPrefetch = false
                suggestionGenerationPrefetchAttemptKey?.let(suggestionPrefetchAttempts097::remove)
                suggestionGenerationPrefetchAttemptKey = null
                suggestionPrefetchLeases097.clear()
            }
        }
        job?.cancel()
        _ui.update { if (it.suggestionGeneration?.requestId == requestId) it.copy(suggestionGeneration = null) else it }
    }

    private fun resolveSuggestion097(
        state: FeedState,
        target: SuggestionTargetSnapshot097,
        stableOptionId: String,
        appLanguage: String,
    ): String? {
        optionsForState097(state, target, appLanguage, System.currentTimeMillis())
            .singleOrNull { it.id == stableOptionId }?.let { return it.questionText }
        // Compatibility for a previously rendered provider-generated item ID. This still resolves
        // only from a fresh complete AI cache; no local or caller-supplied text can pass this path.
        if (target.kind == SuggestionTargetKind097.ITEM) return target.itemId?.let { id ->
            AiSuggestedInteractions095.resolve(state, id, stableOptionId, System.currentTimeMillis(), appLanguage)?.prompt
        }
        return null
    }

    private fun startSuggestionAction(action: suspend () -> Unit): Job = synchronized(suggestionActionLock) {
        suggestionActionJob?.takeIf { it.isActive }?.let { return@synchronized it }
        val before = _ui.value
        suggestionDispatchStarted = false
        pendingSuggestionThreadId = null
        suggestionAskJob = null
        pendingSuggestionOriginalThread = null
        pendingSuggestionAppendedThread = null
        suggestionPreviousSelection = SuggestionSelectionSnapshot097(before.activeConversationId, intendedConversationItemId, before.conversationReady)
        lateinit var launched: Job
        launched = viewModelScope.launch(start = CoroutineStart.LAZY) {
            _ui.update { it.copy(suggestionWorking = true, error = null) }
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (e: Exception) {
                if (!synchronized(suggestionActionLock) { suggestionDispatchStarted })
                    runCatching { rollbackSuggestionBeforeDispatch() }
                diagnose("suggestion", e)
                _ui.update { it.copy(error = failureCode(e)) }
            } finally {
                val released = synchronized(suggestionActionLock) {
                    if (suggestionActionJob === launched) {
                        suggestionActionJob = null; suggestionDispatchStarted = false; pendingSuggestionThreadId = null
                        suggestionAskJob = null; suggestionPreviousSelection = null
                        pendingSuggestionOriginalThread = null; pendingSuggestionAppendedThread = null; true
                    } else false
                }
                if (released) _ui.update { it.copy(suggestionWorking = false) }
            }
        }
        suggestionActionJob = launched
        launched.start()
        launched
    }

    /** Cancel a not-yet-dispatched suggestion when leaving the workspace; lock/cancel paths cancel all work. */
    private fun cancelSuggestionAction(
        pendingOnly: Boolean,
        restorePreviousSelection: Boolean = false,
        rollbackOnCancel: Boolean = true,
    ): Job? {
        var cancelledThreadId: String? = null
        var selectionToRestore: SuggestionSelectionSnapshot097? = null
        var originalThread: AiConversation? = null
        var appendedThread: AiConversation? = null
        var rollbackBeforeDispatch = false
        var selectionGenerationAtCancel = 0L
        var rollbackEpochAtCancel = 0L
        val cancelled = synchronized(suggestionActionLock) {
            val job = suggestionActionJob
            if (job == null || !job.isActive || (pendingOnly && suggestionDispatchStarted)) false
            else {
                cancelledThreadId = pendingSuggestionThreadId
                if (restorePreviousSelection && rollbackOnCancel) selectionToRestore = suggestionPreviousSelection
                rollbackBeforeDispatch = !suggestionDispatchStarted && rollbackOnCancel
                selectionGenerationAtCancel = conversationSelectionGeneration
                rollbackEpochAtCancel = suggestionRollbackEpoch
                if (rollbackBeforeDispatch) {
                    originalThread = pendingSuggestionOriginalThread
                    appendedThread = pendingSuggestionAppendedThread
                }
                // Main.immediate (and Unconfined test dispatchers) can synchronously run the
                // action's finally block on cancel and clear suggestionAskJob. Preserve the child.
                val askJobToCancel = suggestionAskJob
                job.cancel()
                askJobToCancel?.cancel()
                suggestionActionJob = null
                suggestionDispatchStarted = false
                pendingSuggestionThreadId = null
                suggestionAskJob = null
                suggestionPreviousSelection = null
                pendingSuggestionOriginalThread = null; pendingSuggestionAppendedThread = null
                true
            }
        }
        if (!cancelled) return null
        _ui.update { it.copy(suggestionWorking = false) }
        return viewModelScope.launch {
            val updated = repository.update { state ->
                var conversations = state.conversations
                val id = cancelledThreadId
                val original = originalThread
                val appended = appendedThread
                if (rollbackBeforeDispatch && rollbackEpochAtCancel == suggestionRollbackEpoch && original != null && appended != null &&
                    conversations.singleOrNull { it.id == original.id } == appended) {
                    conversations = conversations.map { if (it.id == original.id) original else it }
                }
                if (rollbackBeforeDispatch && rollbackEpochAtCancel == suggestionRollbackEpoch && id != null) {
                    val pending = conversations.singleOrNull { it.id == id }
                    if (pending != null && pending.turns.isEmpty() && pending.attachments.isEmpty())
                        conversations = conversations.filterNot { it.id == id }
                }
                if (conversations != state.conversations) state.copy(conversations = conversations) else state
            }
            if (rollbackBeforeDispatch && rollbackEpochAtCancel == suggestionRollbackEpoch &&
                conversationSelectionGeneration == selectionGenerationAtCancel)
                selectionToRestore?.let { restoreSuggestionSelection(it, updated) }
        }
    }

    private fun restoreSuggestionSelection(snapshot: SuggestionSelectionSnapshot097, state: FeedState) {
        val previousId = snapshot.conversationId
        val previousThread = previousId?.let { id -> state.conversations.singleOrNull { it.id == id } }
        when {
            previousThread != null && previousThread.itemId == snapshot.itemId -> {
                saveActiveConversationSelection(previousThread)
                _ui.update { it.copy(activeConversationId = previousThread.id, conversationOpening = false,
                    conversationReady = true) }
            }
            previousId == null && snapshot.itemId == null && snapshot.ready -> {
                clearSavedConversationSelection()
                _ui.update { it.copy(activeConversationId = null, conversationOpening = false,
                    conversationReady = true) }
            }
            else -> {
                saveBlockedConversationSelection(snapshot.itemId)
                _ui.update { it.copy(activeConversationId = null, conversationOpening = false,
                    conversationReady = false, error = it.error ?: "invalid_input") }
            }
        }
    }

    /** Primary next-step action: re-resolve the displayed ID and immediately send exactly once. */
    fun sendSuggestion(target: SuggestionTargetSnapshot097, stableOptionId: String, appLanguage: String): Job {
        val actionKey = "${target.kind}|${target.targetId.orEmpty()}|${target.revision}|$stableOptionId"
        synchronized(suggestionActionLock) {
            val now = android.os.SystemClock.elapsedRealtime()
            if (lastSuggestionSendKey == actionKey && now - lastSuggestionSendAtElapsed in 0..1_500L) {
                return suggestionActionJob?.takeIf { it.isActive } ?: Job().apply { complete() }
            }
            lastSuggestionSendKey = actionKey
            lastSuggestionSendAtElapsed = now
        }
        return startSuggestionAction {
            val language = appLanguage.takeIf { it == "sw" } ?: "en"
            if (briefLanguage() != language) throw FeedFailure("suggestion_changed")
            val uiAtTap = _ui.value
            if (app.appLock.state.value.locked) throw FeedFailure("suggestion_changed")
            if (uiAtTap.asking || askJob?.isActive == true || uiAtTap.importWorking || uiAtTap.conversationOpening || !uiAtTap.conversationReady)
                throw FeedFailure("invalid_input")
            if (!uiAtTap.connected) throw FeedFailure("unauthorized")
            val selectionAtTap = conversationSelectionGeneration
            if (target.kind == SuggestionTargetKind097.CONVERSATION && uiAtTap.activeConversationId != target.targetId)
                throw FeedFailure("suggestion_changed")
            val initial = repository.current()
            if (selectionAtTap != conversationSelectionGeneration || !SuggestionTargets097.current(initial, target, language))
                throw FeedFailure("suggestion_changed")
            val question = resolveSuggestion097(initial, target, stableOptionId, language) ?: throw FeedFailure("suggestion_changed")
            if (question.isBlank() || question.length > 2000) throw FeedFailure("invalid_input")
            // Reuses the existing deterministic pre-open seam. Production leaves it as a no-op;
            // tests can hold this point to verify navigation/lock cancellation before routing.
            beforeConversationOpen(target.itemId)
            if (selectionAtTap != conversationSelectionGeneration) throw CancellationException("Suggestion target changed")

            val thread = if (target.kind == SuggestionTargetKind097.CONVERSATION) {
                initial.conversations.singleOrNull { it.id == target.targetId } ?: throw FeedFailure("suggestion_changed")
            } else suggestionThreadForTarget(initial, target, language, selectionAtTap)

            val latest = repository.current()
            if (selectionAtTap != conversationSelectionGeneration || !SuggestionTargets097.current(latest, target, language) ||
                briefLanguage() != language || app.appLock.state.value.locked)
                throw FeedFailure("suggestion_changed")
            if (thread.itemId != target.itemId || thread.moduleId != target.moduleId) throw FeedFailure("suggestion_changed")
            val activeGeneration = if (target.kind == SuggestionTargetKind097.CONVERSATION) {
                if (_ui.value.activeConversationId != thread.id || ! _ui.value.conversationReady || _ui.value.conversationOpening)
                    throw FeedFailure("suggestion_changed")
                selectionAtTap
            } else activateSuggestionThread(thread, selectionAtTap)
            synchronized(suggestionActionLock) {
                if (suggestionActionJob?.isActive != true) throw CancellationException("Suggestion send was cancelled")
            }
            val started = launchConversationSend(question, thread.id, thread.itemId, activeGeneration, fromSuggestion = true,
                suggestionTarget = target, suggestionLanguage = language, suggestionOptionId = stableOptionId)
                ?: throw FeedFailure("invalid_input")
            started.join()
            if (!synchronized(suggestionActionLock) { suggestionDispatchStarted }) rollbackSuggestionBeforeDispatch()
        }
    }

    private suspend fun rollbackSuggestionBeforeDispatch() {
        val selectionGenerationAtRollback = conversationSelectionGeneration
        val rollback = synchronized(suggestionActionLock) {
            if (suggestionDispatchStarted) return
            SuggestionRollback097(pendingSuggestionThreadId, pendingSuggestionOriginalThread,
                pendingSuggestionAppendedThread, suggestionPreviousSelection, suggestionRollbackEpoch)
        }
        val createdThreadId = rollback.createdThreadId
        val originalThread = rollback.originalThread
        val appendedThread = rollback.appendedThread
        val updated = repository.update { state ->
            if (rollback.epoch != suggestionRollbackEpoch) return@update state
            var conversations = state.conversations
            if (originalThread != null && appendedThread != null &&
                conversations.singleOrNull { it.id == originalThread.id } == appendedThread) {
                conversations = conversations.map { if (it.id == originalThread.id) originalThread else it }
            }
            if (createdThreadId != null) {
                val created = conversations.singleOrNull { it.id == createdThreadId }
                if (created != null && created.turns.isEmpty() && created.attachments.isEmpty())
                    conversations = conversations.filterNot { it.id == createdThreadId }
            }
            if (conversations != state.conversations) state.copy(conversations = conversations) else state
        }
        if (rollback.epoch == suggestionRollbackEpoch && conversationSelectionGeneration == selectionGenerationAtRollback)
            rollback.previousSelection?.let { restoreSuggestionSelection(it, updated) }
    }

    /** Pencil action: populate the exact scoped composer, never append a turn or contact a provider. */
    fun editSuggestion(target: SuggestionTargetSnapshot097, stableOptionId: String, appLanguage: String): Job =
        startSuggestionAction {
            val language = appLanguage.takeIf { it == "sw" } ?: "en"
            if (briefLanguage() != language) throw FeedFailure("suggestion_changed")
            val uiAtTap = _ui.value
            if (app.appLock.state.value.locked) throw FeedFailure("suggestion_changed")
            if (uiAtTap.asking || askJob?.isActive == true || uiAtTap.importWorking || uiAtTap.conversationOpening || !uiAtTap.conversationReady)
                throw FeedFailure("invalid_input")
            val selectionAtTap = conversationSelectionGeneration
            if (target.kind == SuggestionTargetKind097.CONVERSATION && uiAtTap.activeConversationId != target.targetId)
                throw FeedFailure("suggestion_changed")
            val initial = repository.current()
            if (selectionAtTap != conversationSelectionGeneration || !SuggestionTargets097.current(initial, target, language))
                throw FeedFailure("suggestion_changed")
            val prompt = resolveSuggestion097(initial, target, stableOptionId, language) ?: throw FeedFailure("suggestion_changed")
            val thread = if (target.kind == SuggestionTargetKind097.CONVERSATION) {
                initial.conversations.singleOrNull { it.id == target.targetId } ?: throw FeedFailure("suggestion_changed")
            } else suggestionThreadForTarget(initial, target, language, selectionAtTap)

            val latest = repository.current()
            if (selectionAtTap != conversationSelectionGeneration || !SuggestionTargets097.current(latest, target, language) ||
                briefLanguage() != language || app.appLock.state.value.locked)
                throw FeedFailure("suggestion_changed")
            if (thread.itemId != target.itemId || thread.moduleId != target.moduleId) throw FeedFailure("suggestion_changed")
            if (target.kind == SuggestionTargetKind097.CONVERSATION) {
                if (_ui.value.activeConversationId != thread.id || !_ui.value.conversationReady || _ui.value.conversationOpening)
                    throw FeedFailure("suggestion_changed")
            } else activateSuggestionThread(thread, selectionAtTap)
            synchronized(suggestionActionLock) {
                if (suggestionActionJob?.isActive != true) throw CancellationException("Suggestion edit was cancelled")
                suggestionDispatchStarted = true // The edit is committed locally; navigation must not discard its draft event.
            }
            _ui.update { current ->
                if (_ui.value.activeConversationId != thread.id || !_ui.value.conversationReady) current
                else current.copy(answer = null, question = null, answerCitations = emptyList(),
                    suggestionDraft = SuggestedConversationDraft(java.util.UUID.randomUUID().toString(), thread.id, prompt), error = null)
            }
        }

    private suspend fun suggestionThreadForTarget(
        state: FeedState,
        target: SuggestionTargetSnapshot097,
        appLanguage: String,
        selectionGeneration: Long,
    ): AiConversation {
        var selected: AiConversation? = null
        val candidate = AiConversation(moduleId = target.moduleId, itemId = target.itemId)
        synchronized(suggestionActionLock) {
            if (suggestionActionJob?.isActive == true) pendingSuggestionThreadId = candidate.id
        }
        repository.update { latest ->
            if (selectionGeneration != conversationSelectionGeneration || !SuggestionTargets097.current(latest, target, appLanguage))
                return@update latest
            val existing = when (target.kind) {
                SuggestionTargetKind097.ITEM -> latest.conversations.filter { it.itemId == target.itemId && it.moduleId == target.moduleId }
                    .maxByOrNull { it.updatedAtMillis }
                SuggestionTargetKind097.MODULE -> latest.conversations.filter { it.moduleId == target.moduleId && it.itemId == null }
                    .maxByOrNull { it.updatedAtMillis }
                SuggestionTargetKind097.GENERAL -> latest.conversations.filter { it.moduleId == null && it.itemId == null }
                    .maxByOrNull { it.updatedAtMillis }
                SuggestionTargetKind097.CONVERSATION -> return@update latest
            }
            if (existing != null) { selected = existing; return@update latest }
            if (latest.conversations.size >= ConversationLogic.maxConversations) throw FeedFailure("conversation_limit")
            selected = candidate
            latest.copy(conversations = latest.conversations + candidate)
        }
        return selected ?: throw FeedFailure("suggestion_changed")
    }

    private fun activateSuggestionThread(thread: AiConversation, expectedGeneration: Long): Long {
        if (expectedGeneration != conversationSelectionGeneration) throw CancellationException("Suggestion target changed")
        val generation = ++conversationSelectionGeneration
        saveActiveConversationSelection(thread)
        _ui.update { it.copy(activeConversationId = thread.id, conversationOpening = false,
            conversationReady = true, error = null) }
        return generation
    }

    fun suggestionDraftApplied(id: String) {
        _ui.update { if (it.suggestionDraft?.id == id) it.copy(suggestionDraft = null) else it }
    }

    private suspend fun appendQuestion(
        id: String,
        question: String,
        stillCurrent: () -> Boolean,
        scopeCurrent: (FeedState) -> Boolean = { true },
        beforeCommit: (AiConversation) -> Boolean = { true },
        markAttachmentsUsed: Boolean = true,
    ): AiConversation {
        val clean = question.trim()
        if (clean.isBlank() || clean.length > 2000) throw FeedFailure("invalid_input")
        var appended: AiConversation? = null
        var scopeInvalid = false
        var suggestionCancelled = false
        val updated = repository.update { state ->
            if (!stillCurrent()) return@update state
            if (!scopeCurrent(state)) { scopeInvalid = true; return@update state }
            var found = false
            var candidate: AiConversation? = null
            val conversations = state.conversations.map { thread ->
                if (thread.id != id) thread else {
                    found = true
                    val duplicateRetry = thread.turns.lastOrNull()?.let { it.role == "user" && it.text == clean } == true
                    if (thread.turns.size > ConversationLogic.maxTurns - (if (duplicateRetry) 1 else 2)) throw FeedFailure("conversation_full")
                    val next = thread.copy(title = thread.title.ifBlank { clean.take(160) },
                        turns = if (duplicateRetry) thread.turns else thread.turns + AiTurn(role = "user", text = clean),
                        attachments = if (markAttachmentsUsed) thread.attachments.map {
                            if (it.usedAtMillis > 0) it else it.copy(usedAtMillis = maxOf(System.currentTimeMillis(), thread.createdAtMillis))
                        } else thread.attachments,
                        updatedAtMillis = maxOf(System.currentTimeMillis(), thread.createdAtMillis))
                    candidate = next
                    next
                }
            }
            if (!found || !stillCurrent()) return@update state
            if (!scopeCurrent(state)) { scopeInvalid = true; return@update state }
            val nextThread = candidate ?: return@update state
            if (!beforeCommit(nextThread)) { suggestionCancelled = true; return@update state }
            appended = nextThread
            state.copy(conversations = conversations)
        }
        if (appended == null) {
            if (scopeInvalid) throw FeedFailure("suggestion_changed")
            if (suggestionCancelled) throw CancellationException("Suggestion cancelled before the question was saved")
            throw CancellationException("Conversation selection changed before the question was saved")
        }
        return updated.conversations.find { it.id == id } ?: throw FeedFailure("invalid_input")
    }

    private fun sameRequestContext(current: FeedState, expected: FeedState): Boolean =
        current.connection == expected.connection && current.context == expected.context && current.name == expected.name &&
            current.region == expected.region && current.timezone == expected.timezone

    private suspend fun appendAnswer(
        thread: AiConversation,
        result: AiAnswer,
        expected: FeedState,
        stillCurrent: () -> Boolean,
        scopeCurrent: (FeedState) -> Boolean = { true },
        scopeFailureCode: String = "invalid_input",
    ): AiConversation {
        val next = thread.copy(turns = thread.turns + AiTurn(role = "assistant", text = result.answer, citations = result.citations, model = expected.connection.model),
            updatedAtMillis = maxOf(System.currentTimeMillis(), thread.createdAtMillis))
        repository.update { state ->
            if (!scopeCurrent(state)) throw FeedFailure(scopeFailureCode)
            if (!stillCurrent() || !sameRequestContext(state, expected) || state.conversations.find { it.id == thread.id } != thread ||
                state.modules.find { it.id == thread.moduleId } != expected.modules.find { it.id == thread.moduleId }) throw FeedFailure("invalid_input")
            state.copy(conversations = state.conversations.map { if (it.id == next.id) next else it })
        }
        return next
    }

    fun sendConversation(question: String) {
        val selectedUi = _ui.value
        launchConversationSend(question, selectedUi.activeConversationId, intendedConversationItemId,
            conversationSelectionGeneration, fromSuggestion = false)
    }

    /** Captured destination prevents a suggestion resolving on one screen from drifting to a newly active chat. */
    private fun launchConversationSend(
        question: String,
        targetConversationId: String?,
        targetItemId: String?,
        selectionGeneration: Long,
        fromSuggestion: Boolean,
        suggestionTarget: SuggestionTargetSnapshot097? = null,
        suggestionLanguage: String = "en",
        suggestionOptionId: String? = null,
    ): Job? {
        val selectedUi = _ui.value
        val suggestionInFlight = synchronized(suggestionActionLock) { suggestionActionJob?.isActive == true }
        if (askJob?.isActive == true || (!fromSuggestion && suggestionInFlight) || selectedUi.importWorking ||
            selectedUi.conversationOpening || !selectedUi.conversationReady || targetConversationId != selectedUi.activeConversationId) return null
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
                val beforeSuggestionTurn = selected.takeIf { suggestionTarget?.kind == SuggestionTargetKind097.CONVERSATION }
                var appendedSuggestionThread: AiConversation? = null
                val targetStillCurrent: (FeedState) -> Boolean = { latest ->
                    val target = suggestionTarget
                    val unlocked = !app.appLock.state.value.locked
                    val actionActive = if (target == null) true else synchronized(suggestionActionLock) {
                        suggestionActionJob?.isActive == true
                    }
                    if (!unlocked || !actionActive) false else if (target == null) true else {
                        val optionId = suggestionOptionId
                        if (optionId == null) false
                        else if (target.kind != SuggestionTargetKind097.CONVERSATION) {
                            SuggestionTargets097.current(latest, target, suggestionLanguage) &&
                                resolveSuggestion097(latest, target, optionId, suggestionLanguage) == question
                        } else {
                            val original = beforeSuggestionTurn
                            val nowThread = latest.conversations.singleOrNull { it.id == target.targetId }
                            val expectedThread = appendedSuggestionThread
                            val threadIsExpected = if (expectedThread == null) nowThread == original else nowThread == expectedThread
                            val preAppendState = if (original == null) latest else latest.copy(
                                conversations = latest.conversations.map { if (it.id == original.id) original else it },
                            )
                            threadIsExpected && SuggestionTargets097.current(preAppendState, target, suggestionLanguage) &&
                                resolveSuggestion097(preAppendState, target, optionId, suggestionLanguage) == question
                        }
                    }
                }
                if (suggestionTarget != null) synchronized(suggestionActionLock) {
                    if (suggestionActionJob?.isActive != true) throw CancellationException("Suggestion send was cancelled")
                    pendingSuggestionOriginalThread = selected
                    pendingSuggestionAppendedThread = null
                }
                val commitSuggestionThread: (AiConversation) -> Boolean = { nextThread ->
                    if (suggestionTarget == null) true else synchronized(suggestionActionLock) {
                        if (suggestionActionJob?.isActive != true || app.appLock.state.value.locked) false
                        else { pendingSuggestionAppendedThread = nextThread; true }
                    }
                }
                val stillCurrent: () -> Boolean = {
                    generation == askGeneration && selectionGeneration == conversationSelectionGeneration &&
                        (suggestionTarget == null || synchronized(suggestionActionLock) { suggestionActionJob?.isActive == true })
                }
                val thread = appendQuestion(selected.id, question, stillCurrent,
                    targetStillCurrent, commitSuggestionThread,
                    markAttachmentsUsed = suggestionTarget == null)
                if (suggestionTarget?.kind == SuggestionTargetKind097.CONVERSATION) appendedSuggestionThread = thread
                val requestState = repository.current()
                if (selectionGeneration != conversationSelectionGeneration) return@launch
                if (!targetStillCurrent(requestState)) throw FeedFailure(if (suggestionTarget != null) "suggestion_changed" else "invalid_input")
                val result = withContext(Dispatchers.IO) {
                    if (suggestionTarget == null) engine.askConversation(thread.id, question) { text ->
                        if (generation == askGeneration && selectionGeneration == conversationSelectionGeneration) _ui.update { it.copy(answer = text) }
                    } else engine.askConversation(
                        conversationId = thread.id,
                        question = question,
                        onText = { text ->
                            if (generation == askGeneration && selectionGeneration == conversationSelectionGeneration) _ui.update { it.copy(answer = text) }
                        },
                        expectedCurrent = targetStillCurrent,
                        expectedLanguage = suggestionLanguage,
                        staleFailureCode = "suggestion_changed",
                        excludeUnusedAttachments = true,
                        onRequestDispatch = {
                        synchronized(suggestionActionLock) {
                            if (suggestionActionJob?.isActive != true || app.appLock.state.value.locked)
                                throw CancellationException("Suggestion send was cancelled")
                            suggestionDispatchStarted = true
                        }
                    })
                }
                if (generation == askGeneration && selectionGeneration == conversationSelectionGeneration) {
                    appendAnswer(thread, result, requestState, {
                        generation == askGeneration && selectionGeneration == conversationSelectionGeneration
                    }, targetStillCurrent, if (suggestionTarget != null) "suggestion_changed" else "invalid_input")
                    _ui.update { it.copy(answer = null, answerCitations = emptyList(), error = null) }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (e: Exception) { if (generation == askGeneration && selectionGeneration == conversationSelectionGeneration) {
                diagnose("conversation", e); _ui.update { it.copy(error = failureCode(e), answer = null) }
            } }
            finally { if (generation == askGeneration && selectionGeneration == conversationSelectionGeneration) _ui.update { it.copy(asking = false) } }
        }
        if (fromSuggestion) synchronized(suggestionActionLock) { suggestionAskJob = askJob }
        return askJob
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
                val thread = appendQuestion(selected.id, question, stillCurrent = { generation == detailGeneration })
                _ui.update { it.copy(detailTurns = thread.turns) }
                val result = withContext(Dispatchers.IO) { engine.askConversation(thread.id, question) { text ->
                    if (generation == detailGeneration) _ui.update { it.copy(detailText = text) }
                } }
                if (generation == detailGeneration) {
                    val completed = appendAnswer(thread, result, state, stillCurrent = { generation == detailGeneration })
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
        val entries = imported.entries.map { it.copy(origin = "imported", global = false) }
        val saved = repository.update { s -> s.copy(name = imported.name, region = imported.region, timezone = imported.timezone,
            context = entries, setup099 = Setup099Logic.contextConfirmed(s.setup099, entries),
            modules = ModuleCatalog.mergeCatalog(imported.modules.ifEmpty { s.modules }),
            items = s.items.map { AiSuggestedInteractions095.clear(it.copy(why = "", relevance = 0, aiRelevance = null)) },
            briefs = emptyList(), suggestionSets097 = emptyList(), personalDailySelection098 = null, lastAiRefreshMillis = 0, lastRefreshMillis = 0) }
        // Publish the exact DataStore result before the completion signal returns to the editor.
        // The repository collector will converge on the same snapshot; this avoids briefly
        // restoring a stale saveable context draft as the user lands on the notes list.
        _ui.update { it.copy(state = saved, notice = "imported", error = null) }
    }
    private suspend fun applyBackup(candidate: FeedState) {
        conversationSelectionGeneration++
        clearSavedConversationSelection()
        cancelConnection(); cancelRefresh(); cancelAiWork(discardSuggestionRollback = true)
        // Commit the data first. A failed store write must not erase a working account.
        // A blank plan account prevents old API credentials being used if cleanup fails.
        repository.update { local ->
            val setup = Setup099Logic.contextConfirmed(local.setup099, candidate.context)
            candidate.copy(notifications = false, personalSeedApplied = true, modules = ModuleCatalog.mergeCatalog(candidate.modules),
                items = candidate.items.map { AiSuggestedInteractions095.clear(it.copy(aiRelevance = null)) },
                suggestionSets097 = emptyList(), personalDailySelection098 = null,
                // Wizard identity and notice snoozes belong to this installation, not an imported backup.
                setup099 = setup, dismissedNotices = local.dismissedNotices,
                connection = AiConnection(mode = "chatgpt", model = candidate.connection.model), aiPauseCode = "")
        }
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
            items = state.items.map(AiSuggestedInteractions095::clear),
            suggestionSets097 = emptyList(), personalDailySelection098 = null,
            connection = state.connection.copy(accountId = ""), aiPauseCode = "",
            // Account correlation keys stay on device. An already-onboarded backup must not
            // resurrect the first-run wizard on another installation.
            setup099 = if (state.onboardingComplete) Setup099State(stage = Setup099Stage.COMPLETE)
                else state.setup099.copy(firstChatGptAccountKey = "", pendingImportAccountKey = "", importRouteAcknowledgedAccountKey = ""),
        )))
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
        cancelPersonalDailySelectionEnsure098()
        cancelImport()
        suggestionRollbackEpoch++
        clearAnswerInternal(suppressSuggestionRollback = true)
        conversationSelectionGeneration++
        clearSavedConversationSelection()
        _ui.update { it.copy(activeConversationId = null, conversationOpening = false, conversationReady = true) }
        return work {
            cancelRefresh(); cancelConnection(); cancelAiWork(discardSuggestionRollback = true)
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
