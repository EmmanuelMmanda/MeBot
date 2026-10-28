package mebot.nguli.com.feed

import android.app.Application
import mebot.nguli.com.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.URLDecoder
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PublicDiscoveryTest {
    private val now = Instant.parse("2026-10-23T08:00:00Z").toEpochMilli()

    @Test fun weatherUsesConfiguredRegionAndTypedForecastInsteadOfModelGuesses() = runBlocking {
        val http = RecordingHttp(listOf(geocode, forecast))
        val state = FeedState(region = "Example City, Example Country", timezone = "Africa/Dar_es_Salaam", context = listOf(ContextEntry(text = "Private profile note")))
        val item = PublicDiscoveryProvider(http).discover(state, FeedModule(id = "weather", kind = "weather"), "en", now).single()
        assertEquals("weather", item.contentType)
        assertEquals("Example City, Example Country", item.weather!!.location)
        assertEquals(28.4, item.weather!!.temperatureC, 0.001)
        assertEquals(32.2, item.weather!!.feelsLikeC, 0.001)
        assertEquals(35, item.weather!!.precipitationChance)
        assertEquals(3, item.weather!!.weatherCode)
        assertEquals(now, item.publishedAtMillis)
        assertFalse(item.aiGenerated)
        assertTrue(item.sourceUrl.startsWith("https://open-meteo.com/en/docs?"))
        assertTrue(item.citations.any { it.url == "https://www.geonames.org/" })
        assertTrue(URLDecoder.decode(http.urls.first(), "UTF-8").contains("name=Example City, Example Country"))
        assertTrue(http.urls.none { it.contains("Private") })
        assertTrue(http.bearers.all { it == null })
    }

    @Test fun todayForecastRequiresProviderValidityNotFetchTime() = runBlocking {
        val state = FeedState(region = "Example City", timezone = "Africa/Dar_es_Salaam")
        val module = FeedModule(id = "weather", kind = "weather")
        val unknown = PublicDiscoveryProvider(RecordingHttp(listOf(geocode, forecast))).discover(state, module, "en", now).single()
        assertNull(unknown.temporal)
        val verified = PublicDiscoveryProvider(RecordingHttp(listOf(geocode, forecast.replace("\"current\":{", "\"current\":{\"interval\":900,"))))
            .discover(state, module, "en", now).single()
        assertEquals(now, verified.temporal!!.validFromMillis)
        assertEquals(now + 900_000, verified.temporal!!.validUntilMillis)
        assertEquals(TodayReason.FORECAST, TodayFeed.edition(state.copy(modules = listOf(module), items = listOf(verified)), now).single().reason)
        assertTrue(TodayFeed.edition(state.copy(modules = listOf(module), items = listOf(verified)), now + 86_400_000).isEmpty())
    }

    @Test fun unknownRainProbabilityIsAnErrorNotAFabricatedZero() = runBlocking {
        val http = RecordingHttp(listOf(geocode, forecast.replace("[10,35,60]", "[10,null,60]")))
        val failure = runCatching { PublicDiscoveryProvider(http).discover(FeedState(region = "Example City"), FeedModule(id = "weather", kind = "weather"), "en", now) }.exceptionOrNull()
        assertEquals("invalid_response", (failure as FeedFailure).code)
    }

    @Test fun missingLocationMakesNoNetworkRequestAndOffersAnActionableFailure() = runBlocking {
        val http = RecordingHttp(emptyList())
        val failure = runCatching { PublicDiscoveryProvider(http).discover(FeedState(), FeedModule(id = "weather", kind = "weather"), "en", now) }.exceptionOrNull()
        assertEquals("region_required", (failure as FeedFailure).code)
        assertTrue(http.urls.isEmpty())
    }

    @Test fun cachedWeatherIsBoundToTheConfiguredLocation() = runBlocking {
        val http = RecordingHttp(listOf(geocode, forecast, geocode, forecast))
        val provider = PublicDiscoveryProvider(http)
        val module = FeedModule(id = "weather", kind = "weather")
        provider.discover(FeedState(region = "Example City"), module, "en", now)
        provider.discover(FeedState(region = "Example City"), module, "en", now + 1_000)
        assertEquals(2, http.urls.size)
        provider.discover(FeedState(region = "Second City"), module, "en", now + 2_000)
        assertEquals(4, http.urls.size)
    }

    @Test fun weatherLocationOverrideWorksWithoutAProfileLocationAndInvalidatesItsCache() = runBlocking {
        val http = RecordingHttp(listOf(geocode, forecast, geocode, forecast))
        val provider = PublicDiscoveryProvider(http)
        val state = FeedState(context = listOf(ContextEntry(text = "Private home address")))
        val module = FeedModule(id = "weather", kind = "weather", preferences = mapOf("location" to "Example City, Example Country"))
        provider.discover(state, module, "en", now)
        provider.discover(state, module, "en", now + 1_000)
        assertEquals(2, http.urls.size)
        provider.discover(state, module.copy(preferences = mapOf("location" to "Second City, Example Country")), "en", now + 2_000)
        assertEquals(4, http.urls.size)
        assertTrue(URLDecoder.decode(http.urls[0], "UTF-8").contains("name=Example City, Example Country"))
        assertTrue(URLDecoder.decode(http.urls[2], "UTF-8").contains("name=Second City, Example Country"))
        assertTrue(state.region.isEmpty())
        assertTrue(http.urls.none { URLDecoder.decode(it, "UTF-8").contains("Private home address") })
        assertTrue(http.bearers.all { it == null })
    }

    @Test fun explicitWeatherDestinationDoesNotUseTheHomeTimezoneToChooseAnotherCountry() = runBlocking {
        val places = """{"results":[{"name":"Example City","country":"Destination","country_code":"AA","latitude":1,"longitude":2,"timezone":"Europe/London"},{"name":"Example City","country":"Home","country_code":"BB","latitude":3,"longitude":4,"timezone":"America/New_York"}]}"""
        val http = RecordingHttp(listOf(places, forecast))
        val item = PublicDiscoveryProvider(http).discover(
            FeedState(region = "Home City", timezone = "America/New_York"),
            FeedModule(id = "weather", kind = "weather", preferences = mapOf("location" to "Example City, Destination")), "en", now).single()
        assertEquals("Example City, Destination", item.weather!!.location)
        assertTrue(URLDecoder.decode(http.urls[1], "UTF-8").contains("latitude=1.0&longitude=2.0"))
    }

    @Test fun ambiguousCityAcrossCountriesRequiresAQualifiedLocation() = runBlocking {
        val http = RecordingHttp(listOf("""{"results":[{"name":"Example City","country_code":"AA","latitude":1,"longitude":1,"timezone":"Europe/London"},{"name":"Example City","country_code":"BB","latitude":2,"longitude":2,"timezone":"America/New_York"}]}"""))
        val failure = runCatching { PublicDiscoveryProvider(http).discover(FeedState(region = "Example City"), FeedModule(id = "weather", kind = "weather"), "en", now) }.exceptionOrNull()
        assertEquals("location_not_found", (failure as FeedFailure).code)
        assertEquals(1, http.urls.size)
    }

    @Test fun oldForecastResponseDoesNotMasqueradeAsCurrentConditions() = runBlocking {
        val old = forecast.replace("\"time\":1792742400", "\"time\":1792442400")
        val failure = runCatching { PublicDiscoveryProvider(RecordingHttp(listOf(geocode, old))).discover(FeedState(region = "Example City"), FeedModule(id = "weather", kind = "weather"), "en", now) }.exceptionOrNull()
        assertEquals("invalid_response", (failure as FeedFailure).code)
    }

    @Test fun githubKeepsActualRepositoryLinksAndNeverSendsPrivateContext() = runBlocking {
        val http = RecordingHttp(listOf("""{"incomplete_results":false,"items":[
          {"full_name":"example/project","html_url":"https://github.com/example/project","description":"A public test project.","created_at":"2026-10-22T10:00:00Z","private":false},
          {"full_name":"example/private","html_url":"https://github.com/example/private","private":true},
          {"full_name":"bad","html_url":"http://localhost/private"}
        ]}"""))
        val state = FeedState(context = listOf(ContextEntry(text = "Never send this private note", scopes = setOf("github"))))
        val item = PublicDiscoveryProvider(http).discover(state, FeedModule(id = "github", kind = "github", query = "Kotlin"), "en", now).single()
        assertEquals("repository", item.contentType)
        assertEquals("example/project", item.title)
        assertEquals("https://github.com/example/project", item.sourceUrl)
        val query = URLDecoder.decode(http.urls.single(), "UTF-8")
        assertTrue(query.contains("Kotlin created:>=2026-10-16"))
        assertTrue(query.contains("sort=stars"))
        assertFalse(query.contains("private note"))
    }

    @Test fun githubUsesSelectedFocusAndCodeLanguageWithCustomTopicsAndCachesTheConfiguration() = runBlocking {
        val response = """{"incomplete_results":false,"items":[]}"""
        val http = RecordingHttp(List(4) { response })
        val provider = PublicDiscoveryProvider(http)
        val state = FeedState(context = listOf(ContextEntry(text = "Private career note", scopes = setOf("github"))))
        val module = FeedModule(id = "github", kind = "github", query = "database", preferences = linkedMapOf("focus" to "self_hosted", "code_language" to "python"))
        provider.discover(state, module, "en", now)
        provider.discover(state, module.copy(preferences = linkedMapOf("code_language" to "python", "focus" to "self_hosted")), "en", now + 1_000)
        assertEquals(1, http.urls.size)
        val first = URLDecoder.decode(http.urls.single(), "UTF-8")
        assertTrue(first.contains("q=database created:>=2026-10-16"))
        assertTrue(first.contains("topic:self-hosted language:python"))
        provider.discover(state, module.copy(preferences = mapOf("focus" to "libraries", "code_language" to "rust")), "en", now + 2_000)
        assertTrue(URLDecoder.decode(http.urls[1], "UTF-8").contains("topic:library language:rust"))
        provider.discover(state, module.copy(preferences = mapOf("focus" to "tools", "code_language" to "javascript")), "en", now + 3_000)
        assertTrue(URLDecoder.decode(http.urls[2], "UTF-8").contains("topic:developer-tools language:javascript"))
        provider.discover(state, module.copy(preferences = emptyMap()), "en", now + 4_000)
        val unrestricted = URLDecoder.decode(http.urls[3], "UTF-8")
        assertFalse(unrestricted.contains("topic:"))
        assertFalse(unrestricted.contains("language:"))
        assertTrue(http.urls.none { URLDecoder.decode(it, "UTF-8").contains("Private career note") })
        assertTrue(http.bearers.all { it == null })
    }

    @Test fun githubExactRepositoryQueriesUseBoundedUnauthenticatedReleasesWithHonestMetadata() = runBlocking {
        val releases = buildList {
            add(githubRelease("example", "project", 1, publishedAt = "2026-10-22T12:00:00Z"))
            add(githubRelease("example", "project", 2, prerelease = true, publishedAt = null))
            add(githubRelease("example", "project", 3, draft = true))
            add(githubRelease("attacker", "other-repo", 4)) // Cross-repository links are never exposed.
            add(githubRelease("example", "project", 5, publishedAt = "not-a-timestamp"))
            (6..10).forEach { add(githubRelease("example", "project", it)) }
        }.joinToString(",", prefix = "[", postfix = "]")
        val http = RecordingHttp(listOf(releases))
        val provider = PublicDiscoveryProvider(http)
        val state = FeedState(context = listOf(ContextEntry(text = "Private repo note", scopes = setOf("github"))))
        val module = FeedModule(id = "github", kind = "github", query = "example/project")

        val items = provider.discover(state, module, "en", now)
        assertEquals(6, items.size) // The API response is bounded to its first eight records; drafts/bad links are skipped.
        assertEquals("https://api.github.com/repos/example/project/releases?per_page=8", http.urls.single())
        assertTrue(http.bearers.all { it == null })
        assertTrue(http.urls.none { it.contains("Private repo note") })
        val published = items.first()
        assertEquals("https://github.com/example/project/releases/tag/v1", published.sourceUrl)
        assertEquals(FeedLogic.itemId(published.sourceUrl), published.id)
        assertEquals("GitHub Releases", published.sourceName)
        assertEquals(FeedAcquisition.PUBLIC_API, published.acquisition)
        assertEquals(FeedAuthorship.PUBLISHER, published.authorship)
        assertEquals(Instant.parse("2026-10-22T12:00:00Z").toEpochMilli(), published.publishedAtMillis)
        assertEquals(TemporalProvenance.PUBLISHER, published.temporal!!.provenance)

        val pre = items.first { "v2" in it.title }
        assertTrue(pre.title.contains("[pre-release]"))
        assertEquals(0L, pre.publishedAtMillis)
        assertNull(pre.temporal)
        assertFalse(items.any { "v3" in it.title }) // Drafts are never exposed.
        assertFalse(items.any { "v4" in it.title }) // A release link from a different repository is never exposed.
        val unknownDate = items.single { "v5" in it.title }
        assertEquals(0L, unknownDate.publishedAtMillis)
        assertNull(unknownDate.temporal)
        assertFalse(items.any { "v9" in it.title }) // No implicit second page/request.

        provider.discover(state, module, "en", now + 1_000)
        assertEquals(1, http.urls.size) // The existing per-module GitHub cache covers release queries.
    }

    @Test fun onlyCanonicalRepositoryTargetsUseReleasesAndOtherQueriesKeepRepositorySearch() = runBlocking {
        val directQueries = listOf("example/project", "https://github.com/example/project")
        directQueries.forEachIndexed { index, query ->
            val http = RecordingHttp(listOf("[]"))
            val result = PublicDiscoveryProvider(http).discover(
                FeedState(), FeedModule(id = "github-$index", kind = "github", query = query), "en", now,
            )
            assertTrue(result.isEmpty())
            assertEquals("https://api.github.com/repos/example/project/releases?per_page=8", http.urls.single())
            assertTrue(http.bearers.all { it == null })
        }

        val searchQueries = listOf(
            "https://github.com/example/project/",
            "https://github.com.evil.example/example/project",
            "example/project?sort=stars",
            "example/project with another topic",
        )
        searchQueries.forEachIndexed { index, query ->
            val http = RecordingHttp(listOf("""{"incomplete_results":false,"items":[]}"""))
            val result = PublicDiscoveryProvider(http).discover(
                FeedState(), FeedModule(id = "github-search-$index", kind = "github", query = query), "en", now,
            )
            assertTrue(result.isEmpty())
            assertTrue("Expected legacy search path for $query, got ${http.urls.single()}", http.urls.single().contains("/search/repositories?"))
            assertFalse(http.urls.single().contains("/releases?"))
        }
    }

    @Test fun prereleaseMarkerFollowsSelectedResponseLanguage() = runBlocking {
        val response = "[${githubRelease("example", "project", 1, prerelease = true)}]"
        val module = FeedModule(id = "github", kind = "github", query = "example/project")
        val english = PublicDiscoveryProvider(RecordingHttp(listOf(response))).discover(FeedState(), module, "en", now).single()
        val swahili = PublicDiscoveryProvider(RecordingHttp(listOf(response))).discover(FeedState(), module, "sw", now).single()
        assertTrue(english.title.contains("[pre-release]"))
        assertTrue(swahili.title.contains("[toleo la awali]"))
        assertEquals(english.sourceUrl, swahili.sourceUrl)
        assertEquals(english.publishedAtMillis, swahili.publishedAtMillis)
    }

    @Test fun releaseCacheIsBoundToRepositoryAndRateFailuresAreNotCachedOrRetried() = runBlocking {
        val first = githubRelease("example", "one", 1)
        val second = githubRelease("example", "two", 2)
        val http = RecordingHttp(listOf("[$first]", "[$second]"))
        val provider = PublicDiscoveryProvider(http)
        provider.discover(FeedState(), FeedModule(id = "github", kind = "github", query = "example/one"), "en", now)
        provider.discover(FeedState(), FeedModule(id = "github", kind = "github", query = "example/two"), "en", now + 1_000)
        assertEquals("https://api.github.com/repos/example/one/releases?per_page=8", http.urls[0])
        assertEquals("https://api.github.com/repos/example/two/releases?per_page=8", http.urls[1])

        var calls = 0
        val limited = PublicDiscoveryProvider(object : FeedHttp {
            override suspend fun request(url: String, method: String, body: String?, bearer: String?): String {
                calls++
                if (calls == 1) throw FeedFailure("rate_limit")
                return "[]"
            }
        })
        val target = FeedModule(id = "github", kind = "github", query = "example/limited")
        val failure = runCatching { limited.discover(FeedState(), target, "en", now) }.exceptionOrNull()
        assertEquals("rate_limit", (failure as FeedFailure).code)
        assertEquals(1, calls) // No hidden retry after the public API's rate limit.
        assertTrue(limited.discover(FeedState(), target, "en", now + 1_000).isEmpty())
        assertEquals(2, calls) // Failed responses do not poison the cache.
    }

    @Test fun explicitReleaseDiscoveryPreservesCancellationAndRejectsMalformedPayloads() = runBlocking {
        val cancelled = PublicDiscoveryProvider(object : FeedHttp {
            override suspend fun request(url: String, method: String, body: String?, bearer: String?): String =
                throw CancellationException("cancelled fixture")
        })
        val target = FeedModule(id = "github", kind = "github", query = "example/project")
        assertTrue(runCatching { cancelled.discover(FeedState(), target, "en", now) }.exceptionOrNull() is CancellationException)

        val malformed = runCatching {
            PublicDiscoveryProvider(RecordingHttp(listOf("""{"items": []}"""))).discover(FeedState(), target, "en", now)
        }.exceptionOrNull()
        assertEquals("invalid_response", (malformed as FeedFailure).code)
    }

    @Test fun incompleteGitHubSearchIsReportedInsteadOfPretendingCompleteDiscovery() = runBlocking {
        val failure = runCatching { PublicDiscoveryProvider(RecordingHttp(listOf("""{"incomplete_results":true,"items":[]}"""))).discover(
            FeedState(), FeedModule(id = "github", kind = "github"), "en", now) }.exceptionOrNull()
        assertEquals("provider_error", (failure as FeedFailure).code)
    }

    @Test fun newsKeepsActualLinksWithoutMisrepresentingSubmissionTimeAsPublication() = runBlocking {
        val http = RecordingHttp(listOf("""{"hits":[{"objectID":"12345","title":"A sourced development","url":"https://example.org/development","created_at_i":1792738800,"story_text":"<p>A useful <b>detail</b>.</p>"},{"objectID":"12346","title":"A discussion","url":null,"created_at_i":1792738800}]}"""))
        val items = PublicDiscoveryProvider(http).discover(FeedState(), FeedModule(id = "news", kind = "news"), "en", now)
        assertEquals(2, items.size)
        assertEquals("https://example.org/development", items.first().sourceUrl)
        assertEquals("A useful detail.", items.first().summary)
        assertEquals(0L, items.first().publishedAtMillis)
        assertEquals(now, items.first().fetchedAtMillis)
        assertEquals("example.org", items.first().sourceName)
        assertEquals("https://news.ycombinator.com/item?id=12345", items.first().citations.last().url)
        assertEquals("https://news.ycombinator.com/item?id=12346", items.last().sourceUrl)
        assertEquals("Hacker News", items.last().sourceName)
    }

    @Test fun unsupportedModulesDoNotPretendToHavePublicIntegrations() = runBlocking {
        val http = RecordingHttp(emptyList())
        assertTrue(PublicDiscoveryProvider(http).discover(FeedState(), FeedModule(id = "travel", kind = "travel"), "en", now).isEmpty())
        assertTrue(http.urls.isEmpty())
    }

    @Test fun cancellationIsNotConvertedIntoAProviderFailure() = runBlocking {
        val provider = PublicDiscoveryProvider(object : FeedHttp {
            override suspend fun request(url: String, method: String, body: String?, bearer: String?): String = throw CancellationException("cancelled fixture")
        })
        assertTrue(runCatching { provider.discover(FeedState(), FeedModule(id = "news", kind = "news"), "en", now) }.exceptionOrNull() is CancellationException)
    }

    private class RecordingHttp(private val responses: List<String>) : FeedHttp {
        val urls = mutableListOf<String>()
        val bearers = mutableListOf<String?>()
        override suspend fun request(url: String, method: String, body: String?, bearer: String?): String {
            urls += url; bearers += bearer
            return responses[urls.lastIndex]
        }
    }

    private val geocode = """{"results":[{"name":"Example City","country":"Example Country","latitude":-6.8,"longitude":39.2,"timezone":"Africa/Dar_es_Salaam"}]}"""
    private val forecast = """{"current":{"time":1792742400,"temperature_2m":28.4,"apparent_temperature":32.2,"weather_code":3},"hourly":{"time":[1792738800,1792742400,1792746000],"precipitation_probability":[10,35,60]}}"""

    private fun githubRelease(
        owner: String,
        repository: String,
        number: Int,
        draft: Boolean = false,
        prerelease: Boolean = false,
        publishedAt: String? = "2026-10-22T12:00:00Z",
    ): String = """{"id":$number,"tag_name":"v$number","name":"Release v$number","body":"A documented public release with a concrete change.","html_url":"https://github.com/$owner/$repository/releases/tag/v$number","draft":$draft,"prerelease":$prerelease,"published_at":${publishedAt?.let { "\"$it\"" } ?: "null"}}"""
}
