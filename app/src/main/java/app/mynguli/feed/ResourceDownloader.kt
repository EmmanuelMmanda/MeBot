package app.mynguli.feed

import android.content.Context
import app.mynguli.core.DownloadResource
import app.mynguli.core.FeedLogic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URI
import java.util.UUID

/** No provider credentials/cookies. Own IO capacity; never acquires the AI queue. */
class ResourceDownloader(
    private val context: Context,
    private val open: (String) -> HttpURLConnection = { URI(it).toURL().openConnection() as HttpURLConnection },
    private val resolve: (String) -> Array<InetAddress> = InetAddress::getAllByName,
) {
    companion object { private val transfers = Semaphore(1) }
    /** Small range probe: a URL suffix alone is not evidence that a file exists. */
    suspend fun available(resource: DownloadResource): Boolean = withTimeout(8_000) {
        withContext(Dispatchers.IO) {
            var url = resource.url
            repeat(4) { attempt ->
                coroutineContext.ensureActive()
                if (!FeedLogic.safeUrl(url, true)) return@withContext false
                val addresses = resolve(URI(url).host)
                if (addresses.isEmpty() || addresses.any {
                    it.isAnyLocalAddress || it.isLoopbackAddress || it.isLinkLocalAddress || it.isSiteLocalAddress ||
                        it.isMulticastAddress || (it.address.size == 16 && (it.address[0].toInt() and 0xfe) == 0xfc)
                }) return@withContext false
                val connection = open(url)
                try {
                    connection.instanceFollowRedirects = false
                    connection.connectTimeout = 3_000; connection.readTimeout = 3_000
                    connection.setRequestProperty("User-Agent", "MeBot/0.8.1")
                    connection.setRequestProperty("Range", "bytes=0-511")
                    connection.setRequestProperty("Accept-Encoding", "identity")
                    val status = connection.responseCode
                    if (status in 300..399 && attempt < 3) {
                        url = URI(url).resolve(connection.getHeaderField("Location") ?: return@withContext false).toString()
                        return@repeat
                    }
                    if (status !in setOf(200, 206)) return@withContext false
                    val total = connection.getHeaderField("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
                        ?: connection.contentLengthLong
                    if (total > DownloadResource.MAX_BYTES) return@withContext false
                    val prefix = connection.inputStream.use { input ->
                        val buffer = ByteArray(512); var read = 0
                        while (read < buffer.size) {
                            coroutineContext.ensureActive()
                            val count = input.read(buffer, read, buffer.size - read)
                            if (count < 0) break
                            if (count == 0) break
                            read += count
                        }
                        buffer.copyOf(read)
                    }
                    return@withContext prefix.isNotEmpty() && resource.accepts(connection.contentType.orEmpty(), prefix)
                } finally { connection.disconnect() }
            }
            false
        }
    }

    suspend fun fetch(resource: DownloadResource): File = withTimeout(45_000) {
        transfers.withPermit {
            withContext(Dispatchers.IO) {
                val dir = File(context.cacheDir, "resource-downloads").apply { mkdirs() }
                val now = System.currentTimeMillis()
                dir.listFiles()?.filter { now - it.lastModified() > 3_600_000 }?.forEach { it.delete() }
                val target = File(dir, "${UUID.randomUUID()}.${resource.extension}")
                try {
                    var current = resource.url
                    val deadline = System.nanoTime() + 40_000_000_000L
                    repeat(4) { attempt ->
                        coroutineContext.ensureActive()
                        require(FeedLogic.safeUrl(current, true)) { "Unsafe document link" }
                        val addresses = resolve(URI(current).host)
                        require(addresses.isNotEmpty() && addresses.none {
                            it.isAnyLocalAddress || it.isLoopbackAddress || it.isLinkLocalAddress ||
                                it.isSiteLocalAddress || it.isMulticastAddress ||
                                (it.address.size == 16 && (it.address[0].toInt() and 0xfe) == 0xfc)
                        }) { "Only public files are supported" }
                        val connection = open(current)
                        try {
                            connection.instanceFollowRedirects = false
                            connection.connectTimeout = 10_000
                            connection.readTimeout = 10_000
                            connection.setRequestProperty("User-Agent", "MeBot/0.8.1")
                            connection.setRequestProperty("Accept", resource.mime + ", application/octet-stream;q=0.5")
                            connection.setRequestProperty("Accept-Encoding", "identity")
                            val status = connection.responseCode
                            if (status in 300..399 && attempt < 3) {
                                current = URI(current).resolve(connection.getHeaderField("Location")
                                    ?: throw IOException("Missing redirect")).toString()
                                return@repeat
                            }
                            if (status != 200) throw IOException("File unavailable")
                            if (connection.contentLengthLong > DownloadResource.MAX_BYTES) throw IOException("File too large")
                            val prefix = ByteArrayOutputStream(32)
                            var size = 0L
                            connection.inputStream.use { input ->
                                target.outputStream().use { output ->
                                    val buffer = ByteArray(16 * 1024)
                                    while (true) {
                                        coroutineContext.ensureActive()
                                        if (System.nanoTime() > deadline) throw IOException("Download timed out")
                                        val n = input.read(buffer)
                                        if (n < 0) break
                                        size += n
                                        if (size > DownloadResource.MAX_BYTES) throw IOException("File too large")
                                        if (prefix.size() < 32) prefix.write(buffer, 0, minOf(n, 32-prefix.size()))
                                        output.write(buffer, 0, n)
                                    }
                                }
                            }
                            if (connection.contentLengthLong > 0 && connection.contentLengthLong != size) throw IOException("Incomplete download")
                            if (size == 0L || !resource.accepts(connection.contentType.orEmpty(), prefix.toByteArray()))
                                throw IOException("The link did not return the requested file")
                            if (resource.extension in setOf("docx", "xlsx", "pptx")) {
                                java.util.zip.ZipFile(target).use { archive ->
                                    val body = when (resource.extension) {
                                        "docx" -> "word/document.xml"
                                        "xlsx" -> "xl/workbook.xml"
                                        else -> "ppt/presentation.xml"
                                    }
                                    if (archive.getEntry("[Content_Types].xml") == null || archive.getEntry(body) == null)
                                        throw IOException("Invalid Office document")
                                }
                            }
                            coroutineContext.ensureActive()
                            return@withContext target
                        } finally { connection.disconnect() }
                    }
                    throw IOException("Too many redirects")
                } catch (failure: Throwable) {
                    target.delete()
                    throw failure
                }
            }
        }
    }
}
