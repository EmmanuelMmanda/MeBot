# MeBot 0.9.0 candidate

This is a local candidate, not a published official release. Package
`mebot.nguli.com`, version code 17. The official download remains 0.8.2.
Signing custody, final review and physical-device acceptance remain separate
release gates. Do not uninstall an existing installation to try an unsigned file.

## What changes

- **Today:** a cached daily filter over enabled sources, ranked with existing
  explicit interests, saved/hidden choices and optional local reading history.
  Item identities, original modules and scoped notes stay unchanged. Opening
  Today or revealing more cached entries makes no additional inference request.
  A deliberate pull-to-refresh retains the existing refresh/budget behavior.
- **Evidence-based dates:** publisher publication, explicitly verified substantive
  updates, forecast validity or source-backed event dates can qualify. Unknown
  dates and stale articles do not become today's news simply because fetched
  today. The configured timezone controls midnight, including DST boundaries.
- **Compact interests:** labeled icon choices retain accessible touch targets and
  English/Kiswahili support. Typed onboarding interests reuse the existing scoped
  note; an absent/blank revisit draft does not delete it. Intentional deletion is
  available in the context editor.
- **Optional app lock:** choose a separate app PIN under You → App lock. Supported
  strong biometrics are optional after PIN authentication. The app locks when
  leaving the foreground or when the screen turns off; it checks device lock on
  resume. This does not change the phone's lock timeout or request its device PIN.
- **Measured engine work:** reuse prepared ranking inputs rather than repeating
  context tokenization and history scans for every candidate. Small editions keep
  the original path where snapshot overhead was measured to outweigh savings.

## Today source coverage

RSS/Atom publication fields and public repository creation dates are source
evidence. Atom `updated` alone and generic page `dateModified` do not prove a
substantive update. Open-Meteo forecasts require a provider validity interval and
recent source verification. The existing bounded publisher-image HTML request
also extracts explicit publication metadata, even when no image is present.
It does not add requests or broaden the existing candidate selection. Items
already carrying images may therefore retain unknown dates until separately
verified. Unrelated structured articles embedded on a page are rejected.

AI/service output cannot self-assert verified publisher metadata. Legacy dates
remain readable in the regular feed but do not automatically qualify for Today.
No production adapter currently claims to verify substantive edits or event
dates merely from AI text. Those typed cases remain conservative until actual
source evidence exists. Empty/offline Today points back to the regular feed.

## Privacy and recovery

No new telemetry, private connector, dwell tracking or engagement upload is
introduced. Existing local reading history can be disabled/cleared in Your data;
this does not delete notes or saved items. Existing explicit relevance votes
retain their previously disclosed module-scoped provider behavior.

App lock gates the interface; it **does not encrypt the feed database**, exported
backups or existing conversations. Android app-private storage and OS protections
still apply. The salted slow PIN verifier is encrypted using a separate Android
Keystore key; persisted retry delays survive process restart and wall-clock
changes. This is not a hardware-enforced attempt counter. PINs/security records
are excluded from app backup and manual feed exports/imports cannot disable a
configured lock. No unlocked session is persisted across process death.

Forgotten PIN is not silently bypassed and failed attempts never automatically
erase data. Recovery guidance distinguishes valid enrolled biometrics from an
explicit Android app-data reset, which loses local data and connections and does
not erase remote provider data. Alarm stop/snooze remain available with generic
protected labels; personal navigation still requires unlocking.

## Verification status

**953 local tests passed** (198 core, 575 Android host, 180 service); lint has no errors
(230 debug / 230 release warnings, two hints each). Unsigned non-debug release assembly
and alignment passed. See [acceptance tracker](RELEASE-TRACKER-0.9.0.md). Host Kotlin timings describe
ranking/serialization only, not phone startup, frame timing, battery or network
provider speed. Physical PIN/biometric hardware, enrollment changes, lock-screen
and recents behavior, native PDF decoding and live streaming must still be
verified on actual Android devices. A passing host test or assembled APK does not
prove these device behaviors.

### Source references

- [Android biometric authentication](https://developer.android.com/identity/sign-in/biometric-auth)
- [Android Keystore](https://developer.android.com/privacy-and-security/keystore)
- [Android benchmark guidance](https://developer.android.com/topic/performance/benchmarking/benchmarking-in-ci)
- [Open-Meteo forecast API](https://open-meteo.com/en/docs)
