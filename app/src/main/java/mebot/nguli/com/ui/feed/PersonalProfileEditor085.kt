package mebot.nguli.com.ui.feed

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import mebot.nguli.com.R
import mebot.nguli.com.core.PersonalProfile085

@Composable
internal fun PersonalProfileEditor085(text: String, enabled: Boolean, onChange: (String) -> Unit,
    tag: String = "context-edit", onboarding: Boolean = false, autosave: Boolean = false) {
    var showExample by rememberSaveable { mutableStateOf(false) }
    var replace by rememberSaveable { mutableStateOf(false) }
    val example = stringResource(R.string.profile_example_085)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row {
            Text(stringResource(if (onboarding) R.string.profile_custom_hint_085 else R.string.profile_guide_085),
                modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            IconButton(onClick = { showExample = true }, enabled = enabled, modifier = Modifier.testTag("$tag-example")) {
                Icon(Icons.Outlined.HelpOutline, stringResource(R.string.profile_example_help_085))
            }
        }
        OutlinedTextField(text, onChange, enabled = enabled, minLines = 4, maxLines = 8,
            label = { Text(stringResource(if (onboarding) R.string.profile_custom_interest_085 else R.string.profile_label_085)) },
            isError = !PersonalProfile085.valid(text), modifier = Modifier.fillMaxWidth().testTag(tag),
            supportingText = { Text(stringResource(R.string.profile_count_085, PersonalProfile085.words(text), text.length)) })
        if (!PersonalProfile085.valid(text)) Text(stringResource(R.string.profile_limit_085), color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag("$tag-limit"))
    }
    if (showExample) AlertDialog(onDismissRequest = { showExample = false },
        title = { Text(stringResource(R.string.profile_example_title_085)) }, text = { Text(example, modifier = Modifier.heightIn(max = 200.dp).verticalScroll(rememberScrollState()).testTag("$tag-example-text")) },
        confirmButton = { mebot.nguli.com.security.ProtectedWindowRegistration(); TextButton(onClick = {
            showExample = false
            if (text.isNotBlank()) replace = true else onChange(example)
        }, enabled = enabled, modifier = Modifier.testTag("$tag-insert-example")) { Text(stringResource(R.string.profile_insert_example_085)) } },
        dismissButton = { TextButton(onClick = { showExample = false }, modifier = Modifier.testTag("$tag-dismiss-example")) { Text(stringResource(R.string.profile_cancel_085)) } })
    if (replace) AlertDialog(onDismissRequest = { replace = false },
        title = { Text(stringResource(R.string.profile_replace_title_085)) }, text = {
            Text(stringResource(if (autosave) R.string.profile_replace_body_099_autosave else R.string.profile_replace_body_085),
                modifier = Modifier.heightIn(max = 200.dp).verticalScroll(rememberScrollState()))
        },
        confirmButton = { mebot.nguli.com.security.ProtectedWindowRegistration(); TextButton(onClick = { replace = false; onChange(example) }, enabled = enabled,
            modifier = Modifier.testTag("$tag-confirm-example")) { Text(stringResource(R.string.profile_replace_085)) } },
        dismissButton = { TextButton(onClick = { replace = false }, modifier = Modifier.testTag("$tag-cancel-example")) { Text(stringResource(R.string.profile_cancel_085)) } })
}
