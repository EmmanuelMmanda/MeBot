package mebot.nguli.com.ui.feed

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import mebot.nguli.com.R
import mebot.nguli.com.core.FeedState
import mebot.nguli.com.core.PersonalProfile085
import mebot.nguli.com.ui.MeBotMark
import mebot.nguli.com.ui.MeBotPalette

private data class TourStep(val title: Int, val body: Int)
private val tourSteps = listOf(
    TourStep(R.string.tour_feed_title, R.string.tour_feed_body),
    TourStep(R.string.tour_modules_title, R.string.tour_modules_body),
    TourStep(R.string.tour_explain_title, R.string.tour_explain_body),
    TourStep(R.string.tour_search_title, R.string.tour_search_body),
    TourStep(R.string.tour_tools_title, R.string.tour_tools_body),
    TourStep(R.string.tour_context_title, R.string.tour_context_body),
)

/** A short, optional tour. Browsing the steps never changes the saved profile. */
@Composable
internal fun MeBotOnboarding(state: FeedState, language: String, onLanguage: (String) -> Unit,
    onStart: () -> Unit, onConnect: () -> Unit,
    onPreferences: (Set<String>, String, String, (Boolean) -> Unit) -> Unit = { _, _, _, done -> done(true) }) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    var selectedKinds by rememberSaveable { mutableStateOf(ArrayList(state.modules.filter { it.enabled }.map { it.kind })) }
    var selectedRegion by rememberSaveable { mutableStateOf(state.region) }
    var selectedInterests by rememberSaveable { mutableStateOf(state.context.find { it.id == "onboarding-interests" }?.text.orEmpty()) }
    var saving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf(false) }
    fun finish(next: () -> Unit) {
        if(saving || !PersonalProfile085.valid(selectedInterests)) return
        if(state.onboardingComplete) { next(); return }
        saving = true; saveError=false
        onPreferences(selectedKinds.toSet(), selectedRegion, selectedInterests) { success -> saving=false;if(success)next() else saveError=true }
    }
    val usesSwahili = language == "sw" || (language == "system" && LocalConfiguration.current.locales[0].language == "sw")
    val contentScroll = rememberScrollState()
    BackHandler(step > 0 || saving) { if (!saving) step-- }
    LaunchedEffect(step) { contentScroll.scrollTo(0) }
    Box(Modifier.fillMaxSize().testTag("onboarding-screen"), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 620.dp).fillMaxSize().safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MeBotMark(Modifier.size(30.dp))
                    Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge)
                }
                TextButton(onClick = { onLanguage(if (usesSwahili) "en" else "sw") }, enabled = !saving, modifier = Modifier.testTag("onboarding-language")) {
                    Icon(Icons.Outlined.Language, null, Modifier.size(18.dp)); Spacer(Modifier.width(7.dp))
                    Text(stringResource(if (usesSwahili) R.string.english else R.string.swahili))
                }
            }
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val viewport = maxHeight
                Column(Modifier.fillMaxWidth().verticalScroll(contentScroll).heightIn(min = viewport)
                    .padding(horizontal = 24.dp, vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center) {
                    Text(stringResource(R.string.tour_step, step + 1, tourSteps.size), style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary, modifier = Modifier.testTag("onboarding-progress").semantics { liveRegion = LiveRegionMode.Polite })
                    Spacer(Modifier.height(12.dp))
                    Crossfade(step, animationSpec = tween(180), label = "tour-step") { active ->
                        Column(Modifier.fillMaxWidth().testTag("onboarding-step-$active"), horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(18.dp)) {
                            if (active == 0 && state.name.isNotBlank()) Text(stringResource(R.string.feed_welcome_personal, state.name),
                                style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(stringResource(tourSteps[active].title), style = MaterialTheme.typography.headlineLarge,
                                textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 420.dp).semantics { heading() })
                            Text(stringResource(tourSteps[active].body), style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                                modifier = Modifier.widthIn(max = 420.dp))
                            if(saveError) Text(stringResource(R.string.interests_save_error), color=MaterialTheme.colorScheme.error)
                            if(!state.onboardingComplete && active == 1) {
                                InterestPicker083(selectedKinds, !saving) { selectedKinds = ArrayList(it) }
                                PersonalProfileEditor085(selectedInterests, !saving, { selectedInterests = it }, "onboarding-preferences", onboarding = true)
                            } else if(!state.onboardingComplete && active == 5) {
                                Column(Modifier.fillMaxWidth(), verticalArrangement=Arrangement.spacedBy(12.dp)) {
                                    OutlinedTextField(selectedRegion,{selectedRegion=it.take(100)},singleLine=true,enabled = !saving,
                                        label={Text(stringResource(R.string.interests_region))},modifier=Modifier.fillMaxWidth().testTag("onboarding-region"),shape=RoundedCornerShape(18.dp))
                                    Text(stringResource(R.string.interests_first_feed),style=MaterialTheme.typography.bodySmall)
                                }
                            } else TourPreview(active)
                        }
                    }
                    Spacer(Modifier.height(22.dp))
                    LinearProgressIndicator(progress = { (step + 1f) / tourSteps.size }, modifier = Modifier.width(112.dp).height(3.dp),
                        trackColor = MaterialTheme.colorScheme.outlineVariant)
                }
            }
            Surface(color = MaterialTheme.colorScheme.background) {
                Column(Modifier.fillMaxWidth().heightIn(max = 210.dp).verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (step > 0) OutlinedButton(onClick = { step-- }, enabled = !saving, modifier = Modifier.heightIn(min = 52.dp).testTag("onboarding-back"),
                            shape = RoundedCornerShape(18.dp)) { Text(stringResource(R.string.back)) }
                        Button(onClick = { if (step == tourSteps.lastIndex) finish(onConnect) else step++ }, enabled = !saving && PersonalProfile085.valid(selectedInterests),
                            modifier = Modifier.weight(1f).heightIn(min = 54.dp).testTag(if (step == tourSteps.lastIndex) "onboarding-finish" else "onboarding-next"),
                            shape = RoundedCornerShape(18.dp)) {
                            Text(stringResource(if (saving) R.string.delivery_saving else if (step == tourSteps.lastIndex) R.string.tour_connect else R.string.tour_next))
                            Spacer(Modifier.width(9.dp)); Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, Modifier.size(18.dp))
                        }
                    }
                    TextButton(onClick = { finish(onStart) }, enabled = !saving && PersonalProfile085.valid(selectedInterests), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("onboarding-start")) {
                        Text(stringResource(if (step == tourSteps.lastIndex) R.string.tour_open else R.string.tour_skip))
                    }
                }
            }
        }
    }
}

@Composable
private fun TourPreview(step: Int) {
    Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.widthIn(max = 360.dp).fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(when (step) {
                    0 -> R.string.tour_preview_today; 1 -> R.string.feed_modules_tab; 2 -> R.string.tour_preview_explanation
                    3 -> R.string.search_title; 4 -> R.string.tools_step_preview_title; else -> R.string.feed_context
                }), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))

            }
            when (step) {
                0 -> {
                    EditorialPhoto(dar = true, height = 122.dp)
                    Text(stringResource(R.string.photo_near_far), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.photo_near_far_body), style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Outlined.Newspaper, null, Modifier.size(20.dp))
                        Icon(Icons.Outlined.School, null, Modifier.size(20.dp))
                        Icon(Icons.Outlined.WorkOutline, null, Modifier.size(20.dp))
                        Icon(Icons.Outlined.MusicNote, null, Modifier.size(20.dp))
                    }
                }
                1 -> {
                    val examples = listOf("news", "technology", "learning", "events", "weather", "music")
                    examples.chunked(2).forEach { pair -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        pair.forEach { kind -> Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(16.dp), modifier = Modifier.weight(1f)) {
                            Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(moduleIcon(kind), null, Modifier.size(23.dp), tint = MaterialTheme.colorScheme.primary)
                                Text(stringResource(moduleNameResource(kind)), style = MaterialTheme.typography.labelLarge)
                            }
                        } }
                    } }
                }
                2 -> {
                    EditorialPhoto(dar = false, height = 112.dp)
                    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(18.dp)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(R.string.photo_explore_body), style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer)
                            Text(stringResource(R.string.photo_explore_fact), style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                    }
                    PreviewRow(Icons.Outlined.Link, R.string.tour_preview_sources, R.string.tour_preview_sources_note)
                    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(16.dp)) {
                        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Icon(Icons.Outlined.ChatBubbleOutline, null, Modifier.size(19.dp), tint = MaterialTheme.colorScheme.primary)
                            Text(stringResource(R.string.tour_preview_ask), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                3 -> {
                    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(16.dp)) {
                        Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Search, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
                            Text(stringResource(R.string.tour_preview_query), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    PreviewRow(Icons.Outlined.BookmarkBorder, R.string.tour_preview_save, R.string.tour_preview_save_note)
                    PreviewRow(Icons.Outlined.AttachFile, R.string.tour_preview_chat, R.string.tour_preview_chat_note)
                    Text(stringResource(R.string.tour_preview_voice), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                4 -> {
                    PreviewRow(Icons.Outlined.Alarm, R.string.quick_tools_alarm, R.string.quick_tools_alarm_body)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    PreviewRow(Icons.Outlined.Timer, R.string.quick_tools_focus, R.string.quick_tools_focus_body)
                    PreviewRow(Icons.Outlined.CheckCircleOutline, R.string.quick_tools_checkin, R.string.quick_tools_checkin_body)
                }
                else -> {
                    PreviewRow(Icons.Outlined.PersonOutline, R.string.tour_preview_preferences, R.string.tour_preview_preferences_note)
                    PreviewRow(Icons.Outlined.ChatBubbleOutline, R.string.tour_preview_connection, R.string.tour_preview_connection_note)
                    PreviewRow(Icons.Outlined.Tune, R.string.tour_preview_control, R.string.tour_preview_control_note)
                }
            }
        }
    }
}

@Composable
private fun PreviewRow(icon: ImageVector, title: Int, body: Int) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = CircleShape) {
            Box(Modifier.size(38.dp), contentAlignment = Alignment.Center) {
                Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
