@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package mebot.nguli.com.core

import kotlinx.serialization.Serializable
import java.util.UUID

/** Personal configuration is imported separately. These defaults are reusable. */
@Serializable
data class FeedState(
    val schemaVersion: Int = 1,
    val onboardingComplete: Boolean = false,
    val name: String = "",
    val region: String = "",
    val timezone: String = "",
    val modules: List<FeedModule> = ModuleCatalog.defaults,
    val context: List<ContextEntry> = emptyList(),
    val items: List<FeedItem> = emptyList(),
    val savedIds: Set<String> = emptySet(),
    val hiddenIds: Set<String> = emptySet(),
    val starredStories: List<StarredStory> = emptyList(),
    val savedCollections: Map<String, String> = emptyMap(),
    val readingPreferences: ReadingPreferences = ReadingPreferences(),
    val speechRate: Float = 1f,
    val speechVoice: String = "",
    val connection: AiConnection = AiConnection(),
    val lastRefreshMillis: Long = 0,
    val lastAiRefreshMillis: Long = 0,
    val notifications: Boolean = false,
    val quietStartHour: Int = 22,
    val quietEndHour: Int = 7,
    val refreshHours: Int = 6,
    /** Zero means no local limit. Existing explicit limits are preserved on upgrade. */
    val dailyRequestLimit: Int = RequestBudget.DEFAULT_LIMIT,
    val usageDate: String = "",
    val requestsToday: Int = 0,
    val lastNotificationDate: String = "",
    val pendingNotificationIds: Set<String> = emptySet(),
    val personalSeedApplied: Boolean = false,
    /** Automatic AI pauses after an actionable subscription error; public feeds still refresh. */
    val aiPauseCode: String = "",
    /** Device-local reading history; never an analytics or advertising identifier. */
    val readingHistoryEnabled: Boolean = true,
    val viewedAtMillis: Map<String, Long> = emptyMap(),
    val editionSequence: Long = 0,
    /** Device-local recommendation projection; never publisher/publication metadata. */
    val personalDailySelection098: PersonalDailySelection098? = null,
    val conversations: List<AiConversation> = emptyList(),
    val briefs: List<AiItemBrief> = emptyList(),
    /** Device-local cache of complete provider-authored suggestion sets; never provider request data. */
    val suggestionSets097: List<AiSuggestionSet097> = emptyList(),
    /** Optional additional discovery batches per module. 1 preserves existing behavior. */
    val prefetchBatches: Int = 1,
    val relevanceSignals: List<RelevanceSignal> = emptyList(),
    val interestsConfigured: Boolean = false,
    val quickGuideSeen: Boolean = false,
    val dismissedNotices: Map<String, Long> = emptyMap(),
    val backgroundEnabled: Boolean = false,
    val backgroundWifiOnly: Boolean = true,
    val backgroundBatteryNotLow: Boolean = true,

)

@Serializable
data class AiConnection(
    val mode: String = "direct",
    val model: String = "gpt-6-luna",
    val serviceUrl: String = "",
    /** Local opaque account reference only. OAuth credentials live in the encrypted vault. */
    val accountId: String = "",
    /** Explicit provider choice; no automatic fallback to another billing account. */
    val webSearch: Boolean = true,
    val allowLocalHttp: Boolean = false,
)

@Serializable
data class FeedModule(
    val id: String = UUID.randomUUID().toString(),
    val kind: String = "watch",
    val title: String = "",
    val query: String = "",
    val enabled: Boolean = true,
    val sources: List<FeedSource> = emptyList(),
    val alerts: Boolean = false,
    val zodiacSign: String = "",
    /** Values are validated against ModulePreferences; old installs decode with no overrides. */
    val preferences: Map<String, String> = emptyMap(),
)

@Serializable
data class FeedSource(val name: String, val url: String)

@Serializable
data class ContextEntry(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val category: String = "interest",
    val scopes: Set<String> = setOf("news", "opportunities", "watch"),
    val confirmed: Boolean = true,
    val origin: String = "manual",
    val updatedAtMillis: Long = System.currentTimeMillis(),
    val global: Boolean = false,
)

@Serializable
data class FeedItem(
    val id: String,
    val moduleId: String,
    val title: String,
    val summary: String,
    val why: String = "",
    val sourceName: String = "",
    val sourceUrl: String = "",
    val publishedAtMillis: Long = 0,
    @kotlinx.serialization.EncodeDefault
    val fetchedAtMillis: Long = System.currentTimeMillis(),
    val kind: String = "news",
    val aiGenerated: Boolean = false,
    val citations: List<FeedCitation> = emptyList(),
    val deadline: String = "",
    val relevance: Int = 0,
    /** A publisher-provided preview; never a URL invented by the model. */
    val media: FeedMedia? = null,
    val contentType: String = "article",
    val weather: FeedWeather? = null,
    val keyPoints: List<String> = emptyList(),
    val nextStep: String = "",
    val caveat: String = "",
    val temporal: FeedTemporal? = null,
    /** Bounded, provider-assisted interest fit for a verified public candidate; never a probability. */
    val aiRelevance: AiRelevanceEstimate? = null,
    /** Optional, bounded question suggestions; local cache metadata is stripped from provider DTOs. */
    val suggestedInteractions: List<AiSuggestedInteraction> = emptyList(),
    val suggestedInteractionsContentDigest: String = "",
    val suggestedInteractionsScopeProviderDigest: String = "",
    val suggestedInteractionsGeneratedAtMillis: Long = 0,
    /** How this record was acquired. String codes keep future/unknown values migration-safe. */
    val acquisition: String = FeedAcquisition.LEGACY_UNKNOWN,
    /** Who authored the content, independent of acquisition and AI ranking. */
    val authorship: String = FeedAuthorship.LEGACY_UNKNOWN,
    /** Long-form reading sections; their body text is authoritative for an Original Read. */
    val sections: List<FeedContentSection> = emptyList(),
    /** Optional discovery links, separate from citations/evidence for factual claims. */
    val furtherReading: List<FeedCitation> = emptyList(),
)

/** Stable string codes rather than serialized enums so future values never break old backups. */
object FeedAcquisition {
    const val LEGACY_UNKNOWN = "legacy_unknown"
    const val PUBLISHER_RSS = "publisher_rss"
    const val PUBLIC_API = "public_api"
    const val AI_WEB_GROUNDED = "ai_web_grounded"
    /** AI-authored transformation grounded in already cached/consulted source evidence, not new web research. */
    const val AI_SOURCE_GROUNDED = "ai_source_grounded"
    const val ORIGINAL_READ = "original_read"
}

object FeedAuthorship {
    const val LEGACY_UNKNOWN = "legacy_unknown"
    const val PUBLISHER = "publisher"
    const val AI = "ai"
    const val MIXED = "mixed"
}

@Serializable
data class FeedContentSection(
    val heading: String,
    val body: String,
)

/** Bounded local selection state. Item revisions are parallel to itemIds and hashed only. */
@Serializable
data class PersonalDailySelection098(
    val selectionDate: String = "",
    val timezone: String = "",
    val algorithmVersion: Int = 0,
    val itemIds: List<String> = emptyList(),
    val itemRevisions: List<String> = emptyList(),
    val inputDigest: String = "",
    val selectedAtMillis: Long = 0,
)

/** The only model-generated action exposed by the feed is a user-initiated question about this item. */
@Serializable
enum class AiSuggestedInteractionIntent { ASK_ABOUT_ITEM }

@Serializable
data class AiSuggestedInteraction(
    val intent: AiSuggestedInteractionIntent = AiSuggestedInteractionIntent.ASK_ABOUT_ITEM,
    val label: String,
    val prompt: String,
)

/**
 * Complete provider-authored question set for a non-feed or explicitly regenerated target.
 * `revision` is a local digest of the exact approved target/provider scope, never raw user text.
 */
@Serializable
data class AiSuggestionSet097(
    val targetKind: String,
    val targetId: String? = null,
    val revision: String,
    val questions: List<String>,
    val generatedAtMillis: Long,
)

@Serializable
data class AiRelevanceEstimate(
    val fitEstimate: Int,
    val assessedAtMillis: Long,
    val contentDigest: String,
    /** Digest of explicit module scope, confirmed context, explicit relevance feedback and provider identity. */
    val scopeProviderDigest: String,
)

@Serializable
data class FeedCitation(val title: String = "", val url: String)

@Serializable
data class FeedMedia(val url: String, val sourceUrl: String, val credit: String = "")

@Serializable
data class FeedWeather(
    val temperatureC: Double,
    val feelsLikeC: Double,
    val precipitationChance: Int,
    val weatherCode: Int,
    val location: String,
)

@Serializable
data class PersonalContextImport(
    val format: String = "mebot-context-v1",
    val name: String = "",
    val region: String = "",
    val timezone: String = "",
    val entries: List<ContextEntry> = emptyList(),
    val modules: List<FeedModule> = emptyList(),
)

/** Machine-readable discovery policy. Presentation copy lives in Android resources. */
data class ModulePreset(
    val kind: String,
    val group: String,
    val defaultQuery: String,
    val briefGuidance: String,
    val contentType: String = "article",
    val hasPublicSource: Boolean = false,
)

/** Titles and descriptions are localized by the presentation layer for catalog IDs. */
object ModuleCatalog {
    const val maxModules = 32
    val groups = listOf("signals", "growth", "life")
    val contentTypes = setOf("article", "video", "podcast", "repository", "course", "event", "opportunity", "weather", "entertainment", "original_read")
    val presets = listOf(
        ModulePreset("news", "signals", "The most consequential recent developments for the configured region and approved interests, with what changed and why it matters.",
            "Prioritize recent consequential developments, with a mix of sources and perspectives. Explain what changed and the practical consequence. Link to the reported story, not a search page. Separate reporting from opinion.", hasPublicSource = true),
        ModulePreset("technology", "signals", "Useful AI, software, cloud and hardware changes: one concrete capability, its availability and a direct way to try or understand it.",
            "Find primary release notes, working product pages, demonstrations and documentation. Describe the specific new capability, availability and material limits. Prefer tools relevant to approved technical interests; avoid launch hype."),
        ModulePreset("security", "signals", "Current vendor security advisories relevant to approved technologies, with affected versions, urgency and the documented fix.",
            "Use vendor advisories, CERT notices or original vulnerability records. State affected products and versions only when verified. Include the available fix or mitigation and a direct advisory link; do not imply the user is affected without evidence."),
        ModulePreset("business", "signals", "Verified regional business, infrastructure and policy developments with practical implications for operating or building a business.",
            "Find sourced business developments with clear operational implications. Prefer official announcements, filings or credible local reporting. Date numbers and distinguish reported facts from interpretation. Do not give personalized investment recommendations."),
        ModulePreset("science", "signals", "Recent original research related to approved interests, explaining the finding, evidence, limitation and possible practical use.",
            "Link the original paper, dataset or institutional research page. Explain the actual result in plain language, identify preprints, and include an important limitation. Do not turn early research into established claims."),
        ModulePreset("opportunities", "growth", "Open jobs, consulting work, grants, fellowships and contracts matching the approved skills and region, with an official next step.",
            "Find active opportunities with an official application page. Verify location, eligibility, cost and deadline before mentioning them. Say which eligibility details still need checking. Exclude expired or unverifiable listings.", "opportunity"),
        ModulePreset("scholarships", "growth", "Current funded study and research applications matching approved education interests, with confirmed funding, eligibility and application dates.",
            "Use the university or scholarship provider's current application page. Verify programme level, eligible nationalities or residence, funding coverage, intake and deadline. Do not imply eligibility or full funding unless explicitly supported.", "opportunity"),
        ModulePreset("learning", "growth", "A useful lesson, guide or short learning path for an approved skill, with a concrete practice outcome and a direct starting resource.",
            "Find an accessible lesson, official guide, course or lecture with a direct start link. State what can be learned and prerequisites when known. Distinguish free access from a paid certificate; never invent duration, price or certification.", "course"),
        ModulePreset("github", "growth", "Maintained open-source repositories and useful releases relevant to approved technical interests, with their purpose, setup and license when known.",
            "Link the actual repository or release. Explain the concrete use case, language or runtime when verified, license if confirmed, and relevant limitations. Prefer maintained projects over popularity alone. Never invent star counts or claim code has been audited.", "repository", true),
        ModulePreset("weather", "life", "Current conditions and the next 24 hours in the configured region, highlighting rain, heat and forecast timing with source timestamps.",
            "Use authoritative forecast observations with location and observation time. Clearly distinguish a forecast from current conditions. Never infer precise location from private context or invent weather measurements.", "weather", true),
        ModulePreset("entertainment", "life", "A small selection of films or series matching approved taste, with a spoiler-free reason, an official trailer and verified regional availability when known.",
            "Use official trailers, broadcaster pages, festival pages or reliable reviews. Explain the match to approved viewing preferences without spoilers. Verify regional streaming availability before naming a service. Link directly to the title or official trailer.", "entertainment"),
        ModulePreset("music", "life", "Relevant music discoveries, performances or instrument lessons, with one clear listening or practice idea and an official or licensed resource.",
            "Link official artist channels, performances, lessons or licensed learning resources. Match approved listening and instrument interests. Identify whether the link is a performance or lesson; do not imply an arrangement or sheet music is free when unverified.", "video"),
        ModulePreset("media", "life", "Short educational videos, demonstrations, explainers and interviews relevant to approved interests, from original creators or official channels.",
            "Find original or official short videos with a direct watch link. Prefer useful clips up to 10 minutes. Verify publisher, topic, publication date and duration before stating them. Use authorized watch pages or official embeds; never download or redistribute media. Exclude reuploads, compilations with unclear ownership and fabricated video URLs.", "video"),
        ModulePreset("events", "life", "Upcoming local or online events relevant to approved interests, with date, timezone, access or venue and the organizer's registration page.",
            "Use the organizer's registration or event page. Verify event date, timezone, location or online format, price if stated, and whether registration remains open. Exclude past events. Do not claim a booking or registration was made.", "event"),
        ModulePreset("travel", "life", "Specific places and practical travel updates relevant to the configured region, with official visitor information and verified logistics.",
            "Find a specific destination, attraction or travel update with an official resource link. Date price and schedule information; leave unknown costs unstated. Use official authorities for entry requirements. Never imply a reservation was made."),
        ModulePreset("horoscope", "life", "A short daily horoscope for the chosen zodiac sign.",
            "Write one brief optional entertainment reading for the selected sign. Avoid factual predictions, alarming claims or health, financial and relationship instructions. Do not imply a scientific basis.", "entertainment"),
    ) + EverydayModules083.presets
    val kinds = presets.map { it.kind }.toSet() + "watch"

    val defaults: List<FeedModule> = presets.map { preset ->
        FeedModule(id = preset.kind, kind = preset.kind, enabled = preset.kind == "news", alerts = preset.kind == "news",
            sources = if (preset.kind == "news") listOf(
                FeedSource("OpenAI", "https://openai.com/news/rss.xml"),
                FeedSource("GitHub", "https://github.blog/feed/"),
                FeedSource("Google AI", "https://blog.google/technology/ai/rss/"),
            ) else emptyList())
    }

    fun preset(kind: String): ModulePreset? = presets.find { it.kind == kind }
    /** Resolve an empty topic at request time without rewriting a saved preference. */
    fun discoveryQuery(module: FeedModule): String {
        val goal = module.query.trim().ifBlank {
            preset(module.kind)?.defaultQuery
                ?: "Current verified developments for the configured watch topic and its approved interests."
        }
        val preferences = ModulePreferences.discoveryGuidance(module)
        return if (preferences.isBlank()) goal else "$goal\nSelection preferences: $preferences."
    }

    fun briefGuidance(kind: String): String = (preset(kind)?.briefGuidance
        ?: "Follow the configured watch topic using current verified sources. Explain the specific change and link to the original resource.") +
        " Use the supplied discovery goal as the starting point and approved scoped context to choose relevant items. " +
        "When personal details are absent, give a useful general selection within this module; do not invent preferences, qualifications, subscriptions or a precise location. " +
        "Make each item useful on its own. Choose distinct developments or resources instead of several articles about the same thing."

    /** Add capabilities on upgrade without enabling them or changing an existing choice. */
    fun mergeCatalog(existing: List<FeedModule>): List<FeedModule> {
        val ids = existing.map { it.id }.toSet()
        val availableSlots = (maxModules - existing.size).coerceAtLeast(0)
        return existing + defaults.filter { it.id !in ids }.take(availableSlots).map { it.copy(enabled = false) }
    }
}
