package mebot.nguli.com.feed

import android.content.Context
import mebot.nguli.com.MeBotApplication
import mebot.nguli.com.core.*
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.CopyOnWriteArrayList

/** Deterministic test transports. These fixtures are never packaged in the APK. */
class FeedTestApplication : MeBotApplication() {
    override fun onCreate() { super.onCreate(); androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(this) }
    var sourceItems: List<FeedItem> = emptyList()
    var sourceFailure: String? = null
    var answerText = "This test update relates to your approved technical interests."
    @Volatile var answerCalls = 0
    var suggestionQuestions: List<String>? = null
    @Volatile var suggestionCalls = 0
    @Volatile var suggestionGate: CompletableDeferred<Unit>? = null
    @Volatile var onSuggestionStarted: ((String?) -> Unit)? = null
    /** Records exact item and conversation scopes sent to the deterministic suggestion transport. */
    val suggestionRequestTargets = CopyOnWriteArrayList<Pair<String?, String?>>()
    override val feedSecrets by lazy { TestVault(this) }
    override val feedEngine by lazy {
        FeedEngine(feedRepository, feedSecrets, object : SourceReader {
            override suspend fun read(source: FeedSource, module: FeedModule, now: Long): List<FeedItem> {
                sourceFailure?.let { throw FeedFailure(it) }
                return sourceItems.filter { it.moduleId == module.id }
            }
        }, object : AiProvider {
            override suspend fun check(connection: AiConnection, secret: String) {
                if (secret == "invalid-test-credential") throw FeedFailure("unauthorized")
            }
            override suspend fun feed(state: FeedState, module: FeedModule, secret: String, language: String) = emptyList<FeedItem>()
            override suspend fun ask(state: FeedState, item: FeedItem?, question: String, secret: String, language: String): AiAnswer {
                answerCalls++
                return AiAnswer(answerText, item?.citations.orEmpty())
            }
            override suspend fun suggest(
                state: FeedState,
                item: FeedItem?,
                conversation: AiConversation?,
                module: FeedModule?,
                secret: String,
                language: String,
            ): List<String> {
                suggestionCalls++
                suggestionRequestTargets += item?.id to conversation?.id
                onSuggestionStarted?.invoke(item?.id ?: conversation?.id)
                suggestionGate?.await()
                return suggestionQuestions ?: if (language == "sw") listOf(
                    "Ni ushahidi gani katika taarifa hii unaounga mkono dai lake kuu?",
                    "Ni dhana gani ikibadilika ingeathiri zaidi hitimisho kuhusu jambo hili?",
                    "Ni tofauti gani ya gharama au hatari ni muhimu kabla ya kutumia njia hii?",
                    "Ni mbadala gani unaofaa kulinganisha na chaguo hili, na kwa nini?",
                    "Ni maelezo gani ambayo hayajathibitishwa yanaweza kubadili uamuzi hapa?",
                ) else listOf(
                    "What evidence in this item most strongly supports its main claim?",
                    "Which assumption would change the conclusion if it proved false?",
                    "What practical trade-off matters most before using this approach?",
                    "How does this option compare with the closest alternative described here?",
                    "What unresolved detail would be most useful to investigate next?",
                )
            }
        }, language = { repository.current().settings.language })
    }
}

class TestVault(context: Context) : SecretVault(context) {
    private var credential: String? = null
    override fun read() = credential
    override fun write(secret: String) { credential = secret }
    override fun clear() { credential = null }
}
