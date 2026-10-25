package mebot.nguli.com.feed

import android.Manifest
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import mebot.nguli.com.core.*
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = FeedTestApplication::class)
class FeedWorkerTest {
    private val app get() = ApplicationProvider.getApplicationContext<FeedTestApplication>()
    private val notifications get() = app.getSystemService(NotificationManager::class.java)
    private val source = FeedSource("Example", "https://example.com/feed")
    private val news = FeedModule(id = "news", kind = "news", sources = listOf(source), alerts = true)

    @Before fun setUp() = runBlocking<Unit> {
        app.feedEngine.disconnect()
        app.feedRepository.erase()
        app.sourceItems = emptyList()
        app.sourceFailure = null
        notifications.cancelAll()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        app.feedRepository.update { it.copy(onboardingComplete = true, notifications = true, modules = listOf(news), timezone = "UTC", quietStartHour = 0, quietEndHour = 0) }
    }

    @Test fun backgroundRefreshRequiresExplicitOptIn() = runBlocking {
        app.sourceItems = listOf(article("new"))
        app.feedRepository.update { it.copy(notifications = false) }
        assertEquals(ListenableWorker.Result.success(), worker().doWork())
        assertTrue(app.feedRepository.current().items.isEmpty())
        assertEquals(0L, app.feedRepository.current().lastRefreshMillis)
        assertTrue(notifications.activeNotifications.isEmpty())
    }

    @Test fun quietHoursRetainAnAlertForTheNextAllowedRunAndDailyDeliveryIsCapped() = runBlocking {
        val hour = Instant.ofEpochMilli(System.currentTimeMillis()).atZone(ZoneOffset.UTC).hour
        app.feedRepository.update { it.copy(quietStartHour = (hour + 23) % 24, quietEndHour = (hour + 1) % 24) }
        app.sourceItems = listOf(article("quiet-update"))
        assertEquals(ListenableWorker.Result.success(), worker().doWork())
        assertEquals(setOf("quiet-update"), app.feedRepository.current().pendingNotificationIds)
        assertTrue(notifications.activeNotifications.isEmpty())

        app.feedRepository.update { it.copy(quietStartHour = 0, quietEndHour = 0) }
        assertEquals(ListenableWorker.Result.success(), worker().doWork())
        val posted = notifications.activeNotifications.single().notification
        assertEquals("Synthetic quiet-update", posted.extras.getString("android.text"))
        val destination = shadowOf(posted.contentIntent).savedIntent
        assertEquals("mebot.nguli.com.OPEN_FEED", destination.action)
        assertEquals("quiet-update", destination.getStringExtra("item_id"))
        assertTrue(app.feedRepository.current().pendingNotificationIds.isEmpty())
        assertEquals(FeedLogic.dateAt(System.currentTimeMillis(), "UTC"), app.feedRepository.current().lastNotificationDate)

        notifications.cancelAll()
        app.sourceItems = listOf(article("another-update"))
        worker().doWork()
        assertTrue(notifications.activeNotifications.isEmpty())
        assertEquals(setOf("another-update"), app.feedRepository.current().pendingNotificationIds)
    }

    @Test fun modulesWithAlertsOffAndHoroscopesNeverGenerateAnAlert() = runBlocking {
        val horoscope = FeedModule(id = "horoscope", kind = "horoscope", sources = listOf(source), alerts = true, zodiacSign = "leo")
        app.feedRepository.update { it.copy(modules = listOf(news.copy(alerts = false), horoscope)) }
        app.sourceItems = listOf(article("silent-news"), article("entertainment").copy(moduleId = "horoscope", kind = "horoscope"))
        worker().doWork()
        assertEquals(2, app.feedRepository.current().items.size)
        assertTrue(app.feedRepository.current().pendingNotificationIds.isEmpty())
        assertTrue(notifications.activeNotifications.isEmpty())
    }

    @Test fun deniedNotificationPermissionDoesNotDiscardThePendingUpdate() = runBlocking {
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        app.sourceItems = listOf(article("permission-update"))
        worker().doWork()
        assertTrue(notifications.activeNotifications.isEmpty())
        assertEquals(setOf("permission-update"), app.feedRepository.current().pendingNotificationIds)
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        worker().doWork()
        assertEquals(1, notifications.activeNotifications.size)
        assertTrue(app.feedRepository.current().pendingNotificationIds.isEmpty())
    }

    private fun worker() = TestListenableWorkerBuilder<FeedWorker>(app).build()
    private fun article(id: String) = FeedItem(id, "news", "Synthetic $id", "A test-only update.",
        sourceName = "Example", sourceUrl = "https://example.com/$id", publishedAtMillis = System.currentTimeMillis())
}
