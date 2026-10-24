package app.mynguli.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun ScreenContainer(modifier: Modifier = Modifier, scrollKey: String = "screen", content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier.widthIn(max = MeBotTokens.ContentWidth).fillMaxWidth()
                .verticalScroll(rememberSaveable(scrollKey, saver = ScrollState.Saver) { ScrollState(0) }).imePadding()
                .padding(horizontal = MeBotTokens.ScreenPadding, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp), content = content,
        )
    }
}

@Composable
fun ScreenTitle(title: String, subtitle: String = "", kicker: String = "") {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (kicker.isNotBlank()) Text(kicker, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        Text(title, style = MaterialTheme.typography.headlineLarge)
        if (subtitle.isNotBlank()) SupportingText(subtitle)
    }
}

@Composable
fun SupportingText(text: String) = Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
fun SectionLabel(text: String) = Text(text, style = MaterialTheme.typography.titleMedium)

@Composable
fun SectionHeader(title: String, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        if (actionLabel != null && onAction != null) TextButton(onClick = onAction, contentPadding = PaddingValues(start = 12.dp, end = 0.dp)) {
            Text(actionLabel, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.surface,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(modifier.fillMaxWidth(), color = color, shape = RoundedCornerShape(MeBotTokens.CardRadius)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
    }
}

/** Brand surface drawn in code; no network images or decoration encoded as text. */
@Composable
fun WaveCard(
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.primary,
    contentColor: Color = MaterialTheme.colorScheme.onPrimary,
    contentPadding: PaddingValues = PaddingValues(24.dp),
    verticalSpacing: Dp = 12.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val brand = containerColor
    val ink = contentColor
    Box(modifier.fillMaxWidth().clip(RoundedCornerShape(MeBotTokens.CardRadius))
        .background(Brush.linearGradient(listOf(brand, brand.copy(red = (brand.red * .85f).coerceIn(0f, 1f)))))) {
        Canvas(Modifier.matchParentSize()) {
            val w = size.width
            val h = size.height
            repeat(5) { i ->
                val y = h * (.46f + i * .035f)
                val wave = Path().apply {
                    moveTo(-w * .1f, y)
                    cubicTo(w * .28f, h * .15f, w * .35f, h * 1.2f, w * .7f, h * .57f)
                    cubicTo(w * .84f, h * .32f, w * 1.02f, h * .33f, w * 1.16f, h * .55f)
                }
                drawPath(wave, ink.copy(alpha = .08f + i * .012f), style = Stroke(width = 2.dp.toPx()))
            }
            drawCircle(ink.copy(alpha = .04f), radius = w * .39f, center = Offset(w * 1.02f, h * .07f))
        }
        CompositionLocalProvider(LocalContentColor provides ink) {
            Column(Modifier.padding(contentPadding), verticalArrangement = Arrangement.spacedBy(verticalSpacing), content = content)
        }
    }
}

@Composable
fun PrimaryAction(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick, Modifier.fillMaxWidth().heightIn(min = 54.dp), enabled = enabled,
        shape = RoundedCornerShape(MeBotTokens.ControlRadius), contentPadding = PaddingValues(16.dp)) { Text(text) }
}

@Composable
fun SecondaryAction(text: String, onClick: () -> Unit) {
    OutlinedButton(onClick, Modifier.fillMaxWidth().heightIn(min = 50.dp),
        shape = RoundedCornerShape(MeBotTokens.ControlRadius), contentPadding = PaddingValues(14.dp)) { Text(text) }
}

@Composable
fun FeatureIcon(icon: ImageVector, tint: Color, container: Color, modifier: Modifier = Modifier, size: Dp = 44.dp) {
    Box(modifier.size(size).clip(RoundedCornerShape(15.dp)).background(container), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(size * .5f), tint = tint)
    }
}

@Composable
fun CompactAction(label: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.clip(RoundedCornerShape(20.dp)).clickable(role = Role.Button, onClick = onClick)
        .padding(horizontal = 4.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FeatureIcon(icon, MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primaryContainer, size = 44.dp)
        Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun MeBotMark(modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    Canvas(modifier.size(32.dp)) {
        val w = size.width
        val h = size.height
        drawRoundRect(color, topLeft = Offset(w * .09f, h * .2f), size = Size(w * .82f, h * .64f), cornerRadius = CornerRadius(w * .23f))
        val eyeColor = if (color.luminance() > .5f) Color(0xFF171922) else Color.White
        drawCircle(eyeColor, w * .055f, Offset(w * .35f, h * .49f))
        drawCircle(eyeColor, w * .055f, Offset(w * .65f, h * .49f))
        drawRoundRect(eyeColor.copy(alpha = .9f), topLeft = Offset(w * .39f, h * .65f), size = Size(w * .22f, h * .035f), cornerRadius = CornerRadius(w * .02f))
        drawLine(color, Offset(w * .5f, h * .2f), Offset(w * .5f, h * .1f), w * .055f)
        drawCircle(color, w * .055f, Offset(w * .5f, h * .08f))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChoiceRow(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        options.forEach { (key, label) ->
            FilterChip(selected == key, { onSelect(key) }, label = { Text(label) },
                modifier = Modifier.heightIn(min = MeBotTokens.TouchTarget), shape = RoundedCornerShape(14.dp),
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ))
        }
    }
}
