package app.mynguli.feed.auth

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import app.mynguli.feed.FeedTestApplication
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = FeedTestApplication::class)
class ChatGptBrowserReturnTest {
    private val context get() = ApplicationProvider.getApplicationContext<FeedTestApplication>()

    @Test fun browserSanitizedIntentResolvesThroughActualManifestWithoutExplicitComponent() {
        val intent = Intent.parseUri(ChatGptReturnLink.browserUri(context.packageName), Intent.URI_INTENT_SCHEME)
            .addCategory(Intent.CATEGORY_BROWSABLE).setComponent(null).apply { selector = null }
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(context.packageName, intent.`package`)
        assertNull(intent.component)
        assertTrue(ChatGptReturnLink.matches(intent, context.packageName))
        val handlers = context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
        assertTrue("BROWSABLE return intent must match the installed manifest", handlers.any {
            it.activityInfo.packageName == context.packageName && it.activityInfo.name == "app.mynguli.MainActivity"
        })
    }

    @Test fun wrongSchemeHostPathOrUntrustedOauthValuesCannotBecomeARecognizedReturn() {
        val scheme = "${context.packageName}.connect"
        val bad = listOf("other.app.connect://chatgpt/return", "$scheme://wrong/return", "$scheme://chatgpt/other",
            "$scheme://chatgpt/return?code=untrusted", "$scheme://chatgpt/return#access_token=untrusted",
            "$scheme://user@chatgpt/return", "$scheme://chatgpt:123/return")
        for (uri in bad) assertFalse(uri, ChatGptReturnLink.matches(Intent(Intent.ACTION_VIEW, Uri.parse(uri)), context.packageName))
        assertFalse(ChatGptReturnLink.matches(Intent(Intent.ACTION_SEND, Uri.parse("$scheme://chatgpt/return")), context.packageName))
        // A forged extra has no part in matching and is never consumed as an authorization result.
        assertTrue(ChatGptReturnLink.matches(Intent(Intent.ACTION_VIEW, Uri.parse("$scheme://chatgpt/return"))
            .putExtra("access_token", "untrusted"), context.packageName))
    }

    @Test fun releaseAndDebugUseDistinctInstalledSchemesAndLegacyReturnIsNavigationOnly() {
        assertTrue(ChatGptReturnLink.browserUri("app.mynguli").contains("scheme=app.mynguli.connect;"))
        assertTrue(ChatGptReturnLink.browserUri("app.mynguli.debug").contains("scheme=app.mynguli.debug.connect;"))
        assertTrue(ChatGptReturnLink.matches(Intent(ChatGptReturnLink.LEGACY_ACTION), context.packageName))
        assertFalse(ChatGptReturnLink.matches(Intent(ChatGptReturnLink.LEGACY_ACTION, Uri.parse("https://example.com?code=untrusted")), context.packageName))
    }
}
