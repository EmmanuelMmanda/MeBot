package mebot.nguli.com.security

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AppLockControllerTest {
    private class MemoryStore(var record: LockRecord? = fixture.copy(), var broken: Boolean = false) : LockStorage {
        var writes = 0
        var onWrite: ((LockRecord) -> Unit)? = null
        override fun enabled() = record != null || broken
        override fun read(): LockRecord? { check(!broken); return record }
        override fun write(record: LockRecord) { check(!broken); writes++; onWrite?.invoke(record); this.record = record }
        override fun disable() { record = null }
    }
    private fun controller(store: MemoryStore = MemoryStore(), now: () -> Long = { 100_000 }, boot: () -> Int = { 7 }, locked: () -> Boolean = { false }) =
        AppLockController(store, now, boot, locked)

    @Test fun `configured session always starts locked and no unlocked boolean is stored`() {
        val store = MemoryStore()
        val first = controller(store)
        assertTrue(first.state.value.locked)
        runBlocking { first.foreground(true); assertTrue(first.unlock(pin())) }
        assertFalse(first.state.value.locked)
        assertTrue(controller(store).state.value.locked)
    }
    @Test fun `successful PIN clears caller array and background locks`() = runBlocking {
        val c = controller(); c.foreground(true)
        val input = pin()
        assertTrue(c.unlock(input)); assertTrue(input.all { it == '\u0000' })
        c.foreground(false); assertTrue(c.state.value.locked)
    }
    @Test fun `device keyguard refuses PIN result`() = runBlocking {
        val c = controller(locked = { true }); c.foreground(true)
        assertFalse(c.unlock(pin())); assertTrue(c.state.value.locked)
    }
    @Test fun `bad and too short PIN never unlock`() = runBlocking {
        val c = controller(); c.foreground(true)
        assertFalse(c.unlock("999999".toCharArray()))
        assertFalse(c.unlock("123".toCharArray()))
        assertEquals(LockProblem.WRONG_PIN, c.state.value.problem)
        assertTrue(c.state.value.locked)
    }
    @Test fun `fifth failed admission persists cooldown across process restart`() = runBlocking {
        val store = MemoryStore(); var now = 100_000L
        val c = controller(store, { now }); c.foreground(true)
        repeat(5) { assertFalse(c.unlock("1".toCharArray())) }
        assertEquals(5, store.record!!.throttle.failures)
        val restarted = controller(store, { now }); restarted.foreground(true)
        assertFalse(restarted.unlock(pin())); assertEquals(LockProblem.WAIT, restarted.state.value.problem)
        now += 30_000
        assertTrue(restarted.unlock(pin()))
    }
    @Test fun `reboot restarts persisted delay rather than granting retries`() = runBlocking {
        val store = MemoryStore(fixture.copy(throttle = PinThrottle(5, 30_000, 100_000, 7)))
        var now = 1_000L
        val c = controller(store, { now }, { 8 }); c.foreground(true)
        assertFalse(c.unlock(pin())); assertEquals(30_000, c.state.value.waitMillis)
        now += 29_999
        assertFalse(c.unlock(pin())); now++
        assertTrue(c.unlock(pin()))
    }
    @Test fun `corrupt or missing protected credential fails closed`() = runBlocking {
        val store = MemoryStore(broken = true)
        val c = controller(store); c.foreground(true)
        assertTrue(c.state.value.enabled); assertTrue(c.state.value.locked)
        assertFalse(c.unlock(pin())); assertFalse(c.disable(pin()))
        assertTrue(c.state.value.locked); assertTrue(store.enabled())
    }
    @Test fun `disable and PIN change require fresh correct PIN even when unlocked`() = runBlocking {
        val store = MemoryStore(); val c = controller(store); c.foreground(true)
        assertTrue(c.unlock(pin()))
        assertFalse(c.disable("1".toCharArray())); assertTrue(store.enabled())
        assertFalse(c.changePin("1".toCharArray(), "654321".toCharArray()))
        assertTrue(c.changePin(pin(), "654321".toCharArray()))
        c.lock()
        assertFalse(c.unlock(pin()))
        assertTrue(c.unlock("654321".toCharArray()))
        assertTrue(c.disable("654321".toCharArray())); assertFalse(store.enabled())
    }
    @Test fun `old biometric ticket cannot unlock after background or screen off`() {
        val c = controller(MemoryStore(fixture.copy(biometric = true))); c.foreground(true)
        val ticket = c.ticket(); c.lock()
        assertFalse(c.biometricSuccess(ticket)); assertTrue(c.state.value.locked)
        val second = c.ticket(); c.foreground(false); c.foreground(true)
        assertFalse(c.biometricSuccess(second)); assertTrue(c.state.value.locked)
    }
    @Test fun `biometric result requires enabled option and foreground`() {
        val c = controller(); c.foreground(true)
        assertFalse(c.biometricSuccess(c.ticket()))
        val enabled = controller(MemoryStore(fixture.copy(biometric = true)))
        assertFalse(enabled.biometricSuccess(enabled.ticket()))
        enabled.foreground(true); assertTrue(enabled.biometricSuccess(enabled.ticket()))
    }
    @Test fun `biometric enrollment setting needs PIN and does not prepare key on rejection`() = runBlocking {
        val c = controller(); c.foreground(true); var keys = 0
        assertFalse(c.setBiometric("1".toCharArray(), true) { keys++ })
        assertEquals(0, keys)
        assertTrue(c.setBiometric(pin(), true) { keys++ })
        assertEquals(1, keys); assertTrue(c.state.value.biometric)
    }
    @Test fun `screen off while PIN worker completes cannot resurrect unlocked state`() = runBlocking {
        val store = MemoryStore(); val c = controller(store); c.foreground(true)
        val reached = CountDownLatch(1); val release = CountDownLatch(1)
        store.onWrite = { record -> if (record.throttle.failures == 0) { reached.countDown(); check(release.await(10, TimeUnit.SECONDS)) } }
        val work = async(kotlinx.coroutines.Dispatchers.Default) { c.unlock(pin()) }
        assertTrue(reached.await(10, TimeUnit.SECONDS)); c.lock(); release.countDown()
        assertFalse(work.await()); assertTrue(c.state.value.locked)
    }
    @Test fun `cancelled settings coroutine cannot disable protection after slow verification`() = runBlocking {
        val store = MemoryStore(); val c = controller(store); c.foreground(true)
        val reached = CountDownLatch(1); val release = CountDownLatch(1)
        store.onWrite = { record -> if (record.throttle.failures == 0) { reached.countDown(); check(release.await(10, TimeUnit.SECONDS)) } }
        val work = async(kotlinx.coroutines.Dispatchers.Default) { c.disable(pin()) }
        assertTrue(reached.await(10, TimeUnit.SECONDS)); work.cancel(); release.countDown()
        work.join()
        assertTrue(store.enabled())
    }
    @Test fun `new lock requires deliberate valid PIN and starts locked`() = runBlocking {
        val store = MemoryStore(null); val c = controller(store); c.foreground(true)
        assertFalse(c.setup("12345".toCharArray())); assertFalse(store.enabled())
        assertTrue(c.setup(pin())); assertTrue(c.state.value.locked)
        assertEquals(PinSecurity.ITERATIONS, store.record!!.iterations)
    }
    companion object {
        private fun pin() = "718293".toCharArray() // Synthetic test fixture, never an owner PIN.
        private val fixture by lazy {
            val salt = ByteArray(32) { it.toByte() }
            LockRecord(salt = salt, verifier = PinSecurity.derive(pin(), salt))
        }
    }
}
