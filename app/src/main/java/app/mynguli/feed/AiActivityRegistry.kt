package app.mynguli.feed

import java.util.UUID
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/** Ephemeral, request-scoped activity. Never persists prompts, credentials or raw reasoning. */
data class AiActivity(val id: String, val scope: String, val kind: String, val operation: String,
    val stage: String = "queued", val summary: String = "", val source: String = "", val startedAt: Long = System.currentTimeMillis(),
    val steps: List<AiActivityStep> = emptyList(), val contextEntries: Int? = null)

/** Only observed events and validated result counts, never simulated model thoughts. */
data class AiActivityStep(val stage: String, val source: String = "", val count: Int? = null,
    val at: Long = System.currentTimeMillis())

object AiActivityRegistry {
    private val live = MutableStateFlow<Map<String, AiActivity>>(emptyMap())
    val state = live.asStateFlow()
    private val local = ThreadLocal<String?>()
    private val requests = Semaphore(2)
    // One background request leaves a slot for interactive work.
    private val background = Semaphore(1)
    suspend fun <T> track(scope: String, kind: String, operation: String, block: suspend () -> T): T {
        val id = UUID.randomUUID().toString()
        live.update { it + (id to AiActivity(id, scope, kind, operation)) }
        try { return withContext(local.asContextElement(id)) { block() } }
        finally { live.update { it - id } }
    }
    suspend fun <T> run(scope: String, kind: String, operation: String, block: suspend () -> T): T =
        track(scope, kind, operation) {
            suspend fun execute(): T = requests.withPermit { report("connecting"); block() }
            if (operation == "discovery") background.withPermit { execute() } else execute()
        }
    fun report(stage: String, count: Int? = null, source: String? = null, contextEntries: Int? = null) {
        val id = local.get() ?: return
        live.update { states -> states[id]?.let { current ->
            val actualStage = if (stage == "writing" && current.operation in setOf("discovery", "search", "refresh")) "summarizing" else stage
            val host = source?.take(120)?.replace(Regex("[^a-zA-Z0-9. _-]"), "") ?: current.source
            val step = AiActivityStep(actualStage, host, count?.coerceAtLeast(0))
            val last = current.steps.lastOrNull()
            val history = if (last?.stage == step.stage && last.source == step.source && last.count == step.count)
                current.steps else (current.steps + step).takeLast(8)
            val next = current.copy(stage = actualStage, source = host, steps = history,
                contextEntries = contextEntries?.coerceAtLeast(0) ?: current.contextEntries)
            if (next == current) states else states + (id to next)
        } ?: states }
    }
    private fun update(stage: String, summary: String? = null, source: String? = null) {
        report(stage, source = source)
        if (summary != null) {
            val id = local.get() ?: return
            live.update { states -> states[id]?.let { states + (id to it.copy(summary = summary.take(600))) } ?: states }
        }
    }

    fun observe(type: String, event: JsonObject) {
        when (type) {
            "response.created", "response.in_progress" -> update("working")
            "response.web_search_call.in_progress", "response.web_search_call.searching" -> update("searching")
            "response.web_search_call.completed" -> update("reading")
            "response.output_text.delta" -> update("writing")
            "response.reasoning_summary_text.delta" -> {
                // Only provider-published summaries, never reasoning_text or encrypted content.
                val delta = (event["delta"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
                val id = local.get() ?: return
                live.update { states -> states[id]?.let { a ->
                    val text = (a.summary + delta).replace(Regex("[\\p{Cc}&&[^\\n\\t]]"), "").takeLast(280)
                    states + (id to a.copy(stage = "thinking", summary = text))
                } ?: states }
            }
            "response.output_item.added", "response.output_item.done" -> {
                val item = event["item"] as? JsonObject ?: return
                when ((item["type"] as? JsonPrimitive)?.contentOrNull) {
                    "reasoning" -> update("thinking")
                    "web_search_call" -> {
                        val action = item["action"] as? JsonObject
                        val query = (action?.get("query") as? JsonPrimitive)?.contentOrNull.orEmpty()
                        update(if (type.endsWith(".done")) "reading" else "searching", source = if (query.contains("youtube", true)) "youtube" else "")
                    }
                }
            }
        }
    }
}
