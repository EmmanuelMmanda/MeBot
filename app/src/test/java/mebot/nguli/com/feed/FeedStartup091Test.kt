package mebot.nguli.com.feed

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import mebot.nguli.com.MeBotApplication
import mebot.nguli.com.core.*
import mebot.nguli.com.feed.auth.ChatGptAuthManager
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35], application=FeedStartup091Application::class)
class FeedStartup091Test {
    private lateinit var app: FeedStartup091Application
    private val models=ViewModelStore()
    @Before fun setup(): Unit = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        app=ApplicationProvider.getApplicationContext()
        app.feedRepository.update { FeedState(personalSeedApplied=true,
            modules=listOf(FeedModule(id="news",kind="news",sources=listOf(FeedSource("Fixture","https://example.com/feed")))),
            items=listOf(FeedItem("cached","news","Cached fixture","Offline reading",sourceUrl="https://example.com/cached"))) }
    }
    @After fun close() { models.clear(); app.authRelease.complete(Unit); Dispatchers.resetMain() }

    @Test fun blockedAuthInitializationCannotHideCacheOrPreventPublicRefresh() = runBlocking {
        val vm=ViewModelProvider(models,ViewModelProvider.AndroidViewModelFactory(app))[FeedViewModel::class.java]
        withTimeout(5000) { app.authEntered.await() }
        val cached=withTimeout(5000) { vm.ui.first { !it.loading && it.state?.items?.any { item -> item.id=="cached" }==true } }
        assertFalse(cached.connected)
        assertFalse(app.authRelease.isCompleted)
        vm.refresh()
        withTimeout(5000) { vm.ui.first { !it.refreshing && it.state?.items?.any { item -> item.id=="public" }==true } }
        assertFalse(app.authRelease.isCompleted)
        assertEquals(0,app.feedRepository.current().requestsToday)
        assertTrue(app.feedRepository.current().items.any { it.id=="cached" })
    }
}

class FeedStartup091Application: MeBotApplication() {
    val authEntered=CompletableDeferred<Unit>()
    val authRelease=CompletableDeferred<Unit>()
    override val feedSecrets by lazy { object: SecretVault(this@FeedStartup091Application) {
        override fun read():String?=null
    } }
    override val chatGptAuth by lazy { object: ChatGptAuthManager(this@FeedStartup091Application) {
        override suspend fun initialize() { authEntered.complete(Unit);authRelease.await() }
    } }
    override val feedEngine by lazy { FeedEngine(feedRepository,feedSecrets,sources=object:SourceReader {
        override suspend fun read(source:FeedSource,module:FeedModule,now:Long)=listOf(
            FeedItem("public",module.id,"Public fixture","No inference",sourceUrl="https://example.com/public",fetchedAtMillis=now))
    }) }
}
