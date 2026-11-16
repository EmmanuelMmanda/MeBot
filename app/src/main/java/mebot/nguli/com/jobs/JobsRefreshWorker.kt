package mebot.nguli.com.jobs

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import mebot.nguli.com.MeBotApplication
import mebot.nguli.com.feed.FeedFailure
import java.io.IOException

/** One public-source operation, surviving screen closure. Never generates text or applies to jobs. */
class JobsRefreshWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val repository = (applicationContext as MeBotApplication).jobsRepository
        return try {
            if (isStopped) return Result.failure(workDataOf(KEY_OUTCOME to "cancelled"))
            val refreshed = repository.refresh()
            val latest = repository.current()
            val previousAttemptFailed = latest.lastAttemptAt > latest.lastRefreshAt && latest.failedSources.isNotEmpty()
            if (!refreshed && previousAttemptFailed) {
                // A failed attempt consumed the source window. Do not label old data a successful
                // refresh or call the source again. Only an existing retry may wait out that window.
                if (runAttemptCount in 1 until MAX_RETRIES) Result.retry()
                else Result.failure(workDataOf(KEY_OUTCOME to "provider_error", KEY_NEXT_ALLOWED_AT to latest.lastAttemptAt + SOURCE_INTERVAL_MS))
            } else {
                val outcome = when { !refreshed -> "cached"; latest.failedSources.isNotEmpty() -> "partial"; else -> "updated" }
                Result.success(workDataOf(KEY_OUTCOME to outcome, KEY_NEXT_ALLOWED_AT to latest.lastAttemptAt + SOURCE_INTERVAL_MS))
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: JobsStateChangedException) { Result.success(workDataOf(KEY_OUTCOME to "superseded")) }
        catch (failure: Exception) {
            val code = (failure as? FeedFailure)?.code ?: if (failure is IOException) "provider_error" else "storage_error"
            if (shouldRetry(code, runAttemptCount) && !isStopped) Result.retry()
            else Result.failure(workDataOf(KEY_OUTCOME to code))
        }
    }

    companion object {
        const val KEY_OUTCOME = "outcome"
        const val KEY_NEXT_ALLOWED_AT = "next_allowed_at"
        internal const val SOURCE_INTERVAL_MS = 6 * 60 * 60 * 1000L
        internal const val MAX_RETRIES = 3
        internal fun shouldRetry(code: String, attempts: Int): Boolean =
            attempts < MAX_RETRIES && code in setOf("offline", "provider_error", "network_error", "timeout")
    }
}
