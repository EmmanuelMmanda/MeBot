package app.mynguli.alarm

import android.net.Uri
import app.mynguli.core.AlarmPlan
import java.io.Closeable
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AlarmAudioPlaybackTest {
    @Test fun bundledAudioRingsUntilExternalSourceHasActuallyStarted() {
        val loader = FakeLoader()
        val playback = AlarmTonePlayback(loader, {}, {})
        playback.setGain(.32f)
        playback.play(custom())
        val fallback = loader.created.single()
        assertEquals("pulse", fallback.id)
        assertEquals(1, fallback.starts)
        assertEquals(.32f, fallback.volume, .001f)
        assertFalse(fallback.closed)
        val selected = loader.deliver()
        assertEquals(1, selected.starts)
        assertEquals(.32f, selected.volume, .001f)
        assertTrue(fallback.closed)
        playback.close()
        assertTrue(selected.closed)
        assertTrue(loader.closed)
    }

    @Test fun externalStartFailureOrSetupExceptionNeverStopsTheBundledAlarm() {
        val loader = FakeLoader()
        var fallbacks = 0
        val playback = AlarmTonePlayback(loader, { fallbacks++ }, {})
        playback.play(custom())
        val fallback = loader.created.single()
        val selected = loader.deliver(canStart = false)
        assertTrue(selected.closed)
        assertFalse(fallback.closed)
        assertEquals(1, fallbacks)
        loader.throwExternal = true
        playback.play(custom())
        assertTrue(fallback.closed)
        assertFalse(loader.created.last().closed)
        assertEquals(2, fallbacks)
        playback.close()
    }

    @Test fun canceledPreparationCannotRestartAStoppedOrReplacedAlarm() {
        val loader = FakeLoader()
        val playback = AlarmTonePlayback(loader, {}, {})
        playback.play(custom())
        val old = loader.pending.single()
        playback.play(AlarmPlan(soundId = "bell"))
        assertTrue(old.canceled)
        val stale = old.deliver()
        assertTrue(stale.closed)
        assertEquals(0, stale.starts)
        assertEquals("bell", loader.created.last().id)
        playback.stop()
        assertTrue(loader.created.last().closed)
        playback.close()
    }

    @Test fun pausedAlarmDoesNotStartALateExternalSourceUntilFocusReturns() {
        val loader = FakeLoader()
        val playback = AlarmTonePlayback(loader, {}, {})
        playback.play(custom())
        playback.pause()
        assertEquals(1, loader.created.single().pauses)
        val selected = loader.deliver()
        assertEquals(0, selected.starts)
        playback.setGain(.15f)
        playback.resume()
        assertEquals(1, selected.starts)
        assertEquals(.15f, selected.volume, .001f)
        playback.close()
    }

    @Test fun asynchronousErrorsExhaustFallbacksOnceInsteadOfRestartingABrokenToneForever() {
        val loader = FakeLoader()
        var unavailable = 0
        val playback = AlarmTonePlayback(loader, {}, { unavailable++ })
        playback.play(AlarmPlan(soundId = "pulse"))
        loader.created.first().fail()
        assertEquals(listOf("pulse", "classic"), loader.created.map { it.id })
        loader.created.last().fail()
        assertEquals(2, loader.created.size)
        assertEquals(1, unavailable)
        assertTrue(loader.created.all { it.closed })
        // An old platform error arriving after release cannot restart playback.
        loader.created.first().fail()
        assertEquals(2, loader.created.size)
        playback.close()
    }

    @Test fun externalPlaybackErrorReturnsToABundledSource() {
        val loader = FakeLoader()
        val playback = AlarmTonePlayback(loader, {}, {})
        playback.play(custom())
        val selected = loader.deliver()
        selected.fail()
        assertTrue(selected.closed)
        assertEquals(2, loader.created.size)
        assertEquals("pulse", loader.created.last().id)
        assertFalse(loader.created.last().closed)
        playback.close()
    }

    private fun custom() = AlarmPlan(soundId = "custom", soundUri = "content://test/audio")

    private class Output(val id: String, private val onError: (AlarmToneHandle) -> Unit, private val canStart: Boolean = true) : AlarmToneHandle {
        var starts = 0
        var pauses = 0
        var volume = 0f
        var closed = false
        override fun start(): Boolean { starts++; return canStart }
        override fun pause() { pauses++ }
        override fun gain(value: Float) { volume = value }
        override fun close() { closed = true }
        fun fail() = onError(this)
    }
    private class Pending(val ready: (AlarmToneHandle) -> Unit, val error: (AlarmToneHandle) -> Unit) : Closeable {
        var canceled = false
        fun deliver(canStart: Boolean = true) = Output("external", error, canStart).also(ready)
        override fun close() { canceled = true }
    }
    private class FakeLoader : AlarmToneLoader {
        val created = mutableListOf<Output>()
        val pending = mutableListOf<Pending>()
        var throwExternal = false
        var closed = false
        override fun bundled(id: String, onError: (AlarmToneHandle) -> Unit) = Output(id, onError).also(created::add)
        override fun external(uri: Uri, onReady: (AlarmToneHandle) -> Unit, onFailure: () -> Unit,
            onError: (AlarmToneHandle) -> Unit): Closeable {
            if (throwExternal) throw IllegalStateException("Audio unavailable")
            return Pending(onReady, onError).also(pending::add)
        }
        fun deliver(canStart: Boolean = true) = pending.last().deliver(canStart)
        override fun close() { closed = true }
    }
}
