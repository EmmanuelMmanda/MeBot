package mebot.nguli.com.core

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

/** Max legal conversation-text diagnostic. No disk or phone latency claim. */
object StorageBenchmark {
    @Volatile private var sink=0
    @JvmStatic fun main(args:Array<String>) {
        val now=1_761_350_400_000L
        var remaining=800_000
        val threads=(0 until 24).map { thread ->
            val turns=(0 until 60).mapNotNull { turn ->
                if(remaining==0) null else {
                    val size=minOf(remaining,if(thread==0) 24_000 else 1000)
                    remaining-=size
                    AiTurn(id="turn-$turn",role="assistant",text="x".repeat(size),createdAtMillis=now)
                }
            }
            AiConversation(id="thread-$thread",turns=turns,createdAtMillis=now,updatedAtMillis=now)
        }
        check(remaining==0)
        val state=FeedState(conversations=threads)
        FeedLogic.validate(state)
        val json=Json
        val fixture=json.encodeToString(state)
        val hash=MessageDigest.getInstance("SHA-256").digest(fixture.toByteArray()).joinToString("") { "%02x".format(it) }
        File(args[0]).printWriter().use { out ->
            out.println("fork,scenario,fixture_sha256,iteration,duration_ns,outcome,checksum")
            for(scenario in listOf("validate-800k","serialize-800k")) {
                repeat(100) { if(scenario.startsWith("validate")) sink=FeedLogic.validate(state).conversations.size else sink=json.encodeToString(state).length }
                repeat(400) { iteration ->
                    val start=System.nanoTime()
                    if(scenario.startsWith("validate")) sink=FeedLogic.validate(state).conversations.size else sink=json.encodeToString(state).length
                    val duration=System.nanoTime()-start
                    out.println("${args[1]},$scenario,$hash,$iteration,$duration,success,$sink")
                }
            }
        }
    }
}
