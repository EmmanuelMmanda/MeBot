package mebot.nguli.com.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import mebot.nguli.com.ui.feed.PdfResourcePreview
import mebot.nguli.com.ui.feed.PdfPreviewDecoder
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PdfFullscreen085Test {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val created = mutableListOf<File>()
    private val completedFixtureRenders = ConcurrentHashMap<Int, AtomicInteger>()
    private fun pdf(): File = File.createTempFile("pdf-fixture-", ".pdf", compose.activity.cacheDir).also {
        it.writeBytes(checkNotNull(javaClass.getResourceAsStream("/media-085/tanzania-checklist.pdf")).use { stream -> stream.readBytes() })
        created += it
    }
    // Robolectric SDK35 lacks FileDescriptor.getOwnerId$; SDK34 returns pageCount=0.
    // These PNGs were rendered from the adjacent synthetic PDF by Poppler, not Android PdfRenderer.
    private val fixtureDecoder = object : PdfPreviewDecoder {
        override fun pageCount(file: File): Int = 2
        override fun renderPage(file: File, pageIndex: Int): Bitmap {
            val bitmap = checkNotNull(BitmapFactory.decodeStream(javaClass.getResourceAsStream("/media-085/checklist-page-${pageIndex + 1}.png")))
            completedFixtureRenders.computeIfAbsent(pageIndex) { AtomicInteger() }.incrementAndGet()
            return bitmap
        }
    }
    /** Wait for the IO decoder before querying Compose semantics; the footer can update first. */
    private fun awaitRenderedPage(pageIndex: Int, expectedRenderCount: Int, waitForPageLabel: Boolean = false) {
        val pageLabel = compose.activity.getString(mebot.nguli.com.R.string.rich_pdf_page, pageIndex + 1, 2)
        if (waitForPageLabel) {
            // First settle the page-index composition. The footer changes before IO rendering, so
            // this one-shot assertion is a transition checkpoint, not render readiness.
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            compose.onNodeWithText(pageLabel).assertExists()
        }
        try {
            compose.waitUntil(10_000) {
                (completedFixtureRenders[pageIndex]?.get() ?: 0) >= expectedRenderCount
            }
        } catch (timeout: androidx.compose.ui.test.ComposeTimeoutException) {
            val completed = completedFixtureRenders[pageIndex]?.get() ?: 0
            throw AssertionError("Timed out waiting for PDF page ${pageIndex + 1} render $expectedRenderCount; completed=$completed", timeout)
        }
        // Let the decoder continuation publish its bitmap and loading=false, then settle a frame.
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.onNodeWithTag("pdf-fullscreen-image").assertExists().assertContentDescriptionEquals(pageLabel)
        compose.onNodeWithText(pageLabel).assertExists()
    }
    @Test fun nativeReaderShellWithPrerenderedFixtureFitsZoomsAndRestoresPage() {
        val visible = mutableStateOf(true)
        val restoration = StateRestorationTester(compose)
        restoration.setContent { MeBotTheme {
            if (visible.value) PdfResourcePreview("https://example.org/checklist.pdf", "Small-business checklist", { visible.value = false }, download = { pdf() }, decoder = fixtureDecoder)
        } }
        awaitRenderedPage(pageIndex = 0, expectedRenderCount = 1)
        compose.onNodeWithTag("pdf-previous").assertIsNotEnabled()
        compose.onNodeWithTag("pdf-next").assertIsEnabled()
        compose.onNodeWithTag("pdf-zoom-out").assertIsNotEnabled()
        compose.onNodeWithTag("pdf-zoom-in").performClick()
        compose.onNodeWithTag("pdf-zoom-value").assertTextEquals("125%")
        repeat(15) { compose.onNodeWithTag("pdf-zoom-in").performClick() }
        compose.onNodeWithTag("pdf-zoom-value").assertTextEquals("500%")
        compose.onNodeWithTag("pdf-zoom-in").assertIsNotEnabled()
        compose.onNodeWithTag("pdf-zoom-reset").performClick()
        compose.onNodeWithTag("pdf-zoom-value").assertTextEquals("100%")
        capture()
        compose.onNodeWithTag("pdf-next").performClick()
        awaitRenderedPage(pageIndex = 1, expectedRenderCount = 1, waitForPageLabel = true)
        compose.onNodeWithTag("pdf-next").assertIsNotEnabled()
        compose.onNodeWithTag("pdf-zoom-in").performClick()
        compose.onNodeWithTag("pdf-zoom-value").assertTextEquals("125%")
        val pageTwoRendersBeforeRestore = completedFixtureRenders[1]?.get() ?: 0
        restoration.emulateSavedInstanceStateRestore()
        awaitRenderedPage(pageIndex = 1, expectedRenderCount = pageTwoRendersBeforeRestore + 1, waitForPageLabel = true)
        compose.onNodeWithTag("pdf-zoom-value").assertTextEquals("125%")
        compose.onNodeWithText("Page 2 of 2").assertExists()
        compose.runOnIdle { ShadowDialog.getLatestDialog().onBackPressed() }
        compose.onNodeWithTag("pdf-preview").assertDoesNotExist()
        compose.runOnIdle { assertTrue(created.all { !it.exists() }) }
    }
    @Test fun dismissDuringRenderingRecyclesUnpublishedPageAndDeletesTemporaryFile() {
        val visible = mutableStateOf(true)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val rendered = AtomicReference<Bitmap?>(null)
        val delayedDecoder = object : PdfPreviewDecoder {
            override fun pageCount(file: File): Int = 2
            override fun renderPage(file: File, pageIndex: Int): Bitmap {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                return Bitmap.createBitmap(120, 180, Bitmap.Config.ARGB_8888).also { rendered.set(it) }
            }
        }
        compose.setContent { MeBotTheme {
            if (visible.value) PdfResourcePreview("https://example.org/checklist.pdf", "Pending page", { visible.value = false },
                download = { pdf() }, decoder = delayedDecoder)
        } }
        try {
            compose.waitUntil(5_000) { entered.count == 0L }
            compose.onNodeWithTag("pdf-close").performClick()
            // Establish disposal/cancellation before allowing the IO decoder to return.
            compose.onNodeWithTag("pdf-preview").assertDoesNotExist()
            compose.runOnIdle { assertTrue(created.all { !it.exists() }) }
        } finally { release.countDown() }
        compose.waitUntil(5_000) { rendered.get()?.isRecycled == true }
        compose.onNodeWithTag("pdf-preview").assertDoesNotExist()
        compose.runOnIdle { assertTrue(created.all { !it.exists() }) }
    }
    @Test fun ordinaryDecoderErrorAfterDisposalDoesNotPublishRecoveryUi() {
        val visible = mutableStateOf(true)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = AtomicBoolean(false)
        val delayedFailure = object : PdfPreviewDecoder {
            override fun pageCount(file: File): Int = 2
            override fun renderPage(file: File, pageIndex: Int): Bitmap {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                finished.set(true)
                throw java.io.IOException("Controlled failure after cancellation")
            }
        }
        compose.setContent { MeBotTheme {
            if (visible.value) PdfResourcePreview("https://example.org/checklist.pdf", "Pending page", { visible.value = false },
                download = { pdf() }, decoder = delayedFailure)
        } }
        try {
            compose.waitUntil(5_000) { entered.count == 0L }
            compose.onNodeWithTag("pdf-close").performClick()
            compose.onNodeWithTag("pdf-preview").assertDoesNotExist()
            compose.runOnIdle { assertTrue(created.all { !it.exists() }) }
        } finally { release.countDown() }
        compose.waitUntil(5_000) { finished.get() }
        compose.onNodeWithTag("pdf-preview").assertDoesNotExist()
        compose.onNodeWithText(compose.activity.getString(mebot.nguli.com.R.string.rich_pdf_failed)).assertDoesNotExist()
    }
    private fun capture() {
        compose.mainClock.advanceTimeBy(1_000); compose.waitForIdle()
        val bitmap = compose.runOnIdle {
            val window = checkNotNull(ShadowDialog.getLatestDialog().window)
            val view = window.decorView; view.invalidate()
            Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also {
                var result: Int? = null
                PixelCopy.request(window, it, { status -> result = status }, Handler(Looper.getMainLooper()))
                assertEquals(PixelCopy.SUCCESS, result)
            }
        }
        File("build/outputs/screenshots/085-fullscreen-pdf.png").also { it.parentFile!!.mkdirs(); it.outputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) } }
        bitmap.recycle()
    }
}
