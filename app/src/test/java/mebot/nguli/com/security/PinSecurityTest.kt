package mebot.nguli.com.security

import org.junit.Assert.*
import org.junit.Test

class PinSecurityTest {
    @Test fun `PIN validation accepts only bounded ASCII digits`() {
        assertFalse(PinSecurity.valid("12345".toCharArray()))
        assertFalse(PinSecurity.valid("１２３４５６".toCharArray()))
        assertFalse(PinSecurity.valid("123 56".toCharArray()))
        assertFalse(PinSecurity.valid("1".repeat(33).toCharArray()))
        assertTrue(PinSecurity.valid("718293".toCharArray()))
        assertTrue(PinSecurity.valid("1".repeat(32).toCharArray()))
    }
    @Test fun `salt changes verifier and equality compares full result`() {
        val a = PinSecurity.derive("718293".toCharArray(), ByteArray(32) { 1 })
        val b = PinSecurity.derive("718293".toCharArray(), ByteArray(32) { 2 })
        assertFalse(PinSecurity.matches(a, b)); assertTrue(PinSecurity.matches(a, a.copyOf()))
        assertEquals(32, a.size)
    }
    @Test fun `clock rollback never removes delay and wall clock is not consulted`() {
        val throttle = PinThrottle(7, 120_000, 50_000, 9)
        assertEquals(120_000, throttle.remaining(49_999, 9))
        assertEquals(120_000, throttle.remaining(100_000, 10))
        assertEquals(70_000, throttle.remaining(100_000, 9))
        assertEquals(0, throttle.remaining(170_000, 9))
    }
    @Test fun `delays escalate and are bounded without integer overflow`() {
        var throttle = PinThrottle()
        repeat(100) { throttle = throttle.reserve(0, 1) }
        assertEquals(30, throttle.failures); assertEquals(3_600_000, throttle.delayMillis)
    }
    @Test fun `unknown reboot identity conservatively rebases`() {
        val throttle = PinThrottle(5, 30_000, 0, -1)
        assertEquals(30_000, throttle.rebase(100_000, -1).remaining(100_000, -1))
    }
    @Test fun `record rejects downgraded KDF and malformed bounds`() {
        val valid = LockRecord(salt = ByteArray(32), verifier = ByteArray(32))
        assertNotNull(valid.validate())
        assertTrue(runCatching { valid.copy(iterations = 1).validate() }.isFailure)
        assertTrue(runCatching { valid.copy(salt = ByteArray(8)).validate() }.isFailure)
        assertTrue(runCatching { valid.copy(throttle = PinThrottle(-1)).validate() }.isFailure)
    }
}
