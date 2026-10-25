package mebot.nguli.com

import android.app.Application
import mebot.nguli.com.alarm.AlarmController
import mebot.nguli.com.data.AppRepository
import mebot.nguli.com.feed.*
import mebot.nguli.com.feed.auth.ChatGptAuthManager

open class MeBotApplication : Application() {
    // Lazy: Direct Boot receivers must not open credential-protected personal data.
    val repository by lazy { AppRepository(this) }
    val alarms by lazy { AlarmController(this) }
    open val feedRepository by lazy { FeedRepository(this) }
    open val feedSecrets by lazy { SecretVault(this) }
    open val chatGptAuth by lazy { ChatGptAuthManager(this, language = { repository.current().settings.language }) }
    val softwareUpdates by lazy { mebot.nguli.com.updates.SoftwareUpdateManager(this) }
    open val feedMedia by lazy { FeedMediaRepository(this) }
    open val feedEngine by lazy {
        FeedEngine(feedRepository, feedSecrets, provider = OpenAiFeedProvider(streamDiscovery = true), chatGpt = chatGptAuth, discovery = PublicDiscoveryProvider(),
            enricher = PublisherMediaEnricher(), language = {
            when (val selected = repository.current().settings.language) {
                "en", "sw" -> selected
                else -> if (resources.configuration.locales[0]?.language == "sw") "sw" else "en"
            }
        })
    }
}
