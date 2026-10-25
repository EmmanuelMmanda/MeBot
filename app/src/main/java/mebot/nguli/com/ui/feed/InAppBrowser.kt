package mebot.nguli.com.ui.feed

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import mebot.nguli.com.R
import mebot.nguli.com.core.FeedLogic

/** Browser-owned Custom Tab: no credential interception, JS bridge or certificate overrides.
 * Compatible browsers render in-app controls; other handlers open their normal safe browser.
 * OAuth remains owned by the existing authorization flow and is not routed through a WebView.
 */
fun openInAppSource(context: Context, url: String) {
    if (!FeedLogic.safeUrl(url)) {
        Toast.makeText(context, R.string.feed_link_error, Toast.LENGTH_SHORT).show()
        return
    }
    val session = Bundle().apply { putBinder("android.support.customtabs.extra.SESSION", null) }
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
        putExtras(session)
        putExtra("android.support.customtabs.extra.TITLE_VISIBILITY", 1)
        putExtra("android.support.customtabs.extra.ENABLE_URLBAR_HIDING", false)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(intent) }
        .onFailure { Toast.makeText(context, R.string.feed_link_error, Toast.LENGTH_SHORT).show() }
}
