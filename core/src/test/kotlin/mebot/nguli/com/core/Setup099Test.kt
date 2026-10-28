package mebot.nguli.com.core

import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class Setup099Test {
    private val now = 1_800_000_000_000L

    @Test fun oldAndExistingUsersAreNotForcedIntoNewSetup() {
        val old = Json.decodeFromString<FeedState>("{}")
        assertEquals(Setup099Stage.INTRO, old.setup099.stage)
        assertEquals(Setup099ConnectionEntry.UNKNOWN, old.setup099.preferredConnectionEntry099)
        assertFalse(Setup099Logic.prefersChatGpt(old.setup099, onboardingComplete = true))
        assertNull(Setup099Logic.entryStage(old.setup099, onboardingComplete = true))
        assertEquals(Setup099Stage.INTRO,
            Setup099Logic.entryStage(old.setup099, onboardingComplete = false))
        assertEquals(Setup099Stage.INTRO,
            Setup099Logic.entryStage(old.setup099, onboardingComplete = true, explicitlyOpened = true))
        assertNull(Setup099Logic.entryStage(Setup099State(stage = Setup099Stage.COMPLETE), false, true))
    }

    @Test fun freshPublicSkipKeepsChatGptDefaultAndExplicitProviderChoiceSurvivesFinishAndDisconnect() {
        val migratedUnknown = Setup099State(stage = Setup099Stage.INTRO)
        assertFalse(Setup099Logic.prefersChatGpt(migratedUnknown, onboardingComplete = true))

        val freshSkipped = Setup099Logic.finish(migratedUnknown, preferChatGptIfUnknown = true)
        assertEquals(Setup099ConnectionEntry.CHATGPT, freshSkipped.preferredConnectionEntry099)
        assertTrue(Setup099Logic.prefersChatGpt(freshSkipped, onboardingComplete = true))
        assertEquals(Setup099Stage.COMPLETE, freshSkipped.stage)

        val directSelected = Setup099Logic.recordPreferredConnection(migratedUnknown, Setup099ConnectionEntry.USER_SELECTED)
        val directFinished = Setup099Logic.finish(directSelected, preferChatGptIfUnknown = true)
        assertEquals(Setup099ConnectionEntry.USER_SELECTED, directFinished.preferredConnectionEntry099)
        assertFalse(Setup099Logic.prefersChatGpt(directFinished, onboardingComplete = true))
        // Disconnecting changes connection runtime, not this persisted local choice marker.
        val afterDisconnect = directFinished.copy()
        assertEquals(Setup099ConnectionEntry.USER_SELECTED, afterDisconnect.preferredConnectionEntry099)
    }

    @Test fun successfulChatGptChoiceAndImportTransitionsPreserveTheLocalMarker() {
        val chatGptSelected = Setup099Logic.recordPreferredConnection(
            Setup099State(), Setup099ConnectionEntry.CHATGPT,
        )
        assertTrue(Setup099Logic.prefersChatGpt(chatGptSelected, onboardingComplete = true))
        val pending = Setup099Logic.recordChatGptSuccess(
            chatGptSelected.copy(stage = Setup099Stage.CONNECT_CHATGPT), "acct-import", true, false, setupEligible = true,
        )
        val acknowledged = Setup099Logic.acknowledgeImportRoute(pending, "acct-import")
        val contextDone = Setup099Logic.contextConfirmed(acknowledged,
            listOf(ContextEntry(id = "note", text = "A durable note")))
        assertEquals(Setup099ConnectionEntry.CHATGPT, contextDone.preferredConnectionEntry099)
        assertEquals(Setup099Stage.START_FEED, contextDone.stage)
    }

    @Test fun invalidPreferredConnectionMarkerIsRejected() {
        assertTrue(runCatching {
            Setup099Logic.validate(Setup099State(preferredConnectionEntry099 = "provider-secret"))
        }.isFailure)
    }

    @Test fun onlyConfirmedChatGptConnectQueuesImportAndRepeatedConnectIsDeduplicated() {
        val starting = Setup099State(stage = Setup099Stage.CONNECT_CHATGPT)
        assertEquals(starting, Setup099Logic.recordChatGptSuccess(starting, "acct-1", confirmed = false, hasConfirmedContext = false, setupEligible = true))
        val first = Setup099Logic.recordChatGptSuccess(starting, "acct-1", confirmed = true, hasConfirmedContext = false, setupEligible = true)
        assertEquals(Setup099Stage.IMPORT_CONTEXT, first.stage)
        assertEquals("acct-1", first.firstChatGptAccountKey)
        assertEquals("acct-1", first.pendingImportAccountKey)
        assertEquals(first, Setup099Logic.recordChatGptSuccess(first, "acct-1", confirmed = true, hasConfirmedContext = false, setupEligible = true))
        assertEquals(first, Setup099Logic.recordChatGptSuccess(first, "acct-2", confirmed = true, hasConfirmedContext = false, setupEligible = true))
    }

    @Test fun importRouteIsPendingUntilAcknowledgedThenNeverReplays() {
        val pending = Setup099Logic.recordChatGptSuccess(
            Setup099State(stage = Setup099Stage.CONNECT_CHATGPT), "acct-1", confirmed = true, hasConfirmedContext = false, setupEligible = true,
        )
        val afterEntry = Setup099Logic.acknowledgeImportRoute(pending, "acct-1")
        assertEquals("", afterEntry.pendingImportAccountKey)
        assertEquals("acct-1", afterEntry.importRouteAcknowledgedAccountKey)
        assertEquals(afterEntry, Setup099Logic.acknowledgeImportRoute(afterEntry, "acct-1"))
        assertEquals(afterEntry, Setup099Logic.recordChatGptSuccess(afterEntry, "acct-1", confirmed = true, hasConfirmedContext = false))
        assertEquals(afterEntry, Setup099Logic.recordChatGptSuccess(afterEntry, "acct-2", confirmed = true, hasConfirmedContext = false))
    }

    @Test fun processDeathRoundTripPreservesPendingImportAndAcknowledgement() {
        val pending = Setup099Logic.recordChatGptSuccess(
            Setup099State(stage = Setup099Stage.CONNECT_CHATGPT), "acct-restore", confirmed = true, hasConfirmedContext = false, setupEligible = true,
        )
        val restoredPending = Json.decodeFromString<Setup099State>(Json.encodeToString(pending))
        assertEquals(pending, restoredPending)
        assertEquals("acct-restore", restoredPending.pendingImportAccountKey)
        val entered = Setup099Logic.acknowledgeImportRoute(restoredPending, "acct-restore")
        val restoredEntered = Json.decodeFromString<Setup099State>(Json.encodeToString(entered))
        assertEquals(entered, restoredEntered)
        assertEquals("acct-restore", restoredEntered.importRouteAcknowledgedAccountKey)
        assertEquals("", restoredEntered.pendingImportAccountKey)
    }

    @Test fun backCancelAndSkipDoNotSilentlyRequeueImport() {
        val connected = Setup099Logic.recordChatGptSuccess(
            Setup099State(stage = Setup099Stage.CONNECT_CHATGPT), "acct-1", confirmed = true, hasConfirmedContext = false, setupEligible = true,
        )
        val acknowledged = Setup099Logic.acknowledgeImportRoute(connected, "acct-1")
        val back = Setup099Logic.previousStage(acknowledged)
        assertEquals(Setup099Stage.CONNECT_CHATGPT, back.stage)
        assertEquals("", back.pendingImportAccountKey)
        assertEquals(back, Setup099Logic.recordChatGptSuccess(back, "acct-1", confirmed = true, hasConfirmedContext = false, setupEligible = true))
        val skipped = Setup099Logic.skipContext(acknowledged)
        assertEquals(Setup099Stage.START_FEED, skipped.stage)
        assertEquals("", skipped.pendingImportAccountKey)
        assertEquals(Setup099Stage.COMPLETE, Setup099Logic.finish(skipped).stage)
    }

    @Test fun skippingBeforeImportRouteAcknowledgementClearsPendingAndSurvivesRecreation() {
        val pending = Setup099Logic.recordChatGptSuccess(
            Setup099State(stage = Setup099Stage.CONNECT_CHATGPT), "acct-race", confirmed = true,
            hasConfirmedContext = false, setupEligible = true,
        )
        // The host may receive a skip tap before its async route-acknowledgment write finishes.
        val skipped = Setup099Logic.setStage(pending, Setup099Stage.START_FEED)
        assertEquals(Setup099Stage.START_FEED, skipped.stage)
        assertEquals("", skipped.pendingImportAccountKey)
        assertEquals("acct-race", skipped.importRouteAcknowledgedAccountKey)
        assertEquals(skipped, Setup099Logic.acknowledgeImportRoute(skipped, "acct-race"))
        val restored = Json.decodeFromString<Setup099State>(Json.encodeToString(skipped))
        assertEquals(skipped, restored)
        assertEquals("", restored.pendingImportAccountKey)
        assertEquals(restored, Setup099Logic.recordChatGptSuccess(restored, "acct-race", true, false, setupEligible = true))
    }

    @Test fun onlyNonblankConfirmedContextIncludingGlobalProfileCompletesImport() {
        val empty = listOf(ContextEntry(id = PersonalProfile085.PROFILE_ID, text = "", global = true))
        val unconfirmed = listOf(ContextEntry(id = "manual", text = "Local context", confirmed = false))
        assertFalse(Setup099Logic.hasConfirmedContext(empty))
        assertFalse(Setup099Logic.hasConfirmedContext(unconfirmed))
        val profile = listOf(ContextEntry(id = PersonalProfile085.PROFILE_ID, text = "I prefer short summaries", global = true))
        assertTrue(Setup099Logic.hasConfirmedContext(profile))
        val manual = listOf(ContextEntry(id = "manual", text = "Keep explanations practical", origin = "manual"))
        assertTrue(Setup099Logic.hasConfirmedContext(manual))
        val importing = Setup099State(stage = Setup099Stage.IMPORT_CONTEXT, firstChatGptAccountKey = "acct-1", importRouteAcknowledgedAccountKey = "acct-1")
        assertEquals(Setup099Stage.START_FEED, Setup099Logic.contextConfirmed(importing, profile).stage)
        assertEquals(Setup099Stage.IMPORT_CONTEXT, Setup099Logic.contextConfirmed(importing, unconfirmed).stage)
    }

    @Test fun existingProviderAndUserDataAreOutsideSetupTransitions() {
        val existing = FeedState(
            onboardingComplete = true,
            connection = AiConnection(mode = "claude", model = "known-model", serviceUrl = "https://api.anthropic.com/v1"),
            context = listOf(ContextEntry(id = "keep", text = "Keep my context")),
            savedIds = setOf("saved-1"),
            items = listOf(FeedItem(id = "item-1", moduleId = "news", title = "Title", summary = "Summary")),
        )
        val transition = Setup099Logic.recordChatGptSuccess(existing.setup099, "acct-1", confirmed = true, hasConfirmedContext = true, setupEligible = false)
        val preserved = existing.copy(setup099 = transition)
        assertNull(Setup099Logic.entryStage(preserved.setup099, preserved.onboardingComplete))
        assertEquals(existing.connection, preserved.connection)
        assertEquals(existing.context, preserved.context)
        assertEquals(existing.savedIds, preserved.savedIds)
        assertEquals(existing.items, preserved.items)
    }

    @Test fun tipsFollowRealStateAndACompletedConditionClearsImmediately() {
        val noContext = emptyList<ContextEntry>()
        val manual = listOf(ContextEntry(id = "manual", text = "A confirmed note"))
        fun tip(connected: Boolean = false, context: List<ContextEntry> = noContext, background: Boolean = false,
            update: Boolean = false, dismissals: Map<String, Long> = emptyMap(), voice: Boolean = false) = Setup099Logic.nextTip(
            connected, context, background, update, dismissals, onboardingComplete = true, page = "feed", nowMillis = now,
            voiceSetupNeeded = voice,
        )
        assertEquals("missing_ai", tip()?.key)
        assertEquals("missing_context", tip(connected = true)?.key)
        assertEquals("background_disabled", tip(connected = true, context = manual)?.key)
        assertEquals("update_available", tip(connected = true, context = manual, background = true, update = true)?.key)
        assertNull(tip(connected = true, context = manual, background = true, update = false))
        assertNull(tip(connected = true, context = manual, background = true, update = true,
            dismissals = mapOf("update_available" to now)))
        assertNull(Setup099Logic.nextTip(false, noContext, false, true, emptyMap(), onboardingComplete = false, page = "feed", nowMillis = now))
        assertNull(Setup099Logic.nextTip(false, noContext, false, true, emptyMap(), onboardingComplete = true, page = "settings", nowMillis = now))
    }

    @Test fun optionalVoiceSetupTipIsLastActionableAndUsesTheSameSevenDaySnooze() {
        val context = listOf(ContextEntry(id = "manual", text = "A confirmed note", confirmed = true))
        assertNull(Setup099Logic.nextTip(
            connected = true, contextEntries = context, backgroundEnabled = true, eligibleUpdateAvailable = false,
            dismissedNotices = emptyMap(), onboardingComplete = true, page = "feed", nowMillis = now,
        ))
        val voiceTip = Setup099Logic.nextTip(
            connected = true, contextEntries = context, backgroundEnabled = true, eligibleUpdateAvailable = false,
            dismissedNotices = emptyMap(), onboardingComplete = true, page = "feed", nowMillis = now,
            voiceSetupNeeded = true,
        )
        assertEquals(Setup099Tip("voice_setup", Setup099TipAction.CONFIGURE_VOICE), voiceTip)
        assertNull(Setup099Logic.nextTip(
            connected = true, contextEntries = context, backgroundEnabled = true, eligibleUpdateAvailable = false,
            dismissedNotices = emptyMap(), onboardingComplete = true, page = "mebot", nowMillis = now,
            voiceSetupNeeded = true,
        ))
        assertEquals("missing_ai", Setup099Logic.nextTip(
            connected = false, contextEntries = context, backgroundEnabled = true, eligibleUpdateAvailable = false,
            dismissedNotices = emptyMap(), onboardingComplete = true, page = "mebot", nowMillis = now,
            voiceSetupNeeded = true,
        )?.key)
        assertEquals("update_available", Setup099Logic.nextTip(
            connected = true, contextEntries = context, backgroundEnabled = true, eligibleUpdateAvailable = true,
            dismissedNotices = emptyMap(), onboardingComplete = true, page = "feed", nowMillis = now,
            voiceSetupNeeded = true,
        )?.key)

        val dismissed = mapOf("voice_setup" to now)
        assertNull(Setup099Logic.nextTip(
            connected = true, contextEntries = context, backgroundEnabled = true, eligibleUpdateAvailable = false,
            dismissedNotices = dismissed, onboardingComplete = true, page = "feed", nowMillis = now,
            voiceSetupNeeded = true,
        ))
        assertEquals("voice_setup", Setup099Logic.nextTip(
            connected = true, contextEntries = context, backgroundEnabled = true, eligibleUpdateAvailable = false,
            dismissedNotices = dismissed, onboardingComplete = true, page = "feed",
            nowMillis = now + Setup099Logic.SNOOZE_MILLIS, voiceSetupNeeded = true,
        )?.key)
        assertFalse(Setup099Logic.tipKeys.contains("notification_nag"))
    }

    @Test fun snoozedTipYieldsToNextActionableIssueAndReturnsAfterSnooze() {
        val context = listOf(ContextEntry(id = "manual", text = "Some context"))
        val first = Setup099Logic.nextTip(false, context, false, true, emptyMap(), true, "feed", now)
        assertEquals("missing_ai", first?.key)
        val later = mapOf("missing_ai" to now)
        assertEquals("background_disabled", Setup099Logic.nextTip(false, context, false, true, later, true, "feed", now)?.key)
        assertEquals("missing_ai", Setup099Logic.nextTip(false, context, false, true, later, true, "feed", now + Setup099Logic.SNOOZE_MILLIS)?.key)
    }

    @Test fun legacyConnectionAndPreferenceSnoozesCarryForwardToNewTipKeys() {
        val oldDismissals = mapOf("connection" to now, "preferences" to now)
        assertEquals("background_disabled", Setup099Logic.nextTip(false, emptyList(), false, true,
            oldDismissals, onboardingComplete = true, page = "feed", nowMillis = now)?.key)
        assertEquals("missing_ai", Setup099Logic.nextTip(false, emptyList(), false, true,
            oldDismissals, onboardingComplete = true, page = "feed", nowMillis = now + Setup099Logic.SNOOZE_MILLIS)?.key)
    }

    @Test fun invalidOrSensitiveStateIsRejected() {
        assertTrue(runCatching { Setup099Logic.validate(Setup099State(stage = "unknown")) }.isFailure)
        assertTrue(runCatching { Setup099Logic.validate(Setup099State(firstChatGptAccountKey = "person@example.com")) }.isFailure)
        assertTrue(runCatching { Setup099Logic.validate(Setup099State(firstChatGptAccountKey = "acct-1", pendingImportAccountKey = "acct-2")) }.isFailure)
        assertTrue(runCatching { Setup099Logic.validate(Setup099State(stage = Setup099Stage.COMPLETE, pendingImportAccountKey = "acct-1", firstChatGptAccountKey = "acct-1")) }.isFailure)
        assertFalse(Setup099Logic.tipKeys.contains("notification_nag"))
    }
}
