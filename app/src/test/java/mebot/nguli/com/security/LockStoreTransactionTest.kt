package mebot.nguli.com.security

import org.junit.Assert.*
import org.junit.Test

class LockStoreTransactionTest {
    private class Disk(var marker: Boolean = false, var record: Boolean = false, var key: Boolean = false) {
        val protected get() = marker || record
        val usablePin get() = record && key
    }
    @Test fun `setup failure before atomic commit leaves unconfigured data accessible not an unrecoverable trap`() {
        val disk = Disk(key = true)
        assertTrue(runCatching { LockStoreTransactions.enable({ error("injected before record commit") }, { disk.marker = true }) }.isFailure)
        assertFalse(disk.protected)
    }
    @Test fun `setup failure after record commit retains usable PIN without marker`() {
        val disk = Disk(key = true)
        assertTrue(runCatching { LockStoreTransactions.enable({ disk.record = true }, { error("injected marker failure") }) }.isFailure)
        assertTrue(disk.protected); assertTrue(disk.usablePin)
        // Normal verified-record load repairs marker; verifier is not discarded.
        disk.marker = true
        assertTrue(disk.protected); assertTrue(disk.usablePin)
    }
    @Test fun `successful setup commits record before marker`() {
        val order = mutableListOf<String>()
        LockStoreTransactions.enable({ order += "record" }, { order += "marker" })
        assertEquals(listOf("record", "marker"), order)
    }
    @Test fun `disable marker failure retains existing PIN and key`() {
        val disk = Disk(true, true, true)
        assertTrue(runCatching { LockStoreTransactions.disable({ error("marker") }, { disk.record = false }, { disk.key = false }) }.isFailure)
        assertTrue(disk.protected); assertTrue(disk.usablePin)
    }
    @Test fun `disable deletion failure still protects via file and retains key`() {
        val disk = Disk(true, true, true)
        assertTrue(runCatching { LockStoreTransactions.disable({ disk.marker = false }, { error("delete") }, { disk.key = false }) }.isFailure)
        assertTrue(disk.protected); assertTrue(disk.usablePin)
    }
    @Test fun `disable key cleanup failure cannot resurrect lock after authenticated durable removal`() {
        val disk = Disk(true, true, true)
        LockStoreTransactions.disable({ disk.marker = false }, { disk.record = false }, { error("keystore cleanup") })
        assertFalse(disk.protected); assertFalse(disk.usablePin)
    }
    @Test fun `successful disable orders marker then record then key`() {
        val order = mutableListOf<String>()
        LockStoreTransactions.disable({ order += "marker" }, { order += "record" }, { order += "key" })
        assertEquals(listOf("marker", "record", "key"), order)
    }
}
