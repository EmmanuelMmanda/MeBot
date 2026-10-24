package app.mynguli.feed

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.mynguli.core.DownloadResource
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ResourceDownloaderTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val pdf = DownloadResource.from("https://example.com/report.pdf")!!
    private fun publicAddress(host: String) = arrayOf(InetAddress.getByAddress(host, byteArrayOf(8,8,8,8)))
    private class Wire(val bytes: ByteArray, val mime: String = "application/pdf", val status: Int = 200, val redirect: String? = null,
        val declared: Long = bytes.size.toLong()) : HttpURLConnection(URL("https://example.com/report.pdf")) {
        var closed = false
        override fun connect() {}
        override fun disconnect() { closed = true }
        override fun usingProxy() = false
        override fun getResponseCode() = status
        override fun getContentType() = mime
        override fun getContentLengthLong() = declared
        override fun getHeaderField(name: String?) = if (name == "Location") redirect else null
        override fun getInputStream() = ByteArrayInputStream(bytes)
    }
    @Test fun validFileStreamsToPrivateCacheWithoutAiCredentials() = runBlocking {
        val wire = Wire("%PDF-1.7\n%fixture".toByteArray())
        val file = ResourceDownloader(context, { wire }, ::publicAddress).fetch(pdf)
        assertTrue(file.exists()); assertEquals(wire.bytes.toList(), file.readBytes().toList())
        assertNull(wire.getRequestProperty("Authorization"))
        assertNull(wire.getRequestProperty("Cookie"))
        assertTrue(wire.closed)
        file.delete()
        Unit
    }
    @Test fun htmlAndShortBodiesAreRejectedAndTemporaryFilesAreRemoved() = runBlocking {
        val dir = File(context.cacheDir, "resource-downloads")
        listOf(Wire("<html>log in</html>".toByteArray(), "text/html"), Wire("%PDF-1.7".toByteArray(), declared = 300)).forEach { wire ->
            val before = dir.listFiles()?.map { it.name }.orEmpty().toSet()
            try { ResourceDownloader(context, { wire }, ::publicAddress).fetch(pdf); fail("Should reject invalid body") }
            catch (_: java.io.IOException) {}
            assertEquals(before, dir.listFiles()?.map { it.name }.orEmpty().toSet())
            assertTrue(wire.closed)
        }
    }
    @Test fun redirectToPrivateHostIsBlockedBeforeOpeningSecondConnection() = runBlocking {
        val wire = Wire(ByteArray(0), status = 302, redirect = "https://private.example/report.pdf")
        var opened = 0
        try {
            ResourceDownloader(context, { opened++; wire }, { host ->
                if (host == "private.example") arrayOf(InetAddress.getByAddress(byteArrayOf(127,0,0,1))) else publicAddress(host)
            }).fetch(pdf)
            fail("Private redirect must fail")
        } catch (_: IllegalArgumentException) {}
        assertEquals(1, opened)
        assertTrue(wire.closed)
    }
    @Test fun oversizedFileIsRejectedBeforeReading() = runBlocking {
        val wire = Wire(ByteArray(0), declared = DownloadResource.MAX_BYTES + 1)
        try { ResourceDownloader(context, { wire }, ::publicAddress).fetch(pdf); fail() }
        catch (_: java.io.IOException) {}
        assertTrue(wire.closed)
    }
}
