package app.mynguli.ui.feed

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceError
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.mynguli.R
import app.mynguli.core.*
import java.net.URI
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One expanded player across all response cards and the detail hero. */
internal object MediaPreviewSession {
    private val selected = MutableStateFlow<String?>(null)
    val state = selected.asStateFlow()
    fun select(key: String) { selected.value = key }
    fun clear(key: String) { if (selected.value == key) selected.value = null }
}

@Composable
internal fun RichSourcePreview(source: FeedCitation, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val type = remember(source.url) { RichContent.type(source.url) }
    val key = remember(source.url) { UUID.randomUUID().toString() }
    val selected by MediaPreviewSession.state.collectAsState()
    val preview = selected == key
    DisposableEffect(key) { onDispose { MediaPreviewSession.clear(key) } }
    val hostname = remember(source.url) { runCatching { URI(source.url).host.removePrefix("www.") }.getOrDefault("") }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(when(type) {
                    "youtube", "video" -> Icons.Outlined.SmartDisplay
                    "audio" -> Icons.Outlined.Headphones
                    "image" -> Icons.Outlined.Image
                    else -> Icons.Outlined.Description
                }, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(source.title.ifBlank { hostname }, style = MaterialTheme.typography.titleSmall,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(if (type == "document") "${DownloadResource.from(source.url)?.extension?.uppercase().orEmpty()} · $hostname"
                        else hostname, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (preview && type == "image") {
                val item = remember(source) { FeedItem(FeedLogic.itemId(source.url), "preview", source.title, "",
                    sourceUrl = source.url, media = FeedMedia(source.url, source.url)) }
                PublisherImage(item, Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(12.dp)))
            }
            if (preview && type in setOf("youtube", "video", "audio")) OfficialMediaPlayer(source.url)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                if (type in setOf("image", "youtube", "video", "audio")) TextButton(onClick = {
                    if (preview) MediaPreviewSession.clear(key) else MediaPreviewSession.select(key)
                }, modifier = Modifier.heightIn(min = 48.dp), contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Icon(if (preview) Icons.Outlined.Close else if (type == "image") Icons.Outlined.Visibility else Icons.Outlined.PlayArrow,
                        null, Modifier.size(18.dp))
                    Spacer(Modifier.width(5.dp))
                    Text(stringResource(if (preview) R.string.resource_hide else R.string.resource_preview))
                } else if (DownloadResource.from(source.url) != null) ResourceDownloadButton(source.url)
                TextButton(onClick = { openSource(context, source.url) },
                    modifier = Modifier.heightIn(min = 48.dp), contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text(stringResource(R.string.resource_open)); Spacer(Modifier.width(5.dp))
                    Icon(Icons.AutoMirrored.Outlined.OpenInNew, null, Modifier.size(16.dp))
                }
            }
            if (type == "document") Text(stringResource(R.string.resource_file_limit),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (preview && type == "image") ResourceDownloadButton(source.url)
        }
    }
}

/** Player resources are allocated only after an explicit tap, never on an AI token. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun OfficialMediaPlayer(url: String) {
    if (!FeedLogic.safeUrl(url, true)) return
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val youtube = remember(url) { RichContent.youtubeId(url) }
    var failed by remember(url) { mutableStateOf(false) }
    if (failed) {
        Text(stringResource(R.string.resource_preview_failed), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("resource-preview-error"))
        return
    }
    if (youtube != null) {
        val view = remember(url) {
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = false
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.mediaPlaybackRequiresUserGesture = true
                settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                settings.safeBrowsingEnabled = true
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        if (request.isForMainFrame) {
                            val target = request.url.toString()
                            if (FeedLogic.safeUrl(target, true)) openSource(context, target)
                            return true
                        }
                        return false
                    }
                    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                        if (request.isForMainFrame) failed = true
                    }
                }
                // Official embed, visible controls, identity/referrer; no audio extraction or JS bridge.
                loadDataWithBaseURL("https://emmanuelmmanda.github.io/MeBot/",
                    """<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"><meta name="referrer" content="strict-origin-when-cross-origin"></head><body style="margin:0;background:#111"><iframe title="YouTube video" style="position:fixed;border:0;width:100%;height:100%" src="https://www.youtube.com/embed/$youtube?playsinline=1&amp;enablejsapi=1&amp;origin=https%3A%2F%2Femmanuelmmanda.github.io" allow="accelerometer; autoplay; encrypted-media; gyroscope; picture-in-picture; web-share" referrerpolicy="strict-origin-when-cross-origin" allowfullscreen></iframe></body></html>""",
                    "text/html", "UTF-8", null)
            }
        }
        DisposableEffect(view, owner) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_STOP) {
                    view.evaluateJavascript("""document.querySelector('iframe')?.contentWindow.postMessage(JSON.stringify({event:'command',func:'pauseVideo',args:[]}),'https://www.youtube.com');""", null)
                    view.onPause()
                } else if (event == Lifecycle.Event.ON_START) view.onResume()
            }
            owner.lifecycle.addObserver(observer)
            onDispose { owner.lifecycle.removeObserver(observer); view.stopLoading(); view.loadUrl("about:blank"); view.destroy() }
        }
        AndroidView(factory = { view }, modifier = Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(12.dp)))
    } else {
        val type = remember(url) { RichContent.type(url) }
        if (type !in setOf("audio", "video")) return
        val view = remember(url) {
            VideoView(context).apply {
                setMediaController(MediaController(context).also { it.setAnchorView(this) })
                setOnErrorListener { _, _, _ -> failed = true; true }
                setVideoURI(Uri.parse(url))
                setOnPreparedListener { seekTo(1) }
            }
        }
        DisposableEffect(view, owner) {
            val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) view.pause() }
            owner.lifecycle.addObserver(observer)
            onDispose { owner.lifecycle.removeObserver(observer); view.stopPlayback() }
        }
        AndroidView(factory = { view }, modifier = Modifier.fillMaxWidth().height(if (type == "audio") 110.dp else 220.dp)
            .clip(RoundedCornerShape(12.dp)))
    }
}
