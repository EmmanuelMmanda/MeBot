# Jobs candidate verification

This is a verification log, not a release announcement.

## First candidate: 8c827197

- Core: 270 tests passed on the desktop build.
- Android Kotlin: debug and release compilation passed on the desktop build.
- Linux CI: 900 app tests ran; 13 failed. Six dedicated Jobs screen tests passed. The PDF export test failed because the Robolectric native `PdfDocument` handle was zero; the exporter is being replaced with a portable implementation and real parser/extraction tests, not an ignored test.
- Other Linux failures were existing navigation journeys affected by moving Modules to Feed → All modules, scrolling/pointer synchronization, and a fixed deadline that had elapsed. Their destination, persisted-state and pointer-reachability assertions are retained in the next candidate.
- Service verification and documentation checks passed in CI.
- Website browser installation failed because the pinned Playwright version does not support the new Ubuntu 26.04 runner. The website workflow is now pinned to supported Ubuntu 24.04, retaining all checks.
- The Windows full suite stalled and later targeted runs encountered a full C: volume. Those later runtime-load failures are not valid acceptance results. Task-only build/cache/emulator staging is being moved to D:; no unrelated user files are deleted.

## Follow-up candidate gates

Required before release:

1. Compile, core tests, all app tests, lint and release assembly for the exact final source.
2. Jobs save acknowledgement, idempotent bookmark, serialized preferences, interrupted export and backup-journal recovery tests.
3. Durable refresh uniqueness, network waiting, cancellation and bounded retry tests; no real paid AI calls.
4. PDF parser, Unicode/last-page content and unsupported-glyph tests; render exported synthetic PDFs and inspect them.
5. Actual Android renders and interrupted journeys: onboarding, browse/save/detail, notes, review/export, no-AI mode and refresh queue.
6. Verify APK package, increasing version code, existing signing certificate, size/hash and updater metadata, then anonymous public downloads.

No new Jobs screenshots or completed production release are claimed by the historical website showcase checks.

## State, PDF and device evidence (454e9c5)

- Exact candidate desktop core: 279/279 passed; focused Android suite: 54/54 passed. Debug and release assembly and strict release lint passed (zero errors). The unsigned package has version 0.10.0/code 27, minimum SDK 26 and target SDK 36; 16 KB alignment passed. These focused results do not replace the full-suite gate.
- The unsigned release is 20,715,066 bytes, approximately 2.46 MB larger than the previous signed release. Signing, updater metadata and production in-place migration remain separate gates.
- An isolated Android emulator, not the user's phone, verified real public jobs without AI, saved records, persisted notes and drafts, review-gated PDF export through Android's document picker, and preference rotation/save/reopen. A pulled synthetic PDF was parsed, text-checked and visually rendered. The font has explicit unsupported-character handling; no PDF CV import is claimed.
- Existing Feed → All modules → Focus, Daily check-in and Alarms work by real taps. Focus also opens with a partially clipped card at the exact 822×1782/320 dpi test geometry. Screenshots and synthetic data were retained. No alarm was created by this check.

## Full-suite navigation diagnosis (c5322e2 and d415613)

- c5322e2 ran 925 Android tests; eight existing utility journeys failed. Jobs, the scoped AI/PDF/state tests, the ChatGPT rotation journey and disconnected feed fixture passed. Service, docs and website checks also passed.
- Two earlier test races were corrected by awaiting durable suggestion cache completion and draining Compose Main after an asynchronous fixture commit. No provider-budget, rotation, stale-target or persisted-state assertion was removed or relaxed.
- The utility failure was reproduced in isolation. In d415613 a temporary DEBUG-only route trace showed that semantic scrolling followed by a pointer tap never invoked the Activity utility callback. A separate test of the same Activity using real swipe gestures and a real tap invoked the callback and opened Focus. The native failure screenshot showed the expected row, not a blocking overlay. An isolated catalog component passed its pointer callbacks even at the same geometry.
- The utility journey helper is therefore changed to bounded real swipe gestures and a real pointer tap, preserving user reachability and every existing behavioral assertion. It is not replaced with a semantic click action. Temporary production tracing has been removed. The component semantic-scroll and real-gesture regression tests remain.
- The strict full suite and final exact-source device/signing/updater checks are still required. No release is approved by this log.
