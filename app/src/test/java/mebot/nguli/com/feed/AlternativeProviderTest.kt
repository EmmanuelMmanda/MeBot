package mebot.nguli.com.feed

import mebot.nguli.com.core.*
import mebot.nguli.com.feed.auth.OpenRouterSignIn
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AlternativeProviderTest {
    private fun obj(s:String)=FeedJson.parseToJsonElement(s).jsonObject
    private fun answer(o:JsonObject)=o["output"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
    private fun citations(o:JsonObject)=o["output"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray[0].jsonObject["annotations"]!!.jsonArray
    @Test fun claudeStreamsTextButNotHiddenThinkingAndRetainsActualCitations() {
        val fragments=mutableListOf<String>();val d=ProviderStreamDecoder("claude",fragments::add)
        d.event("""{"type":"content_block_delta","delta":{"type":"thinking_delta","thinking":"private reasoning"}}""")
        d.event("""{"type":"content_block_delta","delta":{"type":"text_delta","text":"Useful "}}""")
        d.event("""{"type":"content_block_delta","delta":{"type":"text_delta","text":"answer."}}""")
        d.event("""{"type":"content_block_delta","delta":{"type":"citations_delta","citation":{"type":"web_search_result_location","url":"https://example.com/source","title":"Source"}}}""")
        d.event("""{"type":"message_delta","delta":{"stop_reason":"end_turn"}}""");d.event("""{"type":"message_stop"}""")
        assertEquals(listOf("Useful ","Useful answer."),fragments)
        assertEquals("Useful answer.",answer(d.result()));assertEquals(1,citations(d.result()).size)
        assertFalse(d.result().toString().contains("private reasoning"))
    }
    @Test fun chatCompletionsRequireAnAcceptedFinishNotJustDoneAndNeverPromoteUrlsInProse() {
        val d=ProviderStreamDecoder("openrouter")
        d.event("""{"choices":[{"delta":{"content":"See https://invented.example/item"},"finish_reason":null}]}""")
        d.event("[DONE]")
        assertTrue(runCatching{d.result()}.exceptionOrNull() is FeedFailure)
        val complete=ProviderStreamDecoder("openrouter")
        complete.event("""{"choices":[{"delta":{"content":"See https://invented.example/item","annotations":[{"type":"url_citation","url_citation":{"url":"https://example.com/verified","title":"Actual source"}}]},"finish_reason":"stop"}]}""")
        assertEquals(1,citations(complete.result()).size)
        assertFalse(citations(complete.result()).toString().contains("invented.example"))
    }
    @Test fun geminiDoesNotLeakThoughtPartsAndRejectsBlockedOrTruncatedCandidates() {
        val d=ProviderStreamDecoder("gemini")
        d.event("""{"candidates":[{"content":{"parts":[{"thought":true,"text":"private"},{"text":"Hello"}]},"finishReason":"STOP"}]}""")
        assertEquals("Hello",answer(d.result()))
        for(reason in listOf("MAX_TOKENS","SAFETY","RECITATION")){
            val bad=ProviderStreamDecoder("gemini")
            assertTrue(runCatching{bad.event("""{"candidates":[{"content":{"parts":[{"text":"partial"}]},"finishReason":"$reason"}]}""")}.isFailure)
        }
    }
    @Test fun nativeClaudeWireUsesOnlyAnthropicHeadersAndOptionalSearch()=runBlocking {
        val wire=CaptureWire("""{"content":[{"type":"text","text":"A supported answer"}],"stop_reason":"end_turn"}""")
        val gateway=AlternativeAiGateway(wire)
        gateway.run(AiConnection(mode="claude",model="returned-model"),"Instructions","Input","fake-api-key",true,null)
        assertEquals("https://api.anthropic.com/v1/messages",wire.url)
        assertEquals("fake-api-key",wire.headers["x-api-key"]);assertFalse(wire.headers.containsKey("Authorization"))
        assertNotNull(wire.body!!["tools"])
        gateway.run(AiConnection(mode="claude",model="returned-model",webSearch=false),"Instructions","Input","fake-api-key",true,null)
        assertNull(wire.body!!["tools"])
        assertTrue(wire.body!!["system"]!!.jsonPrimitive.content.contains("No live web tool"))
    }
    @Test fun geminiAndLocalInferenceHaveExplicitSuppliedSourceCapabilities()=runBlocking {
        val g=CaptureWire("""{"candidates":[{"content":{"parts":[{"text":"From supplied sources"}]},"finishReason":"STOP"}]}""")
        AlternativeAiGateway(g).run(AiConnection(mode="gemini",model="returned-model"),"Instructions","Input","google-test-key",true,null)
        assertEquals("https://generativelanguage.googleapis.com/v1beta/models/returned-model:generateContent",g.url)
        assertEquals("google-test-key",g.headers["x-goog-api-key"]);assertNull(g.body!!["tools"])
        val local=CaptureWire("""{"choices":[{"message":{"content":"Local answer"},"finish_reason":"stop"}]}""")
        AlternativeAiGateway(local).run(AiConnection(mode="compatible",model="local-model:latest",serviceUrl="http://192.168.1.2:11434/v1",allowLocalHttp=true),"Instructions","Input","local-no-key",true,null)
        assertTrue(local.headers.isEmpty());assertNull(local.body!!["plugins"])
        assertEquals("http://192.168.1.2:11434/v1/chat/completions",local.url)
    }
    @Test fun openrouterSearchIsAnExplicitOptInAndStructuredOutputIsRequested()=runBlocking {
        val wire=CaptureWire("""{"choices":[{"message":{"content":"{\"items\":[]}"},"finish_reason":"stop"}]}""")
        AlternativeAiGateway(wire).run(AiConnection(mode="openrouter",model="provider/model"),"Instructions","Input","test-key",true,buildJsonObject{put("type","object")})
        assertEquals("Bearer test-key",wire.headers["Authorization"])
        assertNotNull(wire.body!!["plugins"])
        assertTrue(wire.body!!["messages"]!!.toString().contains("schema"))
    }
    @Test fun modelCatalogsUseProviderReturnedIdsAndFilterInvalidEntries()=runBlocking {
        val wire=CaptureWire("""{"models":[{"name":"models/returned-model","supportedGenerationMethods":["generateContent"]},{"name":"models/embedding","supportedGenerationMethods":["embedContent"]},{"name":"../escape"}]}""")
        val ids=AlternativeAiGateway(wire).models(AiConnection(mode="gemini",model="catalog"),"test-key")
        assertEquals(listOf("returned-model"),ids)
        assertTrue(wire.url.endsWith("/models"));assertNull(wire.body)
    }
    @Test fun incompleteAndProviderErrorsNeverBecomeSuccessfulAnswers() {
        for(mode in listOf("claude","gemini","openrouter","compatible")) {
            val d=ProviderStreamDecoder(mode)
            assertTrue(runCatching{d.result()}.isFailure)
            assertTrue(runCatching{d.event("""{"error":{"message":"failure"}}""")}.isFailure)
        }
    }
    @Test fun plaintextRequestsRejectCredentialsBeforeOpeningTheNetwork()=runBlocking {
        val c=AiConnection(mode="compatible",model="local",serviceUrl="http://192.168.1.2/v1",allowLocalHttp=true)
        val result=runCatching{AlternativeProviderHttp().exchange(c,c.serviceUrl+"/models",mapOf("Authorization" to "Bearer must-not-leave"),null)}
        assertEquals("invalid_input",(result.exceptionOrNull() as FeedFailure).code)
    }
    @Test fun openRouterPkceUsesOneTimeVerifierNotInBrowserUrl()=runBlocking {
        var at=1000L;val http=AuthHttp();val flow=OpenRouterSignIn(http){at}
        val url=flow.begin();assertTrue(url.startsWith("https://openrouter.ai/auth?"));assertTrue(url.contains("code_challenge_method=S256"));assertFalse(url.contains("code_verifier"))
        val key=flow.finish("one-time-authorization-code")
        assertEquals("test-generated-key",key)
        assertEquals("https://openrouter.ai/api/v1/auth/keys",http.url)
        assertEquals("S256",obj(http.body)["code_challenge_method"]!!.jsonPrimitive.content)
        assertFalse(url.contains(obj(http.body)["code_verifier"]!!.jsonPrimitive.content))
        assertTrue(runCatching{flow.finish("one-time-authorization-code")}.isFailure)
        flow.begin();at+=600_001;assertTrue(runCatching{flow.finish("expired-code-value")}.isFailure)
        flow.begin();flow.cancel();assertTrue(runCatching{flow.finish("cancelled-code-value")}.isFailure)
    }
    private class CaptureWire(var response:String):ProviderWire {
        var url="";var headers:Map<String,String> = emptyMap();var body:JsonObject?=null
        override suspend fun exchange(connection:AiConnection,url:String,headers:Map<String,String>,body:JsonObject?,event:((String)->Unit)?):String {
            this.url=url;this.headers=headers;this.body=body;return response
        }
    }
    private class AuthHttp:FeedHttp {
        var url="";var body=""
        override suspend fun request(url:String,method:String,body:String?,bearer:String?):String {
            this.url=url;this.body=body.orEmpty();assertEquals("POST",method);assertNull(bearer)
            return """{"key":"test-generated-key"}"""
        }
    }
}
