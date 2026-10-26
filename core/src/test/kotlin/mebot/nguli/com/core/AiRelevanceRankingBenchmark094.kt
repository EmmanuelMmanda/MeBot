package mebot.nguli.com.core

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

/** Synthetic host-only comparison of prepared ranking with empty, realistic and stress AI-fit caches. */
object AiRelevanceRankingBenchmark094 {
    private var sink = 0L
    private const val WARMUP = 100
    private const val ITERATIONS = 400
    private val now = 1_761_350_400_000L

    @JvmStatic fun main(args: Array<String>) {
        require(args.size >= 2) { "Expected output CSV path and fork number" }
        val output = File(args[0])
        output.parentFile?.mkdirs()
        val fork = args[1].toIntOrNull() ?: error("Fork must be an integer")
        val base = fixture()
        val scenarios = listOf(
            "250-cache-empty" to base,
            "250-cache-40" to withEstimates(base, perModule = 8),
            "250-cache-250-stress" to withEstimates(base, perModule = Int.MAX_VALUE),
        )
        val ordered = scenarios.drop(Math.floorMod(fork, scenarios.size)) + scenarios.take(Math.floorMod(fork, scenarios.size))
        output.printWriter().use { out ->
            out.println("fork,scenario,fixture_sha256,iteration,duration_ns,outcome,checksum")
            ordered.forEach { (scenario, state) ->
                FeedLogic.validate(state)
                val hash = MessageDigest.getInstance("SHA-256")
                    .digest(Json.encodeToString(state).toByteArray())
                    .joinToString("") { "%02x".format(it) }
                repeat(WARMUP) { sink += rankChecksum(state) }
                repeat(ITERATIONS) { iteration ->
                    val start = System.nanoTime()
                    val checksum = rankChecksum(state)
                    val elapsed = System.nanoTime() - start
                    sink += checksum
                    out.println("$fork,$scenario,$hash,$iteration,$elapsed,success,$sink")
                }
                out.flush()
            }
        }
    }

    private fun rankChecksum(state: FeedState): Long = FeedLogic.edition(state, now)
        .sumOf { it.id.hashCode().toLong() + it.relevance }

    private fun fixture(): FeedState {
        val modules = listOf("news", "technology", "business", "learning", "security")
            .map { FeedModule(id = it, kind = it, query = "software business biashara learning") }
        val items = (0 until 250).map { index ->
            val module = modules[index % modules.size]
            FeedItem(
                id = "item-$index", moduleId = module.id, kind = module.kind,
                title = "Software biashara learning opportunity $index",
                summary = "Useful cloud security biashara and software guide ${index % 17}",
                sourceUrl = "https://source${index % 7}.example/story/$index",
                publishedAtMillis = now - index * 3_600_000L,
                fetchedAtMillis = now,
                relevance = 40 + index % 61,
            )
        }
        return FeedState(
            timezone = "Africa/Dar_es_Salaam", modules = modules, items = items,
            context = (0 until 80).map { index ->
                ContextEntry(id = "note-$index", text = "Software biashara learning cloud security ${index % 9}",
                    scopes = setOf(modules[index % modules.size].kind), updatedAtMillis = now)
            },
            relevanceSignals = (0 until 500).map { index ->
                RelevanceSignal("vote-$index", modules[index % modules.size].id, index % 3 != 0, "Synthetic",
                    setOf("software", "biashara", "security"), now - index * 86_400_000L)
            },
        )
    }

    private fun withEstimates(state: FeedState, perModule: Int): FeedState {
        val eligible = state.items.groupBy { it.moduleId }.values.flatMap { it.take(perModule) }
        val digests = state.modules.associate { it.id to AiRelevance094.scopeProviderDigest(state, it) }
        val estimates = eligible.associate { item ->
            item.id to AiRelevanceEstimate(
                fitEstimate = 55 + item.id.substringAfterLast('-').toInt() % 40,
                assessedAtMillis = now,
                contentDigest = AiRelevance094.contentDigest(item),
                scopeProviderDigest = digests.getValue(item.moduleId),
            )
        }
        return state.copy(items = state.items.map { item -> item.copy(aiRelevance = estimates[item.id]) })
    }
}
