package mebot.nguli.com.core

/** No migration: legacy records retain their own identities, consent and scopes. */
object PersonalProfile085 {
    const val MAX_WORDS = 150
    const val MAX_CHARACTERS = 1000 // Existing portable context/service contract.
    const val PROFILE_ID = "profile-about-you"
    fun words(text: String): Int = text.split(Regex("[\\s\\p{Z}]+" )).count { it.isNotBlank() }
    fun valid(text: String): Boolean = words(text) <= MAX_WORDS && text.length <= MAX_CHARACTERS && '\u0000' !in text
    fun primary(entries: List<ContextEntry>): ContextEntry? = entries.find { it.id == PROFILE_ID }
        ?: entries.find { it.id == "onboarding-interests" }
    fun draft(entries: List<ContextEntry>, scopes: Set<String>): List<ContextEntry> =
        if (primary(entries) != null) entries else listOf(ContextEntry(id = PROFILE_ID, text = "", scopes = scopes)) + entries
    fun onboarding(entries: List<ContextEntry>, text: String, scopes: Set<String>): List<ContextEntry> {
        require(valid(text))
        val old = entries.find { it.id == "onboarding-interests" }
        val updated = if (text.isBlank()) emptyList() else listOf((old ?: ContextEntry(id = "onboarding-interests", text = "", origin = "onboarding"))
            .copy(text = text.trim(), scopes = scopes, confirmed = true, updatedAtMillis = System.currentTimeMillis()))
        return (entries.filterNot { it.id == "onboarding-interests" } + updated).also { require(it.size <= 80) }
    }
}
