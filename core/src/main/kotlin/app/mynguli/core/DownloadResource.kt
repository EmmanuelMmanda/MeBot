package app.mynguli.core

import java.net.URI
import java.net.URLDecoder

/** An explicit source URL, never a filename invented from a story title. */
data class DownloadResource(val url: String, val name: String, val mime: String, val extension: String) {
    companion object {
        const val MAX_BYTES = 20L * 1024 * 1024
        private val types = mapOf(
            "pdf" to "application/pdf", "txt" to "text/plain", "csv" to "text/csv",
            "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "png" to "image/png", "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "webp" to "image/webp",
        )
        fun from(url: String): DownloadResource? = runCatching {
            if (!FeedLogic.safeUrl(url, true)) return null
            val uri = URI(url)
            val last = URLDecoder.decode(uri.rawPath.orEmpty().substringAfterLast('/').replace("+", "%2B"), "UTF-8")
            val ext = last.substringAfterLast('.', "").lowercase()
            val mime = types[ext] ?: return null
            val stem = last.substringBeforeLast('.').replace(Regex("[^\\p{L}\\p{N} _.-]"), "_")
                .trim(' ', '.').take(80).ifBlank { "MeBot-resource" }
            DownloadResource(url, "$stem.$ext", mime, ext)
        }.getOrNull()
    }
    fun accepts(contentType: String, prefix: ByteArray): Boolean {
        val type = contentType.substringBefore(';').trim().lowercase()
        if (type in setOf("text/html", "application/xhtml+xml")) return false
        if (type.isNotBlank() && type !in setOf(mime, "application/octet-stream", "binary/octet-stream")) return false
        fun begins(vararg bytes: Int) = prefix.size >= bytes.size && bytes.indices.all { (prefix[it].toInt() and 255) == bytes[it] }
        return when (extension) {
            "pdf" -> prefix.toString(Charsets.US_ASCII).startsWith("%PDF-")
            "png" -> begins(137,80,78,71,13,10,26,10)
            "jpg", "jpeg" -> begins(255,216,255)
            "webp" -> prefix.size >= 12 && prefix.copyOfRange(0,4).toString(Charsets.US_ASCII) == "RIFF" &&
                prefix.copyOfRange(8,12).toString(Charsets.US_ASCII) == "WEBP"
            "docx", "xlsx", "pptx" -> begins(80,75,3,4)
            "txt", "csv" -> type == mime && !prefix.toString(Charsets.UTF_8).trimStart().startsWith("<", true)
            else -> false
        }
    }
}
