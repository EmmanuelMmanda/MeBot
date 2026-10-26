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
import mebot.nguli.com.MeBotViewModel
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
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=V06FlowTestApplication::class)
class LocalBackupReset092Test {
    private lateinit var app:V06FlowTestApplication
    private lateinit var vm:MeBotViewModel
    private val models=ViewModelStore()
    @Before fun setup():Unit=runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        app=ApplicationProvider.getApplicationContext()
        app.repository.update { AppState(profile=Profile(name="Original")) }
        vm=ViewModelProvider(models,ViewModelProvider.AndroidViewModelFactory(app))[MeBotViewModel::class.java]
        withTimeout(5000) { vm.ui.first { it.state!=null } }
    }
    @After fun close() { models.clear();app.alarms.clearAll();Dispatchers.resetMain() }

    private suspend fun blockedOldImport(duringRead:suspend ()->Unit) {
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val uri=Uri.parse("content://backup.fixture/old.json")
        val text=BackupCodec.encode(AppState(profile=Profile(name="Old backup must not return")))
        shadowOf(app.contentResolver).registerInputStream(uri,object:ByteArrayInputStream(text.toByteArray()) {
            override fun read(bytes:ByteArray,off:Int,len:Int):Int {
                entered.countDown();check(release.await(5,TimeUnit.SECONDS));return super.read(bytes,off,len)
            }
        })
        val job=vm.importBackup(uri)
        try { assertTrue(withContext(Dispatchers.IO) { entered.await(5,TimeUnit.SECONDS) });duringRead() }
        finally { release.countDown() }
        withTimeout(5000) { job.join() }
    }
    @Test fun eraseDuringProviderReadWinsOverOlderLocalBackup():Unit=runBlocking {
        var erased:AppState?=null
        blockedOldImport {
            vm.erase()
            erased=withTimeout(5000) { app.repository.state.first { it.profile.name.isEmpty() } }
        }
        assertEquals(erased,app.repository.current())
    }
    @Test fun newerImportWinsOverEarlierSlowProviderRead():Unit=runBlocking {
        blockedOldImport {
            val file=File(app.cacheDir,"new-backup.json").apply {
                writeText(BackupCodec.encode(AppState(profile=Profile(name="Latest backup"))))
            }
            vm.importBackup(Uri.fromFile(file)).join()
            assertEquals("Latest backup",app.repository.current().profile.name)
        }
        assertEquals("Latest backup",app.repository.current().profile.name)
    }
}
