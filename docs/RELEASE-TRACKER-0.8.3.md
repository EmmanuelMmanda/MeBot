# MeBot 0.8.3 acceptance tracker

Base: official 0.8.2 source (`e4b4485` on develop). Implementation branch: `feature/mebot-0.8.3`. Package stays `mebot.nguli.com`; version code 14. This is a locally verified candidate record, not an official signed release or device-acceptance claim.

| Request | Implementation / gate |
| --- | --- |
| 1. Star and follow stories | Persisted positive follow preference, pause/cadence management, existing refresh budget; retention, isolation, reserved collection names, filter switching and draft restoration tested. |
| 2. Broader interests | Six practical catalog additions; existing IDs and choices preserved, new choices paused on upgrade. |
| 3. Rich conversation and details | Parsed media/document links, resource previews, composer acknowledgement, explicit attachment scope. |
| 4. Saved library | Search, filters and user collections; preserve bookmarks on upgrade and refresh. |
| 5. Module filters | Searchable, grouped, accessible filter controls. |
| 6. Everyday utility | Offline unit-price comparison with exact decimal arithmetic and clear unit/currency limits. |
| 7. Voice / realtime | Phone speech and installed-voice controls; no unsupported realtime subscription claim. Full-duplex provider realtime is not implemented. |
| 8. Spacing | Request allowance and wrapped control spacing. |
| 9. Defaults | Useful reading defaults; preserve prior choices, explicit background/notification consent and finite AI allowance. |
| 10. Personal preferences | Plain-language guided preference entry and scoped module use. |
| 11. Self-guided start | Searchable interests and progressive guidance without mandatory context import. |
| 12. Overflow actions | Useful story save/star/share/open/hide controls. |
| 13. Continuous stories | Bounded auto-load, deduplication, cancellation and manual alternative. |
| 14. Show new items | New item identities must actually appear without replacing content mid-reading. |
| 15. Actual progress | Observed stages, public result counts and received character counts; no simulated reasoning. |
| 16. In-app browsing | Browser-owned Custom Tabs, visible origin and safe fallback; OAuth stays in provider-approved flow. |
| 17. Security | URL/file boundaries, credentials and context isolation; focused regression tests and release review. |
| 18. Notifications | Optional publisher image, privacy preview controls, quiet hours and one daily digest, real content only. |
| Presentation settings | Persisted auto-load/progress detail/image/title preferences, independent of provider budget. |
| Website | Update truthful current features, guides, screenshot sources and release availability after verified build. |

## Release boundaries

- Local test providers and host renders do not prove live authorization, actual paid-provider behavior or physical-phone alarm delivery.
- Original release signing inputs are not present in this fresh workspace. Never replace the identity or use an old signed-byte patch for changed APK bytes.
- No public APK link or completed-release label until the matching signed artifact exists and has been verified.
- All new user-facing wording belongs in English and natural Kiswahili resources.

## Design and journey direction

Keep the existing native Material/Figtree identity and five destinations. Make the feed a calm reading surface: first five stories, stable card position, explicit source and positive follow action, with progressive cached loading. Saved is a searchable personal library, not a second feed. Onboarding exposes broad interests through search and grouped choices; About you uses plain language and preserves opt-in scopes. The surprise tool compares everyday purchase sizes offline. Use existing semantic colors, comfortable 48dp actions, wrapping chips with 8dp spacing, concise English/Kiswahili copy and no blocking decorative motion.

Durable state remains in the existing validated repository/backup schema; drawers, filters and draft UI use saveable state. Acknowledged messages clear the composer only after the user turn persists. Attachments remain in the conversation's file drawer after sending. New modules are paused on upgrade. Failed provider calls never imply new results; follow cadence is a minimum eligibility interval within existing refresh, not a separate scheduler or guarantee. Rendering and local utilities do not require an AI account.

## Security review scope

- No new credentials, telemetry, private connectors, JavaScript bridges or signing identities.
- Content opens through browser-owned Custom Tabs with visible origin; provider sign-in stays in its existing approved external-agent flow.
- PDF preview uses the existing bounded HTTPS/public-file downloader, MIME/magic checks and local PdfRenderer; one bounded page at a time, cleanup on dismissal/cancellation. No document viewer upload.
- Stars append bounded untrusted research subjects, not provider instructions; finite existing request budget and refresh pause still apply.
- Notification preview is private on the lock screen, optional, with image timeout and late preference/permission/quiet-time/visibility rechecks.
- This is a focused source/regression review, not an independent penetration test. Live provider authorization, real media playback and physical-device delivery still require owner/device acceptance.

## Verification receipt — 25 October 2026

- JDK 17.0.21, Gradle 8.13, Android SDK 36 / Build Tools 35.0.0.
- `:core:test`: **181 passed**; `:app:testDebugUnitTest`: **467 passed**; Python service: **169 passed**. No test failures, errors or skips. All provider fixtures mocked; no paid inference.
- `:app:assembleDebug` and `:app:assembleRelease`: passed. `:app:lintDebug` and `:app:lintRelease`: passed with **zero errors**, 216 debug warnings / 217 release warnings and one hint per variant. This is not a warning-free build; warnings include unused resources, KTX suggestions, plural candidates and dependency notices.
- Final complete Gradle gate succeeded in 4m29s, including the late collection-editor rotation regression. Service completed in 2.24s with one upstream Starlette/httpx deprecation warning.
- Native Robolectric captures reviewed for feed stars, Saved/Following cadence, rich conversation, module search, unit comparison and English/Kiswahili reading settings. Full navigation-shell captures use dated public or explicitly sample content; not physical-phone or live-provider evidence.
- Debug package: `mebot.nguli.com.debug`, version `0.8.3-debug` / code14. It installs separately from the official app and does not reuse its private data. Keep the installed official app.
- Release package: `mebot.nguli.com`, version `0.8.3` / code14, **unsigned** pending the original release key. Both variants target SDK36, minimum SDK26; ZIP alignment checked.
- Full browser website verification remains for supported CI because local browser launch is restricted. Static site validation is separate from native checks.

Remaining acceptance: original signing and same-data upgrade installation; physical-device media/file/notification/alarm checks; owner-authorized live provider sign-in and voice playback. No new repository, visibility change, credentials or production signing identity was created.
