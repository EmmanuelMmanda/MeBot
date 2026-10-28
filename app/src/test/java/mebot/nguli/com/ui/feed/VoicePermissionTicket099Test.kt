package mebot.nguli.com.ui.feed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoicePermissionTicket099Test {
    private var now = 1_000L
    private fun gate() = VoicePermissionTicket099(nowElapsedMillis = { now })

    @Test fun grantAtStartedWaitsForResumedAndIsConsumedExactlyOnce() {
        val gate = gate()
        val request = gate.issue("scope-a", "call-intent-1")!!
        assertEquals(VoicePermissionTicket099.Result.WAIT_FOR_RESUME,
            gate.onPermissionResult(request, granted = true, resumed = false, pageActive = true,
                unlocked = true, currentScopeKey = "scope-a"))
        assertTrue(gate.hasPendingRequest())

        // A repeated Activity Result cannot replace or double-consume the saved grant.
        assertEquals(VoicePermissionTicket099.Result.STALE,
            gate.onPermissionResult(request, granted = true, resumed = true, pageActive = true,
                unlocked = true, currentScopeKey = "scope-a"))
        assertEquals(request, gate.consumeOnResume(pageActive = true, unlocked = true, currentScopeKey = "scope-a"))
        assertNull(gate.consumeOnResume(pageActive = true, unlocked = true, currentScopeKey = "scope-a"))
        assertFalse(gate.hasPendingRequest())
    }

    @Test fun resumedGrantStartsNowAndCannotReplayOnANewerResume() {
        val gate = gate()
        val request = gate.issue("scope-a", "call-intent-2")!!
        assertEquals(VoicePermissionTicket099.Result.START_NOW,
            gate.onPermissionResult(request, granted = true, resumed = true, pageActive = true,
                unlocked = true, currentScopeKey = "scope-a"))
        assertNull(gate.consumeOnResume(pageActive = true, unlocked = true, currentScopeKey = "scope-a"))
        assertEquals(VoicePermissionTicket099.Result.STALE,
            gate.onPermissionResult(request, granted = true, resumed = true, pageActive = true,
                unlocked = true, currentScopeKey = "scope-a"))
    }

    @Test fun stopLockAndScopeChangeAllInvalidatePendingGrant() {
        fun pendingGrant(token: String) = gate().let { instance ->
            val request = instance.issue("scope-a", token)!!
            instance.onPermissionResult(request, granted = true, resumed = false, pageActive = true,
                unlocked = true, currentScopeKey = "scope-a")
            instance to request
        }

        val (stopped, _) = pendingGrant("stop")
        stopped.invalidateOnStop()
        assertNull(stopped.consumeOnResume(pageActive = true, unlocked = true, currentScopeKey = "scope-a"))

        val (locked, _) = pendingGrant("lock")
        locked.invalidateOnLock()
        assertNull(locked.consumeOnResume(pageActive = true, unlocked = false, currentScopeKey = "scope-a"))

        val (changed, _) = pendingGrant("scope")
        changed.invalidateIfScopeChanged(pageActive = true, unlocked = true, currentScopeKey = "scope-b")
        // Returning to the old scope does not revive a previously invalidated ticket.
        assertNull(changed.consumeOnResume(pageActive = true, unlocked = true, currentScopeKey = "scope-a"))

        val (leftPage, _) = pendingGrant("page")
        leftPage.invalidateIfScopeChanged(pageActive = false, unlocked = true, currentScopeKey = "scope-a")
        assertNull(leftPage.consumeOnResume(pageActive = true, unlocked = true, currentScopeKey = "scope-a"))
    }

    @Test fun deniedOrExpiredPermissionNeverCreatesResumeWork() {
        val deniedGate = gate()
        val denied = deniedGate.issue("scope-a", "denied")!!
        assertEquals(VoicePermissionTicket099.Result.DENIED,
            deniedGate.onPermissionResult(denied, granted = false, resumed = false, pageActive = true,
                unlocked = true, currentScopeKey = "scope-a"))
        assertNull(deniedGate.consumeOnResume(pageActive = true, unlocked = true, currentScopeKey = "scope-a"))

        val expiredGate = gate()
        val expired = expiredGate.issue("scope-a", "expired")!!
        assertEquals(VoicePermissionTicket099.Result.WAIT_FOR_RESUME,
            expiredGate.onPermissionResult(expired, granted = true, resumed = false, pageActive = true,
                unlocked = true, currentScopeKey = "scope-a"))
        now += VoicePermissionTicket099.DEFAULT_TTL_MILLIS
        assertNull(expiredGate.consumeOnResume(pageActive = true, unlocked = true, currentScopeKey = "scope-a"))
        assertFalse(expiredGate.hasPendingRequest())
    }

    @Test fun staleScopeIsRejectedAndOnlyOneRequestMayBeInFlight() {
        val gate = gate()
        val request = gate.issue("scope-a", "call-intent")!!
        assertNull(gate.issue("scope-b", "second-request"))
        assertEquals(VoicePermissionTicket099.Result.STALE,
            gate.onPermissionResult(request, granted = true, resumed = true, pageActive = true,
                unlocked = true, currentScopeKey = "scope-b"))
        assertFalse(gate.hasPendingRequest())
        assertNotNull(gate.issue("scope-b", "fresh-request"))
    }
}
