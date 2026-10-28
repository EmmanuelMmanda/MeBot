package mebot.nguli.com.core

import kotlinx.serialization.Serializable

/** Persisted state for the first-run 0.9.9 guided setup. Codes are strings for migration safety. */
@Serializable
data class Setup099State(
    val stage: String = Setup099Stage.INTRO,
    /** Local non-secret preference marker; `unknown` preserves 0.9.8 migrations. */
    val preferredConnectionEntry099: String = Setup099ConnectionEntry.UNKNOWN,
    /** Opaque local account registration key. Never an email, token, profile field, or export field. */
    val firstChatGptAccountKey: String = "",
    /** Set only after a verified ChatGPT connection; consumed after the import route is entered. */
    val pendingImportAccountKey: String = "",
    /** Idempotency marker for process death/repeated connection completion. */
    val importRouteAcknowledgedAccountKey: String = "",
)

object Setup099ConnectionEntry {
    const val UNKNOWN = "unknown"
    const val CHATGPT = "chatgpt"
    const val USER_SELECTED = "user_selected"
    val values: Set<String> = setOf(UNKNOWN, CHATGPT, USER_SELECTED)
}

object Setup099Stage {
    const val INTRO = "intro"
    const val CONNECT_CHATGPT = "connect_chatgpt"
    const val IMPORT_CONTEXT = "import_context"
    const val START_FEED = "start_feed"
    const val COMPLETE = "complete"

    val values: Set<String> = setOf(INTRO, CONNECT_CHATGPT, IMPORT_CONTEXT, START_FEED, COMPLETE)
}

enum class Setup099TipAction { CONNECT_CHATGPT, ADD_CONTEXT, BACKGROUND_SETTINGS, SOFTWARE_UPDATES, CONFIGURE_VOICE }

data class Setup099Tip(val key: String, val action: Setup099TipAction)

/** State transitions and tip selection are deliberately Android-free so interrupted paths are testable. */
object Setup099Logic {
    const val SNOOZE_MILLIS = 7L * 24 * 60 * 60 * 1000
    const val MAX_ACCOUNT_KEY_LENGTH = 160
    private val safeAccountKey = Regex("[A-Za-z0-9._:/-]{1,$MAX_ACCOUNT_KEY_LENGTH}")

    val tipKeys: Set<String> = setOf("missing_ai", "missing_context", "background_disabled", "update_available", "voice_setup")

    fun validStage(stage: String): Boolean = stage in Setup099Stage.values

    /** ChatGPT is the default only for a new user or after the user has successfully chosen it. */
    fun prefersChatGpt(state: Setup099State, onboardingComplete: Boolean): Boolean =
        state.preferredConnectionEntry099 == Setup099ConnectionEntry.CHATGPT ||
            (!onboardingComplete && state.preferredConnectionEntry099 == Setup099ConnectionEntry.UNKNOWN)

    /** Record only after the selected provider has successfully connected/verified. */
    fun recordPreferredConnection(state: Setup099State, entry: String): Setup099State {
        if (entry !in Setup099ConnectionEntry.values || entry == Setup099ConnectionEntry.UNKNOWN) return state
        return state.copy(preferredConnectionEntry099 = entry).also(::validate)
    }

    fun validAccountKey(key: String, blankAllowed: Boolean = true): Boolean =
        if (key.isBlank()) blankAllowed && key.isEmpty() else safeAccountKey.matches(key)

    /** Called by FeedLogic.validate; bounds every value stored in the serialized record. */
    fun validate(state: Setup099State) {
        require(validStage(state.stage)) { "Invalid 0.9.9 setup stage" }
        require(state.preferredConnectionEntry099 in Setup099ConnectionEntry.values) { "Invalid preferred setup connection" }
        require(validAccountKey(state.firstChatGptAccountKey)) { "Invalid setup account key" }
        require(validAccountKey(state.pendingImportAccountKey)) { "Invalid pending setup account key" }
        require(validAccountKey(state.importRouteAcknowledgedAccountKey)) { "Invalid acknowledged setup account key" }
        require(state.pendingImportAccountKey.isEmpty() || state.pendingImportAccountKey == state.firstChatGptAccountKey) {
            "Pending setup import must match the first connected ChatGPT account"
        }
        require(state.importRouteAcknowledgedAccountKey.isEmpty() || state.importRouteAcknowledgedAccountKey == state.firstChatGptAccountKey) {
            "Acknowledged setup import must match the first connected ChatGPT account"
        }
        require(state.pendingImportAccountKey.isEmpty() || state.importRouteAcknowledgedAccountKey.isEmpty()) {
            "Setup import cannot be pending and acknowledged at the same time"
        }
        require(state.stage != Setup099Stage.COMPLETE || state.pendingImportAccountKey.isEmpty()) {
            "Completed setup cannot have a pending import route"
        }
    }

    /** Existing installs are never forced into setup just because the new field defaults to intro. */
    fun entryStage(state: Setup099State, onboardingComplete: Boolean, explicitlyOpened: Boolean = false): String? {
        validate(state)
        if (state.stage == Setup099Stage.COMPLETE) return null
        return if (!onboardingComplete || explicitlyOpened) state.stage else null
    }

    /** A canceled, failed, non-ChatGPT, or otherwise unverified attempt must pass `confirmed=false`. */
    fun recordChatGptSuccess(
        state: Setup099State,
        accountKey: String,
        confirmed: Boolean,
        hasConfirmedContext: Boolean,
        setupEligible: Boolean = false,
    ): Setup099State {
        if (!confirmed || !setupEligible || !validAccountKey(accountKey, blankAllowed = false) ||
            state.stage == Setup099Stage.COMPLETE || state.firstChatGptAccountKey.isNotEmpty()
        ) return state
        val next = state.copy(
            stage = if (hasConfirmedContext) Setup099Stage.START_FEED else Setup099Stage.IMPORT_CONTEXT,
            firstChatGptAccountKey = accountKey,
            pendingImportAccountKey = if (hasConfirmedContext) "" else accountKey,
            importRouteAcknowledgedAccountKey = "",
        )
        validate(next)
        return next
    }

    /** Consume the pending route only after the host has entered the import page. Safe to repeat. */
    fun acknowledgeImportRoute(state: Setup099State, accountKey: String): Setup099State {
        if (state.pendingImportAccountKey != accountKey || accountKey != state.firstChatGptAccountKey ||
            state.stage != Setup099Stage.IMPORT_CONTEXT
        ) return state
        val next = state.copy(pendingImportAccountKey = "", importRouteAcknowledgedAccountKey = accountKey)
        validate(next)
        return next
    }

    /** Confirmed manual or imported notes count; an empty, unconfirmed, or preview-only import does not. */
    fun hasConfirmedContext(entries: List<ContextEntry>): Boolean =
        entries.any { it.confirmed && it.text.isNotBlank() }

    fun contextConfirmed(state: Setup099State, entries: List<ContextEntry>): Setup099State {
        if (!hasConfirmedContext(entries) || state.stage != Setup099Stage.IMPORT_CONTEXT) return state
        val accountKey = state.pendingImportAccountKey.ifBlank { state.firstChatGptAccountKey }
        val next = state.copy(
            stage = Setup099Stage.START_FEED,
            pendingImportAccountKey = "",
            importRouteAcknowledgedAccountKey = accountKey.ifBlank { state.importRouteAcknowledgedAccountKey },
        )
        validate(next)
        return next
    }

    /** The user can continue with public feeds and add context later; this never changes provider settings. */
    fun skipContext(state: Setup099State): Setup099State {
        if (state.stage != Setup099Stage.IMPORT_CONTEXT) return state
        val next = state.copy(
            stage = Setup099Stage.START_FEED,
            pendingImportAccountKey = "",
            importRouteAcknowledgedAccountKey = state.importRouteAcknowledgedAccountKey.ifBlank { state.firstChatGptAccountKey },
        )
        validate(next)
        return next
    }

    fun nextStage(state: Setup099State): Setup099State = when (state.stage) {
        Setup099Stage.INTRO -> state.copy(stage = Setup099Stage.CONNECT_CHATGPT)
        Setup099Stage.CONNECT_CHATGPT -> state.copy(stage = Setup099Stage.IMPORT_CONTEXT)
        Setup099Stage.IMPORT_CONTEXT -> skipContext(state)
        Setup099Stage.START_FEED -> finish(state)
        else -> state
    }.also(::validate)

    fun previousStage(state: Setup099State): Setup099State = when (state.stage) {
        Setup099Stage.CONNECT_CHATGPT -> state.copy(stage = Setup099Stage.INTRO)
        Setup099Stage.IMPORT_CONTEXT -> state.copy(stage = Setup099Stage.CONNECT_CHATGPT,
            pendingImportAccountKey = "",
            importRouteAcknowledgedAccountKey = state.importRouteAcknowledgedAccountKey.ifBlank { state.firstChatGptAccountKey })
        Setup099Stage.START_FEED -> state.copy(stage = Setup099Stage.IMPORT_CONTEXT)
        else -> state
    }.also(::validate)

    fun setStage(state: Setup099State, stage: String): Setup099State {
        if (!validStage(stage) || state.stage == Setup099Stage.COMPLETE) return state
        if (stage == Setup099Stage.COMPLETE) return finish(state)
        val leavesPendingImport = stage != Setup099Stage.IMPORT_CONTEXT && state.pendingImportAccountKey.isNotEmpty()
        val next = state.copy(
            stage = stage,
            pendingImportAccountKey = if (leavesPendingImport) "" else state.pendingImportAccountKey,
            importRouteAcknowledgedAccountKey = if (leavesPendingImport)
                state.importRouteAcknowledgedAccountKey.ifBlank { state.firstChatGptAccountKey }
            else state.importRouteAcknowledgedAccountKey,
        )
        validate(next)
        return next
    }

    fun finish(state: Setup099State, preferChatGptIfUnknown: Boolean = false): Setup099State = state.copy(
        stage = Setup099Stage.COMPLETE,
        pendingImportAccountKey = "",
        importRouteAcknowledgedAccountKey = state.importRouteAcknowledgedAccountKey.ifBlank { state.firstChatGptAccountKey },
        preferredConnectionEntry099 = if (preferChatGptIfUnknown &&
            state.preferredConnectionEntry099 == Setup099ConnectionEntry.UNKNOWN
        ) Setup099ConnectionEntry.CHATGPT else state.preferredConnectionEntry099,
    ).also(::validate)

    /** Returns one real, actionable tip; conditions that are already fixed disappear immediately. */
    fun nextTip(
        connected: Boolean,
        contextEntries: List<ContextEntry>,
        backgroundEnabled: Boolean,
        eligibleUpdateAvailable: Boolean,
        dismissedNotices: Map<String, Long>,
        onboardingComplete: Boolean,
        page: String,
        nowMillis: Long,
        voiceSetupNeeded: Boolean = false,
    ): Setup099Tip? {
        if (!onboardingComplete || page !in setOf("feed", "mebot")) return null
        val possible = buildList {
            if (!connected) add(Setup099Tip("missing_ai", Setup099TipAction.CONNECT_CHATGPT))
            if (!hasConfirmedContext(contextEntries)) add(Setup099Tip("missing_context", Setup099TipAction.ADD_CONTEXT))
            if (!backgroundEnabled) add(Setup099Tip("background_disabled", Setup099TipAction.BACKGROUND_SETTINGS))
            if (eligibleUpdateAvailable) add(Setup099Tip("update_available", Setup099TipAction.SOFTWARE_UPDATES))
            // Voice configuration belongs on the feed/setup surface, never as a prompt inside MeBot.
            if (voiceSetupNeeded && page == "feed") add(Setup099Tip("voice_setup", Setup099TipAction.CONFIGURE_VOICE))
        }
        fun dismissalTime(key: String): Long? {
            val legacyKey = when (key) {
                "missing_ai" -> "connection"
                "missing_context" -> "preferences"
                else -> null
            }
            return listOfNotNull(dismissedNotices[key], legacyKey?.let(dismissedNotices::get)).maxOrNull()
        }
        return possible.firstOrNull { tip ->
            tip.key in tipKeys && dismissalTime(tip.key)?.let { dismissedAt ->
                nowMillis - dismissedAt >= SNOOZE_MILLIS || dismissedAt > nowMillis
            } != false
        }
    }
}
