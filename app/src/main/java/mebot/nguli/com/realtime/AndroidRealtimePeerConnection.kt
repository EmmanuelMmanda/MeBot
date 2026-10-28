package mebot.nguli.com.realtime

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.invisibleToUser
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.io.ByteArrayInputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Platform-WebView WebRTC factory. It embeds only packaged, immutable JS assets served by
 * WebViewAssetLoader; no provider secret, service token, app profile, or native object bridge
 * crosses into JavaScript. Mount [RealtimeWebRtcSurface] alongside the visible call controls.
 */
class AndroidRealtimePeerConnectionFactory(context: Context) : RealtimePeerConnectionFactory {
    private val context = context
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var surface: FrameLayout? = null
    @Volatile private var active: AndroidRealtimePeerConnection? = null

    override fun create(callbacks: RealtimePeerCallbacks): RealtimePeerConnection =
        AndroidRealtimePeerConnection(context, this, callbacks)

    internal fun attachSurface(container: FrameLayout) {
        checkMainThread()
        surface = container
        active?.attachTo(container)
    }

    internal fun detachSurface(container: FrameLayout) {
        checkMainThread()
        if (surface === container) surface = null
        active?.detachFrom(container)
    }

    internal suspend fun openPeer(peer: AndroidRealtimePeerConnection): WebView = withContext(Dispatchers.Main.immediate) {
        checkMainThread()
        if (peer.isClosing) throw RealtimeTransportException("call_ended")
        val host = withTimeout(SURFACE_ATTACH_TIMEOUT_MILLIS) {
            while (surface?.isAttachedToWindow != true) delay(SURFACE_POLL_MILLIS)
            surface ?: throw RealtimeTransportException("transport_not_available")
        }
        if (peer.isClosing) throw RealtimeTransportException("call_ended")
        if (active != null) throw RealtimeTransportException("call_already_active")
        val view = WebView(context)
        var installed = false
        try {
            configureWebView(view, peer)
            installed = peer.installView(view)
            if (!installed) throw RealtimeTransportException("call_ended")
            active = peer
            if (view.parent !== host) host.addView(view, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            if (peer.isClosing) throw RealtimeTransportException("call_ended")
            peer.beginAssetLoad()
            view
        } catch (error: Throwable) {
            active = null
            if (installed) {
                runCatching { peer.close() }
            } else {
                runCatching { view.stopLoading() }
                runCatching { view.destroy() }
            }
            throw error
        }
    }

    internal suspend fun releasePeer(peer: AndroidRealtimePeerConnection, view: WebView?) {
        withContext(Dispatchers.Main.immediate) {
            if (active === peer) active = null
            if (view != null) {
                (view.parent as? ViewGroup)?.removeView(view)
                runCatching { view.stopLoading() }
                runCatching { view.clearHistory() }
                runCatching { view.destroy() }
            }
        }
    }

    internal fun releasePeerImmediately(peer: AndroidRealtimePeerConnection, view: WebView?) {
        val cleanup = {
            if (active === peer) active = null
            if (view != null) {
                (view.parent as? ViewGroup)?.removeView(view)
                runCatching { view.onPause() }
                runCatching { view.stopLoading() }
                runCatching { view.clearHistory() }
                runCatching { view.destroy() }
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) cleanup() else mainHandler.post(cleanup)
    }

    /** Host shutdown seam; caller must synchronously privacy-stop before disposing the factory. */
    suspend fun dispose() { active?.close() }

    /** Activity onPause/lock seam. Call on Main after controller privacyStop, never after view disposal. */
    fun privacyStopImmediately() { active?.privacyStopImmediately() }

    private fun configureWebView(view: WebView, peer: AndroidRealtimePeerConnection) {
        checkMainThread()
        view.setBackgroundColor(Color.TRANSPARENT)
        view.isFocusable = false
        view.isFocusableInTouchMode = false
        view.isClickable = false
        view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        view.setOnTouchListener { _, _ -> true }
        view.settings.apply {
            javaScriptEnabled = true // required for the packaged WebRTC transport only
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            allowFileAccess = false
            allowContentAccess = false
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false
            domStorageEnabled = false
            databaseEnabled = false
            setGeolocationEnabled(false)
            mediaPlaybackRequiresUserGesture = false // trusted local page; start still requires explicit user action
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
        }
        view.setDownloadListener { _, _, _, _, _ -> Unit }
        val loader = WebViewAssetLoader.Builder()
            .setDomain(TRUSTED_HOST)
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
            .build()
        view.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                val uri = request.url
                if (!RealtimeWebViewSecurityPolicy099.isAllowedAssetRequest(uri)) return blockedResponse()
                return loader.shouldInterceptRequest(uri) ?: blockedResponse()
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                !RealtimeWebViewSecurityPolicy099.isAllowedDocument(request.url)

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) peer.onPageFailure()
            }

            override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                peer.onPageFailure()
                return true
            }
        }
        view.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                val hasRuntimePermission = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                if (RealtimeWebViewSecurityPolicy099.mayGrantAudio(
                        request.origin, request.resources.toList(), peer.mayCaptureAudio(), hasRuntimePermission)) {
                    request.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
                } else {
                    request.deny()
                }
            }

            override fun onPermissionRequestCanceled(request: PermissionRequest) = Unit
            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean,
                                       resultMsg: android.os.Message): Boolean = false
        }
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            throw RealtimeTransportException("transport_not_available")
        }
        WebViewCompat.addWebMessageListener(
            view,
            JS_OBJECT_NAME,
            setOf(TRUSTED_ORIGIN),
        ) { _, message: WebMessageCompat, sourceOrigin: Uri, isMainFrame: Boolean, replyProxy: JavaScriptReplyProxy ->
            peer.onWebMessage(message.data, sourceOrigin, isMainFrame, replyProxy)
        }
    }

    private fun blockedResponse() = WebResourceResponse(
        "text/plain", "UTF-8", 403, "Blocked", mapOf("Cache-Control" to "no-store"), ByteArrayInputStream(byteArrayOf()),
    )

    private fun checkMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) { "WebView must be created on the main thread" }
    }

    private companion object {
        const val TRUSTED_HOST = "appassets.androidplatform.net"
        const val TRUSTED_ORIGIN = "https://appassets.androidplatform.net"
        const val INDEX_PATH = "/assets/realtime/index.html"
        const val SCRIPT_PATH = "/assets/realtime/rtc.js"
        const val JS_OBJECT_NAME = "MebotNative"
        const val SURFACE_ATTACH_TIMEOUT_MILLIS = 10_000L
        const val SURFACE_POLL_MILLIS = 50L
    }
}

/**
 * A 1dp noninteractive host keeps the WebView attached while the active call surface is composed.
 * It has no remote content or controls; Compose owns all visible call/microphone UI.
 */
@Composable
fun RealtimeWebRtcSurface(factory: AndroidRealtimePeerConnectionFactory) {
    val context = LocalContext.current
    val container = androidx.compose.runtime.remember(factory, context) { FrameLayout(context) }
    AndroidView(
        factory = { container },
        modifier = Modifier.size(1.dp).alpha(0.01f).semantics { invisibleToUser() }.testTag("realtime-web-transport"),
    )
    DisposableEffect(factory, container) {
        factory.attachSurface(container)
        onDispose { factory.detachSurface(container) }
    }
}

/** WebRTC transport implemented by the Android system WebView; no bundled native WebRTC code. */
internal class AndroidRealtimePeerConnection(
    private val context: Context,
    private val factory: AndroidRealtimePeerConnectionFactory,
    private val callbacks: RealtimePeerCallbacks,
) : RealtimePeerConnection {
    private val closed = AtomicBoolean(false)
    private val closeStarted = AtomicBoolean(false)
    private val permissionGate = AtomicBoolean(false)
    private val pageReady = CompletableDeferred<Unit>()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JsonElement>>()
    @Volatile private var webView: WebView? = null
    @Volatile private var replyProxy: JavaScriptReplyProxy? = null
    @Volatile private var microphoneMuted = false

    internal val isClosing: Boolean get() = closeStarted.get() || closed.get()

    internal fun installView(view: WebView): Boolean {
        if (isClosing) return false
        webView = view
        if (isClosing) {
            webView = null
            return false
        }
        return true
    }

    internal fun beginAssetLoad() {
        webView?.loadUrl("$TRUSTED_ORIGIN$INDEX_PATH") ?: onPageFailure()
    }

    internal fun attachTo(container: FrameLayout) {
        if (Looper.myLooper() != Looper.getMainLooper()) return
        webView?.takeIf { it.parent !== container }?.let { view ->
            (view.parent as? ViewGroup)?.removeView(view)
            container.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT))
        }
    }

    internal fun detachFrom(container: FrameLayout) {
        if (Looper.myLooper() != Looper.getMainLooper()) return
        webView?.takeIf { it.parent === container }?.let(container::removeView)
    }

    internal fun onPageFailure() {
        pageReady.completeExceptionally(RealtimeTransportException("transport_not_available"))
        callbacks.onPeerState(RealtimePeerLinkState.FAILED)
        pending.values.forEach { it.completeExceptionally(RealtimeTransportException("transport_not_available")) }
        pending.clear()
    }

    internal fun mayCaptureAudio(): Boolean = !closed.get() && permissionGate.get() &&
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** Clears the capture gate synchronously and destroys the WebView on Main without network waits. */
    override fun privacyStopImmediately() {
        permissionGate.set(false)
        if (!closeStarted.compareAndSet(false, true)) return
        closed.set(true)
        val view = webView
        webView = null
        replyProxy = null
        pageReady.completeExceptionally(RealtimeTransportException("call_ended"))
        pending.values.forEach { it.completeExceptionally(RealtimeTransportException("call_ended")) }
        pending.clear()
        factory.releasePeerImmediately(this, view)
    }

    internal fun onWebMessage(raw: String?, sourceOrigin: Uri, isMainFrame: Boolean, proxy: JavaScriptReplyProxy) {
        val message = raw ?: return
        if (!RealtimeWebViewSecurityPolicy099.isAllowedBridgeMessage(
                message, sourceOrigin, isMainFrame, activeCall = !closed.get())) return
        replyProxy = proxy
        val root = runCatching { Json.parseToJsonElement(message).jsonObject }.getOrNull() ?: return
        when (root["type"]?.jsonPrimitive?.contentOrNull) {
            "ready" -> pageReady.complete(Unit)
            "peer" -> callbacks.onPeerState(when (root["state"]?.jsonPrimitive?.contentOrNull) {
                "connected" -> RealtimePeerLinkState.CONNECTED
                "connecting" -> RealtimePeerLinkState.CONNECTING
                "disconnected" -> RealtimePeerLinkState.DISCONNECTED
                "failed" -> RealtimePeerLinkState.FAILED
                "closed" -> RealtimePeerLinkState.CLOSED
                else -> RealtimePeerLinkState.NEW
            })
            "dataChannel" -> callbacks.onDataChannelState(when (root["state"]?.jsonPrimitive?.contentOrNull) {
                "open" -> RealtimeDataChannelState.OPEN
                "closing" -> RealtimeDataChannelState.CLOSING
                "closed" -> RealtimeDataChannelState.CLOSED
                else -> RealtimeDataChannelState.CONNECTING
            })
            "remoteAudio" -> if (root["ready"]?.jsonPrimitive?.contentOrNull == "true") callbacks.onRemoteAudioTrackReceived()
            "data" -> root["message"]?.jsonPrimitive?.contentOrNull?.takeIf { it.length <= MAX_DATA_MESSAGE_CHARS }
                ?.let(callbacks::onDataChannelText)
            "error" -> {
                val code = root["code"]?.jsonPrimitive?.contentOrNull?.let(::sanitizeWebError)
                    ?: "temporary_error"
                callbacks.onDataChannelText("""{"type":"error","error":{"code":"$code"}}""")
            }
            "result" -> {
                val id = root["id"]?.jsonPrimitive?.contentOrNull ?: return
                val continuation = pending.remove(id) ?: return
                val success = root["ok"]?.jsonPrimitive?.contentOrNull == "true"
                if (success) continuation.complete(root["value"] ?: JsonNull)
                else continuation.completeExceptionally(RealtimeTransportException(
                    sanitizeWebError(root["code"]?.jsonPrimitive?.contentOrNull ?: "temporary_error")))
            }
        }
    }

    override suspend fun addMicrophoneTrack() {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            throw RealtimeTransportException("microphone_permission_required")
        permissionGate.set(true) // active connect call has explicit UI start and Android permission
        try {
            factory.openPeer(this)
            invoke("addMicrophoneTrack")
        } catch (error: Throwable) {
            permissionGate.set(false)
            throw error
        }
    }

    override suspend fun createDataChannel(label: String) {
        if (label != "oai-events") throw RealtimeTransportException("invalid_input")
        invoke("createDataChannel") { put("label", label) }
    }

    override suspend fun createOffer(): String = invoke("createOffer")
        .jsonObject["sdp"]?.jsonPrimitive?.contentOrNull
        ?.takeIf { it.startsWith("v=0") && it.contains("m=audio") }
        ?: throw RealtimeTransportException("invalid_sdp")

    override suspend fun setLocalDescription(offerSdp: String) {
        if (offerSdp.length > MAX_SDP_CHARS) throw RealtimeTransportException("invalid_sdp")
        invoke("setLocalDescription") { put("sdp", offerSdp) }
    }

    override suspend fun awaitIceGatheringComplete(timeoutMillis: Long): String {
        val value = withTimeout(timeoutMillis + 1_000L) {
            invoke("awaitIceGatheringComplete") { put("timeoutMillis", timeoutMillis) }
        }
        return value.jsonPrimitive.contentOrNull
            ?.takeIf { it.length <= MAX_SDP_CHARS && it.startsWith("v=0") && it.contains("m=audio") }
            ?: throw RealtimeTransportException("invalid_sdp")
    }

    override suspend fun setRemoteDescription(answerSdp: String) {
        if (answerSdp.length > MAX_SDP_CHARS) throw RealtimeTransportException("invalid_sdp")
        invoke("setRemoteDescription") { put("sdp", answerSdp) }
    }

    override fun setMicrophoneMuted(muted: Boolean): Boolean {
        if (closed.get()) return false
        microphoneMuted = muted
        permissionGate.set(!muted && context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
        return postOneWay("setMicrophoneMuted") { put("muted", muted) }
    }

    override fun sendDataChannelMessage(eventJson: String): Boolean {
        if (closed.get() || eventJson.length > MAX_COMMAND_CHARS || webView == null) return false
        val result = postOneWay("sendDataChannelMessage") { put("message", eventJson) }
        return result
    }

    override suspend fun close() {
        if (!closeStarted.compareAndSet(false, true)) return
        permissionGate.set(false)
        val view = webView
        runCatching {
            if (view != null && pageReady.isCompleted) {
                withTimeout(1_000) { invoke("close", allowClosing = true) }
            }
        }
        closed.set(true)
        webView = null
        replyProxy = null
        pending.values.forEach { it.cancel() }
        pending.clear()
        factory.releasePeer(this, view)
    }

    private suspend fun invoke(
        operation: String,
        allowClosing: Boolean = false,
        params: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {},
    ): JsonElement {
        if (closed.get() && !allowClosing) throw RealtimeTransportException("call_ended")
        withTimeout(PAGE_READY_TIMEOUT_MILLIS) { pageReady.await() }
        val id = UUID.randomUUID().toString()
        val continuation = CompletableDeferred<JsonElement>()
        pending[id] = continuation
        val command = buildJsonObject { put("id", id); put("op", operation); params() }.toString()
        val posted = withContext(Dispatchers.Main.immediate) {
            val target = replyProxy
            if (target == null || closed.get() && !allowClosing) false
            else runCatching { target.postMessage(command); true }.getOrDefault(false)
        }
        if (!posted) {
            pending.remove(id)
            throw RealtimeTransportException("transport_not_available")
        }
        try {
            return withTimeout(COMMAND_TIMEOUT_MILLIS) { continuation.await() }
        } finally {
            pending.remove(id)
        }
    }

    private fun postOneWay(operation: String, params: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): Boolean {
        val view = webView ?: return false
        val command = buildJsonObject { put("id", UUID.randomUUID().toString()); put("op", operation); params() }.toString()
        val send = {
            val proxy = replyProxy
            if (view !== webView || proxy == null || closed.get() && operation != "close") false
            else runCatching { proxy.postMessage(command); true }.getOrDefault(false)
        }
        return if (Looper.myLooper() == Looper.getMainLooper()) send() else {
            // For privacy mute, the local state/gate is invalidated synchronously above;
            // WebView operations are safely serialized onto its required main thread.
            factoryMainPost(send)
            true
        }
    }

    private fun factoryMainPost(action: () -> Boolean) {
        Handler(Looper.getMainLooper()).post { action() }
    }

    private fun sanitizeWebError(value: String): String = when (value) {
        "microphone_permission_required", "microphone_unavailable", "transport_not_available",
        "invalid_sdp", "audio_playback_unavailable", "call_ended", "temporary_error" -> value
        else -> "temporary_error"
    }

    private companion object {
        const val TRUSTED_HOST = "appassets.androidplatform.net"
        const val TRUSTED_ORIGIN = "https://appassets.androidplatform.net"
        const val INDEX_PATH = "/assets/realtime/index.html"
        const val MAX_MESSAGE_CHARS = 1_100_000
        const val MAX_DATA_MESSAGE_CHARS = 128_000
        const val MAX_COMMAND_CHARS = 1_100_000
        const val MAX_SDP_CHARS = 512_000
        const val PAGE_READY_TIMEOUT_MILLIS = 15_000L
        const val COMMAND_TIMEOUT_MILLIS = 35_000L
    }
}

/** Pure origin/resource/permission gates so transport security can be unit tested without a WebView. */
internal object RealtimeWebViewSecurityPolicy099 {
    const val TRUSTED_HOST = "appassets.androidplatform.net"
    const val TRUSTED_ORIGIN = "https://appassets.androidplatform.net"
    const val INDEX_PATH = "/assets/realtime/index.html"
    private const val SCRIPT_PATH = "/assets/realtime/rtc.js"
    const val MAX_BRIDGE_MESSAGE_CHARS = 1_100_000

    fun isAllowedAssetRequest(uri: Uri): Boolean =
        uri.toString() == "$TRUSTED_ORIGIN$INDEX_PATH" || uri.toString() == "$TRUSTED_ORIGIN$SCRIPT_PATH"

    fun isAllowedDocument(uri: Uri): Boolean = isTrustedOrigin(uri) && uri.path == INDEX_PATH &&
        uri.query == null && uri.fragment == null

    fun mayGrantAudio(origin: Uri, resources: List<String>, activeUserCall: Boolean,
                      runtimeRecordPermission: Boolean): Boolean =
        isTrustedOrigin(origin) && resources == listOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE) &&
            activeUserCall && runtimeRecordPermission

    fun isAllowedBridgeMessage(raw: String?, sourceOrigin: Uri, isMainFrame: Boolean,
                               activeCall: Boolean): Boolean {
        if (!activeCall || !isMainFrame || !isTrustedOrigin(sourceOrigin) || raw.isNullOrBlank() ||
            raw.length > MAX_BRIDGE_MESSAGE_CHARS) return false
        return runCatching { Json.parseToJsonElement(raw).jsonObject }.isSuccess
    }

    private fun isTrustedOrigin(uri: Uri): Boolean = uri.scheme == "https" &&
        uri.host == TRUSTED_HOST && uri.port in setOf(-1, 443) && uri.query == null && uri.fragment == null
}
