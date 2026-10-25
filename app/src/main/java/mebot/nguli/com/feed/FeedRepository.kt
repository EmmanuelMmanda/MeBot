package mebot.nguli.com.feed

import android.content.Context
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import mebot.nguli.com.core.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.io.OutputStream
import java.io.ByteArrayOutputStream

class FeedRepository(context: Context, scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())) {
    private val store = DataStoreFactory.create(serializer = FeedSerializer, scope = scope,
        produceFile = { context.applicationContext.filesDir.resolve("mebot/feed.json") })
    val state = store.data
    suspend fun current(): FeedState = state.first()
    suspend fun update(transform: (FeedState) -> FeedState): FeedState = store.updateData { current ->
        val next = transform(current)
        // A repeated selection or an already-recorded view should not revalidate and rewrite the store.
        if (next == current) current else FeedLogic.validate(next)
    }
    suspend fun erase(): FeedState = store.updateData { FeedState(personalSeedApplied = true) }

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
        return FeedLogic.validate(FeedJson.decodeFromString(bytes.toString(Charsets.UTF_8)))
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
