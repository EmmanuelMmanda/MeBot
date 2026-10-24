package app.mynguli.ui.feed

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import app.mynguli.R
import app.mynguli.core.FeedModule
import app.mynguli.core.FeedItem
import java.net.URI
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.delay

internal val LocalFeedClock = compositionLocalOf { System.currentTimeMillis() }

/** One foreground clock keeps cached ages and the local date accurate without background wakeups. */
@Composable
fun FeedClock(content: @Composable () -> Unit) {
    val owner = LocalLifecycleOwner.current
    val now by produceState(initialValue = System.currentTimeMillis(), key1 = owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) { value = System.currentTimeMillis(); delay(60_000) }
        }
    }
    CompositionLocalProvider(LocalFeedClock provides now, content = content)
}

@Composable
fun moduleName(module: FeedModule): String = module.title.ifBlank { stringResource(moduleNameResource(module.kind)) }

fun moduleNameResource(kind: String): Int = when (kind) {
    "opportunities" -> R.string.feed_module_opportunities
    "horoscope" -> R.string.feed_module_horoscope
    "watch" -> R.string.feed_module_watch
    "technology" -> R.string.module_technology
    "security" -> R.string.module_security
    "business" -> R.string.module_business
    "science" -> R.string.module_science
    "scholarships" -> R.string.module_scholarships
    "learning" -> R.string.module_learning
    "github" -> R.string.module_github
    "weather" -> R.string.module_weather
    "entertainment" -> R.string.module_entertainment
    "music" -> R.string.module_music
    "media" -> R.string.module_media
    "events" -> R.string.module_events
    "travel" -> R.string.module_travel
    else -> R.string.feed_module_news
}

fun moduleDescriptionResource(kind: String): Int = when (kind) {
    "opportunities" -> R.string.feed_module_opportunities_body
    "horoscope" -> R.string.feed_module_horoscope_body
    "watch" -> R.string.feed_module_watch_body
    "technology" -> R.string.module_technology_body
    "security" -> R.string.module_security_body
    "business" -> R.string.module_business_body
    "science" -> R.string.module_science_body
    "scholarships" -> R.string.module_scholarships_body
    "learning" -> R.string.module_learning_body
    "github" -> R.string.module_github_body
    "weather" -> R.string.module_weather_body
    "entertainment" -> R.string.module_entertainment_body
    "music" -> R.string.module_music_body
    "media" -> R.string.module_media_body
    "events" -> R.string.module_events_body
    "travel" -> R.string.module_travel_body
    else -> R.string.feed_module_news_body
}

fun moduleIcon(kind: String): ImageVector = when (kind) {
    "opportunities" -> Icons.Outlined.WorkOutline
    "horoscope" -> Icons.Outlined.NightsStay
    "watch" -> Icons.Outlined.Visibility
    "technology" -> Icons.Outlined.Memory
    "security" -> Icons.Outlined.Shield
    "business" -> Icons.Outlined.TrendingUp
    "science" -> Icons.Outlined.Science
    "scholarships" -> Icons.Outlined.School
    "learning" -> Icons.Outlined.AutoStories
    "github" -> Icons.Outlined.Code
    "weather" -> Icons.Outlined.WbSunny
    "entertainment" -> Icons.Outlined.Movie
    "music" -> Icons.Outlined.MusicNote
    "media" -> Icons.Outlined.OndemandVideo
    "events" -> Icons.Outlined.EventAvailable
    "travel" -> Icons.Outlined.Explore
    else -> Icons.Outlined.Article
}

fun moduleFocusResource(kind: String): Int = when (kind) {
    "technology" -> R.string.module_technology_focus
    "security" -> R.string.module_security_focus
    "business" -> R.string.module_business_focus
    "science" -> R.string.module_science_focus
    "opportunities" -> R.string.module_opportunities_focus
    "scholarships" -> R.string.module_scholarships_focus
    "learning" -> R.string.module_learning_focus
    "github" -> R.string.module_github_focus
    "entertainment" -> R.string.module_entertainment_focus
    "music" -> R.string.module_music_focus
    "media" -> R.string.module_media_focus
    "events" -> R.string.module_events_focus
    "travel" -> R.string.module_travel_focus
    else -> R.string.module_news_focus
}

fun feedMessageResource(code: String?): Int = when (code) {
    "chatgpt_ineligible", "chatgpt_not_eligible" -> R.string.chatgpt_error_ineligible
    "chatgpt_limit", "chatgpt_usage_limit" -> R.string.chatgpt_error_limit
    "chatgpt_unavailable", "chatgpt_refresh_wait" -> R.string.chatgpt_error_unavailable
    "chatgpt_capability", "chatgpt_unsupported" -> R.string.chatgpt_error_capability
    "chatgpt_reauth" -> R.string.chatgpt_error_reauth
    "chatgpt_permission" -> R.string.chatgpt_error_permission
    "chatgpt_policy" -> R.string.chatgpt_error_policy
    "chatgpt_incomplete" -> R.string.chatgpt_error_incomplete
    "chatgpt_interrupted" -> R.string.chatgpt_error_interrupted
    "chatgpt_models", "chatgpt_no_models" -> R.string.chatgpt_models_empty
    "chatgpt_cancelled" -> R.string.chatgpt_error_declined
    "chatgpt_timeout" -> R.string.chatgpt_error_expired
    "chatgpt_identity_invalid" -> R.string.chatgpt_error_security
    "chatgpt_invalid_response" -> R.string.edition_provider_protocol
    "region_required" -> R.string.edition_region_required
    "location_not_found" -> R.string.edition_location_missing
    "chatgpt_browser", "chatgpt_browser_unavailable" -> R.string.chatgpt_error_browser
    "chatgpt_loopback" -> R.string.chatgpt_error_listener
    "chatgpt_account" -> R.string.chatgpt_error_account
    "chatgpt_account_limit" -> R.string.chatgpt_error_account_limit
    "chatgpt_local_signout" -> R.string.chatgpt_local_signout
    "chatgpt_configuration" -> R.string.chatgpt_error_configuration
    "chatgpt_storage" -> R.string.feed_error_storage
    "chatgpt_rate_limit" -> R.string.feed_error_rate_limit
    "offline" -> R.string.feed_error_offline
    "unauthorized" -> R.string.feed_error_unauthorized
    "rate_limit" -> R.string.feed_error_rate_limit
    "quota" -> R.string.feed_error_quota
    "invalid_response" -> R.string.feed_error_invalid_response
    "storage_error" -> R.string.feed_error_storage
    "invalid_input" -> R.string.feed_error_invalid_input
    "ai_interrupted" -> R.string.polish_ai_interrupted
    "ai_incomplete" -> R.string.polish_ai_incomplete
    "conversation_limit" -> R.string.polish_conversation_limit
    "conversation_full" -> R.string.polish_conversation_full
    "attachment_type" -> R.string.polish_attachment_type
    "attachment_size" -> R.string.polish_attachment_size
    "import_format" -> R.string.polish_import_format
    "import_invalid" -> R.string.polish_import_invalid
    "file_too_large" -> R.string.polish_file_too_large
    "model_mismatch" -> R.string.polish_model_mismatch
    "partial_refresh" -> R.string.feed_partial
    "no_results" -> R.string.feed_no_results
    "limit_reached" -> R.string.feed_limit_reached
    "connected" -> R.string.feed_connected
    "imported" -> R.string.feed_imported
    "saved" -> R.string.feed_saved_notice
    else -> R.string.feed_error_provider
}

@Composable
fun relativeTime(millis: Long): String {
    val elapsed = (LocalFeedClock.current - millis).coerceAtLeast(0) / 60_000
    return when {
        millis <= 0 -> ""
        elapsed < 1 -> stringResource(R.string.feed_just_now)
        elapsed < 60 -> stringResource(R.string.feed_minutes_ago, elapsed)
        elapsed < 1440 -> stringResource(R.string.feed_hours_ago, elapsed / 60)
        else -> stringResource(R.string.feed_days_ago, elapsed / 1440)
    }
}

@Composable
fun itemTimestamp(item: FeedItem): String = when {
    item.publishedAtMillis > 0 -> relativeTime(item.publishedAtMillis)
    item.fetchedAtMillis > 0 -> stringResource(R.string.feed_retrieved, relativeTime(item.fetchedAtMillis))
    else -> stringResource(R.string.feed_time_unavailable)
}

@Composable
fun feedDate(timezone: String): String {
    val locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    val zone = runCatching { ZoneId.of(timezone) }.getOrDefault(ZoneId.systemDefault())
    return Instant.ofEpochMilli(LocalFeedClock.current).atZone(zone).format(DateTimeFormatter.ofPattern("EEEE, d MMMM", locale))
}

fun openSource(context: Context, value: String) {
    val valid = runCatching { URI(value) }.getOrNull()
    if (valid?.scheme !in setOf("https", "http") || valid?.host.isNullOrBlank() || valid?.userInfo != null) {
        Toast.makeText(context, R.string.feed_link_error, Toast.LENGTH_SHORT).show()
        return
    }
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(value)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        .onFailure { Toast.makeText(context, R.string.feed_link_error, Toast.LENGTH_SHORT).show() }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FeedStatus(code: String, onRetry: (() -> Unit)? = null, onDismiss: (() -> Unit)? = null, onConnect: (() -> Unit)? = null) {
    val context = LocalContext.current
    val planLimit = code in setOf("chatgpt_limit", "chatgpt_usage_limit")
    val needsConnection = code in setOf("chatgpt_reauth", "chatgpt_permission", "chatgpt_capability", "chatgpt_unsupported", "chatgpt_models", "chatgpt_no_models")
    val isError = code !in setOf("no_results", "partial_refresh", "limit_reached")
    Surface(color = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (planLimit) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ChatGptMark(Modifier.size(20.dp))
                Text(stringResource(R.string.chatgpt_name), style = MaterialTheme.typography.titleSmall)
            }
            Text(stringResource(feedMessageResource(code)), style = MaterialTheme.typography.bodyMedium,
                color = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface)
            if (planLimit) {
                Text(stringResource(R.string.chatgpt_usage_help), style = MaterialTheme.typography.bodySmall)
                FilledTonalButton(onClick = { openSource(context, CHATGPT_USAGE_URL) }, modifier = Modifier.testTag("chatgpt-limit-manage")) {
                    Text(stringResource(R.string.chatgpt_manage_usage))
                }
            }
            if (onRetry != null || onDismiss != null || (needsConnection && onConnect != null)) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (needsConnection && onConnect != null) TextButton(onClick = onConnect) { Text(stringResource(R.string.feed_connection)) }
                else if (!planLimit && onRetry != null) TextButton(onClick = onRetry) { Text(stringResource(R.string.feed_retry)) }
                if (onDismiss != null) TextButton(onClick = onDismiss) { Text(stringResource(R.string.feed_dismiss)) }
            }
        }
    }
}

@Composable
fun FeedSettingsRow(icon: ImageVector, title: String, subtitle: String = "", onClick: () -> Unit) {
    Surface(onClick = onClick, color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(20.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 76.dp).padding(18.dp), horizontalArrangement = Arrangement.spacedBy(15.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(23.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
