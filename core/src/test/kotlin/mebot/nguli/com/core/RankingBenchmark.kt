package mebot.nguli.com.core

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

/** Standalone real Kotlin host harness. Not Android latency. Synthetic data only. */
object RankingBenchmark {
    private var sink = 0L
    @JvmStatic fun main(args: Array<String>) {
        val output = File(args[0]); output.parentFile.mkdirs()
        val now = 1_761_350_400_000L
        output.printWriter().use { out ->
            out.println("fork,scenario,fixture_sha256,iteration,duration_ns,outcome,checksum")
            for (size in listOf(5, 50, 250)) for (dense in listOf(false, true)) {
                val modules = listOf("news", "technology", "business", "learning", "security").map { FeedModule(id=it,kind=it,query="software business biashara learning") }
                val items = (0 until size).map { i -> FeedItem(id="item-$i",moduleId=modules[i%5].id,kind=modules[i%5].kind,title="Software biashara learning opportunity $i",summary="Useful cloud security biashara and software guide ${i%17}",sourceUrl="https://source${i%7}.example/story/$i",publishedAtMillis=now-i*3600000L,fetchedAtMillis=now,relevance=40+i%61) }
                val state = FeedState(timezone="Africa/Dar_es_Salaam", modules=modules,items=items,
                    context=if(dense) (0 until 80).map { ContextEntry(id="note-$it",text="Software biashara learning cloud security ${it%9}",scopes=setOf(modules[it%5].kind),updatedAtMillis=now) } else emptyList(),
                    relevanceSignals=if(dense) (0 until 500).map { RelevanceSignal("vote-$it",modules[it%5].id,it%3!=0,"Synthetic",setOf("software","biashara","security"),now-it*86400000L) } else emptyList(),
                    savedIds=items.filterIndexed { i,_->i%7==0 }.map { it.id }.toSet(),hiddenIds=items.filterIndexed { i,_->i%11==0 }.map { it.id }.toSet())
                FeedLogic.validate(state)
                val hash=MessageDigest.getInstance("SHA-256").digest(Json.encodeToString(state).toByteArray()).joinToString("") { "%02x".format(it) }
                repeat(100) { sink += FeedLogic.edition(state,now).size }
                repeat(400) { iteration ->
                    val start=System.nanoTime(); val result=FeedLogic.edition(state,now); val duration=System.nanoTime()-start
                    sink += result.sumOf { it.id.hashCode().toLong() }
                    out.println("${args[1]},$size-${if(dense) "dense" else "sparse"},$hash,$iteration,$duration,success,$sink")
                }
                out.flush()
            }
        }
    }
}
