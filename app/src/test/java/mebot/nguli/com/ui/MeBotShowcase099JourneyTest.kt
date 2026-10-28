package mebot.nguli.com.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import androidx.lifecycle.ViewModelProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import mebot.nguli.com.MainActivity
import mebot.nguli.com.R
import mebot.nguli.com.core.*
import mebot.nguli.com.feed.FeedJson
import mebot.nguli.com.feed.FeedTestApplication
import mebot.nguli.com.feed.FeedViewModel
import mebot.nguli.com.feed.SuggestionTargetSnapshot097
import mebot.nguli.com.feed.SuggestionTargetKind097
import mebot.nguli.com.feed.SuggestionSheetStatus097
import mebot.nguli.com.feed.SuggestionTargets097
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/** Full-shell marketing sample from checked-in data and a deterministic in-process provider only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi", application = FeedTestApplication::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MeBotShowcase099JourneyTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val app get() = compose.activity.application as FeedTestApplication

    @Before fun resetFixture() {
        compose.waitForIdle()
        runBlocking {
            app.feedEngine.disconnect()
            app.repository.erase()
            app.feedRepository.erase()
        }
        app.sourceItems = emptyList()
        app.sourceFailure = null
        app.suggestionQuestions = questionsEn
        app.suggestionGate = null
        app.onSuggestionStarted = null
        app.suggestionCalls = 0
        app.suggestionRequestTargets.clear()
        app.answerCalls = 0
    }

    @Test fun feedAndOpportunityCaptureShowForYouAndVerifiedDeadline() {
        prepareShowcase()

        compose.onNodeWithTag("feed-screen").assertIsDisplayed()
        compose.onNodeWithTag("item-$FSDT_ID").assertIsDisplayed()
        compose.onNodeWithText("Closes 5 Nov 2026", substring = true, useUnmergedTree = true).assertExists()
        compose.onNodeWithText(text(R.string.feed_time_unavailable), useUnmergedTree = true).assertIsNotDisplayed()
        capture("en-feed-for-you")
        compose.onNodeWithTag("opportunity-deadline-$FSDT_ID", useUnmergedTree = true).assertExists()
            .assertTextContains("Closes 5 Nov 2026", substring = true)
        assertTrue("The For You selection includes the source-verified FSDT opportunity",
            state().personalDailySelection098?.itemIds?.contains(FSDT_ID) == true &&
                state().items.single { it.id == FSDT_ID }.let {
                    it.sourceName == "Financial Sector Deepening Tanzania (FSDT)" &&
                        it.deadline == "2026-11-05" && it.publishedAtMillis == 0L
                })
        // The card and detail show only the source-backed close date, never an invented publication time.

        compose.onNodeWithTag("item-$FSDT_ID").performClick()
        waitForTag("detail-screen")
        compose.waitUntil(15_000) { state().briefs.any { it.itemId == FSDT_ID } && app.answerCalls == 1 }
        compose.onNodeWithTag("detail-source").performScrollTo().assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText(text(R.string.feed_time_unavailable)).assertIsNotDisplayed()
        assertTrue("The known FSDT deadline is retained as an event date, not publication time",
            state().items.single { it.id == FSDT_ID }.let { it.deadline == "2026-11-05" && it.publishedAtMillis == 0L })
        capture("en-fsdt-opportunity-detail-source")
        compose.onNodeWithTag("detail-story-notes").performScrollTo().performClick()
        compose.onNodeWithTag("detail-opportunity-deadline").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Deadline · 2026-11-05", substring = true).assertIsDisplayed()
        capture("en-fsdt-opportunity-deadline")
        assertEquals("Only the synthetic FSDT detail brief was requested", 1, app.answerCalls)
    }

    @Test fun preparedQuestionsShowFiveDistinctAnglesAndEditableDraft() {
        val viewModel = prepareShowcase()

        val requestsBeforeAngles = state().requestsToday
        val target = requireNotNull(SuggestionTargets097.item(state(), FSDT_ID, "en"))
        val cached = viewModel.suggestionSheet097(target, "en")
        assertEquals("The exact target is already prepared before the sheet opens", SuggestionSheetStatus097.READY, cached.status)
        compose.onNodeWithTag("feed-screen").performScrollToNode(hasTestTag("feed-suggestions-$FSDT_ID-open"))
        val openButton = compose.onNodeWithTag("feed-suggestions-$FSDT_ID-open")
            .assertIsDisplayed().assertHasClickAction()
        val openBounds = openButton.fetchSemanticsNode().boundsInRoot
        openButton.performTouchInput { click() }
        val waitFailure = runCatching { waitForTag("feed-suggestions-$FSDT_ID-sheet") }.exceptionOrNull()
        if (waitFailure != null) {
            val latestTarget = SuggestionTargets097.item(state(), FSDT_ID, "en")
            val latestSnapshot = latestTarget?.let { viewModel.suggestionSheet097(it, "en") }
            val dialog = runCatching { ShadowDialog.getLatestDialog()?.takeIf { it.isShowing } }.getOrNull()
            throw AssertionError(
                "Suggestion sheet did not mount after explicit pointer tap; bounds=$openBounds, " +
                    "preTap=${cached.status}, postTap=${latestSnapshot?.status}, connected=${viewModel.ui.value.connected}, " +
                    "providerCalls=${app.suggestionCalls}, answerCalls=${app.answerCalls}, " +
                    "dialog=${dialog?.javaClass?.simpleName ?: "none"}, " +
                    "tree=${compose.onRoot(useUnmergedTree = true).printToString().take(6000)}",
                waitFailure,
            )
        }
        val prepared = viewModel.suggestionSheet097(target, "en")
        assertEquals(SuggestionSheetStatus097.READY, prepared.status)
        assertEquals(5, prepared.options.size)
        assertEquals(questionsEn, prepared.options.map { it.questionText })
        questionsEn.forEachIndexed { index, question ->
        compose.onNodeWithTag("feed-suggestions-$FSDT_ID-question-$index", useUnmergedTree = true)
            .assertExists().assertTextEquals(question)
        }
        assertEquals("Opening the prepared sheet is read-only", requestsBeforeAngles, state().requestsToday)
        capture("en-five-questions-top", dialog = true)
        compose.onNodeWithTag("feed-suggestions-$FSDT_ID-content", useUnmergedTree = true)
            .performScrollToNode(hasTestTag("feed-suggestions-$FSDT_ID-send-4"))
        compose.onNodeWithTag("feed-suggestions-$FSDT_ID-question-4", useUnmergedTree = true).assertIsDisplayed()
        capture("en-five-questions-bottom", dialog = true)

        // Pencil transfers the chosen exact prompt to the editable composer without sending it.
        compose.onNodeWithTag("feed-suggestions-$FSDT_ID-edit-0", useUnmergedTree = true).performScrollTo().performClick()
        waitForTag("workspace-question")
        assertEquals("Edit is a draft, not an answer request", 0, app.answerCalls)
        val conversationPreparation = awaitPreparedConversationSuggestion(
            viewModel = viewModel,
            conversationId = null,
            expectedPrompt = questionsEn.first(),
            expectedRequests = requestsBeforeAngles + 1,
            expectedSuggestionCalls = 2,
        )
        val newConversationId = conversationPreparation.targetId
        assertEquals("The draft opened the exact FSDT-scoped conversation target",
            FSDT_ID, conversationPreparation.itemId)
        assertEquals(SuggestionTargetKind097.CONVERSATION, conversationPreparation.kind)
        assertEquals("The draft stays in the opportunity module", "opportunities", conversationPreparation.moduleId)
        assertTrue("Prepared conversation context includes the confirmed, scoped sample note", state().context.any {
            it.confirmed && it.text.contains("practical digital tools") && "opportunities" in it.scopes
        })
        val thread = requireNotNull(state().conversations.singleOrNull { it.id == newConversationId })
        assertTrue("Pencil did not append a user or assistant turn", thread.turns.isEmpty())
        assertEquals("Exactly one item batch and one linked-conversation batch reached the provider",
            listOf(FSDT_ID to null, FSDT_ID to newConversationId), app.suggestionRequestTargets.toList())
        assertEquals("Only the route-owned new conversation preparation adds one bounded request",
            requestsBeforeAngles + 1, state().requestsToday)
        assertEquals("No chat answer was sent", 0, app.answerCalls)
        capture("en-question-edit-draft")

        val requestsBeforeComposerCacheOpen = state().requestsToday
        val callsBeforeComposerCacheOpen = app.suggestionCalls
        compose.onNodeWithTag("workspace-suggestions-open").assertIsDisplayed().performClick()
        waitForTag("workspace-suggestions-sheet")
        assertEquals("The composer sheet reuses the same prepared conversation questions", questionsEn,
            viewModel.suggestionSheet097(conversationPreparation, "en").options.map { it.questionText })
        assertEquals("Opening cached composer suggestions is read-only", requestsBeforeComposerCacheOpen, state().requestsToday)
        assertEquals("Opening cached composer suggestions makes no provider call", callsBeforeComposerCacheOpen, app.suggestionCalls)
        compose.onNodeWithTag("workspace-suggestions-close").performClick()
        compose.onNodeWithTag("workspace-question").assertTextContains(questionsEn.first())

        // Reopening the same active conversation must reuse its prepared five questions.
        val stableRequests = state().requestsToday
        val stableSuggestionCalls = app.suggestionCalls
        compose.onNodeWithTag("nav-feed").performClick()
        waitForTag("feed-screen")
        compose.onNodeWithTag("nav-ai").performClick()
        waitForTag("workspace-question")
        compose.waitForIdle()
        assertEquals("Returning to the active draft target does not generate again", stableRequests, state().requestsToday)
        assertEquals("Returning to the active draft target does not call the provider again", stableSuggestionCalls, app.suggestionCalls)
        val returnedTarget = awaitPreparedConversationSuggestion(
            viewModel, newConversationId, questionsEn.first(), stableRequests, stableSuggestionCalls)
        assertEquals("The exact same revision remains active after navigation", conversationPreparation.revision, returnedTarget.revision)
        assertEquals("The unsent selected prompt remains exact after back/navigation", questionsEn.first(),
            compose.onNodeWithTag("workspace-question").fetchSemanticsNode().config
                [androidx.compose.ui.semantics.SemanticsProperties.EditableText].text)
    }

    @Test fun originalReadCapturesEnglishSwahiliAndSourceDisclosure() {
        prepareShowcase()

        compose.onNodeWithTag("filter-all").performScrollTo().performClick()
        compose.onNodeWithTag("feed-screen").performScrollToNode(hasTestTag("item-$ORIGINAL_READ_ID"))
        compose.onNodeWithTag("item-$ORIGINAL_READ_ID").performClick()
        waitForTag("detail-screen")
        compose.onNodeWithTag("detail-original-read-authorship").assertIsDisplayed()
        compose.onNodeWithTag("detail-original-read-created").assertIsDisplayed().assertTextContains("2026", substring = true)
        compose.onNodeWithText("A Small Kiswahili-First AI Pilot: A Practical Reflection").assertIsDisplayed()
        compose.waitUntil(15_000) {
            val latest = state()
            val readTarget = SuggestionTargets097.item(latest, ORIGINAL_READ_ID, "en")
            readTarget != null && latest.suggestionSets097.any {
                it.targetKind == "item" && it.targetId == ORIGINAL_READ_ID && it.revision == readTarget.revision
            } && app.suggestionCalls >= 2
        }
        capture("en-original-read")
        compose.onNodeWithTag("detail-tab-sources").performScrollTo().performClick()
        compose.onNodeWithTag("detail-original-read-sources-further-reading-0").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("UNESCO and the United Republic of Tanzania", substring = true).assertIsDisplayed()
        capture("en-original-read-further-reading")

        // Actual localized app theme + content; the fixture stays a reflection, not reporting.
        app.suggestionQuestions = questionsSw
        updateAppearance(language = "sw", theme = "dark", accent = "teal", skin = "tinted")
        waitForTag("detail-screen")
        compose.onNodeWithText("Jaribio Dogo la AI Linaloanza na Kiswahili: Tafakari ya Vitendo").assertIsDisplayed()
        compose.onNodeWithTag("detail-original-read-created").assertIsDisplayed().assertTextContains("2026", substring = true)
        compose.waitUntil(15_000) {
            val latest = state()
            val readTarget = SuggestionTargets097.item(latest, ORIGINAL_READ_ID, "sw")
            readTarget != null && latest.suggestionSets097.any {
                it.targetKind == "item" && it.targetId == ORIGINAL_READ_ID && it.revision == readTarget.revision
            } && app.suggestionCalls >= 3
        }
        capture("sw-original-read-dark-teal")
        assertEquals("Original Read journey does not send a detail answer", 0, app.answerCalls)
    }

    @Test fun aboutYouAndAppearanceCaptureConfirmedContextAndControls() {
        prepareShowcase()

        compose.onNodeWithTag("nav-you").performClick()
        compose.onNodeWithText(text(R.string.preferences_title_083)).performScrollTo().performClick()
        waitForTag("context-name")
        compose.onNodeWithTag("context-edit-$CONTEXT_ID").performScrollTo().assertTextContains("Kiswahili-first", substring = true)
        compose.onNodeWithTag("context-screen").performScrollToNode(hasText(text(R.string.preferences_title_083)))
        compose.onAllNodesWithText(text(R.string.preferences_title_083)).onFirst().assertIsDisplayed()
        capture("en-about-you")
        compose.onNodeWithTag("context-edit-$CONTEXT_ID").performScrollTo()
        compose.onNodeWithTag("context-edit-$CONTEXT_ID").assertTextContains("Kiswahili-first", substring = true)
        compose.onNodeWithTag("context-scopes-$CONTEXT_ID", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        capture("en-about-you-context-note")

        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("nav-you").fetchSemanticsNodes().isNotEmpty() }
        updateAppearance(language = "en", theme = "light", accent = "violet", skin = "classic")
        compose.onNodeWithText(text(R.string.personalization_092)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.appearance)).performScrollTo().performClick()
        compose.onNodeWithTag("accent-violet").performScrollTo().assertExists()
        compose.onNodeWithTag("skin-classic").assertExists()
        capture("en-appearance-controls-light-violet", dialog = true)
        captureDialogWindowOnly("en-appearance-controls-dialog-only")
        // Scroll to the final option so the page-style rows are centered in the native
        // dialog viewport rather than hidden below the accent controls.
        compose.onNodeWithTag("skin-oled").performScrollTo().assertIsDisplayed()
        listOf("skin-classic", "skin-paper", "skin-tinted", "skin-oled").forEach { tag ->
            compose.onNodeWithTag(tag).assertIsDisplayed()
        }
        captureDialogWindowOnly("en-appearance-page-styles-dialog-only")
        assertTrue("All suggestions/answers stay within the fixture budget", state().requestsToday <= 20)
    }

    private fun prepareShowcase(): FeedViewModel {
        val now = System.currentTimeMillis()
        val originalRead = originalRead("en", ORIGINAL_READ_CREATED_AT)
        val items = verifiedPackItems(now, "en") + originalRead
        val context = ContextEntry(
            id = "showcase-practical-tools",
            text = "Interested in practical digital tools, Kiswahili-first resources, open-source software, and affordable technology for small businesses.",
            category = "interest",
            scopes = setOf("news", "opportunities", "technology"),
            confirmed = true,
            origin = "manual",
            updatedAtMillis = now,
        )
        val modules = listOf(
            FeedModule(id = "news", kind = "news", title = "News", query = "Tanzania digital technology"),
            FeedModule(id = "opportunities", kind = "opportunities", title = "Opportunities", query = "Tanzania small-business grants"),
            FeedModule(id = "technology", kind = "technology", title = "Technology", query = "practical digital tools"),
        )
        val initial = FeedState(
            onboardingComplete = true,
            interestsConfigured = true,
            quickGuideSeen = true,
            timezone = "Africa/Dar_es_Salaam",
            modules = modules,
            context = listOf(context),
            items = items,
            lastRefreshMillis = now,
            lastAiRefreshMillis = now,
            dailyRequestLimit = 20,
        )
        FeedLogic.validate(initial)
        assertTrue("Original Read fixture follows the canonical shape", FeedLogic.isValidOriginalReadShape(originalRead))
        assertTrue("The FSDT item is sourced and has the real close date", items.single { it.id == FSDT_ID }.let {
            FeedLogic.safeUrl(it.sourceUrl, httpsOnly = true) && it.deadline == "2026-11-05" && it.publishedAtMillis == 0L
        })
        app.answerText = "FSDT lists applications closing on 5 November 2026. Check the full eligibility rules and current application requirements on the official page."
        app.suggestionQuestions = questionsEn

        updateStoresAndAwait(initial, language = "en", theme = "light", accent = "ember", skin = "classic")
        waitForTag("feed-screen")
        val viewModel = ViewModelProvider(compose.activity)[FeedViewModel::class.java]
        viewModel.connect("synthetic-showcase-credential", model = "gpt-6-luna", mode = "direct")
        val connectionReady = runCatching {
            compose.waitUntil(15_000) {
                // Robolectric can leave a Main-dispatch continuation queued while waitUntil
                // repeatedly polls a StateFlow directly. Drain Compose/Main work each pass.
                compose.waitForIdle()
                viewModel.ui.value.connected && !viewModel.ui.value.connecting
            }
        }.isSuccess
        if (!connectionReady) {
            val latest = viewModel.ui.value
            throw AssertionError(
                "Synthetic in-process connection did not become ready: connected=${latest.connected}, " +
                    "connecting=${latest.connecting}, error=${latest.error}, connection=${state().connection}, " +
                    "requests=${state().requestsToday}, secretPresent=${app.feedSecrets.read() != null}, " +
                    "diagnostics=${latest.diagnostics.map { it.operation + ":" + it.code }}",
            )
        }
        viewModel.ensurePersonalDailySelection098()
        compose.waitUntil(15_000) {
            val latest = state()
            val target = SuggestionTargets097.item(latest, FSDT_ID, "en")
            latest.personalDailySelection098?.itemIds?.contains(FSDT_ID) == true &&
                target != null && latest.suggestionSets097.any {
                    it.targetKind == "item" && it.targetId == FSDT_ID && it.revision == target.revision
                } && app.suggestionCalls == 1
        }
        // Do not let the synthetic connection confirmation toast masquerade as provider identity
        // or live-account status in any gallery capture.
        compose.waitUntil(8_000) {
            compose.waitForIdle()
            compose.onAllNodesWithText(text(R.string.feed_connected)).fetchSemanticsNodes().isEmpty()
        }
        // This sample keeps delivery settings truthful. Dismiss optional feed guidance through
        // its real Later action so the source-backed opportunity card is unobstructed.
        dismissOptionalCaptureTip("background_disabled")
        dismissOptionalCaptureTip("voice_setup")
        assertEquals("Only the deterministic test provider prepared one first batch", 1, app.suggestionCalls)
        assertEquals("Feed preparation targets only the visible FSDT item",
            listOf(FSDT_ID to null), app.suggestionRequestTargets.toList())
        assertEquals("Feed preparation does not run a detail answer", 0, app.answerCalls)
        return viewModel
    }

    private fun awaitPreparedConversationSuggestion(
        viewModel: FeedViewModel,
        conversationId: String?,
        expectedPrompt: String,
        expectedRequests: Int,
        expectedSuggestionCalls: Int,
    ): SuggestionTargetSnapshot097 {
        compose.waitUntil(15_000) {
            compose.waitForIdle()
            val latestUi = viewModel.ui.value
            val latestState = latestUi.state ?: return@waitUntil false
            val activeId = latestUi.activeConversationId ?: return@waitUntil false
            if (conversationId != null && activeId != conversationId) return@waitUntil false
            val target = SuggestionTargets097.conversation(latestState, activeId, "en") ?: return@waitUntil false
            val snapshot = viewModel.suggestionSheet097(target, "en")
            val editorText = runCatching {
                compose.onNodeWithTag("workspace-question").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text
            }.getOrNull()
            snapshot.status == SuggestionSheetStatus097.READY && snapshot.options.size == 5 &&
                latestState.requestsToday == expectedRequests && app.suggestionCalls == expectedSuggestionCalls &&
                app.suggestionRequestTargets.lastOrNull() == (FSDT_ID to activeId) && editorText == expectedPrompt
        }
        val latestUi = viewModel.ui.value
        val latestState = requireNotNull(latestUi.state)
        val conversationId = conversationId ?: requireNotNull(latestUi.activeConversationId)
        assertEquals("The active thread is the draft's linked item conversation", conversationId, latestUi.activeConversationId)
        val target = requireNotNull(SuggestionTargets097.conversation(latestState, conversationId, "en"))
        assertEquals("Conversation suggestions retain item scope", FSDT_ID, target.itemId)
        assertEquals("Conversation suggestions retain module scope", "opportunities", target.moduleId)
        val snapshot = viewModel.suggestionSheet097(target, "en")
        assertEquals(SuggestionSheetStatus097.READY, snapshot.status)
        assertEquals("One prepared conversation batch has five options", 5, snapshot.options.size)
        assertEquals("The active conversation receives the exact draft", expectedPrompt,
            compose.onNodeWithTag("workspace-question").fetchSemanticsNode().config
                [androidx.compose.ui.semantics.SemanticsProperties.EditableText].text)
        assertEquals("Pencil navigation creates no conversation turns", emptyList<AiTurn>(),
            latestState.conversations.single { it.id == conversationId }.turns)
        assertEquals("First call is the feed item; second call carries both item and conversation ids",
            listOf(FSDT_ID to null, FSDT_ID to conversationId), app.suggestionRequestTargets.toList())
        assertEquals(expectedRequests, latestState.requestsToday)
        assertEquals(expectedSuggestionCalls, app.suggestionCalls)
        assertEquals("Suggestion preparation does not dispatch a chat answer", 0, app.answerCalls)
        return target
    }

    private fun verifiedPackItems(now: Long, language: String): List<FeedItem> {
        val stream = requireNotNull(javaClass.classLoader?.getResourceAsStream("v098/showcase-content-pack.json"))
        val pack = stream.bufferedReader().use { FeedJson.parseToJsonElement(it.readText()).jsonObject }
        val ids = setOf(FSDT_ID, "eac-ai-declaration-2026", "pdpc-global-ai-summit-oct-2026")
        return pack.getValue("items").jsonArray.mapNotNull { value ->
            val entry = value.jsonObject
            val id = entry.getValue("id").jsonPrimitive.content
            if (id !in ids) return@mapNotNull null
            val category = entry.getValue("category").jsonPrimitive.content
            val moduleId = if (category == "opportunity") "opportunities" else "news"
            val sourceDate = entry["source_date"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content
                ?.let { Regex("\\d{4}-\\d{2}-\\d{2}").find(it)?.value }
            val publishedAt = sourceDate?.let { LocalDate.parse(it).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() } ?: 0L
            val url = entry.getValue("source_url").jsonPrimitive.content
            FeedItem(
                id = id,
                moduleId = moduleId,
                title = entry.getValue("display_title_$language").jsonPrimitive.content,
                summary = entry.getValue("summary_$language").jsonPrimitive.content,
                why = if (id == FSDT_ID) "An open call for eligible MSMEs; confirm the full rules on the linked FSDT page." else "A source-dated public update.",
                sourceName = entry.getValue("publisher").jsonPrimitive.content,
                sourceUrl = url,
                publishedAtMillis = publishedAt,
                fetchedAtMillis = now,
                kind = moduleId,
                citations = listOf(FeedCitation(entry.getValue("source_title").jsonPrimitive.content, url)),
                deadline = if (id == FSDT_ID) "2026-11-05" else "",
                relevance = when (id) { FSDT_ID -> 100; "pdpc-global-ai-summit-oct-2026" -> 50; else -> 40 },
                contentType = if (id == FSDT_ID) "opportunity" else "article",
                nextStep = if (id == FSDT_ID) entry.getValue("next_action_$language").jsonPrimitive.content else "",
                temporal = when {
                    publishedAt > 0L -> FeedTemporal(TemporalProvenance.PUBLISHER, publishedAtMillis = publishedAt)
                    else -> null
                },
            )
        }
    }

    private fun dismissOptionalCaptureTip(key: String) {
        val laterTag = "setup-tip-later-$key"
        if (compose.onAllNodesWithTag(laterTag).fetchSemanticsNodes().isEmpty()) return
        compose.onNodeWithTag(laterTag).performScrollTo().performClick()
        compose.waitUntil(10_000) {
            compose.waitForIdle()
            compose.onAllNodesWithTag(laterTag).fetchSemanticsNodes().isEmpty()
        }
    }

    private fun originalRead(language: String, createdAt: Long): FeedItem {
        val filename = if (language == "sw") "original-read-sw.json" else "original-read-en.json"
        val stream = requireNotNull(javaClass.classLoader?.getResourceAsStream("v099/$filename"))
        val entry = stream.bufferedReader().use { FeedJson.parseToJsonElement(it.readText()).jsonObject }
        val reading = entry.getValue("furtherReading").jsonArray.map { ref ->
            val source = ref.jsonObject
            FeedCitation(source.getValue("title").jsonPrimitive.content, source.getValue("url").jsonPrimitive.content)
        }
        val sections = entry.getValue("sections").jsonArray.map { section ->
            FeedContentSection(
                heading = section.jsonObject.getValue("heading").jsonPrimitive.content,
                body = section.jsonObject.getValue("body").jsonPrimitive.content,
            )
        }
        return FeedItem(
            id = ORIGINAL_READ_ID,
            moduleId = "technology",
            kind = "technology",
            title = entry.getValue("title").jsonPrimitive.content,
            summary = entry.getValue("summary").jsonPrimitive.content,
            why = entry.getValue("why").jsonPrimitive.content,
            fetchedAtMillis = createdAt,
            publishedAtMillis = 0L,
            aiGenerated = true,
            contentType = "original_read",
            acquisition = FeedAcquisition.ORIGINAL_READ,
            authorship = FeedAuthorship.AI,
            sections = sections,
            furtherReading = reading,
            relevance = 25,
        )
    }

    private fun updateAppearance(language: String, theme: String, accent: String, skin: String) {
        val current = state()
        val localized = originalRead(language, ORIGINAL_READ_CREATED_AT)
        val fromPack = verifiedPackItems(current.items.maxOfOrNull { it.fetchedAtMillis } ?: System.currentTimeMillis(), language)
            .associateBy { it.id }
        updateStoresAndAwait(
            current.copy(items = current.items.map { old ->
                when (old.id) {
                    ORIGINAL_READ_ID -> localized
                    else -> fromPack[old.id]?.copy(fetchedAtMillis = old.fetchedAtMillis) ?: old
                }
            }),
            language = language, theme = theme, accent = accent, skin = skin,
        )
    }

    private fun updateStoresAndAwait(feed: FeedState, language: String, theme: String, accent: String, skin: String) {
        val feedRepository = app.feedRepository
        val appRepository = app.repository
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val update = scope.async {
            appRepository.update { current ->
                current.copy(
                    profile = current.profile.copy(onboardingComplete = true),
                    settings = current.settings.copy(language = language, theme = theme, accent = accent, skin = skin),
                )
            }
            feedRepository.update { feed }
        }
        try {
            compose.waitUntil(15_000) {
                compose.waitForIdle()
                update.isCompleted
            }
            runBlocking { withTimeout(10_000) { update.await() } }
        } finally {
            update.cancel()
            scope.cancel()
            runBlocking { withTimeoutOrNull(1_000) { update.join() } }
        }
    }

    private fun text(resource: Int, vararg args: Any) = compose.activity.getString(resource, *args)
    private fun state() = runBlocking { app.feedRepository.current() }
    private fun waitForTag(tag: String) = compose.waitUntil(15_000) {
        compose.waitForIdle()
        compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    }

    private fun capture(name: String, dialog: Boolean = false) {
        compose.waitForIdle()
        val window = compose.activity.window
        val view = window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        var status = -1
        compose.runOnUiThread { PixelCopy.request(window, bitmap, { status = it }, Handler(Looper.getMainLooper())) }
        compose.waitUntil(10_000) { status != -1 }
        assertEquals("Native window capture succeeds for $name", PixelCopy.SUCCESS, status)
        if (dialog) {
            val activeDialog = requireNotNull(ShadowDialog.getLatestDialog()) { "A native Compose dialog must be present for $name" }
            assertTrue("Dialog window is actually shown for $name", activeDialog.isShowing)
            val dialogWindow = requireNotNull(activeDialog.window)
            val dialogView = dialogWindow.decorView
            val overlay = Bitmap.createBitmap(dialogView.width, dialogView.height, Bitmap.Config.ARGB_8888)
            var dialogStatus = -1
            compose.runOnUiThread {
                PixelCopy.request(dialogWindow, overlay, { dialogStatus = it }, Handler(Looper.getMainLooper()))
            }
            compose.waitUntil(10_000) { dialogStatus != -1 }
            assertEquals("Native dialog-window PixelCopy succeeds for $name", PixelCopy.SUCCESS, dialogStatus)
            val location = IntArray(2)
            dialogView.getLocationOnScreen(location)
            Canvas(bitmap).drawBitmap(overlay, location[0].toFloat(), location[1].toFloat(), Paint(Paint.ANTI_ALIAS_FLAG))
            overlay.recycle()
        }
        val file = File("build/outputs/screenshots/v099-showcase/$name.png")
        requireNotNull(file.parentFile).mkdirs()
        file.outputStream().use { assertTrue("PNG is written: $name", bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }

    /** Capture the actual native dialog window, not a crop or composite of the activity. */
    private fun captureDialogWindowOnly(name: String) {
        compose.waitForIdle()
        val activeDialog = requireNotNull(ShadowDialog.getLatestDialog()) { "A native Compose dialog must be present for $name" }
        assertTrue("Dialog window is actually shown for $name", activeDialog.isShowing)
        val dialogWindow = requireNotNull(activeDialog.window)
        val dialogView = dialogWindow.decorView
        val bitmap = Bitmap.createBitmap(dialogView.width, dialogView.height, Bitmap.Config.ARGB_8888)
        val status = AtomicInteger(-1)
        compose.runOnUiThread {
            PixelCopy.request(dialogWindow, bitmap, { status.set(it) }, Handler(Looper.getMainLooper()))
        }
        compose.waitUntil(10_000) { status.get() != -1 }
        assertEquals("Native dialog-window-only PixelCopy succeeds for $name", PixelCopy.SUCCESS, status.get())
        val file = File("build/outputs/screenshots/v099-showcase/$name.png")
        requireNotNull(file.parentFile).mkdirs()
        file.outputStream().use { assertTrue("PNG is written: $name", bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }

    companion object {
        private const val FSDT_ID = "fsdt-impact-grant-fund-cycle-2-2026"
        private const val ORIGINAL_READ_ID = "showcase-kiswahili-ai-pilot-read"
        private const val CONTEXT_ID = "showcase-practical-tools"
        private val ORIGINAL_READ_CREATED_AT = LocalDate.of(2026, 10, 28)
            .atTime(10, 0).atZone(ZoneId.of("Africa/Dar_es_Salaam")).toInstant().toEpochMilli()

        private val questionsEn = listOf(
            "Which FSDT eligibility rule should a digital-solutions business check before drafting an application?",
            "What records could help document the ownership or leadership criteria? Check the exact evidence required in FSDT’s application form.",
            "How could a small team connect its digital solution to one of FSDT’s listed priority sectors?",
            "Which costs and outcomes should a business clarify against the grant ceiling of up to TZS 30 million?",
            "What evidence would show that a proposed project can deliver measurable value without exceeding the call’s scope?",
        )
        private val questionsSw = listOf(
            "Ni kigezo gani cha ustahiki cha FSDT ambacho biashara ya suluhisho za kidijitali inapaswa kukagua kabla ya kuandaa maombi?",
            "Ni nyaraka gani zinaweza kusaidia kuthibitisha umiliki au uongozi? Kagua ushahidi unaohitajika kwenye fomu rasmi ya FSDT.",
            "Timu ndogo inaweza kuhusishaje suluhisho lake la kidijitali na sekta moja ya kipaumbele ya FSDT?",
            "Biashara inapaswa kufafanua vipi gharama na matokeo yake ikilinganishwa na ruzuku ya hadi TZS milioni 30?",
            "Ni ushahidi gani unaweza kuonyesha kuwa mradi una thamani inayopimika na bado unalingana na wigo wa fursa hii?",
        )
    }
}
