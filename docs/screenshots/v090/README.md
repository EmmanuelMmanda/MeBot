# 0.9.0 native previews

Native Compose screens rendered through Robolectric on the host JVM, using synthetic editorial fixtures. These are not physical-device screenshots or evidence of live provider success. `today-full-shell-en.png` renders the real Activity with all five destinations and actual ViewModel/repository callbacks. The normal onboarding previews also render the real Activity shell with step controls. The remaining images render isolated screens without Activity bottom navigation; that isolation is not a production UI change.

- `today-full-shell-en.png`: authoritative full Activity Today preview, with star beside bookmark and all five tabs visible. The test exercises durable save, follow, collection, pause, recreation and original source ownership.
- `today-en.png`, `today-sw.png`: cached Today items with verified synthetic source dates.
- `today-empty-en.png`, `today-empty-sw.png`: the same fixture after the Africa/Dar_es_Salaam local-day rollover, excluding yesterday rather than relabeling it.
- `app-lock.png`, `app-lock-settings.png`: optional protection screens with empty PIN fields and synthetic in-memory security state; no real keys, PIN setup or biometric success is shown.
- `onboarding-en-chooser.png`, `onboarding-sw-chooser.png`: authoritative full Activity onboarding at 411dp width and standard system text size. Equal-width icon tiles preserve the earlier two-column design; header, progress, search, category rail, Back/Next/Skip and scrolling are real runtime UI. Optional custom text follows the chooser.
- `interests-en.png`: historical pre-correction isolated icon-interest fixture, not the current primary preview.
- `interests-small-sw-large.png`: **historical pre-correction accessibility stress fixture**, isolated Kiswahili chooser at 320dp and 1.3× font scale. This is not the normal-size primary onboarding preview. Long labels and scrolling are intentionally stressed.

- `onboarding-sw-stress-top.png`: current full Activity accessibility stress check at 320dp and 130% system text; adaptive single-column tiles preserve readable text and 48dp targets. Not the ordinary phone default.
- `onboarding-sw-custom-text.png`: scrolled optional custom input, fully above the pinned footer.
- `onboarding-sw-landscape.png`: explicit host orientation-change check with retained custom input and reachable footer. These are not physical-keyboard/IME acceptance evidence.

Native tests: `Today090UiTest`, `MeBotToday090JourneyTest` and `Onboarding090ShellTest`. Full onboarding tests use the actual Activity/repository and exercise search, categories, selection, more topics, back, language, recreation, explicit landscape/portrait changes and durable scoped custom-note saving. Geometry checks cover equal widths, minimum targets and custom input fully above the pinned footer. The populated/empty Today tests cover original-ID navigation, filter restoration, local-midnight rollover, cached-only selection and the regular-feed recovery action without extra refresh calls. Source dates are distinct from fetch dates; no personal names, notes, account state or credentials are shown.

The implementation lead regenerates these captures in the final verification gate before source packaging. Physical-device lifecycle and timing acceptance remain separate.
