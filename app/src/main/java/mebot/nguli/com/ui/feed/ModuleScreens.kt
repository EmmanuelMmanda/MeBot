package mebot.nguli.com.ui.feed

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import mebot.nguli.com.R
import mebot.nguli.com.core.*
import mebot.nguli.com.feed.FeedUiState
import mebot.nguli.com.ui.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.URI
import java.util.Locale

@Composable
fun ModulesScreen(state: FeedState, appState: AppState, onEdit: (String?) -> Unit,
    onToggle: (String, Boolean) -> Unit, onUtility: (String) -> Unit, onUtilityToggle: (String, Boolean) -> Unit,
    error: String? = null, onDismissError: () -> Unit = {}, onReorder: (List<String>) -> Unit = {},
    initialFilter: String = ModuleDiscovery.ALL) {
    var status by rememberSaveable(initialFilter) { mutableStateOf(initialFilter.takeIf { it in setOf("all", "enabled", "disabled") } ?: ModuleDiscovery.ALL) }
    var category by rememberSaveable { mutableStateOf(ModuleDiscovery.ALL) }
    var query by rememberSaveable { mutableStateOf("") }
    val scroll = rememberScrollState()
    var viewport by remember { mutableStateOf(Rect.Zero) }
    val labels = state.modules.associate { module -> module.id to "${moduleName(module)} ${stringResource(moduleDescriptionResource(module.kind))}" }
    val filtered = remember(state.modules, status, category, query, labels) { ModuleDiscovery.filter(state.modules, status, category, query, labels) }
    val enabled = filtered.filter { it.enabled }
    val disabled = filtered.filterNot { it.enabled }
    val tools = listOf(
        LocalModule("alarms", "module-alarm", R.string.alarms, R.string.feed_module_alarm_body, Icons.Outlined.Alarm),
        LocalModule("focus", "module-focus", R.string.focus_tab, R.string.feed_module_focus_body, Icons.Outlined.Timer),
        LocalModule("daily", "module-checkin", R.string.feed_module_daily, R.string.feed_module_daily_body, Icons.Outlined.CheckCircleOutline),
        LocalModule("history", "module-history", R.string.history, R.string.feed_module_history_body, Icons.Outlined.History),
    )
    val visibleTools = tools.filter { tool ->
        val label = "${stringResource(tool.title)} ${stringResource(tool.description)}"
        category in setOf(ModuleDiscovery.ALL, "life") &&
            ModuleDiscovery.includesStatus(tool.id in appState.settings.enabledModules, status) && ModuleDiscovery.matches(query, label)
    }
    Box(Modifier.fillMaxSize().onGloballyPositioned { viewport = it.boundsInRoot() }, contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = MeBotTokens.ContentWidth).fillMaxWidth().verticalScroll(scroll).imePadding()
            .padding(horizontal = MeBotTokens.ScreenPadding, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.testTag("modules-heading")) {
                Text(stringResource(R.string.feed_modules_tab), style = MaterialTheme.typography.headlineMedium)
                Text(stringResource(R.string.modules_catalog_count, state.modules.size, state.modules.count { it.enabled }),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            error?.let { FeedStatus(it, onDismiss = onDismissError) }
            ModuleFilters083(query, status, category, filtered.size, { query = it }, { status = it }, { category = it })
            if (enabled.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SectionLabel(stringResource(R.string.modules_yours))
                    ModuleReorderList(state.modules, scroll, viewport, error, onDismissError, onReorder, onEdit, onToggle,
                        visibleIds = enabled.map { it.id }.toSet())
                }
            }
            if (disabled.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SectionLabel(stringResource(R.string.modules_explore))
                    disabled.forEach { module -> key(module.id) { ModuleRow(module, onEdit, onToggle) } }
                }
            }
            if (filtered.isEmpty() && visibleTools.isEmpty()) AppCard {
                Text(stringResource(R.string.modules_no_matches), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.modules_no_matches_help), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { query = ""; category = ModuleDiscovery.ALL; status = ModuleDiscovery.ALL },
                    modifier = Modifier.testTag("module-filters-reset")) { Text(stringResource(R.string.modules_reset_filters)) }
            }
            OutlinedButton(onClick = { onEdit(null) }, enabled = state.modules.size < ModuleCatalog.maxModules,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("module-add"), shape = RoundedCornerShape(18.dp)) {
                Icon(Icons.Outlined.Add, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.modules_custom))
            }
            if (state.modules.size >= ModuleCatalog.maxModules) SupportingText(stringResource(R.string.modules_limit))
            if (visibleTools.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionLabel(stringResource(R.string.modules_local_tools))
                visibleTools.forEach { tool -> key(tool.id) {
                    UtilityRow(tool.id, tool.tag, tool.title, tool.description, tool.icon, appState, onUtility, onUtilityToggle)
                } }
            }
        }
    }
}

private data class LocalModule(val id: String, val tag: String, val title: Int, val description: Int, val icon: ImageVector)

private fun moduleGroupResource(group: String) = when (group) {
    "growth" -> R.string.module_group_growth
    "life" -> R.string.module_group_life
    else -> R.string.module_group_signals
}

@Composable
internal fun ModuleRow(module: FeedModule, onEdit: (String?) -> Unit, onToggle: (String, Boolean) -> Unit,
    interactionEnabled: Boolean = true, modifier: Modifier = Modifier, dragging: Boolean = false,
    dragHandle: (@Composable () -> Unit)? = null) {
    val title = moduleName(module)
    val toggleLabel = stringResource(R.string.modules_toggle, title)
    Surface(modifier = modifier, color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(20.dp), shadowElevation = if (dragging) 8.dp else 0.dp) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            dragHandle?.invoke()
            Surface(onClick = { onEdit(module.id) }, enabled = interactionEnabled, color = MaterialTheme.colorScheme.surface, modifier = Modifier.weight(1f).testTag("module-${module.id}")) {
                Row(Modifier.padding(start = if (dragHandle == null) 16.dp else 0.dp, top = 16.dp, end = 6.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    val palette = MeBotPalette.current
                    val group = ModuleCatalog.preset(module.kind)?.group
                    val surface = when (group) { "growth" -> palette.mintSurface; "life" -> palette.violetSurface; else -> palette.blueSurface }
                    val ink = when (group) { "growth" -> palette.onMint; "life" -> palette.onViolet; else -> palette.onBlue }
                    FeatureIcon(moduleIcon(module.kind), ink, surface, size = if (dragHandle == null) 42.dp else 36.dp)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(title, style = MaterialTheme.typography.titleMedium)
                        Text(module.query.ifBlank { stringResource(moduleDescriptionResource(module.kind)) }, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (!module.enabled && ModuleCatalog.preset(module.kind)?.hasPublicSource != true && module.sources.isEmpty())
                            Text(stringResource(R.string.feed_requires_ai), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Switch(checked = module.enabled, enabled = interactionEnabled, onCheckedChange = { checked ->
                if (checked && module.kind == "horoscope" && module.zodiacSign.isBlank()) onEdit(module.id) else onToggle(module.id, checked)
            }, modifier = Modifier.padding(end = 12.dp).testTag("module-toggle-${module.id}").semantics { contentDescription = toggleLabel })
        }
    }
}

@Composable
private fun UtilityRow(id: String, tag: String, title: Int, description: Int, icon: ImageVector, state: AppState,
    onOpen: (String) -> Unit, onToggle: (String, Boolean) -> Unit) {
    val enabled = id in state.settings.enabledModules
    Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(20.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Surface(onClick = { onOpen(id) }, enabled = enabled, modifier = Modifier.weight(1f).testTag(tag), color = MaterialTheme.colorScheme.surface) {
                Row(Modifier.padding(19.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Icon(icon, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(if (enabled) description else R.string.feed_paused), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (id == "daily") IconButton(onClick = { onOpen(id) }) { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, stringResource(title)) }
            else Switch(checked = enabled, onCheckedChange = { onToggle(id, it) }, modifier = Modifier.padding(end = 16.dp).testTag("utility-toggle-$id"))
        }
    }
}

@Composable
fun ModuleEditor(module: FeedModule, ui: FeedUiState, isNew: Boolean, onSave: (FeedModule) -> Unit, onRemove: (String) -> Unit, onDone: () -> Unit,
    onSavePersonalized: ((FeedModule, Set<String>) -> Unit)? = null, onOpenItem: ((String) -> Unit)? = null) {
    var title by rememberSaveable(module.id) { mutableStateOf(module.title) }
    var topic by rememberSaveable(module.id) { mutableStateOf(module.query) }
    var sourceText by rememberSaveable(module.id) { mutableStateOf(module.sources.joinToString("\n") { it.url }) }
    var enabled by rememberSaveable(module.id) { mutableStateOf(isNew || module.enabled) }
    var alerts by rememberSaveable(module.id) { mutableStateOf(module.alerts) }
    var sign by rememberSaveable(module.id) { mutableStateOf(module.zodiacSign.lowercase(Locale.ROOT)) }
    var preferencesJson by rememberSaveable(module.id) { mutableStateOf(Json.encodeToString(module.preferences)) }
    val preferences = remember(preferencesJson) { Json.decodeFromString<Map<String, String>>(preferencesJson) }
    var advanced by rememberSaveable(module.id) { mutableStateOf(false) }
    var personalization by rememberSaveable(module.id) { mutableStateOf(false) }
    val availableNotes = ui.state?.context.orEmpty().filter { it.confirmed && !PersonalProfile085.isGlobal(it) }
    var selectedNotes by rememberSaveable(module.id) { mutableStateOf(availableNotes.filter { module.kind in it.scopes }.map { it.id }) }
    var localError by rememberSaveable { mutableStateOf<Int?>(null) }
    var pendingJson by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingNotes by rememberSaveable { mutableStateOf<List<String>?>(null) }
    var pendingRemoval by rememberSaveable { mutableStateOf(false) }
    var showRemove by rememberSaveable { mutableStateOf(false) }
    val saving = ui.moduleSavingId != null
    LaunchedEffect(ui.state?.modules, ui.state?.context, pendingJson, pendingNotes, pendingRemoval, ui.moduleSavingId) {
        val persisted = ui.state?.modules?.find { it.id == module.id }
        val desired = pendingJson?.let { Json.decodeFromString<FeedModule>(it) }
        val scopesSaved = pendingNotes?.let { desiredNotes -> availableNotes.filter { module.kind in it.scopes }.map { it.id }.toSet() == desiredNotes.toSet() } ?: true
        if (!saving && ui.state != null && (desired != null || pendingRemoval)) {
            val saved = (desired != null && persisted == desired && scopesSaved) || (pendingRemoval && persisted == null)
            pendingJson = null
            pendingNotes = null
            pendingRemoval = false
            if (saved) onDone()
            // A restored intent may outlive its saving ViewModel. Keep unmatched
            // drafts editable without treating an uncommitted operation as saved.
        }
    }
    LaunchedEffect(ui.error) { if (ui.error != null) { pendingJson = null; pendingNotes = null; pendingRemoval = false } }
    ScreenContainer {
        ScreenTitle(if (isNew) stringResource(R.string.feed_add_watch) else moduleName(module), stringResource(moduleDescriptionResource(module.kind)))
        val publicSource = ModuleCatalog.preset(module.kind)?.hasPublicSource == true || module.sources.isNotEmpty()
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(if (publicSource) Icons.Outlined.Public else Icons.Outlined.ChatBubbleOutline, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
            Text(stringResource(when {
                module.kind == "weather" -> R.string.modules_discovery_public
                publicSource && ui.connected -> R.string.modules_discovery_mixed
                publicSource -> R.string.modules_discovery_public
                else -> R.string.modules_discovery_ai
            }), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        val latest = ui.state?.let { state -> state.items.filter { it.moduleId == module.id && it.id !in state.hiddenIds }
            .maxByOrNull { it.publishedAtMillis.takeIf { time -> time > 0 } ?: it.fetchedAtMillis } }
        if (latest != null && onOpenItem != null) Surface(onClick = { onOpenItem(latest.id) },
            color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(18.dp), modifier = Modifier.testTag("module-latest")) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(stringResource(R.string.modules_latest), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(latest.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, Modifier.size(20.dp))
            }
        }
        if (module.kind == "watch" || module.title.isNotBlank()) OutlinedTextField(title, { if (it.length <= 80) { title = it; localError = null } }, label = { Text(stringResource(R.string.feed_module_name)) }, modifier = Modifier.fillMaxWidth().testTag("module-name"), singleLine = true, shape = RoundedCornerShape(16.dp), enabled = !saving)
        if (module.kind == "horoscope") {
            val signColumns = if (LocalConfiguration.current.screenWidthDp < 360 || LocalDensity.current.fontScale > 1.15f) 2 else 3
            SectionLabel(stringResource(R.string.feed_zodiac))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                zodiacOptions().chunked(signColumns).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { (value, label) ->
                            FilterChip(selected = sign == value, onClick = { sign = value; localError = null }, enabled = !saving,
                                label = { Text(label, maxLines = 2, style = MaterialTheme.typography.labelMedium) },
                                modifier = Modifier.weight(1f).heightIn(min = 52.dp).testTag("module-sign-$value"))
                        }
                    }
                }
            }
            if (!enabled) SupportingText(stringResource(R.string.modules_horoscope_setup))
            Text(stringResource(R.string.feed_entertainment), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else if (module.kind == "weather") {
            val weatherLocation = preferences["location"].orEmpty().trim().ifBlank { ui.state?.region.orEmpty().trim() }
            AppCard(color = MeBotPalette.current.blueSurface) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.LocationOn, null, tint = MeBotPalette.current.onBlue)
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(weatherLocation.ifBlank { stringResource(R.string.modules_weather_location_empty) }, style = MaterialTheme.typography.titleMedium, color = MeBotPalette.current.onBlue)
                        Text(stringResource(R.string.modules_weather_source_note), style = MaterialTheme.typography.bodySmall, color = MeBotPalette.current.onBlue)
                    }
                }
            }
        } else {
            OutlinedTextField(topic, { if (it.length <= 1000) { topic = it; localError = null } }, label = { Text(stringResource(R.string.feed_module_topic)) },
                placeholder = { Text(stringResource(moduleFocusResource(module.kind))) },
                supportingText = { Text(stringResource(if (module.kind == "watch") R.string.feed_module_topic_hint else R.string.modules_focus_optional)) }, minLines = 2, maxLines = 4, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().testTag("module-topic"), enabled = !saving)
        }
        AppCard {
            Text(stringResource(R.string.modules_default_focus), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(moduleDefaultResource(module.kind)), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("module-default-context"))
            if (topic.isNotBlank() && module.kind !in setOf("watch", "weather", "horoscope")) TextButton(
                onClick = { topic = ""; localError = null }, enabled = !saving,
                contentPadding = PaddingValues(0.dp), modifier = Modifier.testTag("module-default-reset")) {
                Text(stringResource(R.string.modules_use_default))
            }
        }
        ModulePreferenceFields(module.kind, preferences, !saving,
            onChange = { key, value ->
                val default = ModulePreferences.definitions(module.kind).find { it.key == key }?.defaultValue
                preferencesJson = Json.encodeToString(if (value == default || value.isBlank()) preferences - key else preferences + (key to value))
                localError = null
            }, onReset = { preferencesJson = "{}"; localError = null })
        val enabledLabel = stringResource(R.string.modules_show_feed)
        val alertsLabel = stringResource(R.string.feed_module_alerts)
        AppCard {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(enabledLabel, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Switch(enabled, { enabled = it; localError = null }, enabled = !saving,
                    modifier = Modifier.testTag("module-editor-enabled").semantics { contentDescription = enabledLabel })
            }
            if (module.kind != "horoscope") Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(alertsLabel, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Switch(alerts, { alerts = it }, enabled = !saving, modifier = Modifier.semantics { contentDescription = alertsLabel })
            }
        }
        if (onSavePersonalized != null && module.kind != "weather" && module.kind != "horoscope") {
            Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(20.dp)) {
                Column {
                    Row(Modifier.fillMaxWidth().clickable(enabled = !saving) { personalization = !personalization }.padding(18.dp).testTag("module-personalization"),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Outlined.Tune, null, tint = MaterialTheme.colorScheme.primary)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(stringResource(R.string.modules_personalization), style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(R.string.modules_notes_count, selectedNotes.count { id -> availableNotes.any { it.id == id } }), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Icon(if (personalization) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
                    }
                    if (personalization) Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(if (module.kind == "watch") R.string.modules_notes_watch else R.string.modules_notes_help), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp))
                        if (availableNotes.isEmpty()) Text(stringResource(R.string.modules_notes_empty), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(6.dp))
                        availableNotes.forEach { note ->
                            val checked = note.id in selectedNotes
                            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = checked, enabled = !saving, role = Role.Checkbox) {
                                selectedNotes = if (checked) selectedNotes - note.id else selectedNotes + note.id
                            }.padding(end = 6.dp).testTag("module-note-${note.id}"), verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked, onCheckedChange = null, enabled = !saving)
                                Text(note.text, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
        if (module.kind !in setOf("horoscope", "weather")) {
            TextButton(onClick = { advanced = !advanced }, enabled = !saving) {
                Text(stringResource(R.string.feed_advanced)); Spacer(Modifier.width(6.dp)); Icon(if (advanced) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
            }
            if (advanced) OutlinedTextField(sourceText, { if (it.length <= 12300) { sourceText = it; localError = null } }, label = { Text(stringResource(R.string.feed_module_sources)) },
                supportingText = { Text(stringResource(R.string.feed_module_sources_hint)) }, minLines = 3, maxLines = 8, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth(), enabled = !saving)
        }
        localError?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
        ui.error?.let { FeedStatus(it) }
        Button(onClick = {
            val urls = sourceText.lines().map { it.trim() }.filter { it.isNotBlank() }.distinct()
            localError = when {
                module.kind == "watch" && (title.isBlank() || topic.isBlank()) -> R.string.feed_module_name_required
                module.kind == "horoscope" && enabled && ZodiacResources.none { it.first == sign } -> R.string.feed_zodiac_required
                urls.size > 6 || urls.any { !FeedLogic.safeUrl(it, httpsOnly = true) } -> R.string.feed_module_source_invalid
                else -> null
            }
            if (localError == null) {
                val candidate = module.copy(title = title.trim(), query = topic.trim(), enabled = enabled, alerts = alerts,
                    zodiacSign = sign, preferences = preferences.mapValues { it.value.trim() }.filterValues { it.isNotBlank() },
                    sources = urls.map { url -> module.sources.find { it.url == url } ?: FeedSource(URI(url).host.removePrefix("www.").take(80), url) })
                pendingJson = Json.encodeToString(candidate)
                if (onSavePersonalized != null) {
                    val selected = selectedNotes.filter { id -> availableNotes.any { it.id == id } }.toSet()
                    pendingNotes = selected.toList()
                    onSavePersonalized(candidate, selected)
                } else onSave(candidate)
            }
        }, enabled = !saving, modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp).testTag("module-save"), shape = RoundedCornerShape(16.dp)) {
            Text(stringResource(R.string.save))
        }
        if (!isNew && module.kind == "watch") TextButton(onClick = { showRemove = true }, enabled = !saving, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.feed_module_remove), color = MaterialTheme.colorScheme.error)
        }
    }
    if (showRemove) AlertDialog(onDismissRequest = { showRemove = false }, title = { Text(stringResource(R.string.feed_module_remove_title)) }, text = { Text(stringResource(R.string.feed_module_remove_body)) },
        confirmButton = { mebot.nguli.com.security.ProtectedWindowRegistration(); TextButton(onClick = { showRemove = false; pendingRemoval = true; onRemove(module.id) }, enabled = !saving) { Text(stringResource(R.string.feed_module_remove), color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { showRemove = false }) { Text(stringResource(R.string.cancel)) } })
}

private fun moduleDefaultResource(kind: String): Int = when (kind) {
    "technology" -> R.string.modules_default_technology
    "security" -> R.string.modules_default_security
    "business" -> R.string.modules_default_business
    "science" -> R.string.modules_default_science
    "opportunities" -> R.string.modules_default_opportunities
    "scholarships" -> R.string.modules_default_scholarships
    "learning" -> R.string.modules_default_learning
    "github" -> R.string.modules_default_github
    "weather" -> R.string.modules_default_weather
    "entertainment" -> R.string.modules_default_entertainment
    "music" -> R.string.modules_default_music
    "media" -> R.string.modules_default_media
    "events" -> R.string.modules_default_events
    "travel" -> R.string.modules_default_travel
    "horoscope" -> R.string.modules_default_horoscope
    "watch" -> R.string.modules_default_watch
    else -> R.string.modules_default_news
}

@Composable
private fun zodiacOptions(): List<Pair<String, String>> = ZodiacResources.map { it.first to stringResource(it.second) }

private val ZodiacResources = listOf(
    "aries" to R.string.feed_zodiac_aries, "taurus" to R.string.feed_zodiac_taurus, "gemini" to R.string.feed_zodiac_gemini,
    "cancer" to R.string.feed_zodiac_cancer, "leo" to R.string.feed_zodiac_leo, "virgo" to R.string.feed_zodiac_virgo,
    "libra" to R.string.feed_zodiac_libra, "scorpio" to R.string.feed_zodiac_scorpio, "sagittarius" to R.string.feed_zodiac_sagittarius,
    "capricorn" to R.string.feed_zodiac_capricorn, "aquarius" to R.string.feed_zodiac_aquarius, "pisces" to R.string.feed_zodiac_pisces,
)
