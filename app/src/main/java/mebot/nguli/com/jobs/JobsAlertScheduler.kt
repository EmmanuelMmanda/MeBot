package mebot.nguli.com.jobs

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import mebot.nguli.com.core.jobs.JobPreferences
import java.util.concurrent.TimeUnit

/** Call only after the user's preferences have been durably saved. Never requests permission. */
object JobsAlertScheduler {
    internal val deliveryLock = Any()
    internal const val LEDGER_NAME = "mebot.jobs-alerts.v1"
    internal const val GENERATION_KEY = "generation"
    internal const val WORK_NAME = "mebot-jobs-alerts"
    internal fun enabled(preferences: JobPreferences) = preferences.onboarded && preferences.alertsEnabled
    internal fun intervalHours(preferences: JobPreferences) = preferences.alertFrequencyHours.coerceIn(6, 168).toLong()

    /** Cancel deliveries and forget their history, including when all app data is reset. */
    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        synchronized(deliveryLock) {
            // Invalidate an in-flight worker even before WorkManager delivers cancellation.
            val ledger = context.getSharedPreferences(LEDGER_NAME, Context.MODE_PRIVATE)
            val generation = ledger.getLong(GENERATION_KEY, 0L) + 1L
            ledger.edit().clear().putLong(GENERATION_KEY, generation).commit()
            context.getSystemService(android.app.NotificationManager::class.java).cancel(JobsAlertWorker.NOTIFICATION_ID)
        }
    }

    fun schedule(context: Context, preferences: JobPreferences) {
        val manager = WorkManager.getInstance(context)
        if (!enabled(preferences)) {
            cancel(context)
            return
        }
        manager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<JobsAlertWorker>(intervalHours(preferences), TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(true).build())
                .build())
    }
}
