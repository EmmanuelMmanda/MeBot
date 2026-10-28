package mebot.nguli.com.feed

import android.content.Context
import android.content.ContextWrapper
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import mebot.nguli.com.core.AppState
import mebot.nguli.com.core.FeedState
import mebot.nguli.com.data.AppRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.rules.TemporaryFolder
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Compatibility regression for files written by the real repositories on DataStore 1.1.7. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class DataStore117ReadCompatibility098Test {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun datastore130ReadsUpdatesAndReopens117FeedAndAppStateWithoutLoss() = runBlocking {
        val feedBytes = resource("compatibility/datastore-117/feed.json")
        val appBytes = resource("compatibility/datastore-117/app-state.json")
        val receipt = resource("compatibility/datastore-117/receipt.txt").toString(Charsets.UTF_8)
        assertTrue(receipt.contains("writer=androidx.datastore:datastore:1.1.7\n"))
        assertTrue(receipt.contains("feed_sha256=${sha256(feedBytes)}\n"))
        assertTrue(receipt.contains("app_state_sha256=${sha256(appBytes)}\n"))
        assertTrue(receipt.contains("synthetic FeedState + AppState only"))

        val files = temporaryFolder.newFolder("files")
        val dataDirectory = files.resolve("mebot").apply { mkdirs() }
        feedBytes.writeTo(dataDirectory.resolve("feed.json"))
        appBytes.writeTo(dataDirectory.resolve("state.json"))
        val base = ApplicationProvider.getApplicationContext<Context>()
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = files
        }

        val firstFeedJob = SupervisorJob()
        val firstAppJob = SupervisorJob()
        try {
            val feedRepository = FeedRepository(context, CoroutineScope(Dispatchers.IO + firstFeedJob))
            val appRepository = AppRepository(context, CoroutineScope(Dispatchers.IO + firstAppJob))

            val feed: FeedState = feedRepository.current()
            assertTrue(feed.onboardingComplete)
            assertEquals("Synthetic Region", feed.region)
            assertEquals("Africa/Dar_es_Salaam", feed.timezone)
            assertEquals(setOf("synthetic-public-story", "synthetic-original-read"), feed.savedIds)
            assertEquals("Synthetic confirmed topic", feed.context.single().text)
            assertEquals("Synthetic publisher update", feed.items.single { it.id == "synthetic-public-story" }.title)
            val originalRead = feed.items.single { it.id == "synthetic-original-read" }
            assertEquals("original_read", originalRead.contentType)
            assertEquals(2, originalRead.sections.size)
            assertTrue(originalRead.sections.first().body.contains("synthetic section with context"))
            assertEquals(2, feed.conversations.single { it.id == "synthetic-thread" }.turns.size)
            // The compatibility fixture must never contain provider credentials or account bindings.
            assertTrue(feed.connection.accountId.isBlank())
            assertTrue(feed.connection.serviceUrl.isBlank())

            val app: AppState = appRepository.current()
            assertEquals("Synthetic reader", app.profile.name)
            assertTrue(app.profile.onboardingComplete)
            assertEquals("sw", app.settings.language)
            assertEquals("dark", app.settings.theme)
            assertEquals("violet", app.settings.accent)
            assertEquals("Synthetic note; no personal content.", app.visits.single().note)

            feedRepository.update { it.copy(notifications = true, lastRefreshMillis = it.lastRefreshMillis + 1) }
            appRepository.update { it.copy(settings = it.settings.copy(defaultFocusMinutes = 20)) }
        } finally {
            firstFeedJob.cancelAndJoin()
            firstAppJob.cancelAndJoin()
        }

        // Closing both 1.3.0 writer scopes and reopening proves the normal update path remains durable.
        val reopenedFeedJob = SupervisorJob()
        val reopenedAppJob = SupervisorJob()
        try {
            val feed = FeedRepository(context, CoroutineScope(Dispatchers.IO + reopenedFeedJob)).current()
            val app = AppRepository(context, CoroutineScope(Dispatchers.IO + reopenedAppJob)).current()
            assertTrue(feed.notifications)
            assertEquals(1_700_000_001_001L, feed.lastRefreshMillis)
            assertEquals(setOf("synthetic-public-story", "synthetic-original-read"), feed.savedIds)
            assertEquals("Synthetic confirmed topic", feed.context.single().text)
            assertEquals(2, feed.items.single { it.id == "synthetic-original-read" }.sections.size)
            assertEquals(2, feed.conversations.single { it.id == "synthetic-thread" }.turns.size)
            assertEquals("sw", app.settings.language)
            assertEquals("dark", app.settings.theme)
            assertEquals("violet", app.settings.accent)
            assertEquals(20, app.settings.defaultFocusMinutes)
            assertEquals("Synthetic note; no personal content.", app.visits.single().note)
            assertNotNull(app.profile)
        } finally {
            reopenedFeedJob.cancelAndJoin()
            reopenedAppJob.cancelAndJoin()
        }
    }

    private fun resource(path: String): ByteArray =
        javaClass.classLoader!!.getResourceAsStream(path).use { stream ->
            assertNotNull("Missing compatibility fixture: $path", stream)
            stream!!.readBytes()
        }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private fun ByteArray.writeTo(file: File) {
        file.outputStream().use { it.write(this) }
    }
}
