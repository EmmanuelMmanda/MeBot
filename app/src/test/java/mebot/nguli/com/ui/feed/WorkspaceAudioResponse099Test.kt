package mebot.nguli.com.ui.feed

import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WorkspaceAudioResponse099Test {
    @Test fun markdownBlocksKeepStructureAndReadableTextOmitsMarkupAndRawUrls() {
        val source = """# **Overview**

            Use **bold** text and [the guide](https://example.org/a). Also `inlineCode`.

            - First point
            - Second point
              1. Nested point

            | Option | Cost |
            | --- | ---: |
            | Alpha | Free |
            | Beta | $5 |

            ```kotlin
            println("hello")
            ```
        """.trimIndent()

        val blocks = RichMarkdown.parse(source)
        assertTrue(blocks.any { it is RichMarkdownBlock.Heading && it.level == 1 && it.text == "**Overview**" })
        assertTrue(blocks.any { it is RichMarkdownBlock.ListItem && !it.ordered && it.text == "First point" })
        assertTrue(blocks.any { it is RichMarkdownBlock.ListItem && it.ordered && it.indent > 0 })
        assertTrue(blocks.any { it is RichMarkdownBlock.Table && it.rows.size == 3 && it.rows[0] == listOf("Option", "Cost") })
        assertTrue(blocks.any { it is RichMarkdownBlock.Code && it.language == "kotlin" && it.text.contains("println") })

        val spoken = RichMarkdown.plainText(source)
        assertTrue(spoken.contains("bold"))
        assertTrue(spoken.contains("Overview"))
        assertTrue(spoken.contains("the guide"))
        assertTrue(spoken.contains("inlineCode"))
        assertFalse(spoken.contains("https://example.org"))
        assertFalse(spoken.contains("**"))
    }

    @Test fun speechChunksRespectLimitAndDoNotSplitOrdinaryWords() {
        val original = (1..30).joinToString(" ") { "word$it" }
        val chunks = chunkSpeechText(original, 80)
        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.length <= 80 })
        assertEquals(original, chunks.joinToString(" "))
    }

    @Test fun missingEngineVoiceDataUnsupportedLanguageAndSavedVoiceAreDistinct() {
        val missingEngine = FakeEngine().apply { service = false }
        val noEngineController = controller(missingEngine)
        noEngineController.start(1f, "")
        assertEquals(WorkspaceSpeechStatus.MISSING_ENGINE, noEngineController.snapshot.status)
        assertEquals(0, missingEngine.initializeCalls)

        val noVoice = FakeEngine().apply { availableVoices = emptyList() }
        val noVoiceController = controller(noVoice)
        noVoiceController.start(1f, "")
        assertEquals(WorkspaceSpeechStatus.MISSING_VOICE, noVoiceController.snapshot.status)

        val noData = FakeEngine().apply { availableVoices = listOf(voice(hasData = false)) }
        val noDataController = controller(noData)
        noDataController.start(1f, "")
        assertEquals(WorkspaceSpeechStatus.VOICE_DATA_MISSING, noDataController.snapshot.status)

        val unsupported = FakeEngine().apply { languageResult = TextToSpeech.LANG_NOT_SUPPORTED }
        val unsupportedController = controller(unsupported)
        unsupportedController.start(1f, "")
        assertEquals(WorkspaceSpeechStatus.UNSUPPORTED_LANGUAGE, unsupportedController.snapshot.status)

        val missingSavedVoice = FakeEngine()
        val savedVoiceController = controller(missingSavedVoice)
        savedVoiceController.start(1f, "voice-gone")
        assertEquals(WorkspaceSpeechStatus.SELECTED_VOICE_MISSING, savedVoiceController.snapshot.status)
    }

    @Test fun readRequestsAudioFocusUsesSystemMediaAttributesAndCanStopRestart() {
        val engine = FakeEngine().apply { maxSpeechInputLength = 80 }
        val focus = FakeFocus()
        val controller = WorkspaceSpeechController(engine, focus, Locale.ENGLISH)
        controller.start(1.25f, "voice-en")
        assertEquals(WorkspaceSpeechStatus.READY, controller.snapshot.status)
        assertEquals(1.25f, engine.lastRate)
        assertEquals("voice-en", engine.selectedVoice)
        assertEquals(AudioAttributes.USAGE_MEDIA, engine.audioAttributes?.usage)
        assertEquals(AudioAttributes.CONTENT_TYPE_SPEECH, engine.audioAttributes?.contentType)

        controller.read("answer-a", "Read **this** response with enough words to test proper chunking and prevent a TTS service call from exceeding its supported input boundary. [Help](https://example.org/help).")
        assertEquals("answer-a", controller.snapshot.readingId)
        assertEquals(WorkspaceSpeechStatus.READING, controller.snapshot.status)
        assertTrue(focus.requested)
        assertTrue(engine.spoken.size > 1)
        assertFalse(engine.spoken.joinToString(" ").contains("**"))
        assertFalse(engine.spoken.joinToString(" ").contains("https://"))

        val firstBatch = engine.spoken.toList()
        controller.stop()
        assertNull(controller.snapshot.readingId)
        assertEquals(WorkspaceSpeechStatus.STOPPED, controller.snapshot.status)
        assertTrue(focus.abandoned)
        assertTrue(engine.stopCalls > 0)

        controller.read("answer-a", "Restart from the beginning.")
        assertEquals("answer-a", controller.snapshot.readingId)
        firstBatch.last().second.let(engine.eventListener::onDone)
        assertEquals("answer-a", controller.snapshot.readingId) // stale done cannot end the newer session
        engine.spoken.last().second.let(engine.eventListener::onDone)
        assertNull(controller.snapshot.readingId)
        assertEquals(WorkspaceSpeechStatus.READY, controller.snapshot.status)
    }

    @Test fun lifecycleAndRuntimeErrorsStopCleanlyAndFocusDenialDoesNotSpeak() {
        val focusDenied = FakeFocus().apply { grant = false }
        val deniedEngine = FakeEngine()
        val denied = WorkspaceSpeechController(deniedEngine, focusDenied, Locale.ENGLISH)
        denied.start(1f, "")
        denied.read("answer", "A complete answer.")
        assertEquals(WorkspaceSpeechStatus.FOCUS_DENIED, denied.snapshot.status)
        assertNull(denied.snapshot.readingId)
        assertTrue(deniedEngine.spoken.isEmpty())

        val engine = FakeEngine()
        val focus = FakeFocus()
        val controller = WorkspaceSpeechController(engine, focus, Locale.ENGLISH)
        controller.start(1f, "")
        controller.read("answer", "Start reading, then leave this screen.")
        controller.onLifecycleStopped()
        assertEquals(WorkspaceSpeechStatus.STOPPED, controller.snapshot.status)
        assertNull(controller.snapshot.readingId)
        assertTrue(focus.abandoned)

        controller.read("answer", "Try once more.")
        engine.eventListener.onError(engine.spoken.last().second)
        assertEquals(WorkspaceSpeechStatus.SPEAK_ERROR, controller.snapshot.status)
        assertNull(controller.snapshot.readingId)
        controller.close()
        assertTrue(engine.shutdownCalls > 0)
    }

    @Test fun retryCanRecoverAfterSpeechEngineBecomesAvailable() {
        val engine = FakeEngine().apply { service = false }
        val controller = controller(engine)
        controller.start(1f, "")
        assertEquals(WorkspaceSpeechStatus.MISSING_ENGINE, controller.snapshot.status)
        engine.service = true
        controller.retry()
        assertEquals(WorkspaceSpeechStatus.READY, controller.snapshot.status)
        assertTrue(engine.initializeCalls > 0)
    }

    private fun controller(engine: FakeEngine): WorkspaceSpeechController =
        WorkspaceSpeechController(engine, FakeFocus(), Locale.ENGLISH)

    private fun voice(hasData: Boolean = true) = InstalledWorkspaceVoice("voice-en", "English · Voice", "en", hasData)

    private class FakeFocus : WorkspaceAudioFocus {
        var grant = true
        var requested = false
        var abandoned = false
        private var onLost: (() -> Unit)? = null
        override fun request(onLost: () -> Unit): Boolean {
            requested = true
            this.onLost = onLost
            return grant
        }
        override fun abandon() { abandoned = true; onLost = null }
        fun interrupt() = onLost?.invoke()
    }

    private class FakeEngine : WorkspaceSpeechEngine {
        var service = true
        var initialization = TextToSpeech.SUCCESS
        var languageResult = TextToSpeech.LANG_AVAILABLE
        var setVoiceResult = TextToSpeech.SUCCESS
        var setRateResult = TextToSpeech.SUCCESS
        var audioAttributesResult = TextToSpeech.SUCCESS
        var speakResult = TextToSpeech.SUCCESS
        var availableVoices = listOf(InstalledWorkspaceVoice("voice-en", "English · Voice", "en", true))
        var initializeCalls = 0
        var stopCalls = 0
        var shutdownCalls = 0
        var lastRate = 1f
        var selectedVoice: String? = null
        var audioAttributes: AudioAttributes? = null
        override var maxSpeechInputLength = 4000
        var spoken = mutableListOf<Pair<String, String>>()
        lateinit var eventListener: WorkspaceSpeechEngineListener

        override fun hasEngineService() = service
        override fun initialize(onInitialized: (Int) -> Unit) {
            initializeCalls++
            onInitialized(initialization)
        }
        override fun setAudioAttributes(attributes: AudioAttributes): Int { audioAttributes = attributes; return audioAttributesResult }
        override fun setLanguage(locale: Locale) = languageResult
        override fun voices() = availableVoices
        override fun setVoice(name: String): Int { selectedVoice = name; return setVoiceResult }
        override fun setSpeechRate(rate: Float): Int { lastRate = rate; return setRateResult }
        override fun speak(text: String, utteranceId: String): Int { spoken += text to utteranceId; return speakResult }
        override fun stop() { stopCalls++ }
        override fun shutdown() { shutdownCalls++ }
        override fun setListener(listener: WorkspaceSpeechEngineListener) { this.eventListener = listener }
    }
}
