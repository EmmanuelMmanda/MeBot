package mebot.nguli.com.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import mebot.nguli.com.R

/** One welcome screen. Identity and preferences can be edited later. */
@Composable
fun MeBotOnboardingScreen(
    language: String,
    onLanguage: (String) -> Unit,
    onAlarm: () -> Unit,
    onExplore: () -> Unit,
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 540.dp).fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = MeBotTokens.ScreenPadding, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                MeBotMark(Modifier.size(34.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { onLanguage(if (language == "sw") "en" else "sw") }) {
                    Text(stringResource(if (language == "sw") R.string.english else R.string.swahili),
                        style = MaterialTheme.typography.labelMedium)
                }
            }
            Spacer(Modifier.height(8.dp))
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.mebot_welcome), style = MaterialTheme.typography.displaySmall)
                Text(stringResource(R.string.mebot_welcome_detail),
                    style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            WelcomeArtwork()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                WelcomeFeature(Icons.Outlined.Alarm, stringResource(R.string.mebot_wake))
                WelcomeFeature(Icons.Outlined.Timer, stringResource(R.string.focus_tab))
                WelcomeFeature(Icons.Outlined.WbSunny, stringResource(R.string.mebot_check_in))
            }
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onAlarm, Modifier.fillMaxWidth().heightIn(min = 56.dp),
                    shape = RoundedCornerShape(MeBotTokens.ControlRadius)) {
                    Text(stringResource(R.string.mebot_first_alarm), Modifier.weight(1f), textAlign = TextAlign.Center)
                    Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, Modifier.size(20.dp))
                }
                TextButton(onClick = onExplore, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.mebot_explore))
                }
                Text(stringResource(R.string.mebot_local), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun WelcomeFeature(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FeatureIcon(icon, MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primaryContainer, size = 42.dp)
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun WelcomeArtwork() {
    Box(Modifier.fillMaxWidth().height(246.dp)) {
        val decoration = MaterialTheme.colorScheme.primary.copy(alpha = .08f)
        Canvas(Modifier.matchParentSize()) {
            drawCircle(decoration, size.width * .31f, Offset(size.width * .79f, size.height * .36f))
            drawCircle(decoration, size.width * .16f, Offset(size.width * .14f, size.height * .81f), style = Stroke(18.dp.toPx()))
        }
        WaveCard(Modifier.align(Alignment.TopCenter).padding(horizontal = 9.dp, vertical = 10.dp).rotate(-3f)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.mebot_morning_alarm), style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                Icon(Icons.Outlined.Alarm, null, Modifier.size(24.dp))
            }
            Text("07:00", style = MaterialTheme.typography.displayLarge)
            Text(stringResource(R.string.mebot_weekdays), style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(12.dp))
        }
        Surface(
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 4.dp, bottom = 0.dp).rotate(3f),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 10.dp,
        ) {
            Row(Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(38.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Check, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                }
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(stringResource(R.string.mebot_math_label), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("12 + 7 = 19", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
