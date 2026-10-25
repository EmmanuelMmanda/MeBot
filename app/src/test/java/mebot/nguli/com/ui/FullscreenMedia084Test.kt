package mebot.nguli.com.ui

import android.webkit.WebView
import android.webkit.WebChromeClient
import android.view.View
import android.view.ViewGroup
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import kotlinx.coroutines.awaitCancellation
import org.robolectric.shadows.ShadowDialog
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import mebot.nguli.com.R
import mebot.nguli.com.core.FeedCitation
import mebot.nguli.com.ui.feed.*
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FullscreenMedia084Test {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private fun fixture(): Bitmap = Bitmap.createBitmap(1000, 700, Bitmap.Config.ARGB_8888).also {
        val canvas = Canvas(it); canvas.drawColor(Color.rgb(30, 61, 78))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(139, 204, 186) }
        canvas.drawCircle(750f, 200f, 105f, paint)
        paint.color = Color.rgb(72, 128, 133); canvas.drawRect(0f, 400f, 1000f, 700f, paint)
        paint.color = Color.WHITE; paint.textSize = 42f
        canvas.drawText("Example image · local test fixture", 60f, 570f, paint)
    }
    @Test fun imageOpensNativeFullscreenCanResetAndClose() {
        val bitmap = fixture()
        compose.setContent { MeBotTheme { CompositionLocalProvider(LocalPublisherImageLoader provides { bitmap }) {
            RichSourcePreview(FeedCitation("Example landscape", "https://example.org/landscape.jpg"))
        } } }
        compose.onNodeWithTag("media-open-fullscreen").performClick()
        compose.onNodeWithTag("media-fullscreen").assertExists()
        compose.onNodeWithTag("media-fullscreen-image").assertExists()
        compose.onNodeWithTag("media-zoom-reset").performClick()
        capture("084-fullscreen-image")
        compose.onNodeWithTag("media-fullscreen-close").performClick()
        compose.onNodeWithTag("media-fullscreen").assertDoesNotExist()
    }
    @Test fun knownPublisherImageWithoutFilenameExtensionStillRenders() {
        val bitmap = fixture()
        compose.setContent { MeBotTheme { CompositionLocalProvider(LocalPublisherImageLoader provides { bitmap }) {
            FullscreenMediaPreview("https://example.org/media?id=123", "Publisher image", {}, image = true)
        } } }
        compose.onNodeWithTag("media-fullscreen-image").assertExists()
        compose.onNodeWithText(compose.activity.getString(R.string.media_original_quality)).assertDoesNotExist()
    }
    @Test fun failedImageShowsRetryAndCloseWithoutInventedPicture() {
        val visible = mutableStateOf(true)
        compose.setContent { MeBotTheme { CompositionLocalProvider(LocalPublisherImageLoader provides { null }) {
            if (visible.value) FullscreenMediaPreview("https://example.org/missing.jpg", "Missing image", { visible.value = false })
        } } }
        compose.onNodeWithText(compose.activity.getString(R.string.resource_preview_failed)).assertExists()
        compose.onNodeWithText(compose.activity.getString(R.string.feed_retry)).performClick()
        compose.onNodeWithTag("media-fullscreen-image").assertDoesNotExist()
        compose.onNodeWithTag("media-fullscreen-close").performClick()
        compose.onNodeWithTag("media-fullscreen").assertDoesNotExist()
    }
    @Test fun backDismissesFullscreenAndCancelsPendingImage() {
        val visible = mutableStateOf(true)
        var cancelled = false
        compose.setContent { MeBotTheme { CompositionLocalProvider(LocalPublisherImageLoader provides {
            try { awaitCancellation() } finally { cancelled = true }
        }) {
            if (visible.value) FullscreenMediaPreview("https://example.org/slow.jpg", "Pending image", { visible.value = false })
        } } }
        compose.onNodeWithTag("media-fullscreen").assertExists()
        compose.runOnIdle { ShadowDialog.getLatestDialog().onBackPressed() }
        compose.onNodeWithTag("media-fullscreen").assertDoesNotExist()
        compose.runOnIdle { assertTrue(cancelled) }
    }
    @Test fun youtubeProviderFullscreenKeepsTheSamePlayerAndReleasesCallback() {
        compose.setContent { MeBotTheme { OfficialMediaPlayer("https://www.youtube.com/watch?v=M7lc1UVf-VE") } }
        fun findPlayer(view: View): WebView? = when (view) {
            is WebView -> view
            is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { findPlayer(view.getChildAt(it)) }
            else -> null
        }
        val original = compose.runOnIdle { checkNotNull(findPlayer(compose.activity.window.decorView)) }
        var hidden = 0
        compose.onNodeWithTag("media-open-fullscreen").assertDoesNotExist()
        compose.runOnIdle {
            original.webChromeClient!!.onShowCustomView(View(compose.activity), object : WebChromeClient.CustomViewCallback {
                override fun onCustomViewHidden() { hidden++ }
            })
        }
        compose.onNodeWithTag("media-fullscreen").assertExists()
        compose.onNodeWithTag("media-fullscreen-close").performClick()
        compose.onNodeWithTag("media-fullscreen").assertDoesNotExist()
        compose.runOnIdle {
            assertSame(original, findPlayer(compose.activity.window.decorView))
            assertEquals(1, hidden)
        }
    }
    @Test fun directVideoShowsHonestSingleSourceQuality() {
        compose.setContent { MeBotTheme {
            FullscreenMediaPreview("https://example.org/sample.mp4", "Example video", {})
        } }
        compose.onNodeWithText(compose.activity.getString(R.string.media_original_quality)).assertExists()
        compose.onNodeWithText("1080p").assertDoesNotExist()
        compose.onNodeWithTag("media-fullscreen-close").assertExists()
    }
    private fun capture(name: String) {
        compose.mainClock.advanceTimeBy(1_000); compose.waitForIdle()
        val bitmap = compose.runOnIdle {
            val window = checkNotNull(ShadowDialog.getLatestDialog().window)
            val view = window.decorView
            view.invalidate()
            Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also {
                var result: Int? = null
                PixelCopy.request(window, it, { status -> result = status }, Handler(Looper.getMainLooper()))
                assertEquals(PixelCopy.SUCCESS, result)
            }
        }
        File("build/outputs/screenshots/$name.png").also { it.parentFile!!.mkdirs(); it.outputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) } }
        bitmap.recycle()
    }
}
