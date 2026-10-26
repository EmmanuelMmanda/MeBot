# MeBot 0.9.1 — reliability patch candidate

This patch follows reported device failures in 0.9.0. It preserves the existing design, onboarding tiles, app lock, global About you and module-scoped notes. Version code 18 can update version 17 when signed by the same production certificate; do not uninstall or erase data to update.

## Scope and design

- Cached reading and free public sources must not wait for AI authorization or inference. Preserve cancellation, source ownership, context checks, quota accounting and cached content on partial failure.
- A browser return link is navigation, not proof that connection checks succeeded. Keep the callback state-matched, single-use, loopback-only and cancellation-safe; do not put authorization data in the return link or page.
- Today/weather cards should show one intelligible source-appropriate time. Exact dates, freshness and timezone belong in the existing details view, not repeated labels on every card. Unknown source times stay unknown.
- Give the final settings rows useful descriptions. Device dictation and read-aloud are not realtime voice conversation. Preserve these accessibility features without misleading voice marketing.

## Verification

971 local tests passed: 198 core, 593 Android host, and 180 service, with no failures, errors or skips. Final lint/assembly and signing results are recorded in the delivery report. No real-device, authenticated-provider or paid-inference success is claimed. Public-source host probes are separately recorded, not treated as phone transport evidence. See `diagnostics/AUTH-AND-VOICE-0.9.1.md` for supported voice-route boundaries.

A synchronized credential-presence epoch prevents a delayed startup snapshot from overwriting a newer connect, disconnect, reset or import-clear result. Three deterministic regressions exercise both stale-result directions and normal startup publication.

The optional setup promotion now disappears immediately after Later; a persisted dismissal refreshes the foreground clock rather than waiting for its next minute tick. The real Activity regression also verifies dismissal and Today selection survive recreation.

[Normal-size synthetic native previews](screenshots/v091/README.md) retain the existing visual design. Five actual public RSS responses also passed the production Android parser and item validator in a one-off host probe; raw publisher bodies are not included in delivered source. This is not phone HTTP or authenticated-provider verification.

The production signer must remain the same as the delivered 0.9.0 signer. No new key is needed. Public source/release publication remains blocked separately; a privately delivered signed APK does not imply a public release.
