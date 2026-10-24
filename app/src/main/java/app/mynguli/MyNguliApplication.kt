package app.mynguli

import android.app.Application
import app.mynguli.alarm.AlarmController
import app.mynguli.data.AppRepository
import app.mynguli.feed.*
import app.mynguli.feed.auth.ChatGptAuthManager

open class MyNguliApplication : Application() {
    // Lazy: Direct Boot receivers must not open credential-protected personal data.
    val repository by lazy { AppRepository(this) }
    val alarms by lazy { AlarmController(this) }
    open val feedRepository by lazy { FeedRepository(this) }
    open val feedSecrets by lazy { SecretVault(this) }
    open val chatGptAuth by lazy { ChatGptAuthManager(this, language = { repository.current().settings.language }) }
    open val feedMedia by lazy { FeedMediaRepository(this) }
    open val feedEngine by lazy {
        FeedEngine(feedRepository, feedSecrets, chatGpt = chatGptAuth, discovery = PublicDiscoveryProvider(),
            enricher = PublisherMediaEnricher(), language = {
            when (val selected = repository.current().settings.language) {
                "en", "sw" -> selected
                else -> if (resources.configuration.locales[0]?.language == "sw") "sw" else "en"
            }
        })
    }
}
