package mebot.nguli.com.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import mebot.nguli.com.R
import mebot.nguli.com.core.*
import mebot.nguli.com.feed.*
import mebot.nguli.com.ui.feed.*
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Release082UiTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    @Test fun loaderShowsRealPhaseRatherThanReceivedCharacterCounts() {
        compose.setContent { MeBotTheme { AiActivityPanel(AiActivity("phase", "news", "news", "discovery", "reading", receivedCharacters = 23535)) } }
        compose.onNodeWithText(compose.activity.getString(R.string.activity_reading)).assertIsDisplayed()
        compose.onNodeWithText("23535", substring = true).assertDoesNotExist()
        compose.onNodeWithText(compose.activity.getString(R.string.activity_received_chars, 23535), substring = true).assertDoesNotExist()
    }
    @Test fun popupMatchesItsAnchorAndRingEndsWithOperation() {
        val activity=mutableStateOf<AiActivity?>(AiActivity("a","news","news","discovery","reading",contextEntries=2))
        compose.setContent { MeBotTheme { Column(Modifier.fillMaxSize().padding(horizontal=20.dp)) {
            AiActivityPanel(activity.value)
            Text("The page stays readable")
        } } }
        val width=compose.onNodeWithTag("ai-activity-panel").fetchSemanticsNode().boundsInRoot.width
        compose.onNodeWithTag("ai-activity-panel").performClick()
        val popup=compose.onNodeWithTag("activity-details-popup").fetchSemanticsNode().boundsInRoot.width
        assertEquals(width,popup,2f)
        compose.onNodeWithText(compose.activity.getString(R.string.activity_less)).performClick()
        compose.runOnIdle{activity.value=null}
        compose.onAllNodesWithTag("ai-activity-panel").assertCountEquals(0)
        compose.onAllNodesWithTag("mebot-active-ring",useUnmergedTree=true).assertCountEquals(0)
    }
    @Test @Config(qualifiers="sw-rTZ-w320dp-h800dp-xhdpi")
    fun narrowSwahiliPopupUsesAvailableWidthWithoutHorizontalOverflow() {
        compose.setContent { MeBotTheme(Settings(language="sw")) { Column(Modifier.fillMaxWidth().padding(20.dp)) {
            AiActivityPanel(AiActivity("a","scholarships","scholarships","discovery","summarizing",summary="Nimepata taarifa kutoka kwenye vyanzo vilivyotajwa."))
        } } }
        val anchor=compose.onNodeWithTag("ai-activity-panel").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("ai-activity-panel").performClick()
        assertEquals(anchor.width,compose.onNodeWithTag("activity-details-popup").fetchSemanticsNode().boundsInRoot.width,2f)
    }
    @Test fun introductionRemainsVisibleAcrossWaitingPartialAndCompletedAnswers() {
        val item=FeedItem("one","news","A source-backed update","This introduction is visible before the answer arrives.",sourceUrl="https://example.com/a",sourceName="Publisher",kind="news")
        val ui=mutableStateOf(FeedUiState(state=FeedState(items=listOf(item)),loading=false,connected=true,detailItemId=item.id,detailLoading=true))
        compose.setContent { MeBotTheme{ FeedDetail(ui.value,item.id,{},{},{}) } }
        compose.onNodeWithTag("detail-summary-intro").performScrollTo().assertIsDisplayed()
        compose.runOnIdle{ui.value=ui.value.copy(detailText="The first streamed sentence.")}
        compose.onNodeWithTag("detail-summary-intro").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("detail-brief-draft").performScrollTo().assertTextContains("The first streamed sentence.")
        compose.runOnIdle{ui.value=ui.value.copy(detailLoading=false,detailText="",detailTurns=listOf(AiTurn("answer","assistant","The completed explanation.")))}
        compose.onNodeWithTag("detail-summary-intro").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("detail-brief-answer").performScrollTo().assertTextContains("The completed explanation.")
        compose.onNodeWithTag("detail-story-notes").performScrollTo().assertTextContains("Show more")
    }
    @Test fun allowancePresetsUnlimitedAndCustomKeepConsumedUsage() {
        val state=mutableStateOf(FeedState(timezone="UTC",usageDate=LocalDate.now(java.time.ZoneOffset.UTC).toString(),requestsToday=9))
        compose.setContent { MeBotTheme{ ScreenContainer { RequestAllowanceCard(state.value,true){n->state.value=state.value.copy(dailyRequestLimit=n)} } } }
        compose.onNodeWithTag("connection-limit-28").assertIsSelected()
        compose.onNodeWithTag("connection-limit-56").performClick()
        compose.onNodeWithTag("connection-usage").assertTextContains("9",substring=true)
        compose.onNodeWithTag("connection-limit-unlimited").performScrollTo().performClick().assertIsSelected()
        assertEquals(0,state.value.dailyRequestLimit);assertEquals(9,state.value.requestsToday)
        compose.onNodeWithTag("connection-limit-custom-toggle").performScrollTo().performClick()
        compose.onNodeWithTag("connection-limit-custom").performScrollTo().performTextReplacement("0")
        compose.onNodeWithTag("connection-limit-apply").performScrollTo().performClick()
        assertEquals(0,state.value.dailyRequestLimit)
        compose.onNodeWithText(compose.activity.getString(R.string.delivery_limit_invalid)).assertExists()
        compose.onNodeWithTag("connection-limit-custom").performScrollTo().performTextReplacement("73")
        compose.onNodeWithTag("connection-limit-apply").performScrollTo().performClick()
        assertEquals(73,state.value.dailyRequestLimit);assertEquals(9,state.value.requestsToday)
    }
    @Test fun starIsExplicitAndIndependentOfBookmarksAndRelevance() {
        val item=FeedItem("a","music","Piano practice","Useful practice.",sourceUrl="https://example.com/a",sourceName="Source",kind="music")
        val state=mutableStateOf(FeedState(items=listOf(item)))
        compose.setContent { MeBotTheme { CompositionLocalProvider(LocalRelevanceContext provides RelevanceUiContext(
            state = state.value, onStar = { id -> state.value = StarredStories.toggle(state.value,id,100) })) {
            RelevanceControls(item)
        } } }
        compose.onNodeWithTag("star-a").performClick().assertIsSelected()
        assertEquals(1,state.value.starredStories.size)
        assertTrue(state.value.savedIds.isEmpty())
        assertTrue(state.value.relevanceSignals.isEmpty())
        compose.onNodeWithTag("star-a").performClick().assertIsNotSelected()
        assertTrue(state.value.starredStories.isEmpty())
    }
    @Test fun softwareUpdatesHasDistinctCheckDownloadAndInstallActions() {
        val release=SoftwareRelease("0.8.3",14,"mebot.nguli.com",26,"https://github.com/EmmanuelMmanda/MeBot/releases/download/v0.8.3/mebot-0.8.3.apk","a".repeat(64),16_000_000,"b".repeat(64),"An improved update.")
        val state=mutableStateOf(mebot.nguli.com.updates.SoftwareUpdateState())
        var checks=0;var downloads=0;var installs=0
        compose.setContent{MeBotTheme{SoftwareUpdatesContent(state.value,{checks++},{downloads++},{installs++},{},{a,w->state.value=state.value.copy(automaticChecks=a,downloadOnWifi=w)})}}
        compose.onNodeWithText("Software Updates").assertExists()
        compose.onNodeWithTag("update-primary").performClick();assertEquals(1,checks)
        compose.runOnIdle{state.value=state.value.copy(release=release)}
        compose.onNodeWithTag("update-primary").performClick();assertEquals(1,downloads)
        compose.runOnIdle{state.value=state.value.copy(downloading=true)}
        compose.onNodeWithTag("update-primary").assertIsNotEnabled()
        compose.runOnIdle{state.value=state.value.copy(downloading=false,ready=true)}
        compose.onNodeWithTag("update-primary").performClick();assertEquals(1,installs)
        compose.runOnIdle{state.value=state.value.copy(awaitingInstall=true)}
        compose.onNodeWithText(compose.activity.getString(R.string.update_install_approval)).assertExists()
    }

    @Test fun typedDraftSurvivesLateThreadCreationAndRestoration() {
        val ui=mutableStateOf(FeedUiState(state=FeedState(onboardingComplete=true),loading=false,connected=true))
        val restore=StateRestorationTester(compose)
        restore.setContent { MeBotTheme { AiWorkspace(ui.value,{},{},{},{},{},{},{},{},{}) } }
        compose.onNodeWithTag("workspace-question").performTextInput("Help me compare these sources.")
        compose.runOnIdle { ui.value=ui.value.copy(activeConversationId="new-thread") }
        compose.onNodeWithTag("workspace-question").assertTextContains("Help me compare these sources.")
        compose.runOnIdle { ui.value=ui.value.copy(state=ui.value.state!!.copy(conversations=listOf(AiConversation(id="new-thread")))) }
        restore.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("workspace-question").assertTextContains("Help me compare these sources.")
    }

}
