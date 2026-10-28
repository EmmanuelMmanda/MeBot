package mebot.nguli.com.ui.feed

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import mebot.nguli.com.R
import mebot.nguli.com.core.FeedState
import mebot.nguli.com.core.ModuleCatalog
import mebot.nguli.com.core.PersonalProfile085
import mebot.nguli.com.feed.FeedUiState
import mebot.nguli.com.feed.FeedViewModel
import mebot.nguli.com.feed.PersonalInterestsSaveResult099
import mebot.nguli.com.ui.AppCard
import mebot.nguli.com.ui.ScreenContainer
import mebot.nguli.com.ui.ScreenTitle

/** Optional, local-only topic preferences. Opening this screen never mutates the profile. */
@Composable
fun PersonalInterests099Screen(
    ui: FeedUiState,
    vm: FeedViewModel,
    initialText: String?,
    finishRequest: Int,
    onDone: () -> Unit,
) {
    val state = ui.state ?: return
    val presetKinds = remember { ModuleCatalog.presets.map { it.kind }.toSet() }
    val canonicalPresetModules = remember {
        ModuleCatalog.defaults.filter { it.kind in presetKinds }.associate { it.id to it.kind }
    }
    val editSession = remember { vm.beginPersonalInterestsEdit099() }
    val seededKinds = remember(state.modules) {
        canonicalPresetModules.filter { (id, kind) -> state.modules.any { it.id == id && it.kind == kind && it.enabled } }.values.toList()
    }
    var selectedKinds by rememberSaveable { mutableStateOf(seededKinds) }
    val persistedNote = remember(state.context) { state.context.firstOrNull { it.id == INTERESTS_NOTE_ID }?.text.orEmpty() }
    var customText by rememberSaveable { mutableStateOf(initialText ?: persistedNote) }
    var revision by rememberSaveable { mutableLongStateOf(0L) }
    var savedRevision by rememberSaveable { mutableLongStateOf(0L) }
    var saving by rememberSaveable { mutableStateOf(false) }
    var saveFailed by rememberSaveable { mutableStateOf(false) }
    var saveErrorCode by rememberSaveable { mutableStateOf<String?>(null) }
    var finishRequestSeen by remember { mutableIntStateOf(0) }
    var finishing by remember { mutableStateOf(false) }
    var latestJob by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val currentKinds = selectedKinds.filter { it in presetKinds }.toSet()
    val latestState by rememberUpdatedState(ui.state)
    val disposeRevision by rememberUpdatedState(revision)
    val disposeSavedRevision by rememberUpdatedState(savedRevision)
    val disposeKinds by rememberUpdatedState(currentKinds)
    val disposeText by rememberUpdatedState(customText)

    fun submit(kinds: Set<String>, text: String, draftRevision: Long): CompletableDeferred<PersonalInterestsSaveResult099> {
        val completion = CompletableDeferred<PersonalInterestsSaveResult099>()
        saving = true
        saveFailed = false
        saveErrorCode = null
        latestJob = vm.savePersonalInterests099(kinds, text, editSession, draftRevision) { result ->
            if (draftRevision >= savedRevision && result.status == PersonalInterestsSaveResult099.Status.SAVED) {
                savedRevision = draftRevision
                saving = false
                saveFailed = false
            } else if (draftRevision >= revision) {
                saving = false
                saveFailed = result.status != PersonalInterestsSaveResult099.Status.SAVED
                saveErrorCode = result.errorCode
            }
            completion.complete(result)
        }
        return completion
    }

    LaunchedEffect(editSession, revision, selectedKinds, customText) {
        if (revision <= savedRevision || !PersonalProfile085.valid(customText)) return@LaunchedEffect
        delay(AUTOSAVE_DELAY_MILLIS)
        submit(currentKinds, customText, revision)
    }

    suspend fun finishWhenDurable() {
        if (finishing) return
        if (!PersonalProfile085.valid(customText)) {
            saveFailed = true
            saveErrorCode = "invalid_input"
            return
        }
        if (revision == 0L) {
            onDone()
            return
        }
        finishing = true
        val submittedRevision = revision
        val submittedKinds = currentKinds
        val submittedText = customText
        val completed = withTimeoutOrNull(DURABLE_UI_TIMEOUT_MILLIS) {
            val completion = submit(submittedKinds, submittedText, submittedRevision)
            // Joining a waiter is bounded; the durable ViewModel transaction itself is not
            // cancelled if this screen times out or leaves composition during rotation.
            latestJob?.join()
            val result = completion.await()
            val durable = result.status == PersonalInterestsSaveResult099.Status.SAVED &&
                result.state?.let { isExpected099(it, submittedKinds, submittedText) } == true
            if (!durable) false else {
                savedRevision = maxOf(savedRevision, submittedRevision)
                // Wait for the UI collector as well as the repository callback. ContextScreen's
                // three-way draft merge must see the durable note before it is restored.
                snapshotFlow { latestState }.first { it != null && isExpected099(it, submittedKinds, submittedText) } != null
            }
        }
        if (completed == true) {
            saving = false
            saveFailed = false
            onDone()
            return
        }
        finishing = false
        saving = false
        saveFailed = true
    }

    LaunchedEffect(finishRequest) {
        if (finishRequest > finishRequestSeen) {
            finishRequestSeen = finishRequest
            finishWhenDurable()
        }
    }

    DisposableEffect(editSession) {
        onDispose {
            if (disposeRevision > disposeSavedRevision && PersonalProfile085.valid(disposeText)) {
                // Best effort for rotation/process interruption. Navigation itself only completes
                // after finishWhenDurable has observed the durable result.
                vm.savePersonalInterests099(disposeKinds, disposeText, editSession, disposeRevision)
            }
        }
    }

    ScreenContainer(Modifier.testTag("personal-interests-099")) {
        ScreenTitle(stringResource(R.string.personal_interests_title_099), stringResource(R.string.personal_interests_body_099))
        AppCard {
            Icon(Icons.Outlined.Tune, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(4.dp))
            InterestPicker083(selectedKinds, !finishing, onChange = { next ->
                val normalized = next.filter { it in presetKinds }.distinct()
                if (normalized != selectedKinds) {
                    selectedKinds = normalized
                    revision++
                    saveFailed = false
                    saveErrorCode = null
                }
            }, tagPrefix = "personal-interests")
        }
        AppCard {
            Text(stringResource(R.string.personal_interests_note_body_099), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            PersonalProfileEditor085(customText, !finishing, onChange = { next ->
                if (next != customText) {
                    customText = next
                    revision++
                    saveFailed = false
                    saveErrorCode = null
                }
            }, tag = "personal-interests-custom-note", onboarding = true, autosave = true)
            Text(stringResource(R.string.personal_interests_blank_note_099), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (saving) {
            CircularProgressIndicator(modifier = Modifier.testTag("personal-interests-saving"))
            Text(stringResource(R.string.personal_interests_saving_099), style = MaterialTheme.typography.bodySmall)
        } else if (revision > 0 && savedRevision >= revision && !saveFailed) {
            Text(stringResource(R.string.personal_interests_saved_099), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary, modifier = Modifier.testTag("personal-interests-saved"))
        }
        if (saveErrorCode != null) FeedStatus(saveErrorCode!!)
        else if (saveFailed) Text(stringResource(R.string.personal_interests_save_failed_099),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag("personal-interests-save-error"))
        Button(onClick = { scope.launch { finishWhenDurable() } }, enabled = !finishing,
            modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp).testTag("personal-interests-done")) {
            Text(stringResource(R.string.personal_interests_done_099))
        }
    }
}

private fun isExpected099(state: FeedState, kinds: Set<String>, customText: String): Boolean {
    val canonical = ModuleCatalog.defaults.filter { it.kind in ModuleCatalog.presets.map { preset -> preset.kind }.toSet() }
        .associate { it.id to it.kind }
    val enabled = canonical.filter { (id, kind) -> state.modules.any { it.id == id && it.kind == kind && it.enabled } }.values.toSet()
    if (enabled != kinds) return false
    if (customText.isBlank()) return true // A blank draft intentionally leaves a saved note untouched.
    val expectedScopes = kinds - "weather"
    val saved = state.context.firstOrNull { it.id == INTERESTS_NOTE_ID } ?: return false
    return saved.confirmed && saved.text == customText.trim() && saved.scopes == expectedScopes
}

private const val INTERESTS_NOTE_ID = "onboarding-interests"
private const val AUTOSAVE_DELAY_MILLIS = 500L
private const val DURABLE_UI_TIMEOUT_MILLIS = 10_000L
