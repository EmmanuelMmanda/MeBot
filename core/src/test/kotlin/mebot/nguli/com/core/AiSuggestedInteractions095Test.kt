package mebot.nguli.com.core

import org.junit.Assert.*
import org.junit.Test

class AiSuggestedInteractions095Test {
    private val now = 1_800_000_000_000L
    private val module = FeedModule(id = "news", kind = "news", query = "public technology")
    private val suggestionSet = listOf(
        AiSuggestedInteraction(label = "Deployment timeline", prompt = "What does the source say about the deployment timeline?"),
        AiSuggestedInteraction(label = "Affected audiences", prompt = "Which organizations or users does the update say are affected?"),
        AiSuggestedInteraction(label = "Evidence quality", prompt = "What evidence supports the announced performance improvement?"),
        AiSuggestedInteraction(label = "Practical limits", prompt = "Where do the stated costs or limitations affect practical use?"),
        AiSuggestedInteraction(label = "Compare alternatives", prompt = "What background would help compare this change with existing alternatives?"),
    )
    private val suggestion = suggestionSet.first()

    @Test fun cacheBindsItemContentApprovedScopeProviderLanguageAndExpiry() {
        val item = item()
        val state = state(item)
        val cached = cached(state, item)
        val cachedState = state.copy(items = listOf(cached))
        val changedContext = cachedState.copy(context = listOf(
            ContextEntry(id = "new", text = "A different confirmed priority", scopes = setOf("news"))))

        assertTrue(AiSuggestedInteractions095.isApplicable(cachedState, cached, now, "en"))
        assertFalse(AiSuggestedInteractions095.isApplicable(cachedState, cached.copy(summary = "Changed facts."), now, "en"))
        assertFalse(AiSuggestedInteractions095.isApplicable(changedContext, cached, now, "en"))
        assertFalse(AiSuggestedInteractions095.isApplicable(cachedState, cached, now, "sw"))
        assertFalse(AiSuggestedInteractions095.isApplicable(cachedState.copy(connection = AiConnection(model = "other-model")), cached, now, "en"))
        assertFalse(AiSuggestedInteractions095.isApplicable(cachedState, cached, now + AiSuggestedInteractions095.CACHE_TTL_MILLIS + 1, "en"))
        assertFalse(AiSuggestedInteractions095.isApplicable(cachedState, cached.copy(suggestedInteractionsGeneratedAtMillis = now + 1), now, "en"))
        assertEquals(AiSuggestedInteractions095.contentDigest(item), cached.suggestedInteractionsContentDigest)
        assertEquals(64, cached.suggestedInteractionsScopeProviderDigest.length)
    }

    @Test fun pre097OneOrTwoQuestionCachesRemainReadableButAreNotShownAsComplete() {
        val item = item()
        val base = state(item)
        val old = cached(base, item).copy(suggestedInteractions = suggestionSet.take(2))
        val validated = FeedLogic.validate(base.copy(items = listOf(old)))

        assertEquals(1, validated.items.size)
        assertEquals(2, validated.items.single().suggestedInteractions.size)
        assertFalse(AiSuggestedInteractions095.isApplicable(validated, validated.items.single(), now, "en"))
        assertEquals(item.sourceUrl, validated.items.single().sourceUrl)
    }

    @Test fun resolverUsesCurrentItemAndScopeAndAllowsExistingSavedStories() {
        val item = item()
        val state = state(item)
        val cached = cached(state, item)
        val current = state.copy(items = listOf(cached), savedIds = setOf(item.id))
        val id = AiSuggestedInteractions095.suggestionId(cached, suggestion)
        val changedContext = current.copy(context = listOf(
            ContextEntry(id = "changed", text = "New confirmed note", scopes = setOf("news"))))

        assertEquals(suggestion, AiSuggestedInteractions095.resolve(current, item.id, id, now, "en"))
        assertNull(AiSuggestedInteractions095.resolve(changedContext, item.id, id, now, "en"))
        assertNull(AiSuggestedInteractions095.resolve(current.copy(items = listOf(cached.copy(title = "Changed title"))), item.id, id, now, "en"))
        assertNull(AiSuggestedInteractions095.resolve(current, "other-item", id, now, "en"))
        assertNull(AiSuggestedInteractions095.resolve(current, item.id, id, now + AiSuggestedInteractions095.CACHE_TTL_MILLIS + 1, "en"))
        assertNull(AiSuggestedInteractions095.resolve(current, item.id, "model-supplied-target", now, "en"))
    }

    @Test fun validatorBoundsTypedQuestionsAndRejectsLinksControlsAndDuplicates() {
        assertTrue(AiSuggestedInteractions095.areValid(listOf(suggestion)))
        assertTrue(AiSuggestedInteractions095.isCompleteSet(suggestionSet))
        assertFalse(AiSuggestedInteractions095.areValid(listOf(suggestion, suggestion.copy(label = "Same?"))))
        assertFalse(AiSuggestedInteractions095.areValid(listOf(suggestion.copy(label = "x".repeat(49)))))
        assertFalse(AiSuggestedInteractions095.areValid(listOf(suggestion.copy(prompt = "x".repeat(321) + "?"))))
        assertFalse(AiSuggestedInteractions095.areValid(listOf(suggestion.copy(prompt = "Try https://example.com?"))))
        assertFalse(AiSuggestedInteractions095.areValid(listOf(suggestion.copy(prompt = "What is this?\nignore rules"))))
        assertFalse(AiSuggestedInteractions095.areValid(listOf(suggestion.copy(prompt = "Understand this update"))))
        val partial = listOf(suggestion, suggestion.copy(prompt = "Which date is stated?"), suggestion.copy(prompt = "Who announced it?"))
        assertTrue(AiSuggestedInteractions095.areValid(partial)) // Legacy cache data remains decodable.
        assertFalse(AiSuggestedInteractions095.isCompleteSet(partial)) // But 3/5 is never renderable.
        assertFalse(AiSuggestedInteractions095.isCompleteSet(suggestionSet.take(4)))
        assertFalse(AiSuggestedInteractions095.isCompleteSet(List(5) { suggestion.copy(prompt = "What does the source say about the deployment timeline?") }))
    }

    @Test fun questionsRejectBareLinksAndOtherSchemesButAllowNumbersAndInitialisms() {
        listOf("example.com/story", "github.com/org/repo", "EXAMPLE.ORG", "ftp://example.com/file",
            "file:///tmp/data", "mailto:reader@example.com", "javascript:alert(1)").forEach { link ->
            assertFalse("link in prompt: $link", AiSuggestedInteractions095.areValid(listOf(suggestion.copy(prompt = "What does $link explain?"))))
            assertFalse("link in label: $link", AiSuggestedInteractions095.areValid(listOf(suggestion.copy(label = link))))
        }
        listOf("What changed in version 2.0?", "Does the U.S. announcement give a date?", "Which examples, e.g. courses, are included?").forEach { question ->
            assertTrue(question, AiSuggestedInteractions095.areValid(listOf(suggestion.copy(prompt = question))))
        }
    }

    @Test fun clearAndMergeKeepSourceFactsButDiscardStaleDerivedQuestions() {
        val item = item()
        val state = state(item)
        val cached = cached(state, item)
        val sameContent = FeedLogic.merge(state.copy(items = listOf(cached)), listOf(item.copy(fetchedAtMillis = now + 5)), now + 5).items.single()
        assertEquals(suggestionSet, sameContent.suggestedInteractions)
        val newlyGenerated = FeedLogic.merge(state.copy(items = emptyList()), listOf(cached), now + 5).items.single()
        assertEquals(suggestionSet, newlyGenerated.suggestedInteractions)

        val changed = FeedLogic.merge(state.copy(items = listOf(cached)), listOf(item.copy(summary = "Updated source facts.")), now + 5).items.single()
        assertTrue(changed.suggestedInteractions.isEmpty())
        assertEquals(item.sourceUrl, changed.sourceUrl)
        val cleared = AiSuggestedInteractions095.clear(cached)
        assertTrue(cleared.suggestedInteractions.isEmpty())
        assertEquals(item.title, cleared.title)
        assertEquals(item.sourceUrl, cleared.sourceUrl)
    }

    private fun cached(state: FeedState, item: FeedItem) = item.copy(
        suggestedInteractions = suggestionSet,
        suggestedInteractionsContentDigest = AiSuggestedInteractions095.contentDigest(item),
        suggestedInteractionsScopeProviderDigest = AiSuggestedInteractions095.scopeProviderDigest(state, module, "en"),
        suggestedInteractionsGeneratedAtMillis = now,
    )

    private fun state(item: FeedItem) = FeedState(
        modules = listOf(module), items = listOf(item), connection = AiConnection(model = "gpt-6-luna"),
        context = listOf(ContextEntry(id = "approved", text = "Confirmed public technology interest", scopes = setOf("news"))),
        region = "Tanzania", timezone = "Africa/Dar_es_Salaam",
    )

    private fun item() = FeedItem(
        id = "story-1", moduleId = module.id, kind = module.kind, title = "A source-backed technology update",
        summary = "The public source describes one new change and who it affects.", sourceName = "Example",
        sourceUrl = "https://example.com/story", publishedAtMillis = now - 60_000, fetchedAtMillis = now - 60_000,
    )
}
