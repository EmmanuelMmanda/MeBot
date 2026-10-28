package mebot.nguli.com.core

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest

/** Validation and stable identity for provider-only prepared suggestion sets. */
object AiSuggestionCache097 {
    const val QUESTION_COUNT = 5
    const val MAX_QUESTION_LENGTH = 320
    const val CACHE_TTL_MILLIS = 24L * 60 * 60 * 1000
    private const val MAX_TIMESTAMP = 32_503_680_000_000L
    private val digestPattern = Regex("[0-9a-f]{64}")
    private val urlPattern = Regex("""(?i)(?:\b[a-z][a-z0-9+.-]*://|\b(?:mailto|tel|sms|data|javascript):|\bwww\.|\b(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,}\b(?:/[^\s]*)?)""")

    fun isValid(record: AiSuggestionSet097): Boolean =
        record.targetKind in setOf("item", "conversation", "module", "general") &&
            (record.targetKind == "general" && record.targetId == null ||
                record.targetKind != "general" && record.targetId?.length?.let { it in 1..100 } == true) &&
            digestPattern.matches(record.revision) &&
            record.generatedAtMillis in 0..MAX_TIMESTAMP &&
            record.questions.size == QUESTION_COUNT && areValidQuestions(record.questions)

    fun areValidQuestions(questions: List<String>): Boolean =
        questions.size == QUESTION_COUNT && questions.all { question ->
            question.length in 24..MAX_QUESTION_LENGTH && question == question.trim() &&
                question.none { it.isISOControl() } && '?' in question && !urlPattern.containsMatchIn(question) &&
                question.split(Regex("\\s+")).count { it.length >= 2 } >= 5
        } && AiSuggestedInteractions095.areDistinctAngles(questions)

    fun isFresh(record: AiSuggestionSet097, kind: String, targetId: String?, revision: String, now: Long): Boolean =
        isValid(record) && record.targetKind == kind && record.targetId == targetId &&
            record.revision == revision && now in 0..MAX_TIMESTAMP &&
            now - record.generatedAtMillis in 0..CACHE_TTL_MILLIS

    fun optionId(record: AiSuggestionSet097, question: String): String = digest(listOf(
        "mebot-ai-suggestion-set-097-option-v1", record.targetKind, record.targetId.orEmpty(),
        record.revision, record.generatedAtMillis.toString(), question,
    )).take(24)

    fun key(kind: String, targetId: String?): String = "$kind:${targetId.orEmpty()}"

    private fun digest(parts: List<String>): String {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output -> parts.forEach { part ->
            val encoded = part.toByteArray(Charsets.UTF_8)
            output.writeInt(encoded.size); output.write(encoded)
        } }
        return MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
