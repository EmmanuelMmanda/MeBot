package mebot.nguli.com

import android.Manifest
import android.content.pm.PackageManager
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.view.ContextThemeWrapper
import androidx.fragment.app.FragmentActivity
import mebot.nguli.com.security.*
import mebot.nguli.com.documents.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.view.WindowManager
import androidx.core.content.ContextCompat
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import mebot.nguli.com.alarm.AlarmActivity
import mebot.nguli.com.core.*
import mebot.nguli.com.ui.*
import mebot.nguli.com.ui.feed.*
import mebot.nguli.com.feed.RealtimeCall099ViewModel
import mebot.nguli.com.feed.RealtimeScope099
import mebot.nguli.com.feed.RealtimeScope099Snapshot
import mebot.nguli.com.realtime.*
import mebot.nguli.com.feed.Realtime099ViewModel
import mebot.nguli.com.feed.FeedViewModel
import mebot.nguli.com.feed.FeedUiState
import mebot.nguli.com.feed.SuggestionGenerationHandle097
import mebot.nguli.com.feed.SuggestionSheetSnapshot097
import mebot.nguli.com.feed.SuggestionTargets097
import mebot.nguli.com.feed.SuggestionTargetSnapshot097
import mebot.nguli.com.feed.auth.ChatGptReturnLink
import androidx.compose.ui.platform.testTag
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.ZonedDateTime
import java.time.LocalDate
import java.util.Locale
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.awaitCancellation

class MainActivity : FragmentActivity() {
    private val appLock get() = (application as MeBotApplication).appLock
    private lateinit var biometricUnlock: BiometricUnlock
    private lateinit var privacyCurtain: PrivacyCurtain
    private var screenReceiverRegistered = false
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                voicePrivacyStop?.invoke()
                privacyCurtain.cover()
                appLock.lock()
                biometricUnlock.cancel()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::privacyCurtain.isInitialized) privacyCurtain.cover()
        appLock.foreground(true)
    }

    private var voicePrivacyStop: (() -> Unit)? = null

    override fun onPause() {
        voicePrivacyStop?.invoke()
        // Lock before leaving personal UI; FLAG_SECURE also prevents recents/screenshot capture.
        if (::privacyCurtain.isInitialized) privacyCurtain.cover()
        appLock.foreground(false)
        if (::biometricUnlock.isInitialized) biometricUnlock.cancel()
        super.onPause()
    }

    override fun onDestroy() {
        voicePrivacyStop?.invoke(); voicePrivacyStop = null
        if (screenReceiverRegistered) unregisterReceiver(screenReceiver)
        if (::privacyCurtain.isInitialized) privacyCurtain.close()
        if (::biometricUnlock.isInitialized) biometricUnlock.cancel()
        super.onDestroy()
    }

    private var softwareNavigationRequest by mutableIntStateOf(0)
    private var alarmNavigationRequest by mutableIntStateOf(0)
    private var feedNavigationRequest by mutableIntStateOf(0)
    private var feedNavigationItem by mutableStateOf<String?>(null)
    private var chatGptNavigationRequest by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Synchronous fail-closed state is known before any personal composable is mounted.
        if (appLock.state.value.enabled) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        biometricUnlock = BiometricUnlock(this, appLock)
        privacyCurtain = PrivacyCurtain(this, appLock)
        privacyCurtain.cover()
        ContextCompat.registerReceiver(this, screenReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED)
        screenReceiverRegistered = true
        alarmNavigationRequest = savedInstanceState?.getInt("mebot.alarmNavigationRequest")
            ?: if (intent?.action == "mebot.nguli.com.OPEN_ALARMS") 1 else 0
        feedNavigationRequest = savedInstanceState?.getInt("mebot.feedNavigationRequest")
            ?: if (intent?.action == "mebot.nguli.com.OPEN_FEED") 1 else 0
        feedNavigationItem = savedInstanceState?.getString("mebot.feedNavigationItem") ?: intent?.getStringExtra("item_id")
        chatGptNavigationRequest = savedInstanceState?.getInt("mebot.chatGptNavigationRequest")
            ?: if (ChatGptReturnLink.matches(intent, packageName)) 1 else 0
        softwareNavigationRequest = savedInstanceState?.getInt("mebot.softwareNavigationRequest")
            ?: if (intent?.action == "mebot.nguli.com.OPEN_SOFTWARE_UPDATES") 1 else 0
        mebot.nguli.com.updates.SoftwareUpdateWorker.schedule(this, (application as MeBotApplication).softwareUpdates.state.value.automaticChecks)
        enableEdgeToEdge()
        setContent {
            val vm: MeBotViewModel = viewModel(factory = remember(application) {
                ViewModelProvider.AndroidViewModelFactory(application)
            })
            // Use FragmentActivity's SavedStateHandle-aware factory while keeping this
            // Activity-scoped FeedViewModel tied to the same MeBotApplication instance.
            val feedVm: FeedViewModel = viewModel()
            val feedUi by feedVm.ui.collectAsStateWithLifecycle()
            val ui by vm.ui.collectAsStateWithLifecycle()
            val settings = ui.state?.settings ?: Settings()
            val context = LocalContext.current
            val configuration = LocalConfiguration.current
            val localized = remember(context, configuration, settings.language) {
                ContextThemeWrapper(context, R.style.Theme_MeBot).apply {
                    applyOverrideConfiguration(Configuration(configuration).apply {
                        if (settings.language != "system") setLocale(Locale.forLanguageTag(settings.language))
                    })
                }
            }
            CompositionLocalProvider(
                LocalContext provides localized,
                LocalConfiguration provides localized.resources.configuration,
                LocalResources provides localized.resources,
            ) {
                MeBotTheme(settings) {
                    val lightBars = MaterialTheme.colorScheme.background.luminance() > .5
                    SideEffect {
                        WindowCompat.getInsetsController(window, window.decorView).apply {
                            isAppearanceLightStatusBars = lightBars
                            isAppearanceLightNavigationBars = lightBars
                        }
                    }
                    val protection by appLock.state.collectAsStateWithLifecycle()
                    val coverEpoch = privacyCurtain.epoch
                    val lockTicket = appLock.ticket()
                    LaunchedEffect(protection.locked) {
                        if (protection.locked) {
                            feedVm.cancelConversationOpen(); feedVm.clearAnswer(); feedVm.cancelDetail(); feedVm.cancelSearch()
                        }
                    }
                    SideEffect {
                        if (protection.enabled) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    }
                    val protectedScreens = rememberSaveableStateHolder()
                    val biometricTitle = stringResource(R.string.app_lock_unlock)
                    val biometricPinLabel = stringResource(R.string.app_lock_use_pin)
                    DocumentActionsHost(
                        locked = protection.locked,
                        targetIdentity = { operation -> when (operation) {
                            DocumentOperation.IMPORT_ATTACHMENT -> feedVm.ui.value.activeConversationId
                            DocumentOperation.EXPORT_CHECKPOINT -> checkpointDocumentIdentity(vm.ui.value.state?.settings?.checkpointCode.orEmpty())
                            else -> null
                        } },
                        canUseDocuments = { !appLock.state.value.locked && lifecycle.currentState == Lifecycle.State.RESUMED },
                        onLaunchFailure = { vm.showMessage(R.string.document_picker_error) },
                        onDocument = { operation, uri, target -> when (operation) {
                            DocumentOperation.EXPORT_CHECKPOINT -> vm.exportCheckpoint(uri, target)
                            DocumentOperation.IMPORT_ATTACHMENT -> feedVm.importTextAttachment(uri, target)
                            DocumentOperation.IMPORT_CONTEXT -> feedVm.prepareContextImport(uri)
                            DocumentOperation.EXPORT_CONTEXT -> feedVm.exportContext(uri)
                            DocumentOperation.IMPORT_FEED -> feedVm.prepareFeedBackup(uri)
                            DocumentOperation.EXPORT_FEED -> feedVm.exportFeedBackup(uri)
                            DocumentOperation.IMPORT_LOCAL -> vm.importBackup(uri)
                            DocumentOperation.EXPORT_LOCAL -> vm.exportBackup(uri)
                        } },
                    ) {
                    if (protection.locked) AppLockScreen(appLock) { biometricUnlock.authenticate(biometricTitle, biometricPinLabel) }
                    else protectedScreens.SaveableStateProvider("personal-navigation") {
                        FeedClock(timezone = feedUi.state?.timezone.orEmpty(), noticeDismissals = feedUi.state?.dismissedNotices.orEmpty()) { MeBotApp(ui, vm, feedUi, feedVm, alarmNavigationRequest, feedNavigationRequest, feedNavigationItem, chatGptNavigationRequest, softwareNavigationRequest) { voicePrivacyStop = it } }
                    }
                    }
                    SideEffect { privacyCurtain.committed(coverEpoch, lockTicket, protection.locked) }
                }
            }
        }
    }

    public override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == "mebot.nguli.com.OPEN_SOFTWARE_UPDATES") softwareNavigationRequest++
        if (intent.action == "mebot.nguli.com.OPEN_ALARMS") alarmNavigationRequest++
        if (intent.action == "mebot.nguli.com.OPEN_FEED") { feedNavigationItem = intent.getStringExtra("item_id"); feedNavigationRequest++ }
        // This intent only changes navigation. OAuth codes and tokens are never read from it.
        if (ChatGptReturnLink.matches(intent, packageName)) chatGptNavigationRequest++
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("mebot.softwareNavigationRequest", softwareNavigationRequest)
        outState.putInt("mebot.alarmNavigationRequest", alarmNavigationRequest)
        outState.putInt("mebot.feedNavigationRequest", feedNavigationRequest)
        outState.putString("mebot.feedNavigationItem", feedNavigationItem)
        outState.putInt("mebot.chatGptNavigationRequest", chatGptNavigationRequest)
    }
}

@Composable
private fun MeBotApp(ui: AppUiState, vm: MeBotViewModel, feedUi: FeedUiState, feedVm: FeedViewModel,
    alarmNavigationRequest: Int, feedNavigationRequest: Int, feedNavigationItem: String?, chatGptNavigationRequest: Int, softwareNavigationRequest: Int,
    onVoicePrivacyHandler: ((() -> Unit)?) -> Unit) {
    val voiceVm: Realtime099ViewModel = viewModel()
    val voiceSetupUi by voiceVm.ui.collectAsStateWithLifecycle()
    val callVm: RealtimeCall099ViewModel = viewModel()
    val callUi by callVm.ui.collectAsStateWithLifecycle()
    var page by rememberSaveable { mutableStateOf("feed") }
    var showQuickGuide by rememberSaveable { mutableStateOf(false) }
    var explicitlyOpenedSetup099 by rememberSaveable { mutableStateOf(false) }
    var finishingSetup099 by remember { mutableStateOf(false) }
    LaunchedEffect(feedUi.error) { if (feedUi.error != null) finishingSetup099 = false }
    var consumedSoftwareNavigation by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(softwareNavigationRequest) { if(softwareNavigationRequest > consumedSoftwareNavigation) { consumedSoftwareNavigation=softwareNavigationRequest; page="software-updates" } }
    var consumedAlarmNavigation by rememberSaveable { mutableIntStateOf(0) }
    var consumedFeedNavigation by rememberSaveable { mutableIntStateOf(0) }
    var consumedChatGptNavigation by rememberSaveable { mutableIntStateOf(0) }
    var alarmId by rememberSaveable { mutableStateOf<String?>(null) }
    var itemId by rememberSaveable { mutableStateOf<String?>(null) }
    var detailReturn by rememberSaveable { mutableStateOf("feed") }
    var aiReturn by rememberSaveable { mutableStateOf("feed") }
    var connectionReturn by rememberSaveable { mutableStateOf("you") }
    var contextReturn by rememberSaveable { mutableStateOf("you") }
    var interestsReturn by rememberSaveable { mutableStateOf("you") }
    var interestsInitialText by rememberSaveable { mutableStateOf<String?>(null) }
    var interestsFinishRequest by rememberSaveable { mutableIntStateOf(0) }
    var interestsReturnRequest by rememberSaveable { mutableIntStateOf(0) }
    var contextImportRefreshRequest by rememberSaveable { mutableIntStateOf(0) }
    var contextNotesRevealRequest by rememberSaveable { mutableIntStateOf(0) }
    var deliveryReturn by rememberSaveable { mutableStateOf("you") }
    var moduleDraft by rememberSaveable { mutableStateOf<String?>(null) }
    var moduleIsNew by rememberSaveable { mutableStateOf(false) }
    var moduleCatalogFilter by rememberSaveable { mutableStateOf(ModuleDiscovery.ALL) }
    var feedTopRequest by rememberSaveable { mutableIntStateOf(0) }
    var suggestedTitle by rememberSaveable { mutableStateOf<String?>(null) }
    var suggestedMinutes by rememberSaveable { mutableStateOf<Int?>(null) }
    var checkpointReturn by rememberSaveable { mutableStateOf("modules") }
    var visitDate by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    val screenStates = rememberSaveableStateHolder()
    var date by remember { mutableStateOf(LocalDate.now()) }
    val owner = LocalLifecycleOwner.current
    val context = LocalContext.current
    val resources = LocalResources.current
    var voiceFactory by remember { mutableStateOf<AndroidRealtimePeerConnectionFactory?>(null) }
    var pendingVoiceScope by remember { mutableStateOf<RealtimeScope099Snapshot?>(null) }
    var confirmVoiceRestart by remember { mutableStateOf(false) }
    val voicePermissionGate = remember { VoicePermissionTicket099() }
    val voicePermissionHost = remember { java.util.UUID.randomUUID().toString() }
    var voicePermissionRequest by remember { mutableStateOf<VoicePermissionTicket099.Request?>(null) }
    val voicePermissionLaunchSlot = remember { VoicePermissionLaunchSlot099<RealtimeScope099Snapshot>(voicePermissionGate) }
    val latestPage by rememberUpdatedState(page)
    val app099 = context.applicationContext as MeBotApplication
    var itemPreparationBridge by remember { mutableStateOf<Pair<String, SuggestionGenerationHandle097>?>(null) }
    fun releaseItemPreparationBridge() {
        val old = itemPreparationBridge
        itemPreparationBridge = null
        old?.second?.let(feedVm::releaseSuggestionPreparation097)
    }
    DisposableEffect(owner, feedVm) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) {
            releaseItemPreparationBridge()
            feedVm.cancelSuggestionPreparationsOnBackground099()
        } }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); releaseItemPreparationBridge() }
    }
    LaunchedEffect(page, itemId) {
        if (page != "item" || itemPreparationBridge?.first != itemId) releaseItemPreparationBridge()
    }
    fun stopVoice(reason: String) {
        callVm.stop(reason)
        voiceFactory?.privacyStopImmediately()
    }
    DisposableEffect(owner, voiceVm, callVm) {
        val stop = { stopVoice("host_background_or_lock"); voiceVm.cancelOperations() }
        onVoicePrivacyHandler(stop)
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_PAUSE) stop() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); stop(); onVoicePrivacyHandler(null) }
    }
    fun beginVoiceCall(snapshot: RealtimeScope099Snapshot) {
        if (latestPage !in setOf("ai", "voice-call") || owner.lifecycle.currentState != Lifecycle.State.RESUMED ||
            !RealtimeScope099.stillCurrent(snapshot, feedVm.ui.value, app099.appLock.state.value.locked)) {
            pendingVoiceScope = null
            return
        }
        stopVoice("new_call")
        pendingVoiceScope = snapshot
        voiceFactory = AndroidRealtimePeerConnectionFactory(context)
        page = "voice-call"
    }
    fun currentPermissionScopeKey(): String? = pendingVoiceScope?.let { snapshot ->
        voicePermissionRequest?.scopeKey?.takeIf {
            RealtimeScope099.stillCurrent(snapshot, feedVm.ui.value, app099.appLock.state.value.locked)
        }
    }
    val voicePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val launched = voicePermissionLaunchSlot.consumeLaunch()
        val ticket = launched?.request
        val snapshot = launched?.payload
        if (ticket != null && snapshot != null) {
            when (voicePermissionGate.onPermissionResult(ticket, granted,
                owner.lifecycle.currentState == Lifecycle.State.RESUMED,
                latestPage in setOf("ai", "voice-call"), !app099.appLock.state.value.locked,
                currentPermissionScopeKey())) {
                VoicePermissionTicket099.Result.START_NOW -> {
                    voicePermissionRequest = null
                    beginVoiceCall(snapshot)
                }
                VoicePermissionTicket099.Result.WAIT_FOR_RESUME -> Unit
                VoicePermissionTicket099.Result.DENIED -> {
                    voicePermissionRequest = null; pendingVoiceScope = null
                    vm.showMessage(R.string.workspace_voice_permission_denied)
                }
                VoicePermissionTicket099.Result.STALE -> {
                    voicePermissionRequest = null; pendingVoiceScope = null
                }
            }
        }
    }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                voicePermissionGate.invalidateOnStop()
                voicePermissionRequest = null
                if (voiceFactory == null) pendingVoiceScope = null
            } else if (event == Lifecycle.Event.ON_RESUME) {
                val snapshot = pendingVoiceScope
                val ticket = voicePermissionGate.consumeOnResume(latestPage in setOf("ai", "voice-call"),
                    !app099.appLock.state.value.locked, currentPermissionScopeKey())
                if (ticket != null && snapshot != null) {
                    voicePermissionRequest = null
                    beginVoiceCall(snapshot)
                }
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); voicePermissionGate.invalidateOnStop() }
    }
    LaunchedEffect(page, feedUi) {
        voicePermissionGate.invalidateIfScopeChanged(page in setOf("ai", "voice-call"),
            !app099.appLock.state.value.locked, currentPermissionScopeKey())
        if (!voicePermissionGate.hasPendingRequest()) voicePermissionRequest = null
    }
    LaunchedEffect(app099) {
        app099.appLock.state.collect { if (it.locked) {
            voicePermissionGate.invalidateOnLock()
            voicePermissionRequest = null
            if (voiceFactory == null) pendingVoiceScope = null
        } }
    }
    fun requestVoiceCall() {
        if (voicePermissionLaunchSlot.hasOutstandingLaunch() || voicePermissionGate.hasPendingRequest()) return
        if (voiceSetupUi.capability?.voice?.canOfferCall != true) { page = "voice-setup"; return }
        val snapshot = RealtimeScope099.project(feedVm.ui.value) ?: return
        if (app099.appLock.state.value.locked || owner.lifecycle.currentState != Lifecycle.State.RESUMED) return
        pendingVoiceScope = snapshot
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
            beginVoiceCall(snapshot)
        else {
            val launch = voicePermissionLaunchSlot.issue(java.util.UUID.randomUUID().toString(), voicePermissionHost, snapshot) ?: return
            voicePermissionRequest = launch.request
            voicePermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    LaunchedEffect(page, voiceFactory) {
        val factory = voiceFactory
        val snapshot = pendingVoiceScope
        if (page == "voice-call" && factory != null && snapshot != null) {
            callVm.start(snapshot, factory) {
                voiceVm.ui.value.capability?.voice?.canOfferCall == true &&
                latestPage == "voice-call" && owner.lifecycle.currentState == Lifecycle.State.RESUMED &&
                    RealtimeScope099.stillCurrent(snapshot, feedVm.ui.value, app099.appLock.state.value.locked)
            }
        } else if (callVm.boundScope != null) stopVoice("navigation")
    }
    LaunchedEffect(feedUi, callUi.snapshot.phase, voiceSetupUi.capability) {
        callVm.boundScope?.let { snapshot ->
            if (voiceSetupUi.capability?.voice?.canOfferCall != true ||
                !RealtimeScope099.stillCurrent(snapshot, feedVm.ui.value, app099.appLock.state.value.locked)) stopVoice("context_or_capability_changed")
        }
    }
    if (confirmVoiceRestart) AlertDialog(
        onDismissRequest = { confirmVoiceRestart = false },
        title = { Text(stringResource(R.string.workspace_voice_live_confirm_title)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.workspace_voice_live_confirm_body))
            RealtimeScope099.project(feedUi)?.context?.selectedItem?.let {
                Text(stringResource(R.string.workspace_voice_live_selected_item, it.title))
            }
        } },
        confirmButton = { TextButton(onClick = { confirmVoiceRestart = false; requestVoiceCall() }) { Text(stringResource(R.string.workspace_voice_live_confirm)) } },
        dismissButton = { TextButton(onClick = { confirmVoiceRestart = false }) { Text(stringResource(android.R.string.cancel)) } },
    )
    val voiceScope = RealtimeScope099.project(feedUi)
    val voiceTranscriptionBilling = stringResource(R.string.workspace_voice_transcribe_service_billing)
    val voiceTranscriber = voiceScope?.let { snapshot ->
        voiceVm.transcriber("OpenAI · " + runCatching { java.net.URI(voiceSetupUi.savedServiceUrl).host }.getOrNull().orEmpty(),
            voiceTranscriptionBilling) {
            latestPage == "ai" && owner.lifecycle.currentState == Lifecycle.State.RESUMED &&
                RealtimeScope099.stillCurrent(snapshot, feedVm.ui.value, app099.appLock.state.value.locked)
        }
    }

    val softwareUpdate099 by (context.applicationContext as MeBotApplication).softwareUpdates.state.collectAsStateWithLifecycle()
    val readSuggestionSheet: (SuggestionTargetSnapshot097, String) -> SuggestionSheetSnapshot097 = feedVm::suggestionSheet097
    val generateSuggestion: (SuggestionTargetSnapshot097, String) -> SuggestionGenerationHandle097? = generate@{ target, language ->
        if ((context.applicationContext as MeBotApplication).appLock.state.value.locked) return@generate null
        feedVm.generateSuggestions097(target, language)
    }
    val loadMoreSuggestions099: (SuggestionTargetSnapshot097, String) -> SuggestionGenerationHandle097? = more@{ target, language ->
        if ((context.applicationContext as MeBotApplication).appLock.state.value.locked) return@more null
        feedVm.loadMoreSuggestions097(target, language)
    }
    val prepareSuggestions099: (SuggestionTargetSnapshot097, String) -> SuggestionGenerationHandle097? = prepare@{ target, language ->
        val current = feedUi.state
        if ((context.applicationContext as MeBotApplication).appLock.state.value.locked ||
            current?.onboardingComplete != true || current.setup099.stage !in setOf(Setup099Stage.INTRO, Setup099Stage.COMPLETE)) return@prepare null
        feedVm.prepareSuggestions097(target, language)
    }
    val cancelSuggestionGeneration: (String) -> Unit = feedVm::cancelSuggestionGeneration097
    // Only an explicit Send action sends chat. Opening may prepare AI angles on a cache miss.
    val useSuggestion: (SuggestionTargetSnapshot097, String, String) -> Unit = use@{ target, optionId, language ->
        if ((context.applicationContext as MeBotApplication).appLock.state.value.locked) return@use
        if (page != "ai") aiReturn = if (page == "item") "item" else "feed"
        feedVm.sendSuggestion(target, optionId, language)
        page = "ai"
    }
    val editSuggestion: (SuggestionTargetSnapshot097, String, String) -> Unit = edit@{ target, optionId, language ->
        if ((context.applicationContext as MeBotApplication).appLock.state.value.locked) return@edit
        if (page != "ai") aiReturn = if (page == "item") "item" else "feed"
        feedVm.editSuggestion(target, optionId, language)
        page = "ai"
    }
    val snackbars = remember { SnackbarHostState() }
    val feedbackScope = rememberCoroutineScope()
    val latestConnected by rememberUpdatedState(feedUi.connected)
    val onStar099: (String, Boolean) -> Unit = { id, desired ->
        feedVm.setStarred099(id, desired) { committed ->
            val following = committed.starredStories.any { it.itemId == id }
            val feedback = when {
                !following -> R.string.star_099_unfollowed
                !latestConnected -> R.string.star_099_following_connect
                committed.backgroundEnabled -> R.string.star_099_following_background
                else -> R.string.star_099_following_refresh
            }
            feedbackScope.launch { snackbars.showSnackbar(resources.getString(feedback)) }
        }
    }
    val documents = LocalDocumentActions.current
    val message by vm.message.collectAsStateWithLifecycle()
    LaunchedEffect(message, resources) {
        message?.let { snackbars.showSnackbar(resources.getString(it)); vm.clearMessage() }
    }
    LaunchedEffect(feedUi.notice, resources) {
        feedUi.notice?.let { notice ->
            val needsRemoteSignOut = notice == "chatgpt_local_signout"
            val result = snackbars.showSnackbar(resources.getString(feedMessageResource(notice)),
                actionLabel = if (needsRemoteSignOut) resources.getString(R.string.chatgpt_manage_usage) else null,
                duration = if (needsRemoteSignOut) SnackbarDuration.Long else SnackbarDuration.Short)
            if (needsRemoteSignOut && result == SnackbarResult.ActionPerformed) openSource(context, CHATGPT_USAGE_URL)
            feedVm.clearNotice()
        }
    }
    LaunchedEffect(feedVm, owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            feedVm.browserLaunches.collect { url ->
                runCatching {
                    val uri = Uri.parse(url)
                    require(uri.scheme == "https" && uri.userInfo == null && uri.port == -1 && ((uri.host == "auth.openai.com" && uri.path == "/api/accounts/authorize") || (uri.host == "openrouter.ai" && uri.path == "/auth")))
                    context.startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE))
                }.onFailure { feedVm.browserLaunchFailed() }
            }
        }
    }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) { date = LocalDate.now(); vm.onResume() }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(Unit) { while (true) { delay(60_000); date = LocalDate.now() } }
    val state = ui.state
    val feed = feedUi.state
    val activeAlarm by produceState(initialValue = vm.alarms.session(), vm, owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) { value = vm.alarms.session(); delay(1_000) }
        }
    }
    val onboarded = feed?.onboardingComplete == true
    val setupActive099 = feed != null && (!onboarded || explicitlyOpenedSetup099 ||
        feed.setup099.stage !in setOf(Setup099Stage.INTRO, Setup099Stage.COMPLETE))
    val setupHasContext099 = feed?.context?.let(Setup099Logic::hasConfirmedContext) == true
    val setupChatGptConnected099 = feedUi.connected && feed?.connection?.mode == "chatgpt"
    LaunchedEffect(setupActive099, feed?.setup099?.pendingImportAccountKey, feed?.connection, feedUi.connected) {
        val pending = feed?.setup099?.pendingImportAccountKey.orEmpty()
        if (setupActive099 && feed?.setup099?.stage == Setup099Stage.IMPORT_CONTEXT && pending.isNotEmpty() && setupChatGptConnected099 &&
            feed?.connection?.accountId == pending) {
            contextReturn = "feed"
            feedVm.clearImportSummary()
            page = "context-import"
            feedVm.acknowledgeSetup099ImportRoute(pending)
        }
    }
    // The completed tour leads straight to the feed. The quick guide stays available in Help.
    LaunchedEffect(setupActive099, feed?.setup099?.stage, page) {
        if (onboarded && setupActive099 && page == "feed" && feed?.setup099?.pendingImportAccountKey.isNullOrEmpty()) {
            when (feed?.setup099?.stage) {
                Setup099Stage.CONNECT_CHATGPT -> { connectionReturn = "feed"; page = "connect" }
                Setup099Stage.IMPORT_CONTEXT, Setup099Stage.START_FEED -> { contextReturn = "feed"; page = "context" }
            }
        }
    }
    LaunchedEffect(page, onboarded, owner) {
        if (page == "feed" && onboarded) owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            feedVm.refreshIfStale()
            awaitCancellation()
        }
    }
    val basePages = setOf("feed", "modules", "ai", "saved", "you")
    fun openItem(id: String) {
        if (page != "item") detailReturn = when (page) { "saved", "search", "module-edit" -> page; else -> "feed" }
        // Acquire before Home disposes its lease: the same item's prepared request survives
        // route-effect ordering without a timer, duplicate request, or extra quota reservation.
        val target = feedVm.ui.value.state?.let { SuggestionTargets097.item(it, id, feedVm.ui.value.appLanguage) }
        val bridge = target?.let(feedVm::retainActiveSuggestionPreparationForNavigation097)
        releaseItemPreparationBridge()
        itemPreparationBridge = bridge?.let { id to it }
        itemId = id; feedVm.clearAnswerForNavigation(); feedVm.markViewed(id); page = "item"
    }
    fun openModule(id: String?) {
        val selected = feed?.modules?.find { it.id == id } ?: FeedModule()
        moduleDraft = Json.encodeToString(selected); moduleIsNew = id == null
        feedVm.clearMessage(); screenStates.removeState("module-edit"); page = "module-edit"
    }
    // Restore an obsolete utility destination safely after the 0.8.5 upgrade.
    LaunchedEffect(page) { if (page == "compare_prices") page = "modules" }
    LaunchedEffect(page, itemId) { if (page != "item") feedVm.cancelDetail() }
    LaunchedEffect(page) {
        if (page == "ai") feedVm.resumeConversationOpenIfNeeded()
        else feedVm.cancelConversationOpenForNavigation()
    }
    fun openConnection() {
        if (onboarded && !feedUi.connected && !setupHasContext099 && feed?.setup099?.stage != Setup099Stage.COMPLETE) {
            explicitlyOpenedSetup099 = true
            feedVm.setSetup099Stage(Setup099Stage.CONNECT_CHATGPT)
        }
        connectionReturn = page; feedVm.clearMessage(); screenStates.removeState("connect"); page = "connect"
    }
    fun openModuleCatalog(filter: String) {
        if (moduleCatalogFilter != filter) screenStates.removeState("modules")
        moduleCatalogFilter = filter
        page = "modules"
    }
    fun openPersonalInterests(initialText: String? = null) {
        if (page == "personal-interests") return
        interestsReturn = page.takeIf { it in setOf("you", "context", "context-import") } ?: "you"
        interestsInitialText = initialText
        interestsFinishRequest = 0
        screenStates.removeState("personal-interests")
        feedVm.clearMessage()
        page = "personal-interests"
    }
    fun back() {
        if (page == "personal-interests") { interestsFinishRequest++; return }
        if (page == "voice-call") { stopVoice("navigation"); voiceFactory = null; pendingVoiceScope = null }
        if (page == "context-import") {
            if (feedUi.importWorking || feedUi.contextSaving) return
            if (feedUi.importPreview != null) { feedVm.cancelImport(); return }
            feedVm.cancelImport()
        }
        if (page == "connect") { feedVm.cancelChatGptSignIn(); feedVm.cancelOpenRouterSignIn() }
        if (onboarded && page in setOf("connect", "context") && setupActive099) {
            explicitlyOpenedSetup099 = false
            feedVm.setSetup099Stage(Setup099Stage.INTRO)
        }
        page = when (page) {
            "checkpoint" -> checkpointReturn
            "alarm-edit" -> "alarms"
            "alarms", "focus", "history", "daily", "module-edit" -> "modules"
            "item" -> detailReturn
            "ai" -> if (aiReturn == "item" && feedUi.state?.items?.any { it.id == itemId } == true) "item" else "feed"
            "ask" -> if (itemId != null) "item" else "feed"
            "voice-setup", "voice-call" -> "ai"
            "connect" -> if (onboarded) connectionReturn else "feed"
            "context-import" -> "context"
            "context" -> contextReturn
            "data", "settings", "software-updates", "starred-settings" -> "you"
            "delivery" -> deliveryReturn
            "app-lock" -> "settings"
            "faq" -> "settings"
            "tour" -> "faq"
            "search" -> "feed"
            else -> "feed"
        }
    }
    fun openAlarm(id: String?) { screenStates.removeState("alarm-edit"); alarmId = id; page = "alarm-edit" }
    fun openFocus(title: String?, minutes: Int?) {
        if (suggestedTitle != title || suggestedMinutes != minutes) screenStates.removeState("focus")
        suggestedTitle = title; suggestedMinutes = minutes; page = "focus"
    }
    fun finishSetup() {
        if (finishingSetup099) return
        if (state?.profile?.onboardingComplete != true) vm.finishOnboarding { }
        if (setupActive099) {
            finishingSetup099 = true
            feedVm.finishSetup099 {
                finishingSetup099 = false
                explicitlyOpenedSetup099 = false
                page = "feed"
            }
        } else page = "feed"
    }
    BackHandler(page != "feed") { back() }
    LaunchedEffect(alarmNavigationRequest, onboarded) {
        if (onboarded && alarmNavigationRequest > consumedAlarmNavigation) {
            consumedAlarmNavigation = alarmNavigationRequest
            if ("alarms" in state?.settings?.enabledModules.orEmpty()) page = "alarms"
        }
    }
    LaunchedEffect(feedNavigationRequest, onboarded) {
        if (onboarded && feedNavigationRequest > consumedFeedNavigation) {
            consumedFeedNavigation = feedNavigationRequest
            if (feedNavigationItem != null) openItem(feedNavigationItem) else page = "feed"
        }
    }
    LaunchedEffect(chatGptNavigationRequest) {
        if (chatGptNavigationRequest > consumedChatGptNavigation) {
            consumedChatGptNavigation = chatGptNavigationRequest
            if (page != "connect") connectionReturn = if (onboarded) page else "feed"
            page = "connect"
        }
    }
    LaunchedEffect(state?.settings?.enabledModules) {
        val enabled = state?.settings?.enabledModules.orEmpty()
        if ((page == "history" && "history" !in enabled) ||
            (page.startsWith("alarm") && "alarms" !in enabled) || (page == "focus" && "focus" !in enabled)) page = "modules"
    }
    CompositionLocalProvider(
        LocalSetup099TipHostActions provides Setup099TipHostActions(
            eligibleUpdateAvailable = softwareUpdate099.release?.let { it.applicationId == context.packageName && it.versionCode > BuildConfig.VERSION_CODE } == true,
            onBackgroundSettings = { deliveryReturn = page; screenStates.removeState("delivery"); page = "delivery" },
            onSoftwareUpdates = { page = "software-updates" },
            voiceSetupNeeded = voiceSetupUi.capability?.voice?.canOfferCall != true,
            onVoiceSetup = { page = "voice-setup" }),
        LocalRelevanceContext provides RelevanceUiContext(feedUi.state, feedVm::rateRelevance, onStar099, feedVm::configureStar, feedVm::setSavedCollection)) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
          Column(Modifier.statusBarsPadding()) {
            ProcessingStatus(feedUi, page, itemId, Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
            if (setupActive099 && page in setOf("connect", "context", "context-import", "personal-interests")) {
                Setup099Progress(feed?.setup099?.stage ?: Setup099Stage.INTRO,
                    setupChatGptConnected099, setupHasContext099, Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            }
            if ((onboarded && ((page !in basePages && page != "tour") || activeAlarm != null)) || (!onboarded && page in setOf("connect", "personal-interests"))) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp).height(52.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (page !in basePages) IconButton(onClick = ::back) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back)) }
                    Spacer(Modifier.weight(1f))
                    if (activeAlarm != null) FilledTonalButton(onClick = { context.startActivity(Intent(context, AlarmActivity::class.java)) }) {
                        Icon(Icons.Outlined.Alarm, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.mebot_alarm_ringing))
                    }
                }
            }
          }
        },
        bottomBar = { if (onboarded && page in basePages) MeBotNavigation(page) { destination ->
            if (destination == "ai") aiReturn = "feed"
            if (destination == "feed" && page == "feed") {
                feedTopRequest++
            } else page = destination
        } },
        snackbarHost = { SnackbarHost(snackbars) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                ui.failed || (!feedUi.loading && feed == null) -> ScreenContainer { ScreenTitle(stringResource(R.string.data_error_title), stringResource(R.string.data_error_body)) }
                state == null || feed == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                !onboarded && page !in setOf("connect", "context", "context-import", "personal-interests") -> Setup099Onboarding(
                    feed.setup099, setupChatGptConnected099, setupHasContext099, state.settings.language,
                    onLanguage = { language -> vm.saveSettings { it.copy(language = language) } },
                    onConnectChatGpt = { feedVm.setSetup099Stage(Setup099Stage.CONNECT_CHATGPT); openConnection() },
                    onImportContext = {
                        feedVm.setSetup099Stage(Setup099Stage.IMPORT_CONTEXT)
                        contextReturn = "feed"; feedVm.clearImportSummary(); page = "context-import"
                    }, onSkipContext = { feedVm.skipSetup099Context() },
                    onContinuePublic = ::finishSetup, onStartFeed = ::finishSetup,
                    errorCode = feedUi.error, onDismissError = feedVm::clearMessage)
                else -> screenStates.SaveableStateProvider(if (page == "daily") "daily:$visitDate" else page) {
                    when (page) {
                        "feed" -> {
                            val now = ZonedDateTime.now()
                            val nextAlarm = if ("alarms" in state.settings.enabledModules) state.alarms.filter { it.enabled }.minByOrNull { nextAlarmOccurrence(it, now).toInstant() } else null
                            FeedHome(feedUi, ::openItem, { openModuleCatalog(ModuleDiscovery.ALL) }, { page = "you" }, ::openConnection,
                                { aiReturn = "feed"; feedVm.clearMessage(); page = "ai" }, feedVm::refresh, feedVm::toggleSaved,
                                feedVm::hideItem, feedVm::clearMessage, nextAlarm, { page = "alarms" },
                                onPrompt = { id, prompt ->
                                    aiReturn = "feed"; feedVm.openConversation(id, prompt); page = "ai"
                                }, onSearch = { page = "search" }, returnToTopRequest = feedTopRequest, onRefreshModule = feedVm::refreshModule,
                                onExploreModules = { openModuleCatalog(ModuleDiscovery.DISABLED) },
                                onContext = { contextReturn = "feed"; page = "context" }, onDismissNotice = feedVm::dismissSetupNotice,
                                onLoadMore = feedVm::loadMore,
                                onEnsureDailySelection = feedVm::ensurePersonalDailySelection098,
                                onPrepareActiveSuggestion = prepareSuggestions099,
                            onReleaseSuggestionPreparation = feedVm::releaseSuggestionPreparation097,
                                onUseSuggestion = useSuggestion, onEditSuggestion = editSuggestion,
                                onSuggestionSheet = readSuggestionSheet,
                                onGenerateSuggestion = generateSuggestion,
                                onLoadMoreSuggestion = loadMoreSuggestions099,
                                onCancelSuggestionGeneration = cancelSuggestionGeneration)
                        }
                        "modules" -> ModulesScreen(feed, state,
                            onEdit = ::openModule, onToggle = feedVm::setModuleEnabled,
                            onUtility = { route -> if (route == "focus") openFocus(null, null) else { if (route == "daily") visitDate = date.toString(); page = route } },
                            onUtilityToggle = { id, checked -> vm.saveSettings { it.copy(enabledModules = (if (checked) it.enabledModules + id else it.enabledModules - id) + "daily") } },
                            error = feedUi.error, onDismissError = feedVm::clearMessage,
                            onReorder = feedVm::reorderModules,
                            initialFilter = moduleCatalogFilter,
                        )
                        "module-edit" -> moduleDraft?.let { draft -> ModuleEditor(Json.decodeFromString(draft), feedUi, moduleIsNew,
                            { module -> feedVm.clearMessage(); feedVm.saveModule(module) }, feedVm::removeModule, { page = "modules" },
                            onSavePersonalized = { module, notes -> feedVm.clearMessage(); feedVm.saveModule(module, notes) }, onOpenItem = ::openItem) }
                        "saved" -> SavedFeed(feed, ::openItem, feedVm::toggleSaved, { page = "feed" })
                        "you" -> YouScreen(feedUi,
                            { contextReturn = "you"; feedVm.clearMessage(); page = "context" }, ::openConnection,
                            { deliveryReturn = "you"; feedVm.clearMessage(); screenStates.removeState("delivery"); page = "delivery" }, { page = "settings" }, { page = "data" }, onSoftwareUpdates={page="software-updates"}, onAppLock={page="app-lock"}, onStarred={page="starred-settings"}, onInterests={openPersonalInterests()})
                        "software-updates" -> SoftwareUpdatesScreen((LocalContext.current.applicationContext as MeBotApplication).softwareUpdates)
                        "connect" -> ConnectionScreen(feedUi, feedVm::connect, feedVm::disconnect,
                            feedVm::beginChatGptSignIn, feedVm::cancelChatGptSignIn, feedVm::selectChatGptAccount,
                            feedVm::selectChatGptModel, feedVm::disconnectChatGptAccount, feedVm::useChatGptAccount,
                            onProviderConnect=feedVm::connectProvider, onProviderModels=feedVm::loadProviderModels,
                            onOpenRouterStart=feedVm::beginOpenRouterSignIn, onOpenRouterFinish=feedVm::finishOpenRouterSignIn,
                            onOpenRouterCancel=feedVm::cancelOpenRouterSignIn, onLimitChange=feedVm::saveRequestLimit,
                            onChatGptRemoveAccount=feedVm::removeChatGptAccount,
                            firstRunSetupActive = setupActive099,
                            onFirstChatGptConnectionConfirmed = { accountKey ->
                                feedVm.recordSetup099ChatGptConfirmed(accountKey, setupEligible = setupActive099)
                            }) { refresh ->
                            if (onboarded && !setupActive099 && refresh) feedVm.refresh()
                            if (onboarded && !setupActive099) page = connectionReturn
                            else if (feedUi.connected) {
                                // Alternative providers retain the same explicit context step.
                                feedVm.setSetup099Stage(if (setupHasContext099) Setup099Stage.START_FEED else Setup099Stage.IMPORT_CONTEXT)
                                contextReturn = "feed"
                                feedVm.clearImportSummary()
                                page = if (setupHasContext099) "context" else "context-import"
                            } else page = "feed"
                        }
                        "personal-interests" -> PersonalInterests099Screen(
                            ui = feedUi, vm = feedVm, initialText = interestsInitialText,
                            finishRequest = interestsFinishRequest, onDone = {
                                if (interestsReturn == "context") interestsReturnRequest++
                                page = interestsReturn.takeIf { it in setOf("you", "context", "context-import") } ?: "context"
                            },
                        )
                        "context" -> ContextScreen(feedUi, feedVm,
                            onInterests = { text -> openPersonalInterests(text) },
                            interestsReturn = interestsReturnRequest,
                            onInterestsReturnConsumed = { request ->
                                if (interestsReturnRequest == request) interestsReturnRequest = 0
                            },
                            setup099Stage = if (setupActive099) feed.setup099.stage else null,
                            onStartSetupFeed = if (setupActive099) ::finishSetup else null,
                            onImportContext = { feedVm.clearMessage(); feedVm.clearImportSummary(); page = "context-import" },
                            importRefreshRequest = contextImportRefreshRequest,
                            onImportRefreshConsumed = { request ->
                                if (contextImportRefreshRequest == request) contextImportRefreshRequest = 0
                            },
                            revealImportedNotesRequest = contextNotesRevealRequest,
                            onNotesRevealConsumed = { request ->
                                if (contextNotesRevealRequest == request) contextNotesRevealRequest = 0
                            },
                        ) {
                            if (setupActive099) { feedVm.advanceSetup099ToStart(); page = "context" }
                            else page = contextReturn
                        }
                        "context-import" -> ContextImportScreen(feedUi, feedVm,
                            onInterests = { openPersonalInterests() },
                            onManualContext = { contextReturn = "feed"; page = "context" }, onImported = {
                            contextImportRefreshRequest++
                            if (feedUi.state?.context?.any { it.id != PersonalProfile085.PROFILE_ID && it.text.isNotBlank() } == true) {
                                contextNotesRevealRequest++
                            }
                            page = "context"
                        })
                        "delivery" -> DeliveryScreen(feedUi, feedVm, { page = deliveryReturn }, ::openConnection)
                        "data" -> FeedDataScreen(feedUi, feedVm) { page = "settings" }
                        "app-lock" -> AppLockSettingsScreen((context.applicationContext as MeBotApplication).appLock, onBack = { page = "settings" }, showBack = false)
                        "starred-settings" -> StarredSettingsScreen(feedUi, feedVm, onBackground = { deliveryReturn = "starred-settings"; screenStates.removeState("delivery"); page = "delivery" }, onConnection = ::openConnection)
                        "settings" -> SettingsScreen(state, vm, onErase = { documents.invalidatePending(); stopVoice("erase_local_data"); screenStates.removeState("ai"); voiceVm.clearForReset(onCleared = { feedVm.reset(); vm.erase(); page = "feed" }, onFailure = { vm.showMessage(R.string.feed_error_storage) }) }, onHelp = { page = "faq" }, onAppLock = { page = "app-lock" })
                        "faq" -> FaqScreen(
                            onContext = { contextReturn = "faq"; feedVm.clearMessage(); page = "context" },
                            onConnect = ::openConnection,
                            onTour = { showQuickGuide = true },
                        )
                        "tour" -> FeedOnboarding(feed, state.settings.language,
                            { language -> vm.saveSettings { it.copy(language = language) } }, { page = "faq" },
                            { connectionReturn = "faq"; feedVm.clearMessage(); screenStates.removeState("connect"); page = "connect" })
                        "item" -> FeedDetail(feedUi, itemId, feedVm::toggleSaved,
                            { aiReturn = "item"; feedVm.openConversation(itemId); page = "ai" }, { page = detailReturn },
                            onPrompt = { prompt -> itemId?.let { feedVm.followUpDetail(it, prompt) } },
                            onItem = ::openItem, onModule = ::openModule, onHide = feedVm::hideItem,
                            onLoadBrief = { itemId?.let { feedVm.loadDetail(it) } }, onCancelBrief = feedVm::cancelDetail,
                            onRetryBrief = { itemId?.let { feedVm.retryDetail(it) } },
                            onFollowUp = { prompt -> itemId?.let { feedVm.followUpDetail(it, prompt) } }, onConnect = ::openConnection,
                            onUseSuggestion = useSuggestion, onEditSuggestion = editSuggestion,
                            onSuggestionSheet = readSuggestionSheet,
                            onGenerateSuggestion = generateSuggestion,
                            onLoadMoreSuggestion = loadMoreSuggestions099,
                            onPrepareActiveSuggestion = prepareSuggestions099,
                            onReleaseSuggestionPreparation = feedVm::releaseSuggestionPreparation097,
                            onCancelSuggestionGeneration = cancelSuggestionGeneration)
                        "ai" -> AiWorkspace(feedUi, feedVm::newConversation, feedVm::selectConversation, feedVm::deleteConversation,
                            feedVm::sendConversation, feedVm::clearAnswer, ::openConnection,
                            { contextReturn = "ai"; feedVm.clearMessage(); page = "context" },
                            feedVm::importTextAttachment, feedVm::removeAttachment, onSelectModel = feedVm::switchModel, onDismissNotice = feedVm::dismissSetupNotice, onSpeechPreferences = feedVm::updateSpeechPreferences,
                            onSuggestionDraftApplied = feedVm::suggestionDraftApplied,
                            onUseSuggestion = useSuggestion, onEditSuggestion = editSuggestion,
                            onSuggestionSheet = readSuggestionSheet,
                            onGenerateSuggestion = generateSuggestion,
                            onLoadMoreSuggestion = loadMoreSuggestions099,
                            onPrepareSuggestions = prepareSuggestions099,
                            onOpenVoiceSetup = { page = "voice-setup" },
                            onTranscribeVoice = voiceTranscriber,
                            recordingTranscriptionCapability = voiceSetupUi.capability?.transcription ?: RecordingTranscriptionCapability.Unavailable(RecordingTranscriptionCapability.Reason.SERVICE_NOT_CONFIGURED),
                            realtimeCapability = voiceSetupUi.capability?.voice ?: RealtimeCapability.Unavailable(RealtimeCapability.Reason.SERVICE_NOT_CONFIGURED),
                            onStartRealtimeVoiceCall = if (voiceSetupUi.capability?.voice?.canOfferCall == true) ({ requestVoiceCall() }) else null,
                            onReleaseSuggestionPreparation = feedVm::releaseSuggestionPreparation097,
                            onCancelSuggestionGeneration = cancelSuggestionGeneration)
                        "voice-call" -> VoiceCallRouteGate099(
                            capabilityAvailable = voiceSetupUi.capability?.voice?.canOfferCall == true,
                            transportPresent = voiceFactory != null,
                            onUnavailable = { stopVoice("voice_route_unavailable"); page = "voice-setup" }) {
                          Box(Modifier.fillMaxSize()) {
                            voiceFactory?.let { RealtimeWebRtcSurface(it) }
                            RealtimeCall099Screen(callUi, onStart = { confirmVoiceRestart = true },
                                onCancelOrEnd = { stopVoice("user_ended"); voiceFactory = null },
                                onRetry = { confirmVoiceRestart = true }, onToggleMicrophone = callVm::setMuted, onBack = ::back)
                          }
                        }
                        "voice-setup" -> RealtimeSetup099Screen(voiceSetupUi,
                            onSave = voiceVm::save, onTest = voiceVm::checkCapability,
                            onRemove = voiceVm::remove, onBack = ::back)
                        "search" -> SearchScreen(feedUi, feedVm::search, ::openItem, feedVm::toggleSaved, ::openConnection, feedVm::cancelSearch)
                        "ask" -> AskScreen(feedUi, itemId, { question -> feedVm.ask(itemId, question) }, ::openConnection)
                        "daily" -> {
                            val initial = remember(page) { DailyLogic.draftForDate(state, visitDate) }
                            DailyScreen(initial, vm::saveDraft) { vm.finishVisit(it) { page = "modules" } }
                        }
                        "history" -> HistoryScreen(state)
                        "focus" -> FocusScreen(state, vm, suggestedTitle, suggestedMinutes)
                        "alarms" -> AlarmsScreen(state, vm, ::openAlarm, { checkpointReturn = "alarms"; page = "checkpoint" })
                        "alarm-edit" -> AlarmEditScreen(state, vm, alarmId, onSaved = { page = "alarms" }, onCheckpoint = { checkpointReturn = "alarm-edit"; page = "checkpoint" })
                        "checkpoint" -> CheckpointScreen(state, vm)
                    }
                }
            }
        }
    }
    }
    if (showQuickGuide) QuickGuideSheet { showQuickGuide = false; feedVm.completeQuickGuide() }
    ChatGptPlanNotice(feedUi, feedVm::acknowledgeChatGptPlan)
}

@Composable
internal fun MeBotNavigation(page: String, onPage: (String) -> Unit) {
    val items = listOf(
        Triple("feed", R.string.feed_tab, Icons.Outlined.ViewAgenda),
        Triple("modules", R.string.feed_modules_tab, Icons.Outlined.GridView),
        Triple("ai", R.string.workspace_title, Icons.Outlined.ChatBubbleOutline),
        Triple("saved", R.string.feed_saved_tab, Icons.Outlined.BookmarkBorder),
        Triple("you", R.string.feed_you_tab, Icons.Outlined.PersonOutline),
    )
    Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = 10.dp, top = 8.dp), contentAlignment = Alignment.Center) {
        Surface(modifier = Modifier.widthIn(max = 620.dp).fillMaxWidth(), shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface, shadowElevation = 5.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f))) {
            Row(Modifier.padding(horizontal = 6.dp, vertical = 7.dp)) {
                items.forEach { (route, labelId, icon) ->
                    val label = stringResource(labelId)
                    val selectedPage = page == route
                    val accessibilityLabel = if (route == "feed" && selectedPage)
                        "$label, ${stringResource(R.string.polish_feed_reselect)}" else label
                    Surface(onClick = { onPage(route) }, modifier = Modifier.weight(1f).heightIn(min = 57.dp).testTag("nav-$route").semantics {
                        selected = selectedPage; role = Role.Tab; contentDescription = accessibilityLabel
                    }, color = if (selectedPage) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(20.dp)) {
                        Column(Modifier.padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            val color = if (selectedPage) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            if (route == "ai") MeBotMark(Modifier.size(23.dp)) else Icon(icon, null, Modifier.size(21.dp), tint = color)
                            Text(label, style = MaterialTheme.typography.labelMedium, color = color, fontWeight = if (selectedPage) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}
