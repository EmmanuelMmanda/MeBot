package mebot.nguli.com.ui.feed

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import mebot.nguli.com.core.FeedItem
import mebot.nguli.com.core.TodayEntry
import mebot.nguli.com.core.TodayReason
import java.time.LocalDate
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** One relevant time on the card; exact dates, freshness and timezone are available on tap. */
@Composable
internal fun TodayReasonLabel090(entry: TodayEntry, timezone: String) {
    val locale = LocalConfiguration.current.locales[0]
    val zone = runCatching { ZoneId.of(timezone) }.getOrElse { ZoneId.systemDefault() }
    val full = remember(locale, zone) { DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(locale).withZone(zone) }
    val time = remember(locale, zone) { DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).withZone(zone) }
    val temporal = entry.item.temporal
    val publication = temporal?.publishedAtMillis
    val update = temporal?.substantiveUpdatedAtMillis
    var showReason by remember { mutableStateOf(false) }
    val label = when(entry.reason) {
        TodayReason.PUBLISHED -> publication?.let { stringResource(R.string.today_published_time_091, time.format(Instant.ofEpochMilli(it))) } ?: stringResource(R.string.today_090_published)
        TodayReason.UPDATED -> update?.let { stringResource(R.string.today_updated_time_091, time.format(Instant.ofEpochMilli(it))) } ?: stringResource(R.string.today_090_updated)
        TodayReason.FORECAST -> weatherTiming091(entry.item, timezone)
        TodayReason.HAPPENING -> stringResource(R.string.today_090_happening)
        TodayReason.DUE -> stringResource(R.string.today_090_due)
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f).testTag("today-time-${entry.item.id}"))
        IconButton(onClick = { showReason = true }, modifier = Modifier.testTag("today-why-${entry.item.id}")) {
            Icon(Icons.Outlined.Info, stringResource(R.string.today_dates_091), Modifier.size(20.dp))
        }
    }
    if(showReason) AlertDialog(onDismissRequest = { showReason = false },
        title = { Text(stringResource(R.string.today_dates_091)) },
        text = { Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (publication != null) Text(todayPublication090(entry, timezone))
            if (update != null) Text(stringResource(R.string.today_updated_time_091, full.format(Instant.ofEpochMilli(update))))
            val from = temporal?.validFromMillis
            val until = temporal?.validUntilMillis
            if (entry.reason == TodayReason.FORECAST && from != null && until != null) {
                Text(stringResource(R.string.weather_range_091, full.format(Instant.ofEpochMilli(from)), full.format(Instant.ofEpochMilli(until))))
            }
            temporal?.relevanceDate?.let { raw ->
                val date = runCatching { LocalDate.parse(raw).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)) }.getOrNull()
                if (date != null) Text(stringResource(R.string.today_090_source_date, date, temporal.sourceTimezone.orEmpty()))
            }
            val checked = temporal?.verifiedAtMillis ?: entry.item.fetchedAtMillis
            if (checked > 0) Text(stringResource(R.string.today_retrieved_091, full.format(Instant.ofEpochMilli(checked))))
            Text(stringResource(R.string.today_zone_091, zone.id))
            Text(stringResource(R.string.today_090_why_body), style = MaterialTheme.typography.bodySmall)
        } },
        confirmButton = { mebot.nguli.com.security.ProtectedWindowRegistration(); TextButton(onClick = { showReason = false }) { Text(stringResource(android.R.string.ok)) } })
}

/** Never use a retrieval timestamp as the time covered by a weather forecast. */
@Composable
internal fun weatherTiming091(item: FeedItem, timezone: String): String {
    val from = item.temporal?.validFromMillis ?: return stringResource(R.string.weather_unknown_time_091)
    val until = item.temporal?.validUntilMillis ?: return stringResource(R.string.weather_unknown_time_091)
    val zone = runCatching { ZoneId.of(timezone) }.getOrElse { ZoneId.systemDefault() }
    val locale = LocalConfiguration.current.locales[0]
    val today = Instant.ofEpochMilli(LocalFeedClock.current).atZone(zone).toLocalDate()
    fun format(millis: Long): String {
        val instant = Instant.ofEpochMilli(millis).atZone(zone)
        val formatter = when {
            instant.toLocalDate() == today -> DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
            instant.year != today.year -> DateTimeFormatter.ofPattern("d MMM uuuu, HH:mm", locale)
            else -> DateTimeFormatter.ofPattern("d MMM, HH:mm", locale)
        }
        return instant.format(formatter.withLocale(locale))
    }
    return stringResource(R.string.weather_range_091, format(from), format(until))
}

/** Exact source publication for the expandable date detail, never a legacy guessed timestamp. */
@Composable
internal fun todayPublication090(entry: TodayEntry, timezone: String): String {
    val published = entry.item.temporal?.publishedAtMillis ?: return stringResource(R.string.feed_time_unavailable)
    val locale = LocalConfiguration.current.locales[0]
    val zone = runCatching { ZoneId.of(timezone) }.getOrElse { ZoneId.systemDefault() }
    val formatted = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
        .withLocale(locale).withZone(zone).format(Instant.ofEpochMilli(published))
    return stringResource(R.string.today_090_original, formatted)
}
