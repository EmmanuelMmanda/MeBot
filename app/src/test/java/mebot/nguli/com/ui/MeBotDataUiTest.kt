package mebot.nguli.com.ui

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import mebot.nguli.com.R
import mebot.nguli.com.core.*
import mebot.nguli.com.feed.FeedImportPreview
import mebot.nguli.com.feed.FeedUiState
import mebot.nguli.com.feed.OperationDiagnostic
import mebot.nguli.com.ui.feed.FeedDataContent
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
class MeBotDataUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val original = FeedState(name = "Example", modules = listOf(FeedModule(id = "news", kind = "news")),
        context = listOf(ContextEntry(id = "example-context", text = "Example reading preference")),
        viewedAtMillis = mapOf("example-one" to 1L, "example-two" to 2L))
    private val ui = mutableStateOf(FeedUiState(state = original, loading = false))
    private var pickRequests = 0
    private var importRequests = 0
    private var cancelRequests = 0
    private var clearRequests = 0
    private val historyChanges = mutableListOf<Boolean>()

    @Test fun fileSelectionStartsImmediatelyAndAValidatedPreviewCanBeCancelledWithoutReplacement() {
        show()
        node("data-import").performClick()
        assertEquals(1, pickRequests)
        compose.onNodeWithTag("import-review").assertDoesNotExist()
        compose.runOnIdle { ui.value = ui.value.copy(importWorking = true) }
        node("data-import").assertIsNotEnabled()
        node("data-export").assertIsNotEnabled()
        node("data-history-switch").assertIsNotEnabled()
        assertEquals(original, ui.value.state)
        compose.runOnIdle { ui.value = ui.value.copy(importWorking = false,
            importPreview = FeedImportPreview("context", "example-context.json", 3, 0)) }
        compose.onNodeWithTag("import-file-name").assertTextEquals("example-context.json")
        compose.onNodeWithText(text(R.string.imports_modules_keep)).assertExists()
        assertEquals(0, importRequests)
        compose.onNodeWithTag("import-cancel").performClick()
        assertEquals(1, cancelRequests)
        assertEquals(original, ui.value.state)
        compose.onNodeWithTag("import-review").assertDoesNotExist()
    }

    @Test fun importFailureKeepsTheReviewedFileAndWorkingStateBlocksDuplicateSubmissions() {
        ui.value = ui.value.copy(importPreview = FeedImportPreview("backup", "example-backup.json", 3, 16, 4, 2))
        show()
        compose.onNodeWithText(text(R.string.imports_conversations)).assertExists()
        compose.onNodeWithText(text(R.string.imports_backup_impact)).assertExists()
        compose.onNodeWithTag("import-confirm").performClick()
        assertEquals(1, importRequests)
        compose.onNodeWithTag("import-confirm").assertIsNotEnabled()
        compose.onNodeWithTag("import-cancel").assertIsNotEnabled()
        compose.runOnIdle { ui.value = ui.value.copy(importWorking = false, error = "storage_error") }
        compose.onNodeWithTag("import-file-name").assertTextEquals("example-backup.json")
        compose.onNodeWithTag("import-confirm").assertIsEnabled().performClick()
        assertEquals(2, importRequests)
        assertEquals(original, ui.value.state)
        compose.runOnIdle { ui.value = ui.value.copy(importWorking = false, error = null, importPreview = null, importSummary = "backup") }
        compose.onNodeWithTag("import-review").assertDoesNotExist()
        node("import-success").assertIsDisplayed()
    }

    @Test fun historySettingsWaitForPersistenceAndClearFailureCanBeRetried() {
        show()
        node("data-history-switch").assertContentDescriptionEquals(text(R.string.imports_history_title)).assertIsOn().performClick()
        assertEquals(listOf(false), historyChanges)
        node("data-history-switch").assertIsOn().assertIsNotEnabled()
        compose.runOnIdle { ui.value = ui.value.copy(state = original.copy(readingHistoryEnabled = false)) }
        node("data-history-switch").assertIsOff().assertIsEnabled()
        node("data-history-clear").performClick()
        assertEquals(0, clearRequests)
        compose.onNodeWithTag("history-clear-cancel").performClick()
        assertEquals(0, clearRequests)
        node("data-history-clear").performClick()
        compose.onNodeWithTag("history-clear-confirm").performClick()
        assertEquals(1, clearRequests)
        node("data-history-clear").assertIsNotEnabled()
        compose.runOnIdle { ui.value = ui.value.copy(error = "storage_error") }
        node("data-history-clear").assertIsEnabled().performClick()
        compose.onNodeWithTag("history-clear-confirm").performClick()
        assertEquals(2, clearRequests)
        compose.runOnIdle { ui.value = ui.value.copy(state = ui.value.state!!.copy(viewedAtMillis = emptyMap())) }
        node("data-history-clear").assertIsNotEnabled()
        assertEquals(original.context, ui.value.state!!.context)
        assertEquals(original.modules, ui.value.state!!.modules)
        assertFalse(ui.value.state!!.readingHistoryEnabled)
        screenshot("53-mebot-data-controls-v06")
    }

    @Test fun troubleshootingIsCollapsedAndShowsOnlyOperationCodeAndCorrelation() {
        ui.value = ui.value.copy(diagnostics = listOf(OperationDiagnostic("backup_import", "import_invalid",
            moduleId = "Private module label", correlationId = "abcd1234", atMillis = 1L)))
        show()
        compose.onNodeWithTag("data-diagnostics-content").assertDoesNotExist()
        node("data-diagnostics").performClick()
        node("data-diagnostics-content").assertIsDisplayed()
        compose.onNodeWithText("backup_import · import_invalid\nID abcd1234").assertExists()
        compose.onNodeWithText("Private module label", substring = true).assertDoesNotExist()
        node("data-diagnostics").performClick()
        compose.onNodeWithTag("data-diagnostics-content").assertDoesNotExist()
    }

    private fun show() {
        compose.setContent {
            MeBotTheme { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                FeedDataContent(ui.value, onExport = {}, onImport = { pickRequests++ }, onRestoreHidden = {}, onLocalData = {},
                    onHistoryChanged = { historyChanges += it }, onClearHistory = { clearRequests++ },
                    onDismissError = { ui.value = ui.value.copy(error = null) },
                    onConfirmImport = { importRequests++; ui.value = ui.value.copy(importWorking = true, error = null) },
                    onCancelImport = { cancelRequests++; ui.value = ui.value.copy(importPreview = null) })
            } }
        }
    }

    private fun node(tag: String) = compose.onNodeWithTag(tag).performScrollTo()
    private fun text(id: Int) = compose.activity.getString(id)
    private fun screenshot(name: String) {
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
        val image = compose.runOnIdle {
            val window = compose.activity.window
            val view = window.decorView
            view.invalidate()
            Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also {
                var result: Int? = null
                PixelCopy.request(window, it, { status -> result = status }, Handler(Looper.getMainLooper()))
                assertEquals(PixelCopy.SUCCESS, result)
            }
        }
        val file = File("build/outputs/screenshots/$name.png")
        file.parentFile!!.mkdirs()
        file.outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }
}
