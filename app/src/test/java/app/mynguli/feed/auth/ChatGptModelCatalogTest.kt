package app.mynguli.feed.auth

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ChatGptModelCatalogTest {
    @Test fun accountCatalogPreservesServerOrderAndSkipsHiddenOrInvalidEntries() {
        val result = ChatGptModelCatalog.parse(AuthHttpResult(200, """{"models":[
          {"visibility":"hide","slug":"hidden","display_name":"Hidden"},
          {"visibility":"list","slug":"model-b","display_name":"Model B","future_metadata":true},
          {"visibility":"list","slug":"invalid slug","display_name":"Invalid"},
          {"visibility":"list","slug":"model-a"},
          {"visibility":"list","slug":"model-b","display_name":"Duplicate"}
        ]}"""))
        assertEquals(listOf(ChatGptModel("model-b", "Model B"), ChatGptModel("model-a", "model-a")), result)
    }

    @Test fun publicModelsListUsesOnlyReturnedIdsAndNeverInventsAvailability() {
        val result = ChatGptModelCatalog.parse(AuthHttpResult(200, """{"object":"list","data":[
          {"object":"model","id":"response-model-2","created":1720000000,"owned_by":"openai"},
          {"object":"other","id":"not-a-model"},
          {"object":"model","id":"response-model-1"}
        ]}"""))
        assertEquals(listOf("response-model-2", "response-model-1"), result.map { it.slug })
        assertEquals("response-model-2", result.first().displayName)
    }

    @Test fun richCatalogMetadataCanExceedOldHalfMegabyteLimitWithinNewBound() {
        val body = buildJsonObject {
            putJsonArray("models") { add(buildJsonObject {
                put("slug", "fixture-model"); put("visibility", "list"); put("display_name", "Fixture")
                put("unneeded_metadata", "x".repeat(600 * 1024))
            }) }
        }.toString()
        assertEquals("fixture-model", ChatGptModelCatalog.parse(AuthHttpResult(200, body)).single().slug)
        assertFailure("chatgpt_models", " ".repeat(ChatGptModelCatalog.MAX_BYTES + 1))
    }

    @Test fun invalidEnvelopeDoesNotFallThroughOrBecomeIdentityFailure() {
        listOf("not JSON", "[]", "{}", """{"data":[]}""", """{"models":{},"object":"list","data":[{"object":"model","id":"fallback"}]}""")
            .forEach { assertFailure("chatgpt_models", it) }
        assertFailure("chatgpt_no_models", """{"models":[]}""")
        assertFailure("chatgpt_no_models", """{"models":[{"slug":"hidden","visibility":"hide"}]}""")
    }

    @Test fun diagnosticContainsOnlyStageStatusSafeRequestIdAndAllowlistedShape() {
        val detail = AuthHttpResult(503, """{"detail":"fixture-access-token and personal context"}""", "req_fixture_123").diagnostic("models")
        assertEquals(ChatGptDiagnostic("models", 503, "req_fixture_123", "detail"), detail)
        assertFalse(detail.toString().contains("fixture-access-token"))
        val injected = AuthHttpResult(400, """{"error":"private description"}""", "req_fixture\r\nAuthorization: secret").diagnostic("sign_in")
        assertNull(injected.requestId)
        assertEquals("error_string", injected.responseShape)
    }

    private fun assertFailure(code: String, body: String) {
        try { ChatGptModelCatalog.parse(AuthHttpResult(200, body)); fail("Expected catalog rejection") }
        catch (error: ChatGptAuthException) { assertEquals(code, error.code); assertEquals("models", error.diagnostic?.stage) }
    }
}
