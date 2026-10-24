package app.mynguli.feed

import android.content.Context
import app.mynguli.MyNguliApplication
import app.mynguli.core.*

/** Deterministic test transports. These fixtures are never packaged in the APK. */
class FeedTestApplication : MyNguliApplication() {
    var sourceItems: List<FeedItem> = emptyList()
    var sourceFailure: String? = null
    var answerText = "This test update relates to your approved technical interests."
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
            override suspend fun ask(state: FeedState, item: FeedItem?, question: String, secret: String, language: String) =
                AiAnswer(answerText, item?.citations.orEmpty())
        }, language = { repository.current().settings.language })
    }
}

class TestVault(context: Context) : SecretVault(context) {
    private var credential: String? = null
    override fun read() = credential
    override fun write(secret: String) { credential = secret }
    override fun clear() { credential = null }
}
