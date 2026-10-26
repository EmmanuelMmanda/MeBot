package mebot.nguli.com.feed.auth

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.Socket
import java.net.URI

/** Loopback-only transport tests. No account, browser, token exchange or paid provider is contacted. */
class ChatGptCallbackResponsivenessTest {
    private val copy = ChatGptCallbackCopy("Return", "Continue in app", "Invalid", "Open app", "mebot.nguli.com.debug")
    private fun request(port: Int, state: String = "expected", code: String = "fixture-code") =
        "GET /auth/callback?state=$state&code=$code HTTP/1.1\r\nHost: 127.0.0.1:$port\r\n\r\n"
    private fun send(port: Int, state: String = "expected", code: String = "fixture-code"): String = Socket("127.0.0.1", port).use {
        it.soTimeout = 5_000
        it.getOutputStream().apply { write(request(port, state, code).toByteArray()); flush() }
        it.getInputStream().bufferedReader().readText()
    }
    @Test fun idleBrowserPreconnectionDoesNotBlockRealCallbackBehindReadTimeout() = runBlocking {
        LoopbackChatGptCallback(copy).use { listener ->
            val port = URI(listener.redirectUri).port
            val result = async(Dispatchers.IO) { listener.await("expected") }
            Socket("127.0.0.1", port).use { idle ->
                // Send an unfinished header to reproduce a browser preconnect/slow header connection.
                idle.getOutputStream().apply { write("GET /auth/callback?".toByteArray()); flush() }
                withTimeout(5_000) { while (listener.activeConnectionCount < 1) delay(5) }
                val page = withTimeout(1_500) { withContext(Dispatchers.IO) { send(port) } }
                assertTrue(page.startsWith("HTTP/1.1 200"))
                assertEquals("fixture-code", result.await()["code"])
            }
        }
    }
    @Test fun validCallbackSurvivesBrowserResponseWriteFailure() = runBlocking {
        LoopbackChatGptCallback(copy) { _, _, _ -> throw IOException("synthetic closed browser response") }.use { listener ->
            val port = URI(listener.redirectUri).port
            val result = async(Dispatchers.IO) { listener.await("expected") }
            withContext(Dispatchers.IO) { runCatching { send(port) } }
            assertEquals("fixture-code", withTimeout(2_000) { result.await() }["code"])
        }
    }
    @Test fun wrongStateWithDisconnectedBrowserDoesNotConsumePendingAttempt() = runBlocking {
        LoopbackChatGptCallback(copy) { _, _, _ -> throw IOException("synthetic response failure") }.use { listener ->
            val port = URI(listener.redirectUri).port
            val result = async(Dispatchers.IO) { listener.await("expected") }
            withContext(Dispatchers.IO) { runCatching { send(port, "wrong") } }
            assertFalse(result.isCompleted)
            withContext(Dispatchers.IO) { runCatching { send(port) } }
            assertEquals("expected", withTimeout(2_000) { result.await() }["state"])
        }
    }
    @Test fun concurrentMatchingRequestsStillAcceptOneCallbackOnly() = runBlocking {
        LoopbackChatGptCallback(copy).use { listener ->
            val port = URI(listener.redirectUri).port
            val result = async(Dispatchers.IO) { listener.await("expected") }
            val responses = (1..3).map { n -> async(Dispatchers.IO) { runCatching { send(port, code = "code-$n") }.getOrDefault("") } }.awaitAll()
            assertEquals(1, responses.count { it.startsWith("HTTP/1.1 200") })
            assertTrue(result.await()["code"] in (1..3).map { "code-$it" })
        }
    }
    @Test fun closingListenerClosesAllPendingHeaderReaders() = runBlocking {
        val listener = LoopbackChatGptCallback(copy)
        val port = URI(listener.redirectUri).port
        val result = async(Dispatchers.IO) { runCatching { listener.await("expected") } }
        val sockets = (1..3).map { Socket("127.0.0.1", port).apply { soTimeout = 2_000 } }
        try {
            listener.close()
            assertTrue(withTimeout(2_000) { result.await() }.isFailure)
            sockets.forEach { socket ->
                try { assertEquals(-1, socket.getInputStream().read()) }
                catch (_: java.net.SocketException) { /* Socket reset is also a completed close. */ }
            }
        } finally { sockets.forEach { it.close() }; listener.close() }
    }
    @Test fun fullHeaderReaderPoolRejectsExcessThenRecoversFreedSlot() = runBlocking {
        LoopbackChatGptCallback(copy).use { listener ->
            val port = URI(listener.redirectUri).port
            val result = async(Dispatchers.IO) { listener.await("expected") }
            val idle = (1..4).map { Socket("127.0.0.1", port) }
            try {
                withTimeout(5_000) { while (listener.activeConnectionCount < 4) delay(5) }
                Socket("127.0.0.1", port).use { extra ->
                    extra.soTimeout = 1_000
                    try { assertEquals(-1, extra.getInputStream().read()) }
                    catch (_: java.net.SocketException) { /* Saturated connections can be reset. */ }
                }
                assertFalse(result.isCompleted)
                idle.first().close()
                withTimeout(5_000) { while (listener.activeConnectionCount >= 4) delay(5) }
                assertTrue(withContext(Dispatchers.IO) { send(port) }.startsWith("HTTP/1.1 200"))
                assertEquals("fixture-code", result.await()["code"])
            } finally { idle.forEach { it.close() } }
        }
    }
    @Test fun malformedParallelRequestKeepsHttpErrorAndDoesNotConsumeValidCallback() = runBlocking {
        LoopbackChatGptCallback(copy).use { listener ->
            val port = URI(listener.redirectUri).port
            val result = async(Dispatchers.IO) { listener.await("expected") }
            Socket("127.0.0.1", port).use { idle ->
                val malformed = withContext(Dispatchers.IO) { Socket("127.0.0.1", port).use { socket ->
                    socket.soTimeout = 5_000
                    socket.getOutputStream().apply { write("GET /auth/callback?state=%ZZ HTTP/1.1\r\nHost: 127.0.0.1:$port\r\n\r\n".toByteArray()); flush() }
                    socket.getInputStream().bufferedReader().readText()
                } }
                assertTrue(malformed.startsWith("HTTP/1.1 400")); assertFalse(result.isCompleted)
                assertTrue(withContext(Dispatchers.IO) { send(port) }.startsWith("HTTP/1.1 200"))
                assertEquals("fixture-code", result.await()["code"])
            }
        }
    }
    @Test fun cancelDuringMatchedResponseWriteCannotExchangeTokenOrDeliverLateAccount() = runBlocking {
        val fixture = AuthFixture()
        val reached = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val manager = ChatGptAuthManager(fixture.store, fixture.transport, fixture.now::get, copy, fixture.scope,
            callbackFactory = { words -> LoopbackChatGptCallback(words) { _, _, _ ->
                reached.countDown(); check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                throw IOException("synthetic cancelled browser")
            } })
        try {
            val query = AuthFixture.query(manager.beginSignIn())
            fixture.nonce = query.getValue("nonce")
            val awaiting = async(Dispatchers.IO) { runCatching { manager.awaitSignIn() } }
            val browser = async(Dispatchers.IO) { runCatching { AuthFixture.callback(query.getValue("redirect_uri"),
                mapOf("state" to query.getValue("state"), "code" to "fixture-code", "client_id" to fixture.client)) } }
            assertTrue(withContext(Dispatchers.IO) { reached.await(5, java.util.concurrent.TimeUnit.SECONDS) })
            manager.cancelSignIn(); release.countDown()
            assertTrue(withTimeout(2_000) { awaiting.await() }.isFailure)
            withTimeout(2_000) { browser.await() }
            assertEquals(0, fixture.tokenCalls.get())
            assertTrue(manager.state.value.accounts.isEmpty())
        } finally { release.countDown(); manager.cancelSignIn(); fixture.manager.cancelSignIn(); fixture.scope.cancel() }
    }

}
