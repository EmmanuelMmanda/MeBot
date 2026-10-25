package mebot.nguli.com.ui.feed

import android.graphics.Bitmap
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import mebot.nguli.com.MeBotApplication
import mebot.nguli.com.core.FeedItem
import mebot.nguli.com.ui.MeBotPalette

/** Test/preview injection supplies labelled fixtures; production always uses the bounded media cache. */
internal val LocalPublisherImageLoader = staticCompositionLocalOf<(suspend (String) -> Bitmap?)?> { null }

@Composable
internal fun PublisherImage(item: FeedItem, modifier: Modifier = Modifier, large: Boolean = false) {
    val context = LocalContext.current
    val loader = LocalPublisherImageLoader.current
    val url = item.media?.url
    val bitmap by produceState<Bitmap?>(initialValue = null, key1 = url, key2 = loader) {
        value = null
        if (!url.isNullOrBlank()) value = loader?.invoke(url)
            ?: if (loader == null) (context.applicationContext as? MeBotApplication)?.feedMedia?.load(url) else null
    }
    Crossfade(targetState = bitmap, modifier = modifier.clipToBounds(), animationSpec = tween(180), label = "publisher-image") { loaded ->
        if (loaded != null) Image(loaded.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else EditorialFallback(item.kind, large)
    }
}

@Composable
private fun EditorialFallback(kind: String, large: Boolean) {
    val colors = MeBotPalette.current
    val (background, ink) = when (kind) {
        "opportunities", "scholarships", "business", "github" -> colors.mintSurface to colors.onMint
        "entertainment", "music", "horoscope", "learning" -> colors.violetSurface to colors.onViolet
        "science", "technology", "security", "weather", "travel" -> colors.blueSurface to colors.onBlue
        else -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.primary
    }
    // This is visibly category artwork, never an invented photograph of an event.
    Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(background, ink.copy(alpha = if (large) .55f else .18f)))),
        contentAlignment = if (large) Alignment.CenterEnd else Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val center = Offset(size.width * .8f, size.height * .3f)
            for (ring in 1..4) drawCircle(ink.copy(alpha = .12f), size.minDimension * ring * .24f, center, style = Stroke(1.2.dp.toPx()))
            drawCircle(ink.copy(alpha = .08f), size.minDimension * .48f, Offset(size.width * .12f, size.height * .98f))
        }
        Icon(moduleIcon(kind), contentDescription = null, modifier = Modifier.padding(if (large) 28.dp else 0.dp).size(if (large) 76.dp else 36.dp),
            tint = ink.copy(alpha = .72f))
    }
}
