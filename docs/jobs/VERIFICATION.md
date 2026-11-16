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
