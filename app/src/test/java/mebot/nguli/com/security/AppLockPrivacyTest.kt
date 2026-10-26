package mebot.nguli.com.security

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import mebot.nguli.com.MainActivity
import mebot.nguli.com.core.FeedItem
import mebot.nguli.com.feed.FeedNotificationFactory
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppLockPrivacyTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    @Test fun `enabled lock suppresses requested notification previews and images`() {
        AppLockPrivacy.mark(context, true)
        val item = FeedItem("private", "news", "Private chosen story", "private summary", sourceName = "Private source", sourceUrl = "https://example.org/story")
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val notification = FeedNotificationFactory.build(context, item, open, true, Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888))
        assertFalse(notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString().contains(item.title))
        assertNull(notification.extras.getParcelable<Bitmap>(Notification.EXTRA_PICTURE))
        assertFalse(notification.extras.toString().contains(item.sourceName))
    }
    @Test fun `enabling removes an existing feed preview and scrubs alarm title retaining controls`() {
        val manager = context.getSystemService(android.app.NotificationManager::class.java)
        manager.createNotificationChannel(android.app.NotificationChannel("mebot_feed", "Feed", 3))
        manager.createNotificationChannel(android.app.NotificationChannel("mebot_active_alarm_v1", "Alarm", 4))
        val action = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        manager.notify(300, Notification.Builder(context, "mebot_feed").setSmallIcon(mebot.nguli.com.R.drawable.ic_notification).setContentTitle("Private story").build())
        manager.notify(3101, Notification.Builder(context, "mebot_active_alarm_v1").setSmallIcon(mebot.nguli.com.R.drawable.ic_notification).setContentTitle("Private alarm")
            .addAction(Notification.Action.Builder(null, "Stop", action).build()).build())
        AppLockPrivacy.mark(context, true)
        assertFalse(manager.activeNotifications.any { it.id == 300 })
        val alarm = manager.activeNotifications.single { it.id == 3101 }.notification
        assertFalse(alarm.extras.getCharSequence(Notification.EXTRA_TITLE).toString().contains("Private"))
        assertEquals(1, alarm.actions.size)
        assertEquals(action, alarm.actions.single().actionIntent)
    }
    @Test fun `missing protected file stays enabled and cannot become unconfigured`() {
        AppLockPrivacy.mark(context, true)
        val store = AppLockStore(context)
        assertTrue(store.enabled())
        assertTrue(runCatching { store.read() }.isFailure)
        val controller = AppLockController(context)
        assertTrue(controller.state.value.enabled)
        assertTrue(controller.state.value.locked)
        assertEquals(LockProblem.STORAGE, controller.state.value.problem)
    }
    @Test fun `unconfigured store needs no keystore and creates no credential material`() {
        val store = AppLockStore(context)
        assertFalse(store.enabled()); assertNull(store.read())
        assertFalse(java.io.File(context.noBackupFilesDir, "app-lock-v1.bin").exists())
    }
}
