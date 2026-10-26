package mebot.nguli.com.ui.feed

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import mebot.nguli.com.R
import mebot.nguli.com.core.TodayEntry
import mebot.nguli.com.core.TodayReason
import java.time.LocalDate
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
internal fun TodayReasonLabel090(entry: TodayEntry, timezone: String) {
    val locale = LocalConfiguration.current.locales[0]
    val zone = runCatching { ZoneId.of(timezone) }.getOrElse { ZoneId.systemDefault() }
    val formatter = remember(locale, zone) { DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(locale).withZone(zone) }
    fun format(millis: Long): String = formatter.format(Instant.ofEpochMilli(millis))
    var showReason by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(when(entry.reason) {
            TodayReason.PUBLISHED -> R.string.today_090_published
            TodayReason.UPDATED -> R.string.today_090_updated
            TodayReason.FORECAST -> R.string.today_090_forecast
            TodayReason.HAPPENING -> R.string.today_090_happening
            TodayReason.DUE -> R.string.today_090_due
        }), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
        IconButton(onClick = { showReason = true }, modifier = Modifier.testTag("today-why-${entry.item.id}")) {
            Icon(Icons.Outlined.Info, stringResource(R.string.today_090_why), Modifier.size(20.dp))
        }
    }
    val temporal = entry.item.temporal
    val validFrom = temporal?.validFromMillis
    val validUntil = temporal?.validUntilMillis
    if (entry.reason == TodayReason.FORECAST && validFrom != null && validUntil != null) {
        Text(stringResource(R.string.today_090_validity, format(validFrom), format(validUntil), relativeTime(temporal?.verifiedAtMillis ?: 0)),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (entry.reason == TodayReason.HAPPENING || entry.reason == TodayReason.DUE) {
        val sourceDate = temporal?.relevanceDate?.let { raw -> runCatching {
            LocalDate.parse(raw).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))
        }.getOrNull() }
        if (sourceDate != null) Text(stringResource(R.string.today_090_source_date, sourceDate, temporal?.sourceTimezone.orEmpty()),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if(showReason) AlertDialog(onDismissRequest = { showReason = false },
        title = { Text(stringResource(R.string.today_090_why)) },
        text = { Text(stringResource(R.string.today_090_why_body)) },
        confirmButton = { mebot.nguli.com.security.ProtectedWindowRegistration(); TextButton(onClick = { showReason = false }) { Text(stringResource(android.R.string.ok)) } })
}

/** Never present a legacy/AI-guessed publication timestamp as Today source evidence. */
@Composable
internal fun todayPublication090(entry: TodayEntry, timezone: String): String {
    val published = entry.item.temporal?.publishedAtMillis ?: return stringResource(R.string.feed_time_unavailable)
    val locale = LocalConfiguration.current.locales[0]
    val zone = runCatching { ZoneId.of(timezone) }.getOrElse { ZoneId.systemDefault() }
    val formatted = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
        .withLocale(locale).withZone(zone).format(Instant.ofEpochMilli(published))
    return stringResource(R.string.today_090_original, formatted)
}
