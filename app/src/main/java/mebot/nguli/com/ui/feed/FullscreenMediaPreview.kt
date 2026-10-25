package mebot.nguli.com.ui.feed

import android.graphics.Bitmap
import kotlinx.coroutines.CancellationException
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import mebot.nguli.com.MeBotApplication
import mebot.nguli.com.R
import mebot.nguli.com.core.FeedLogic
import mebot.nguli.com.core.RichContent

/** A native full-window shell; provider playback and controls stay inside it. */
@Composable
internal fun FullscreenMediaPreview(url: String, title: String, onClose: () -> Unit, playback: MediaPlaybackPosition? = null, image: Boolean = false) {
    if (!FeedLogic.safeUrl(url, true)) return
    val type = remember(url, image) { if (image) "image" else RichContent.type(url) }
    FullscreenMediaShell(title, onClose) {
        if (type == "image") FullscreenImage(url, title)
        else {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                OfficialMediaPlayer(url, expanded = true, playback = playback)
            }
            Text(stringResource(if (type == "youtube") R.string.media_provider_quality else R.string.media_original_quality),
                Modifier.padding(20.dp), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
internal fun FullscreenMediaShell(title: String, onClose: () -> Unit, body: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val view = LocalView.current
        DisposableEffect(view) {
            val window = (view.parent as? DialogWindowProvider)?.window
            val controls = window?.let { WindowCompat.getInsetsController(it, view) }
            controls?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controls?.hide(WindowInsetsCompat.Type.systemBars())
            onDispose { controls?.show(WindowInsetsCompat.Type.systemBars()) }
        }
        Surface(Modifier.fillMaxSize().testTag("media-fullscreen"), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().displayCutoutPadding()) {
                Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(title, Modifier.weight(1f).padding(horizontal = 12.dp), maxLines = 2, style = MaterialTheme.typography.titleMedium)
                    IconButton(onClick = onClose, modifier = Modifier.testTag("media-fullscreen-close")) {
                        Icon(Icons.Outlined.Close, stringResource(R.string.media_close_fullscreen))
                    }
                }
                body()
            }
        }
    }
}

@Composable
private fun ColumnScope.FullscreenImage(url: String, title: String) {
    val context = LocalContext.current
    val loader = LocalPublisherImageLoader.current
    var retry by remember(url) { mutableIntStateOf(0) }
    var loading by remember(url) { mutableStateOf(true) }
    val bitmap by produceState<Bitmap?>(null, url, retry) {
        loading = true; value = null
        try { value = loader?.invoke(url) ?: if (loader == null) (context.applicationContext as? MeBotApplication)?.feedMedia?.load(url, retryFailed = retry > 0) else null }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { value = null }
        finally { loading = false }
    }
    var zoom by remember(url) { mutableFloatStateOf(1f) }
    var pan by remember(url) { mutableStateOf(Offset.Zero) }
    Box(Modifier.weight(1f).fillMaxWidth().clipToBounds().pointerInput(url) {
        detectTransformGestures { _, movement, scale, _ ->
            zoom = (zoom * scale).coerceIn(1f, 5f)
            val maxX = size.width * (zoom - 1) / 2f
            val maxY = size.height * (zoom - 1) / 2f
            pan = Offset((pan.x + movement.x).coerceIn(-maxX, maxX), (pan.y + movement.y).coerceIn(-maxY, maxY))
        }
    }, contentAlignment = Alignment.Center) {
        bitmap?.let { image -> Image(image.asImageBitmap(), title, contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().graphicsLayer(scaleX = zoom, scaleY = zoom, translationX = pan.x, translationY = pan.y).testTag("media-fullscreen-image")) }
            ?: if (loading) Text(stringResource(R.string.media_loading)) else Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.resource_preview_failed))
                TextButton(onClick = { retry++ }) { Text(stringResource(R.string.feed_retry)) }
            }
    }
    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.media_image_zoom), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { zoom = 1f; pan = Offset.Zero }, modifier = Modifier.testTag("media-zoom-reset")) {
            Icon(Icons.Outlined.RestartAlt, null); Text(stringResource(R.string.media_reset))
        }
    }
}
