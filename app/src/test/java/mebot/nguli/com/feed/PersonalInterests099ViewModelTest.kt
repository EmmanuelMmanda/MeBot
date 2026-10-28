package mebot.nguli.com.feed

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import mebot.nguli.com.core.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Deliberate optional-interest edits persist locally without provider work or broad profile writes. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = PersonalDailySelection098TestApplication::class)
class PersonalInterests099ViewModelTest {
    private lateinit var app: PersonalDailySelection098TestApplication
    private lateinit var store: ViewModelStore
    private lateinit var vm: FeedViewModel
    private lateinit var initial: FeedState
    private lateinit var item: FeedItem

    @Before fun setUp(): Unit = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        app = ApplicationProvider.getApplicationContext()
        app.feedSecrets.clear()
        app.feedRepository.erase()
        app.feedSecrets.write("synthetic-interests-test-key")

        val now = System.currentTimeMillis()
        val news = ModuleCatalog.defaults.single { it.id == "news" }.copy(
            enabled = true,
            query = "Preserve this customized preset query",
        )
        val customTechnology = FeedModule(
            id = "custom-technology-module",
            kind = "technology",
            title = "Custom technology watch",
            query = "Preserve this custom module",
            enabled = false,
            sources = listOf(FeedSource("Example source", "https://example.com/custom")),
            preferences = mapOf("language" to "sw"),
        )
        val global = ContextEntry(
            id = PersonalProfile085.PROFILE_ID,
            text = "A global preference that must remain unchanged.",
            category = PersonalProfile085.PROFILE_CATEGORY,
            scopes = emptySet(),
            global = true,
        )
        val unrelated = ContextEntry(
            id = "manual-context-entry",
            text = "A separate confirmed note about a project.",
            category = "project",
            scopes = setOf("business"),
            origin = "manual",
        )
        val onboarding = ContextEntry(
            id = "onboarding-interests",
            text = "Previously selected interests.",
            category = "interest",
            scopes = setOf("news"),
            origin = "onboarding",
        )
        item = FeedItem(
            id = "cached-item",
            moduleId = "news",
            title = "A cached local example",
            summary = "A synthetic source-backed record for persistence tests.",
            why = "Old context-specific explanation.",
            sourceName = "Example publisher",
            sourceUrl = "https://example.com/story",
            publishedAtMillis = now - 60_000,
            fetchedAtMillis = now,
            relevance = 82,
            acquisition = FeedAcquisition.PUBLISHER_RSS,
            authorship = FeedAuthorship.PUBLISHER,
        )
        initial = FeedState(
            onboardingComplete = false,
            name = "Sample Reader",
            region = "Dar es Salaam",
            timezone = "Africa/Dar_es_Salaam",
            // Match the repository's startup migration (which merges the canonical preset catalog).
            modules = ModuleCatalog.mergeCatalog(listOf(news, customTechnology)),
            context = listOf(global, unrelated, onboarding),
            items = listOf(item),
            connection = AiConnection(mode = "direct", model = "synthetic-interests-model"),
            lastRefreshMillis = now,
            lastAiRefreshMillis = now,
            dailyRequestLimit = 12,
            usageDate = FeedLogic.dateAt(now, "Africa/Dar_es_Salaam"),
            requestsToday = 3,
            personalSeedApplied = true,
            relevanceSignals = listOf(RelevanceSignal(
                itemId = item.id,
                moduleId = item.moduleId,
                relevant = true,
                title = item.title,
                terms = setOf("cached", "local"),
                updatedAtMillis = now,
            )),
            setup099 = Setup099State(
                stage = Setup099Stage.IMPORT_CONTEXT,
                firstChatGptAccountKey = "fixture-account-key",
                pendingImportAccountKey = "fixture-account-key",
            ),
        )
        initial = PersonalDailySelectionLogic098.ensure(initial, now)
        app.feedRepository.update { initial }
        app.resetCounters()
        store = ViewModelStore()
        vm = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(app))[FeedViewModel::class.java]
        withTimeout(10_000) { vm.ui.first { it.state != null && it.connected && !it.loading } }
        initial = app.feedRepository.current()
        Unit
    }

    @After fun tearDown() {
        if (::store.isInitialized) store.clear()
        Dispatchers.resetMain()
    }

    @Test fun scopedEditChangesOnlyCanonicalPresetAndInterestNoteAndInvalidatesDerivedPersonalization() = runBlocking {
        assertFalse("Opening an editor must not mark interests configured", app.feedRepository.current().interestsConfigured)
        val token = vm.beginPersonalInterestsEdit099()
        assertEquals(initial, app.feedRepository.current())
        // Exercise the migration-safe case where a selected canonical preset is absent locally.
        initial = app.feedRepository.update { state ->
            state.copy(modules = state.modules.filterNot { it.id in setOf("technology", "weather") })
        }

        var callback: PersonalInterestsSaveResult099? = null
        vm.savePersonalInterests099(
            kinds = setOf("technology", "weather"),
            customText = "  I am learning practical machine learning for community projects.  ",
            sessionToken = token,
            revision = 1L,
        ) { callback = it }.join()

        val saved = app.feedRepository.current()
        assertEquals(PersonalInterestsSaveResult099.Status.SAVED, callback?.status)
        assertEquals(saved, callback?.state)
        assertTrue(saved.interestsConfigured)
        val enabledPresets = saved.modules.filter { it.id in ModuleCatalog.defaults.map(FeedModule::id) && it.enabled }
            .map { it.id }.toSet()
        assertEquals(setOf("technology", "weather"), enabledPresets)
        val restoredNews = saved.modules.single { it.id == "news" }
        assertEquals("Preserve this customized preset query", restoredNews.query)
        assertFalse(restoredNews.enabled)
        assertTrue("A selected missing preset is materialized", saved.modules.any { it.id == "technology" && it.enabled })
        assertEquals("Same-kind custom modules are not preset-owned", initial.modules.single { it.id == "custom-technology-module" },
            saved.modules.single { it.id == "custom-technology-module" })

        val expectedScopes = setOf("technology") // Weather is deliberately never personal context.
        val savedNote = saved.context.single { it.id == "onboarding-interests" }
        assertEquals("I am learning practical machine learning for community projects.", savedNote.text)
        assertEquals(expectedScopes, savedNote.scopes)
        assertTrue(savedNote.confirmed)
        assertEquals("Existing note identity/metadata is retained", initial.context.single { it.id == "onboarding-interests" }.origin, savedNote.origin)
        assertEquals(initial.context.single { it.id == "onboarding-interests" }.category, savedNote.category)
        assertEquals(initial.context.single { PersonalProfile085.isGlobal(it) }, saved.context.single { PersonalProfile085.isGlobal(it) })
        assertEquals(initial.context.single { it.id == "manual-context-entry" }, saved.context.single { it.id == "manual-context-entry" })
        assertEquals(initial.name, saved.name)
        assertEquals(initial.region, saved.region)
        assertEquals(initial.timezone, saved.timezone)
        assertEquals(initial.connection, saved.connection)
        assertEquals(initial.dailyRequestLimit, saved.dailyRequestLimit)
        assertEquals(initial.usageDate, saved.usageDate)
        assertEquals(initial.requestsToday, saved.requestsToday)
        assertEquals("Explicit usefulness feedback is user data, not a derived cache", initial.relevanceSignals, saved.relevanceSignals)
        assertEquals(Setup099Stage.START_FEED, saved.setup099.stage)
        assertNull("Changed context invalidates the device-local daily projection", saved.personalDailySelection098)
        assertEquals(0L, saved.lastRefreshMillis)
        assertEquals(0L, saved.lastAiRefreshMillis)
        assertEquals("Old relevance explanations are context-dependent", "", saved.items.single().why)
        assertEquals(0, saved.items.single().relevance)
        assertEquals(0, app.sourceReaderCalls.get())
        assertEquals(0, app.aiProviderCalls.get())
    }

    @Test fun blankNoteAndEmptySelectionDoNotEraseExistingNoteOrFallbackToNews() = runBlocking {
        val token = vm.beginPersonalInterestsEdit099()
        var result: PersonalInterestsSaveResult099? = null
        vm.savePersonalInterests099(emptySet(), "   ", token, 0L) { result = it }.join()

        val saved = app.feedRepository.current()
        assertEquals(PersonalInterestsSaveResult099.Status.SAVED, result?.status)
        assertTrue("Empty is an explicit empty selection, never a hidden News fallback",
            saved.modules.filter { it.id in ModuleCatalog.defaults.map(FeedModule::id) }.none { it.enabled })
        assertEquals("Existing onboarding note is preserved by a blank edit", initial.context, saved.context)
        assertEquals("Custom same-kind module remains untouched", initial.modules.single { it.id == "custom-technology-module" },
            saved.modules.single { it.id == "custom-technology-module" })
        assertTrue(saved.interestsConfigured)
        assertEquals(0, app.sourceReaderCalls.get())
        assertEquals(0, app.aiProviderCalls.get())
    }

    @Test fun emptyScopesStayLocalAndOnlyConfirmedNonblankContentAdvancesSetup() = runBlocking {
        app.feedRepository.update { it.copy(context = emptyList()) }
        val blankSession = vm.beginPersonalInterestsEdit099()
        var blankResult: PersonalInterestsSaveResult099? = null
        vm.savePersonalInterests099(emptySet(), "", blankSession, 0L) { blankResult = it }.join()
        val blankSaved = app.feedRepository.current()
        assertEquals(PersonalInterestsSaveResult099.Status.SAVED, blankResult?.status)
        assertTrue(blankSaved.context.isEmpty())
        assertEquals("Preview/blank context cannot advance first-run import", Setup099Stage.IMPORT_CONTEXT, blankSaved.setup099.stage)
        assertEquals("No automatic News fallback", emptySet<String>(), blankSaved.modules
            .filter { it.id in ModuleCatalog.defaults.map(FeedModule::id) && it.enabled }.map { it.id }.toSet())

        val noteSession = vm.beginPersonalInterestsEdit099()
        var noteResult: PersonalInterestsSaveResult099? = null
        vm.savePersonalInterests099(emptySet(), "A deliberately unscoped interest note.", noteSession, 0L) { noteResult = it }.join()
        val noteSaved = app.feedRepository.current()
        val note = noteSaved.context.single { it.id == "onboarding-interests" }
        assertEquals(PersonalInterestsSaveResult099.Status.SAVED, noteResult?.status)
        assertEquals(emptySet<String>(), note.scopes)
        assertTrue(note.confirmed)
        assertFalse("An empty scope set is not the global profile", PersonalProfile085.isGlobal(note))
        assertEquals(Setup099Stage.START_FEED, noteSaved.setup099.stage)
    }

    @Test fun unsupportedNonPresetKindFailsWithoutWritingOrMarkingConfigured() = runBlocking {
        val before = app.feedRepository.current()
        val token = vm.beginPersonalInterestsEdit099()
        var result: PersonalInterestsSaveResult099? = null
        vm.savePersonalInterests099(setOf("watch"), "A valid-looking but non-preset topic.", token, 0L) { result = it }.join()
        assertEquals(PersonalInterestsSaveResult099.Status.FAILED, result?.status)
        assertEquals("invalid_input", result?.errorCode)
        assertEquals(before, app.feedRepository.current())
        assertFalse(app.feedRepository.current().interestsConfigured)
    }

    @Test fun conflictingImportedPresetIdIsPreservedAndFailsClosedInsteadOfDuplicating() = runBlocking {
        val before = app.feedRepository.update { state ->
            state.copy(modules = state.modules.filterNot { it.id in setOf("technology", "weather") } +
                FeedModule(id = "technology", kind = "watch", title = "Imported custom watch", enabled = true,
                    query = "Preserve this colliding record"))
        }
        val token = vm.beginPersonalInterestsEdit099()
        var result: PersonalInterestsSaveResult099? = null
        vm.savePersonalInterests099(setOf("technology"), "A note that cannot be committed with the collision.", token, 0L) { result = it }.join()

        assertEquals(PersonalInterestsSaveResult099.Status.FAILED, result?.status)
        assertEquals("module_identity_conflict", result?.errorCode)
        assertEquals("No duplicate ID or partial interest mutation", before, app.feedRepository.current())

        // Correct the module identity and retry the same durable tuple. A prior route-owned
        // conflict must not stick as a global FeedUiState error after the retry is saved.
        app.feedRepository.update { state -> state.copy(modules = state.modules.filterNot { it.id == "technology" }) }
        var retry: PersonalInterestsSaveResult099? = null
        vm.savePersonalInterests099(setOf("technology"), "A note that cannot be committed with the collision.", token, 0L) { retry = it }.join()
        assertEquals(PersonalInterestsSaveResult099.Status.SAVED, retry?.status)
        assertEquals("A note that cannot be committed with the collision.", app.feedRepository.current().context.single { it.id == "onboarding-interests" }.text)
        assertNotEquals("module_identity_conflict", vm.ui.value.error)
        assertEquals(0, app.sourceReaderCalls.get())
        assertEquals(0, app.aiProviderCalls.get())
    }

    @Test fun newerRevisionAndReopenedSessionFenceDelayedAutosaveAndDuplicateFlushJoins() = runBlocking {
        var blockedSession = 1L
        var blockedRevision = 4L
        var blockedEntered = CompletableDeferred<Unit>()
        var releaseBlocked = CompletableDeferred<Unit>()
        vm.beforePersonalInterestsCommit099 = { session, revision ->
            if (session == blockedSession && revision == blockedRevision) {
                blockedEntered.complete(Unit)
                releaseBlocked.await()
            }
        }

        val firstSession = vm.beginPersonalInterestsEdit099()
        var staleRevision: PersonalInterestsSaveResult099? = null
        var newestRevision: PersonalInterestsSaveResult099? = null
        var duplicateFlush: PersonalInterestsSaveResult099? = null
        val delayed = vm.savePersonalInterests099(setOf("news"), "Outdated editor draft.", firstSession, 4L) { staleRevision = it }
        withTimeout(5_000) { blockedEntered.await() }
        val newest = vm.savePersonalInterests099(setOf("technology"), "Newest draft for the same editor.", firstSession, 5L) { newestRevision = it }
        val flush = vm.savePersonalInterests099(setOf("technology"), "Newest draft for the same editor.", firstSession, 5L) { duplicateFlush = it }
        releaseBlocked.complete(Unit)
        delayed.join(); newest.join(); flush.join()

        assertEquals(PersonalInterestsSaveResult099.Status.SUPERSEDED, staleRevision?.status)
        assertEquals(PersonalInterestsSaveResult099.Status.SAVED, newestRevision?.status)
        assertEquals(PersonalInterestsSaveResult099.Status.SAVED, duplicateFlush?.status)
        assertEquals("Newest draft for the same editor.", app.feedRepository.current().context.single { it.id == "onboarding-interests" }.text)

        // A fresh route gets a greater session token. Its revision 0 wins over the old route's
        // delayed revision 99 disposal, which must not overwrite the reopened draft.
        val oldRoute = vm.beginPersonalInterestsEdit099()
        blockedSession = oldRoute
        blockedRevision = 99L
        blockedEntered = CompletableDeferred()
        releaseBlocked = CompletableDeferred()
        var staleRouteResult: PersonalInterestsSaveResult099? = null
        var reopenedResult: PersonalInterestsSaveResult099? = null
        val oldDispose = vm.savePersonalInterests099(setOf("news"), "Old route disposal.", oldRoute, 99L) { staleRouteResult = it }
        withTimeout(5_000) { blockedEntered.await() }
        val reopened = vm.beginPersonalInterestsEdit099()
        assertTrue(reopened > oldRoute)
        val newerRoute = vm.savePersonalInterests099(setOf("business"), "Reopened editor draft.", reopened, 0L) { reopenedResult = it }
        releaseBlocked.complete(Unit)
        oldDispose.join(); newerRoute.join()
        assertEquals(PersonalInterestsSaveResult099.Status.SUPERSEDED, staleRouteResult?.status)
        assertEquals(PersonalInterestsSaveResult099.Status.SAVED, reopenedResult?.status)

        // Re-submit a stale old-session disposal after the new session has been accepted.
        var rejected: PersonalInterestsSaveResult099? = null
        vm.savePersonalInterests099(setOf("news"), "Late stale route flush.", oldRoute, 100L) { rejected = it }.join()
        assertEquals(PersonalInterestsSaveResult099.Status.SUPERSEDED, rejected?.status)
        assertEquals("Reopened editor draft.", app.feedRepository.current().context.single { it.id == "onboarding-interests" }.text)
        assertEquals(0, app.sourceReaderCalls.get())
        assertEquals(0, app.aiProviderCalls.get())
    }
}
