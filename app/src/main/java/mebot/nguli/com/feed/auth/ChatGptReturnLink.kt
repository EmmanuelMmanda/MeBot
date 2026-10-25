package mebot.nguli.com.feed.auth

import android.content.Intent
import java.net.URI

/**
 * A browser may reopen MeBot, but it never delivers OAuth credentials to an Activity.
 * The package-specific scheme is paired with a BROWSABLE manifest filter. Chromium
 * intentionally removes explicit components from browser intents before resolution.
 */
object ChatGptReturnLink {
    const val LEGACY_ACTION = "mebot.nguli.com.RETURN_FROM_CHATGPT"
    private const val HOST = "chatgpt"
    private const val PATH = "/return"
    private val packagePattern = Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+")

    internal fun browserUri(packageName: String): String {
        require(packageName.matches(packagePattern))
        // Reorder the existing task without clearing an active alarm screen.
        return "intent://$HOST$PATH#Intent;scheme=$packageName.connect;action=android.intent.action.VIEW;" +
            "category=android.intent.category.BROWSABLE;launchFlags=0x30020000;package=$packageName;end"
    }

    fun matches(intent: Intent?, packageName: String): Boolean {
        if (intent == null || !packageName.matches(packagePattern)) return false
        if (intent.action == LEGACY_ACTION) return intent.data == null
        if (intent.action != Intent.ACTION_VIEW) return false
        val uri = runCatching { URI(intent.dataString ?: return false) }.getOrNull() ?: return false
        return uri.scheme == "$packageName.connect" && uri.host == HOST && uri.rawPath == PATH &&
            uri.port == -1 && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null
    }
}
