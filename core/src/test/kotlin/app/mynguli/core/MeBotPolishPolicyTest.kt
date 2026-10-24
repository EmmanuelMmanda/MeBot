package app.mynguli.core

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class MeBotPolishPolicyTest {
    @Test fun oldPreferencesStillDecodeWithoutNewFields() {
        val old = Json.decodeFromString<Settings>("""{"language":"sw","theme":"dark","accent":"sage"}""")
        assertEquals("#7251B5", old.customAccent)
        assertEquals("classic", old.skin)
        assertEquals("sage", StateValidation.validate(AppState(settings = old)).settings.accent)
    }
    @Test fun invalidCustomColorsAreRejectedWithoutChangingSavedValues() {
        listOf("", "red", "#FFF", "#00000000", "#FFXX00", "0x123456", "<script>").forEach {
            assertNull(Appearance.normalizeHex(it))
        }
        assertEquals("#A1B2C3", Appearance.normalizeHex(" a1b2c3 "))
        try { StateValidation.validate(AppState(settings = Settings(customAccent = "#oops"))); fail() }
        catch (_: IllegalArgumentException) { }
    }
    @Test fun allPresetsAndCustomExtremesHaveReadableForegrounds() {
        val colors = Appearance.presets.values.map { it.removePrefix("#").toInt(16) } + listOf(0,0xFFFFFF,0xFFFF00,0x00FF00,0xFF00FF)
        colors.forEach { base ->
            assertTrue(Appearance.contrast(base, Appearance.foreground(base)) >= 4.5)
            for (dark in listOf(false, true)) {
                val toned = Appearance.readableAccent(base, dark)
                assertTrue(Appearance.contrast(toned, if (dark) 0x23211D else 0xF0EBDF) >= 5.0)
            }
        }
    }
    @Test fun appearanceSettingsRoundTripWithoutPrivateProfileOrConnection() {
        val settings = Settings(skin = "tinted", accent = "custom", customAccent = "#126789")
        assertEquals(settings, Json.decodeFromString<Settings>(Json.encodeToString(Settings.serializer(), settings)))
        assertEquals(Appearance.rgb(Settings()), Appearance.rgb(Settings(accent = "old-unknown")))
    }
    @Test fun downloadableResourcesRequireExplicitSafeHttpsFiles() {
        listOf("http://example.com/a.pdf", "https://user:pass@example.com/a.pdf",
            "https://127.0.0.1/a.pdf", "https://example.com/article", "file:///tmp/a.pdf",
            "https://example.com/download?filename=a.pdf", "https://example.com/installer.apk").forEach { assertNull(DownloadResource.from(it)) }
        assertEquals("report.pdf", DownloadResource.from("https://example.com/report.pdf?download=1")!!.name)
        assertEquals("application/pdf", DownloadResource.from("https://example.com/report.pdf")!!.mime)
    }
    @Test fun filenamesAreSanitizedAndNeverEscapeTheDownloadFolder() {
        val resource = DownloadResource.from("https://example.com/%2E%2E%2Fbad%0Aname.pdf")!!
        assertFalse(resource.name.contains('/')); assertFalse(resource.name.contains('\n'))
        assertFalse(resource.name.startsWith('.')); assertTrue(resource.name.endsWith(".pdf"))
    }
    @Test fun htmlAndMismatchedBytesCannotMasqueradeAsPdf() {
        val pdf = DownloadResource.from("https://example.com/report.pdf")!!
        assertTrue(pdf.accepts("application/pdf", "%PDF-1.7\n".toByteArray()))
        assertTrue(pdf.accepts("application/octet-stream", "%PDF-1.7\n".toByteArray()))
        assertFalse(pdf.accepts("text/html", "%PDF-1.7\n".toByteArray()))
        assertFalse(pdf.accepts("application/pdf", "<html>sign in</html>".toByteArray()))
        assertFalse(pdf.accepts("application/pdf", ByteArray(0)))
    }
    @Test fun officeImagesAndTextAreIdentifiedWithoutGuessingFromTitles() {
        assertEquals("document", RichContent.type("https://example.com/report.docx"))
        assertEquals("document", RichContent.type("https://example.com/table.csv"))
        assertEquals("link", RichContent.type("https://example.com/guide?format=pdf"))
        val image = DownloadResource.from("https://example.com/image.png")!!
        assertFalse(image.accepts("image/png", "broken".toByteArray()))
        val doc = DownloadResource.from("https://example.com/file.docx")!!
        assertTrue(doc.accepts(doc.mime, byteArrayOf(80,75,3,4)))
    }
}
