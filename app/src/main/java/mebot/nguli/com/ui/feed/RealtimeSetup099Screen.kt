package mebot.nguli.com.ui.feed

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.SettingsEthernet
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import mebot.nguli.com.R
import mebot.nguli.com.core.FeedLogic
import mebot.nguli.com.realtime.RealtimeCapability
import mebot.nguli.com.realtime.RealtimeServerCapability
import mebot.nguli.com.realtime.RecordingTranscriptionCapability
import mebot.nguli.com.ui.AppCard
import mebot.nguli.com.ui.ScreenContainer
import mebot.nguli.com.ui.ScreenTitle
import java.net.URI

/** Safe display-only settings state. The service token itself is intentionally never accepted here. */
data class RealtimeSetup099UiState(
    val savedServiceUrl: String = "",
    val tokenConfigured: Boolean = false,
    val isSaving: Boolean = false,
    val isChecking: Boolean = false,
    val capability: RealtimeServerCapability? = null,
    val errorCode: String? = null,
)

/**
 * Optional voice broker configuration is separate from ChatGPT text sign-in and the text provider.
 * Saving calls only the supplied persistence callback; checking is a separate, explicit callback.
 */
@Composable
internal fun RealtimeSetup099Screen(
    state: RealtimeSetup099UiState,
    onSave: (serviceUrl: String, serviceToken: String?) -> Unit,
    onTest: () -> Unit,
    onRemove: () -> Unit,
    onBack: () -> Unit,
) {
    var serviceUrl by rememberSaveable(state.savedServiceUrl) { mutableStateOf(state.savedServiceUrl) }
    // Secrets are kept only in transient in-memory Compose state, never in saved-instance state.
    var serviceToken by remember(state.savedServiceUrl) { mutableStateOf("") }
    var confirmRemove by rememberSaveable { mutableStateOf(false) }

    val normalizedUrl = normalizeVoiceServiceUrl(serviceUrl)
    val urlValid = isValidVoiceServiceUrl(normalizedUrl)
    val urlChanged = normalizedUrl != state.savedServiceUrl
    val tokenProvided = serviceToken.isNotBlank()
    val tokenValid = !tokenProvided || isValidVoiceServiceToken(serviceToken)
    val canRetainToken = state.tokenConfigured && !urlChanged
    val configurationReady = urlValid && tokenValid && (canRetainToken || isValidVoiceServiceToken(serviceToken))
    val hasUnsavedChanges = urlChanged || tokenProvided
    val enabled = !state.isSaving && !state.isChecking
    val canSave = enabled && configurationReady
    val canTest = enabled && state.tokenConfigured && !urlChanged && !tokenProvided

    ScreenContainer(Modifier.testTag("realtime-setup-screen")) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.testTag("realtime-setup-back")) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.back))
            }
            Spacer(Modifier.weight(1f))
        }
        ScreenTitle(stringResource(R.string.realtime_setup_title), stringResource(R.string.realtime_setup_intro))

        AppCard(modifier = Modifier.testTag("realtime-setup-text-separation")) {
            Icon(Icons.Outlined.Cloud, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text(stringResource(R.string.realtime_setup_chatgpt_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.realtime_setup_chatgpt_body), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        AppCard(modifier = Modifier.testTag("realtime-setup-broker")) {
            Icon(Icons.Outlined.SettingsEthernet, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text(stringResource(R.string.realtime_setup_broker_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.realtime_setup_broker_body), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.realtime_setup_no_auto_call), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)

            OutlinedTextField(
                value = serviceUrl,
                onValueChange = { serviceUrl = it.take(500) },
                label = { Text(stringResource(R.string.realtime_setup_url_label)) },
                placeholder = { Text(stringResource(R.string.realtime_setup_url_placeholder)) },
                singleLine = true,
                enabled = enabled,
                isError = serviceUrl.isNotBlank() && !urlValid,
                supportingText = {
                    if (serviceUrl.isNotBlank() && !urlValid) Text(stringResource(R.string.realtime_setup_error_https), modifier = Modifier.testTag("realtime-setup-error-https"))
                    else Text(stringResource(R.string.realtime_setup_url_help))
                },
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().testTag("realtime-service-url"),
            )
            OutlinedTextField(
                value = serviceToken,
                onValueChange = { serviceToken = it.take(4096) },
                label = { Text(stringResource(R.string.realtime_setup_token_label)) },
                placeholder = { Text(stringResource(if (state.tokenConfigured) R.string.realtime_setup_token_saved_placeholder else R.string.realtime_setup_token_placeholder)) },
                singleLine = true,
                enabled = enabled,
                isError = tokenProvided && !tokenValid,
                visualTransformation = PasswordVisualTransformation(),
                supportingText = {
                    when {
                        tokenProvided && !tokenValid -> Text(stringResource(R.string.realtime_setup_error_token), modifier = Modifier.testTag("realtime-setup-error-token"))
                        normalizedUrl.isNotBlank() && (urlChanged || !state.tokenConfigured) && !tokenProvided -> Text(stringResource(R.string.realtime_setup_error_token_required), modifier = Modifier.testTag("realtime-setup-error-token"))
                        else -> Text(stringResource(if (state.tokenConfigured) R.string.realtime_setup_token_replace_help else R.string.realtime_setup_token_help))
                    }
                },
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().testTag("realtime-service-token"),
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Outlined.Security, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                Text(stringResource(R.string.realtime_setup_token_security), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            state.errorCode?.let { FeedStatus(it) }

            if (state.isSaving) LinearProgressIndicator(Modifier.fillMaxWidth())
            Button(
                onClick = {
                    val canonicalUrl = normalizeVoiceServiceUrl(serviceUrl)
                    when {
                        !isValidVoiceServiceUrl(canonicalUrl) -> Unit
                        tokenProvided && !isValidVoiceServiceToken(serviceToken) -> Unit
                        !state.tokenConfigured && !tokenProvided -> Unit
                        urlChanged && !tokenProvided -> Unit
                        else -> {
                            val secret = serviceToken.takeIf { it.isNotBlank() }
                            onSave(canonicalUrl, secret)
                            serviceToken = ""
                        }
                    }
                },
                enabled = canSave,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("realtime-save"),
                shape = RoundedCornerShape(16.dp),
            ) {
                if (state.isSaving) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else Text(stringResource(R.string.realtime_setup_save))
            }
            Text(stringResource(R.string.realtime_setup_save_help), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)

            if (state.tokenConfigured) {
                HorizontalDivider()
                Text(stringResource(R.string.realtime_setup_status_title), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(if (state.capability == null) R.string.realtime_setup_not_checked else R.string.realtime_setup_test_help),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (hasUnsavedChanges) Text(stringResource(R.string.realtime_setup_unsaved_changes),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.testTag("realtime-setup-unsaved"))
                if (state.isChecking) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(stringResource(R.string.realtime_setup_checking), style = MaterialTheme.typography.bodySmall)
                } else if (state.capability != null && !hasUnsavedChanges) {
                    VoiceCapabilityStatus(state.capability)
                }
                OutlinedButton(
                    onClick = onTest,
                    enabled = canTest,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp).testTag("realtime-test"),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Text(stringResource(R.string.realtime_setup_test))
                }
                TextButton(
                    onClick = { confirmRemove = true },
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("realtime-remove"),
                ) {
                    Icon(Icons.Outlined.DeleteOutline, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.realtime_setup_remove))
                }
            }
        }
    }

    if (confirmRemove) AlertDialog(
        onDismissRequest = { confirmRemove = false },
        modifier = Modifier.testTag("realtime-remove-confirm-dialog"),
        title = { Text(stringResource(R.string.realtime_setup_remove_title)) },
        text = { Text(stringResource(R.string.realtime_setup_remove_body)) },
        confirmButton = {
            mebot.nguli.com.security.ProtectedWindowRegistration()
            TextButton(
                onClick = {
                    confirmRemove = false
                    serviceToken = ""
                    onRemove()
                },
                modifier = Modifier.testTag("realtime-remove-confirm"),
            ) { Text(stringResource(R.string.realtime_setup_remove)) }
        },
        dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun VoiceCapabilityStatus(capability: RealtimeServerCapability) {
    Column(Modifier.fillMaxWidth().testTag("realtime-test-status"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CapabilityRow(
            label = stringResource(R.string.realtime_setup_voice_status),
            message = when (val voice = capability.voice) {
                RealtimeCapability.Available -> stringResource(R.string.realtime_setup_voice_available)
                is RealtimeCapability.Unavailable -> stringResource(voiceReasonString(voice.reason))
            },
            available = capability.voice.canOfferCall,
        )
        CapabilityRow(
            label = stringResource(R.string.realtime_setup_transcription_status),
            message = when (val transcription = capability.transcription) {
                RecordingTranscriptionCapability.Available -> stringResource(R.string.realtime_setup_transcription_available)
                is RecordingTranscriptionCapability.Unavailable -> stringResource(transcriptionReasonString(transcription.reason))
            },
            available = capability.transcription.canTranscribe,
        )
    }
}

@Composable
private fun CapabilityRow(label: String, message: String, available: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Text(message, style = MaterialTheme.typography.bodySmall,
            color = if (available) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun voiceReasonString(reason: RealtimeCapability.Reason): Int = when (reason) {
    RealtimeCapability.Reason.SERVICE_NOT_CONFIGURED -> R.string.workspace_voice_live_service_not_configured
    RealtimeCapability.Reason.SERVICE_TOKEN_MISSING -> R.string.workspace_voice_live_service_token_missing
    RealtimeCapability.Reason.REALTIME_NOT_ENABLED -> R.string.workspace_voice_live_not_enabled
    RealtimeCapability.Reason.API_ACCESS_NOT_CONFIRMED -> R.string.workspace_voice_live_api_access_not_confirmed
    RealtimeCapability.Reason.API_ACCESS_UNAVAILABLE -> R.string.workspace_voice_live_api_access_unavailable
    RealtimeCapability.Reason.TRANSPORT_NOT_AVAILABLE -> R.string.workspace_voice_live_transport_missing
    RealtimeCapability.Reason.DAILY_LIMIT_REACHED -> R.string.workspace_voice_live_daily_limit
    RealtimeCapability.Reason.TEMPORARY_ERROR -> R.string.workspace_voice_live_temporary_error
    RealtimeCapability.Reason.ACTIVE_CALL -> R.string.workspace_voice_live_active_call
}

private fun transcriptionReasonString(reason: RecordingTranscriptionCapability.Reason): Int = when (reason) {
    RecordingTranscriptionCapability.Reason.SERVICE_NOT_CONFIGURED -> R.string.workspace_voice_transcription_unavailable
    RecordingTranscriptionCapability.Reason.SERVICE_TOKEN_MISSING -> R.string.workspace_voice_transcription_service_token_missing
    RecordingTranscriptionCapability.Reason.TRANSCRIPTION_NOT_CONFIGURED -> R.string.workspace_voice_transcription_not_configured
    RecordingTranscriptionCapability.Reason.API_ACCESS_UNAVAILABLE -> R.string.workspace_voice_transcription_api_unavailable
    RecordingTranscriptionCapability.Reason.DAILY_LIMIT_REACHED -> R.string.workspace_voice_transcription_daily_limit
    RecordingTranscriptionCapability.Reason.TEMPORARY_ERROR -> R.string.workspace_voice_transcription_temporary_error
}

private fun normalizeVoiceServiceUrl(value: String): String = value.trim().trimEnd('/')

private fun isValidVoiceServiceUrl(value: String): Boolean = runCatching {
    if (!FeedLogic.safeUrl(value, httpsOnly = true)) return false
    val uri = URI(value)
    uri.rawQuery == null && uri.rawFragment == null
}.getOrDefault(false)

private fun isValidVoiceServiceToken(value: String): Boolean =
    value.length in 32..4096 && value.none(Char::isWhitespace)
