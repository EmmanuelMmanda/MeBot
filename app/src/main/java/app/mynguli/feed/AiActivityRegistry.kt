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
    val stage: String = "queued", val summary: String = "", val source: String = "", val startedAt: Long = System.currentTimeMillis())

object AiActivityRegistry {
    private val live = MutableStateFlow<Map<String, AiActivity>>(emptyMap())
    val state = live.asStateFlow()
    private val local = ThreadLocal<String?>()
    private val requests = Semaphore(2)
    // One background request leaves a slot for interactive work.
    private val background = Semaphore(1)
    suspend fun <T> run(scope: String, kind: String, operation: String, block: suspend () -> T): T {
        val id = UUID.randomUUID().toString()
        live.update { it + (id to AiActivity(id, scope, kind, operation)) }
        try {
            return withContext(local.asContextElement(id)) {
                suspend fun execute(): T = requests.withPermit { update("connecting"); block() }
                if (operation == "discovery") background.withPermit { execute() } else execute()
            }
        } finally { live.update { it - id } }
    }
    private fun update(stage: String, summary: String? = null, source: String? = null) {
        val id = local.get() ?: return
        live.update { states -> states[id]?.let { current ->
            states + (id to current.copy(stage = stage, summary = summary ?: current.summary, source = source ?: current.source))
        } ?: states }
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
                        update("searching", source = if (query.contains("youtube", true)) "youtube" else "")
                    }
                }
            }
        }
    }
}
