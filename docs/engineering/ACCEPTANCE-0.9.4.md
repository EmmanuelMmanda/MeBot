# 0.9.4 acceptance tracker

## Direction
Preserve the existing native visual design. Complete the context-import journey: explicit review, durable save, return to the context page and reveal the imported notes. Keep natural English/Kiswahili copy and accessible layout. This patch also improves feed freshness without deleting saved, starred or personal information.

## Required evidence
- Import: reproduce the previous missing return/stale saved draft; verify the committed notes are visibly rendered after confirmation and recreation. Cancel, invalid input, reopening, reset and lock boundaries must not produce false success or overwrite newer work.
- Freshness: verified expired forecasts/events leave the active feed, not storage; unknown publication dates remain unknown; evergreen items decay more slowly; explicit hide and protected collections retain their semantics.
- AI fit: bounded shortlist assessed in the existing authorized per-module discovery request, never a second request. Validate stable IDs and scores; limit influence; invalidate changed content/context/provider identity and expired cache. No passive reading history in prompts. Failure falls back to deterministic ranking; current reading order remains pinned.
- Run focused regressions, full core/Android tests, service tests when affected, release lint and production assembly. Inspect native normal-size EN/SW captures. Sign only the verified artifact with the existing production certificate.

## Evidence limits
Host-rendered Android tests are not physical-device acceptance. Model fit estimates are not calibrated probabilities. Legacy/self-hosted providers without fit output retain deterministic ranking. Event expiry requires typed source evidence; an unverified model-written deadline is not sufficient.

## Publication
User-facing release notes describe the experience. Detailed implementation, test counts, source identity and signature evidence belong in the technical verification report. No owner-personalized JSON, credentials, keys or local state may enter public artifacts.
