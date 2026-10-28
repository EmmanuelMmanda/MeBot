package mebot.nguli.com.ui

import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import mebot.nguli.com.MeBotNavigation
import mebot.nguli.com.R
import mebot.nguli.com.core.*
import mebot.nguli.com.feed.FeedUiState
import mebot.nguli.com.feed.SuggestedConversationDraft
import mebot.nguli.com.feed.SuggestionOptionSnapshot097
import mebot.nguli.com.feed.SuggestionGenerationHandle097
import mebot.nguli.com.feed.SuggestionSheetSnapshot097
import mebot.nguli.com.feed.SuggestionSheetStatus097
import mebot.nguli.com.feed.SuggestionTargetKind097
import mebot.nguli.com.feed.SuggestionTargetSnapshot097
import mebot.nguli.com.ui.feed.AiWorkspace
import mebot.nguli.com.ui.feed.FeedDetail
import java.io.File
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.Job
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowDialog
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Native rendering and state transitions use synthetic records and no provider calls. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MeBotWorkspaceUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val restoration = StateRestorationTester(compose)
    private val now = System.currentTimeMillis()
    private val story = FeedItem("guide", "technology", "Preview: a useful deployment guide", "A synthetic source summary for interface verification.",
        sourceName = "Preview publisher", sourceUrl = "https://example.com/guide", kind = "technology", publishedAtMillis = now - 3_600_000,
        fetchedAtMillis = now, keyPoints = listOf("The source describes a small deployment example.", "Compatibility still needs checking."),
        nextStep = "Read the original prerequisites before trying the example.", caveat = "This is synthetic review content.")
    private val thread = AiConversation(id = "preview", title = "Preview deployment discussion", moduleId = "technology",
        turns = listOf(AiTurn(id = "q1", role = "user", text = "What should I check before trying this?"),
            AiTurn(id = "a1", role = "assistant", text = "Start with the documented requirements, then try the smallest example in a test environment. This is a synthetic answer.",
                citations = listOf(FeedCitation("Preview guide", "https://example.com/guide")), model = "Preview model")),
        attachments = listOf(AiAttachment("note", "example-notes.txt", "Synthetic imported text for this conversation.")), createdAtMillis = now - 1000, updatedAtMillis = now)
    private val state = FeedState(onboardingComplete = true, timezone = "Africa/Dar_es_Salaam",
        modules = listOf(FeedModule(id = "technology", kind = "technology"), FeedModule(id = "learning", kind = "learning")),
        items = listOf(story, story.copy(id = "related", title = "Preview: another useful guide", sourceUrl = "https://example.com/related")),
        conversations = listOf(thread), context = listOf(ContextEntry(id = "topic", text = "Synthetic approved engineering interest.", scopes = setOf("technology"))))
    private val ui = mutableStateOf(FeedUiState(state = state, loading = false, connected = true, activeConversationId = thread.id))
    private val questions = mutableListOf<String>()
    private val selected = mutableListOf<String>()
    private val deleted = mutableListOf<String>()
    private val scopes = mutableListOf<String?>()
    private val removedAttachments = mutableListOf<String>()
    private val usedSuggestions = mutableListOf<Pair<SuggestionTargetSnapshot097, String>>()
    private val editedSuggestions = mutableListOf<Pair<SuggestionTargetSnapshot097, String>>()
    private val appliedSuggestionDrafts = mutableListOf<String>()
    private var showSyntheticCachedSuggestions = false
    private var suggestionsNeedGeneration = false
    private var generationCalls = 0
    private var holdSuggestionGeneration = false
    private var activeSuggestionGeneration: CompletableJob? = null
    private val cancelledSuggestionRequestIds = mutableListOf<String>()
    private var loaded = 0
    private var cancelled = 0
    private var retry = 0
    private var connected = 0
    private var modules = 0

    @Test fun detailLoadsOnceShowsRealDraftAndKeepsFollowUpsInPlace() {
        ui.value = ui.value.copy(detailItemId = story.id, detailLoading = true, detailText = "A real provisional explanation from the controlled stream.")
        show(detail = true)
        compose.runOnIdle { assertEquals(1, loaded) }
        assertRichResponseText("detail-brief-draft", "A real provisional explanation from the controlled stream.")
        compose.onAllNodesWithTag("detail-brief-answer").assertCountEquals(0)
        detail("detail-brief-stop").performClick()
        assertEquals(1, cancelled)
        compose.runOnIdle { ui.value = ui.value.copy(detailLoading = false, detailText = "", detailTurns = listOf(
            AiTurn(id = "brief", role = "assistant", text = "A completed, sourced explanation for this preview.", citations = listOf(FeedCitation("Preview original", story.sourceUrl))))) }
        assertRichResponseText("detail-brief-answer", "A completed, sourced explanation for this preview.")
        detail("detail-followup").performTextInput("Which prerequisite matters first?")
        detail("detail-followup-send").performClick()
        assertEquals(listOf("Which prerequisite matters first?"), questions)
        compose.runOnIdle {
            ui.value = ui.value.copy(detailLoading = true, detailTurns = ui.value.detailTurns + AiTurn("follow-question", "user", questions.single()), detailText = "The first requirement is")
        }
        assertRichResponseText("detail-brief-draft", "The first requirement is")
        compose.onNodeWithTag("detail-followup-send").assertIsNotEnabled()
        compose.runOnIdle { ui.value = ui.value.copy(detailLoading = false, detailText = "", detailTurns = ui.value.detailTurns + AiTurn("follow-answer", "assistant", "A completed follow-up answer.")) }
        detail("detail-followup").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        detail("detail-tab-overview")
        screenshot("47-mebot-detail-v06")
        assertEquals("Recomposition cannot start duplicate automatic requests", 1, loaded)
    }

    @Test fun detailErrorPreservesPartialTextAndSourcesAndRelatedActionsWork() {
        ui.value = ui.value.copy(detailItemId = story.id, detailText = "Unfinished preview text.", detailError = "offline")
        show(detail = true)
        assertRichResponseText("detail-brief-draft", "Unfinished preview text.")
        compose.onNodeWithText(text(R.string.workspace_partial)).assertExists()
        compose.onNodeWithText(text(R.string.feed_retry)).performScrollTo().performClick()
        assertEquals(1, retry)
        detail("detail-module").performClick()
        assertEquals(1, modules)
        detail("detail-related-section")
        compose.onNodeWithTag("detail-related-related").assertIsDisplayed().performClick()
        assertEquals(listOf("related"), selected)
        detail("detail-tab-sources").performClick()
        compose.onNodeWithText(text(R.string.detail_published)).performScrollTo().assertIsDisplayed()
        detail("detail-citation-0").performClick()
        assertEquals(story.sourceUrl, shadowOf(compose.activity).nextStartedActivity.dataString)
        detail("detail-share").performClick()
        val chooser = shadowOf(compose.activity).nextStartedActivity
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        @Suppress("DEPRECATION") val shared = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
        assertEquals(story.title + "\n" + story.sourceUrl, shared?.getStringExtra(Intent.EXTRA_TEXT))
    }

    @Test fun workspaceRestoresDraftAndReviewsHistoryAndAttachmentsBeforeDeleting() {
        show()
        compose.onNodeWithTag("workspace-question").performTextInput("Keep this draft after an error")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("workspace-question").assertTextContains("Keep this draft after an error")
        compose.onNodeWithTag("workspace-send").performClick()
        assertEquals(listOf("Keep this draft after an error"), questions)
        compose.runOnIdle { ui.value = ui.value.copy(error = "offline") }
        compose.onNodeWithTag("workspace-question").assertTextContains("Keep this draft after an error")
        compose.onNodeWithTag("attachment-note").performClick()
        compose.onNodeWithText("Synthetic imported text for this conversation.").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.done)).performClick()
        compose.onNodeWithTag("attachment-remove-note").performClick()
        assertEquals(listOf("note"), removedAttachments)
        compose.onNodeWithTag("workspace-history").performClick()
        compose.onNodeWithTag("conversation-preview").performClick()
        assertEquals(listOf("preview"), selected)
        compose.onNodeWithTag("workspace-history").performClick()
        compose.onNodeWithTag("conversation-delete-preview").performClick()
        assertTrue(deleted.isEmpty())
        compose.onNodeWithTag("conversation-delete-confirm").performClick()
        assertEquals(listOf("preview"), deleted)
    }

    @Test fun chatSuggestionsRequireAnExplicitActionAndPrimarySendStaysInTheCurrentConversation() {
        show()
        compose.onNodeWithTag("workspace-suggestions-open").performClick()
        compose.onNodeWithTag("workspace-suggestions-question-0").assertTextContains("discussion about", substring = true)
        (0..4).forEach { compose.onNodeWithTag("workspace-suggestions-question-$it").assertExists() }
        compose.onNodeWithTag("workspace-suggestions-close").performClick()
        assertTrue("Opening and cancelling the sheet is UI-only", usedSuggestions.isEmpty() && editedSuggestions.isEmpty())

        compose.onNodeWithTag("workspace-suggestions-open").performClick()
        compose.runOnIdle { ShadowDialog.getLatestDialog().onBackPressed() }
        compose.waitForIdle()
        compose.onNodeWithTag("workspace-suggestions-close").assertDoesNotExist()
        assertTrue("Back dismisses without using a suggestion", usedSuggestions.isEmpty() && editedSuggestions.isEmpty())

        compose.onNodeWithTag("workspace-suggestions-open").performClick()
        compose.onNodeWithTag("workspace-suggestions-send-0").performTouchInput { click(); click() }
        assertEquals(1, usedSuggestions.size)
        assertEquals(SuggestionTargetKind097.CONVERSATION, usedSuggestions.single().first.kind)
        assertEquals(thread.id, usedSuggestions.single().first.targetId)
        assertEquals("provider-fixture-0", usedSuggestions.single().second)
        assertTrue("The UI dispatches a selected action, not an implicit or duplicate send", questions.isEmpty())

        compose.onNodeWithTag("workspace-suggestions-open").performClick()
        compose.onNodeWithTag("workspace-suggestions-edit-0").performClick()
        assertEquals(1, editedSuggestions.size)
        assertEquals(SuggestionTargetKind097.CONVERSATION, editedSuggestions.single().first.kind)
        assertTrue("Pencil is distinct from Send", usedSuggestions.size == 1 && questions.isEmpty())
    }

    @Test fun disconnectedGeneralSuggestionsHaveNoLocalFallbackAndCannotSend() {
        ui.value = ui.value.copy(activeConversationId = null, state = state.copy(items = emptyList(), conversations = emptyList()))
        ui.value = ui.value.copy(connected = false)
        show()
        compose.onNodeWithTag("workspace-suggestions-open").performClick()
        compose.onNodeWithTag("workspace-suggestions-unavailable-note").assertIsDisplayed()
        compose.onNodeWithTag("workspace-suggestions-question-0").assertDoesNotExist()
        compose.onNodeWithTag("workspace-suggestions-connect").assertIsDisplayed().performClick()
        assertEquals(1, connected)
        assertTrue(editedSuggestions.isEmpty())
        assertTrue(usedSuggestions.isEmpty() && questions.isEmpty())
    }

    @Test fun explicitOpenWithoutPreparedQuestionsStartsOneGenerationAndShowsPreparedAngles() {
        suggestionsNeedGeneration = true
        // Hold the test transport open so the intermediate progress state is deterministic
        // instead of racing a synchronously completed fake Job.
        holdSuggestionGeneration = true
        show()
        compose.onNodeWithTag("workspace-suggestions-open").performClick()
        compose.onNodeWithTag("workspace-suggestions-generating").assertExists()
        assertEquals("One deliberate open starts one lazy generation", 1, generationCalls)
        assertTrue("The test provider request is held until the progress assertion", activeSuggestionGeneration?.isActive == true)
        compose.runOnIdle { assertTrue(activeSuggestionGeneration?.complete() == true) }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("workspace-suggestions-question-4").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("workspace-suggestions-generate").assertDoesNotExist()
        compose.onNodeWithTag("workspace-suggestions-provenance").assertTextEquals(text(R.string.suggestions_ai_generated))
        assertTrue(usedSuggestions.isEmpty() && editedSuggestions.isEmpty() && questions.isEmpty())
    }

    @Test fun closingAnActiveSuggestionSheetCancelsItsExactGenerationSynchronously() {
        suggestionsNeedGeneration = true
        holdSuggestionGeneration = true
        show()
        compose.onNodeWithTag("workspace-suggestions-open").performClick()
        compose.onNodeWithTag("workspace-suggestions-generating").assertExists()
        assertEquals(1, generationCalls)

        compose.onNodeWithTag("workspace-suggestions-close").performClick()
        compose.waitForIdle()
        assertTrue("Close synchronously fences the exact active generation request",
            cancelledSuggestionRequestIds.isNotEmpty() &&
                cancelledSuggestionRequestIds.all { it == "provider-fixture-generation-1" })
        assertFalse("The provider generation job is cancelled", activeSuggestionGeneration?.isActive == true)
        compose.onNodeWithTag("workspace-suggestions-sheet").assertDoesNotExist()
        assertTrue("Cancelling generation never sends or edits a question", usedSuggestions.isEmpty() && editedSuggestions.isEmpty() && questions.isEmpty())
    }

    @Test fun systemBackCancelsTheActiveSuggestionGeneration() {
        suggestionsNeedGeneration = true
        holdSuggestionGeneration = true
        show()
        compose.onNodeWithTag("workspace-suggestions-open").performClick()
        compose.onNodeWithTag("workspace-suggestions-generating").assertExists()

        compose.runOnIdle { ShadowDialog.getLatestDialog().onBackPressed() }
        compose.waitForIdle()
        assertTrue("Back dismiss synchronously fences the active request",
            cancelledSuggestionRequestIds.isNotEmpty() &&
                cancelledSuggestionRequestIds.all { it == "provider-fixture-generation-1" })
        assertFalse("The provider generation job is cancelled", activeSuggestionGeneration?.isActive == true)
        compose.onNodeWithTag("workspace-suggestions-sheet").assertDoesNotExist()
    }

    @Test fun disconnectWhileSheetIsOpenKeepsValidatedAiSnapshotEditableButDisablesSend() {
        showSyntheticCachedSuggestions = true
        show()
        compose.onNodeWithTag("workspace-suggestions-open").performClick()
        compose.onNodeWithTag("workspace-suggestions-question-0").assertTextEquals(
            "From our discussion about ${thread.title}, what evidence would most change the decision?",
        )
        compose.onNodeWithTag("workspace-suggestions-provenance").assertTextEquals(text(R.string.suggestions_ai_generated))
        ui.value = ui.value.copy(connected = false)
        compose.waitForIdle()
        compose.onNodeWithTag("workspace-suggestions-offline-note").assertIsDisplayed()
        compose.onNodeWithTag("workspace-suggestions-send-0").assertIsNotEnabled()
        compose.onNodeWithTag("workspace-suggestions-edit-0").assertIsEnabled()
        assertTrue(usedSuggestions.isEmpty() && editedSuggestions.isEmpty())

        compose.onNodeWithTag("workspace-suggestions-edit-0").assertIsEnabled().performClick()
        assertEquals("provider-fixture-0", editedSuggestions.single().second)
        assertTrue(usedSuggestions.isEmpty() && questions.isEmpty())
    }

    @Test fun openingConversationKeepsDraftEditableAndDisablesSendUntilReady() {
        val pending = AiConversation(id = "pending-story-thread", moduleId = "technology", itemId = story.id)
        ui.value = ui.value.copy(activeConversationId = null, conversationOpening = true, conversationReady = false,
            state = state.copy(conversations = listOf(pending)))
        show()

        compose.onNodeWithTag("workspace-opening").assertTextEquals(text(R.string.workspace_opening))
        compose.onNodeWithTag("workspace-question").performTextInput("What should I check first?")
        compose.onNodeWithTag("workspace-question").assertTextContains("What should I check first?")
        compose.onNodeWithTag("workspace-send").assertIsNotEnabled()
        compose.onNodeWithTag("workspace-attach").assertIsNotEnabled()
        assertTrue("Typing while a scoped conversation opens must not call send", questions.isEmpty())

        ui.value = ui.value.copy(activeConversationId = pending.id, conversationOpening = false, conversationReady = true)
        compose.waitForIdle()
        compose.onNodeWithTag("workspace-opening").assertDoesNotExist()
        compose.onNodeWithTag("workspace-question").assertTextContains("What should I check first?")
        compose.onNodeWithTag("workspace-send").assertIsEnabled().performClick()
        assertEquals(listOf("What should I check first?"), questions)
    }

    @Test fun pencilToANewItemThreadPreservesAnUnrelatedGeneralDraft() {
        ui.value = ui.value.copy(activeConversationId = null, state = state.copy(conversations = emptyList()))
        show()
        compose.onNodeWithTag("workspace-question").performTextInput("Keep this unsent note")
        val target = AiConversation(id = "suggestion-thread", title = "", moduleId = "technology", itemId = story.id)
        ui.value = ui.value.copy(
            activeConversationId = target.id,
            state = state.copy(conversations = listOf(target)),
            suggestionDraft = SuggestedConversationDraft("draft-event", target.id, "Explain this current update in simple terms."),
        )
        compose.waitForIdle()
        compose.onNodeWithText(text(R.string.suggestions_replace_title)).assertDoesNotExist()
        compose.onNodeWithTag("workspace-question").assertTextContains("Explain this current update in simple terms.")
        assertTrue("The unrelated draft in the general composer must not make this story-scoped edit replace it", questions.isEmpty())
        assertTrue("A dismissed pencil draft never sends", questions.isEmpty())
        assertEquals(listOf("draft-event"), appliedSuggestionDrafts)
        ui.value = ui.value.copy(activeConversationId = null, suggestionDraft = null,
            state = ui.value.state!!.copy(conversations = ui.value.state!!.conversations.filterNot { it.id == target.id }))
        compose.waitForIdle()
        compose.onNodeWithTag("workspace-question").assertTextContains("Keep this unsent note")
    }

    @Test fun primaryStorySendKeepsTheGeneralComposerDraftOnItsOriginalKey() {
        ui.value = ui.value.copy(activeConversationId = null, state = state.copy(conversations = emptyList()))
        show()
        compose.onNodeWithTag("workspace-question").performTextInput("Keep my general question")
        val storyThread = AiConversation(id = "sent-story-thread", title = "A useful deployment guide", moduleId = "technology", itemId = story.id)
        ui.value = ui.value.copy(
            activeConversationId = storyThread.id,
            conversationReady = true,
            suggestionWorking = true,
            state = state.copy(conversations = listOf(storyThread)),
        )
        compose.waitForIdle()
        compose.onNodeWithTag("workspace-question").assert(SemanticsMatcher.expectValue(
            SemanticsProperties.EditableText, AnnotatedString(""),
        ))
        ui.value = ui.value.copy(suggestionWorking = false)
        compose.waitForIdle()
        compose.onNodeWithTag("workspace-question").assert(SemanticsMatcher.expectValue(
            SemanticsProperties.EditableText, AnnotatedString(""),
        ))
        ui.value = ui.value.copy(activeConversationId = null)
        compose.waitForIdle()
        compose.onNodeWithTag("workspace-question").assertTextContains("Keep my general question")
        assertTrue("Primary suggestion Send must not send or migrate the unrelated draft", questions.isEmpty())
    }

    @Test fun replacingAChatDraftRequiresTheProtectedConfirmation() {
        show()
        compose.onNodeWithTag("workspace-question").performTextInput("Keep this chat draft")
        ui.value = ui.value.copy(suggestionDraft = SuggestedConversationDraft("replace-event", thread.id, "From the current discussion, what should I verify first?"))
        compose.waitForIdle()
        compose.onNodeWithText(text(R.string.suggestions_replace_title)).assertIsDisplayed()
        compose.onNodeWithTag("workspace-question").assertTextContains("Keep this chat draft")
        assertTrue(questions.isEmpty())
        compose.onNodeWithTag("workspace-suggestion-replace").performClick()
        compose.onNodeWithTag("workspace-question").assertTextContains("what should I verify first", substring = true)
        assertEquals(listOf("replace-event"), appliedSuggestionDrafts)
        assertTrue("Editing a suggestion never sends it", questions.isEmpty())
    }

    @Test fun emptyNewThreadPencilDraftPrefillsWithoutSending() {
        ui.value = ui.value.copy(activeConversationId = null, state = state.copy(conversations = emptyList()))
        show()
        val target = AiConversation(id = "suggestion-thread", title = "", moduleId = "technology", itemId = story.id)
        ui.value = ui.value.copy(
            activeConversationId = target.id,
            state = state.copy(conversations = listOf(target)),
            suggestionDraft = SuggestedConversationDraft("draft-event", target.id, "Explain this current update in simple terms."),
        )
        compose.waitForIdle()
        compose.onNodeWithTag("workspace-question").assertTextContains("Explain this current update in simple terms.")
        compose.onNodeWithText(text(R.string.suggestions_replace_title)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.workspace_item_draft_title)).assertExists()
        compose.onNodeWithText(text(R.string.workspace_item_draft_help)).assertExists()
        compose.onNodeWithText(text(R.string.workspace_confirmed_about_you_available)).assertDoesNotExist()
        assertEquals(listOf("draft-event"), appliedSuggestionDrafts)
        assertTrue(questions.isEmpty())

        val confirmedProfile = ContextEntry(
            id = PersonalProfile085.PROFILE_ID, text = "Synthetic saved About you context.",
            category = PersonalProfile085.PROFILE_CATEGORY, scopes = emptySet(), global = true, confirmed = true,
        )
        val withConfirmedProfile = ui.value.state!!
        assertTrue(PersonalProfile085.isGlobal(confirmedProfile))
        ui.value = ui.value.copy(state = withConfirmedProfile.copy(context = withConfirmedProfile.context + confirmedProfile))
        compose.waitForIdle()
        compose.onNodeWithText(text(R.string.workspace_confirmed_about_you_available)).assertExists()

        val withProfileAndSuggestionThread = ui.value.state!!
        ui.value = ui.value.copy(activeConversationId = null, suggestionDraft = null,
            state = withProfileAndSuggestionThread.copy(conversations = withProfileAndSuggestionThread.conversations.filterNot { it.id == target.id }))
        compose.waitForIdle()
        compose.onNodeWithTag("workspace-question").assert(SemanticsMatcher.expectValue(
            SemanticsProperties.EditableText, AnnotatedString(""),
        ))
    }

    @Test fun workspaceStreamsTruthfullyKeepsCompletedTurnsAndStopsWithoutFakeAnswers() {
        show()
        assertWorkspaceControlsStacked(false)
        message("turn-copy-a1").performClick()
        assertEquals(thread.turns.last().text, compose.activity.getSystemService(ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString())
        message("turn-source-a1-0").performClick()
        assertEquals(story.sourceUrl, shadowOf(compose.activity).nextStartedActivity.dataString)
        compose.onNodeWithTag("workspace-question").performTextInput("Explain the first step")
        compose.onNodeWithTag("workspace-send").performClick()
        compose.runOnIdle { ui.value = ui.value.copy(asking = true, answer = "The response is arriving", question = questions.single()) }
        assertRichResponseText("workspace-draft", "The response is arriving")
        compose.onNodeWithTag("workspace-cancel").performClick()
        assertEquals(1, cancelled)
        compose.runOnIdle {
            val current = ui.value.state!!
            ui.value = ui.value.copy(asking = false, answer = "", state = current.copy(conversations = listOf(thread.copy(turns = thread.turns +
                listOf(AiTurn("q2", "user", questions.single()), AiTurn("a2", "assistant", "A completed controlled answer."))))))
        }
        message("turn-a2").assertExists()
        compose.onNodeWithTag("workspace-draft").assertDoesNotExist()
        compose.onNodeWithTag("workspace-question").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        compose.onNodeWithTag("workspace-scope").performClick()
        compose.onNodeWithTag("workspace-scope-learning").performClick()
        assertEquals(listOf<String?>("learning"), scopes)
    }

    @Test
    @Config(qualifiers = "sw-rTZ-w320dp-h800dp-xhdpi")
    fun narrowWorkspaceKeepsComposerReachableAndDisconnectedHistoryReadable() {
        ui.value = ui.value.copy(connected = false)
        showSyntheticCachedSuggestions = true
        show(settings = Settings(theme = "dark", language = "sw"), fontScale = 1.3f)
        assertWorkspaceControlsStacked(true)
        assertLabelFits(text(R.string.feed_connect))
        assertLabelFits(text(R.string.module_technology))
        compose.onNodeWithTag("workspace-suggestions-open").assertIsDisplayed().performClick()
        compose.onNodeWithTag("workspace-suggestions-offline-note").assertIsDisplayed()
        compose.onNodeWithTag("workspace-suggestions-send-0").assertIsNotEnabled()
        val suggestionQuestion = compose.onNodeWithTag("workspace-suggestions-question-0").assertIsDisplayed()
        assertLabelFits(suggestionQuestion.fetchSemanticsNode().config[SemanticsProperties.Text].joinToString("") { it.text })
        val suggestionButtonBounds = compose.onNodeWithTag("workspace-suggestions-edit-0").getUnclippedBoundsInRoot()
        assertTrue("The pencil remains a 48dp target with larger text", suggestionButtonBounds.bottom - suggestionButtonBounds.top >= 48.dp)
        compose.onNodeWithTag("workspace-suggestions-close").performClick()
        compose.onNodeWithTag("workspace-question").assertIsDisplayed().performTextInput("Swali langu la mfano")
        compose.onNodeWithTag("workspace-send").assertIsNotEnabled()
        message("turn-a1").assertExists()
        compose.onNodeWithTag("workspace-model").performClick()
        assertEquals(1, connected)
        screenshot("49-mebot-workspace-small-sw-v06")
        compose.onNodeWithTag("workspace-history").performClick()
        compose.onNodeWithTag("conversation-preview").performClick()
        assertEquals(listOf("preview"), selected)
    }

    @Test
    @Config(qualifiers = "sw-rTZ-w320dp-h800dp-xhdpi")
    fun narrowDetailShowsRecentTurnsAndCanRevealEarlierMessagesWithoutLosingState() {
        val turns = List(20) { index -> AiTurn(id = "archived-$index", role = if (index % 2 == 0) "user" else "assistant",
            text = "Ujumbe wa mfano $index", createdAtMillis = 946684800000L + index * 60_000L) }
        ui.value = ui.value.copy(connected = false, detailItemId = story.id, detailTurns = turns)
        show(detail = true, settings = Settings(theme = "dark", language = "sw"), fontScale = 1.3f)
        compose.onAllNodesWithTag("detail-brief-answer").assertCountEquals(4)
        compose.onNodeWithText("Ujumbe wa mfano 0").assertDoesNotExist()
        detail("detail-brief-time").assertTextContains("2000", substring = true)
            .assertTextContains("03:19", substring = true)
        detail("detail-earlier-messages").performClick()
        compose.onAllNodesWithTag("detail-brief-answer").assertCountEquals(8)
        restoration.emulateSavedInstanceStateRestore()
        compose.onAllNodesWithTag("detail-brief-answer").assertCountEquals(8)
        detail("detail-earlier-messages").performClick()
        compose.onAllNodesWithTag("detail-brief-answer").assertCountEquals(10)
        compose.onNodeWithTag("detail-earlier-messages").assertDoesNotExist()
        compose.onNodeWithText("Ujumbe wa mfano 0").assertExists()
    }

    private fun show(detail: Boolean = false, settings: Settings = Settings(), fontScale: Float = 1f) {
        ui.value = ui.value.copy(appLanguage = if (settings.language == "sw") "sw" else "en")
        restoration.setContent {
            MeBotTheme(settings) {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                    Scaffold(containerColor = MaterialTheme.colorScheme.background,
                        topBar = { Text(if (settings.language == "sw") "Mfano wa ukaguzi" else "Synthetic review preview", style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)) },
                        bottomBar = { if (!detail) MeBotNavigation("ai") {} },
                    ) { padding ->
                        Surface(Modifier.fillMaxSize().padding(padding), color = MaterialTheme.colorScheme.background) {
                            if (detail) FeedDetail(ui.value, story.id, onSave = {}, onAsk = {}, onFeed = {}, onPrompt = { questions += it },
                                onItem = { selected += it }, onModule = { modules++ }, onHide = {},
                                onLoadBrief = { loaded++ }, onCancelBrief = { cancelled++ }, onRetryBrief = { retry++ }, onFollowUp = { questions += it }, onConnect = { connected++ })
                            else AiWorkspace(ui.value, onNew = { scopes += it }, onSelect = { selected += it }, onDelete = { deleted += it },
                                onAsk = { questions += it }, onCancel = { cancelled++ }, onConnect = { connected++ }, onContext = {},
                                onImportText = {}, onRemoveAttachment = { removedAttachments += it },
                                onSuggestionDraftApplied = { appliedSuggestionDrafts += it },
                                onUseSuggestion = { target, id, _ -> usedSuggestions += target to id },
                                onEditSuggestion = { target, id, _ -> editedSuggestions += target to id },
                                onSuggestionSheet = ::suggestionSheet,
                                onGenerateSuggestion = ::generateSuggestion,
                                onCancelSuggestionGeneration = ::cancelSuggestionGeneration)
                        }
                    }
                }
            }
        }
        if (!detail && fontScale == 1f) screenshot("48-mebot-workspace-v06")
    }

    /** Explicitly synthetic provider output, used only by isolated component tests. */
    private fun suggestionSheet(target: SuggestionTargetSnapshot097, language: String): SuggestionSheetSnapshot097 {
        val current = ui.value.state ?: return SuggestionSheetSnapshot097(target, SuggestionSheetStatus097.UNAVAILABLE)
        val storyForScope = target.itemId?.let { id -> current.items.singleOrNull { it.id == id } }
        if (!ui.value.connected && !showSyntheticCachedSuggestions) {
            return SuggestionSheetSnapshot097(target, SuggestionSheetStatus097.UNAVAILABLE, failureCode = "suggestion_unavailable")
        }
        if (suggestionsNeedGeneration) return SuggestionSheetSnapshot097(target, SuggestionSheetStatus097.NEEDS_GENERATION)
        val conversation = current.conversations.singleOrNull { it.id == target.targetId }
        val topic = storyForScope?.title ?: conversation?.title ?: "the preview topic"
        val topicPhrase = when (target.kind) {
            SuggestionTargetKind097.CONVERSATION -> "our discussion about $topic"
            else -> "the synthetic preview about $topic"
        }
        val questions = listOf(
            "From $topicPhrase, what evidence would most change the decision?",
            "Which assumption about $topic should be checked before acting?",
            "What practical limitation or risk about $topic remains unresolved?",
            "Which alternative to $topic would be useful to compare, and on what measure?",
            "What unanswered detail about $topic would be most useful to investigate next?",
        )
        return SuggestionSheetSnapshot097(target, SuggestionSheetStatus097.READY,
            questions.mapIndexed { index, question -> SuggestionOptionSnapshot097("provider-fixture-$index", question, now) },
            validUntilMillis = now + 60_000)
    }

    private fun generateSuggestion(target: SuggestionTargetSnapshot097, language: String): SuggestionGenerationHandle097? {
        generationCalls++
        suggestionsNeedGeneration = false
        val job = Job().also { if (!holdSuggestionGeneration) it.complete() }
        activeSuggestionGeneration = job
        return SuggestionGenerationHandle097("provider-fixture-generation-$generationCalls", job)
    }

    private fun cancelSuggestionGeneration(requestId: String) {
        cancelledSuggestionRequestIds += requestId
        activeSuggestionGeneration?.cancel()
    }

    private fun detail(tag: String) = compose.onNodeWithTag(tag).performScrollTo()
    private fun assertRichResponseText(tag: String, expected: String) {
        val response = if (tag == "workspace-draft") message(tag) else detail(tag)
        response.assert(hasAnyDescendant(hasText(expected)))
    }
    private fun assertWorkspaceControlsStacked(stacked: Boolean) {
        val model = compose.onNodeWithTag("workspace-model").fetchSemanticsNode().boundsInRoot
        val scope = compose.onNodeWithTag("workspace-scope").fetchSemanticsNode().boundsInRoot
        if (stacked) {
            assertTrue("Narrow controls must occupy separate rows", scope.top >= model.bottom)
            assertEquals(model.width, scope.width, 1f)
        } else assertTrue("Regular controls must remain side by side", scope.left >= model.right)
    }
    private fun assertLabelFits(label: String) {
        compose.onNodeWithText(label, useUnmergedTree = true).assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action ->
                val layouts = mutableListOf<TextLayoutResult>()
                assertTrue(action(layouts))
                assertTrue(layouts.isNotEmpty())
                assertFalse("The complete control label should be visible", layouts.any { layout ->
                    layout.hasVisualOverflow || (0 until layout.lineCount).any { layout.isLineEllipsized(it) }
                })
            }
    }
    private fun message(tag: String): SemanticsNodeInteraction {
        compose.onNodeWithTag("workspace-messages").performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag)
    }
    private fun text(id: Int) = compose.activity.getString(id)
    private fun screenshot(name: String) {
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        val bitmap = compose.runOnIdle {
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
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }
}
