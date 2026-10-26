package mebot.nguli.com.ui.feed

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mebot.nguli.com.R
import mebot.nguli.com.core.DownloadResource
import mebot.nguli.com.feed.ResourceDownloader
import mebot.nguli.com.feed.ResourceOperations
import java.io.File

/** Decoder boundary allows honest host UI fixtures where Robolectric lacks native PDF decoding. */
internal interface PdfPreviewDecoder {
    fun pageCount(file: File): Int
    fun renderPage(file: File, pageIndex: Int): Bitmap
}

internal object NativePdfPreviewDecoder : PdfPreviewDecoder {
    override fun pageCount(file: File): Int = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
        PdfRenderer(descriptor).use { it.pageCount }
    }
    override fun renderPage(file: File, pageIndex: Int): Bitmap =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                renderer.openPage(pageIndex).use { page ->
                    val scale = minOf(1200f / page.width.coerceAtLeast(1), 1800f / page.height.coerceAtLeast(1), 2f)
                    val rendered = Bitmap.createBitmap((page.width * scale).toInt().coerceAtLeast(1),
                        (page.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                    try {
                        rendered.eraseColor(Color.WHITE)
                        page.render(rendered, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        rendered
                    } catch (failure: Throwable) { rendered.recycle(); throw failure }
                }
            }
        }
}

/** Bounded, on-demand, local PDF rendering. No third-party viewer receives the document URL. */
@Composable
internal fun PdfResourcePreview(url: String, title: String, onClose: () -> Unit,
    download: (suspend (DownloadResource) -> File)? = null, decoder: PdfPreviewDecoder = NativePdfPreviewDecoder) {
    val context = LocalContext.current
    var file by remember(url) { mutableStateOf<File?>(null) }
    var bitmap by remember(url) { mutableStateOf<Bitmap?>(null) }
    var pageCount by remember(url) { mutableIntStateOf(0) }
    var pageIndex by rememberSaveable(url) { mutableIntStateOf(0) }
    // Keep viewport state registered while loading so recreation does not lose it.
    val zoomState = rememberSaveable(url, saver = MediaZoomState.Saver) { MediaZoomState() }
    var failed by remember(url) { mutableStateOf(false) }
    var loading by remember(url) { mutableStateOf(true) }
    var retry by remember(url) { mutableIntStateOf(0) }
    LaunchedEffect(url, retry) {
        loading = true; failed = false
        var downloaded: File? = null
        try {
            val resource = DownloadResource.from(url)?.takeIf { it.extension == "pdf" } ?: error("Not a PDF")
            downloaded = ResourceOperations.run("downloading") { download?.invoke(resource) ?: ResourceDownloader(context.applicationContext).fetch(resource) }
            val count = withContext(Dispatchers.IO) {
                decoder.pageCount(checkNotNull(downloaded))
            }
            require(count > 0)
            file?.delete(); file = downloaded; downloaded = null
            pageCount = count; pageIndex = pageIndex.coerceIn(0, count - 1)
        } catch (_: TimeoutCancellationException) {
            // A downloader's own bounded timeout is recoverable; dismissal still cancels us.
            currentCoroutineContext().ensureActive()
            failed = true; loading = false
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { currentCoroutineContext().ensureActive(); failed = true; loading = false }
        finally { downloaded?.delete() }
    }
    LaunchedEffect(file, pageIndex) {
        val source = file ?: return@LaunchedEffect
        loading = true; failed = false
        var rendered: Bitmap? = null
        try {
            withContext(Dispatchers.IO) {
                // Assign inside the IO block so cancellation can still recycle an unpublished bitmap.
                rendered = decoder.renderPage(source, pageIndex)
            }
            // Compose may still be drawing the previous image; let GC release published bitmaps.
            currentCoroutineContext().ensureActive()
            bitmap = rendered; rendered = null
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { currentCoroutineContext().ensureActive(); failed = true }
        finally { rendered?.recycle(); if (currentCoroutineContext().isActive) loading = false }
    }
    DisposableEffect(url) {
        onDispose { file?.delete() }
    }
    FullscreenMediaShell(title.ifBlank { "PDF" }, onClose, screenTag = "pdf-preview", closeTag = "pdf-close") {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            if (failed) Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.rich_pdf_failed))
                TextButton(onClick = { file?.delete(); file = null; bitmap = null; retry++ }) { Text(stringResource(R.string.feed_retry)) }
                ResourceDownloadButton(url)
                TextButton(onClick = { openSource(context, url) }) { Text(stringResource(R.string.resource_open)) }
            } else if (loading) Text(stringResource(R.string.rich_pdf_loading), Modifier.testTag("pdf-loading"))
            else bitmap?.let { page ->
                ZoomableMediaBitmap(page, stringResource(R.string.rich_pdf_page, pageIndex + 1, pageCount),
                    Modifier.fillMaxSize(), tagPrefix = "pdf", hintResource = R.string.rich_pdf_zoom, stateKey = "$url:$pageIndex", zoomState = zoomState)
            }
        }
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { zoomState.fit(); pageIndex-- }, enabled = !loading && !failed && pageIndex > 0, modifier = Modifier.testTag("pdf-previous")) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.rich_previous_page))
            }
            Text(stringResource(R.string.rich_pdf_page, if (pageCount > 0) pageIndex + 1 else 0, pageCount), style = MaterialTheme.typography.labelLarge)
            IconButton(onClick = { zoomState.fit(); pageIndex++ }, enabled = !loading && !failed && pageIndex + 1 < pageCount, modifier = Modifier.testTag("pdf-next")) {
                Icon(Icons.AutoMirrored.Outlined.ArrowForward, stringResource(R.string.rich_next_page))
            }
        }
    }
}
