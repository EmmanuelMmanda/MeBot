# MeBot 0.9.8 verification

## Identity and publication status

Prepublication evidence. Final local and PR hosted qualification and corrected-byte signing have passed; publication, anonymous download/updater verification and website promotion remain pending. An earlier unpublished signed draft does not contain this persistence correction and must not be published.

- Application `mebot.nguli.com`, version `0.9.8`, code `25`, minimum API26, target API36.
- Public source commit `b081584d68413bcda330f23d760474871ea705de`; tree `f7f98aaab2b754931dde9ad1c962fdf9b0540bc1`.
- Local archive commit `d59a303e6ec22b818b59c0cb4fa9d7d49686e3da` has the identical tree. Git archive exports `gradlew.bat` as CRLF according to `.gitattributes`; normalized bytes match the tracked blob.
- Source ZIP:800 tracked files,25,161,324 bytes, SHA-256 `6ff3ce4081afb822a37dc94c7a34cdc78db4ea51b1760893fe198701b03b2f1b`.
- Corrected unsigned APK:17,731,844 bytes, SHA-256 `5878ea579f070aee46b855225fb34ffe815b166bb6125accfea5fc9b4dd9705a`.

- Corrected signed APK:17,785,105 bytes, SHA-256 `3ba3610d50c94e184c3e0e47c97b672a37464243a40bac2842d5244660538fde`. Existing certificate SHA-256 `c665c578ecf64f33541670d94f099ee435db1e55790395ff0339f2bdf231d007`; v2/v3 signatures valid, non-debug/non-test-only. All eight packaged native libraries have16KiB ELF LOAD alignment; ZIP alignment passes.

## Corrected qualification

Final local source passed760 Android and245 core tests, with zero failures/errors/skips, plus debug/release lint and debug/release assembly. Debug lint reported276 warnings and2 hints; release lint277 warnings and3 hints, no errors. Hosted PR21 run40661981925 passed760 Android +245 core tests and204 service tests, with zero failures/errors/skips. All five checks passed on public source b081584d. Tested merge ref `39c6d59150e663bd25017ad82c9867514b5d4646` has the identical source tree. PR21 merged as `9724be4a8e20862f9885a2c271b07eece777b397`, also the identical tree; merged-head run40663223682 passed all required checks. Downloaded artifact12805790001 (SHA-256 `5561397def02669a50a4ee94547a32097bb65bf92da96ed1f64b7f448c0ec198`) independently confirms760 Android and245 core tests with zero failures/errors/skips; service204, release lint and assembly also passed. The frozen release target branch points to corrected source b081584d, not the earlier unpublished runtime.

Resolved DataStore modules are stable1.3.0, coroutines1.10.2 and Kotlin stdlib2.2.21. DataStore's minimum API24 remains below the app minimum26. All four packaged DataStore native ABIs have ELF LOAD alignment0x4000; both APKs also pass16KiB ZIP alignment. No storage schema, migration, credential or permission change was introduced.

A deterministic Apache-attributed regression adapted from AndroidX commit `1ef22b89a34bd4027d7c50684101a62daf27b07b` fails on1.1.7 and passes unchanged on1.3.0: a cold collector opened during a paused write previously missed the committed value. Official issue431787506 documents the old-data/new-version race and its correction. This establishes the library defect; it does not prove every prior UI/PDF test failure shared that cause.

Compatibility evidence uses actual1.1.7 FeedRepository and AppRepository writes of synthetic state. Both writer scopes are canceled/joined before export. The checked-in fixtures are pinned by SHA-256; the1.3.0 test reads them with the real repositories, preserves saved stories, Original Read sections, context/conversation and language/theme/preferences, updates both stores, closes/reopens them and verifies the checked old and new values. Writer and reader focused tests passed; the reader is included in the final760-test Android gate.

## Retained hosted history

- Run40637734282 reached its30-minute limit without final Android results; not a pass.
- Run40641352020 completed with three failures. The sheet-budget fixture was waiting before an explicit Continue-triggered refresh completed; the fixture now waits for real completion before the unchanged no-inference/budget assertions. Cancellation waits retained exact predicates/deadlines and gained bounded diagnostics.
- Run40643458885 first attempt had a PDF Compose test-clock/measure-layout exception at the first125% zoom assertion. It did not reproduce locally. No PDF runtime workaround, skipped assertion or timeout increase was introduced. The one same-head retry stalled in a synchronous test fixture DataStore write on test Main; a separate PR20 run exposed the same pattern in an offline fixture. Two traced writes and two narrowly reviewed startup-refresh analogues now use bounded asynchronous writes and real refresh barriers; values and all product assertions remain.
- Run40651382396 passed those UI paths but timed out in three VM Flow waits. A test-only IO observation helper preserved exact predicates; local and PR run40654145561 passed, but identical-tree merged run40655113784 still failed two waits. Therefore the helper was not claimed as a causal fix.
- Isolated diagnostic run40657841627 ran only the two complete VM classes,17/18 passed. Traces showed healthy emissions in the two instrumented cases while a sibling cold-flow wait failed. This was diagnosis, not release qualification. The diagnostic-only reflection instrumentation and workflow are excluded from final source.
- Inspection then identified the upstream DataStore race; deterministic old-version failure/new-stable-version success supports the production dependency correction above. Earlier signed draft bytes and prior green source trees are not presented as this corrected build.

## Safety and limits

The source archive has no signing private key/password, provider credential, owner-personalized JSON, local application state or build output. Compatibility JSON is explicitly synthetic and has blank account/service bindings. The included signing certificate is public; example configuration contains no live credentials.

Suggestions contain only provider-authored sets of at least five validated distinct questions; incomplete/unavailable sets are not padded with local templates. Public feed reading remains usable without AI. Explicit Send and pencil Edit are distinct. Pending unsent attachments are not silently dispatched, and scope/context/cancellation fences remain tested.

Original Reads distinguish AI authorship from publisher/API or source-grounded acquisition. Unknown publication dates remain unknown; personal daily selection does not imply publication today. Key Points retain readable prose and safe secondary source links.

Host/Robolectric and fake-provider tests do not establish physical-phone/OEM dialogs, live paid-provider limits, overnight reliability or universal process-kill behavior. No tests were skipped to obtain qualification. The separate bilingual showcase uses host-debug renders and curated verified public-source fixtures, not live provider/physical-device evidence. Its previously reviewed PR20 website passed107 hosted browser scenarios plus manual PC inspection; final website promotion and current-release parity remain separate gates.
