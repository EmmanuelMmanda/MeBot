package mebot.nguli.com

import mebot.nguli.com.core.AppState

/** Invoked under the caller's alarm mutation mutex. A durable commit requires its side effects. */
internal suspend fun commitBackupImport(
    candidate: AppState,
    expectedEpoch: Long,
    currentEpoch: () -> Long,
    update: suspend ((AppState) -> AppState) -> AppState,
    reconcileCommitted: suspend () -> Unit,
): Boolean {
    var applied = false
    update { current ->
        if (currentEpoch() == expectedEpoch) {
            applied = true
            candidate
        } else current
    }
    if (!applied) return false
    // A newer failed import may advance the epoch while update is returning after its durable write.
    // Do not leave the old alarm schedule attached to this successfully restored state.
    reconcileCommitted()
    return true
}
