package mebot.nguli.com.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import mebot.nguli.com.R
import mebot.nguli.com.core.AppState

@Composable
fun ToolsScreen(state: AppState, onFocus: () -> Unit, onAlarms: () -> Unit, onHistory: () -> Unit, onCheckpoint: () -> Unit) {
    val enabled = state.settings.enabledModules
    val feature = MeBotPalette.current
    ScreenContainer {
        ScreenTitle(stringResource(R.string.tools))
        if ("alarms" in enabled) {
            WaveCard {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Alarm, null, Modifier.size(32.dp))
                    Spacer(Modifier.weight(1f))
                    Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, Modifier.size(20.dp))
                }
                Text(stringResource(R.string.alarms), style = MaterialTheme.typography.headlineMedium)
                Text(stringResource(R.string.mb_alarm_tools_summary), style = MaterialTheme.typography.bodyMedium)
                Button(onClick = onAlarms, colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.onPrimary, contentColor = MaterialTheme.colorScheme.primary),
                    shape = RoundedCornerShape(14.dp)) { Text(stringResource(R.string.secondary_open_alarms)) }
            }
        }
        if ("focus" in enabled) ToolRow(stringResource(R.string.focus_tab),
            stringResource(if (state.focus != null) R.string.resume_focus else R.string.mb_focus_tools_summary),
            Icons.Outlined.Timer, feature.onViolet, feature.violetSurface, onFocus)
        if ("history" in enabled) ToolRow(stringResource(R.string.history), stringResource(R.string.mb_history_tools_summary),
            Icons.Outlined.History, feature.onBlue, feature.blueSurface, onHistory)
        if ("alarms" in enabled) ToolRow(stringResource(R.string.mb_qr_checkpoint),
            state.settings.checkpointLabel.ifBlank { stringResource(R.string.mb_qr_tools_summary) },
            Icons.Outlined.QrCode2, feature.onMint, feature.mintSurface, onCheckpoint)
        if (enabled.none { it in setOf("focus", "alarms", "history") }) AppCard { SupportingText(stringResource(R.string.no_modules)) }
    }
}

@Composable
private fun ToolRow(title: String, subtitle: String, icon: ImageVector, tint: Color, container: Color, onClick: () -> Unit) {
    Surface(onClick = onClick, color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(24.dp)) {
        Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            FeatureIcon(icon, tint, container, size = 52.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, Modifier.size(19.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
