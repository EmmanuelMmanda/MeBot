# MeBot 0.9.4 verification — in progress

This is an engineering record, not a release announcement. No final APK identity or overall pass is claimed yet.

## Confirmed import defect and repair
The previous native journey committed the imported context to DataStore, but stayed on the import page. Navigating back could restore the previous editor draft from SaveableStateHolder. A separate edge occurred when importing the same durable snapshot while the editor held unsaved changes: equality-based reconciliation alone retained the draft.

The candidate completes navigation only after the durable write, explicitly reconciles the editor after every successful import, and reveals the first imported note. Cancel and invalid input retain the draft; an explicit successful replacement does not. A pending editor save cannot redirect the returning import journey. Profile-only imports do not scroll to an empty notes section. Context and full-backup import discard derived AI ranking cache, not personal content.

The existing review contract remains: imported profile/context replaces the current profile/context after confirmation; empty modules preserves module configuration; nonempty modules explicitly replaces its settings/order. Imported notes do not acquire global trust.

## Freshness and AI matching
Active-feed filtering uses validated typed forecast/event evidence. Expired content remains stored subject to the existing retention policy; saved/starred content remains accessible in its collections. Legacy/model-written event deadline strings are not publisher verification. Unknown publication dates receive neutral age treatment, rather than pretending fetch time is publication. Evergreen material decays more slowly than current news.

Optional candidate matching is piggybacked on the already budgeted module discovery request: at most eight public candidates, bounded title/summary text, confirmed module-scoped notes and explicit relevance feedback. This adds bounded prompt/output work, not a second provider request; it does not imply zero token cost. Fit is a limited local scoring component, not a calibrated probability. Explicit user actions, source eligibility, diversity and pinned reading order retain their roles.

Estimates expire after 24 hours and are invalidated by content/scope/provider changes. Successful credential replacement and sign-out clear them. Exact unchanged public refreshes can reuse prior local estimates; external results cannot inject cache metadata. Cache fingerprints are removed from provider payloads. Older self-hosted service responses continue the prior AI-generated relevance behavior without candidate-fit enrichment.

## Acceptance limits
Controlled fake providers are used in automated tests; no paid/live inference is required or claimed. Native host rendering and lifecycle tests are not physical-device, OEM picker, biometric or overnight-alarm acceptance. Cold process death after a durable import but before transient completion feedback can lose that transient message; the durable notes remain the source of truth.

Final test totals, raw host ranking measurements, screenshots, exact source tree and production signature evidence will be attached after the candidate is frozen and verified.
