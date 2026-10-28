# Activity, refresh and resource checks

> Workflow reference for the 0.9.8 source preview, not a public APK availability claim. See [release availability](download.html). Older host screenshots have been removed from this current guide; archived captures remain in their dated folders.

## What the top panel means

The activity panel is outside the page's scroll container. Its icon has a running ring only while the associated operation exists. Tap it for a short, scrollable popover; close it with Show less or by tapping outside. This does not move the article underneath.

Labels come from actual work: preparing a request, connecting, searching, reading sources, preparing updates, checking results, or writing an answer. Feed and search item generation are not labelled as writing a chat answer. Source counts appear only after the returned items pass validation. An elapsed duration is not a completion estimate.

The panel can show the provider's published reasoning summary when supplied. It does not expose private internal reasoning and does not simulate thoughts, source counts or a percentage. A model that provides no summary still has real operation status. There is one status panel; lightweight robot badges can reflect their own active operation.

## Explore without losing the information

The conversation destination is called MeBot. Story summaries remain readable while an explanation is prepared. Show more opens notes and actions without another bordered card. Answers, source links and optional media remain separate so a missing thumbnail cannot replace the answer.

Pull down inside a module to refresh only that module. The refresh button and reselecting Feed use the same selected scope. Reselecting Feed also returns to the top. Explicit refresh invalidates the relevant public-adapter cache. Refresh requests already in progress are coalesced. Sources may have no new items; the app does not invent novelty.

## Local defaults and privacy

A Tanzania regional focus is used when selected explicitly or when no region is selected and the saved device timezone is Africa/Dar_es_Salaam. It is not a claim about nationality, precise location or eligibility. An explicit different region wins. Custom source collections are preserved.

Default Tanzania news uses Daily News and BBC News Swahili, with a Daily News business feed. Public feeds can be read without connecting an AI account. Their original language is preserved when AI translation is unavailable. AI discovery applies the selected region and relevant approved preferences, and verifies work/study eligibility and deadlines at original sources. No employer vacancy count is assumed.

New installations have a random local edition seed so equally relevant choices need not appear in the same order. Strong preferences, sources, freshness and relevance still matter more than this small variation. This does not guarantee different results when the source pool is small.

## File availability

A URL ending in .pdf is not proof of an available PDF. MeBot performs a bounded, credential-free public-file check before exposing a verified Files count or Save file. The check validates status, redirect safety, size, content type and a small file prefix. A short-lived cache and two separate check slots prevent repeated probes from using AI capacity.

The original source stays available when a check fails. Tapping Check file retries. Saving downloads and revalidates the complete file, then uses Android's document picker. The maximum download remains 20 MiB. Availability can change after a check; authenticated sites and paywalls are not bypassed. Automatic probes do not interrupt the AI activity display.

## Language

Status strings are localized independently of generated text. Kiswahili uses short everyday wording. AI content continues to use the chosen module language or app language. Publisher content is not silently represented as translated.

## Verification limits

Automated provider fixtures are not a live account authorization test. Device playback, physical alarms, the Android save picker, third-party availability and live account quotas need device checks. See the release verification report for executed checks and remaining limitations.


## Interface previews

Native host renders with example content, not live account responses.
