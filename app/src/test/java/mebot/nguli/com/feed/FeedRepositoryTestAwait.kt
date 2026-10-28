package mebot.nguli.com.feed

import mebot.nguli.com.core.FeedState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Collect DataStore state on an IO worker in Robolectric VM tests. These tests replace Main for
 * ViewModel work and use runBlocking for the test body; keeping persistence observation on IO
 * separates the DataStore collector from that test/Main event loop. Callers retain their original
 * timeout and exact persistence predicate.
 */
internal suspend fun FeedRepository.awaitFeedState(predicate: (FeedState) -> Boolean): FeedState =
    withContext(Dispatchers.IO) { state.first(predicate) }
