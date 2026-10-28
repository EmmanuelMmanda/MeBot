package mebot.nguli.com.feed

import mebot.nguli.com.core.*
import mebot.nguli.com.realtime.*

/** In-memory consent binding. Never persisted, logged, or included in backups. */
internal data class RealtimeScope099Snapshot(
    val conversationId: String?,
    val connection: AiConnection,
    val context: RealtimeSessionContext,
) {
    override fun toString(): String = "RealtimeScope099Snapshot(<private scope>)"
}

/** Least-data projection for the currently ready chat; missing scoped state never becomes General. */
internal object RealtimeScope099 {
    fun project(ui: FeedUiState): RealtimeScope099Snapshot? {
        val state = ui.state ?: return null
        if (!ui.conversationReady || ui.conversationOpening || ui.asking || ui.connecting ||
            ui.importWorking || ui.contextSaving || ui.suggestionWorking) return null
        val thread = ui.activeConversationId?.let { id ->
            state.conversations.singleOrNull { it.id == id } ?: return null
        }
        val item = thread?.itemId?.let { id -> state.items.singleOrNull { it.id == id } ?: return null }
        if (item != null && !runCatching { FeedLogic.validateItem(item) }.isSuccess) return null
        val moduleId = thread?.moduleId ?: item?.moduleId
        val module = moduleId?.let { id -> state.modules.singleOrNull { it.id == id && it.enabled } ?: return null }
        if (item != null && (module == null || item.moduleId != module.id || item.kind != module.kind)) return null
        val notes = FeedLogic.contextForKinds(state, setOfNotNull(module?.kind)).filter { it.text.isNotBlank() }
            .map { RealtimeContextNote(it.id, it.text.take(1500), it.category, it.scopes, it.confirmed, it.global, it.updatedAtMillis) }
        var remaining = 8000
        val history = thread?.let(ConversationLogic::requestHistory).orEmpty().takeLast(8).asReversed()
            .mapNotNull { turn ->
                if (remaining <= 0) null else {
                    val text = turn.text.take(minOf(2000, remaining))
                    remaining -= text.length
                    text.takeIf { it.isNotBlank() }?.let { RealtimeTurn(turn.role, it) }
                }
            }.asReversed()
        val evidence = item?.let {
            RealtimeItemEvidence(
                moduleKind = it.kind, moduleId = it.moduleId, title = it.title, summary = it.summary,
                contentType = it.contentType, acquisition = it.acquisition, authorship = it.authorship,
                sourceName = it.sourceName, sourceUrl = it.sourceUrl, publishedAtMillis = it.publishedAtMillis,
                sections = it.sections.map { section -> RealtimeContentSection(section.heading, section.body) },
                citationReferences = it.citations.map { source -> RealtimeCitation(source.title, source.url) },
                furtherReading = it.furtherReading.map { source -> RealtimeCitation(source.title, source.url) },
            )
        }
        return RealtimeScope099Snapshot(thread?.id, state.connection,
            RealtimeSessionContext(moduleKind = module?.kind.orEmpty(), moduleId = module?.id.orEmpty(),
                moduleTitle = module?.title.orEmpty(), moduleQuery = module?.query.orEmpty(),
                confirmedContext = notes, selectedItem = evidence, recentHistory = history,
                language = if (ui.appLanguage == "sw") "sw" else "en"))
    }

    fun stillCurrent(snapshot: RealtimeScope099Snapshot, ui: FeedUiState, locked: Boolean): Boolean =
        !locked && project(ui) == snapshot
}
