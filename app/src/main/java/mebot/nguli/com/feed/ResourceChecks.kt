package mebot.nguli.com.feed

import android.content.Context
import mebot.nguli.com.core.DownloadResource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Ephemeral public-file checks. Separate capacity and cache from AI and credentials. */
object ResourceChecks {
    data class Result(val available: Boolean, val checkedAt: Long)
    private val checks = MutableStateFlow<Map<String, Result>>(emptyMap())
    val state = checks.asStateFlow()
    private val slots = Semaphore(2)
    suspend fun check(context: Context, url: String, force: Boolean = false): Boolean {
        val resource = DownloadResource.from(url) ?: return false
        val now = System.currentTimeMillis()
        fun cached(): Boolean? = checks.value[url]?.takeIf {
            now >= it.checkedAt && now - it.checkedAt < if (it.available) 300_000 else 30_000
        }?.available
        if (!force) cached()?.let { return it }
        return slots.withPermit {
            if (!force) cached()?.let { return@withPermit it }
            val available = try { ResourceDownloader(context.applicationContext).available(resource) } catch (_: TimeoutCancellationException) { false }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { false }
            checks.update { previous ->
                (previous.filterValues { now >= it.checkedAt && now - it.checkedAt < 300_000 }
                    .entries.toList().takeLast(127).associate { it.toPair() }) + (url to Result(available, System.currentTimeMillis()))
            }
            available
        }
    }
}
