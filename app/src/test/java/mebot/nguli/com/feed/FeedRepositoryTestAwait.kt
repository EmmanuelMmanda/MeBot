package mebot.nguli.com.feed

import android.app.Application
import mebot.nguli.com.core.FeedState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.TestDispatcher
import java.lang.reflect.Field
import java.util.concurrent.atomic.AtomicLong

/** Test-only trace for the real DataStore state waiter; never logs conversation text or credentials. */
internal class FeedRepositoryObservationTrace(
    private val label: String,
    private val application: Application,
    private val viewModel: FeedViewModel,
    private val repository: FeedRepository,
) {
    private val startedAtNanos = System.nanoTime()
    private val nextEvent = AtomicLong()
    private val events = ArrayList<String>()

    fun mark(phase: String) = note("phase=$phase")

    fun noteCurrent(phase: String, current: FeedState?) =
        note("phase=$phase current=${current?.let(::stateSummary) ?: "unavailable"}")

    fun emission(state: FeedState) = note("waiter-emission#${nextEvent.incrementAndGet()} ${stateSummary(state)}")

    fun summary(): String = synchronized(events) { events.joinToString("\n") }

    fun printSummary() {
        println("[FeedRepositoryObservationTrace:$label]\n${summary()}")
    }

    fun stateSummary(state: FeedState): String {
        val conversations = state.conversations.take(8).joinToString(",") { thread ->
            "${thread.id.take(80)}:${thread.turns.size}:${thread.turns.lastOrNull()?.role ?: "none"}"
        }
        val dailyIds = state.personalDailySelection098?.itemIds?.take(8)?.joinToString(",") { it.take(80) } ?: "none"
        return "requests=${state.requestsToday} edition=${state.editionSequence} items=${state.items.size} " +
            "conversationCount=${state.conversations.size} conversations=[$conversations] daily=[$dailyIds]"
    }

    private fun note(event: String) {
        val line = "+${(System.nanoTime() - startedAtNanos) / 1_000_000}ms " +
            "thread=${Thread.currentThread().name} $event ${identitySnapshot()}"
        synchronized(events) {
            if (events.size >= 64) events.removeAt(0)
            events.add(line.take(1800))
        }
    }

    private fun identitySnapshot(): String {
        val vmRepository = runCatching { field(viewModel, "repository") }.getOrNull()
        val store = runCatching { field(repository, "store") }.getOrNull()
        val storeScope = store?.let { runCatching { field(it, "scope") }.getOrNull() as? kotlinx.coroutines.CoroutineScope }
        val scopeJob = storeScope?.coroutineContext?.get(Job)
        val dispatcher = storeScope?.coroutineContext?.get(kotlin.coroutines.ContinuationInterceptor)
        val collectorCount = store?.let { runCatching { field(it, "collectorCounter") }.getOrNull() }
        val collectorJob = store?.let { runCatching { field(it, "collectorJob") }.getOrNull() as? Job }
        val cache = store?.let { runCatching { field(it, "inMemoryCache") }.getOrNull() }
        val cachedState = cache?.let { runCatching { it.javaClass.getMethod("getCurrentState").invoke(it) }.getOrNull() }
        val version = cachedState?.let { runCatching { it.javaClass.getMethod("getVersion").invoke(it) }.getOrNull() }
        val cachedValue = cachedState?.let { runCatching { it.javaClass.getMethod("getValue").invoke(it) as? FeedState }.getOrNull() }
        val main = runCatching { Dispatchers.Main }.getOrNull()
        val scheduler = (main as? TestDispatcher)?.scheduler
        return "appId=${identity(application)} repoId=${identity(repository)} vmRepoId=${identity(vmRepository)} " +
            "storeId=${identity(store)} storeClass=${store?.javaClass?.name ?: "unavailable"} " +
            "storeScopeId=${identity(storeScope)} storeDispatcher=${dispatcher?.javaClass?.name ?: "unavailable"} " +
            "storeJobId=${identity(scopeJob)} storeJobActive=${scopeJob?.isActive} " +
            "collectors=$collectorCount collectorJobId=${identity(collectorJob)} collectorJobActive=${collectorJob?.isActive} " +
            "cacheVersion=$version cacheState=${cachedValue?.let(::stateSummary) ?: "unavailable"} " +
            "mainId=${identity(main)} mainClass=${main?.javaClass?.name ?: "unavailable"} testSchedulerId=${identity(scheduler)} trace=$label"
    }

    private fun field(instance: Any, name: String): Any? = findField(instance.javaClass, name).let { property ->
        property.isAccessible = true
        property.get(instance)
    }

    private fun identity(instance: Any?): String = instance?.let { "${it.javaClass.simpleName}@${System.identityHashCode(it).toString(16)}" } ?: "null"

    companion object {
        private fun findField(type: Class<*>, name: String): Field {
            var current: Class<*>? = type
            while (current != null) {
                runCatching { current.getDeclaredField(name) }.getOrNull()?.let { return it }
                current = current.superclass
            }
            error("Field $name not found on ${type.name}")
        }
    }
}

/**
 * Test-only raw DataStore observation. The `first(predicate)` condition stays the pass criterion;
 * the optional trace only records that same collector's lifecycle/emissions.
 */
internal suspend fun FeedRepository.awaitFeedState(
    trace: FeedRepositoryObservationTrace? = null,
    predicate: (FeedState) -> Boolean,
): FeedState = withContext(Dispatchers.IO) {
    val flow = if (trace == null) state else state
        .onStart { trace.mark("waiter-subscribe rawFlow=FeedRepository.state(store.data)") }
        .onEach(trace::emission)
        .onCompletion { cause -> trace.mark("waiter-complete cause=${cause?.javaClass?.simpleName ?: "success"}") }
    flow.first(predicate)
}
