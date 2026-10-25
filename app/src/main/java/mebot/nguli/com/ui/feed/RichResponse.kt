package mebot.nguli.com.ui.feed

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.*
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import mebot.nguli.com.core.*
import java.net.URI

private val inlineMarkup = Regex("\\[([^]\\n]+)]\\((https?://[^\\s)]+)\\)|\\*\\*([^*]+)\\*\\*|`([^`]+)`")

@Composable
internal fun RichResponse(text: String, modifier: Modifier = Modifier, citations: List<FeedCitation> = emptyList()) {
    val context = LocalContext.current
    val blocks = remember(text) { text.take(24000).split('\n').take(240) }
    Column(modifier.semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(7.dp)) {
        var code = false
        blocks.forEach { raw ->
            if (raw.trim().startsWith("```")) code = !code
            else if (raw.isNotBlank()) {
                val heading = raw.startsWith("#")
                val line = raw.replace(Regex("^#{1,6}\\s+"), "").replace(Regex("^[-*]\\s+"), "• ")
                val image = Regex("^!\\[([^]]*)]\\((https://[^\\s)]+)\\)$").matchEntire(line.trim())
                if (image != null && citations.any { FeedLogic.canonicalUrl(it.url) == FeedLogic.canonicalUrl(image.groupValues[2]) }) {
                    val url = image.groupValues[2]
                    val item = FeedItem(FeedLogic.itemId(url), "preview", image.groupValues[1], "", sourceUrl=url, media=FeedMedia(url,url))
                    PublisherImage(item, Modifier.fillMaxWidth().height(190.dp).clip(RoundedCornerShape(16.dp)))
                } else if (image != null) {
                    val url = image.groupValues[2]
                    if (FeedLogic.safeUrl(url)) TextButton(onClick = { openSource(context, url) }) {
                        Text(image.groupValues[1].ifBlank { "Image" })
                    }
                } else {
                    val linkColor = MaterialTheme.colorScheme.primary
                    val rendered = remember(line, linkColor) { buildAnnotatedString {
                        var cursor = 0
                        inlineMarkup.findAll(line).forEach { m ->
                            append(line.substring(cursor, m.range.first))
                            when {
                                m.groupValues[2].isNotBlank() -> {
                                    val url = m.groupValues[2]
                                    if (FeedLogic.safeUrl(url)) { pushStringAnnotation("url", url); withStyle(SpanStyle(color=linkColor)) { append(m.groupValues[1]) }; pop() }
                                    else append(m.groupValues[1])
                                }
                                m.groupValues[3].isNotBlank() -> withStyle(SpanStyle(fontWeight=FontWeight.SemiBold)) { append(m.groupValues[3]) }
                                else -> withStyle(SpanStyle(fontFamily=FontFamily.Monospace)) { append(m.groupValues[4]) }
                            }
                            cursor = m.range.last + 1
                        }
                        append(line.substring(cursor))
                    } }
                    @Suppress("DEPRECATION")
                    ClickableText(rendered, style=(if(heading) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge)
                        .copy(color=MaterialTheme.colorScheme.onSurface, fontFamily=if(code) FontFamily.Monospace else MaterialTheme.typography.bodyLarge.fontFamily),
                        onClick={ offset -> rendered.getStringAnnotations("url", offset, offset).firstOrNull()?.let { openSource(context,it.item) } })
                }
            }
        }
    }
}

