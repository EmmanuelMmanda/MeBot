package mebot.nguli.com.jobs

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

enum class JobsRefreshPhase { IDLE, QUEUED, RUNNING, RETRYING, OFFLINE, SUCCESS, FAILURE, CANCELLED }
data class JobsRefreshStatus(
    val phase: JobsRefreshPhase = JobsRefreshPhase.IDLE,
    val outcome: String = "",
    val requestId: String = "",
    val attempts: Int = 0,
    val nextAllowedAt: Long = 0,
)

/** Durable, unique manual refresh. No provider credentials or personal fields enter WorkManager. */
object JobsRefreshScheduler {
    const val WORK_NAME = "mebot-jobs-refresh"
    const val REQUESTED_AT_TAG = "jobs-requested-at:"
    private val lastTimestamp = AtomicLong()

    fun schedule(context: Context) {
        val timestamp = lastTimestamp.updateAndGet { maxOf(System.currentTimeMillis(), it + 1) }
        val request = OneTimeWorkRequestBuilder<JobsRefreshWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.HOURS)
            .addTag("jobs-manual-refresh").addTag("$REQUESTED_AT_TAG$timestamp").build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }

    fun enqueue(context: Context) = schedule(context)
    fun cancel(context: Context) { WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME) }

    fun observe(context: Context): Flow<JobsRefreshStatus> {
        val app = context.applicationContext
        return combine(WorkManager.getInstance(app).getWorkInfosForUniqueWorkFlow(WORK_NAME), connectivity(app)) { work, online ->
            val latest = work.filterNot { it.state.isFinished }.maxByOrNull(::requestedAt)
                ?: work.maxByOrNull(::requestedAt)
            status(latest, online)
        }.distinctUntilChanged()
    }

    private fun requestedAt(info: WorkInfo) = info.tags.firstOrNull { it.startsWith(REQUESTED_AT_TAG) }
        ?.removePrefix(REQUESTED_AT_TAG)?.toLongOrNull() ?: 0L

    internal fun phase(state: WorkInfo.State?, online: Boolean, attempts: Int): JobsRefreshPhase = when (state) {
        null -> JobsRefreshPhase.IDLE
        WorkInfo.State.RUNNING -> JobsRefreshPhase.RUNNING
        WorkInfo.State.SUCCEEDED -> JobsRefreshPhase.SUCCESS
        WorkInfo.State.FAILED -> JobsRefreshPhase.FAILURE
        WorkInfo.State.CANCELLED -> JobsRefreshPhase.CANCELLED
        WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> when {
            !online -> JobsRefreshPhase.OFFLINE
            attempts > 0 -> JobsRefreshPhase.RETRYING
            else -> JobsRefreshPhase.QUEUED
        }
    }

    private fun status(info: WorkInfo?, online: Boolean): JobsRefreshStatus = JobsRefreshStatus(
        phase = phase(info?.state, online, info?.runAttemptCount ?: 0),
        outcome = info?.outputData?.getString(JobsRefreshWorker.KEY_OUTCOME).orEmpty(),
        requestId = info?.id?.toString().orEmpty(), attempts = info?.runAttemptCount ?: 0,
        nextAllowedAt = info?.outputData?.getLong(JobsRefreshWorker.KEY_NEXT_ALLOWED_AT, 0L) ?: 0L,
    )

    private fun connectivity(context: Context): Flow<Boolean> = callbackFlow {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        fun emitCurrent() {
            val online = runCatching {
                manager.getNetworkCapabilities(manager.activeNetwork)
                    ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
            }.getOrDefault(false)
            trySend(online)
        }
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = emitCurrent()
            override fun onLost(network: Network) = emitCurrent()
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = emitCurrent()
        }
        val registered = runCatching { manager.registerDefaultNetworkCallback(callback) }.isSuccess
        emitCurrent()
        awaitClose { if (registered) runCatching { manager.unregisterNetworkCallback(callback) } }
    }.distinctUntilChanged()
}
