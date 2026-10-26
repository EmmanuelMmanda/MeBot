# App lock — 0.9.0 implementation and acceptance boundary

Optional local UI protection, disabled by default. The owner chooses a **separate app PIN**
(6–32 ASCII digits), confirms it, and may explicitly enable enrolled strong biometrics after
fresh PIN authentication. Builds/tests do not create an owner PIN or production key.

## Security model

- App lock hides personal UI and disposes sensitive media when MeBot leaves the foreground or
  receives screen-off. `KeyguardManager.isDeviceLocked` is checked before accepting authentication.
  Background refresh is not a provider logout. Nonsecret navigation state survives behind the gate.
- The session starts locked after process death; no `unlocked` flag is persisted. A synchronized
  generation boundary rejects late PIN/biometric results after background, screen-off or reset.
  `FLAG_SECURE` protects recents/screenshots while lock is enabled and while editing a PIN.
  A synchronous neutral native curtain covers the activity on pause, screen-off and resume, before
  lifecycle-aware collection can catch up. Only a committed composition with the current curtain
  epoch, lock generation and actual lock state can remove it. Personal dialog, dropdown-popup and
  bottom-sheet roots explicitly register through public View APIs; they are hidden synchronously,
  excluded from accessibility and guarded before drawing. A prior-generation window cannot reappear
  after a stale commit. The safe lock-screen recovery dialog and independent alarm controls are exempt.
- PIN verifier: fresh 32-byte random salt, PBKDF2-HMAC-SHA256, 600,000 iterations, 256-bit output;
  full result compared using `MessageDigest.isEqual`. KDF work runs off the UI thread. PIN buffers
  are cleared after operations; UI fields are transient, masked and never saved across recreation.
  Android/Compose immutable strings cannot be guaranteed erased from process memory.
- An AES-GCM Android Keystore key, distinct from provider credential keys and APK signing keys,
  protects the entire versioned verifier/throttle record with fixed AAD. Atomic file storage resides
  in credential-protected `noBackupFilesDir`. No security state is a field in feed/context exports.
  Missing/invalid keys and corrupt records fail closed. Setup persists the usable verifier before the
  privacy marker; interrupted setup therefore retains either no lock or a usable PIN. Authenticated
  disable clears the marker, verifies record deletion, then best-effort deletes inert keys.
- Retry admission is durably recorded **before** KDF verification. From the fifth failure, delays
  start at 30 seconds, double to a one-hour ceiling and persist across process restart. Timing uses
  `elapsedRealtime`, not user-adjustable wall time. A different boot count/elapsed rollback restarts
  the full stored delay conservatively. Unknown boot identity is also conservative. This is not a
  hardware-enforced attempt limit; rooted devices or compromised app processes remain out of scope.
- AndroidX `BiometricPrompt` permits `BIOMETRIC_STRONG` only. A separate authentication-bound,
  enrollment-invalidated Keystore AES key performs an operation through the returned CryptoObject;
  a success callback without the matching cipher is insufficient. Cancellation, stale generation,
  key invalidation, unavailable enrollment and device lockout never unlock via a fallback callback.
  The separate app PIN remains the fallback; app-owned UI never requests the device PIN.
- Changing/disabling the PIN and enabling/disabling biometrics require fresh app PIN authentication,
  even in an already-unlocked session. Navigation away cancels pending administrative acceptance.
  No import/restore can disable the separate security store.
- Feed notification previews/images, alarm titles, spoken custom messages and QR checkpoint labels
  are generic whenever app lock is enabled. A non-personal enabled marker in device-protected storage
  allows direct-boot alarm privacy without opening personal data. Before first device unlock, labels
  are conservatively generic even when that marker is absent (including interrupted initial setup). Stop, snooze and challenge controls
  remain operational; returning to the app still crosses the lock gate.

## What this does not protect

This is **not encryption of the existing feed database**, conversations or exported backups.
Existing Android app-private storage and device encryption still provide that boundary. This does
not change the device screen-lock timeout or dismiss Android keyguard. It is not protection from
rooted-device attackers, process compromise or data already exported/shared externally.

No silent recovery bypass, automatic wipe or remote recovery service exists. Enrolled, previously
enabled biometrics can unlock if still valid. If neither works, the UI explains Android's explicit
Clear storage action as a destructive last resort: local data, settings, alarms and credentials are
lost; remote provider data is not erased. Export first if authentication remains possible.

## Verification status and required device acceptance

Host tests exercise fake storage and real PIN KDF, synthetic-key AES-GCM file round trips/tampering,
shared transaction-helper fault injection (not real power-loss/Keystore failure), persistent/reboot/rollback delays, corruption,
current-PIN administrative checks, stale biometric tickets, PIN-worker screen-off races, cancellation,
notification suppression and native Compose rendering/restoration plus pause/resume PIN clearing.
Native curtain tests deliberately omit a UI collector and verify current/stale epochs, actual Compose
context/media dialogs and dropdown popups; physical compositor behavior still needs device acceptance. They do **not** authenticate real
biometrics or demonstrate hardware-backed key protection. No production key is generated in tests.

Physical API 26 and representative current Android acceptance is still required: actual strong
biometric enrollment/invalidation/cancellation, screen-off and Android delayed-lock behavior,
rotation/process death, notification/deep-link cold launch, recents privacy, PDF/video disposal and
resume, safe ringing/snooze/emergency stop/direct boot, and low-end device KDF latency. No phone
unlock-speed, battery or universal biometric compatibility claim is made from host results.

Primary Android references reviewed 2026-10-25:
- https://developer.android.com/identity/sign-in/biometric-auth
- https://developer.android.com/privacy-and-security/keystore
- https://developer.android.com/reference/android/app/KeyguardManager
