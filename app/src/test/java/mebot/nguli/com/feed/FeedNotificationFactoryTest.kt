package mebot.nguli.com.feed

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import mebot.nguli.com.MainActivity
import mebot.nguli.com.core.FeedItem
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FeedNotificationFactoryTest {
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val item = FeedItem("test", "news", "Private chosen story", "summary", sourceName = "Publisher", sourceUrl = "https://example.org/story")
    private fun intent() = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)

    @Test fun hiddenPreviewNeverIncludesStoryOrImage() {
        val notification = FeedNotificationFactory.build(context, item, intent(), false, Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888))
        assertFalse(notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString().contains(item.title))
        assertNull(notification.extras.getParcelable<Bitmap>(Notification.EXTRA_PICTURE))
        assertEquals(Notification.VISIBILITY_PRIVATE, notification.visibility)
        assertNotNull(notification.publicVersion)
        assertFalse(notification.publicVersion.extras.getCharSequence(Notification.EXTRA_TEXT).toString().contains(item.title))
    }

    @Test fun realImageUsesBigPictureAndOffersReadAction() {
        val notification = FeedNotificationFactory.build(context, item, intent(), true, Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888))
        assertEquals(item.title, notification.extras.getCharSequence(Notification.EXTRA_TEXT))
        assertNotNull(notification.extras.getParcelable<Bitmap>(Notification.EXTRA_PICTURE))
        assertEquals(1, notification.actions.size)
    }

    @Test fun missingImageStillProvidesReadableNotification() {
        val notification = FeedNotificationFactory.build(context, item, intent(), true, null)
        assertEquals(item.title, notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT))
    }
}
