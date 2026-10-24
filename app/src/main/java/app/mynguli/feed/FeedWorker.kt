package app.mynguli.feed

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import app.mynguli.MainActivity
import app.mynguli.MyNguliApplication
import app.mynguli.R
import app.mynguli.core.FeedLogic
import app.mynguli.core.FeedState
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

class FeedWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as MyNguliApplication
        return try {
            val before = app.feedRepository.current()
            if (!before.onboardingComplete || !before.notifications) return Result.success()
            app.feedEngine.refresh()
            val state = app.feedRepository.update { refreshed ->
                val alertModules = refreshed.modules.filter { it.enabled && it.alerts && it.kind != "horoscope" }.map { it.id }.toSet()
                val freshIds = FeedLogic.visible(refreshed).filter { it.moduleId in alertModules && before.items.none { old -> old.id == it.id } }.map { it.id }.toSet()
                val eligibleIds = FeedLogic.visible(refreshed).filter { it.moduleId in alertModules }.map { it.id }.toSet()
                refreshed.copy(pendingNotificationIds = ((refreshed.pendingNotificationIds + freshIds) intersect eligibleIds).take(1000).toSet())
            }
            val now = System.currentTimeMillis(); val date = FeedLogic.dateAt(now, state.timezone)
            val allowed = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            if (state.pendingNotificationIds.isNotEmpty() && state.notifications && allowed && !FeedLogic.quietAt(now, state) && state.lastNotificationDate != date) {
                val item = FeedLogic.visible(state).firstOrNull { it.id in state.pendingNotificationIds }
                if (item != null) {
                    val manager = app.getSystemService(NotificationManager::class.java)
                    manager.createNotificationChannel(NotificationChannel("mebot_feed", app.getString(R.string.feed_channel_name), NotificationManager.IMPORTANCE_DEFAULT))
                    val intent = Intent(app, MainActivity::class.java).setAction("app.mynguli.OPEN_FEED").putExtra("item_id", item.id)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    val pending = PendingIntent.getActivity(app, 300, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                    manager.notify(300, NotificationCompat.Builder(app, "mebot_feed")
                        .setSmallIcon(R.drawable.ic_notification).setContentTitle(app.getString(R.string.feed_notification_title))
                        .setContentText(item.title).setStyle(NotificationCompat.BigTextStyle().bigText(item.title))
                        .setContentIntent(pending).setAutoCancel(true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build())
                    app.feedRepository.update { it.copy(lastNotificationDate = date, pendingNotificationIds = emptySet()) }
                }
            }
            Result.success()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { Result.failure() }
    }
    companion object {
        fun schedule(context: Context, state: FeedState) {
            if (!state.notifications) { cancel(context); return }
            val request = PeriodicWorkRequestBuilder<FeedWorker>(state.refreshHours.toLong(), TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresBatteryNotLow(true).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("mebot-feed", ExistingPeriodicWorkPolicy.UPDATE, request)
        }
        fun cancel(context: Context) { WorkManager.getInstance(context).cancelUniqueWork("mebot-feed") }
    }
}
