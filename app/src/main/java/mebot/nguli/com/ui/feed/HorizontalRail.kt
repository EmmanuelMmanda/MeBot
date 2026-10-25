package mebot.nguli.com.ui.feed

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** One horizontal scroll owner, stable item identities and a selected item that stays visible.
 * Selection only moves this rail; it never moves an enclosing reading page back to its header.
 */
@Composable
internal fun <T> HorizontalRail(
    items: List<T>,
    itemKey: (T) -> String,
    modifier: Modifier = Modifier,
    selectedKey: String? = null,
    spacing: Dp = 8.dp,
    content: @Composable (T) -> Unit,
) {
    val scroll = rememberScrollState()
    val positions = remember { mutableStateMapOf<String, Pair<Int, Int>>() }
    var viewport by remember { mutableIntStateOf(0) }
    val direction = LocalLayoutDirection.current
    val background = MaterialTheme.colorScheme.background
    val selectedBounds = selectedKey?.let { positions[it] }
    LaunchedEffect(selectedKey, selectedBounds, viewport, scroll.maxValue, direction) {
        val bounds = selectedBounds ?: return@LaunchedEffect
        if (viewport <= 0 || scroll.maxValue == Int.MAX_VALUE) return@LaunchedEffect
        val total = viewport + scroll.maxValue
        val start = if (direction == LayoutDirection.Ltr) bounds.first else total - bounds.second
        val end = if (direction == LayoutDirection.Ltr) bounds.second else total - bounds.first
        val target = when {
            end - start > viewport -> start
            start < scroll.value -> start
            end > scroll.value + viewport -> end - viewport
            else -> scroll.value
        }.coerceIn(0, scroll.maxValue)
        if (target != scroll.value) scroll.animateScrollTo(target)
    }
    Row(
        modifier.fillMaxWidth().onSizeChanged { viewport = it.width }.drawWithContent {
            drawContent()
            val edge = 12.dp.toPx().coerceAtMost(size.width / 3)
            val left = if (direction == LayoutDirection.Ltr) scroll.canScrollBackward else scroll.canScrollForward
            val right = if (direction == LayoutDirection.Ltr) scroll.canScrollForward else scroll.canScrollBackward
            if (left) drawRect(Brush.horizontalGradient(listOf(background, background.copy(alpha = 0f)), 0f, edge),
                size = Size(edge, size.height))
            if (right) drawRect(Brush.horizontalGradient(listOf(background.copy(alpha = 0f), background), size.width - edge, size.width),
                topLeft = Offset(size.width - edge, 0f), size = Size(edge, size.height))
        }.horizontalScroll(scroll),
        horizontalArrangement = Arrangement.spacedBy(spacing), verticalAlignment = Alignment.CenterVertically,
    ) {
        items.forEach { item ->
            val id = itemKey(item)
            key(id) {
                Box(Modifier.onGloballyPositioned { coordinates ->
                    val left = coordinates.positionInParent().x.roundToInt()
                    positions[id] = left to left + coordinates.size.width
                }) { content(item) }
            }
        }
    }
}
