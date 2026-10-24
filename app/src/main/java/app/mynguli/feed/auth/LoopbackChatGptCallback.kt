package app.mynguli.feed.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean

internal data class ChatGptCallbackCopy(
    val title: String,
    val body: String,
    val invalid: String,
    val returnLabel: String,
    val packageName: String,
    val manualReturn: String = "",
    val language: String = "en",
)

/** Only a loopback-bound, state-matched, single-use request can supply the authorization response. */
internal class LoopbackChatGptCallback(private val copy: ChatGptCallbackCopy) : AutoCloseable {
    private val server = ServerSocket().apply {
        reuseAddress = false
        bind(InetSocketAddress(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), 0), 8)
        soTimeout = 500
    }
    private val closed = AtomicBoolean(false)
    @Volatile private var activeSocket: Socket? = null
    val redirectUri = "http://127.0.0.1:${server.localPort}/auth/callback"

    suspend fun await(expectedState: String): Map<String, String> = withContext(Dispatchers.IO) {
        while (!closed.get()) {
            currentCoroutineContext().ensureActive()
            val socket = try { server.accept() } catch (_: SocketTimeoutException) { continue }
            activeSocket = socket
            if (closed.get()) { socket.close(); break }
            socket.use {
                it.soTimeout = 2_000
                try {
                    if (!it.inetAddress.isLoopbackAddress) { respond(it, 400, false); return@use }
                    val raw = readHeaders(it)
                    val callback = parseRequest(raw, server.localPort)
                    if (!ChatGptIdTokenVerifier.constantTimeEqual(expectedState, callback["state"].orEmpty())) {
                        respond(it, 400, false)
                        return@use
                    }
                    respond(it, 200, true)
                    close()
                    return@withContext callback
                } catch (_: SocketTimeoutException) { runCatching { respond(it, 408, false) } }
                catch (_: IllegalArgumentException) { runCatching { respond(it, 400, false) } }
                catch (_: java.io.IOException) { /* A disconnected local browser cannot consume the pending attempt. */ }
                finally { activeSocket = null }
            }
        }
        throw ChatGptAuthException("chatgpt_cancelled")
    }

    @Synchronized
    override fun close() {
        if (closed.compareAndSet(false, true)) {
            runCatching { server.close() }
            runCatching { activeSocket?.close() }
        }
    }

    private fun readHeaders(socket: Socket): String {
        val input = socket.getInputStream()
        val output = ByteArrayOutputStream()
        val startedAt = System.nanoTime()
        var tail = 0
        while (output.size() < 16384) {
            if (System.nanoTime() - startedAt > 5_000_000_000L) throw SocketTimeoutException()
            val next = input.read()
            require(next >= 0 && next != 0)
            output.write(next)
            tail = (tail shl 8) or next
            if (tail == 0x0d0a0d0a) return output.toString("US-ASCII")
        }
        throw IllegalArgumentException()
    }

    private fun respond(socket: Socket, status: Int, matched: Boolean) {
        // No provider-supplied value, authorization code or state is reflected into this page.
        val title = escape(copy.title)
        val message = escape(if (matched) copy.body else copy.invalid)
        val returnUri = runCatching { ChatGptReturnLink.browserUri(copy.packageName) }.getOrNull()
        // Even an invalid attempt can offer navigation back to retry. This carries no auth data.
        val returnLink = if (returnUri != null) {
            "<a class=\"return\" href=\"${escape(returnUri)}\">${escape(copy.returnLabel)}<span aria-hidden=\"true\">&#8599;</span></a>" +
                if (copy.manualReturn.isBlank()) "" else "<p class=\"hint\">${escape(copy.manualReturn)}</p>"
        } else ""
        val language = copy.language.takeIf { it.matches(Regex("[a-z]{2,3}(?:-[A-Za-z]{2,8})?")) } ?: "en"
        val styleHash = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(PAGE_STYLE.toByteArray(Charsets.UTF_8)))
        val page = ("<!doctype html><html lang=\"$language\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1,viewport-fit=cover\">" +
            "<meta name=\"color-scheme\" content=\"light dark\"><title>$title</title><style>$PAGE_STYLE</style></head>" +
            "<body><main><div class=\"brand\"><span class=\"mark\" aria-hidden=\"true\">m</span><span>MeBot</span></div>" +
            "<h1>$title</h1><p class=\"body\">$message</p>$returnLink</main></body></html>")
            .toByteArray(Charsets.UTF_8)
        val reason = when (status) { 200 -> "OK"; 408 -> "Request Timeout"; else -> "Bad Request" }
        val headers = "HTTP/1.1 $status $reason\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${page.size}\r\nCache-Control: no-store\r\nPragma: no-cache\r\nReferrer-Policy: no-referrer\r\nContent-Security-Policy: default-src 'none'; style-src 'sha256-$styleHash'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'\r\nX-Content-Type-Options: nosniff\r\nConnection: close\r\n\r\n"
        socket.getOutputStream().apply { write(headers.toByteArray(Charsets.US_ASCII)); write(page); flush() }
    }

    companion object {
        private const val PAGE_STYLE = """
            :root{color-scheme:light dark;--bg:#f8f5f4;--surface:#fff;--ink:#29232a;--muted:#645c64;--accent:#9e2854;--on-accent:#fff;--line:#e6dfe2}
            *{box-sizing:border-box}body{margin:0;min-height:100svh;background:var(--bg);color:var(--ink);font:16px/1.55 system-ui,-apple-system,sans-serif;display:grid;align-items:center;padding:32px 20px;padding-bottom:max(32px,env(safe-area-inset-bottom))}
            main{width:100%;max-width:420px;margin:auto;padding:32px 26px;background:var(--surface);border:1px solid var(--line);border-radius:28px;box-shadow:0 12px 44px #24101d08}
            .brand{display:flex;align-items:center;gap:10px;font-size:18px;font-weight:700;letter-spacing:-.04em}.mark{display:grid;place-items:center;width:36px;height:36px;border-radius:12px;background:var(--accent);color:var(--on-accent);font-size:27px;line-height:1;padding-bottom:4px}
            h1{font-size:32px;line-height:1.12;letter-spacing:-.055em;margin:40px 0 16px;font-weight:700}.body{color:var(--muted);margin:0 0 28px}.return{display:flex;align-items:center;justify-content:space-between;gap:16px;min-height:56px;padding:15px 20px;border-radius:16px;background:var(--accent);color:var(--on-accent);font-weight:650;text-decoration:none}.return:focus-visible{outline:3px solid var(--ink);outline-offset:4px}.return span{font-size:24px;line-height:1}.hint{font-size:13px;color:var(--muted);margin:20px 0 0}
            @media(prefers-color-scheme:dark){:root{--bg:#171317;--surface:#231e23;--ink:#f5edf2;--muted:#c3b7c0;--accent:#f49bbe;--on-accent:#481229;--line:#3b3038}}
            @media(max-width:320px){body{padding:16px}main{padding:24px 20px}h1{font-size:28px}}
        """
        internal fun parseRequest(raw: String, port: Int): Map<String, String> {
            require(raw.length <= 16384 && raw.endsWith("\r\n\r\n"))
            val lines = raw.split("\r\n")
            val first = lines.first().split(' ')
            require(first.size == 3 && first[0] == "GET" && first[2] in setOf("HTTP/1.1", "HTTP/1.0"))
            val uri = try { URI(first[1]) } catch (_: Exception) { throw IllegalArgumentException() }
            require(!uri.isAbsolute && uri.rawAuthority == null && uri.rawFragment == null && uri.rawPath == "/auth/callback")
            val headers = lines.drop(1).filter { it.isNotEmpty() }.map {
                val index = it.indexOf(':')
                require(index > 0 && !it.startsWith(' ') && !it.startsWith('\t'))
                it.substring(0, index).lowercase() to it.substring(index + 1).trim()
            }
            require(headers.count { it.first == "host" } == 1 && headers.first { it.first == "host" }.second == "127.0.0.1:$port")
            require(headers.none { it.first in setOf("origin", "content-length", "transfer-encoding") })
            val query = uri.rawQuery ?: throw IllegalArgumentException()
            require(query.length in 1..8192)
            val pairs = query.split('&').map {
                val equals = it.indexOf('=')
                require(equals > 0)
                URLDecoder.decode(it.substring(0, equals), "UTF-8") to URLDecoder.decode(it.substring(equals + 1), "UTF-8")
            }
            require(pairs.size <= 16 && pairs.map { it.first }.distinct().size == pairs.size)
            require(pairs.all { (key, value) -> key.length <= 80 && value.length <= 4096 && value.none { it.isISOControl() } })
            return pairs.toMap()
        }
        private fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&#39;")
    }
}
