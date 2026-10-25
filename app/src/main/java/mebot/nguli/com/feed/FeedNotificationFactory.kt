package mebot.nguli.com.feed

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.graphics.Bitmap
import androidx.core.app.NotificationCompat
import mebot.nguli.com.R
import mebot.nguli.com.core.FeedItem

/** No secret/account content. Android still owns permission, lock-screen policy and channel controls. */
internal object FeedNotificationFactory {
    fun build(context: Context, item: FeedItem, open: PendingIntent, showPreview: Boolean, image: Bitmap?): Notification {
        val title = context.getString(R.string.feed_notification_title)
        val text = if (showPreview) item.title else context.getString(R.string.notification_ready_private)
        val builder = NotificationCompat.Builder(context, "mebot_feed")
            .setSmallIcon(R.drawable.ic_notification).setContentTitle(title)
            .setContentText(text).setContentIntent(open).setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setOnlyAlertOnce(true)
            .addAction(R.drawable.ic_notification, context.getString(R.string.notification_open), open)
        if (showPreview && image != null) builder.setStyle(NotificationCompat.BigPictureStyle()
            .bigPicture(image).setBigContentTitle(item.title).setSummaryText(item.sourceName.take(200)))
        else builder.setStyle(NotificationCompat.BigTextStyle().bigText(text))
        // The public lock-screen version never includes the article or its image.
        builder.setPublicVersion(NotificationCompat.Builder(context, "mebot_feed")
            .setSmallIcon(R.drawable.ic_notification).setContentTitle(title)
            .setContentText(context.getString(R.string.notification_ready_private)).build())
        return builder.build()
    }
}
