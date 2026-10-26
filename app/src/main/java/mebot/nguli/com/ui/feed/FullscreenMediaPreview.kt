package mebot.nguli.com.ui.feed

import android.graphics.Bitmap
import kotlinx.coroutines.CancellationException
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ZoomIn
import androidx.compose.material.icons.outlined.ZoomOut
import androidx.compose.material.icons.outlined.FitScreen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.layout.onSizeChanged
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
import androidx.compose.ui.text.style.TextAlign
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
            Text(stringResource(if (type == "youtube") R.string.media_provider_quality else if (RichContent.isHls(url)) R.string.media_stream_quality else R.string.media_original_quality),
                Modifier.padding(20.dp), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
internal fun FullscreenMediaShell(title: String, onClose: () -> Unit, screenTag: String = "media-fullscreen", closeTag: String = "media-fullscreen-close", body: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val view = LocalView.current
        DisposableEffect(view) {
            val window = (view.parent as? DialogWindowProvider)?.window
            val controls = window?.let { WindowCompat.getInsetsController(it, view) }
            controls?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controls?.hide(WindowInsetsCompat.Type.systemBars())
            onDispose { controls?.show(WindowInsetsCompat.Type.systemBars()) }
        }
        Surface(Modifier.fillMaxSize().testTag(screenTag), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().displayCutoutPadding()) {
                Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(title, Modifier.weight(1f).padding(horizontal = 12.dp), maxLines = 2, style = MaterialTheme.typography.titleMedium)
                    IconButton(onClick = onClose, modifier = Modifier.testTag(closeTag)) {
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
    val zoomState = rememberSaveable(url, saver = MediaZoomState.Saver) { MediaZoomState() }
    var loading by remember(url) { mutableStateOf(true) }
    val bitmap by produceState<Bitmap?>(null, url, retry) {
        loading = true; value = null
        try { value = loader?.invoke(url) ?: if (loader == null) (context.applicationContext as? MeBotApplication)?.feedMedia?.load(url, retryFailed = retry > 0) else null }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { value = null }
        finally { loading = false }
    }
    if (bitmap != null) {
        ZoomableMediaBitmap(bitmap!!, title, Modifier.weight(1f).fillMaxWidth(), stateKey = url, zoomState = zoomState)
    } else Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
        if (loading) Text(stringResource(R.string.media_loading)) else Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.resource_preview_failed))
            TextButton(onClick = { retry++ }) { Text(stringResource(R.string.feed_retry)) }
        }
    }
}

/** Pan only the portion of the fitted image that actually extends past the viewport. */
internal fun mediaPanBounds(imageWidth: Int, imageHeight: Int, viewportWidth: Int, viewportHeight: Int, zoom: Float): Offset {
    if (imageWidth <= 0 || imageHeight <= 0 || viewportWidth <= 0 || viewportHeight <= 0) return Offset.Zero
    val fit = minOf(viewportWidth.toFloat() / imageWidth, viewportHeight.toFloat() / imageHeight)
    val scale = (zoom.takeIf { it.isFinite() } ?: 1f).coerceIn(1f, 5f)
    return Offset(((imageWidth * fit * scale - viewportWidth) / 2f).coerceAtLeast(0f),
        ((imageHeight * fit * scale - viewportHeight) / 2f).coerceAtLeast(0f))
}

@Stable
internal class MediaZoomState(zoom: Float = 1f, panX: Float = 0f, panY: Float = 0f) {
    var zoom by mutableFloatStateOf(zoom.coerceIn(1f, 5f))
    var panX by mutableFloatStateOf(panX)
    var panY by mutableFloatStateOf(panY)
    fun fit() { zoom = 1f; panX = 0f; panY = 0f }
    companion object {
        val Saver = listSaver<MediaZoomState, Float>(save = { listOf(it.zoom, it.panX, it.panY) },
            restore = { MediaZoomState(it[0], it[1], it[2]) })
    }
}

/** Fit first; bounded display-only zoom never creates an unbounded full-resolution bitmap. */
@Composable
internal fun ZoomableMediaBitmap(bitmap: Bitmap, description: String, modifier: Modifier = Modifier,
    tagPrefix: String = "media", hintResource: Int = R.string.media_image_zoom, stateKey: String = description, zoomState: MediaZoomState? = null) {
    val state = zoomState ?: rememberSaveable(stateKey, saver = MediaZoomState.Saver) { MediaZoomState() }
    Column(modifier) {
        Box(Modifier.weight(1f).fillMaxWidth().clipToBounds().onSizeChanged { size ->
            val bounds = mediaPanBounds(bitmap.width, bitmap.height, size.width, size.height, state.zoom)
            state.panX = state.panX.coerceIn(-bounds.x, bounds.x); state.panY = state.panY.coerceIn(-bounds.y, bounds.y)
        }.pointerInput(stateKey) {
            detectTransformGestures { _, movement, scale, _ ->
                state.zoom = (state.zoom * scale).coerceIn(1f, 5f)
                val bounds = mediaPanBounds(bitmap.width, bitmap.height, size.width, size.height, state.zoom)
                state.panX = (state.panX + movement.x).coerceIn(-bounds.x, bounds.x)
                state.panY = (state.panY + movement.y).coerceIn(-bounds.y, bounds.y)
            }
        }, contentAlignment = Alignment.Center) {
            Image(bitmap.asImageBitmap(), description, contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().graphicsLayer(scaleX = state.zoom, scaleY = state.zoom, translationX = state.panX, translationY = state.panY)
                    .testTag("$tagPrefix-fullscreen-image"))
        }
        Text(stringResource(hintResource), Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { state.zoom = (state.zoom - .25f).coerceAtLeast(1f); state.panX = 0f; state.panY = 0f }, enabled = state.zoom > 1f,
                modifier = Modifier.testTag("$tagPrefix-zoom-out")) {
                Icon(Icons.Outlined.ZoomOut, stringResource(R.string.media_zoom_out))
            }
            Text(stringResource(R.string.media_zoom_percent, (state.zoom * 100).toInt()),
                Modifier.weight(1f).testTag("$tagPrefix-zoom-value"), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelLarge)
            IconButton(onClick = { state.zoom = (state.zoom + .25f).coerceAtMost(5f); state.panX = 0f; state.panY = 0f }, enabled = state.zoom < 5f,
                modifier = Modifier.testTag("$tagPrefix-zoom-in")) {
                Icon(Icons.Outlined.ZoomIn, stringResource(R.string.media_zoom_in))
            }
            IconButton(onClick = { state.zoom = 1f; state.panX = 0f; state.panY = 0f }, modifier = Modifier.testTag("$tagPrefix-zoom-reset")) {
                Icon(Icons.Outlined.FitScreen, stringResource(R.string.media_zoom_fit))
            }
        }
    }
}
