package mebot.nguli.com.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import java.io.File
import android.os.Bundle
import android.os.Parcel
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import mebot.nguli.com.MainActivity
import mebot.nguli.com.R
import mebot.nguli.com.core.*
import mebot.nguli.com.feed.*
import mebot.nguli.com.MeBotApplication
import mebot.nguli.com.security.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Uses the real Activity result registry and platform launch path, not a mock onLaunch. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi", application = ExternalActivity093Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ExternalActivity093LifecycleTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val app get() = compose.activity.application as ExternalActivity093Application

    private fun ready(lockEnabled: Boolean = false, language: String = "en") {
        compose.waitForIdle()
        runBlocking {
            app.feedEngine.disconnect()
            app.repository.update { AppState(profile = Profile(onboardingComplete = true), settings = Settings(language = language)) }
            app.feedRepository.update { FeedState(onboardingComplete = true, quickGuideSeen = true,
                interestsConfigured = true, timezone = "UTC", lastRefreshMillis = System.currentTimeMillis(),
                context = listOf(ContextEntry(id = "original", text = "Keep this synthetic note", scopes = setOf("news"))),
                modules = listOf(FeedModule(id = "news", kind = "news"))) }
        }
        if (lockEnabled) {
            runBlocking { assertTrue(app.appLock.setup("718293".toCharArray())) }
            waitTag("app-lock-screen")
            runBlocking { assertTrue(app.appLock.unlock("718293".toCharArray())) }
            compose.waitForIdle()
        }
        waitTag("nav-you")
        compose.onNodeWithTag("nav-you").performClick()
    }

    private fun saveStopAndRecreate() {
        // Marshal the real Activity bundle: StateRestorationTester does not exercise Parcel/Binder types.
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.onActivity { activity ->
            val state = Bundle()
            InstrumentationRegistry.getInstrumentation().callActivityOnSaveInstanceState(activity, state)
            val parcel = Parcel.obtain()
            try {
                parcel.writeBundle(state)
                assertTrue("Saved Activity state unexpectedly exceeds Binder budget: ${parcel.dataSize()}", parcel.dataSize() < 512 * 1024)
                val bytes = parcel.marshall()
                assertTrue(bytes.isNotEmpty())
            } finally { parcel.recycle() }
        }
        compose.activityRule.scenario.recreate()
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitForIdle()
        if (app.appLock.state.value.enabled) {
            waitTag("app-lock-screen")
            compose.onNodeWithTag("context-import").assertDoesNotExist()
            compose.onNodeWithTag("background-notifications").assertDoesNotExist()
            runBlocking { assertTrue(app.appLock.unlock("718293".toCharArray())) }
            compose.waitForIdle()
        }
    }

    @Test fun chooseFileRealLaunchSurvivesSaveStopRecreationAndCancel() = picker(false)
    @Test fun lockedChooseFileRealLaunchSurvivesSaveStopRecreationAndCancel() = picker(true)
    private fun picker(lockEnabled: Boolean) {
        ready(lockEnabled)
        compose.onNodeWithText(compose.activity.getString(R.string.preferences_title_083)).performScrollTo().performClick()
        waitTag("context-region")
        compose.onNodeWithText(compose.activity.getString(R.string.context_import_title_092)).performScrollTo().performClick()
        waitTag("context-import")
        compose.onNodeWithTag("context-import").performScrollTo().performClick()
        val launch = shadowOf(compose.activity).nextStartedActivityForResult
        assertNotNull("Choose File must reach Android Activity launch", launch)
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, launch.intent.action)
        // OpenDocument sets */* plus EXTRA_MIME_TYPES; CATEGORY_OPENABLE belongs to GetContent.
        assertEquals("*/*", launch.intent.type)
        assertArrayEquals(arrayOf("application/json", "text/plain", "application/octet-stream"), launch.intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES))
        saveStopAndRecreate()
        compose.runOnUiThread { compose.activity.activityResultRegistry.dispatchResult(launch.requestCode, Activity.RESULT_CANCELED, null) }
        waitTag("context-import")
        compose.onNodeWithTag("context-import").performScrollTo().performClick()
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, shadowOf(compose.activity).nextStartedActivityForResult.intent.action)
        assertEquals("original", runBlocking { app.feedRepository.current() }.context.single().id)
    }

    @Test fun letMeKnowRealPermissionLaunchSurvivesSaveStopRecreationAndDenial() = permission(false)
    @Test @Config(qualifiers = "sw-rTZ-w411dp-h891dp-xhdpi")
    fun lockedLetMeKnowRealPermissionLaunchSurvivesSaveStopRecreationAndDenial() = permission(true, "sw")
    private fun permission(lockEnabled: Boolean, language: String = "en") {
        ready(lockEnabled, language)
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        compose.onNodeWithText(compose.activity.getString(R.string.feed_delivery)).performScrollTo().performClick()
        waitTag("delivery-enabled")
        compose.onNodeWithTag("delivery-enabled").performScrollTo().performClick()
        compose.onNodeWithTag("background-notifications").performScrollTo().performClick()
        val request = shadowOf(compose.activity).lastRequestedPermission
        assertNotNull("Let me know must reach Android permission request", request)
        assertArrayEquals(arrayOf(Manifest.permission.POST_NOTIFICATIONS), request.requestedPermissions)
        saveStopAndRecreate()
        compose.runOnUiThread { dispatchPermissionResult(request.requestCode, PackageManager.PERMISSION_DENIED) }
        waitTag("background-notifications")
        compose.onNodeWithTag("delivery-enabled").performScrollTo().assertIsOn()
        compose.onNodeWithTag("background-notifications").performScrollTo().assertIsEnabled().assertIsOff()
        compose.onNodeWithText(compose.activity.getString(R.string.feed_permission_denied)).assertExists()
        assertFalse(runBlocking { app.feedRepository.current() }.notifications)
        // Denial is retryable; a subsequent granted result changes only the draft until Save.
        val reportedActivity = compose.activity
        compose.activityRule.scenario.onActivity { assertSame("Compose must reference the current recreated Activity", it, reportedActivity) }
        val priorRequest = shadowOf(compose.activity).lastRequestedPermission
        compose.onNodeWithTag("background-notifications").performScrollTo().assertIsEnabled().performClick()
        val retried = shadowOf(compose.activity).lastRequestedPermission
        assertNotNull("Retry must issue a platform permission request", retried)
        assertNotSame("Retry must not reuse a stale recorded request", priorRequest, retried)
        assertArrayEquals(arrayOf(Manifest.permission.POST_NOTIFICATIONS), retried.requestedPermissions)
        compose.runOnUiThread {
            shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
            dispatchPermissionResult(retried.requestCode, PackageManager.PERMISSION_GRANTED)
        }
        compose.onNodeWithTag("background-notifications").assertIsOn()
        capture("delivery-$language")
        assertFalse(runBlocking { app.feedRepository.current() }.notifications)
        compose.onNodeWithTag("delivery-save").performScrollTo().performClick()
        compose.waitUntil(10_000) { runBlocking { app.feedRepository.current() }.notifications }
    }

    @Test fun exportRealLaunchSurvivesSaveStopRecreationAndCancel() = export(false)
    @Test fun lockedExportRealLaunchSurvivesSaveStopRecreationAndCancel() = export(true)
    private fun export(lockEnabled: Boolean) {
        ready(lockEnabled)
        compose.onNodeWithText(compose.activity.getString(R.string.preferences_title_083)).performScrollTo().performClick()
        waitTag("context-export")
        compose.onNodeWithTag("context-export").performScrollTo().performClick()
        val launch = shadowOf(compose.activity).nextStartedActivityForResult
        assertNotNull("Export must reach Android Activity launch", launch)
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, launch.intent.action)
        assertEquals("application/json", launch.intent.type)
        assertTrue(launch.intent.getStringExtra(Intent.EXTRA_TITLE)!!.matches(Regex("mebot-context-\\d{4}-\\d{2}-\\d{2}\\.json")))
        saveStopAndRecreate()
        compose.runOnUiThread { compose.activity.activityResultRegistry.dispatchResult(launch.requestCode, Activity.RESULT_CANCELED, null) }
        waitTag("context-export")
        compose.onNodeWithTag("context-export").performScrollTo().performClick()
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, shadowOf(compose.activity).nextStartedActivityForResult.intent.action)
        assertEquals("original", runBlocking { app.feedRepository.current() }.context.single().id)
    }

    @Test fun textAttachmentRealLaunchSurvivesSaveStopRecreationAndCancelWithoutCreatingThread() {
        ready()
        compose.onNodeWithTag("nav-ai").performClick()
        waitTag("workspace-attach")
        compose.onNodeWithTag("workspace-attach").performClick()
        val launch = shadowOf(compose.activity).nextStartedActivityForResult
        assertNotNull("Attachment must reach Android Activity launch", launch)
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, launch.intent.action)
        assertArrayEquals(arrayOf("text/plain", "text/markdown", "text/x-markdown"), launch.intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES))
        saveStopAndRecreate()
        compose.runOnUiThread { compose.activity.activityResultRegistry.dispatchResult(launch.requestCode, Activity.RESULT_CANCELED, null) }
        waitTag("workspace-attach")
        compose.onNodeWithTag("workspace-attach").performClick()
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, shadowOf(compose.activity).nextStartedActivityForResult.intent.action)
        assertTrue(runBlocking { app.feedRepository.current() }.conversations.isEmpty())
    }

    private fun capture(name: String) {
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
        val window = compose.activity.window
        val view = window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        var status = -1
        compose.runOnIdle { PixelCopy.request(window, bitmap, { status = it }, Handler(Looper.getMainLooper())) }
        compose.waitUntil(10_000) { status != -1 }
        assertEquals(PixelCopy.SUCCESS, status)
        File("build/outputs/screenshots/v093/$name.png").also { it.parentFile.mkdirs() }
            .outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }

    private fun dispatchPermissionResult(requestCode: Int, result: Int) {
        // Android clears its in-flight permission flag BEFORE invoking the public callback.
        // Calling onRequestPermissionsResult directly skips that step, causing the next request
        // to auto-cancel as overlapping even though the test already simulated a denial.
        val pending = Activity::class.java.getDeclaredField("mHasCurrentPermissionsRequest").apply { isAccessible = true }
        assertTrue("A real platform permission request must be pending", pending.getBoolean(compose.activity))
        val data = Intent()
            .putExtra("android.content.pm.extra.REQUEST_PERMISSIONS_NAMES", arrayOf(Manifest.permission.POST_NOTIFICATIONS))
            .putExtra("android.content.pm.extra.REQUEST_PERMISSIONS_RESULTS", intArrayOf(result))
        Activity::class.java.getDeclaredMethod("dispatchRequestPermissionsResult", Int::class.javaPrimitiveType, Intent::class.java)
            .apply { isAccessible = true }.invoke(compose.activity, requestCode, data)
        assertFalse("Framework dispatch must clear pending permission state", pending.getBoolean(compose.activity))
    }

    private fun waitTag(tag: String) {
        compose.waitUntil(15_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    }
}

/** Synthetic in-memory lock custody and offline transports only; never packaged in the app. */
class ExternalActivity093Application : MeBotApplication() {
    override fun onCreate() { super.onCreate(); androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(this) }
    override val appLock by lazy {
        val storage = object : LockStorage {
            private var record: LockRecord? = null
            override fun enabled() = record != null
            override fun read() = record
            override fun write(record: LockRecord) { this.record = record }
            override fun disable() { record = null }
        }
        AppLockController(storage, { 100_000L }, { 1 }, { false })
    }
    override val feedSecrets by lazy { TestVault(this) }
    override val feedEngine by lazy {
        FeedEngine(feedRepository, feedSecrets, object : SourceReader {
            override suspend fun read(source: FeedSource, module: FeedModule, now: Long) = emptyList<FeedItem>()
        }, object : AiProvider {
            override suspend fun check(connection: AiConnection, secret: String) = Unit
            override suspend fun feed(state: FeedState, module: FeedModule, secret: String, language: String) = emptyList<FeedItem>()
            override suspend fun ask(state: FeedState, item: FeedItem?, question: String, secret: String, language: String) = AiAnswer("Synthetic answer", emptyList())
        }, language = { "en" })
    }
}
