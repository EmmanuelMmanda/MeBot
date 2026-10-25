package mebot.nguli.com.ui.feed

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import mebot.nguli.com.R
import mebot.nguli.com.core.*

private val inlineMarkup = Regex("""\[([^]\n]+)]\((https?://(?:[^\s()]|\([^\s()]*\))+)\)|\*\*([^*]+)\*\*|`([^`]+)`|https?://[^\s<>"\]]+""")

/** Present text immediately. Resource cards allocate media only after a deliberate tap. */
@Composable
internal fun RichResponse(text: String, modifier: Modifier = Modifier, citations: List<FeedCitation> = emptyList()) {
    val context = LocalContext.current
    val blocks = remember(text) { text.take(24000).split('\n').take(240) }
    val resources = remember(text) { RichContent.links(text) }
    val imageLabel = stringResource(R.string.rich_image)
    Column(modifier.semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(7.dp)) {
        var code = false
        blocks.forEach { raw ->
            if (raw.trim().startsWith("```")) code = !code
            else if (raw.isNotBlank()) {
                val heading = !code && raw.startsWith("#")
                val line = if (code) raw else raw.replace(Regex("^#{1,6}\\s+"), "").replace(Regex("^[-*]\\s+"), "• ")
                val image = Regex("""^!\[([^]]*)]\((https://[^\s)]+)\)$""").matchEntire(line.trim())
                if (image != null && !code) {
                    Text(image.groupValues[1].ifBlank { imageLabel }, style = MaterialTheme.typography.bodyMedium)
                } else {
                    val linkColor = MaterialTheme.colorScheme.primary
                    val rendered = remember(line, linkColor, code) { buildAnnotatedString {
                        if (code) append(line) else {
                            var cursor = 0
                            inlineMarkup.findAll(line).forEach { match ->
                                append(line.substring(cursor, match.range.first))
                                when {
                                    match.groupValues[2].isNotBlank() -> {
                                        val url = match.groupValues[2].replace("&amp;", "&")
                                        if (FeedLogic.safeUrl(url)) {
                                            pushStringAnnotation("url", url)
                                            withStyle(SpanStyle(color = linkColor)) { append(match.groupValues[1]) }
                                            pop()
                                        } else append(match.groupValues[1])
                                    }
                                    match.groupValues[3].isNotBlank() -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(match.groupValues[3]) }
                                    match.groupValues[4].isNotBlank() -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(match.groupValues[4]) }
                                    else -> {
                                        val url = RichContent.links(match.value).firstOrNull()?.url
                                        if (url != null) {
                                            pushStringAnnotation("url", url)
                                            withStyle(SpanStyle(color = linkColor)) { append(url) }
                                            pop()
                                            append(match.value.removePrefix(url))
                                        } else append(match.value)
                                    }
                                }
                                cursor = match.range.last + 1
                            }
                            append(line.substring(cursor))
                        }
                    } }
                    @Suppress("DEPRECATION")
                    ClickableText(rendered,
                        style = (if (heading) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge)
                            .copy(color = MaterialTheme.colorScheme.onSurface, fontFamily = if (code) FontFamily.Monospace else MaterialTheme.typography.bodyLarge.fontFamily),
                        onClick = { offset -> rendered.getStringAnnotations("url", offset, offset).firstOrNull()?.let { openInAppSource(context, it.item) } })
                }
            }
        }
        if (resources.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.rich_resources), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            resources.forEach { linked ->
                // Prefer the source-provided title, but do not describe an unchecked link as verified.
                val citation = citations.firstOrNull { FeedLogic.canonicalUrl(it.url) == FeedLogic.canonicalUrl(linked.url) }
                RichSourcePreview(if (linked.title.isBlank() && citation != null) citation else linked)
            }
        }
    }
}
