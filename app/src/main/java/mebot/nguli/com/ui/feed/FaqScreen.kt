package mebot.nguli.com.ui.feed

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import mebot.nguli.com.R
import mebot.nguli.com.ui.ScreenContainer
import mebot.nguli.com.ui.ScreenTitle

private data class FaqEntry(val id: String, val question: Int, val answer: Int)
private val faqEntries = listOf(
    FaqEntry("setup", R.string.setup_faq_setup_q, R.string.setup_faq_setup_a),
    FaqEntry("memory", R.string.setup_faq_memory_q, R.string.setup_faq_memory_a),
    FaqEntry("connection", R.string.setup_faq_connection_q, R.string.setup_faq_connection_a),
    FaqEntry("modules", R.string.setup_faq_modules_q, R.string.setup_faq_modules_a),
    FaqEntry("explanations", R.string.setup_faq_explanations_q, R.string.setup_faq_explanations_a),
    FaqEntry("search", R.string.setup_faq_search_q, R.string.setup_faq_search_a),
    FaqEntry("sources", R.string.setup_faq_sources_q, R.string.setup_faq_sources_a),
    FaqEntry("limits", R.string.setup_faq_limits_q, R.string.setup_faq_limits_a),
    FaqEntry("background", R.string.setup_faq_background_q, R.string.setup_faq_background_a),
    FaqEntry("offline", R.string.setup_faq_offline_q, R.string.setup_faq_offline_a),
    FaqEntry("privacy", R.string.setup_faq_privacy_q, R.string.setup_faq_privacy_a),
    FaqEntry("import", R.string.setup_faq_import_q, R.string.setup_faq_import_a),
    FaqEntry("voice", R.string.setup_faq_voice_q, R.string.setup_faq_voice_a),
    FaqEntry("alarms", R.string.setup_faq_alarms_q, R.string.setup_faq_alarms_a),
)

@Composable
fun FaqScreen(onContext: () -> Unit, onConnect: () -> Unit, onTour: (() -> Unit)? = null) {
    val context = LocalContext.current
    var query by rememberSaveable { mutableStateOf("") }
    var expanded by rememberSaveable { mutableStateOf<String?>("setup") }
    val entries = faqEntries.map { Triple(it, stringResource(it.question), stringResource(it.answer)) }
    val visible = entries.filter { (_, question, answer) -> query.isBlank() || question.contains(query.trim(), ignoreCase = true) || answer.contains(query.trim(), ignoreCase = true) }
    ScreenContainer {
        ScreenTitle(stringResource(R.string.setup_faq_title), stringResource(R.string.setup_faq_summary))
        OutlinedTextField(query, { query = it.take(100) }, singleLine = true, shape = RoundedCornerShape(18.dp),
            modifier = Modifier.fillMaxWidth().testTag("faq-search"),
            label = { Text(stringResource(R.string.setup_faq_search_label)) },
            leadingIcon = { Icon(Icons.Outlined.Search, null) },
            trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) {
                Icon(Icons.Outlined.Close, stringResource(R.string.setup_clear_search))
            } })
        if (visible.isEmpty()) Text(stringResource(R.string.setup_faq_no_results), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("faq-empty"))
        visible.forEach { (entry, question, answer) ->
            val open = expanded == entry.id
            val disclosure = stringResource(if (open) R.string.setup_expanded else R.string.setup_collapsed)
            Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(20.dp)) {
                Column {
                    Surface(onClick = { expanded = if (open) null else entry.id }, color = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.testTag(if (entry.id == "search") "faq-topic-search" else "faq-${entry.id}").semantics { stateDescription = disclosure }) {
                        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(18.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(question, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                            Icon(if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, Modifier.size(22.dp))
                        }
                    }
                    if (open) Column(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, bottom = 18.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(answer, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag("faq-answer-${entry.id}"))
                        when (entry.id) {
                            "setup" -> {
                                if (onTour != null) OutlinedButton(onClick = onTour,
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("faq-open-tour")) {
                                    Icon(Icons.Outlined.PlayCircleOutline, null, Modifier.size(19.dp)); Spacer(Modifier.width(8.dp))
                                    Text(stringResource(R.string.tour_replay))
                                }
                                FilledTonalButton(onClick = onContext, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("faq-open-context")) {
                                    Text(stringResource(R.string.setup_open_context))
                                }
                                ContextPromptActions()
                            }
                            "memory" -> TextButton(onClick = { openSource(context, "https://developers.openai.com/siwc/quickstart") }) {
                                Text(stringResource(R.string.setup_account_access_link))
                            }
                            "connection" -> FilledTonalButton(onClick = onConnect, modifier = Modifier.heightIn(min = 48.dp).testTag("faq-open-connect")) {
                                Text(stringResource(R.string.feed_connection))
                            }
                            "import" -> TextButton(onClick = onContext) { Text(stringResource(R.string.setup_open_context)) }
                        }
                    }
                }
            }
        }
    }
}
