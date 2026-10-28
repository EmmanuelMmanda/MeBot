package mebot.nguli.com.ui.feed

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import mebot.nguli.com.R
import mebot.nguli.com.core.*
import mebot.nguli.com.feed.FeedUiState
import mebot.nguli.com.feed.SuggestionGenerationHandle097
import mebot.nguli.com.feed.SuggestedConversationDraft
import mebot.nguli.com.feed.SuggestionSheetSnapshot097
import mebot.nguli.com.feed.SuggestionSheetStatus097
import mebot.nguli.com.feed.SuggestionTargetSnapshot097
import mebot.nguli.com.feed.SuggestionTargets097
import mebot.nguli.com.realtime.RealtimeCapability
import mebot.nguli.com.realtime.RecordingTranscriptionCapability
import mebot.nguli.com.ui.MeBotMark
import mebot.nguli.com.ui.MeBotTokens
import java.util.Locale
import java.io.File
import kotlin.math.roundToInt

/** A durable conversation workspace. Account setup and model eligibility remain in the existing connection flow. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiWorkspace(
    ui: FeedUiState,
    onNew: (String?) -> Unit,
    onSelect: (String) -> Unit,
    onDelete: (String) -> Unit,
    onAsk: (String) -> Unit,
    onCancel: () -> Unit,
    onConnect: () -> Unit,
    onContext: () -> Unit,
    onImportText: (Uri) -> Unit,
    onRemoveAttachment: (String) -> Unit,
    onSelectModel: (String) -> Unit = {},
    onDismissNotice: ((String) -> Unit)? = null,
    onSpeechPreferences: (Float, String) -> Unit = { _, _ -> },
    onSuggestionDraftApplied: (String) -> Unit = {},
    onUseSuggestion: (SuggestionTargetSnapshot097, String, String) -> Unit = { _, _, _ -> },
    onEditSuggestion: (SuggestionTargetSnapshot097, String, String) -> Unit = { _, _, _ -> },
    onSuggestionSheet: (SuggestionTargetSnapshot097, String) -> SuggestionSheetSnapshot097 = { target, _ ->
        SuggestionSheetSnapshot097(target, SuggestionSheetStatus097.NEEDS_GENERATION, emptyList())
    },
    onGenerateSuggestion: (SuggestionTargetSnapshot097, String) -> SuggestionGenerationHandle097? = { _, _ -> null },
    onPrepareSuggestions: (SuggestionTargetSnapshot097, String) -> SuggestionGenerationHandle097? = { _, _ -> null },
    onLoadMoreSuggestion: (SuggestionTargetSnapshot097, String) -> SuggestionGenerationHandle097? = { _, _ -> null },
    onCancelSuggestionGeneration: (String) -> Unit = {},
    onReleaseSuggestionPreparation: (SuggestionGenerationHandle097) -> Unit = {},
    onTranscribeVoice: WorkspaceVoiceTranscriber? = null,
    realtimeCapability: RealtimeCapability = RealtimeCapability.Unavailable(RealtimeCapability.Reason.SERVICE_NOT_CONFIGURED),
    recordingTranscriptionCapability: RecordingTranscriptionCapability = RecordingTranscriptionCapability.Unavailable(
        RecordingTranscriptionCapability.Reason.SERVICE_NOT_CONFIGURED),
    onStartRealtimeVoiceCall: (() -> Unit)? = null,
    onOpenVoiceSetup: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val canUseLiveVoice = realtimeCapability.canOfferCall && onStartRealtimeVoiceCall != null
    val canUseTranscription = recordingTranscriptionCapability.canTranscribe && onTranscribeVoice != null
    val lifecycleOwner = LocalLifecycleOwner.current
    val locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    val state = ui.state
    val conversations = state?.conversations.orEmpty().sortedByDescending { it.updatedAtMillis }
    val conversation = conversations.find { it.id == ui.activeConversationId }
    // The selected id is authoritative even while DataStore has not emitted the thread yet.
    val draftKey = ui.activeConversationId ?: "new"
    var drafts by rememberSaveable { mutableStateOf(hashMapOf<String, String>()) }
    // Private cache paths remain attached to a draft/thread, never to exported feed state.
    var voiceClipsByDraft by rememberSaveable { mutableStateOf(hashMapOf<String, String>()) }
    var previousDraftKey by rememberSaveable { mutableStateOf(draftKey) }
    var pendingReplaceDraft by remember { mutableStateOf<SuggestedConversationDraft?>(null) }
    var focusComposerAfterEdit by rememberSaveable { mutableStateOf(false) }
    val draft = drafts[draftKey].orEmpty()
    fun setDraft(value: String) { drafts = HashMap(drafts).apply { if (value.isEmpty()) remove(draftKey) else put(draftKey, value.take(2000)) } }
    LaunchedEffect(draftKey, ui.suggestionDraft?.id, ui.suggestionWorking) {
        val pendingSuggestion = ui.suggestionDraft
        val suggestionTarget = pendingSuggestion?.conversationId == draftKey
        // A newly-created thread may arrive after the user starts typing.
        // A suggestion creates its own thread; keep any unsent "new" draft on the old key.
        // Suppress the generic new-thread migration during any suggestion action, including
        // direct Send (which intentionally carries no draft-application event).
        if (!ui.suggestionWorking && pendingSuggestion == null && !suggestionTarget && previousDraftKey == "new" &&
            draftKey != "new" && conversation?.turns.isNullOrEmpty() && drafts[draftKey].isNullOrBlank()) {
            drafts["new"]?.takeIf { it.isNotBlank() }?.let { pending ->
                drafts = HashMap(drafts).apply { put(draftKey, pending); remove("new") }
                voiceClipsByDraft["new"]?.let { savedClip ->
                    voiceClipsByDraft = HashMap(voiceClipsByDraft).apply {
                        if (get(draftKey).isNullOrBlank()) put(draftKey, savedClip)
                        remove("new")
                    }
                }
            }
        }
        previousDraftKey = draftKey
        if (suggestionTarget && pendingSuggestion != null) {
            val existingDraft = drafts[draftKey].orEmpty()
            if (existingDraft.isNotBlank() && existingDraft.trim() != pendingSuggestion.text.trim()) {
                if (pendingReplaceDraft?.id != pendingSuggestion.id) pendingReplaceDraft = pendingSuggestion
            } else {
                drafts = HashMap(drafts).apply { put(draftKey, pendingSuggestion.text.take(2000)) }
                focusComposerAfterEdit = true
                onSuggestionDraftApplied(pendingSuggestion.id)
            }
        }
    }
    LaunchedEffect(pendingReplaceDraft?.id, ui.suggestionDraft?.id, draftKey, state?.conversations?.map { it.id }) {
        val pending = pendingReplaceDraft ?: return@LaunchedEffect
        val currentEvent = ui.suggestionDraft
        val suggestionStillCurrent = currentEvent?.id == pending.id && currentEvent.conversationId == pending.conversationId
        val targetStillPresent = state?.conversations?.any { it.id == pending.conversationId } == true
        if (!suggestionStillCurrent || !targetStillPresent || pending.conversationId != draftKey) {
            pendingReplaceDraft = null
        }
    }
    var submittedKey by rememberSaveable { mutableStateOf<String?>(null) }
    var submittedText by rememberSaveable { mutableStateOf<String?>(null) }
    var previousTurnId by rememberSaveable { mutableStateOf<String?>(null) }
    var sentAttachmentIds by rememberSaveable(draftKey) { mutableStateOf(arrayListOf<String>()) }
    var showConversationFiles by rememberSaveable { mutableStateOf(false) }
    var showVoiceSettings by rememberSaveable { mutableStateOf(false) }
    var showHistory by rememberSaveable { mutableStateOf(false) }
    var showScopes by rememberSaveable { mutableStateOf(false) }
    var showModels by rememberSaveable { mutableStateOf(false) }
    var showVoiceCapture by rememberSaveable { mutableStateOf(false) }
    var showVoiceOptions by rememberSaveable { mutableStateOf(false) }
    var apiModel by rememberSaveable { mutableStateOf("") }
    var deletingId by rememberSaveable { mutableStateOf<String?>(null) }
    var reviewAttachmentId by rememberSaveable { mutableStateOf<String?>(null) }
    val speech = rememberWorkspaceSpeech(state?.speechRate ?: 1f, state?.speechVoice.orEmpty())
    val storedVoiceClip = remember(voiceClipsByDraft, draftKey) { decodeWorkspaceClip(voiceClipsByDraft[draftKey]) }
    val existingVoiceClip = remember(context, storedVoiceClip) {
        resolveWorkspaceClip(context, storedVoiceClip?.path, storedVoiceClip?.durationMillis ?: 0L)
    }
    val voiceTranscriptApplied = storedVoiceClip?.transcriptApplied == true
    // Run once after restored draft ownership is known; never sweep an active recorder.
    LaunchedEffect(Unit) {
        val retainedPaths = voiceClipsByDraft.values.mapNotNull { decodeWorkspaceClip(it)?.path }.toSet()
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            cleanupOrphanedWorkspaceVoiceAudio(context, retainedPaths, null, System.currentTimeMillis())
        }
    }
    fun rememberVoiceClip(ownerKey: String, clip: WorkspaceClip) {
        val previous = decodeWorkspaceClip(voiceClipsByDraft[ownerKey])
        if (previous?.path != clip.file.absolutePath) {
            previous?.let { deleteWorkspaceClipFile(context, it.path) }
        }
        voiceClipsByDraft = HashMap(voiceClipsByDraft).apply { put(ownerKey, encodeWorkspaceClip(clip, transcriptApplied = false)) }
    }
    fun discardVoiceClipFor(ownerKey: String) {
        decodeWorkspaceClip(voiceClipsByDraft[ownerKey])?.let { saved ->
            deleteWorkspaceClipFile(context, saved.path)
        }
        voiceClipsByDraft = HashMap(voiceClipsByDraft).apply { remove(ownerKey) }
    }
    fun discardVoiceClip() = discardVoiceClipFor(draftKey)
    LaunchedEffect(state?.conversations?.map { it.id }, draftKey, submittedKey) {
        val persistedIds = state?.conversations?.mapTo(mutableSetOf()) { it.id } ?: return@LaunchedEffect
        val retained = persistedIds + "new" + draftKey + listOfNotNull(submittedKey)
        val removed = voiceClipsByDraft.keys - retained
        if (removed.isNotEmpty()) {
            removed.forEach(::discardVoiceClipFor)
        }
    }
    val documents = mebot.nguli.com.documents.LocalDocumentActions.current
    val listState = rememberLazyListState()
    var previousThread by remember { mutableStateOf<String?>(null) }
    val turns = conversation?.turns.orEmpty()
    LaunchedEffect(conversation?.id, turns.size, ui.asking) {
        val changedThread = previousThread != conversation?.id
        val nearBottom = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let {
            it >= listState.layoutInfo.totalItemsCount - 3
        } ?: true
        if (changedThread || nearBottom) {
            val lastIndex = (listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)
            listState.scrollToItem(lastIndex)
        }
        previousThread = conversation?.id
    }
    LaunchedEffect(turns.lastOrNull()?.id, ui.asking, ui.activeConversationId) {
        val submitted = submittedText
        val accepted = turns.lastOrNull { it.role == "user" }
        if (submitted != null && accepted != null && accepted.id != previousTurnId && accepted.text == submitted &&
            (submittedKey == draftKey || submittedKey == "new")) {
            drafts = HashMap(drafts).apply {
                submittedKey?.let { key -> if (get(key)?.trim() == submitted) remove(key) }
                if (get(draftKey)?.trim() == submitted) remove(draftKey)
            }
            submittedKey?.let(::discardVoiceClipFor)
            if (submittedKey == "new") discardVoiceClipFor(draftKey)
            sentAttachmentIds = ArrayList((sentAttachmentIds + conversation?.attachments.orEmpty().map { it.id }).distinct())
            submittedText = null; submittedKey = null
        }
    }
    fun send(value: String) {
        if (!ui.connected || ui.asking || ui.importWorking || ui.suggestionWorking || ui.conversationOpening || !ui.conversationReady || value.isBlank()) return
        speech.stop()
        previousTurnId = turns.lastOrNull { it.role == "user" }?.id
        submittedKey = draftKey; submittedText = value.trim()
        onAsk(value.trim())
        // Retrying an unanswered durable question reuses its turn id. Only clear that exact
        // text, never another unsent draft; new attachments still await their persisted timestamp.
        if (turns.lastOrNull()?.let { it.role == "user" && it.text == value.trim() } == true) {
            drafts = HashMap(drafts).apply { if (get(draftKey)?.trim() == value.trim()) remove(draftKey) }
            submittedText = null; submittedKey = null
        }
    }
    val module = state?.modules?.find { it.id == conversation?.moduleId }
    val story = state?.items?.find { it.id == conversation?.itemId }
    val scopeKind = module?.kind ?: story?.kind
    val hasConfirmedAboutYou = state?.context.orEmpty().any {
        PersonalProfile085.isGlobal(it) && it.confirmed && it.text.isNotBlank()
    }
    val appLanguage = if (ui.appLanguage == "sw") "sw" else "en"
    val suggestionTarget = remember(state, ui.activeConversationId, conversation?.id, module?.id, appLanguage) {
        when {
            state == null -> null
            ui.activeConversationId != null -> conversation?.takeIf { it.id == ui.activeConversationId }
                ?.let { SuggestionTargets097.conversation(state, it.id, appLanguage) }
            module != null -> SuggestionTargets097.module(state, module.id, appLanguage)
            else -> SuggestionTargets097.general(state, appLanguage)
        }
    }
    val suggestionModule = state?.modules?.singleOrNull { it.id == suggestionTarget?.moduleId && it.enabled }
    val suggestionContextKinds = remember(state?.modules, suggestionModule?.kind) {
        suggestionModule?.let { setOf(it.kind) } ?: state?.modules.orEmpty().filter { it.enabled }.map { it.kind }.toSet()
    }
    val hasApprovedSuggestionContext = state?.context.orEmpty().any { entry ->
        entry.confirmed && entry.text.isNotBlank() &&
            (PersonalProfile085.isGlobal(entry) || (!entry.global && entry.scopes.any(suggestionContextKinds::contains)))
    }
    val hasCurrentSuggestionMatter = when (suggestionTarget?.kind) {
        mebot.nguli.com.feed.SuggestionTargetKind097.ITEM -> true // Validated current item content is the context.
        mebot.nguli.com.feed.SuggestionTargetKind097.CONVERSATION -> conversation?.turns?.isNotEmpty() == true
        mebot.nguli.com.feed.SuggestionTargetKind097.MODULE -> suggestionModule?.query?.isNotBlank() == true
        mebot.nguli.com.feed.SuggestionTargetKind097.GENERAL, null -> false
    }
    val hasSuggestionContext = hasApprovedSuggestionContext || hasCurrentSuggestionMatter
    val voiceContextModule = state?.modules?.singleOrNull { it.id == (conversation?.moduleId ?: story?.moduleId) }
    val hasConfirmedVoiceContext = state?.context.orEmpty().any { entry ->
        entry.confirmed && entry.text.isNotBlank() &&
            (PersonalProfile085.isGlobal(entry) || (!entry.global && voiceContextModule?.kind?.let(entry.scopes::contains) == true))
    }
    val suggestionContext = when {
        story != null -> story.title
        conversation?.title?.isNotBlank() == true -> conversation.title
        module != null -> moduleName(module)
        else -> stringResource(R.string.workspace_general)
    }
    // This opaque digest comes from the finalized persisted target, including bounded transcript
    // revision, account/connection and approved context. It changes after a completed turn, but
    // not for stream-token updates because `asking` below gates preparation until completion.
    val suggestionPreparationKey = remember(draftKey, appLanguage, suggestionTarget?.kind,
        suggestionTarget?.targetId, suggestionTarget?.revision) {
        listOf(draftKey, appLanguage, suggestionTarget?.kind?.name.orEmpty(),
            suggestionTarget?.targetId.orEmpty(), suggestionTarget?.revision.orEmpty()).joinToString("|")
    }
    var preparedSuggestionSession by remember { mutableStateOf<String?>(null) }
    var preparedSuggestionHandle by remember { mutableStateOf<SuggestionGenerationHandle097?>(null) }
    val releasePreparedSuggestion by rememberUpdatedState(onReleaseSuggestionPreparation)
    LaunchedEffect(suggestionPreparationKey, suggestionTarget, hasSuggestionContext, ui.conversationReady, ui.connected,
        ui.asking, ui.importWorking, ui.suggestionWorking, ui.conversationOpening, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            val target = suggestionTarget ?: return@repeatOnLifecycle
            if ((target.kind == mebot.nguli.com.feed.SuggestionTargetKind097.CONVERSATION &&
                    target.targetId != ui.activeConversationId) ||
                (ui.activeConversationId != null && target.kind != mebot.nguli.com.feed.SuggestionTargetKind097.CONVERSATION)) {
                return@repeatOnLifecycle
            }
            if (!ui.connected || !ui.conversationReady || !hasSuggestionContext || ui.asking || ui.importWorking || ui.suggestionWorking || ui.conversationOpening) {
                return@repeatOnLifecycle
            }
            if (preparedSuggestionSession == suggestionPreparationKey) return@repeatOnLifecycle
            // Mark before invoking the coalescing VM callback so recomposition/resume cannot duplicate prefetch.
            preparedSuggestionSession = suggestionPreparationKey
            preparedSuggestionHandle = onPrepareSuggestions(target, appLanguage)
            // Keep cancellation attached to this target even if the screen is stopped immediately.
            currentCoroutineContext().ensureActive()
        }
    }
    DisposableEffect(suggestionPreparationKey, ui.activeConversationId, ui.connected, ui.asking, ui.importWorking,
        ui.conversationOpening, lifecycleOwner, preparedSuggestionHandle?.requestId) {
        val handle = preparedSuggestionHandle
        fun cancelForBackground() {
            if (handle?.cancelOnDismiss == false && handle.job.isActive) {
                handle.preparationLeaseId?.let { releasePreparedSuggestion(handle) }
                if (preparedSuggestionSession == suggestionPreparationKey) preparedSuggestionSession = null
            }
        }
        fun cancelStaleTarget() {
            if (handle?.preparationLeaseId != null && handle.job.isActive) releasePreparedSuggestion(handle)
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) cancelForBackground()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            cancelStaleTarget()
        }
    }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(focusComposerAfterEdit, draftKey, draft) {
        if (focusComposerAfterEdit && draft.isNotBlank()) {
            runCatching { focusRequester.requestFocus() }
            keyboard?.show()
            focusComposerAfterEdit = false
        }
    }
    val modelName = state?.connection?.let { connection ->
        if (connection.mode == "chatgpt") ui.chatGpt.accounts.find { it.id == connection.accountId }?.models?.find { it.slug == connection.model }?.displayName ?: connection.model
        else connection.model
    }.orEmpty()

    if(showModels) {
        val models = ui.chatGpt.accounts.find { it.id == state?.connection?.accountId }?.models.orEmpty()
        AlertDialog(onDismissRequest={if(!ui.connecting)showModels=false},
            title={Text(if(locale.language=="sw") "Chagua modeli" else "Choose a model")},
            text={Column(Modifier.heightIn(max=320.dp).verticalScroll(rememberScrollState()), verticalArrangement=Arrangement.spacedBy(6.dp)) {
                if(state?.connection?.mode=="chatgpt") models.forEach { model ->
                    TextButton(onClick={showModels=false;onSelectModel(model.slug)},enabled=!ui.asking && !ui.connecting,modifier=Modifier.fillMaxWidth()) {
                        Text(model.displayName,Modifier.weight(1f));if(model.slug==state.connection.model)Icon(Icons.Outlined.Check,null,Modifier.size(18.dp))
                    }
                } else OutlinedTextField(apiModel, {apiModel=it.take(128)},singleLine=true,label={Text(if(locale.language=="sw") "Jina la modeli" else "Model ID")})
            }},
            confirmButton={ mebot.nguli.com.security.ProtectedWindowRegistration();if(state?.connection?.mode!="chatgpt")TextButton(onClick={showModels=false;onSelectModel(apiModel.trim())},enabled=apiModel.isNotBlank()){Text(if(locale.language=="sw") "Tumia" else "Use model")}},
            dismissButton={TextButton(onClick={showModels=false}){Text(if(locale.language=="sw") "Funga" else "Close")}})
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = MeBotTokens.ContentWidth).fillMaxWidth().fillMaxHeight().imePadding()
            .padding(horizontal = MeBotTokens.ScreenPadding).testTag("ai-workspace")) {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                MeBotActivityBadge(if (ui.asking) ui.activities.lastOrNull { it.operation == "answer" } ?: mebot.nguli.com.feed.AiActivity("workspace-preparing", "workspace", "", "answer", "preparing") else null, Modifier.size(36.dp)); Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.workspace_title), Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
                IconButton(onClick = { showHistory = true }, enabled = conversations.isNotEmpty() && !ui.asking && !ui.importWorking && !ui.suggestionWorking,
                    modifier = Modifier.testTag("workspace-history")) { Icon(Icons.Outlined.History, stringResource(R.string.workspace_history)) }
                IconButton(onClick = { speech.stop(); onNew(null) }, enabled = !ui.asking && !ui.importWorking && !ui.suggestionWorking,
                    modifier = Modifier.testTag("workspace-new")) { Icon(Icons.Outlined.Add, stringResource(R.string.workspace_new)) }
            }
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val stacked = maxWidth < 340.dp || LocalDensity.current.fontScale >= 1.2f
                val modelLabel = if (ui.connected) modelName else stringResource(R.string.feed_connect)
                val scopeLabel = module?.let { moduleName(it) } ?: stringResource(R.string.workspace_general)
                if (stacked) Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    WorkspaceModelControl(modelLabel, ui.connected, { if(ui.connected) showModels = true else onConnect() }, Modifier.fillMaxWidth(),
                        enabled = !ui.asking && !ui.importWorking && !ui.suggestionWorking && !ui.conversationOpening, wrapLabel = true)
                    WorkspaceScopeControl(scopeLabel, !ui.asking && !ui.importWorking && !ui.suggestionWorking && !ui.conversationOpening,
                        { showScopes = true }, Modifier.fillMaxWidth(), wrapLabel = true)
                } else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    WorkspaceModelControl(modelLabel, ui.connected, { if(ui.connected) showModels = true else onConnect() }, Modifier.weight(1f),
                        enabled = !ui.asking && !ui.importWorking && !ui.suggestionWorking && !ui.conversationOpening)
                    WorkspaceScopeControl(scopeLabel, !ui.asking && !ui.importWorking && !ui.suggestionWorking && !ui.conversationOpening,
                        { showScopes = true }, Modifier.widthIn(max = 160.dp))
                }
            }
            if (story != null) Text(story.title, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth().testTag("workspace-messages"),
                contentPadding = PaddingValues(top = 14.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                if (turns.isEmpty() && !ui.asking && state != null && onDismissNotice != null) item(key = "setup") {
                    SetupNotice(state, ui.connected, "mebot", onConnect, onContext, onDismissNotice)
                }
                if (turns.isEmpty()) item(key = "start") {
                    WorkspaceStart(
                        ui.connected, onConnect, onContext, scopeKind != null, story != null,
                        hasConfirmedAboutYou, draft.isNotBlank(),
                        personalizationContextMissing = suggestionTarget?.kind == mebot.nguli.com.feed.SuggestionTargetKind097.GENERAL &&
                            !hasSuggestionContext,
                    )
                }
                items(turns, key = { it.id }, contentType = { it.role }) { turn -> WorkspaceTurn(turn, speech) }
                if (ui.asking || (ui.answer?.isNotBlank() == true && turns.lastOrNull()?.text != ui.answer)) item(key = "pending") {
                    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag("workspace-pending"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (!ui.answer.isNullOrBlank()) {
                            if (!ui.asking) Text(stringResource(R.string.workspace_partial),
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            RichResponse(ui.answer.orEmpty(), Modifier.testTag("workspace-draft"), ui.answerCitations)
                            if (!ui.asking && ui.answer.orEmpty().isNotBlank()) {
                                WorkspaceAnswerActions("workspace-draft", ui.answer.orEmpty(), speech, allowReadAloud = true)
                            }
                        }

                    }
                }
                if (ui.error != null) item(key = "error") {
                    FeedStatus(ui.error, onConnect = onConnect)
                    turns.lastOrNull()?.takeIf { it.role == "user" }?.let { unanswered ->
                        TextButton(onClick = { send(unanswered.text) }, enabled = ui.connected && !ui.asking && !ui.importWorking,
                            modifier = Modifier.testTag("workspace-retry")) { Text(stringResource(R.string.rich_retry_question)) }
                    }
                }
            }
            val composerFiles = conversation?.attachments.orEmpty().filterNot { it.usedAtMillis > 0 || it.id in sentAttachmentIds }
            if (conversation?.attachments.orEmpty().any { it.usedAtMillis > 0 || it.id in sentAttachmentIds }) TextButton(onClick = { showConversationFiles = true },
                modifier = Modifier.testTag("workspace-conversation-files")) {
                Icon(Icons.Outlined.FolderOpen, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.rich_conversation_files, conversation?.attachments.orEmpty().size))
            }
            if (composerFiles.isNotEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                composerFiles.forEach { attachment -> InputChip(selected = false, onClick = { reviewAttachmentId = attachment.id },
                    label = { Text(attachment.name, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 180.dp)) },
                    leadingIcon = { Icon(Icons.Outlined.Description, null, Modifier.size(16.dp)) },
                    trailingIcon = { IconButton(onClick = { onRemoveAttachment(attachment.id) }, enabled = !ui.asking && !ui.importWorking,
                        modifier = Modifier.size(32.dp).testTag("attachment-remove-${attachment.id}")) { Icon(Icons.Outlined.Close, stringResource(R.string.workspace_remove_attachment), Modifier.size(16.dp)) } },
                    modifier = Modifier.testTag("attachment-${attachment.id}")) }
            }
            existingVoiceClip?.let { clip ->
                Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().testTag("workspace-voice-clip")) {
                    Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Mic, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.workspace_voice_clip_local, formatCaptureDuration(clip.durationMillis)),
                                style = MaterialTheme.typography.labelLarge)
                            Text(stringResource(if (voiceTranscriptApplied) R.string.workspace_voice_clip_transcribed else R.string.workspace_voice_clip_not_sent),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = { showVoiceCapture = true }, modifier = Modifier.testTag("workspace-voice-clip-review")) {
                            Text(stringResource(R.string.workspace_voice_review))
                        }
                        IconButton(onClick = ::discardVoiceClip, modifier = Modifier.size(40.dp).testTag("workspace-voice-clip-delete")) {
                            Icon(Icons.Outlined.Close, stringResource(R.string.workspace_voice_discard), Modifier.size(18.dp))
                        }
                    }
                }
            }

            Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(22.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                Column {
                    if (ui.conversationOpening) Text(stringResource(R.string.workspace_opening),
                        Modifier.fillMaxWidth().padding(start = 18.dp, top = 10.dp, end = 18.dp).testTag("workspace-opening"),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (ui.suggestionWorking) Text(stringResource(R.string.suggestions_working),
                        Modifier.fillMaxWidth().padding(start = 18.dp, top = 10.dp, end = 18.dp).testTag("workspace-suggestion-working"),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextField(value = draft, onValueChange = ::setDraft, enabled = !ui.asking && !ui.importWorking,
                        placeholder = { Text(stringResource(R.string.workspace_question)) }, minLines = 1, maxLines = 4,
                        modifier = Modifier.fillMaxWidth().focusRequester(focusRequester).testTag("workspace-question"),
                        colors = TextFieldDefaults.colors(focusedContainerColor = MaterialTheme.colorScheme.surface,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surface, disabledContainerColor = MaterialTheme.colorScheme.surface,
                            focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                            unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent, disabledIndicatorColor = androidx.compose.ui.graphics.Color.Transparent),
                        shape = RoundedCornerShape(22.dp))
                    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { documents.importAttachment() },
                        enabled = !ui.asking && !ui.importWorking && !ui.suggestionWorking && !ui.conversationOpening && ui.conversationReady && conversation?.attachments.orEmpty().size < 3,
                            modifier = Modifier.testTag("workspace-attach")) { Icon(Icons.Outlined.AttachFile, stringResource(R.string.workspace_attach)) }
                        if (canUseLiveVoice || canUseTranscription) IconButton(onClick = { speech.stop(); showVoiceOptions = true },
                            enabled = !ui.asking && !ui.importWorking && !ui.suggestionWorking && !ui.conversationOpening,
                            modifier = Modifier.testTag("workspace-voice")) {
                            Icon(Icons.Outlined.MicNone, stringResource(R.string.workspace_voice_options))
                        }
                        IconButton(onClick = { showVoiceSettings = true }, modifier = Modifier.testTag("workspace-voice-options")) {
                            Icon(Icons.Outlined.VolumeUp, stringResource(R.string.rich_voice_settings))
                        }
                        SuggestionAction097(
                            target = suggestionTarget,
                            contextLabel = suggestionContext,
                            language = appLanguage,
                            connected = ui.connected,
                            busy = ui.asking || ui.importWorking || ui.suggestionWorking || ui.conversationOpening || !ui.conversationReady,
                            onReadSnapshot = onSuggestionSheet,
                            onGenerate = onGenerateSuggestion,
                            onLoadMore = onLoadMoreSuggestion,
                            onCancelGeneration = onCancelSuggestionGeneration,
                            onConnect = onConnect,
                            onUse = onUseSuggestion,
                            onEdit = onEditSuggestion,
                            testTag = "workspace-suggestions",
                        )
                        if (speech.readingId != null) IconButton(onClick = speech.stop) { Icon(Icons.Outlined.VolumeOff, stringResource(R.string.workspace_stop_audio)) }
                        Spacer(Modifier.weight(1f))
                        if (ui.asking) FilledTonalButton(onClick = { speech.stop(); onCancel() }, modifier = Modifier.heightIn(min = 44.dp).testTag("workspace-cancel")) {
                            Icon(Icons.Outlined.Stop, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.workspace_stop))
                        } else FilledIconButton(onClick = { send(draft) }, enabled = ui.connected && draft.isNotBlank() && !ui.importWorking && !ui.suggestionWorking && !ui.conversationOpening && ui.conversationReady,
                            modifier = Modifier.size(44.dp).testTag("workspace-send")) { Icon(Icons.AutoMirrored.Outlined.Send, stringResource(R.string.feed_ask_send), Modifier.size(20.dp)) }
                    }
                }
            }
        }
    }
    pendingReplaceDraft?.let { pending ->
        AlertDialog(
            onDismissRequest = {
                pendingReplaceDraft = null
                onSuggestionDraftApplied(pending.id)
            },
            title = { Text(stringResource(R.string.suggestions_replace_title)) },
            text = { Text(stringResource(R.string.suggestions_replace_body)) },
            confirmButton = {
                mebot.nguli.com.security.ProtectedWindowRegistration()
                TextButton(onClick = {
                    drafts = HashMap(drafts).apply { put(pending.conversationId, pending.text.take(2000)) }
                    pendingReplaceDraft = null
                    focusComposerAfterEdit = true
                    onSuggestionDraftApplied(pending.id)
                }, modifier = Modifier.testTag("workspace-suggestion-replace")) {
                    Text(stringResource(R.string.suggestions_replace_draft))
                }
            },
            dismissButton = {
                mebot.nguli.com.security.ProtectedWindowRegistration()
                TextButton(onClick = {
                    pendingReplaceDraft = null
                    onSuggestionDraftApplied(pending.id)
                }, modifier = Modifier.testTag("workspace-suggestion-keep")) {
                    Text(stringResource(R.string.suggestions_keep_draft))
                }
            },
        )
    }
    if (showConversationFiles) ModalBottomSheet(onDismissRequest = { showConversationFiles = false }) {
        mebot.nguli.com.security.ProtectedWindowRegistration()
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.rich_conversation_files, conversation?.attachments.orEmpty().size), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.rich_conversation_files_help), style = MaterialTheme.typography.bodyMedium)
            conversation?.attachments.orEmpty().forEach { attachment ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { reviewAttachmentId = attachment.id }, modifier = Modifier.weight(1f)) { Text(attachment.name) }
                    IconButton(onClick = { onRemoveAttachment(attachment.id) }, enabled = !ui.asking && !ui.importWorking) {
                        Icon(Icons.Outlined.Close, stringResource(R.string.workspace_remove_attachment))
                    }
                }
            }
        }
    }
    if (showVoiceSettings) ModalBottomSheet(onDismissRequest = { showVoiceSettings = false }) {
        mebot.nguli.com.security.ProtectedWindowRegistration()
        Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.rich_voice_settings), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.rich_voice_help), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.workspace_speech_privacy), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            var rate by remember { mutableFloatStateOf(state?.speechRate ?: 1f) }
            val speedLabel = stringResource(R.string.rich_voice_speed)
            val speedValue = stringResource(R.string.rich_voice_speed_value, rate)
            val slowerLabel = stringResource(R.string.rich_voice_speed_slower)
            val fasterLabel = stringResource(R.string.rich_voice_speed_faster)
            Row(Modifier.fillMaxWidth().testTag("workspace-speech-rate"), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.AutoMirrored.Outlined.VolumeUp, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f).semantics {
                    contentDescription = "$speedLabel, $speedValue"
                    stateDescription = speedValue
                }) {
                    Text(speedLabel, style = MaterialTheme.typography.labelLarge)
                    Text(speedValue, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(stringResource(R.string.rich_voice_speed_help), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    IconButton(onClick = {
                        rate = ((rate + 0.1f) * 10).roundToInt() / 10f
                        onSpeechPreferences(rate, state?.speechVoice.orEmpty())
                    }, enabled = rate < 1.5f, modifier = Modifier.size(44.dp).testTag("workspace-speech-rate-increase")) {
                        Icon(Icons.Outlined.Add, fasterLabel, Modifier.size(20.dp))
                    }
                    IconButton(onClick = {
                        rate = ((rate - 0.1f) * 10).roundToInt() / 10f
                        onSpeechPreferences(rate, state?.speechVoice.orEmpty())
                    }, enabled = rate > 0.6f, modifier = Modifier.size(44.dp).testTag("workspace-speech-rate-decrease")) {
                        Icon(Icons.Outlined.Remove, slowerLabel, Modifier.size(20.dp))
                    }
                }
            }
            Text(stringResource(R.string.rich_voice_choice), style = MaterialTheme.typography.labelLarge)
            speech.statusMessage?.let { Text(stringResource(it), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag("workspace-speech-status")) }
            TextButton(onClick = speech.retry, modifier = Modifier.testTag("workspace-speech-retry")) {
                Text(stringResource(R.string.workspace_speech_retry))
            }
            TextButton(onClick = { speech.stop(); onSpeechPreferences(rate, "") }) { Text(stringResource(R.string.rich_voice_default)) }
            speech.voices.forEach { (name, label) ->
                TextButton(onClick = { speech.stop(); onSpeechPreferences(rate, name) }, modifier = Modifier.fillMaxWidth()) {
                    Text(label, Modifier.weight(1f)); if (state?.speechVoice == name) Icon(Icons.Outlined.Check, null, Modifier.size(18.dp))
                }
            }
        }
    }
    if (showVoiceCapture) WorkspaceVoiceCapture(
        ownerKey = draftKey,
        existingClip = existingVoiceClip,
        existingClipHasTranscript = voiceTranscriptApplied,
        onClipReady = { clip -> rememberVoiceClip(draftKey, clip) },
        onDiscardClip = ::discardVoiceClip,
        onTranscript = { transcript ->
            val revised = mergeWorkspaceTranscriptIntoDraft(draft, transcript)
            setDraft(revised)
            decodeWorkspaceClip(voiceClipsByDraft[draftKey])?.let { saved ->
                resolveWorkspaceClip(context, saved.path, saved.durationMillis)?.let { clip ->
                    voiceClipsByDraft = HashMap(voiceClipsByDraft).apply { put(draftKey, encodeWorkspaceClip(clip, transcriptApplied = true)) }
                }
            }
            focusComposerAfterEdit = true
            showVoiceCapture = false
        },
        onClose = { showVoiceCapture = false },
        transcriber = onTranscribeVoice,
    )
    LaunchedEffect(canUseLiveVoice, canUseTranscription) {
        if (!canUseLiveVoice && !canUseTranscription) showVoiceOptions = false
    }
    if (showVoiceOptions && (canUseLiveVoice || canUseTranscription)) WorkspaceVoiceOptionsSheet(
        capability = realtimeCapability,
        onStartCall = onStartRealtimeVoiceCall,
        selectedItemTitle = story?.title.orEmpty(),
        currentModuleName = if (voiceContextModule != null) moduleName(voiceContextModule) else "",
        conversationTitle = conversation?.title.orEmpty(),
        hasRecentHistory = conversation?.turns?.isNotEmpty() == true,
        hasConfirmedContext = hasConfirmedVoiceContext,
        transcriptionCapability = recordingTranscriptionCapability,
        canTranscribe = onTranscribeVoice != null && recordingTranscriptionCapability.canTranscribe,
        onRecordForTranscription = {
            showVoiceOptions = false
            showVoiceCapture = true
        },
        onOpenVoiceSetup = onOpenVoiceSetup,
        onDismiss = { showVoiceOptions = false },
    )
    if (showHistory) ModalBottomSheet(onDismissRequest = { showHistory = false }) {
        mebot.nguli.com.security.ProtectedWindowRegistration()
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
            Text(stringResource(R.string.workspace_history), style = MaterialTheme.typography.titleLarge)
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp).padding(top = 12.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(conversations, key = { it.id }) { saved ->
                    Surface(color = if (saved.id == conversation?.id) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                        shape = RoundedCornerShape(16.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Surface(onClick = { speech.stop(); showHistory = false; onSelect(saved.id) }, color = androidx.compose.ui.graphics.Color.Transparent,
                                modifier = Modifier.weight(1f).testTag("conversation-${saved.id}")) {
                                Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                    Text(saved.title.ifBlank { stringResource(R.string.workspace_new) }, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text(relativeTime(saved.updatedAtMillis), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            IconButton(onClick = { deletingId = saved.id }, modifier = Modifier.testTag("conversation-delete-${saved.id}")) {
                                Icon(Icons.Outlined.DeleteOutline, stringResource(R.string.workspace_delete))
                            }
                        }
                    }
                }
            }
        }
    }
    if (showScopes) AlertDialog(onDismissRequest = { showScopes = false },
        title = { Text(stringResource(R.string.workspace_choose_context)) },
        text = { Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.workspace_context_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { showScopes = false; speech.stop(); onNew(null) }, modifier = Modifier.fillMaxWidth().testTag("workspace-scope-general")) {
                Text(stringResource(R.string.workspace_general), Modifier.fillMaxWidth())
            }
            state?.modules.orEmpty().filter { it.enabled }.forEach { choice -> TextButton(onClick = { showScopes = false; speech.stop(); onNew(choice.id) },
                modifier = Modifier.fillMaxWidth().testTag("workspace-scope-${choice.id}")) {
                Icon(moduleIcon(choice.kind), null, Modifier.size(20.dp)); Spacer(Modifier.width(10.dp)); Text(moduleName(choice), Modifier.weight(1f))
            } }
            TextButton(onClick = { showScopes = false; onContext() }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.workspace_manage_context)) }
        } }, confirmButton = { mebot.nguli.com.security.ProtectedWindowRegistration(); TextButton(onClick = { showScopes = false }) { Text(stringResource(R.string.done)) } })
    deletingId?.let { target -> AlertDialog(onDismissRequest = { deletingId = null },
        title = { Text(stringResource(R.string.workspace_delete_title)) }, text = { Text(stringResource(R.string.workspace_delete_body)) },
        confirmButton = { mebot.nguli.com.security.ProtectedWindowRegistration(); TextButton(onClick = {
            discardVoiceClipFor(target)
            onDelete(target); deletingId = null; showHistory = false
        }, modifier = Modifier.testTag("conversation-delete-confirm")) { Text(stringResource(R.string.workspace_delete)) } },
        dismissButton = { TextButton(onClick = { deletingId = null }) { Text(stringResource(R.string.cancel)) } }) }
    conversation?.attachments?.find { it.id == reviewAttachmentId }?.let { attachment -> AlertDialog(onDismissRequest = { reviewAttachmentId = null },
        title = { Text(attachment.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = { Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.workspace_attachment_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SelectionContainer { Text(attachment.text, style = MaterialTheme.typography.bodyMedium) }
        } }, confirmButton = { mebot.nguli.com.security.ProtectedWindowRegistration(); TextButton(onClick = { reviewAttachmentId = null }) { Text(stringResource(R.string.done)) } }) }
}

@Composable
private fun WorkspaceModelControl(label: String, connected: Boolean, onClick: () -> Unit, modifier: Modifier,
    enabled: Boolean = true, wrapLabel: Boolean = false) {
    Surface(onClick = onClick, enabled = enabled, color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp), modifier = modifier.testTag("workspace-model")) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(if (connected) Icons.Outlined.CheckCircleOutline else Icons.Outlined.Link, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
            Text(label, style = MaterialTheme.typography.labelMedium, maxLines = if (wrapLabel) Int.MAX_VALUE else 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Icon(Icons.Outlined.ExpandMore, null, Modifier.size(16.dp))
        }
    }
}

@Composable
private fun WorkspaceScopeControl(label: String, enabled: Boolean, onClick: () -> Unit, modifier: Modifier, wrapLabel: Boolean = false) {
    TextButton(onClick = onClick, enabled = enabled, modifier = modifier.testTag("workspace-scope")) {
        Icon(Icons.Outlined.Tune, null, Modifier.size(16.dp)); Spacer(Modifier.width(5.dp))
        Text(label, maxLines = if (wrapLabel) Int.MAX_VALUE else 1, overflow = TextOverflow.Ellipsis,
            modifier = if (wrapLabel) Modifier.weight(1f) else Modifier)
    }
}

@Composable
private fun WorkspaceStart(
    connected: Boolean,
    onConnect: () -> Unit,
    onContext: () -> Unit,
    scoped: Boolean,
    itemScoped: Boolean,
    hasConfirmedAboutYou: Boolean,
    draftPrefilled: Boolean,
    personalizationContextMissing: Boolean,
) {
    Column(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(when {
            itemScoped && draftPrefilled -> R.string.workspace_item_draft_title
            itemScoped -> R.string.workspace_item_start_title
            else -> R.string.workspace_start_title
        }), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(when {
            itemScoped && draftPrefilled -> R.string.workspace_item_draft_help
            itemScoped -> R.string.workspace_item_scope
            scoped -> R.string.workspace_topic_scope
            else -> R.string.workspace_general_context
        }),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (itemScoped && hasConfirmedAboutYou) Text(stringResource(R.string.workspace_confirmed_about_you_available),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (personalizationContextMissing) Text(stringResource(R.string.workspace_suggestions_need_context),
            Modifier.testTag("workspace-personalization-context-needed"),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!connected) FilledTonalButton(onClick = onConnect, modifier = Modifier.testTag("workspace-connect")) {
            Icon(Icons.Outlined.Link, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.feed_connect))
        }
        TextButton(onClick = onContext, contentPadding = PaddingValues(0.dp)) {
            Text(stringResource(R.string.workspace_manage_context), style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun WorkspaceTurn(turn: AiTurn, speech: WorkspaceSpeech) {
    val user = turn.role == "user"
    Surface(color = if (user) MaterialTheme.colorScheme.primaryContainer else androidx.compose.ui.graphics.Color.Transparent,
        shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth().padding(start = if (user) 28.dp else 0.dp).testTag("turn-${turn.id}")) {
        Column(Modifier.padding(if (user) 16.dp else 0.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (!user) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MeBotMark(Modifier.size(24.dp))
                Text(turn.model.ifBlank { stringResource(R.string.workspace_title) }, style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if(user) SelectionContainer { Text(turn.text, style=MaterialTheme.typography.bodyLarge, color=MaterialTheme.colorScheme.onPrimaryContainer) }
            else RichResponse(turn.text, citations=turn.citations)
            if (!user) {
                val represented = remember(turn.text) { RichContent.links(turn.text).map { FeedLogic.canonicalUrl(it.url) }.toSet() }
                ConversationSources(turn.citations.filterNot { FeedLogic.canonicalUrl(it.url) in represented }, "turn-source-${turn.id}")
                WorkspaceAnswerActions(turn.id, turn.text, speech, allowReadAloud = true)
            }
        }
    }
}

@Composable
private fun WorkspaceAnswerActions(id: String, answer: String, speech: WorkspaceSpeech, allowReadAloud: Boolean) {
    val context = LocalContext.current
    val copyLabel = stringResource(R.string.workspace_copy)
    val copiedLabel = stringResource(R.string.workspace_copy_done)
    val shareLabel = stringResource(R.string.workspace_share_answer)
    val readLabel = stringResource(if (speech.readingId == id) R.string.workspace_stop_audio else R.string.workspace_read)
    val shareFailed = stringResource(R.string.workspace_share_failed)
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag("answer-actions-$id"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton(onClick = {
            context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(copyLabel, answer))
            Toast.makeText(context, copiedLabel, Toast.LENGTH_SHORT).show()
        }, modifier = Modifier.testTag("turn-copy-$id")) {
            Icon(Icons.Outlined.ContentCopy, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(copyLabel)
        }
        TextButton(onClick = {
            val intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, answer)
            runCatching { context.startActivity(Intent.createChooser(intent, shareLabel)) }
                .onFailure { Toast.makeText(context, shareFailed, Toast.LENGTH_SHORT).show() }
        }, modifier = Modifier.testTag("turn-share-$id")) {
            Icon(Icons.Outlined.Share, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(shareLabel)
        }
        TextButton(onClick = {
            if (speech.readingId == id) speech.stop() else if (allowReadAloud) speech.read(id, answer)
        }, modifier = Modifier.testTag("turn-read-$id")) {
            Icon(if (speech.readingId == id) Icons.Outlined.Stop else Icons.AutoMirrored.Outlined.VolumeUp,
                null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(readLabel)
        }
    }
    if (speech.feedbackId == id) speech.statusMessage?.let { status ->
        Text(stringResource(status), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }.testTag("turn-speech-status-$id"))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WorkspaceVoiceOptionsSheet(
    capability: RealtimeCapability,
    onStartCall: (() -> Unit)?,
    selectedItemTitle: String,
    currentModuleName: String,
    conversationTitle: String,
    hasRecentHistory: Boolean,
    hasConfirmedContext: Boolean,
    transcriptionCapability: RecordingTranscriptionCapability,
    canTranscribe: Boolean,
    onRecordForTranscription: () -> Unit,
    onOpenVoiceSetup: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    var confirmLiveCall by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(stringResource(R.string.workspace_voice_options_title), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.workspace_voice_options_intro), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (capability.canOfferCall && onStartCall != null) {
                Text(stringResource(R.string.workspace_voice_live_label), style = MaterialTheme.typography.titleMedium)
                Button(onClick = { confirmLiveCall = true }, modifier = Modifier.fillMaxWidth()
                    .testTag("workspace-voice-live-start")) {
                    Icon(Icons.Outlined.Call, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.workspace_voice_live_start))
                }
            }
            if (canTranscribe) {
                Text(stringResource(R.string.workspace_voice_transcription_label), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.workspace_voice_transcription_available), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = onRecordForTranscription, modifier = Modifier.fillMaxWidth()
                    .testTag("workspace-voice-record-transcribe")) {
                    Icon(Icons.Outlined.Mic, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.workspace_voice_record_transcribe))
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
    if (confirmLiveCall && capability.canOfferCall && onStartCall != null) AlertDialog(
        onDismissRequest = { confirmLiveCall = false },
        title = { Text(stringResource(R.string.workspace_voice_live_confirm_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.workspace_voice_live_confirm_body))
                if (selectedItemTitle.isNotBlank()) Text(stringResource(R.string.workspace_voice_live_selected_item, selectedItemTitle))
                else if (currentModuleName.isNotBlank()) Text(stringResource(R.string.workspace_voice_live_selected_module, currentModuleName))
                if (hasRecentHistory) Text(stringResource(R.string.workspace_voice_live_recent_history,
                    conversationTitle.ifBlank { stringResource(R.string.workspace_title) }))
                if (hasConfirmedContext) Text(stringResource(R.string.workspace_voice_live_approved_context))
                if (selectedItemTitle.isBlank() && currentModuleName.isBlank() && !hasRecentHistory && !hasConfirmedContext) {
                    Text(stringResource(R.string.workspace_voice_live_no_workspace_context))
                }
                Text(stringResource(R.string.workspace_voice_live_other_chats_excluded))
            }
        },
        confirmButton = {
            TextButton(onClick = {
                confirmLiveCall = false
                onDismiss()
                onStartCall()
            }, modifier = Modifier.testTag("workspace-voice-live-confirm")) {
                Text(stringResource(R.string.workspace_voice_live_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = { confirmLiveCall = false }, modifier = Modifier.testTag("workspace-voice-live-cancel")) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

private fun voiceCapabilityMessage(reason: RealtimeCapability.Reason): Int = when (reason) {
    RealtimeCapability.Reason.SERVICE_NOT_CONFIGURED -> R.string.workspace_voice_live_service_not_configured
    RealtimeCapability.Reason.SERVICE_TOKEN_MISSING -> R.string.workspace_voice_live_service_token_missing
    RealtimeCapability.Reason.REALTIME_NOT_ENABLED -> R.string.workspace_voice_live_not_enabled
    RealtimeCapability.Reason.API_ACCESS_NOT_CONFIRMED -> R.string.workspace_voice_live_api_access_not_confirmed
    RealtimeCapability.Reason.API_ACCESS_UNAVAILABLE -> R.string.workspace_voice_live_api_access_unavailable
    RealtimeCapability.Reason.TRANSPORT_NOT_AVAILABLE -> R.string.workspace_voice_live_transport_missing
    RealtimeCapability.Reason.DAILY_LIMIT_REACHED -> R.string.workspace_voice_live_daily_limit
    RealtimeCapability.Reason.TEMPORARY_ERROR -> R.string.workspace_voice_live_temporary_error
    RealtimeCapability.Reason.ACTIVE_CALL -> R.string.workspace_voice_live_active_call
}

private fun transcriptionCapabilityMessage(capability: RecordingTranscriptionCapability, hasCallback: Boolean): Int = when (capability) {
    RecordingTranscriptionCapability.Available -> if (hasCallback) R.string.workspace_voice_transcription_available
        else R.string.workspace_voice_transcription_transport_missing
    is RecordingTranscriptionCapability.Unavailable -> when (capability.reason) {
        RecordingTranscriptionCapability.Reason.SERVICE_NOT_CONFIGURED -> R.string.workspace_voice_transcription_unavailable
        RecordingTranscriptionCapability.Reason.SERVICE_TOKEN_MISSING -> R.string.workspace_voice_transcription_service_token_missing
        RecordingTranscriptionCapability.Reason.TRANSCRIPTION_NOT_CONFIGURED -> R.string.workspace_voice_transcription_not_configured
        RecordingTranscriptionCapability.Reason.API_ACCESS_UNAVAILABLE -> R.string.workspace_voice_transcription_api_unavailable
        RecordingTranscriptionCapability.Reason.DAILY_LIMIT_REACHED -> R.string.workspace_voice_transcription_daily_limit
        RecordingTranscriptionCapability.Reason.TEMPORARY_ERROR -> R.string.workspace_voice_transcription_temporary_error
    }
}
