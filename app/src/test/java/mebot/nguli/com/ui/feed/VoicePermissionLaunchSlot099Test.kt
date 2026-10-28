package mebot.nguli.com.ui.feed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoicePermissionLaunchSlot099Test {
    @Test fun invalidatedAuthorizationBlocksNewLaunchUntilTheOriginalResultReturns() {
        var now = 500L
        val gate = VoicePermissionTicket099(nowElapsedMillis = { now })
        val launches = VoicePermissionLaunchSlot099<String>(gate)
        val original = launches.issue("scope-old", "host", "original-private-snapshot")!!

        // ON_STOP revokes permission to begin a call, but does not misassociate Activity Result.
        gate.invalidateOnStop()
        assertTrue(launches.hasOutstandingLaunch())
        assertNull(launches.issue("scope-new", "host", "new-private-snapshot"))

        val returned = launches.consumeLaunch()!!
        assertEquals(original.request, returned.request)
        assertEquals("original-private-snapshot", returned.payload)
        assertNull("Result launch can be consumed only once", launches.consumeLaunch())
        assertEquals(VoicePermissionTicket099.Result.STALE,
            gate.onPermissionResult(returned.request, granted = true, resumed = true, pageActive = true,
                unlocked = true, currentScopeKey = "scope-old"))

        val next = launches.issue("scope-new", "host", "new-private-snapshot")!!
        assertEquals("new-private-snapshot", next.payload)
        assertTrue("A platform result is still outstanding until its callback", launches.hasOutstandingLaunch())
        val nextResult = launches.consumeLaunch()!!
        assertEquals(next.request, nextResult.request)
        assertEquals("new-private-snapshot", nextResult.payload)
        assertEquals("A fresh user request gets a fresh request identity", VoicePermissionTicket099.Result.START_NOW,
            gate.onPermissionResult(nextResult.request, granted = true, resumed = true, pageActive = true,
                unlocked = true, currentScopeKey = "scope-new"))
        assertFalse(launches.hasOutstandingLaunch())
    }

    @Test fun grantedResultIsOneShotAndASecondLaunchBindsItsOwnPayload() {
        val gate = VoicePermissionTicket099()
        val launches = VoicePermissionLaunchSlot099<String>(gate)
        val first = launches.issue("scope-a", "host", "a")!!
        val resultA = launches.consumeLaunch()!!
        assertNull(launches.consumeLaunch())
        assertEquals(VoicePermissionTicket099.Result.START_NOW,
            gate.onPermissionResult(resultA.request, granted = true, resumed = true, pageActive = true,
                unlocked = true, currentScopeKey = "scope-a"))

        val second = launches.issue("scope-b", "host", "b")!!
        assertTrue(launches.hasOutstandingLaunch())
        assertEquals(first.request.hostToken, second.request.hostToken)
        val resultB = launches.consumeLaunch()!!
        assertEquals("b", resultB.payload)
        assertEquals(VoicePermissionTicket099.Result.START_NOW,
            gate.onPermissionResult(resultB.request, granted = true, resumed = true, pageActive = true,
                unlocked = true, currentScopeKey = "scope-b"))
        assertFalse(launches.hasOutstandingLaunch())
    }
}
