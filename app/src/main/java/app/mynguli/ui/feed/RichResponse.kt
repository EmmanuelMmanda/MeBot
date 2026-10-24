package app.mynguli.ui.feed

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
import app.mynguli.core.*
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

@Composable
internal fun RichSourcePreview(source: FeedCitation, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val type = remember(source.url) { RichContent.type(source.url) }
    val sw = LocalConfiguration.current.locales[0].language == "sw"
    var play by rememberSaveable(source.url) { mutableStateOf(false) }
    Column(modifier.fillMaxWidth(), verticalArrangement=Arrangement.spacedBy(7.dp)) {
        if (type == "image") {
            val item = remember(source) { FeedItem(FeedLogic.itemId(source.url), "preview", source.title, "", sourceUrl=source.url, media=FeedMedia(source.url,source.url)) }
            PublisherImage(item, Modifier.fillMaxWidth().height(190.dp).clip(RoundedCornerShape(16.dp)))
        }
        if (play && type in setOf("youtube", "audio", "video")) OfficialMediaPlayer(source.url)
        if (type in setOf("youtube", "audio", "video")) FilledTonalButton(onClick={play=!play}, shape=RoundedCornerShape(14.dp), modifier=Modifier.fillMaxWidth()) {
            Icon(if(play) Icons.Outlined.Stop else Icons.Outlined.PlayArrow,null,Modifier.size(20.dp));Spacer(Modifier.width(8.dp))
            Text(if(play) {if(sw) "Funga kichezaji" else "Close player"} else {if(sw) "Cheza hapa" else "Play here"})
        }
        TextButton(onClick={openSource(context,source.url)}, contentPadding=PaddingValues(4.dp)) {
            Icon(if(type=="document") Icons.Outlined.Description else Icons.Outlined.OpenInNew,null,Modifier.size(18.dp));Spacer(Modifier.width(8.dp))
            Text(source.title.ifBlank { runCatching { URI(source.url).host }.getOrDefault(source.url) }, maxLines=2)
        }
        if (play) Text(if(sw) "Ikishindwa kucheza, fungua kiungo hapo juu." else "If playback is unavailable, open the source above.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Players load only after a tap and are destroyed on leaving the screen. No media extraction. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun OfficialMediaPlayer(url: String) {
    val context=LocalContext.current
    val youtube=remember(url){RichContent.youtubeId(url)}
    if (!FeedLogic.safeUrl(url,true)) return
    if(youtube != null) {
        val web=remember(url) { WebView(context).apply {
            settings.javaScriptEnabled=true
            settings.domStorageEnabled=false
            settings.allowFileAccess=false
            settings.allowContentAccess=false
            settings.mediaPlaybackRequiresUserGesture=true
            settings.mixedContentMode=android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            webViewClient=object:WebViewClient(){ override fun shouldOverrideUrlLoading(view:WebView?,request:WebResourceRequest?):Boolean {
                if(request?.isForMainFrame==true){ val target=request.url.toString();if(FeedLogic.safeUrl(target,true))openSource(context,target);return true };return false
            } }
            loadDataWithBaseURL("https://emmanuelmmanda.github.io/MeBot/", "<!doctype html><html><head><meta name='viewport' content='width=device-width,initial-scale=1'></head><body style='margin:0;background:#000'><iframe width='100%' height='100%' style='position:absolute;border:0' src='https://www.youtube.com/embed/$youtube?playsinline=1' title='YouTube player' allow='encrypted-media; picture-in-picture' allowfullscreen></iframe></body></html>","text/html","UTF-8",null)
        } }
        DisposableEffect(web){onDispose {web.stopLoading();web.loadUrl("about:blank");web.destroy()}}
        AndroidView(factory={web},modifier=Modifier.fillMaxWidth().height(230.dp).clip(RoundedCornerShape(16.dp)))
    } else if(RichContent.type(url) in setOf("audio","video")) {
        val video=remember(url){VideoView(context).apply{setVideoURI(Uri.parse(url));setMediaController(MediaController(context).also{it.setAnchorView(this)});setOnPreparedListener{seekTo(1)}}}
        DisposableEffect(video){onDispose{video.stopPlayback()}}
        AndroidView(factory={video},modifier=Modifier.fillMaxWidth().height(230.dp).clip(RoundedCornerShape(16.dp)))
    }
}
