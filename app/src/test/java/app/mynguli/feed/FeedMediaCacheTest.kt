package app.mynguli.feed

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FeedMediaCacheTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var context: Context
    private lateinit var cache: File
    private lateinit var image: ByteArray
    private val url = "https://publisher.example/media/story.png"
    private var now = 1_792_742_400_000L

    @Before fun setUp() {
        cache = temporary.newFolder("cache")
        val app = ApplicationProvider.getApplicationContext<Context>()
        context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getCacheDir(): File = cache
        }
        val bitmap = Bitmap.createBitmap(160, 90, Bitmap.Config.ARGB_8888)
        image = ByteArrayOutputStream().use { output ->
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            output.toByteArray()
        }
        bitmap.recycle()
    }

    @Test fun concurrentSameImageLoadsShareOneFetchAndDecodedBitmap() = runBlocking {
        val calls = AtomicInteger()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val repository = FeedMediaRepository(context, fetcher = {
            calls.incrementAndGet()
            entered.complete(Unit)
            release.await()
            image
        }, clock = { now })
        val requests = (1..10).map { async(start = CoroutineStart.UNDISPATCHED) { repository.load(url) } }
        withTimeout(5_000) { entered.await() }
        release.complete(Unit)
        val results = withTimeout(10_000) { requests.awaitAll() }
        assertEquals(1, calls.get())
        assertNotNull(results.first())
        results.forEach { assertSame(results.first(), it) }
        assertSame(results.first(), repository.load(url))
        assertEquals(1, calls.get())
    }

    @Test fun aSharedFailureHasOneCooldownThenCanRecover() = runBlocking {
        val calls = AtomicInteger()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val repository = FeedMediaRepository(context, fetcher = {
            if (calls.incrementAndGet() == 1) {
                entered.complete(Unit)
                release.await()
                null
            } else image
        }, clock = { now })
        val requests = (1..8).map { async(start = CoroutineStart.UNDISPATCHED) { repository.load(url) } }
        withTimeout(5_000) { entered.await() }
        release.complete(Unit)
        assertTrue(withTimeout(10_000) { requests.awaitAll() }.all { it == null })
        assertEquals(1, calls.get())
        now += 4 * 60_000
        assertNull(repository.load(url))
        assertEquals(1, calls.get())
        now += 60_001
        assertNotNull(repository.load(url))
        assertEquals(2, calls.get())
    }

    @Test fun cancelledLeaderAndWaiterDoNotPoisonTheImageForTheRemainingCaller() = runBlocking {
        val calls = AtomicInteger()
        val entered = CompletableDeferred<Unit>()
        val repository = FeedMediaRepository(context, fetcher = {
            if (calls.incrementAndGet() == 1) { entered.complete(Unit); awaitCancellation() }
            image
        }, clock = { now })
        val leader = async(start = CoroutineStart.UNDISPATCHED) { repository.load(url) }
        withTimeout(5_000) { entered.await() }
        val cancelledWaiter = async(start = CoroutineStart.UNDISPATCHED) { repository.load(url) }
        cancelledWaiter.cancelAndJoin()
        val follower = async(start = CoroutineStart.UNDISPATCHED) { repository.load(url) }
        leader.cancelAndJoin()
        assertNotNull(withTimeout(10_000) { follower.await() })
        assertEquals(2, calls.get())
        assertNotNull(repository.load(url))
        assertEquals(2, calls.get())
    }

    @Test fun unrelatedImagesCanDownloadAtTheSameTime() = runBlocking {
        val entered = AtomicInteger()
        val bothEntered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val repository = FeedMediaRepository(context, fetcher = {
            if (entered.incrementAndGet() == 2) bothEntered.complete(Unit)
            release.await()
            image
        }, clock = { now })
        val first = async { repository.load(url) }
        val second = async { repository.load("https://publisher.example/media/another.png") }
        withTimeout(5_000) { bothEntered.await() }
        release.complete(Unit)
        assertNotNull(first.await())
        assertNotNull(second.await())
        assertEquals(2, entered.get())
    }

    @Test fun corruptCachedImageIsReplacedDuringTheSameLoadAndExpiredImagesAreRefetched() = runBlocking {
        val original = FeedMediaRepository(context, fetcher = { image }, clock = { now })
        assertNotNull(original.load(url))
        val stored = File(cache, "feed-media-v1").listFiles()!!.single { !it.name.endsWith(".part") }
        stored.writeText("Damaged cached bytes")
        assertTrue(stored.setLastModified(now))
        val repairs = AtomicInteger()
        val repaired = FeedMediaRepository(context, fetcher = { repairs.incrementAndGet(); image }, clock = { now })
        assertNotNull(repaired.load(url))
        assertEquals(1, repairs.get())
        assertArrayEquals(image, stored.readBytes())
        now += 7 * 24 * 60 * 60_000L + 1
        val refreshes = AtomicInteger()
        val reopened = FeedMediaRepository(context, fetcher = { refreshes.incrementAndGet(); image }, clock = { now })
        assertNotNull(reopened.load(url))
        assertEquals(1, refreshes.get())
        assertEquals(now, stored.lastModified())
    }

    @Test fun failedDiskWriteCleansItsPendingPathAndKeepsTheValidImageInMemory() = runBlocking {
        val directory = File(cache, "feed-media-v1").apply { mkdirs() }
        val key = MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
        val pending = File(directory, "$key.part")
        // An existing directory makes opening the pending output stream fail on every platform.
        assertTrue(pending.mkdir())
        val calls = AtomicInteger()
        val repository = FeedMediaRepository(context, fetcher = { calls.incrementAndGet(); image }, clock = { now })
        val loaded = repository.load(url)
        assertNotNull(loaded)
        assertFalse(pending.exists())
        assertSame(loaded, repository.load(url))
        assertEquals(1, calls.get())
    }

    @Test fun unsafeUrlsAndOversizedPayloadsNeverEnterTheImageCache() = runBlocking {
        val calls = AtomicInteger()
        val repository = FeedMediaRepository(context, fetcher = { calls.incrementAndGet(); ByteArray(4 * 1024 * 1024 + 1) }, clock = { now })
        assertNull(repository.load("https://127.0.0.1/private.png"))
        assertNull(repository.load("https://user:secret@publisher.example/private.png"))
        assertEquals(0, calls.get())
        assertNull(repository.load(url))
        assertEquals(1, calls.get())
        assertTrue(File(cache, "feed-media-v1").listFiles().orEmpty().isEmpty())
    }
}
