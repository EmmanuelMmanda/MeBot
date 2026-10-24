package app.mynguli.core

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class AiConversation(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "",
    val itemId: String? = null,
    val moduleId: String? = null,
    val turns: List<AiTurn> = emptyList(),
    val attachments: List<AiAttachment> = emptyList(),
    val createdAtMillis: Long = System.currentTimeMillis(),
    val updatedAtMillis: Long = createdAtMillis,
)

@Serializable
data class AiTurn(
    val id: String = UUID.randomUUID().toString(),
    val role: String,
    val text: String,
    val citations: List<FeedCitation> = emptyList(),
    val createdAtMillis: Long = System.currentTimeMillis(),
    val model: String = "",
)

@Serializable
data class AiAttachment(val id: String = UUID.randomUUID().toString(), val name: String, val text: String)

@Serializable
data class AiItemBrief(
    val itemId: String,
    val text: String,
    val citations: List<FeedCitation> = emptyList(),
    val generatedAtMillis: Long,
    val model: String,
    val fingerprint: String,
)

/** Explicit limits keep the local JSON store and every request bounded. */
object ConversationLogic {
    const val maxConversations = 24
    const val maxTurns = 60
    const val maxAttachmentBytes = 48 * 1024
    const val maxAttachmentCharacters = 24_000
    const val maxTextCharacters = 800_000

    fun validate(conversations: List<AiConversation>) {
        require(conversations.size <= maxConversations && conversations.map { it.id }.distinct().size == conversations.size)
        var characters = 0L
        conversations.forEach { thread ->
            require(thread.id.length in 1..100 && thread.title.length <= 160)
            require(thread.itemId == null || thread.itemId.length in 1..100)
            require(thread.moduleId == null || thread.moduleId.length in 1..100)
            require(thread.createdAtMillis in 0..32_503_680_000_000L && thread.updatedAtMillis in thread.createdAtMillis..32_503_680_000_000L)
            require(thread.turns.size <= maxTurns && thread.turns.map { it.id }.distinct().size == thread.turns.size)
            thread.turns.forEach { turn ->
                require(turn.id.length in 1..100 && turn.role in setOf("user", "assistant"))
                require(turn.text.isNotBlank() && turn.text.length <= if (turn.role == "user") 2000 else 24000)
                require(turn.createdAtMillis in 0..32_503_680_000_000L && turn.model.length <= 128)
                require(turn.citations.size <= 20 && turn.citations.all { it.title.length <= 300 && FeedLogic.safeUrl(it.url) })
                require(turn.role == "assistant" || turn.citations.isEmpty())
                characters += turn.text.length
            }
            require(thread.attachments.size <= 3 && thread.attachments.map { it.id }.distinct().size == thread.attachments.size)
            require(thread.attachments.sumOf { it.text.length } <= maxAttachmentCharacters)
            thread.attachments.forEach { attachment ->
                require(attachment.id.length in 1..100 && attachment.name.length in 1..160)
                require(attachment.text.isNotBlank() && attachment.text.length <= maxAttachmentCharacters && '\u0000' !in attachment.text)
                characters += attachment.text.length
            }
        }
        require(characters <= maxTextCharacters)
    }

    fun context(state: FeedState, thread: AiConversation): List<ContextEntry> {
        val kind = thread.moduleId?.let { id -> state.modules.find { it.id == id }?.kind }
            ?: thread.itemId?.let { id -> state.items.find { it.id == id }?.kind }
            ?: return emptyList()
        return state.context.filter { it.confirmed && kind in it.scopes }.take(32)
    }

    fun validateBriefs(briefs: List<AiItemBrief>) {
        require(briefs.size <= 24 && briefs.map { it.itemId }.distinct().size == briefs.size)
        briefs.forEach { brief ->
            require(brief.itemId.length in 1..100 && brief.text.isNotBlank() && brief.text.length <= 12000)
            require(brief.generatedAtMillis in 0..32_503_680_000_000L && brief.model.length <= 128)
            require(brief.fingerprint.matches(Regex("[0-9a-f]{64}")))
            require(brief.citations.size <= 20 && brief.citations.all { it.title.length <= 300 && FeedLogic.safeUrl(it.url) })
        }
    }

    /** Only a bounded recent window is sent; full history remains visible on the device. */
    fun requestHistory(thread: AiConversation): List<AiTurn> {
        var remaining = 36_000
        return thread.turns.takeLast(16).asReversed().mapNotNull { turn ->
            if (remaining <= 0) null else turn.copy(text = turn.text.take(remaining)).also { remaining -= it.text.length }
        }.asReversed()
    }
}
