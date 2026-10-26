# 0.9.3 repair acceptance (in progress)

The owner reports that 0.9.2 closes when choosing a context JSON file and when enabling update notifications. 0.9.2 public release and promotion are held. A passing mocked ActivityResultRegistry test is not evidence that these real Android transitions work.

## Scope
- Reproduce actual MainActivity document and notification launches, pause/stop, saved-state parceling, recreation, return/cancel and lock boundaries.
- Repair demonstrated causes without disabling app lock or losing notes, pending drafts, or existing picker features.
- Keep the existing design. Distinguish background refresh from notifications with concise English/Kiswahili labels.
- Version 0.9.3, code 20; reuse the existing production signer only after the final gate.

## Evidence still required
- Root cause and before/after regression, including actual Android platform execution where available.
- Picker selection/cancel/retry, notification grant/deny/retry, default lock off and enabled lock boundaries.
- All relevant host tests, lint and production assembly; signatures/package/checksums verified separately.
- Normal rendered screens and honest emulator versus owner-device limitations.

The prior public CI reset test timed out once, then passed with diagnostic-only assertions. Its intermittent cause is not established. This does not negate the owner's separate launch-crash report.

## Reproduced cause
The exact signed 0.9.0, 0.9.1 and 0.9.2 APKs contain Fragment 1.2.5, brought transitively by Biometric 1.1.0, together with Activity 1.12.2. Moving MainActivity to FragmentActivity for biometrics exposed the old Fragment request-code restriction. Both document and permission launch paths throw `IllegalArgumentException: Can only use lower 16 bits for requestCode` before Android can show the requested UI.

Four new tests using the actual MainActivity and its real ActivityResultRegistry reproduce the exception for both triggers with app lock off and on. The previous document tests substituted the registry launch callback and missed this integration boundary. These are host Android-runtime tests, not yet a physical-phone pass.

The bounded repair explicitly declares Fragment 1.8.9; it does not truncate request codes, swallow the incompatibility, remove biometrics, or disable app lock. Android documents the old incompatibility in its [Fragment release notes](https://developer.android.com/jetpack/androidx/releases/fragment#1.3.0). Patched validation is still in progress.
