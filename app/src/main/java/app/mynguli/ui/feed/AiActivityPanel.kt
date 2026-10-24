package app.mynguli.ui.feed

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.mynguli.feed.AiActivity
import app.mynguli.ui.MeBotMark
import kotlinx.coroutines.delay

@Composable
internal fun AiActivityPanel(activity: AiActivity?, modifier: Modifier = Modifier) {
    if (activity == null) return
    val sw = LocalConfiguration.current.locales[0].language == "sw"
    var expanded by rememberSaveable(activity.id) { mutableStateOf(false) }
    var seconds by remember(activity.id) { mutableLongStateOf(0) }
    LaunchedEffect(activity.id) { while (true) { seconds = ((System.currentTimeMillis()-activity.startedAt)/1000).coerceAtLeast(0); delay(1000) } }
    val label = when(activity.stage) {
        "queued" -> if(sw) "Ombi lako linasubiri" else "Your request is queued"
        "connecting" -> if(sw) "Inaunganisha AI" else "Connecting to AI"
        "searching" -> if(sw) "Inatafuta taarifa" else "Searching sources"
        "reading" -> if(sw) "Inasoma taarifa zilizopatikana" else "Reviewing search results"
        "thinking" -> if(sw) "Inaandaa jibu" else "Preparing an answer"
        "writing" -> if(sw) "Inaandika jibu" else "Writing the response"
        else -> if(sw) "MeBot inashughulikia ombi lako" else "MeBot is working on your request"
    }
    Surface(onClick = { expanded = !expanded }, shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = modifier.fillMaxWidth().testTag("ai-activity-panel")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(38.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.fillMaxSize(), strokeWidth = 2.dp)
                    if (activity.stage == "connecting" || activity.stage == "queued") MeBotMark(Modifier.size(23.dp))
                    else Icon(when { activity.source == "youtube" -> Icons.Outlined.SmartDisplay
                        activity.kind == "horoscope" -> Icons.Outlined.AutoAwesome
                        activity.kind in setOf("music", "media", "entertainment") -> Icons.Outlined.PlayCircleOutline
                        activity.kind == "weather" -> Icons.Outlined.CloudQueue
                        else -> Icons.Outlined.MenuBook }, null, Modifier.size(22.dp), tint=MaterialTheme.colorScheme.primary)
                }
                Column(Modifier.weight(1f)) {
                    Text(label, style = MaterialTheme.typography.labelLarge, modifier=Modifier.semantics { liveRegion=LiveRegionMode.Polite })
                    Text(if(sw) "Sekunde $seconds · Gusa kuona zaidi" else "${seconds}s · Tap for details", style=MaterialTheme.typography.labelSmall, color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(if(expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, if(sw) "Maelezo" else "Details", Modifier.size(20.dp))
            }
            AnimatedVisibility(expanded) {
                Text(activity.summary.ifBlank { if(sw) "Hali hii inatokana na ombi linaloendelea. Jibu litaonekana hapa likipatikana." else "This status follows the active request. The answer appears as it arrives." },
                    style=MaterialTheme.typography.bodySmall, color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
