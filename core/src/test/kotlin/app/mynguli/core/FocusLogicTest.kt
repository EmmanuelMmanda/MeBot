package app.mynguli.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class FocusLogicTest {
    private val start = 1_792_540_800_000L

    @Test fun `persisted deadline survives a process restart`() {
        val session = FocusLogic.start("Write one paragraph", 15, start)
        val restored = BackupCodec.decode(BackupCodec.encode(AppState(focus = session))).focus!!
        assertEquals(12 * 60_000L, FocusLogic.remainingMillis(restored, start + 3 * 60_000L))
    }

    @Test fun `pause excludes time spent paused`() {
        val session = FocusLogic.start("Read", 10, start)
        val paused = FocusLogic.pause(session, start + 2 * 60_000)
        assertEquals(8 * 60_000L, FocusLogic.remainingMillis(paused, start + 90 * 60_000))
        val resumed = FocusLogic.resume(paused, start + 90 * 60_000)
        assertNull(resumed.pausedRemainingMillis)
        assertEquals(7 * 60_000L, FocusLogic.remainingMillis(resumed, start + 91 * 60_000))
    }

    @Test fun `repeated pause and resume calls are idempotent`() {
        val running = FocusLogic.start("Read", 10, start)
        assertSame(running, FocusLogic.resume(running, start + 1_000))
        val paused = FocusLogic.pause(running, start + 2_000)
        assertSame(paused, FocusLogic.pause(paused, start + 50_000))
    }

    @Test fun `expired timer never shows negative time`() {
        val session = FocusLogic.start("Read", 5, start)
        assertEquals(0L, FocusLogic.remainingMillis(session, start + 600_000))
    }

    @Test fun `backward clock adjustment is bounded by the original duration`() {
        val session = FocusLogic.start("Read", 5, start)
        assertEquals(300_000L, FocusLogic.remainingMillis(session, start - 600_000))
    }

    @Test fun `resuming an expired paused timer cannot restart it`() {
        val session = FocusLogic.start("Read", 5, start)
        val paused = FocusLogic.pause(session, start + 600_000)
        val resumed = FocusLogic.resume(paused, start + 900_000)
        assertEquals(0L, FocusLogic.remainingMillis(resumed, start + 900_000))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `unfinished focus does not create completed time`() {
        FocusLogic.complete(FocusLogic.start("Read", 5, start), start + 1_000)
    }

    @Test fun `finished record retains session identity and selected duration`() {
        val session = FocusLogic.start(" Read ", 5, start)
        val record = FocusLogic.complete(session, start + 300_000)
        assertEquals(session.id, record.id)
        assertEquals("Read", record.title)
        assertEquals(5, record.minutes)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `focus rejects blank title`() {
        FocusLogic.start(" ", 5, start)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `focus rejects unsafe duration`() {
        FocusLogic.start("Read", Int.MAX_VALUE, start)
    }
}
