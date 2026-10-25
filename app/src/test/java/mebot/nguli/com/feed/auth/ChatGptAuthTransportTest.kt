package mebot.nguli.com.feed.auth

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ChatGptAuthTransportTest {
    @Test fun ownedDeadlineDisconnectsBlockedReadAndIsTemporaryFailure() = runBlocking {
        val input = WaitingInput()
        val connection = FixtureConnection(input)
        val transport = SecureChatGptAuthTransport({ connection }, requestTimeoutMillis = 100)
        val started = System.nanoTime()
        try { transport.request(ChatGptEndpoints.DISCOVERY); fail("Expected timeout") }
        catch (error: ChatGptAuthException) { assertEquals("chatgpt_unavailable", error.code) }
        assertTrue(connection.disconnected)
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 3000)
    }

    @Test fun callerCancellationDisconnectsRequestAndStaysCancellation() = runBlocking {
        val input = WaitingInput()
        val connection = FixtureConnection(input)
        val transport = SecureChatGptAuthTransport({ connection }, requestTimeoutMillis = 5000)
        val task = async { transport.request(ChatGptEndpoints.DISCOVERY) }
        input.started.await()
        task.cancel()
        try { task.await(); fail("Expected cancellation") }
        catch (_: CancellationException) { /* No misleading offline or identity error. */ }
        assertTrue(connection.disconnected)
    }

    @Test fun authenticatedRedirectIsNotFollowedAndFormIsEncoded() = runBlocking {
        val connection = FixtureConnection(ByteArrayInputStream(ByteArray(0)), status = 302)
        val transport = SecureChatGptAuthTransport({ connection })
        try {
            transport.request(ChatGptEndpoints.TOKEN, form = mapOf("client_id" to "opaque:+/=&", "code" to "a+b"))
            fail("Expected redirect rejection")
        } catch (error: ChatGptAuthException) { assertEquals("chatgpt_configuration", error.code) }
        assertFalse(connection.instanceFollowRedirects)
        assertEquals("client_id=opaque%3A%2B%2F%3D%26&code=a%2Bb", connection.written.toString("UTF-8"))
        assertTrue(connection.disconnected)
    }

    @Test fun credentialsCannotBeSentToAnotherHostOrPath() = runBlocking {
        var opened = false
        val transport = SecureChatGptAuthTransport({ opened = true; FixtureConnection(ByteArrayInputStream(ByteArray(0))) })
        val urls = listOf("https://attacker.example/v1/models", "http://api.openai.com/v1/models",
            "https://api.openai.com/v1/models?redirect=elsewhere", "https://api.openai.com/v1/responses",
            "https://auth.openai.com/.well-known/jwks.json")
        for (url in urls) {
            try { transport.request(url, bearer = "fixture-access"); fail("Expected destination rejection") }
            catch (error: ChatGptAuthException) { assertEquals("chatgpt_configuration", error.code) }
        }
        assertFalse(opened)
    }

    @Test fun oversizedResponseIsRejectedBeforeJsonParsing() = runBlocking {
        val connection = FixtureConnection(ByteArrayInputStream(ByteArray(512 * 1024 + 1) { 'x'.code.toByte() }))
        try {
            SecureChatGptAuthTransport({ connection }).request(ChatGptEndpoints.DISCOVERY)
            fail("Expected bounded response rejection")
        } catch (error: ChatGptAuthException) { assertEquals("chatgpt_invalid_response", error.code) }
        assertTrue(connection.disconnected)
    }

    @Test fun richModelCatalogHasItsOwnBoundAndSafeRequestId() = runBlocking {
        val content = "x".repeat(600 * 1024)
        val connection = FixtureConnection(ByteArrayInputStream(content.toByteArray()), requestId = "req_models_fixture")
        val response = SecureChatGptAuthTransport({ connection }).request(ChatGptEndpoints.MODELS, bearer = "fixture-access")
        assertEquals(content.length, response.body.length)
        assertEquals("req_models_fixture", response.requestId)
        assertTrue(connection.disconnected)
        val oversized = FixtureConnection(ByteArrayInputStream(ByteArray(ChatGptModelCatalog.MAX_BYTES + 1)), requestId = "req_large_fixture")
        try {
            SecureChatGptAuthTransport({ oversized }).request(ChatGptEndpoints.MODELS, bearer = "fixture-access")
            fail("Expected bounded catalog rejection")
        } catch (error: ChatGptAuthException) {
            assertEquals("chatgpt_models", error.code)
            assertEquals(ChatGptDiagnostic("models", 200, "req_large_fixture"), error.diagnostic)
        }
        assertTrue(oversized.disconnected)
    }

    private class WaitingInput : InputStream() {
        val started = CompletableDeferred<Unit>()
        private val closed = CountDownLatch(1)
        override fun read(): Int {
            started.complete(Unit)
            if (!closed.await(5, TimeUnit.SECONDS)) throw IOException("fixture timeout")
            throw IOException("fixture closed")
        }
        override fun close() { closed.countDown() }
    }
    private class FixtureConnection(private val input: InputStream, private val status: Int = 200, private val requestId: String? = null) :
        HttpURLConnection(URL(ChatGptEndpoints.DISCOVERY)) {
        @Volatile var disconnected = false
        val written = ByteArrayOutputStream()
        override fun connect() = Unit
        override fun usingProxy() = false
        override fun disconnect() { disconnected = true; input.close() }
        override fun getResponseCode() = status
        override fun getHeaderField(name: String?): String? = if (name.equals("x-request-id", true)) requestId else null
        override fun getInputStream() = input
        override fun getErrorStream() = input
        override fun getOutputStream() = written
    }
}
