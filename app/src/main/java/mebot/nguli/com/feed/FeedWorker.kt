package mebot.nguli.com.feed

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.work.*
import mebot.nguli.com.MainActivity
import mebot.nguli.com.MeBotApplication
import mebot.nguli.com.R
import mebot.nguli.com.core.FeedLogic
import mebot.nguli.com.core.FeedState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit

class FeedWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as MeBotApplication
        return try {
            val before = app.feedRepository.current()
            if (!before.onboardingComplete || !(before.backgroundEnabled || before.notifications)) return Result.success()
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
                    val intent = Intent(app, MainActivity::class.java).setAction("mebot.nguli.com.OPEN_FEED").putExtra("item_id", item.id)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    val pending = PendingIntent.getActivity(app, 300, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                    val preview = state.readingPreferences.notificationPreview
                    // Public publisher imagery is optional and bounded; a slow image never blocks delivery.
                    val image = if (preview && state.readingPreferences.notificationImages) item.media?.url?.let { url ->
                        withTimeoutOrNull(2_000) { app.feedMedia.load(url) }
                    } else null
                    val latest = app.feedRepository.current()
                    val deliveryNow = System.currentTimeMillis()
                    val deliveryDate = FeedLogic.dateAt(deliveryNow, latest.timezone)
                    val permissionStillGranted = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                    val stillAllowed = permissionStillGranted && latest.notifications && !FeedLogic.quietAt(deliveryNow, latest) &&
                        latest.lastNotificationDate != deliveryDate && item.id in latest.pendingNotificationIds &&
                        FeedLogic.visible(latest).any { it.id == item.id } &&
                        latest.modules.any { it.id == item.moduleId && it.enabled && it.alerts }
                    if (stillAllowed) {
                        mebot.nguli.com.security.AppLockPrivacy.withPolicy {
                            manager.notify(300, FeedNotificationFactory.build(app, item, pending,
                                showPreview = latest.readingPreferences.notificationPreview,
                                image = image.takeIf { latest.readingPreferences.notificationImages }))
                        }
                        app.feedRepository.update { current -> current.copy(lastNotificationDate = deliveryDate,
                            pendingNotificationIds = current.pendingNotificationIds - state.pendingNotificationIds) }
                    }
                }
            }
            Result.success()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { Result.failure() }
    }
    companion object {
        fun schedule(context: Context, state: FeedState) {
            if (!state.backgroundEnabled && !state.notifications) { cancel(context); return }
            val request = PeriodicWorkRequestBuilder<FeedWorker>(state.refreshHours.toLong(), TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(if (state.backgroundWifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED).setRequiresBatteryNotLow(state.backgroundBatteryNotLow).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("mebot-feed", ExistingPeriodicWorkPolicy.UPDATE, request)
        }
        fun cancel(context: Context) { WorkManager.getInstance(context).cancelUniqueWork("mebot-feed") }
    }
}
