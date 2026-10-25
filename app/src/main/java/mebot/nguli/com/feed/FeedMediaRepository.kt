package mebot.nguli.com.feed

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import mebot.nguli.com.core.FeedItem
import mebot.nguli.com.core.FeedLogic
import mebot.nguli.com.core.FeedMedia
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URI
import java.security.MessageDigest

interface FeedEnricher { suspend fun enrich(items: List<FeedItem>): List<FeedItem> }

/** Looks at metadata published by the linked source. Model output cannot supply images. */
class PublisherMediaEnricher(
    private val http: FeedHttp = SecureFeedHttp(maxBytes = 1024 * 1024, publicReadTimeoutMillis = 8_000, publicDeadlineMillis = 10_000),
    private val maxItems: Int = 6,
) : FeedEnricher {
    override suspend fun enrich(items: List<FeedItem>): List<FeedItem> = coroutineScope {
        val selected = items.filter { it.media == null && it.contentType != "weather" && FeedLogic.safeUrl(it.sourceUrl, true) }
            .take(maxItems.coerceIn(0, 8)).map { it.id }.toSet()
        val semaphore = Semaphore(2)
        items.map { item -> async {
            if (item.id !in selected) item else semaphore.withPermit {
                val media = try {
                    withTimeoutOrNull(12_000) { PublisherMediaParser.parse(http.request(item.sourceUrl), item.sourceUrl, item.sourceName) }
                } catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { null }
                item.copy(media = media)
            }
        } }.awaitAll()
    }
}

object PublisherMediaParser {
    private val metaTag = Regex("<meta\\b[^>]{0,4096}>", setOf(RegexOption.IGNORE_CASE))
    private val attributes = Regex("([A-Za-z_:][-A-Za-z0-9_:.]*)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))")
    private val jsonScript = Regex("<script\\b([^>]{0,4096})>([\\s\\S]*?)</script\\s*>", RegexOption.IGNORE_CASE)
    private val supportedTypes = setOf("Article", "NewsArticle", "BlogPosting", "TechArticle", "VideoObject", "Movie", "TVSeries", "Course", "Event", "Product", "CreativeWork")

    fun parse(html: String, sourceUrl: String, credit: String = ""): FeedMedia? {
        if (!FeedLogic.safeUrl(sourceUrl, true)) return null
        val bounded = html.take(1024 * 1024)
        val metas = metaTag.findAll(bounded).take(200).map { attrs(it.value) }.toList()
        for (key in listOf("og:image:secure_url", "og:image", "twitter:image", "twitter:image:src")) {
            for (meta in metas) {
                if ((meta["property"] ?: meta["name"])?.lowercase() == key) {
                    resolve(meta["content"].orEmpty(), sourceUrl)?.let { return FeedMedia(it, sourceUrl, credit.take(200)) }
                }
            }
        }
        jsonScript.findAll(bounded).take(20).forEach { match ->
            if (attrs(match.groupValues[1])["type"]?.lowercase()?.substringBefore(';') != "application/ld+json") return@forEach
            val raw = match.groupValues[2].takeIf { it.length <= 256 * 1024 } ?: return@forEach
            val json = runCatching { FeedJson.parseToJsonElement(raw) }.getOrNull() ?: return@forEach
            structuredImage(json, 0)?.let { candidate ->
                resolve(candidate, sourceUrl)?.let { return FeedMedia(it, sourceUrl, credit.take(200)) }
            }
        }
        return null
    }

    private fun attrs(tag: String): Map<String, String> = attributes.findAll(tag).take(50).associate { match ->
        match.groupValues[1].lowercase() to decode(match.groupValues.drop(2).firstOrNull { it.isNotEmpty() }.orEmpty())
    }

    private fun decode(value: String): String = android.text.Html.fromHtml(value, android.text.Html.FROM_HTML_MODE_LEGACY).toString().trim()

    private fun resolve(value: String, sourceUrl: String): String? = runCatching {
        if (value.isBlank() || value.length > 2048) return null
        URI(sourceUrl).resolve(value).toString().takeIf { FeedLogic.safeUrl(it, true) }
    }.getOrNull()

    private fun structuredImage(value: JsonElement, depth: Int): String? {
        if (depth > 6) return null
        if (value is JsonArray) return value.take(30).firstNotNullOfOrNull { structuredImage(it, depth + 1) }
        val obj = value as? JsonObject ?: return null
        val type = obj["@type"]
        val accepted = when (type) {
            is JsonPrimitive -> type.contentOrNull in supportedTypes
            is JsonArray -> type.any { (it as? JsonPrimitive)?.contentOrNull in supportedTypes }
            else -> false
        }
        if (accepted) imageValue(obj["image"] ?: obj["thumbnailUrl"])?.let { return it }
        return obj["@graph"]?.let { structuredImage(it, depth + 1) }
    }

    private fun imageValue(value: JsonElement?): String? = when (value) {
        is JsonPrimitive -> value.contentOrNull
        is JsonArray -> value.take(8).firstNotNullOfOrNull { imageValue(it) }
        is JsonObject -> (value["contentUrl"] as? JsonPrimitive)?.contentOrNull ?: (value["url"] as? JsonPrimitive)?.contentOrNull
        else -> null
    }
}

/** Small public-image cache. No authenticated WebView, cookies or provider credentials are used. */
class FeedMediaRepository(
    context: Context,
    private val fetcher: (suspend (String) -> ByteArray?)? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val directory = File(context.applicationContext.cacheDir, "feed-media-v1")
    private val memory = object : LruCache<String, Bitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }
    private val lock = Mutex()
    private val network = Semaphore(2)
    private val failures = linkedMapOf<String, Long>()
    private class LoadGate(val mutex: Mutex = Mutex(), var users: Int = 0)
    private val loads = mutableMapOf<String, LoadGate>()

    suspend fun load(url: String, retryFailed: Boolean = false): Bitmap? {
        if (!FeedLogic.safeUrl(url, true)) return null
        val key = digest(url)
        synchronized(memory) { memory.get(key) }?.let { return it }
        // Waiters share the result (including a short failure cooldown), while other URLs
        // retain two concurrent downloads. Gates exist only while a caller uses or awaits them.
        val gate = synchronized(loads) { loads.getOrPut(key) { LoadGate() }.also { it.users++ } }
        try {
            return gate.mutex.withLock {
                // Only an explicit user retry bypasses this URL's negative cache.
                // Clear after acquiring its load gate, never affecting other URLs or automatic throttling.
                if (retryFailed) lock.withLock { failures.remove(key) }
                loadOnce(url, key)
            }
        } finally {
            // No suspension here: cancelling either the leader or a waiter cannot leak a gate.
            synchronized(loads) { if (--gate.users == 0) loads.remove(key) }
        }
    }

    private suspend fun loadOnce(url: String, key: String): Bitmap? {
        synchronized(memory) { memory.get(key) }?.let { return it }
        if (lock.withLock { failures[key] }?.let { it > clock() } == true) return null
        val stored = File(directory, key)
        val cached = withContext(Dispatchers.IO) {
            lock.withLock {
                val valid = stored.isFile && stored.length() in 1..MAX_BYTES.toLong() && clock() - stored.lastModified() in 0 until CACHE_AGE
                if (valid) runCatching { stored.readBytes() }.getOrNull()
                else { stored.delete(); null }
            }
        }
        if (cached != null) {
            val bitmap = withContext(Dispatchers.Default) { decode(cached) }
            if (bitmap != null) return rememberImage(key, bitmap)
            // Corrupt bytes are evicted immediately, then repaired in this same load.
            withContext(Dispatchers.IO) { lock.withLock { stored.delete() } }
        }
        val bytes = try {
            network.withPermit { if (fetcher == null) fetch(url) else fetcher.invoke(url) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
        currentCoroutineContext().ensureActive()
        if (bytes == null || bytes.isEmpty() || bytes.size > MAX_BYTES) { recordFailure(key); return null }
        val bitmap = withContext(Dispatchers.Default) { decode(bytes) }
        if (bitmap == null) { recordFailure(key); return null }
        withContext(Dispatchers.IO) {
            lock.withLock {
                val pending = File(directory, "$key.part")
                try {
                    directory.mkdirs()
                    pending.outputStream().use { it.write(bytes) }
                    if (pending.renameTo(stored)) stored.setLastModified(clock())
                    trimDisk()
                } catch (_: Exception) {
                    // An optional disk cache failure must not discard an already decoded image.
                } finally {
                    pending.delete()
                }
            }
        }
        return rememberImage(key, bitmap)
    }

    private fun rememberImage(key: String, bitmap: Bitmap): Bitmap {
        synchronized(memory) { memory.put(key, bitmap) }
        return bitmap
    }

    private suspend fun recordFailure(key: String) = lock.withLock {
        failures.remove(key)
        failures[key] = clock() + 5 * 60_000L
        while (failures.size > 64) failures.remove(failures.keys.first())
    }

    private suspend fun fetch(url: String): ByteArray? = withContext(Dispatchers.IO) {
        try {
            var current = url
            val deadline = System.nanoTime() + 18_000_000_000L
            repeat(4) { index ->
                coroutineContext.ensureActive()
                if (System.nanoTime() >= deadline) return@withContext null
                if (!FeedLogic.safeUrl(current, true)) return@withContext null
                val host = URI(current).host
                if (!publicHost(host)) return@withContext null
                val connection = URI(current).toURL().openConnection() as HttpURLConnection
                try {
                    connection.instanceFollowRedirects = false
                    connection.requestMethod = "GET"
                    connection.connectTimeout = 5_000
                    connection.readTimeout = 6_000
                    connection.setRequestProperty("User-Agent", "MeBot/0.5 (+publisher-media)")
                    connection.setRequestProperty("Accept", "image/avif,image/webp,image/png,image/jpeg,image/gif")
                    val code = connection.responseCode
                    if (code in 300..399 && index < 3) {
                        current = URI(current).resolve(connection.getHeaderField("Location") ?: return@withContext null).toString()
                        return@repeat
                    }
                    if (code !in 200..299 || connection.contentLengthLong > MAX_BYTES) return@withContext null
                    val mime = connection.contentType?.substringBefore(';')?.lowercase().orEmpty()
                    if (mime !in setOf("image/jpeg", "image/png", "image/webp", "image/gif", "image/avif", "image/heif", "image/heic")) return@withContext null
                    val out = ByteArrayOutputStream()
                    connection.inputStream.use { input ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            coroutineContext.ensureActive()
                            val remainingMillis = (deadline - System.nanoTime()) / 1_000_000
                            if (remainingMillis <= 0) return@withContext null
                            connection.readTimeout = minOf(6_000L, remainingMillis).toInt().coerceAtLeast(1)
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (out.size() + count > MAX_BYTES) return@withContext null
                            out.write(buffer, 0, count)
                        }
                    }
                    return@withContext out.toByteArray()
                } finally { connection.disconnect() }
            }
            null
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { null }
    }

    private fun publicHost(host: String): Boolean {
        val addresses = InetAddress.getAllByName(host)
        return addresses.isNotEmpty() && addresses.none { address ->
            val bytes = address.address
            address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress || address.isMulticastAddress ||
                (bytes.size == 16 && (bytes[0].toInt() and 0xfe) == 0xfc)
        }
    }

    private fun trimDisk() {
        val files = directory.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() }.orEmpty()
        var size = 0L
        files.forEach { file ->
            val length = file.length()
            if (file.name.endsWith(".part") || clock() - file.lastModified() !in 0 until CACHE_AGE || size + length > 32 * 1024 * 1024) file.delete()
            else size += length
        }
    }

    companion object {
        private const val MAX_BYTES = 4 * 1024 * 1024
        private const val CACHE_AGE = 7 * 24 * 60 * 60_000L
        private fun digest(url: String) = MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }

        internal fun decode(bytes: ByteArray): Bitmap? {
            if (bytes.size > MAX_BYTES) return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth < 100 || bounds.outHeight < 60 || bounds.outWidth.toLong() * bounds.outHeight > 30_000_000L) return null
            var sample = 1
            while (bounds.outWidth / sample > 1200 || bounds.outHeight / sample > 1200) sample *= 2
            return runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) }.getOrNull()
        }
    }
}
