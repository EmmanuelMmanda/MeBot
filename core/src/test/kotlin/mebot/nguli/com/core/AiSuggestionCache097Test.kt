package mebot.nguli.com.core

import org.junit.Assert.*
import org.junit.Test

class AiSuggestionCache097Test {
    private val questions = listOf(
        "What timeline does this source give for the public launch?",
        "Which groups does the source identify as affected by the public launch?",
        "What evidence supports the main claim about the public launch?",
        "Which stated limitation could change how someone uses the public launch?",
        "What context would help compare the public launch with current alternatives?",
    )
    private val record = AiSuggestionSet097(
        targetKind = "general", targetId = null, revision = "a".repeat(64),
        questions = questions, generatedAtMillis = 1_800_000_000_000L,
    )

    @Test fun onlyFiveDistinctSubstantiveBoundedQuestionsCanBeCached() {
        assertTrue(AiSuggestionCache097.isValid(record))
        assertFalse(AiSuggestionCache097.isValid(record.copy(questions = questions.take(4))))
        assertFalse(AiSuggestionCache097.isValid(record.copy(questions = List(5) { questions.first() })))
        assertFalse(AiSuggestionCache097.isValid(record.copy(questions = questions + questions.first())))
        assertFalse(AiSuggestionCache097.isValid(record.copy(revision = "not-a-digest")))
        assertFalse(AiSuggestionCache097.isValid(record.copy(targetKind = "module", targetId = null)))
        assertFalse(AiSuggestionCache097.isValid(record.copy(questions = questions.mapIndexed { index, q ->
            if (index == 0) "What does https://example.com say about this current question?" else q
        })))
        assertEquals(AiSuggestionCache097.optionId(record, questions.first()), AiSuggestionCache097.optionId(record, questions.first()))
        assertNotEquals(AiSuggestionCache097.optionId(record, questions.first()), AiSuggestionCache097.optionId(record, questions.last()))
    }

    @Test fun cacheFreshnessBindsExactTargetRevisionAndTwentyFourHourExpiry() {
        val now = record.generatedAtMillis + 1_000L
        assertTrue(AiSuggestionCache097.isFresh(record, "general", null, record.revision, now))
        assertFalse(AiSuggestionCache097.isFresh(record, "module", null, record.revision, now))
        assertFalse(AiSuggestionCache097.isFresh(record, "general", null, "b".repeat(64), now))
        assertFalse(AiSuggestionCache097.isFresh(record, "general", "unexpected", record.revision, now))
        assertFalse(AiSuggestionCache097.isFresh(record, "general", null, record.revision,
            record.generatedAtMillis + AiSuggestionCache097.CACHE_TTL_MILLIS + 1))
    }

    @Test fun invalidDerivedSuggestionCacheIsDroppedWithoutLosingFeedNotesOrHistory() {
        val item = FeedItem("source", "news", "Public source", "Safe excerpt", sourceUrl = "https://example.com/source")
        val conversation = AiConversation(id = "chat", title = "Local chat",
            turns = listOf(AiTurn(id = "turn", role = "user", text = "A locally saved question")))
        val state = FeedState(items = listOf(item), conversations = listOf(conversation),
            context = listOf(ContextEntry(id = "note", text = "Confirmed note", scopes = setOf("news"))),
            suggestionSets097 = listOf(record.copy(questions = questions.take(3))))

        val validated = FeedLogic.validate(state)

        assertTrue(validated.suggestionSets097.isEmpty())
        assertEquals(listOf(item), validated.items)
        assertEquals(listOf(conversation), validated.conversations)
        assertEquals(state.context, validated.context)
    }
}
