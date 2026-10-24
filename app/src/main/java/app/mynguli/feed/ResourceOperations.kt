package app.mynguli.feed

import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** UI-only state: no URLs, account tokens, file paths or document text are retained. */
object ResourceOperations {
    private val active = MutableStateFlow<Map<String, AiActivity>>(emptyMap())
    val state = active.asStateFlow()
    suspend fun <T> run(stage: String, block: suspend () -> T): T {
        val id = UUID.randomUUID().toString()
        active.update { it + (id to AiActivity(id, "resources", "document", "resource", stage)) }
        try { return block() } finally { active.update { it - id } }
    }
}
