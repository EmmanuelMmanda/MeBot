package mebot.nguli.com.ui

import androidx.lifecycle.ViewModelProvider
import mebot.nguli.com.feed.FeedViewModel
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import mebot.nguli.com.MainActivity
import mebot.nguli.com.R
import mebot.nguli.com.core.*
import mebot.nguli.com.feed.FeedJson
import mebot.nguli.com.feed.FeedTestApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowDialog
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.LocalDate
import java.time.ZoneOffset

/** Full Activity showcase renders from a checked-in, source-attributed fixture pack, never live data. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi", application = FeedTestApplication::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MeBotShowcase098JourneyTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val app get() = compose.activity.application as FeedTestApplication

    @Test fun sourcePackRendersAcrossLocalizedThemesAndActualAppearanceControls() {
        compose.waitForIdle()
        val now = System.currentTimeMillis()
        val items = showcaseItems(now, "en")
        assertEquals("Only the eight verified sourced items are included; the editorial prompt is not rendered as a story", 8, items.size)
        assertTrue(items.all { FeedLogic.safeUrl(it.sourceUrl, httpsOnly = true) && it.sourceName.isNotBlank() })
        val initialFeedState = FeedState(onboardingComplete = true, interestsConfigured = true, quickGuideSeen = true,
            timezone = "Africa/Dar_es_Salaam", modules = listOf("news", "opportunities", "events", "technology").map { id ->
                FeedModule(id = id, kind = id, enabled = true)
            }, items = items, lastRefreshMillis = now, lastAiRefreshMillis = 0)
        FeedLogic.validate(initialFeedState)
        runBlocking {
            app.feedEngine.disconnect()
            app.sourceItems = emptyList()
            app.repository.update {
                AppState(profile = Profile(onboardingComplete = true), settings = Settings(language = "en", theme = "light", accent = "ember", skin = "classic"))
            }
            app.feedRepository.update { initialFeedState }
        }
        waitForTag("feed-screen")
        compose.waitUntil(15_000) { runBlocking { app.feedRepository.current() }.items.size == 8 }
        compose.onNodeWithTag("setup-tip-later-missing_ai").performScrollTo().performClick()
        compose.waitUntil(10_000) {
            runBlocking { app.feedRepository.current() }.dismissedNotices["missing_ai"] != null &&
                compose.onAllNodesWithTag("setup-notice-missing_ai").fetchSemanticsNodes().isEmpty()
        }
        assertEquals("Static showcase fixture must not make provider requests", 0, runBlocking { app.feedRepository.current() }.requestsToday)
        assertEquals(0, app.suggestionCalls)
        assertEquals(0, app.answerCalls)

        capture("en-feed-light-ember")

        setAppearance(language = "sw", theme = "light", accent = "sage", skin = "paper", capturedAt = now)
        compose.onNodeWithTag("filter-today").performScrollTo().performClick()
        val viewModel = ViewModelProvider(compose.activity)[FeedViewModel::class.java]
        // Localising item text invalidates selection revisions. Wait for the same emitted
        // projection used by the UI, not merely non-empty IDs from the previous language.
        compose.waitUntil(15_000) {
            val renderedState = viewModel.ui.value.state ?: return@waitUntil false
            PersonalDailySelectionLogic098.resolve(renderedState, now).isNotEmpty()
        }
        compose.onNodeWithTag("filter-today").assertIsSelected()
        scrollFeedTo("today-personal-selection-heading")
        compose.onNodeWithTag("today-personal-selection-heading").assertIsDisplayed()
        // None of the pack's public stories is dated 28 October. Do not relabel a fetch as news from today.
        assertTrue("Verified source-day remains empty when the fixture has no 28 October publication", TodayFeed.edition(runBlocking { app.feedRepository.current() }, now).isEmpty())
        capture("sw-today-light-sage")
        scrollFeedTo("today-verified-section-heading")
        compose.onNodeWithTag("today-verified-section-heading").assertIsDisplayed()
        capture("sw-today-verified-light-sage")

        setAppearance(language = "en", theme = "dark", accent = "slate", skin = "classic", capturedAt = now)
        compose.onNodeWithTag("filter-technology").performScrollTo().performClick()
        openStory("tcra-q4-2026-telecom-snapshot")
        compose.onNodeWithTag("detail-source").assertIsDisplayed()
        compose.onNodeWithTag("detail-save").assertIsDisplayed()
        compose.onNodeWithText("117.0 million", substring = true).assertExists()
        capture("en-detail-dark-slate")

        compose.onNodeWithContentDescription(compose.activity.getString(R.string.back)).performClick()
        waitForTag("feed-screen")
        setAppearance(language = "sw", theme = "dark", accent = "teal", skin = "tinted", capturedAt = now)
        compose.onNodeWithTag("filter-opportunities").performScrollTo().performClick()
        openStory("fsdt-impact-grant-fund-cycle-2-2026")
        compose.onNodeWithTag("detail-source").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("detail-save").assertIsDisplayed()
        compose.onNodeWithText("hadi shilingi milioni 30", substring = true).assertExists()
        compose.onNodeWithTag("detail-story-notes").performScrollTo().performClick()
        compose.onNodeWithText("Mwisho · 2026-11-05").assertExists()
        capture("sw-opportunity-dark-teal")

        compose.onNodeWithContentDescription("Rudi").performClick()
        waitForTag("feed-screen")
        compose.onNodeWithTag("nav-you").performClick()
        setAppearance(language = "en", theme = "light", accent = "violet", skin = "classic", capturedAt = now)
        compose.onNodeWithText(compose.activity.getString(R.string.personalization_092)).performScrollTo().performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.appearance)).performClick()
        compose.onNodeWithTag("accent-violet").assertExists()
        compose.onNodeWithTag("skin-classic").assertExists()
        capture("en-appearance-light-violet", dialog = true)
        // The real Appearance dialog scrolls: capture its lower page-style choices as a separate full-shell image.
        compose.onNodeWithTag("skin-oled").performScrollTo()
        compose.onNodeWithTag("skin-classic").assertIsDisplayed()
        compose.onNodeWithTag("skin-paper").assertIsDisplayed()
        compose.onNodeWithTag("skin-tinted").assertIsDisplayed()
        compose.onNodeWithTag("skin-oled").assertIsDisplayed()
        capture("en-appearance-page-style-light-violet", dialog = true)

        assertEquals("No capture interaction calls the answer provider", 0, app.answerCalls)
        assertEquals("No capture interaction generates AI suggestions", 0, app.suggestionCalls)
        assertEquals("The feed remains available without a connected AI account", 0, runBlocking { app.feedRepository.current() }.requestsToday)
    }

    private fun showcaseItems(now: Long, language: String): List<FeedItem> {
        val stream = requireNotNull(javaClass.classLoader?.getResourceAsStream("v098/showcase-content-pack.json"))
        val pack = stream.bufferedReader().use { FeedJson.parseToJsonElement(it.readText()).jsonObject }
        val items = pack.getValue("items").jsonArray
        return items.mapNotNull { value ->
            val o = value.jsonObject
            val category = o.getValue("category").jsonPrimitive.content
            if (category == "evergreen_reading") return@mapNotNull null
            val module = when (category) {
                "opportunity" -> "opportunities"
                "events" -> "events"
                "news" -> "news"
                "technology_data" -> "technology"
                else -> return@mapNotNull null
            }
            val id = o.getValue("id").jsonPrimitive.content
            val sourceDate = o["source_date"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content
                ?.let { Regex("\\d{4}-\\d{2}-\\d{2}").find(it)?.value }
            val published = sourceDate?.let { LocalDate.parse(it).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli() } ?: 0L
            val deadline = if (category == "opportunity") o["event_or_deadline_date"]?.jsonPrimitive?.content
                ?.let { Regex("\\d{4}-\\d{2}-\\d{2}").find(it)?.value }.orEmpty() else ""
            FeedItem(
                id = id, moduleId = module, kind = module,
                title = o.getValue("display_title_$language").jsonPrimitive.content,
                summary = o.getValue("summary_$language").jsonPrimitive.content,
                sourceName = o.getValue("publisher").jsonPrimitive.content,
                sourceUrl = o.getValue("source_url").jsonPrimitive.content,
                publishedAtMillis = published, fetchedAtMillis = 0L,
                contentType = when (category) {
                    "opportunity" -> "opportunity"
                    "events" -> "event"
                    else -> "article"
                },
                deadline = deadline,
                nextStep = o.getValue("next_action_$language").jsonPrimitive.content,
                caveat = "",
                temporal = if (published > 0) FeedTemporal(TemporalProvenance.PUBLISHER, publishedAtMillis = published) else null,
            )
        }
    }

    private fun setAppearance(language: String, theme: String, accent: String, skin: String, capturedAt: Long) {
        runBlocking {
            app.repository.update { state -> state.copy(settings = state.settings.copy(language = language, theme = theme, accent = accent, skin = skin)) }
            app.feedRepository.update { state ->
                val localized = showcaseItems(capturedAt, language).associateBy { it.id }
                state.copy(items = state.items.map { old -> localized[old.id]?.copy(fetchedAtMillis = old.fetchedAtMillis) ?: old })
            }
        }
        compose.waitUntil(10_000) { runBlocking { app.repository.current() }.settings.let { it.language == language && it.theme == theme && it.accent == accent && it.skin == skin } }
        compose.waitForIdle()
    }

    private fun openStory(id: String) {
        compose.onNodeWithTag("feed-screen").performScrollToNode(hasTestTag("item-$id"))
        compose.onNodeWithTag("item-$id").assertIsDisplayed().performClick()
        waitForTag("detail-screen")
        compose.onNodeWithText(runBlocking { app.feedRepository.current() }.items.single { it.id == id }.title).assertExists()
    }

    private fun scrollFeedTo(tag: String) = compose.onNodeWithTag("feed-screen").performScrollToNode(hasTestTag(tag))

    private fun waitForTag(tag: String) {
        compose.waitUntil(15_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun capture(name: String, dialog: Boolean = false) {
        compose.waitForIdle()
        val activityWindow = compose.activity.window
        val view = activityWindow.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        var status = -1
        compose.runOnIdle { PixelCopy.request(activityWindow, bitmap, { status = it }, Handler(Looper.getMainLooper())) }
        compose.waitUntil(10_000) { status != -1 }
        assertEquals(PixelCopy.SUCCESS, status)
        if (dialog) {
            val activeDialog = requireNotNull(ShadowDialog.getLatestDialog()) { "Appearance options must render in the actual Compose dialog window" }
            assertTrue(activeDialog.isShowing)
            val window = requireNotNull(activeDialog.window)
            val dialogView = window.decorView
            val overlay = Bitmap.createBitmap(dialogView.width, dialogView.height, Bitmap.Config.ARGB_8888)
            var dialogStatus = -1
            compose.runOnIdle { PixelCopy.request(window, overlay, { dialogStatus = it }, Handler(Looper.getMainLooper())) }
            compose.waitUntil(10_000) { dialogStatus != -1 }
            assertEquals(PixelCopy.SUCCESS, dialogStatus)
            val location = IntArray(2)
            dialogView.getLocationOnScreen(location)
            Canvas(bitmap).drawBitmap(overlay, location[0].toFloat(), location[1].toFloat(), Paint(Paint.ANTI_ALIAS_FLAG))
            overlay.recycle()
        }
        val output = File("build/outputs/screenshots/v098-showcase/$name.png")
        requireNotNull(output.parentFile).mkdirs()
        output.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }
}
