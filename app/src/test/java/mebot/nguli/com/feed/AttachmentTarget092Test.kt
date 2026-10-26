package mebot.nguli.com.feed

import android.net.Uri
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import mebot.nguli.com.core.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=V06FlowTestApplication::class)
class AttachmentTarget092Test {
    private lateinit var app:V06FlowTestApplication
    private lateinit var vm:FeedViewModel
    private val models=ViewModelStore()
    private val first=AiConversation(id="first",createdAtMillis=1)
    private val second=AiConversation(id="second",createdAtMillis=1)
    @Before fun setup():Unit=runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        app=ApplicationProvider.getApplicationContext()
        app.feedRepository.update { FeedState(personalSeedApplied=true,conversations=listOf(first,second)) }
        vm=ViewModelProvider(models,ViewModelProvider.AndroidViewModelFactory(app))[FeedViewModel::class.java]
        withTimeout(5000) { vm.ui.first { !it.loading } }
    }
    @After fun close() { models.clear();Dispatchers.resetMain() }

    private suspend fun delayedImport(expected:String?, switch: suspend ()->Unit) {
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val uri=Uri.parse("content://attachment.fixture/notes.md")
        shadowOf(app.contentResolver).registerInputStream(uri,object:ByteArrayInputStream("Synthetic attachment".toByteArray()) {
            override fun read(bytes:ByteArray,off:Int,len:Int):Int {
                entered.countDown();check(release.await(5,TimeUnit.SECONDS));return super.read(bytes,off,len)
            }
        })
        vm.importTextAttachment(uri,expected)
        try {
            assertTrue(withContext(Dispatchers.IO) { entered.await(5,TimeUnit.SECONDS) })
            switch()
        } finally { release.countDown() }
        withTimeout(5000) { vm.ui.first { !it.importWorking } }
    }
    @Test fun switchingThreadsDuringProviderReadNeverAttachesToNewThread():Unit=runBlocking {
        vm.selectConversation(first.id).join()
        delayedImport(first.id) { vm.selectConversation(second.id).join() }
        assertEquals(listOf(first,second),app.feedRepository.current().conversations)
        assertEquals(second.id,vm.ui.value.activeConversationId)
    }
    @Test fun nullNewThreadTargetCannotCreateThreadAfterSelectionChanges():Unit=runBlocking {
        assertNull(vm.ui.value.activeConversationId)
        delayedImport(null) { vm.selectConversation(second.id).join() }
        assertEquals(listOf(first,second),app.feedRepository.current().conversations)
    }
    @Test fun deletedTargetIsNotRecreatedAfterProviderRead():Unit=runBlocking {
        vm.selectConversation(first.id).join()
        delayedImport(first.id) { vm.deleteConversation(first.id).join() }
        assertEquals(listOf(second),app.feedRepository.current().conversations)
    }
    @Test fun unchangedTargetReceivesAttachmentAndOtherThreadIsUntouched():Unit=runBlocking {
        vm.selectConversation(first.id).join()
        delayedImport(first.id) { }
        val saved=app.feedRepository.current().conversations
        assertEquals("Synthetic attachment",saved.first { it.id==first.id }.attachments.single().text)
        assertEquals(second,saved.first { it.id==second.id })
    }
    @Test fun unchangedNullTargetCreatesExactlyOneThreadWithAttachment():Unit=runBlocking {
        delayedImport(null) { }
        val saved=app.feedRepository.current().conversations
        assertEquals(3,saved.size)
        assertEquals(1,saved.single { it.id==vm.ui.value.activeConversationId }.attachments.size)
    }
}
