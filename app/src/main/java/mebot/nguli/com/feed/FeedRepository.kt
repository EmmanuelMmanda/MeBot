package mebot.nguli.com.feed

import android.content.Context
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import mebot.nguli.com.core.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import mebot.nguli.com.core.jobs.JobsBackup
import mebot.nguli.com.core.jobs.JobsState
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.io.OutputStream
import java.io.ByteArrayOutputStream

class FeedRepository(
    context: Context,
    scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
    private val restoreJobs: suspend (JobsState) -> Unit = {
        throw IllegalStateException("Jobs restore coordinator is not configured")
    },
) {
    // Shared lock order is Feed consistency -> Jobs mutation. Recovery never calls a gated Jobs API.
    private val consistency = Mutex()
    private val store = DataStoreFactory.create(serializer = FeedSerializer, scope = scope,
        produceFile = { context.applicationContext.filesDir.resolve("mebot/feed.json") })
    val state = store.data.map { withJobsConsistency { store.data.first() } }.distinctUntilChanged()
    suspend fun current(): FeedState = withJobsConsistency { store.data.first() }

    /** Jobs reads/mutations use this gate too, so neither store exposes a half-restored backup. */
    suspend fun <T> withJobsConsistency(action: suspend () -> T): T = withContext(Dispatchers.IO) {
        // The lock owner must not need a UI dispatcher to resume and release this gate.
        // Keeping only the DataStore on IO leaves suspended Main collectors holding the lock.
        consistency.withLock {
            recoverLocked()
            action()
        }
    }

    private suspend fun recoverLocked() {
        val pending = store.data.first().jobsBackup ?: return
        val restored = JobsBackup.restore(pending)
        // Idempotent roll-forward: if the process dies after this write, replaying it is safe.
        restoreJobs(restored)
        store.updateData { current -> current.copy(jobsBackup = null) }
    }

    suspend fun update(transform: (FeedState) -> FeedState): FeedState = withJobsConsistency {
        store.updateData { current ->
            val next = transform(current)
            require(next.jobsBackup == null) { "Use importBackup for journaled restoration" }
            if (next == current) current else FeedLogic.validate(next)
        }
    }

    /** The durable marker is the commit point. Failures after it remain pending for recovery. */
    suspend fun importBackup(transform: (FeedState) -> FeedState): FeedState = withContext(Dispatchers.IO) {
        consistency.withLock {
            recoverLocked()
            store.updateData { current ->
                FeedLogic.validate(transform(current)).also { next -> next.jobsBackup?.let(JobsBackup::validate) }
            }
            recoverLocked()
            store.data.first()
        }
    }

    suspend fun erase(): FeedState = withJobsConsistency {
        store.updateData { FeedState(personalSeedApplied = true) }
    }

    suspend fun applyOptionalSeed(context: Context) {
        if (current().personalSeedApplied) {
            update { it.copy(modules = ModuleCatalog.mergeCatalog(it.modules)) }
            return
        }
        val seed = runCatching {
            context.assets.open("mebot-profile.json").use { stream ->
                val bytes = stream.readBounded(128 * 1024)
                FeedJson.decodeFromString<PersonalContextImport>(bytes.toString(Charsets.UTF_8))
            }
        }.getOrNull()
        update { s ->
            if (s.personalSeedApplied) s else if (seed == null || seed.format != "mebot-context-v1") s.copy(personalSeedApplied = true,
                timezone = s.timezone.ifBlank { java.time.ZoneId.systemDefault().id },
                editionSequence = if (s.editionSequence == 0L) java.security.SecureRandom().nextInt(999_999_998).toLong() + 1 else s.editionSequence)
            else s.copy(name = seed.name, region = seed.region, timezone = seed.timezone,
                context = seed.entries.map { it.copy(origin = "imported", global = false) },
                modules = ModuleCatalog.mergeCatalog(seed.modules.ifEmpty { s.modules }), personalSeedApplied = true)
        }
    }
}

val FeedJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

private object FeedSerializer : Serializer<FeedState> {
    override val defaultValue = FeedState()
    override suspend fun readFrom(input: InputStream): FeedState {
        val bytes = input.readBounded(8 * 1024 * 1024)
        return FeedLogic.validate(FeedJson.decodeFromString(bytes.toString(Charsets.UTF_8))).also {
            it.jobsBackup?.let(JobsBackup::validate)
        }
    }
    override suspend fun writeTo(t: FeedState, output: OutputStream) {
        // Updates and reads are validated at the repository boundary. Avoid walking all cached
        // articles and conversation turns for a second time on every usage-counter write.
        val bytes = FeedJson.encodeToString(t).toByteArray(Charsets.UTF_8)
        require(bytes.size <= 8 * 1024 * 1024)
        output.write(bytes)
    }
}

private fun InputStream.readBounded(maximum: Int): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        require(output.size() + count <= maximum)
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}
