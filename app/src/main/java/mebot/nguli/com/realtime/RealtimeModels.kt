package mebot.nguli.com.realtime

/** Separate voice broker settings; deliberately not tied to the selected text provider. */
class RealtimeBrokerConfig private constructor(
    val serviceUrl: String,
    private val token: String,
) {
    internal fun serviceToken(): String = token

    override fun toString(): String = "RealtimeBrokerConfig(serviceUrl=$serviceUrl, serviceToken=<redacted>)"

    companion object {
        fun create(serviceUrl: String, serviceToken: String): RealtimeBrokerConfig {
            val base = serviceUrl.trim().trimEnd('/')
            require(mebot.nguli.com.core.FeedLogic.safeUrl(base, httpsOnly = true)) { "An HTTPS public service URL is required" }
            val uri = java.net.URI(base)
            require(uri.rawQuery == null && uri.rawFragment == null) { "A broker URL cannot contain query or fragment" }
            val token = serviceToken.trim()
            require(token.length in 32..4096 && token.none(Char::isWhitespace)) { "A valid service token is required" }
            return RealtimeBrokerConfig(base, token)
        }
    }
}

data class RealtimeContextNote(
    val id: String,
    val text: String,
    val category: String = "interest",
    val scopes: Set<String> = emptySet(),
    val confirmed: Boolean = false,
    val global: Boolean = false,
    val updatedAtMillis: Long = 0,
) {
    override fun toString(): String = "RealtimeContextNote(id=$id, text=<redacted>, category=$category, scopes=$scopes, confirmed=$confirmed, global=$global)"
}

data class RealtimeCitation(val title: String, val url: String) {
    override fun toString(): String = "RealtimeCitation(title=<redacted>, url=<redacted>)"
}

data class RealtimeContentSection(val heading: String, val body: String) {
    override fun toString(): String = "RealtimeContentSection(heading=<redacted>, body=<redacted>)"
}

/** Selected item provenance/evidence stays distinct from About You and from optional resources. */
data class RealtimeItemEvidence(
    val moduleKind: String,
    val moduleId: String,
    val title: String = "",
    val summary: String = "",
    val contentType: String = "article",
    val acquisition: String = "legacy_unknown",
    val authorship: String = "legacy_unknown",
    val sourceName: String = "",
    val sourceUrl: String = "",
    val publishedAtMillis: Long = 0,
    val sections: List<RealtimeContentSection> = emptyList(),
    /** Caller-provided source references are not independently verified by the service. */
    val citationReferences: List<RealtimeCitation> = emptyList(),
    /** Optional destinations; never considered evidence merely because they are supplied. */
    val furtherReading: List<RealtimeCitation> = emptyList(),
) {
    override fun toString(): String = "RealtimeItemEvidence(moduleKind=$moduleKind, moduleId=$moduleId, title=<redacted>, summary=<redacted>, contentType=$contentType, acquisition=$acquisition, authorship=$authorship, sourceName=<redacted>, sourceUrl=<redacted>, publishedAtMillis=$publishedAtMillis, sections=<redacted>, citationReferences=<redacted>, furtherReading=<redacted>)"
}

data class RealtimeTurn(val role: String, val text: String) {
    override fun toString(): String = "RealtimeTurn(role=$role, text=<redacted>)"
}

data class RealtimeSessionContext(
    val moduleKind: String = "",
    val moduleId: String = "",
    val moduleTitle: String = "",
    val moduleQuery: String = "",
    val confirmedContext: List<RealtimeContextNote> = emptyList(),
    val selectedItem: RealtimeItemEvidence? = null,
    val recentHistory: List<RealtimeTurn> = emptyList(),
    val language: String = "en",
) {
    override fun toString(): String = "RealtimeSessionContext(moduleKind=$moduleKind, moduleId=$moduleId, moduleTitle=<redacted>, moduleQuery=<redacted>, confirmedContext=<redacted>, selectedItem=<redacted>, recentHistory=<redacted>, language=$language)"
}

/** Opaque app-side handle returned by the trusted broker; the provider call ID stays server-only. */
class RealtimeCallReference internal constructor(val value: String) {
    override fun toString(): String = "RealtimeCallReference(value=<redacted>)"
}

data class RealtimeCallHandshake(
    val answerSdp: String,
    val callReference: RealtimeCallReference,
) {
    override fun toString(): String = "RealtimeCallHandshake(answerSdp=<redacted>, callReference=$callReference)"
}

data class RealtimeServerCapability(
    val voice: RealtimeCapability,
    val transcription: RecordingTranscriptionCapability,
)

class RealtimeTransportException(val reasonCode: String) : Exception(reasonCode)
