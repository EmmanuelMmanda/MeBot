# Jobs release verification

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

## Final release: v0.10.0 (2026-11-16)

- Tested source: `63e61dd8d8e149eb16c8c9428f5598e34822be9d`; tree `0d602cc86b1ee5c2ffcbd31bf2dbb1dc7a40422f`. The CI merge and merged develop trees were checked equal. PR #30 was merged without changing main.
- [Exact-source CI](https://github.com/EmmanuelMmanda/MeBot/actions/runs/43874007322) passed: 279 core tests and 927 Android tests, zero failures/errors/skips; release assembly/lint, service, docs and browser verification. PC debug assembly/lint also passed. Release lint has zero errors and 318 warnings; no broad lint suppression was introduced.
- The last narrow-screen test correction awaited the rendered Disabled-filter update after durable storage acknowledgement; all prior persistence, visibility and selection assertions remain.
- Unsigned CI APK SHA-256: `4040e7a8a6f816b0a153a1c88e8fdbf2a26a1a6293253ba5a167f7de992b99ac` (20,715,074 bytes).
- Signed APK SHA-256: `cdb04a739ca0949d71b4f7e2e1bb4eb395cf0a1652851cc4678c6be5bebaa494` (20,721,294 bytes). Existing certificate: `c665c578ecf64f33541670d94f099ee435db1e55790395ff0339f2bdf231d007`. Package `mebot.nguli.com`, version 0.10.0/code 27, minimum SDK 26/target 36. V2/V3 signatures and 16 KB alignment were independently checked; all application ZIP entry bytes match the tested unsigned APK. No private key left its existing custody.
- Actual Android offline refresh preserved cards, showed queued state, and cancelled visibly. Preference rotation/save/reopen, saved notes/drafts, and PDF export were verified without AI.
- After a verified synthetic backup, the full app erase returned Jobs to blank onboarding. Import restored the saved job, application status, exact note/draft and role/skill/country preferences. Alerts were visibly off. Only isolated test emulators were used.
- Official signed 0.9.9/code 26 upgraded to the final signed 0.10.0/code 27 with no uninstall/data clear between versions; synthetic profile/context remained. A separate fresh code-26 emulator discovered the public release and completed the real download to “Verified and ready to install”. It remained on code 26; no unknown-app security setting was changed.
- [Public release](https://github.com/EmmanuelMmanda/MeBot/releases/tag/v0.10.0) is non-draft/non-prerelease, latest, and its tag resolves to the tested source. Independent anonymous downloads returned HTTP 200 with the exact signed APK digest and metadata digest `718803d0f8db42f13758a5a4e685108837c77ff7c0b30ca5a1cc93794e933c15`.
- Scope limits remain explicit: remote feeds are not comprehensive local coverage; external Tanzania sources are not ingested; no PDF CV parsing, automatic application submission or durable background AI queue. Live paid-provider acceptance and overnight notification/alarm delivery are not claimed.

This final section is a documentation-only follow-up. It does not change the immutable release source, tag or APK.
