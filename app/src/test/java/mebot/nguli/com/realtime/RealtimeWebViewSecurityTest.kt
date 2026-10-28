package mebot.nguli.com.realtime

import android.net.Uri
import android.webkit.PermissionRequest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RealtimeWebViewSecurityTest {
    private val trustedOrigin = Uri.parse(RealtimeWebViewSecurityPolicy099.TRUSTED_ORIGIN)

    @Test fun onlyExactPackagedAssetsAndDocumentNavigationAreTrusted() {
        val root = RealtimeWebViewSecurityPolicy099.TRUSTED_ORIGIN
        assertTrue(RealtimeWebViewSecurityPolicy099.isAllowedAssetRequest(
            Uri.parse("$root/assets/realtime/index.html")))
        assertTrue(RealtimeWebViewSecurityPolicy099.isAllowedAssetRequest(
            Uri.parse("$root/assets/realtime/rtc.js")))
        assertFalse(RealtimeWebViewSecurityPolicy099.isAllowedAssetRequest(
            Uri.parse("$root/assets/realtime/index.html?remote=1")))
        assertFalse(RealtimeWebViewSecurityPolicy099.isAllowedAssetRequest(
            Uri.parse("https://appassets.androidplatform.net.evil.example/assets/realtime/rtc.js")))
        assertFalse(RealtimeWebViewSecurityPolicy099.isAllowedAssetRequest(
            Uri.parse("file:///android_asset/realtime/index.html")))
        assertFalse(RealtimeWebViewSecurityPolicy099.isAllowedAssetRequest(
            Uri.parse("http://appassets.androidplatform.net/assets/realtime/index.html")))

        assertTrue(RealtimeWebViewSecurityPolicy099.isAllowedDocument(
            Uri.parse("$root/assets/realtime/index.html")))
        assertFalse(RealtimeWebViewSecurityPolicy099.isAllowedDocument(
            Uri.parse("$root/assets/realtime/rtc.js")))
        assertFalse(RealtimeWebViewSecurityPolicy099.isAllowedDocument(
            Uri.parse("$root/assets/realtime/index.html?remote=1")))
        assertFalse(RealtimeWebViewSecurityPolicy099.isAllowedDocument(
            Uri.parse("https://attacker.example/")))
    }

    @Test fun microphoneGrantRequiresExactAudioOnlyTrustedOriginActiveCallAndRuntimeGrant() {
        val audioOnly = listOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
        assertTrue(RealtimeWebViewSecurityPolicy099.mayGrantAudio(trustedOrigin, audioOnly,
            activeUserCall = true, runtimeRecordPermission = true))
        assertFalse(RealtimeWebViewSecurityPolicy099.mayGrantAudio(trustedOrigin, audioOnly,
            activeUserCall = false, runtimeRecordPermission = true))
        assertFalse(RealtimeWebViewSecurityPolicy099.mayGrantAudio(trustedOrigin, audioOnly,
            activeUserCall = true, runtimeRecordPermission = false))
        assertFalse(RealtimeWebViewSecurityPolicy099.mayGrantAudio(Uri.parse("https://evil.example"), audioOnly,
            activeUserCall = true, runtimeRecordPermission = true))
        assertFalse(RealtimeWebViewSecurityPolicy099.mayGrantAudio(trustedOrigin,
            listOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE), activeUserCall = true, runtimeRecordPermission = true))
        assertFalse(RealtimeWebViewSecurityPolicy099.mayGrantAudio(trustedOrigin,
            listOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE, PermissionRequest.RESOURCE_VIDEO_CAPTURE),
            activeUserCall = true, runtimeRecordPermission = true))
    }

    @Test fun bridgeRejectsWrongOriginSubframesStaleSessionMalformedAndOversizedMessages() {
        val ready = """{"type":"ready"}"""
        assertTrue(RealtimeWebViewSecurityPolicy099.isAllowedBridgeMessage(ready, trustedOrigin,
            isMainFrame = true, activeCall = true))
        assertFalse(RealtimeWebViewSecurityPolicy099.isAllowedBridgeMessage(ready, trustedOrigin,
            isMainFrame = false, activeCall = true))
        assertFalse(RealtimeWebViewSecurityPolicy099.isAllowedBridgeMessage(ready, Uri.parse("https://evil.example"),
            isMainFrame = true, activeCall = true))
        assertFalse(RealtimeWebViewSecurityPolicy099.isAllowedBridgeMessage(ready, trustedOrigin,
            isMainFrame = true, activeCall = false))
        assertFalse(RealtimeWebViewSecurityPolicy099.isAllowedBridgeMessage("not-json", trustedOrigin,
            isMainFrame = true, activeCall = true))
        assertFalse(RealtimeWebViewSecurityPolicy099.isAllowedBridgeMessage(
            "{" + "x".repeat(RealtimeWebViewSecurityPolicy099.MAX_BRIDGE_MESSAGE_CHARS) + "}",
            trustedOrigin, isMainFrame = true, activeCall = true))
    }
}
