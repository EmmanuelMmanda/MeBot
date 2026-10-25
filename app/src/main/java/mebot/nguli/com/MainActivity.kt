package mebot.nguli.com

import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.view.ContextThemeWrapper
import androidx.activity.ComponentActivity
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
import mebot.nguli.com.feed.FeedViewModel
import mebot.nguli.com.feed.FeedUiState
import mebot.nguli.com.feed.auth.ChatGptReturnLink
import androidx.compose.ui.platform.testTag
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.ZonedDateTime
import java.time.LocalDate
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.awaitCancellation

class MainActivity : ComponentActivity() {
    private var softwareNavigationRequest by mutableIntStateOf(0)
    private var alarmNavigationRequest by mutableIntStateOf(0)
    private var feedNavigationRequest by mutableIntStateOf(0)
    private var feedNavigationItem by mutableStateOf<String?>(null)
    private var chatGptNavigationRequest by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
            val feedVm: FeedViewModel = viewModel(factory = remember(application) {
                ViewModelProvider.AndroidViewModelFactory(application)
            })
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
                    FeedClock { MeBotApp(ui, vm, feedUi, feedVm, alarmNavigationRequest, feedNavigationRequest, feedNavigationItem, chatGptNavigationRequest, softwareNavigationRequest) }
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
    alarmNavigationRequest: Int, feedNavigationRequest: Int, feedNavigationItem: String?, chatGptNavigationRequest: Int, softwareNavigationRequest: Int) {
    var page by rememberSaveable { mutableStateOf("feed") }
    var showQuickGuide by rememberSaveable { mutableStateOf(false) }
    var consumedSoftwareNavigation by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(softwareNavigationRequest) { if(softwareNavigationRequest > consumedSoftwareNavigation) { consumedSoftwareNavigation=softwareNavigationRequest; page="software-updates" } }
    var consumedAlarmNavigation by rememberSaveable { mutableIntStateOf(0) }
    var consumedFeedNavigation by rememberSaveable { mutableIntStateOf(0) }
    var consumedChatGptNavigation by rememberSaveable { mutableIntStateOf(0) }
    var alarmId by rememberSaveable { mutableStateOf<String?>(null) }
    var itemId by rememberSaveable { mutableStateOf<String?>(null) }
    var detailReturn by rememberSaveable { mutableStateOf("feed") }
    var connectionReturn by rememberSaveable { mutableStateOf("you") }
    var contextReturn by rememberSaveable { mutableStateOf("you") }
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
    val snackbars = remember { SnackbarHostState() }
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
    // The completed tour leads straight to the feed. The quick guide stays available in Help.
    LaunchedEffect(page, onboarded, owner) {
        if (page == "feed" && onboarded) owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            feedVm.refreshIfStale()
            awaitCancellation()
        }
    }
    val basePages = setOf("feed", "modules", "ai", "saved", "you")
    fun openItem(id: String) {
        if (page != "item") detailReturn = when (page) { "saved", "search", "module-edit" -> page; else -> "feed" }
        itemId = id; feedVm.clearAnswer(); feedVm.markViewed(id); page = "item"
    }
    fun openModule(id: String?) {
        val selected = feed?.modules?.find { it.id == id } ?: FeedModule()
        moduleDraft = Json.encodeToString(selected); moduleIsNew = id == null
        feedVm.clearMessage(); screenStates.removeState("module-edit"); page = "module-edit"
    }
    // Restore an obsolete utility destination safely after the 0.8.5 upgrade.
    LaunchedEffect(page) { if (page == "compare_prices") page = "modules" }
    LaunchedEffect(page, itemId) { if (page != "item") feedVm.cancelDetail() }
    fun openConnection() { connectionReturn = page; feedVm.clearMessage(); screenStates.removeState("connect"); page = "connect" }
    fun openModuleCatalog(filter: String) {
        if (moduleCatalogFilter != filter) screenStates.removeState("modules")
        moduleCatalogFilter = filter
        page = "modules"
    }
    fun back() {
        if (page == "connect") { feedVm.cancelChatGptSignIn(); feedVm.cancelOpenRouterSignIn() }
        page = when (page) {
            "checkpoint" -> checkpointReturn
            "alarm-edit" -> "alarms"
            "alarms", "focus", "history", "daily", "module-edit" -> "modules"
            "item" -> detailReturn
            "ask" -> if (itemId != null) "item" else "feed"
            "connect" -> if (onboarded) connectionReturn else "feed"
            "context" -> contextReturn
            "delivery", "data", "settings", "software-updates" -> "you"
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
        if (state?.profile?.onboardingComplete != true) vm.finishOnboarding { }
        if (!onboarded) feedVm.finishOnboarding()
        page = "feed"
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
    CompositionLocalProvider(LocalRelevanceContext provides RelevanceUiContext(feedUi.state, feedVm::rateRelevance, feedVm::toggleStar, feedVm::configureStar, feedVm::setSavedCollection)) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
          Column(Modifier.statusBarsPadding()) {
            ProcessingStatus(feedUi, page, itemId, Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
            if ((onboarded && ((page !in basePages && page != "tour") || activeAlarm != null)) || (!onboarded && page == "connect")) {
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
                !onboarded && page != "connect" -> FeedOnboarding(feed, state.settings.language,
                    { language -> vm.saveSettings { it.copy(language = language) } }, ::finishSetup, ::openConnection,
                    onPreferences = { kinds, region, interests, done -> feedVm.configureOnboarding(kinds, region, interests, done) })
                else -> screenStates.SaveableStateProvider(if (page == "daily") "daily:$visitDate" else page) {
                    when (page) {
                        "feed" -> {
                            val now = ZonedDateTime.now()
                            val nextAlarm = if ("alarms" in state.settings.enabledModules) state.alarms.filter { it.enabled }.minByOrNull { nextAlarmOccurrence(it, now).toInstant() } else null
                            FeedHome(feedUi, ::openItem, { openModuleCatalog(ModuleDiscovery.ALL) }, { page = "you" }, ::openConnection,
                                { feedVm.clearMessage(); page = "ai" }, feedVm::refresh, feedVm::toggleSaved,
                                feedVm::hideItem, feedVm::clearMessage, nextAlarm, { page = "alarms" },
                                onPrompt = { id, prompt ->
                                    feedVm.openConversation(id, prompt); page = "ai"
                                }, onSearch = { page = "search" }, returnToTopRequest = feedTopRequest, onRefreshModule = feedVm::refreshModule,
                                onExploreModules = { openModuleCatalog(ModuleDiscovery.DISABLED) },
                                onContext = { contextReturn = "feed"; page = "context" }, onDismissNotice = feedVm::dismissSetupNotice,
                                onLoadMore = feedVm::loadMore)
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
                            { feedVm.clearMessage(); screenStates.removeState("delivery"); page = "delivery" }, { page = "settings" }, { page = "data" }, onSoftwareUpdates={page="software-updates"})
                        "software-updates" -> SoftwareUpdatesScreen((LocalContext.current.applicationContext as MeBotApplication).softwareUpdates)
                        "connect" -> ConnectionScreen(feedUi, feedVm::connect, feedVm::disconnect,
                            feedVm::beginChatGptSignIn, feedVm::cancelChatGptSignIn, feedVm::selectChatGptAccount,
                            feedVm::selectChatGptModel, feedVm::disconnectChatGptAccount, feedVm::useChatGptAccount,
                            onProviderConnect=feedVm::connectProvider, onProviderModels=feedVm::loadProviderModels,
                            onOpenRouterStart=feedVm::beginOpenRouterSignIn, onOpenRouterFinish=feedVm::finishOpenRouterSignIn,
                            onOpenRouterCancel=feedVm::cancelOpenRouterSignIn, onLimitChange=feedVm::saveRequestLimit) { refresh ->
                            if (onboarded && refresh) feedVm.refresh()
                            if (onboarded) page = connectionReturn else finishSetup()
                        }
                        "context" -> ContextScreen(feedUi, feedVm) { page = contextReturn }
                        "delivery" -> DeliveryScreen(feedUi, feedVm, { page = "you" }, ::openConnection)
                        "data" -> FeedDataScreen(feedUi, feedVm) { page = "settings" }
                        "settings" -> SettingsScreen(state, vm, onErase = { feedVm.reset(); vm.erase(); page = "feed" }, onHelp = { page = "faq" })
                        "faq" -> FaqScreen(
                            onContext = { contextReturn = "faq"; feedVm.clearMessage(); page = "context" },
                            onConnect = ::openConnection,
                            onTour = { showQuickGuide = true },
                        )
                        "tour" -> FeedOnboarding(feed, state.settings.language,
                            { language -> vm.saveSettings { it.copy(language = language) } }, { page = "faq" },
                            { connectionReturn = "faq"; feedVm.clearMessage(); screenStates.removeState("connect"); page = "connect" })
                        "item" -> FeedDetail(feedUi, itemId, feedVm::toggleSaved,
                            { feedVm.openConversation(itemId); page = "ai" }, { page = detailReturn },
                            onPrompt = { prompt -> itemId?.let { feedVm.followUpDetail(it, prompt) } },
                            onItem = ::openItem, onModule = ::openModule, onHide = feedVm::hideItem,
                            onLoadBrief = { itemId?.let { feedVm.loadDetail(it) } }, onCancelBrief = feedVm::cancelDetail,
                            onRetryBrief = { itemId?.let { feedVm.retryDetail(it) } },
                            onFollowUp = { prompt -> itemId?.let { feedVm.followUpDetail(it, prompt) } }, onConnect = ::openConnection)
                        "ai" -> AiWorkspace(feedUi, feedVm::newConversation, feedVm::selectConversation, feedVm::deleteConversation,
                            feedVm::sendConversation, feedVm::clearAnswer, ::openConnection,
                            { contextReturn = "ai"; feedVm.clearMessage(); page = "context" },
                            feedVm::importTextAttachment, feedVm::removeAttachment, onSelectModel = feedVm::switchModel, onDismissNotice = feedVm::dismissSetupNotice, onSpeechPreferences = feedVm::updateSpeechPreferences)
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
