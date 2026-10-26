package mebot.nguli.com

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import mebot.nguli.com.alarm.AlarmAudioAccess
import mebot.nguli.com.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID
import java.io.ByteArrayOutputStream

data class AppUiState(val state: AppState? = null, val failed: Boolean = false)

class MeBotViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as MeBotApplication
    private val repository = app.repository
    val alarms = app.alarms
    private val _ui = MutableStateFlow(AppUiState())
    val ui = _ui.asStateFlow()
    private val _message = MutableStateFlow<Int?>(null)
    val message = _message.asStateFlow()
    private val alarmChanges = Mutex()
    private val backupMutationEpoch = java.util.concurrent.atomic.AtomicLong(0)

    init {
        viewModelScope.launch {
            try {
                repository.state.collect { state ->
                    _ui.value = AppUiState(state)
                    alarms.setLanguage(state.settings.language)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { _ui.value = AppUiState(failed = true) }
        }
    }
    fun clearMessage() { _message.value = null }
    fun showMessage(id: Int) { _message.value = id }
    private fun work(error: Int = R.string.operation_error, action: suspend () -> Unit) {
        viewModelScope.launch {
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { showMessage(error) }
        }
    }

    private suspend fun reconcileAlarms(state: AppState, targetAlarmId: String? = null): Boolean = withContext(Dispatchers.IO) {
        val reconciled = try {
            alarms.setLanguage(state.settings.language)
            if ("alarms" in state.settings.enabledModules) {
                alarms.sync(state.alarms).none { it.error != null && (targetAlarmId == null || it.alarmId == targetAlarmId) }
            } else {
                alarms.cancelAll()
                true
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { false }
        // Keep selected-file access until the persisted plans and active runtime no longer need it.
        // Cleanup is best-effort and cannot turn a successful save into a scheduling failure.
        AlarmAudioAccess.prune(app, state.alarms)
        reconciled
    }

    fun onResume() = work(R.string.alarm_error) {
        alarmChanges.withLock {
            val consumed = alarms.completedOneTimeIds()
            val updated = repository.update { state ->
                state.copy(alarms = state.alarms.map {
                    if (it.id in consumed && it.days.isEmpty()) it.copy(enabled = false) else it
                })
            }
            // Consumed one-time occurrences may still be ringing or queued. This is not a user cancellation.
            if (!reconcileAlarms(updated)) showMessage(R.string.alarm_error)
        }
    }

    fun saveProfile(name: String, intention: String, completed: Boolean = true) = work {
        repository.update { it.copy(profile = Profile(name.trim().take(80), intention.trim().take(500), completed)) }
    }
    fun finishOnboarding(onFinished: () -> Unit) = work {
        repository.update { it.copy(profile = it.profile.copy(onboardingComplete = true)) }
        onFinished()
    }
    fun saveSettings(settings: Settings) = saveSettings { settings }
    fun saveSettings(transform: (Settings) -> Settings) = work {
        alarmChanges.withLock {
            val updated = repository.update { it.copy(settings = transform(it.settings)) }
            if (!reconcileAlarms(updated)) showMessage(R.string.alarm_error)
        }
    }
    fun saveDraft(visit: DailyVisit) = work {
        repository.update { DailyLogic.upsertVisit(it, visit) }
    }
    fun finishVisit(visit: DailyVisit, onFinished: () -> Unit) = work(R.string.validation_error) {
        val finished = DailyLogic.completeVisit(visit)
        repository.update { DailyLogic.upsertVisit(it, finished) }
        showMessage(R.string.plan_saved)
        onFinished()
    }
    fun toggleOutcome(visitId: String, outcomeId: String) = work {
        repository.update { state -> state.copy(visits = state.visits.map { visit ->
            if (visit.id != visitId) visit else visit.copy(outcomes = visit.outcomes.map { if (it.id == outcomeId) it.copy(done = !it.done) else it })
        }) }
    }
    fun startFocus(title: String, minutes: Int, onStarted: () -> Unit = {}) = work {
        val focus = FocusLogic.start(title.trim(), minutes)
        val updated = repository.update { if (it.focus == null) it.copy(focus = focus) else it }
        if (updated.focus?.id != focus.id) showMessage(R.string.focus_existing)
        onStarted()
    }
    fun pauseFocus() = work { repository.update { it.copy(focus = it.focus?.let { f -> FocusLogic.pause(f) }) } }
    fun resumeFocus() = work { repository.update { it.copy(focus = it.focus?.let { f -> FocusLogic.resume(f) }) } }
    fun endFocus() = work { repository.update { it.copy(focus = null) } }
    fun completeFocus() = work {
        var recorded = false
        repository.update { s ->
            val focus = s.focus ?: return@update s
            val record = FocusLogic.complete(focus)
            if (s.focusHistory.any { it.id == record.id }) s.copy(focus = null)
            else {
                require(s.focusHistory.size < AppLimits.FOCUS_RECORDS)
                recorded = true
                s.copy(focus = null, focusHistory = s.focusHistory + record)
            }
        }
        if (recorded) showMessage(R.string.focus_saved)
    }
    fun saveAlarm(plan: AlarmPlan, onSaved: () -> Unit) = work(R.string.alarm_error) {
        alarmChanges.withLock {
            if (plan.enabled && !alarms.readiness().exactAllowed) { showMessage(R.string.permission_alarm_needed); return@withLock }
            if (plan.effectiveDismissMode == AlarmDismissMode.QR && plan.checkpointCode.isBlank()) { showMessage(R.string.checkpoint_needed); return@withLock }
            StateValidation.alarm(plan)
            if (!plan.enabled) withContext(Dispatchers.IO) { alarms.cancel(plan.id) }
            val updated = repository.update {
                val plans = it.alarms.filterNot { a -> a.id == plan.id } + plan
                require(plans.size <= AppLimits.ALARMS)
                it.copy(alarms = plans)
            }
            if (!reconcileAlarms(updated, plan.id)) { showMessage(R.string.alarm_error); return@withLock }
            showMessage(R.string.alarm_saved)
            onSaved()
        }
    }
    fun deleteAlarm(id: String) = work {
        alarmChanges.withLock {
            withContext(Dispatchers.IO) { alarms.cancel(id) }
            val updated = repository.update { it.copy(alarms = it.alarms.filterNot { a -> a.id == id }) }
            withContext(Dispatchers.IO) { AlarmAudioAccess.prune(app, updated.alarms) }
            showMessage(R.string.alarm_deleted)
        }
    }
    fun testAlarm(plan: AlarmPlan) = work(R.string.alarm_error) {
        alarmChanges.withLock {
            if (!alarms.readiness().exactAllowed) { showMessage(R.string.permission_alarm_needed); return@withLock }
            if (plan.effectiveDismissMode == AlarmDismissMode.QR && plan.checkpointCode.isBlank()) { showMessage(R.string.checkpoint_needed); return@withLock }
            StateValidation.alarm(plan)
            val result = withContext(Dispatchers.IO) { alarms.testAlarm(plan, 10) }
            showMessage(if (result.error == null && result.scheduledAtMillis != null) R.string.test_scheduled else R.string.alarm_error)
        }
    }
    fun createCheckpoint(label: String) = work {
        if (label.isBlank()) { showMessage(R.string.validation_error); return@work }
        repository.update { state -> state.copy(settings = state.settings.copy(
            checkpointLabel = label.trim().take(80),
            checkpointCode = state.settings.checkpointCode.ifBlank { "mebot:checkpoint:${UUID.randomUUID()}" }
        )) }
    }
    fun exportCheckpoint(uri: Uri, expectedIdentity: String?) = work(R.string.file_error) {
        val code = repository.current().settings.checkpointCode
        require(code.isNotBlank() && mebot.nguli.com.documents.checkpointDocumentIdentity(code) == expectedIdentity)
        withContext(Dispatchers.IO) {
            val bitmap = mebot.nguli.com.ui.checkpointBitmap(code)
            try {
                app.contentResolver.openOutputStream(uri, "wt")?.use {
                    check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
                } ?: error("Output unavailable")
            } finally { bitmap.recycle() }
        }
        showMessage(R.string.checkpoint_saved)
    }
    fun exportBackup(uri: Uri) = work(R.string.file_error) {
        val text = repository.export()
        withContext(Dispatchers.IO) { app.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) } ?: error("No output") }
        showMessage(R.string.export_success)
    }
    fun importBackup(uri: Uri): kotlinx.coroutines.Job {
        val expectedEpoch = backupMutationEpoch.incrementAndGet()
        return viewModelScope.launch {
        var committed = false
        try {
            val text = withContext(Dispatchers.IO) {
                app.contentResolver.openInputStream(uri)?.use { stream ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    var count: Int
                    while (stream.read(buffer).also { count = it } != -1) {
                        require(output.size() + count <= AppLimits.BACKUP_BYTES)
                        output.write(buffer, 0, count)
                    }
                    output.toString("UTF-8")
                } ?: error("No input")
            }
            val candidate = BackupCodec.decode(text).let { decoded ->
                decoded.copy(alarms = decoded.alarms.map { it.copy(enabled = false) }, focus = null)
            }
            alarmChanges.withLock {
                // Erase or a newer import invalidates reads that started against an older state.
                if (backupMutationEpoch.get() != expectedEpoch) return@launch
                // Decode and validate the whole candidate before replacing data or cancelling old alarms.
                val applied = commitBackupImport(candidate, expectedEpoch, backupMutationEpoch::get,
                    update = { transform -> repository.update(transform) },
                    reconcileCommitted = {
                        committed = true
                        withContext(Dispatchers.IO) { alarms.clearAll() }
                        check(reconcileAlarms(candidate))
                    })
                if (!applied) return@launch
            }
            if (backupMutationEpoch.get() == expectedEpoch) showMessage(R.string.import_success)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            if (backupMutationEpoch.get() == expectedEpoch)
                showMessage(if (committed) R.string.secondary_import_alarm_error else R.string.import_error)
        }
        }
    }
    fun erase() {
        backupMutationEpoch.incrementAndGet()
        work {
        alarmChanges.withLock {
            withContext(Dispatchers.IO) { alarms.clearAll() }
            val updated = repository.erase()
            withContext(Dispatchers.IO) {
                AlarmAudioAccess.forgetDrafts(app)
                AlarmAudioAccess.prune(app, updated.alarms)
            }
        }
        }
    }
}
