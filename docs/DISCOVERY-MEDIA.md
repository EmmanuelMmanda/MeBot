# Discovery and publisher media

MeBot 0.6 keeps discovery, personalization and presentation separate. A module describes a purpose, an editable focus and validated kind-specific preferences. The selected AI connection searches the public web with only confirmed context scoped to that module. Public data adapters supply weather, repository discovery and a technical-news fallback. RSS remains an optional source preference and fallback.

## Source paths

| Path | Actual capability | Data sent | Boundaries |
| --- | --- | --- | --- |
| Connected AI | Current web research, short sourced summaries, module-specific recommendations and contextual questions | Module focus, region/timezone, confirmed scoped notes, up to 12 recently shown titles/links for that module | Requires the owner's configured ChatGPT plan or API/service connection. Never automatically changes billing routes. |
| Open-Meteo | Geocode the module's location override, or its profile-region fallback; fetch current model conditions and hourly precipitation probability | Selected location, then the resolved public city coordinates | No GPS permission or background location. Unknown/ambiguous places produce an actionable error. A destination override is not replaced by a different country merely because it matches the home timezone. Forecast validity time is preserved; these values are not claimed to be readings from a personal weather station. |
| GitHub REST search | Public repositories created in the last seven days, ranked by stars, narrowed by custom keywords and selected focus/programming language | Owner-configured query, focus topic and language qualifier | No GitHub account access. This is a recent-repository search, not the GitHub Trending ranking. Incomplete results and rate limits are surfaced. |
| Hacker News/Algolia | Recent public submissions, optional configured keyword filter, actual article and discussion links | Module query only | Technical-news fallback, not comprehensive regional news or access to a personal Hacker News account. HN submission time is not presented as the linked article's publication date. |
| Optional RSS/Atom | Publisher entries, dates and links | Configured public feed URL | Does not establish AI personalization or make a model call. |
| Publisher metadata | Image selected by the linked publisher via Open Graph, Twitter Card or article JSON-LD | Linked public page URL | No image URL is accepted from model text. Missing or blocked media leaves the sourced item usable. |

The public adapters never send personal-context notes, account credentials or the person's name. Local relevance scoring can use approved context after the public data reaches the device. Weather and GitHub public adapters can supply useful content without an AI connection. The Android app fetches weather data directly even when AI is connected; it does not ask a model to invent numeric measurements.

## AI output and provenance

Each module has a `briefGuidance` policy in `ModuleCatalog`: vendor advisories for security, official application pages for scholarships, direct lessons for learning, repositories for open source, and organizer pages for events. News normally concerns the previous seven days. Useful older lessons and entertainment recommendations are allowed but must not be presented as breaking news.

`ModulePreferences` accepts only documented keys and values for each kind. Preferences shape topics, level, format, language, location and other relevant selection constraints; they do not grant access to additional notes. A custom query replaces the starter topic and retains the selected constraints. The optional `media` kind, labelled Clips, asks for original creators and official short-video pages. It defaults to videos up to ten minutes, with shorter/any-duration choices. Missing verified duration is not invented. New catalog capabilities remain disabled on upgrade.

The provider accepts a factual item's primary URL only if it occurs in the Responses API's actual search-source metadata or citation annotations. Optional additional reference URLs must pass the same check. Unknown publication dates remain unknown. Expired or malformed deadlines are removed. Duplicate canonical URLs are combined. The model's proposed publisher label is replaced with the verified URL's host.

This verifies URL provenance, not the truth of every sentence. The source and direct destination remain visible so the person can inspect claims, eligibility and availability. Horoscope output remains optional entertainment without fabricated sources. Neither the mobile app nor the optional service claims access to ChatGPT conversation memory or private third-party accounts.

Keyword search submits one explicit request across enabled factual modules, or a selected one, and accepts at most eight distinct items assigned to those modules. Each module retains its own confirmed-context scope. Weather and horoscope are excluded. Typing, switching filters and revealing cached results do not make extra requests. Source citations and destinations follow the same provenance checks as feed discovery.

Story details can contain short source-supported key points, a practical next step and a caveat. Opening details with AI connected starts an explanation using the stored source material and current cited research; full article access is not guaranteed. Native ChatGPT/direct streaming paths display actual text deltas. Only a completed validated answer enters the six-hour cache, keyed to the item, module configuration, scoped context, connection and language. Cached explanations and local follow-up conversations remain readable without replaying a request. The optional personal service returns completed JSON; the UI does not manufacture a stream.

`contentType` describes the destination: article, video, podcast, repository, course, event, opportunity or entertainment. A weather item additionally carries bounded typed weather values. Direct URLs remain the authoritative destination; presentation can provide a suitable action label without creating an imaginary integration.

## Media behavior

Text is committed before optional metadata enrichment. The current edition enriches at most six new items with two parallel public-page requests. Publisher-provided RSS image enclosures can be reused without another metadata request. Open Graph images take priority, followed by Twitter images and article/video/course/event JSON-LD. Organization logos are not treated as story imagery.

Every `FeedMedia` contains the HTTPS image URL, the publisher page/feed that supplied it, and an attribution label. The parser does not execute scripts or render arbitrary HTML. Public URL checks, redirect validation, DNS checks, response-size limits and short network deadlines apply. Failed image metadata never removes article text or its direct link.

The image repository fetches public image bytes without provider credentials or cookies supplied by the app. It accepts common raster-image MIME types, limits downloads to 4 MiB, checks dimensions before decoding, and downsamples to at most 1,200 pixels per side. Very large or tiny images are rejected. A failed fetch is held for five minutes to avoid repeated requests on scroll. The cache uses up to 12 MiB of decoded memory and 32 MiB of disk, with a seven-day disk expiry. It does not autoplay video, execute embeds, fetch arbitrary private-network images or download media to public device storage.

A missing image has a designed module treatment, not an unrelated stock photograph. Publisher imagery is a preview; opening it or its attribution does not imply a content license or endorsement.

Video and audio cards open the verified original resource in the appropriate browser or app. Clips does not download or redistribute videos, bypass a platform restriction, or promise an embedded streaming player. Voice input and read-aloud in the AI workspace use available device speech services and are independent of media discovery and model audio capabilities.

## Persistence and partial failures

New item fields are optional and keep the existing persisted schema backward-compatible. Existing saved articles remain readable. Module additions are disabled on upgrade and preserve the owner's existing focus and switches. Public failures, model errors and unavailable media do not erase cached content. Context, module or connection changes invalidate stale asynchronous completions.

Public adapter caching is scoped by module ID/kind, focus, the complete preference map, region, timezone and display language. Equivalent preference maps reuse the same cache regardless of entry order; a changed setting invalidates it. Weather/news reuse in-memory responses for up to 30 minutes; repository search for two hours. This reduces public-endpoint traffic. Stored item timestamps are retained rather than replaced with invented publication times.

The optional personal service accepts all 17 module kinds and mobile media/weather/detail fields for contextual questions. It validates module preferences and supports the bounded cross-module search endpoint. It generates grounded text and direct source references; publisher-image enrichment remains on the Android device. Service requests and schedules support up to 32 modules, still bounded by the operator's request cap.

## Verification

Tests use deterministic fixtures for weather mapping, missing/ambiguous locations, source links, API failure, scoped request data, caching, media provenance, unsafe URLs, JSON-LD, RSS enclosures, cancellation and no-image fallback. Provider tests reject unconsulted URLs and model-invented media while accepting bounded JSON-fence compatibility. The Python service suite covers new kinds, mobile item validation and expired-deadline removal without paid provider calls.

An unauthenticated public smoke check on 23 October 2026 received HTTP 200 from Open-Meteo geocoding/forecast, GitHub repository search, Hacker News/Algolia, a linked publisher page and its actual Open Graph image. It checked returned field shapes, six repository results with `incomplete_results=false`, eight news results and a 276 KiB PNG. This is evidence for those endpoints at that time, not a guarantee of future third-party availability or a live ChatGPT-account inference test. Private release evidence records exact URLs and timestamps.

## Primary references

- [Open-Meteo forecast API](https://open-meteo.com/en/docs)
- [Open-Meteo geocoding API and attribution](https://open-meteo.com/en/docs/geocoding-api)
- [GitHub REST repository search, ranking and public rate limits](https://docs.github.com/en/rest/search/search#search-repositories)
- [Hacker News Algolia API](https://hn.algolia.com/api)
- [OpenAI Responses web search](https://developers.openai.com/api/docs/guides/tools-web-search)
- [ChatGPT plan inference contract](https://developers.openai.com/siwc/token-sharing-open-source/models-and-inference)

Public service terms and licenses still apply. Open-Meteo's public endpoint is intended for non-commercial use; a future commercial distribution must configure an appropriate plan or self-hosted provider. Provider adapters are isolated so this can change without rebuilding the feed UI.

## Feed rendering and local novelty

The home edition is a lazy list with stable story keys and reusable content types. It starts with five stories and reveals five more per action. The counter distinguishes the revealed page from the total cached edition. Paging preserves the current viewport and each module filter has its own saved count and scroll position. A minute-based clock keeps ranking/relative dates current without sorting again for unrelated connection or saved-state updates. Paging does not call a provider; Refresh remains the explicit way to fetch current content.

Module drag/drop stores the complete module order; enabled home/search filters follow it. Optional local reading history temporarily lowers the priority of already-opened items while preserving saved/visible content and considering approaching deadlines. It can be disabled or cleared under Your data. This is ranking over real cached results, not a way to fabricate fresh publication dates or an endless stream of new content.

Module starter goals are resolved at request time. Existing custom topics and enabled choices are preserved. Guidance covers all catalog kinds, including custom watches, and instructs the provider to avoid inventing preferences when approved personal context is absent.
