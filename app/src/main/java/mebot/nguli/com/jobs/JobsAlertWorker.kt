package mebot.nguli.com.jobs

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import mebot.nguli.com.MainActivity
import mebot.nguli.com.MeBotApplication
import mebot.nguli.com.R
import mebot.nguli.com.core.jobs.JobPosting
import mebot.nguli.com.core.jobs.JobPreferences
import mebot.nguli.com.core.jobs.JobsMatcher

/** Public-source refresh and local deterministic matching only. Never invokes an AI provider. */
class JobsAlertWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val app = applicationContext as MeBotApplication
        return try {
            val ledger = app.getSharedPreferences(JobsAlertScheduler.LEDGER_NAME, Context.MODE_PRIVATE)
            val generation = ledger.getLong(JobsAlertScheduler.GENERATION_KEY, 0L)
            val repository = app.jobsRepository
            val before = repository.current()
            if (!JobsAlertScheduler.enabled(before.preferences) || !canNotify(app)) return Result.success()
            // Existing cached jobs are the initial baseline, not a flood of "new" alerts on opt-in.
            val seen = ledger.getStringSet("seen", null)?.toSet() ?: before.jobs.map { it.id }.toSet()
            repository.refresh() // Repository persists and enforces the six-hour source request limit.
            val latest = repository.current()
            if (!JobsAlertScheduler.enabled(latest.preferences) || !canNotify(app) || isStopped) return Result.success()
            val newMatches = matchingNewIds(latest.jobs, latest.preferences, seen)
            val allSeen = (seen + latest.jobs.map { it.id }).toList().takeLast(10_000).toSet()
            // Commit before posting so a process restart cannot repeatedly alert the same batch.
            // This intentionally prefers an occasional missed alert over duplicate interruptions.
            synchronized(JobsAlertScheduler.deliveryLock) {
                if (isStopped || ledger.getLong(JobsAlertScheduler.GENERATION_KEY, 0L) != generation) return Result.success()
                if (!ledger.edit().putStringSet("seen", allSeen).commit()) return Result.failure()
            }
            if (newMatches.isEmpty()) return Result.success()
            val finalPreferences = repository.current().preferences
            if (!JobsAlertScheduler.enabled(finalPreferences) || !canNotify(app) || isStopped) return Result.success()
            val stillMatches = matchingNewIds(latest.jobs, finalPreferences, seen)
            if (stillMatches.isEmpty()) return Result.success()
            val manager = app.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, app.getString(R.string.jobs_alert_channel), NotificationManager.IMPORTANCE_DEFAULT))
            val intent = Intent(app, MainActivity::class.java).setAction(ACTION_OPEN_JOBS)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val pending = PendingIntent.getActivity(app, NOTIFICATION_ID, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            // Never expose a job search, employer, salary or profile detail on the lock screen.
            synchronized(JobsAlertScheduler.deliveryLock) {
                if (isStopped || ledger.getLong(JobsAlertScheduler.GENERATION_KEY, 0L) != generation) return Result.success()
                manager.notify(NOTIFICATION_ID, NotificationCompat.Builder(app, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(app.getString(R.string.jobs_alert_title))
                .setContentText(app.getString(R.string.jobs_alert_body))
                .setVisibility(NotificationCompat.VISIBILITY_SECRET)
                .setContentIntent(pending).setAutoCancel(true).build())
            }
            Result.success()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { Result.failure() } // Next periodic run retries; do not hammer the source.
    }

    companion object {
        const val ACTION_OPEN_JOBS = "mebot.nguli.com.OPEN_JOBS"
        internal const val CHANNEL_ID = "mebot_jobs"
        internal const val NOTIFICATION_ID = 302

        internal fun matchingNewIds(jobs: List<JobPosting>, preferences: JobPreferences, seen: Set<String>): Set<String> =
            if (!JobsAlertScheduler.enabled(preferences)) emptySet()
            else jobs.asSequence().filter { it.id !in seen && JobsMatcher.matches(it, preferences) }.map { it.id }.toSet()

        private fun canNotify(context: Context): Boolean {
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
            if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
            val channel = context.getSystemService(NotificationManager::class.java).getNotificationChannel(CHANNEL_ID)
            return channel == null || channel.importance != NotificationManager.IMPORTANCE_NONE
        }
    }
}
