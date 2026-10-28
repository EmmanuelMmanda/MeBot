package mebot.nguli.com.ui.feed

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect

/** Saved navigation is not proof that this process owns a usable voice transport. */
@Composable
internal fun VoiceCallRouteGate099(
    capabilityAvailable: Boolean,
    transportPresent: Boolean,
    onUnavailable: () -> Unit,
    content: @Composable () -> Unit,
) {
    if (capabilityAvailable && transportPresent) content()
    else LaunchedEffect(capabilityAvailable, transportPresent) { onUnavailable() }
}
