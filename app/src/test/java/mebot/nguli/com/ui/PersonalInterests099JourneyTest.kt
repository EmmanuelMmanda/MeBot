package mebot.nguli.com.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import mebot.nguli.com.MainActivity
import mebot.nguli.com.R
import mebot.nguli.com.core.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Real route/repository checks; editing topics must not perform or imply an AI/provider request. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi", application = ChatGptJourneyApplication::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PersonalInterests099JourneyTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val app get() = compose.activity.application as ChatGptJourneyApplication
    private lateinit var original: FeedState

    private val profile = ContextEntry(
        id = PersonalProfile085.PROFILE_ID, text = "Saved profile", category = "profile", scopes = emptySet(),
        confirmed = true, origin = "manual", updatedAtMillis = 10L, global = true,
    )
    private val interests = ContextEntry(
        id = "onboarding-interests", text = "Gardening", scopes = setOf("news"),
        confirmed = true, origin = "onboarding", updatedAtMillis = 11L,
    )
    private val legacy = ContextEntry(
        id = "legacy-learning", text = "Keep my course notes", scopes = setOf("learning"),
        confirmed = true, origin = "manual", updatedAtMillis = 12L,
    )
    private val customModule = FeedModule(
        id = "user-custom-technology", kind = "technology", title = "My custom technology watch",
        query = "Only keep this local custom setup", enabled = true,
    )

    @Before fun prepareOnboardedLocalProfile() {
        runBlocking {
            app.feedEngine.clearAllCredentials()
            app.repository.erase()
            app.feedRepository.erase()
            app.repository.update { AppState(profile = Profile(onboardingComplete = true)) }
            val modules = ModuleCatalog.defaults.map { module ->
                when (module.id) {
                    "news" -> module.copy(enabled = true)
                    "technology" -> module.copy(enabled = false)
                    else -> module
                }
            } + customModule
            original = FeedState(
                onboardingComplete = true,
                modules = modules,
                context = listOf(profile, interests, legacy),
                connection = AiConnection(),
                personalSeedApplied = true,
            )
            app.feedRepository.update { original }
        }
        app.alarms.clearAll()
        app.checked.clear(); app.asked.clear(); app.searched.clear(); app.providerDiscoveries.clear()
        waitForTag("feed-screen")
        compose.waitUntil(10_000) {
            val saved = state()
            saved.onboardingComplete && saved.context == listOf(profile, interests, legacy) &&
                saved.modules.any { it.id == "news" && it.enabled } && saved.modules.any { it.id == "technology" && !it.enabled }
        }
        original = state()
    }

    @Test fun chooserIsReachableFromYouAndContextAndImportAndAutosavesWithoutLosingContextDraft() {
        val networkCounts = listOf(app.checked.size, app.asked.size, app.searched.size, app.providerDiscoveries.size)

        // You/About You has a direct, optional entry. Merely opening and leaving does not write.
        compose.onNodeWithTag("nav-you").performClick()
        compose.onNodeWithTag("you-personal-interests").performScrollTo().performClick()
        waitForTag("personal-interests-099")
        assertEquals(original.modules, state().modules)
        assertEquals(original.context, state().context)
        assertEquals(original.interestsConfigured, state().interestsConfigured)
        compose.onNodeWithTag("personal-interests-selected-count")
            .assertTextContains(compose.activity.getString(R.string.interests_selected, 1))
        compose.onNodeWithTag("personal-interests-interest-search").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("personal-interests-interest-group-life").performScrollTo().performClick()
        compose.onNodeWithTag("personal-interests-interest-travel").assertExists()
        compose.onNodeWithTag("personal-interests-interest-group-all").performScrollTo().performClick()
        compose.onNodeWithTag("personal-interests-interest-search").performScrollTo().performTextInput("tech")
        compose.onNodeWithTag("personal-interests-interest-technology").performScrollTo().assertExists()
        compose.onNodeWithTag("personal-interests-interest-search").performTextReplacement("")
        compose.onNodeWithTag("personal-interests-interests-more").performScrollTo().performClick()
        compose.onNodeWithTag("personal-interests-interest-horoscope").assertExists()
        pressBack()
        waitForTag("you-context")
        assertEquals("Closing an untouched chooser leaves module choices unchanged", original.modules, state().modules)
        assertEquals("Closing an untouched chooser leaves context unchanged", original.context, state().context)

        // Open About You with an unsaved global-profile draft, then edit custom interests.
        compose.onNodeWithTag("you-context").performScrollTo().performClick()
        waitForTag("context-screen")
        compose.onNodeWithTag("context-edit").performScrollTo().performTextReplacement("Unsaved global profile draft")
        compose.onNodeWithTag("context-personal-interests").performScrollTo().performClick()
        waitForTag("personal-interests-099")
        compose.onNodeWithTag("personal-interests-custom-note").performScrollTo().assertTextContains("Gardening")
        compose.onNodeWithTag("personal-interests-interest-search").performScrollTo().performTextInput("tech")
        compose.onNodeWithTag("personal-interests-interest-technology").performScrollTo().performClick()
        compose.onNodeWithTag("personal-interests-custom-note").performScrollTo().performTextReplacement("Urban gardening")

        // Rotation restores the dirty selection/text under a fresh VM-issued save session.
        compose.activityRule.scenario.recreate()
        waitForTag("personal-interests-099")
        compose.waitUntil(15_000) {
            val saved = state()
            saved.modules.any { it.id == "technology" && it.kind == "technology" && it.enabled } &&
                saved.context.any { it.id == interests.id && it.text == "Urban gardening" && it.confirmed }
        }
        compose.onNodeWithTag("personal-interests-custom-note").performScrollTo().assertTextContains("Urban gardening")
        pressBack()
        waitForTag("context-screen")

        // The remote interest-note save must merge with, not replace, the other local context draft.
        compose.onNodeWithTag("context-edit").performScrollTo().assertTextContains("Unsaved global profile draft")
        compose.onNodeWithTag("context-edit-onboarding-interests").performScrollTo().assertTextContains("Urban gardening")
        compose.onNodeWithTag("context-save").performScrollTo().performClick()
        compose.waitUntil(15_000) {
            val saved = state()
            saved.context.any { it.id == profile.id && it.text == "Unsaved global profile draft" } &&
                saved.context.any { it.id == interests.id && it.text == "Urban gardening" }
        }
        val committed = state()
        assertTrue(committed.interestsConfigured)
        assertEquals(setOf("news", "technology"), committed.modules.filter { it.id in setOf("news", "technology") && it.enabled }.map { it.id }.toSet())
        assertEquals("Custom same-kind modules are not rewritten by the preset picker", customModule, committed.modules.single { it.id == customModule.id })
        val global = committed.context.single { it.id == profile.id }
        assertTrue(global.global)
        assertTrue(global.scopes.isEmpty())
        val savedInterest = committed.context.single { it.id == interests.id }
        assertEquals(setOf("news", "technology"), savedInterest.scopes)
        assertTrue(savedInterest.confirmed)
        assertEquals(legacy, committed.context.single { it.id == legacy.id })

        // The same optional chooser is available within the guided import screen and returns there.
        waitForTag("you-context")
        compose.onNodeWithTag("you-context").performScrollTo().performClick()
        waitForTag("context-screen")
        compose.onNodeWithTag("context-import-entry").performScrollTo().performClick()
        waitForTag("context-import-screen")
        compose.onNodeWithTag("context-import-interests").performScrollTo().performClick()
        waitForTag("personal-interests-099")
        compose.onNodeWithTag("personal-interests-custom-note").performScrollTo().assertTextContains("Urban gardening")
        pressBack()
        waitForTag("context-import-screen")

        assertEquals("Topic editing is local; it must not call any provider", networkCounts,
            listOf(app.checked.size, app.asked.size, app.searched.size, app.providerDiscoveries.size))
        assertEquals("Provider selection remains untouched", original.connection, committed.connection)
    }

    @Test fun guidedFirstRunContextImportCanOpenTopicsAndSystemBackReturnsToImport() {
        runBlocking {
            app.repository.update { AppState(profile = Profile(onboardingComplete = false)) }
            app.feedRepository.update { FeedState(setup099 = Setup099State(stage = Setup099Stage.IMPORT_CONTEXT)) }
        }
        waitForTag("setup-099-onboarding")
        compose.onNodeWithTag("setup099-import-context").performScrollTo().performClick()
        waitForTag("context-import-screen")
        compose.onNodeWithTag("context-import-interests").performScrollTo().performClick()
        waitForTag("personal-interests-099")
        compose.onNodeWithTag("setup099-progress").assertExists()
        pressBack()
        waitForTag("context-import-screen")
        assertEquals("Browsing topics does not skip context import", Setup099Stage.IMPORT_CONTEXT, state().setup099.stage)
        assertFalse(state().onboardingComplete)
    }

    private fun state(): FeedState = runBlocking { app.feedRepository.current() }

    private fun waitForTag(tag: String) {
        compose.waitUntil(15_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun pressBack() {
        compose.activityRule.scenario.onActivity { activity -> activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }
}
