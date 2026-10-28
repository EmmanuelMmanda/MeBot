package mebot.nguli.com.ui.feed

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.mapSaver
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import mebot.nguli.com.R
import mebot.nguli.com.core.FeedLogic
import mebot.nguli.com.core.AiConnection
import mebot.nguli.com.core.Setup099Logic
import mebot.nguli.com.feed.FeedUiState
import mebot.nguli.com.feed.auth.ChatGptAccount
import mebot.nguli.com.feed.auth.ChatGptModel
import mebot.nguli.com.ui.AppCard
import mebot.nguli.com.ui.ChoiceRow
import mebot.nguli.com.ui.ScreenContainer
import mebot.nguli.com.ui.ScreenTitle
import mebot.nguli.com.ui.SectionLabel

/** Public link supplied by the official SIWC UI guidelines. No session data is appended. */
const val CHATGPT_USAGE_URL = "https://chatgpt.com/settings/usage"

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ConnectionScreen(
    ui: FeedUiState,
    onConnect: (String, String, String, String) -> Unit,
    onDisconnect: () -> Unit,
    onChatGptSignIn: (String?) -> Unit,
    onChatGptCancel: () -> Unit,
    onChatGptAccount: (String) -> Unit,
    onChatGptModel: (String) -> Unit,
    onChatGptSignOut: (String) -> Unit,
    onChatGptAccountModel: (String, String) -> Unit = { id, _ -> onChatGptAccount(id) },
    onProviderConnect: ((AiConnection, String) -> Unit)? = null,
    onProviderModels: (AiConnection, String) -> Unit = { _, _ -> },
    onOpenRouterStart: () -> Unit = {},
    onOpenRouterFinish: (String, String) -> Unit = { _, _ -> },
    onOpenRouterCancel: () -> Unit = {},
    onLimitChange: ((Int) -> Unit)? = null,
    onChatGptRemoveAccount: (String) -> Unit = {},
    firstRunSetupActive: Boolean = false,
    onFirstChatGptConnectionConfirmed: (String) -> Unit = {},
    onDone: (Boolean) -> Unit,
) {
    val state = ui.state ?: return
    val context = LocalContext.current
    val active = ui.chatGpt.accounts.find { it.id == state.connection.accountId }
    val usingPlan = state.connection.mode == "chatgpt" && ui.connected
    val usingApi = state.connection.mode != "chatgpt" && ui.connected
    val chatGptPrimaryForNewUser = Setup099Logic.prefersChatGpt(state.setup099, state.onboardingComplete)
    val readyAccount = active?.takeIf { it.connected && it.planEnabled }
        ?: ui.chatGpt.accounts.firstOrNull { it.connected && it.planEnabled }
    val returningAccount = active?.id.takeIf { state.connection.mode == "chatgpt" }
        ?: ui.chatGpt.accounts.singleOrNull()?.id
    val planBusy = ui.chatGpt.phase != "idle" || ui.chatGptVerifying
    val primaryAccount = when {
        planBusy -> null
        usingPlan -> active
        chatGptPrimaryForNewUser || state.connection.mode == "chatgpt" -> readyAccount
        else -> null
    }
    var removingAccount by rememberSaveable { mutableStateOf<String?>(null) }
    var showAccounts by rememberSaveable { mutableStateOf(false) }
    var showApi by rememberSaveable(state.connection.mode) { mutableStateOf(false) }
    var replacingKey by rememberSaveable { mutableStateOf(false) }
    var connectionRequested by rememberSaveable { mutableStateOf(false) }
    var changedConnection by rememberSaveable { mutableStateOf(false) }
    // Secret drafts are intentionally absent from saved instance state and navigation state.
    var key by remember { mutableStateOf("") }
    var apiMode by rememberSaveable { mutableStateOf(state.connection.mode.takeIf { it == "service" } ?: "direct") }
    var apiModel by rememberSaveable { mutableStateOf(if (state.connection.mode == "chatgpt") AiConnection().model else state.connection.model) }
    var serviceUrl by rememberSaveable { mutableStateOf(state.connection.serviceUrl) }
    var advanced by rememberSaveable { mutableStateOf(false) }
    var localError by rememberSaveable { mutableStateOf<Int?>(null) }
    // Keep one draft per account as it moves between the main card and Accounts.
    // A visible selection must remain the selection checked after rotation.
    val selectedModels = rememberSaveable(saver = AccountModelSelectionsSaver) { mutableStateMapOf<String, String>() }
    fun selectedModel(account: ChatGptAccount): String = selectedModels[account.id]?.takeIf { slug -> account.models.any { it.slug == slug } }
        ?: account.models.find { account.id == state.connection.accountId && it.slug == state.connection.model }?.slug
        ?: account.models.firstOrNull()?.slug.orEmpty()
    fun useAccount(account: ChatGptAccount) {
        key = ""
        connectionRequested = true
        val model = selectedModel(account)
        if (model.isBlank()) onChatGptAccount(account.id) else onChatGptAccountModel(account.id, model)
    }

    LaunchedEffect(ui.chatGpt.accounts.map { Triple(it.id, it.connected, it.planEnabled) }, usingPlan, usingApi) {
        if (!usingPlan && !usingApi) showAccounts = ui.chatGpt.accounts.size > 1 ||
            (ui.chatGpt.accounts.isNotEmpty() && ui.chatGpt.accounts.none { it.connected && it.planEnabled })
    }
    LaunchedEffect(ui.chatGpt.accounts.map { it.id to it.models }) {
        selectedModels.keys.toList().forEach { id ->
            if (ui.chatGpt.accounts.find { it.id == id }?.models?.none { it.slug == selectedModels[id] } != false)
                selectedModels.remove(id)
        }
    }

    LaunchedEffect(ui.connecting, ui.connected, state.connection, ui.error, ui.notice) {
        if (!ui.connecting && ui.connected && ui.error == null && ui.notice == "connected" && connectionRequested) {
            val verifiedAccount = active?.takeIf { it.id == state.connection.accountId && it.connected }
            val verifiedModel = state.connection.mode == "chatgpt" && state.connection.accountId.isNotBlank() &&
                verifiedAccount?.models?.any { it.slug == state.connection.model } == true
            key = ""
            replacingKey = false
            connectionRequested = false
            changedConnection = true
            showApi = false
            // The verified, configured model is the success boundary. No entitlement or billing claim
            // is inferred here; persistence and one-time routing are the host's responsibility.
            if (firstRunSetupActive && verifiedModel) onFirstChatGptConnectionConfirmed(state.connection.accountId)
        }
    }

    ScreenContainer(Modifier.testTag("connection-screen")) {
        ScreenTitle(stringResource(R.string.feed_connection), stringResource(R.string.connection_subtitle_092))

        if (planBusy) {
            AppCard() {
                Text(stringResource(if (ui.chatGpt.phase == "waiting") R.string.chatgpt_waiting_title else R.string.chatgpt_verifying_title), style = MaterialTheme.typography.headlineSmall)
                Text(stringResource(if (ui.chatGpt.phase == "waiting") R.string.chatgpt_waiting_body else R.string.chatgpt_verifying_body), style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { connectionRequested = false; onChatGptCancel() }, modifier = Modifier.testTag("chatgpt-cancel")) {
                    Text(stringResource(if (ui.chatGpt.phase == "waiting") R.string.chatgpt_cancel else R.string.chatgpt_cancel_check))
                }
            }
        } else if (usingPlan && active != null) {
            AppCard() {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ChatGptMark(Modifier.size(28.dp))
                    Text(stringResource(R.string.feed_connected), style = MaterialTheme.typography.headlineSmall)
                }
                Text(active.label, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                ChatGptModelPicker(active.models, state.connection.model, !ui.connecting) {
                    connectionRequested = true
                    onChatGptModel(it)
                }
                ChatGptPlanBadge()
                Button(onClick = { onDone(changedConnection) }, enabled = !ui.connecting,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp).testTag("connection-continue"), shape = RoundedCornerShape(16.dp)) {
                    Text(stringResource(if (changedConnection) R.string.feed_refresh_mine else R.string.feed_back_feed))
                }
            }
        } else if (usingApi) {
            AppCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Outlined.CheckCircleOutline, null, tint = MaterialTheme.colorScheme.primary)
                    Text(stringResource(R.string.connection_existing_provider_099), style = MaterialTheme.typography.titleMedium)
                }
                Text("${state.connection.mode.replaceFirstChar { it.uppercase() }} · ${state.connection.model}",
                    style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.connection_existing_provider_body_099), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = { onDone(changedConnection) }, enabled = !ui.connecting,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp).testTag("connection-continue"), shape = RoundedCornerShape(16.dp)) {
                    Text(stringResource(if (changedConnection) R.string.feed_refresh_mine else R.string.feed_back_feed))
                }
                TextButton(onClick = { showApi = true }, enabled = !ui.connecting,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("connection-manage-provider-099")) {
                    Text(stringResource(R.string.connection_manage_provider_099))
                }
            }
        } else if (readyAccount != null && (chatGptPrimaryForNewUser || state.connection.mode == "chatgpt")) {
            val ready = readyAccount
            AppCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ChatGptMark(Modifier.size(28.dp))
                    Text(stringResource(R.string.edition_account_ready), style = MaterialTheme.typography.headlineSmall)
                }
                Text(ready.label, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(stringResource(R.string.edition_account_ready_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (ready.models.isNotEmpty()) ChatGptModelPicker(ready.models, selectedModel(ready), !ui.connecting,
                    tag = "chatgpt-ready-model") { selectedModels[ready.id] = it }
                Button(onClick = { useAccount(ready) }, enabled = !ui.connecting,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp).testTag("chatgpt-retry-account"), shape = RoundedCornerShape(16.dp)) {
                    Text(stringResource(R.string.edition_check_connection))
                }
            }
        } else if (!chatGptPrimaryForNewUser && state.connection.mode != "chatgpt") {
            AppCard {
                Text(stringResource(R.string.connection_existing_provider_099), style = MaterialTheme.typography.titleMedium)
                Text("${state.connection.mode.replaceFirstChar { it.uppercase() }} · ${state.connection.model}",
                    style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.connection_existing_provider_body_099), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = { showApi = true }, enabled = !ui.connecting,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp).testTag("connection-reconnect-provider-099"), shape = RoundedCornerShape(16.dp)) {
                    Text(stringResource(R.string.connection_reconnect_provider_099))
                }
            }
        } else {
            AppCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                        Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) { ChatGptMark(Modifier.size(28.dp)) }
                    }
                    Text(stringResource(R.string.chatgpt_connect_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                }
                Text(stringResource(R.string.chatgpt_connect_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                ChatGptSignInButton(enabled = !ui.connecting, onClick = {
                    key = ""
                    connectionRequested = true
                    onChatGptSignIn(returningAccount)
                })
                Text(stringResource(R.string.chatgpt_signin_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        if (!showApi || planBusy || usingPlan) ui.error?.let { FeedStatus(it) }
        if (ui.error != null) ui.chatGpt.lastDiagnostic?.let { diagnostic ->
            var showDetails by rememberSaveable { mutableStateOf(false) }
            TextButton(onClick = { showDetails = !showDetails }, modifier = Modifier.testTag("connection-diagnostics")) {
                Text(stringResource(R.string.edition_connection_details))
                Spacer(Modifier.width(6.dp)); Icon(if (showDetails) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
            }
            if (showDetails) AppCard {
                val step = stringResource(when (diagnostic.stage) {
                    "models" -> R.string.edition_stage_models
                    "sign_in" -> R.string.edition_stage_sign_in
                    "refresh" -> R.string.edition_stage_refresh
                    "inference" -> R.string.edition_stage_inference
                    else -> R.string.edition_stage_other
                })
                Text(stringResource(R.string.edition_connection_stage, step), style = MaterialTheme.typography.bodySmall)
                diagnostic.httpStatus?.let { Text(stringResource(R.string.edition_connection_status, it), style = MaterialTheme.typography.bodySmall) }
                diagnostic.requestId?.let { Text(stringResource(R.string.edition_connection_request, it), style = MaterialTheme.typography.bodySmall) }
                diagnostic.responseShape?.let { Text(stringResource(R.string.edition_connection_shape, it), style = MaterialTheme.typography.bodySmall) }
                diagnostic.responseMediaType?.let { Text(stringResource(R.string.polish_response_type, it), style = MaterialTheme.typography.bodySmall) }
                diagnostic.responseStatus?.let { Text(stringResource(R.string.polish_response_state, it), style = MaterialTheme.typography.bodySmall) }
                diagnostic.providerCode?.let { Text(stringResource(R.string.polish_provider_code, it), style = MaterialTheme.typography.bodySmall) }
                diagnostic.providerParam?.let { Text(stringResource(R.string.polish_provider_parameter, it), style = MaterialTheme.typography.bodySmall) }
            }
        }

        if (ui.chatGpt.accounts.isNotEmpty()) {
            TextButton(onClick = { showAccounts = !showAccounts }, enabled = !ui.connecting,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("chatgpt-accounts"), shape = RoundedCornerShape(20.dp)) {
                Icon(Icons.Outlined.PersonOutline, null, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.connection_accounts_092), modifier = Modifier.weight(1f))
                Spacer(Modifier.width(6.dp))
                Icon(if (showAccounts) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
            }
            if (showAccounts) {
                // The account already shown above needs management, not a second
                // model picker and connection action for the same session.
                primaryAccount?.let { account ->
                    if (account.connected) TextButton(onClick = { onChatGptSignOut(account.id) }, enabled = !ui.connecting,
                        modifier = Modifier.heightIn(min = 48.dp).testTag("chatgpt-sign-out-${account.id}")) {
                        Icon(Icons.Outlined.Logout, null, Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.chatgpt_sign_out))
                    }
                    TextButton(onClick = { removingAccount = account.id }, enabled = !ui.connecting,
                        modifier = Modifier.heightIn(min = 48.dp).testTag("chatgpt-remove-${account.id}")) {
                        Icon(Icons.Outlined.DeleteOutline, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.connection_remove_092))
                    }
                }
                ui.chatGpt.accounts.filterNot { it.id == primaryAccount?.id }.forEach { account ->
                    ChatGptAccountRow(account, usingPlan && account.id == active?.id, !ui.connecting,
                        selected = selectedModel(account), onModel = { selectedModels[account.id] = it },
                        onUse = { useAccount(account) },
                        onSignIn = { connectionRequested = true; onChatGptSignIn(account.id) },
                        onSignOut = { onChatGptSignOut(account.id) }, onRemove = { removingAccount = account.id })
                }
                OutlinedButton(onClick = { connectionRequested = true; onChatGptSignIn(null) }, enabled = !ui.connecting,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("chatgpt-add-account"), shape = RoundedCornerShape(16.dp)) {
                    Icon(Icons.Outlined.Add, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.chatgpt_add_account))
                }
                if (!usingPlan) TextButton(onClick = { openSource(context, CHATGPT_USAGE_URL) }, modifier = Modifier.testTag("chatgpt-account-usage")) {
                    Text(stringResource(R.string.chatgpt_manage_usage))
                }
            }
        }

        FilledTonalButton(onClick = { showApi = !showApi; if (!showApi) key = "" }, enabled = !ui.connecting,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("connection-api-alternative"), shape = RoundedCornerShape(20.dp)) {
            Icon(Icons.Outlined.Key, null, Modifier.size(20.dp)); Spacer(Modifier.width(10.dp))
            Text(stringResource(R.string.connection_providers_092), modifier = Modifier.weight(1f))
            Spacer(Modifier.width(6.dp))
            Icon(if (showApi) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
        }
        if (showApi) {
            if (state.connection.mode != "chatgpt") AppCard(modifier = Modifier.testTag("chatgpt-alternative-primary-099")) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ChatGptMark(Modifier.size(24.dp))
                    Text(stringResource(R.string.chatgpt_connect_title), style = MaterialTheme.typography.titleMedium)
                }
                Text(stringResource(R.string.chatgpt_connect_body), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                ChatGptSignInButton(enabled = !ui.connecting, onClick = {
                    key = ""
                    connectionRequested = true
                    onChatGptSignIn(returningAccount)
                }, tag = "chatgpt-sign-in-alternative-099")
            }
            if (usingApi && !replacingKey) {
                AppCard {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.Outlined.CheckCircleOutline, null, tint = MaterialTheme.colorScheme.primary)
                        Text(stringResource(R.string.chatgpt_api_active), style = MaterialTheme.typography.titleMedium)
                    }
                    Text(state.connection.mode.replaceFirstChar { it.uppercase() } + " · " + state.connection.model, style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = { onDone(changedConnection) }, enabled = !ui.connecting,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp).testTag("connection-continue"), shape = RoundedCornerShape(16.dp)) {
                        Text(stringResource(if (changedConnection) R.string.feed_refresh_mine else R.string.feed_back_feed))
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { replacingKey = true }, enabled = !ui.connecting) { Text(stringResource(R.string.feed_change_key)) }
                        TextButton(onClick = { key = ""; onDisconnect() }, enabled = !ui.connecting) { Text(stringResource(R.string.feed_disconnect)) }
                    }
                }
                ui.error?.let { FeedStatus(it) }
            } else {
                ProviderConnectionCard(ui,
                    onConnect = { connection, secret ->
                        connectionRequested = true
                        if (onProviderConnect != null) onProviderConnect(connection, secret)
                        else onConnect(secret, connection.model, connection.mode, connection.serviceUrl)
                    }, onModels = onProviderModels, onOpenRouterStart = { connectionRequested = true; onOpenRouterStart() },
                    onOpenRouterFinish = onOpenRouterFinish, onOpenRouterCancel = onOpenRouterCancel)
                if (!usingPlan && !planBusy) ui.error?.let { FeedStatus(it) }

            }
        }
        onLimitChange?.let { RequestAllowanceCard(state, !ui.connecting, it) }
        Text(stringResource(R.string.provider_memory_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    removingAccount?.let { id ->
        val account = ui.chatGpt.accounts.find { it.id == id }
        if (account != null) AlertDialog(onDismissRequest = { removingAccount = null },
            title = { Text(stringResource(R.string.connection_remove_title_092)) },
            text = { Text(stringResource(R.string.connection_remove_body_092, account.label)) },
            confirmButton = { mebot.nguli.com.security.ProtectedWindowRegistration()
                TextButton(onClick = { removingAccount = null; onChatGptRemoveAccount(id) }, enabled = !ui.connecting,
                    modifier = Modifier.testTag("chatgpt-remove-confirm")) { Text(stringResource(R.string.connection_remove_confirm_092)) }
            }, dismissButton = { TextButton(onClick = { removingAccount = null }) { Text(stringResource(R.string.cancel)) } })
    }
}

@Composable
private fun ChatGptSignInButton(enabled: Boolean, onClick: () -> Unit, tag: String = "chatgpt-sign-in") {
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    Button(onClick = onClick, enabled = enabled,
        colors = ButtonDefaults.buttonColors(containerColor = if (dark) Color.White else Color.Black, contentColor = if (dark) Color.Black else Color.White),
        modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp).testTag(tag), shape = RoundedCornerShape(16.dp)) {
        Icon(painterResource(R.drawable.ic_chatgpt), null, Modifier.size(24.dp))
        Spacer(Modifier.width(10.dp))
        Text(stringResource(R.string.chatgpt_continue))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChatGptAccountRow(account: ChatGptAccount, active: Boolean, enabled: Boolean, selected: String,
    onModel: (String) -> Unit, onUse: () -> Unit, onSignIn: () -> Unit, onSignOut: () -> Unit, onRemove: () -> Unit) {
    AppCard {
        Text(account.label, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(stringResource(when {
            active -> R.string.chatgpt_active_account
            !account.connected -> R.string.chatgpt_signed_out
            !account.planEnabled -> R.string.chatgpt_plan_off
            else -> R.string.chatgpt_plan_ready
        }), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!account.connected || !account.planEnabled) ChatGptSignInButton(enabled, onSignIn, "chatgpt-sign-in-${account.id}")
        if (!active && account.connected && account.planEnabled && account.models.isNotEmpty())
            ChatGptModelPicker(account.models, selected, enabled, tag = "chatgpt-model-picker-" + account.id, onChoose = onModel)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!active && account.connected && account.planEnabled) FilledTonalButton(onClick = onUse, enabled = enabled,
                modifier = Modifier.testTag("chatgpt-use-${account.id}")) {
                Text(stringResource(if (account.models.isEmpty()) R.string.edition_check_connection else R.string.chatgpt_use_account))
            }
            if (account.connected) TextButton(onClick = onSignOut, enabled = enabled, modifier = Modifier.testTag("chatgpt-sign-out-${account.id}")) {
                Text(stringResource(R.string.chatgpt_sign_out))
            }
            TextButton(onClick = onRemove, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp).testTag("chatgpt-remove-${account.id}")) {
                Text(stringResource(R.string.connection_remove_092))
            }
        }
    }
}

private val AccountModelSelectionsSaver = mapSaver<SnapshotStateMap<String, String>>(
    save = { it.toMap() },
    restore = { values -> mutableStateMapOf<String, String>().apply {
        values.forEach { (id, slug) -> if (slug is String) put(id, slug) }
    } },
)

@Composable
private fun ChatGptModelPicker(models: List<ChatGptModel>, selected: String, enabled: Boolean, tag: String = "chatgpt-model-picker", onChoose: (String) -> Unit) {
    var choosing by rememberSaveable { mutableStateOf(false) }
    OutlinedButton(onClick = { choosing = true }, enabled = enabled && models.isNotEmpty(),
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(tag), shape = RoundedCornerShape(14.dp)) {
        Text(models.find { it.slug == selected }?.displayName ?: selected, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.width(8.dp)); Icon(Icons.Outlined.ExpandMore, null)
    }
    if (models.isEmpty()) Text(stringResource(R.string.chatgpt_models_empty), style = MaterialTheme.typography.bodySmall)
    if (choosing) AlertDialog(
        onDismissRequest = { choosing = false },
        title = { Text(stringResource(R.string.chatgpt_choose_model)) },
        text = {
            Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                models.forEach { model ->
                    Surface(onClick = { choosing = false; if (model.slug != selected) onChoose(model.slug) }, shape = RoundedCornerShape(12.dp), color = Color.Transparent) {
                        Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = model.slug == selected, onClick = null, modifier = Modifier.padding(10.dp))
                            Text(model.displayName, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).padding(end = 12.dp))
                        }
                    }
                }
            }
        },
        confirmButton = { mebot.nguli.com.security.ProtectedWindowRegistration(); TextButton(onClick = { choosing = false }) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
fun ChatGptPlanBadge(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(0.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            ChatGptMark(Modifier.size(20.dp))
            Text(stringResource(R.string.chatgpt_using_plan), style = MaterialTheme.typography.labelMedium)
        }
        TextButton(onClick = { openSource(context, CHATGPT_USAGE_URL) }, contentPadding = PaddingValues(horizontal = 0.dp),
            modifier = Modifier.testTag("chatgpt-manage-usage")) { Text(stringResource(R.string.chatgpt_manage_usage)) }
    }
}

@Composable
fun ChatGptPlanNotice(ui: FeedUiState, onAcknowledge: () -> Unit) {
    val active = ui.chatGpt.accounts.find { it.id == ui.state?.connection?.accountId }
    if (ui.connected && ui.state?.connection?.mode == "chatgpt" && active?.planEnabled == true && !active.planNoticeAcknowledged) {
        AlertDialog(onDismissRequest = onAcknowledge,
            icon = { ChatGptMark(Modifier.size(32.dp)) },
            title = { Text(stringResource(R.string.chatgpt_welcome_title)) },
            text = { Text(stringResource(R.string.chatgpt_welcome_body)) },
            confirmButton = { mebot.nguli.com.security.ProtectedWindowRegistration(); TextButton(onClick = onAcknowledge, modifier = Modifier.testTag("chatgpt-plan-got-it")) { Text(stringResource(R.string.chatgpt_got_it)) } })
    }
}

@Composable
internal fun ChatGptMark(modifier: Modifier = Modifier) {
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    Icon(painterResource(R.drawable.ic_chatgpt), null, modifier, tint = if (dark) Color.White else Color.Black)
}
