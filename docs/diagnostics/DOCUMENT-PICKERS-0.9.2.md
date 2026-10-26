# Document import/export reliability — 0.9.2

## Findings and limits

The 0.9.1 context, feed backup, and local utility backup screens called Android document launchers without handling launch failure. A missing/disabled picker or a provider/security rejection can therefore throw on the UI thread before the existing ViewModel I/O error handling runs. The new regression test exercises the same unguarded AndroidX launcher shape with a synthetic `ActivityNotFoundException`, then verifies guarded retry behavior.

No crash trace from the reported phone is available. This is a concrete source-level crash path, not proof that the phone had a missing document provider. We cannot promise that an application will never crash. Real OEM document providers, process termination, and physical device lock/resume still need device verification.

Picker registrations previously lived inside individual screens under the personal UI gate. The existing saveable state holder may restore their keys after unlocking; we do not claim every locked return lost its result. Registrations are now unconditional above that gate, making ownership and deferred dispatch explicit rather than depending on destination composition lifetime.

## Behavior retained

- Context JSON import still requires preview and explicit confirmation, and pasted JSON remains supported.
- Context export, feed backup import/export, and local utility backup import/export remain distinct and call their existing ViewModel methods.
- Existing bounded reads, strict validation, credential exclusions, scoped context rules, backup confirmation, and failure-before-replacement guarantees remain unchanged.
- Only the requested system document picker is used. No broad storage permission, account access, automatic cloud upload, persistent document grant, or app-lock bypass was added.
- Picker launch failures produce the dedicated localized picker-opening error with retry guidance; cancellation does not produce an error or modify data.
- Duplicate taps cannot replace an outstanding operation. Pending operation/URI state survives saved-state recreation. Results received while paused or locked are queued until resumed and currently unlocked.
- Explicit erase and confirmed full backup replacement invalidate older picker operations. An outstanding stale system request drains before another launch; a late result cannot be reassigned to a new operation. Ordinary context imports and account selection do not invalidate unrelated context work. Forgotten-PIN recovery only displays guidance; it does not erase application data.
- Export success is still reported only after the existing write operation completes. A provider failure is an error, not success. A newly created external file can remain empty/partial if its provider fails; existing in-app data is not replaced.
- Pending URIs are not a new durable document library. If Android revokes temporary access after a process/device interruption, existing I/O error handling reports failure and the user can select the file again.

## Verification

New `DocumentActionsTest` covers unguarded baseline exception propagation; handled missing-picker/security/illegal-state failures and retry; cancellation; duplicate taps; locked return; saved-state recreation with queued result; paused return with lock off; invalidated late/queued results and fresh retry after reset.

`PersonalContextImportTest` adds actual synthetic file-URI context/feed exports and unwritable/unreadable paths, checking persisted data and credentials remain intact. Existing context and backup tests remain part of the aggregate gate. Focused host run on 2026-10-26: `DocumentActionsTest` 11/11 passed (08:12:09 UTC), `PersonalContextImportTest` 7/7 passed (08:12:18 UTC). Evidence: `mebot-092-evidence/picker-and-journey-final.log` and JUnit XML. The combined 35-test run still had four separate UI-journey failures under correction by the UI lane; this is not an aggregate release pass. Physical OEM picker/process-death verification remains outstanding.

## Platform references

- [Android Activity Result APIs](https://developer.android.com/training/basics/intents/result): register callbacks unconditionally across recreation; keep additional operation state separately.
- [Android Storage Access Framework](https://developer.android.com/training/data-storage/shared/documents-files): system-selected documents and URI-based access without broad storage permissions.

## Completed document-picker inventory

The stable host owns eight routes: context JSON import/export; feed backup import/export; local utility backup import/export; text/Markdown conversation attachment; checkpoint PNG export. Attachment MIME types remain unchanged. Its launch-time conversation identity is checked against the current ViewModel, including after provider I/O; a changed thread cannot receive the attachment. Checkpoint export saves only a comparison hash in picker state and regenerates its PNG from the unchanged current checkpoint after unlock, never storing Bitmap/image bytes in saved state.

Three specialized paths retain their original contracts through a common deferred-result guard: audio file selection, system ringtone selection, and downloaded-resource save. URI flags are preserved for the audio permission lease. No grant acquisition, draft mutation or file write begins until currently unlocked and resumed. The result guard retains only URI/ringtone URI, flags, result code and owner/generation metadata across gate disposal/recreation; unrelated provider extras are discarded. Stale owner/reset results become cancellation so the existing resource path cleans its prepared temporary file without writing. A resource URL change retains the pending file path until cleanup rather than forgetting ownership. Download cache files abandoned with their destination remain subject to the existing one-hour age cleanup on a subsequent download.

Already-guarded audio/ringtone/resource launch error handling remains in place. The guard also rejects delayed download completion trying to open Files while paused, letting the existing caller report failure and clean its temporary file. Malformed ringtone parcel/type metadata is cancelled instead of throwing on the UI thread. Voice recognition, notification permissions and QR scanning are not document pickers and were not redesigned.

The additional inventory tests are pending the next coordinated gate; the 08:12 results above predate these extensions.

### Inventory verification update

The 08:25 focused gate (`documents-complete-focused.log`) passed all 12 central-host tests, all 8 deferred specialized-result tests, all 10 context/import/export tests (including native PNG output and stale-checkpoint rejection), 5 delayed-attachment-target tests, 2 local-backup reset tests, 6 audio-access tests and 6 resource-downloader tests. Separate UI fixture failures prevented an aggregate pass at that point.

The alarm editor's apparent save failure was independently diagnosed: it passed alone, but its default Android ViewModel factory reused a stale Robolectric Application under mixed test execution. An assertion-only rerun reproduced different test applications receiving the same stale application instance (`audio-identity-proof-xml`). The test now uses an explicit `AndroidViewModelFactory(app)`, matching production, and retains application-identity, restored-draft and durable-data assertions. No production alarm-save behavior was weakened or changed for that test. The release lead owns the final aggregate rerun against this final source.
