package mebot.nguli.com.ui.feed

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import mebot.nguli.com.R
import mebot.nguli.com.core.*

private val inlineMarkup = Regex(
    """!\[([^]\n]*)]\((https?://[^\s)]+)\)|\[([^]\n]+)]\((https?://[^\s)]+)\)|\*\*(.+?)\*\*|__(.+?)__|~~(.+?)~~|`([^`]+)`|\*([^*\n]+)\*|_([^_\n]+)_|https?://[^\s<>"]+"""
)

/** Presents complete response text immediately; links are safe annotated links, media stays opt-in. */
@Composable
internal fun RichResponse(text: String, modifier: Modifier = Modifier, citations: List<FeedCitation> = emptyList()) {
    val context = LocalContext.current
    val blocks = remember(text) { RichMarkdown.parse(text) }
    val resources = remember(text) { RichContent.links(text) }
    val linkColor = MaterialTheme.colorScheme.primary
    val imageLabel = stringResource(R.string.rich_image)
    Column(modifier = modifier.semantics(mergeDescendants = false) {}, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SelectionContainer(modifier = Modifier.testTag("rich-selectable-content")) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                blocks.forEachIndexed { index, block ->
                    when (block) {
                        is RichMarkdownBlock.Heading -> RichInlineText(block.text, context, linkColor,
                            style = when (block.level) {
                                1 -> MaterialTheme.typography.headlineSmall
                                2 -> MaterialTheme.typography.titleLarge
                                3 -> MaterialTheme.typography.titleMedium
                                else -> MaterialTheme.typography.titleSmall
                            })
                        is RichMarkdownBlock.Paragraph -> {
                            val image = Regex("^!\\[([^]]*)]\\((https?://[^\\s)]+)\\)$").matchEntire(block.text.trim())
                            if (image != null) Text(image.groupValues[1].ifBlank { imageLabel }, style = MaterialTheme.typography.bodyLarge)
                            else RichInlineText(block.text, context, linkColor, style = MaterialTheme.typography.bodyLarge)
                        }
                        is RichMarkdownBlock.ListItem -> Row(
                            Modifier.fillMaxWidth().padding(start = (block.indent * 12).dp),
                            verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(if (block.ordered) block.marker else "•", style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            RichInlineText(block.text, context, linkColor, Modifier.weight(1f), MaterialTheme.typography.bodyLarge)
                        }
                        is RichMarkdownBlock.Code -> RichCodeBlock(block, index)
                        is RichMarkdownBlock.Table -> RichTable(block, context, linkColor)
                    }
                }
            }
        }
        if (resources.isNotEmpty()) {
            Spacer(Modifier.height(2.dp))
            Text(stringResource(R.string.rich_resources), style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            resources.forEach { linked ->
                // Prefer source-provided title, but don't describe an unchecked link as verified.
                val citation = citations.firstOrNull { FeedLogic.canonicalUrl(it.url) == FeedLogic.canonicalUrl(linked.url) }
                RichSourcePreview(if (linked.title.isBlank() && citation != null) citation else linked)
            }
        }
    }
}

@Composable
private fun RichInlineText(
    text: String,
    context: Context,
    linkColor: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
    style: androidx.compose.ui.text.TextStyle = androidx.compose.ui.text.TextStyle.Default,
) {
    val linkStyles = remember(linkColor) {
        TextLinkStyles(style = SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))
    }
    val rendered = remember(text, linkStyles, style.fontFamily, context) {
        buildAnnotatedString {
            var cursor = 0
            inlineMarkup.findAll(text).forEach { match ->
                append(text.substring(cursor, match.range.first))
                val groups = match.groupValues
                when {
                    groups[1].isNotEmpty() -> append(groups[1].ifBlank { "Image" })
                    groups[3].isNotEmpty() -> appendSafeLink(groups[3], groups[4], linkStyles, context)
                    groups[5].isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(groups[5]) }
                    groups[6].isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(groups[6]) }
                    groups[7].isNotEmpty() -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { append(groups[7]) }
                    groups[8].isNotEmpty() -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace,
                        background = androidx.compose.ui.graphics.Color(0x14000000))) { append(groups[8]) }
                    groups[9].isNotEmpty() -> withStyle(SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)) { append(groups[9]) }
                    groups[10].isNotEmpty() -> withStyle(SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)) { append(groups[10]) }
                    else -> {
                        val raw = match.value
                        val url = raw.trimEnd('.', ',', '!', '?', ';', ':', ')')
                        if (FeedLogic.safeUrl(url)) {
                            appendSafeLink(url, url, linkStyles, context)
                            append(raw.removePrefix(url))
                        } else append(raw)
                    }
                }
                cursor = match.range.last + 1
            }
            append(text.substring(cursor))
        }
    }
    Text(rendered, modifier = modifier, style = style)
}

private fun AnnotatedString.Builder.appendSafeLink(
    label: String,
    rawUrl: String,
    styles: TextLinkStyles,
    context: Context,
) {
    val url = rawUrl.replace("&amp;", "&")
    if (!FeedLogic.safeUrl(url)) {
        append(label)
        return
    }
    withLink(LinkAnnotation.Clickable(
        tag = "url:$url",
        styles = styles,
        linkInteractionListener = LinkInteractionListener { _ -> openInAppSource(context, url) },
    )) { append(label) }
}

@Composable
private fun RichCodeBlock(block: RichMarkdownBlock.Code, index: Int) {
    val context = LocalContext.current
    val codeLabel = stringResource(R.string.rich_code)
    val copyLabel = stringResource(R.string.rich_code_copy)
    val copied = stringResource(R.string.rich_code_copied)
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().testTag("rich-code-$index")) {
        Column(Modifier.padding(start = 12.dp, end = 6.dp, top = 4.dp, bottom = 12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(block.language.ifBlank { codeLabel }, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                IconButton(onClick = { copyText(context, block.text, codeLabel, copied) },
                    modifier = Modifier.size(40.dp).testTag("rich-code-copy-$index")) {
                    Icon(Icons.Outlined.ContentCopy, copyLabel, Modifier.size(18.dp))
                }
            }
            Text(block.text, Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                softWrap = false, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
private fun RichTable(block: RichMarkdownBlock.Table, context: Context, linkColor: androidx.compose.ui.graphics.Color) {
    if (block.rows.isEmpty()) return
    val headers = block.rows.first()
    Text(stringResource(R.string.rich_table_label), style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(1.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            block.rows.forEachIndexed { rowIndex, row ->
                Row(horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                    headers.indices.forEach { columnIndex ->
                        Surface(color = if (rowIndex == 0) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                            shape = RoundedCornerShape(if (rowIndex == 0) 8.dp else 4.dp),
                            modifier = Modifier.widthIn(min = 104.dp).width(132.dp)) {
                            RichInlineText(row.getOrElse(columnIndex) { "" }, context, linkColor,
                                Modifier.padding(horizontal = 9.dp, vertical = 8.dp),
                                if (rowIndex == 0) MaterialTheme.typography.labelLarge else MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}

private fun copyText(context: Context, value: String, label: String, copied: String) {
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(label, value))
    Toast.makeText(context, copied, Toast.LENGTH_SHORT).show()
}
