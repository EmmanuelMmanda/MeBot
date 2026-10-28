package mebot.nguli.com.ui

import mebot.nguli.com.core.ContextEntry
import mebot.nguli.com.core.PersonalProfile085
import mebot.nguli.com.ui.feed.mergeContextDraft099
import org.junit.Assert.assertEquals
import org.junit.Test

class PersonalInterests099MergeTest {
    private val global = ContextEntry(
        id = PersonalProfile085.PROFILE_ID, text = "Saved profile", category = "profile",
        scopes = emptySet(), global = true, confirmed = true, updatedAtMillis = 1L,
    )
    private val interest = ContextEntry(
        id = "onboarding-interests", text = "Gardening", scopes = setOf("news"),
        confirmed = true, origin = "onboarding", updatedAtMillis = 2L,
    )
    private val legacy = ContextEntry(
        id = "legacy-learning", text = "Keep my course notes", scopes = setOf("learning"),
        confirmed = true, updatedAtMillis = 3L,
    )

    @Test fun autosavedInterestIsAdoptedWithoutReplacingUnrelatedLocalDraftAndDeletion() {
        val editedProfile = global.copy(text = "Unsaved profile draft")
        val base = listOf(global, interest, legacy)
        val local = listOf(editedProfile, interest)
        val durableInterest = interest.copy(text = "Urban gardening", scopes = setOf("news", "technology"), updatedAtMillis = 9L)
        val remote = listOf(global, durableInterest, legacy)

        val merged = mergeContextDraft099(base, local, remote, "Gardening", adoptHandedOffInterest = true)

        assertEquals("Unsaved profile is still editable", editedProfile, merged.single { it.id == global.id })
        assertEquals("Returned chooser note is durably visible", durableInterest, merged.single { it.id == interest.id })
        assertEquals("A local deletion is retained", null, merged.find { it.id == legacy.id })
    }

    @Test fun locallyEditedInterestWinsWhenItWasNotHandedOffAndUntouchedEntriesAdoptRemoteUpdates() {
        val base = listOf(global, interest, legacy)
        val localInterest = interest.copy(text = "Unsaved note conflict")
        val local = listOf(global, localInterest, legacy)
        val remote = listOf(global.copy(text = "Remote profile", updatedAtMillis = 7L),
            interest.copy(text = "Remote interest", updatedAtMillis = 8L), legacy.copy(text = "Remote legacy"))

        val merged = mergeContextDraft099(base, local, remote, null, adoptHandedOffInterest = false)

        assertEquals("Local edit wins its same-entry conflict", localInterest, merged.single { it.id == interest.id })
        assertEquals("Untouched entries still accept remote updates", remote.single { it.id == global.id }, merged.single { it.id == global.id })
        assertEquals(remote.single { it.id == legacy.id }, merged.single { it.id == legacy.id })
    }

    @Test fun chooserReturnWithoutDurableNoteChangeDoesNotReplaceUnsavedInterestDraft() {
        val base = listOf(global, interest)
        val localInterest = interest.copy(text = "Unsaved local edit")

        val merged = mergeContextDraft099(
            base, listOf(global, localInterest), base, "Unsaved local edit", adoptHandedOffInterest = true,
        )

        assertEquals(localInterest, merged.single { it.id == interest.id })
    }
}
