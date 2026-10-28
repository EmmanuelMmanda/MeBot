package mebot.nguli.com.ui

import android.content.Intent
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import mebot.nguli.com.R
import mebot.nguli.com.core.AiConnection
import mebot.nguli.com.core.FeedState
import mebot.nguli.com.core.Settings
import mebot.nguli.com.feed.FeedUiState
import mebot.nguli.com.feed.auth.ChatGptAccount
import mebot.nguli.com.feed.auth.ChatGptAuthState
import mebot.nguli.com.feed.auth.ChatGptDiagnostic
import mebot.nguli.com.feed.auth.ChatGptModel
import mebot.nguli.com.ui.feed.CHATGPT_USAGE_URL
import mebot.nguli.com.ui.feed.ChatGptPlanNotice
import mebot.nguli.com.ui.feed.ConnectionScreen
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** UI-only provider fixtures. These tests never authenticate or call a live model. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MeBotChatGptUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val ui = mutableStateOf(FeedUiState(state = FeedState(onboardingComplete = true), loading = false))
    private val signIns = mutableListOf<String?>()
    private val selections = mutableListOf<String>()
    private val accountModels = mutableListOf<Pair<String, String>>()
    private val models = mutableListOf<String>()
    private val signOuts = mutableListOf<String>()
    private val removals = mutableListOf<String>()
    private var apiCalls = 0
    private var cancellations = 0
    private var acknowledgements = 0

    @Test fun primarySignInCanBeCancelledWithoutExposingOrSelectingApiBilling() {
        ui.value = ui.value.copy(state = ui.value.state!!.copy(onboardingComplete = false))
        show()
        compose.onNodeWithTag("chatgpt-sign-in").assertIsDisplayed()
        compose.onNodeWithTag("connection-key").assertDoesNotExist()
        screenshot("30-mebot-chatgpt-connection")
        compose.onNodeWithTag("chatgpt-sign-in").performClick()
        assertEquals(listOf<String?>(null), signIns)
        compose.runOnIdle { ui.value = ui.value.copy(connecting = true, chatGpt = ChatGptAuthState(phase = "waiting")) }
        compose.onNodeWithTag("chatgpt-cancel").assertIsDisplayed()
        compose.onNodeWithTag("connection-api-alternative").assertIsNotEnabled()
        compose.onNodeWithTag("chatgpt-sign-in").assertDoesNotExist()
        screenshot("31-mebot-chatgpt-waiting")
        compose.onNodeWithTag("chatgpt-cancel").performClick()
        assertEquals(1, cancellations)
        compose.runOnIdle { ui.value = ui.value.copy(connecting = false, chatGpt = ChatGptAuthState(), error = "chatgpt_cancelled") }
        compose.onNodeWithTag("chatgpt-sign-in").assertIsEnabled()
        compose.onNodeWithText(text(R.string.chatgpt_error_declined)).assertExists()
        compose.onNodeWithTag("connection-key").assertDoesNotExist()
        assertEquals(0, apiCalls)
    }

    @Test fun apiAlternativeIsExplicitAndClosingItClearsTheUnsubmittedSecret() {
        show()
        compose.onNodeWithTag("connection-api-alternative").performScrollTo().performClick()
        compose.onNodeWithTag("connection-key").performScrollTo().performTextInput("private-unsaved-fixture")
        compose.onNodeWithTag("connection-connect").performScrollTo().assertIsEnabled()
        compose.onNodeWithTag("connection-api-alternative").performScrollTo().performClick()
        compose.onNodeWithTag("connection-key").assertDoesNotExist()
        compose.onNodeWithTag("connection-api-alternative").performClick()
        compose.onNodeWithTag("connection-connect").performScrollTo().assertIsNotEnabled()
        assertEquals(0, apiCalls)
        assertTrue(signIns.isEmpty())
    }

    @Test fun modelAndSameEmailAccountChangesWaitForCommittedState() {
        val first = account("account-a", "Example account · TEST-A")
        val second = account("account-b", "Example account · TEST-B")
        ui.value = connected(first).copy(chatGpt = ChatGptAuthState(listOf(first, second), first.id))
        show()
        compose.onNodeWithText(first.label).assertExists()
        screenshot("33-mebot-chatgpt-connected-fixture")
        compose.onNodeWithTag("chatgpt-model-picker").performClick()
        compose.onNodeWithText("Careful model").performClick()
        assertEquals(listOf("fixture-careful"), models)
        compose.onNodeWithTag("chatgpt-model-picker").assertTextContains("Fast model")
        compose.runOnIdle { ui.value = ui.value.copy(state = ui.value.state!!.copy(connection = ui.value.state!!.connection.copy(model = "fixture-careful"))) }
        compose.onNodeWithTag("chatgpt-model-picker").assertTextContains("Careful model")
        compose.onNodeWithTag("chatgpt-accounts").performScrollTo().performClick()
        compose.onAllNodesWithText(first.label).assertCountEquals(1)
        compose.onNodeWithTag("chatgpt-model-picker-${first.id}").assertDoesNotExist()
        compose.onNodeWithTag("chatgpt-use-${first.id}").assertDoesNotExist()
        compose.onNodeWithTag("chatgpt-use-${second.id}").performScrollTo().performClick()
        assertEquals(listOf(second.id), selections)
        assertEquals(first.id, ui.value.state!!.connection.accountId)
        compose.onNodeWithTag("chatgpt-sign-out-${first.id}").performScrollTo().performClick()
        assertEquals(listOf(first.id), signOuts)
    }

    @Test fun identityWithoutPlanPermissionHasAReconsentActionAndNoConnectionClaim() {
        val account = account("identity-only", "Alex · acct-a").copy(planEnabled = false)
        ui.value = ui.value.copy(chatGpt = ChatGptAuthState(listOf(account)), error = "chatgpt_permission")
        show()
        compose.onNodeWithText(text(R.string.feed_connected)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.chatgpt_plan_off)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("chatgpt-sign-in-${account.id}").performScrollTo().performClick()
        assertEquals(listOf(account.id), signIns)
        compose.onNodeWithTag("connection-key").assertDoesNotExist()
        assertEquals(0, apiCalls)
    }

    @Test fun savedPlanAccountWithCatalogFailureRetriesWithoutOAuthOrAnIdentityError() {
        val saved = account("saved-plan", "Example account · SAVED").copy(models = emptyList())
        ui.value = ui.value.copy(state = ui.value.state!!.copy(onboardingComplete = false), chatGpt = ChatGptAuthState(accounts = listOf(saved),
            lastDiagnostic = ChatGptDiagnostic("models", 200, "req_fixture_catalog", "other_json")), error = "chatgpt_models")
        show()
        compose.onNodeWithTag("chatgpt-retry-account").assertIsDisplayed()
        compose.onNodeWithText(saved.label).assertIsDisplayed()
        compose.onNodeWithTag("chatgpt-sign-in").assertDoesNotExist()
        compose.onNodeWithTag("chatgpt-use-${saved.id}").assertDoesNotExist()
        compose.onNodeWithText(text(R.string.feed_connected)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.chatgpt_error_security)).assertDoesNotExist()
        compose.onNodeWithText("req_fixture_catalog", substring = true).assertDoesNotExist()
        screenshot("34-mebot-chatgpt-saved-session-recovery")
        compose.onNodeWithTag("connection-diagnostics").performScrollTo().performClick()
        compose.onNodeWithText("req_fixture_catalog", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("chatgpt-retry-account").performScrollTo().performClick()
        assertEquals(listOf(saved.id), selections)
        assertTrue(accountModels.isEmpty())
        assertTrue(signIns.isEmpty())
        assertEquals(0, apiCalls)
        compose.runOnIdle { ui.value = ui.value.copy(error = "chatgpt_invalid_response") }
        compose.onNodeWithText(text(R.string.edition_provider_protocol)).assertExists()
        compose.onNodeWithText(text(R.string.chatgpt_error_security)).assertDoesNotExist()
    }

    @Test fun savedAccountHasOneModelControlAndKeepsItsDraftWhenManagementIsExpanded() {
        val saved = account("saved-model", "Example account · MODEL")
        ui.value = ui.value.copy(
            // Start on Fast so choosing Careful in the open picker has one unambiguous menu node
            // and genuinely changes the pending model draft.
            state = ui.value.state!!.copy(connection = AiConnection(mode = "chatgpt", model = "fixture-fast", accountId = saved.id)),
            chatGpt = ChatGptAuthState(accounts = listOf(saved)), error = "chatgpt_invalid_response",
        )
        show(settings = Settings(theme = "dark"))
        compose.onNodeWithTag("chatgpt-ready-model").performClick()
        compose.onNodeWithText("Careful model").performClick()
        compose.onNodeWithTag("chatgpt-retry-account").performClick()
        assertEquals(listOf(saved.id to "fixture-careful"), accountModels)
        compose.onNodeWithTag("chatgpt-accounts").performScrollTo().performClick()
        compose.onAllNodesWithText(saved.label).assertCountEquals(1)
        compose.onNodeWithTag("chatgpt-model-picker-${saved.id}").assertDoesNotExist()
        compose.onNodeWithTag("chatgpt-use-${saved.id}").assertDoesNotExist()
        compose.onNodeWithTag("chatgpt-ready-model").assertTextContains("Careful model")
        compose.onNodeWithTag("chatgpt-sign-out-${saved.id}").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("chatgpt-add-account").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.feed_connection)).performScrollTo()
        screenshot("44-mebot-connection-polish-v051")
        compose.onNodeWithTag("chatgpt-accounts").performScrollTo().performClick()
        compose.onNodeWithTag("chatgpt-sign-out-${saved.id}").assertDoesNotExist()
        compose.onNodeWithTag("chatgpt-ready-model").assertTextContains("Careful model")
        // If the account catalog changes, an unavailable draft must not be submitted.
        compose.runOnIdle {
            val remaining = saved.copy(models = saved.models.filter { it.slug == "fixture-fast" })
            ui.value = ui.value.copy(chatGpt = ui.value.chatGpt.copy(accounts = listOf(remaining)))
        }
        compose.onNodeWithTag("chatgpt-ready-model").assertTextContains("Fast model")
        compose.onNodeWithTag("chatgpt-retry-account").performScrollTo().performClick()
        assertEquals(listOf(saved.id to "fixture-careful", saved.id to "fixture-fast"), accountModels)
        assertTrue(signIns.isEmpty())
        assertEquals(0, apiCalls)
    }

    @Test fun usageLimitLeadsToVerifiedUsageSettingsAndNeverSwitchesProvider() {
        val account = account("plan-account", "Alex · acct-a")
        ui.value = connected(account).copy(error = "chatgpt_limit")
        show()
        compose.onNodeWithTag("chatgpt-limit-manage").performScrollTo().performClick()
        val opened = shadowOf(compose.activity).nextStartedActivity
        assertNotNull(opened)
        assertEquals(Intent.ACTION_VIEW, opened.action)
        assertEquals(CHATGPT_USAGE_URL, opened.dataString)
        assertEquals("chatgpt", ui.value.state!!.connection.mode)
        compose.onNodeWithTag("connection-key").assertDoesNotExist()
        assertEquals(0, apiCalls)
    }

    @Test fun firstUseNoticeWaitsForVerifiedPlanAndDoesNotReappearAfterAcknowledgement() {
        val account = account("first-plan", "Alex · acct-a").copy(planNoticeAcknowledged = false)
        ui.value = connected(account).copy(connected = false)
        show(notice = true)
        compose.onNodeWithTag("chatgpt-plan-got-it").assertDoesNotExist()
        compose.runOnIdle { ui.value = ui.value.copy(connected = true) }
        compose.onNodeWithTag("chatgpt-plan-got-it").performClick()
        assertEquals(1, acknowledgements)
        compose.runOnIdle {
            ui.value = ui.value.copy(chatGpt = ui.value.chatGpt.copy(accounts = listOf(account.copy(planNoticeAcknowledged = true))))
        }
        compose.onNodeWithTag("chatgpt-plan-got-it").assertDoesNotExist()
        compose.runOnIdle { ui.value = ui.value.copy(refreshing = true) }
        compose.onNodeWithTag("chatgpt-plan-got-it").assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "sw-rTZ-w320dp-h800dp-xhdpi")
    fun compactSwahiliDarkConnectionKeepsItsActionsReachable() {
        ui.value = ui.value.copy(state = ui.value.state!!.copy(onboardingComplete = false))
        show(settings = Settings(language = "sw", theme = "dark"), fontScale = 1.3f)
        compose.onNodeWithTag("chatgpt-sign-in").assertIsDisplayed()
        screenshot("32-mebot-chatgpt-small-swahili-dark")
        compose.onNodeWithTag("connection-api-alternative").performScrollTo().performClick()
        compose.onNodeWithTag("connection-key").performScrollTo().performTextInput("test-credential")
        compose.onNodeWithTag("connection-connect").performScrollTo().assertIsDisplayed().performClick()
        assertEquals(1, apiCalls)
    }

    @Test fun removeFromDeviceRequiresConfirmationAndDoesNotSignOutOrDeleteRemotely() {
        val account = account("local-remove", "Fixture account")
        ui.value = connected(account)
        show()
        compose.onNodeWithTag("chatgpt-accounts").performScrollTo().performClick()
        compose.onNodeWithTag("chatgpt-remove-local-remove").performScrollTo().performClick()
        assertTrue(removals.isEmpty()); assertTrue(signOuts.isEmpty())
        compose.onNodeWithText(text(R.string.cancel)).performClick()
        assertTrue(removals.isEmpty())
        compose.onNodeWithTag("chatgpt-remove-local-remove").performScrollTo().performClick()
        compose.onNodeWithTag("chatgpt-remove-confirm").performClick()
        assertEquals(listOf("local-remove"), removals); assertTrue(signOuts.isEmpty())
    }

    @Test fun signedOutSavedAccountCanBeRemovedAndFailureKeepsItRetryable() {
        val account = account("signed-out", "Saved fixture").copy(connected = false, planEnabled = false)
        ui.value = FeedUiState(state = FeedState(onboardingComplete = true), loading = false,
            chatGpt = ChatGptAuthState(accounts = listOf(account)))
        show()
        compose.onNodeWithTag("chatgpt-remove-signed-out").performScrollTo().performClick()
        compose.onNodeWithTag("chatgpt-remove-confirm").performClick()
        compose.runOnIdle { ui.value = ui.value.copy(error = "storage_error") }
        compose.onNodeWithTag("chatgpt-remove-signed-out").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithTag("chatgpt-remove-confirm").performClick()
        assertEquals(listOf("signed-out", "signed-out"), removals); assertTrue(signOuts.isEmpty())
        compose.runOnIdle { ui.value = ui.value.copy(error = null, chatGpt = ChatGptAuthState()) }
        compose.onNodeWithTag("chatgpt-remove-signed-out").assertDoesNotExist()
    }

    private fun show(notice: Boolean = false, settings: Settings = Settings(), fontScale: Float = 1f) {
        compose.setContent {
            MeBotTheme(settings) {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        Box {
                            ConnectionScreen(ui.value, onConnect = { _, _, _, _ -> apiCalls++ }, onDisconnect = {},
                                onChatGptSignIn = { signIns += it }, onChatGptCancel = { cancellations++ },
                                onChatGptAccount = { selections += it }, onChatGptModel = { models += it },
                                onChatGptAccountModel = { id, model -> selections += id; accountModels += id to model },
                                onChatGptSignOut = { signOuts += it }, onChatGptRemoveAccount = { removals += it }, onDone = {})
                            if (notice) ChatGptPlanNotice(ui.value) { acknowledgements++ }
                        }
                    }
                }
            }
        }
    }

    private fun account(id: String, label: String) = ChatGptAccount(id, label, "alex@example.com", connected = true, planEnabled = true,
        models = listOf(ChatGptModel("fixture-fast", "Fast model"), ChatGptModel("fixture-careful", "Careful model")), planNoticeAcknowledged = true)

    private fun connected(account: ChatGptAccount) = FeedUiState(
        state = FeedState(onboardingComplete = true, connection = AiConnection(mode = "chatgpt", model = "fixture-fast", accountId = account.id)),
        loading = false, connected = true, chatGpt = ChatGptAuthState(listOf(account), account.id))

    private fun text(id: Int) = compose.activity.getString(id)

    private fun screenshot(name: String) {
        compose.mainClock.advanceTimeBy(5_000)
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
