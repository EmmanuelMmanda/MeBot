# MeBot 0.9.8 — Reading and discovery plan

**Status: first slice locally qualified; not released.** The first slice adds typed acquisition/authorship, substantial Original Reads in the existing discovery response, a local daily selection distinct from publication dates, and explicit GitHub repository release discovery. Service follow-up requests accept validated Read bodies; the self-hosted discovery path does not yet generate Original Reads. AndroidX/arXiv/World Bank adapters remain proposed, not implemented. This is a bounded extension of the existing Feed/AI pipeline, not a new recommendation framework. Each additional adapter requires deterministic fixtures, current endpoint/terms verification and review.

## Product goal

Let a person move between three clearly different things without confusing their origins:

1. **Five AI questions** for an applicable item or chat target. This is an independent, exact-size AI set; it is not the number of feed candidates.
2. **Sourced updates/discoveries** acquired from publisher RSS/Atom, public APIs, provider web research, or AI transformation of already acquired source material. The record states how it was acquired; external claims in AI-authored material are tied to the sources actually consulted.
3. **Original Reads / workflow ideas**, written by AI for a selected, confirmed scope. These are full, durable readings, not a teaser disguised as an article. They are explicitly AI-authored, have no invented publisher or publication date, and do not imply facts were externally verified when they were not.

The public feed remains immediately readable with AI disconnected, paused, rate-limited, or over the local budget. No account memory, new credential type, private connector, or paid fallback is introduced. Religion is never inferred from region, timezone, language, or name; it appears only after a direct user choice of that topic or scope.

## Current extension points

- `FeedEngine.refreshSelected()` already reads public sources and commits them before it resolves AI credentials. `SourceReader` handles RSS; `PublicDiscovery` currently owns typed public adapters (weather, GitHub repository search and one news fallback). `FeedEngine` already limits public concurrency and coalesces module refreshes.
- `AiProvider.discover()` returns a `FeedDiscoveryResult` from one authorized, budgeted module request. The result currently separates feed items, optional candidate-fit values, and per-candidate interactions. Do not derive question-set size from `items.size` or make a second hidden call to fill missing candidates.
- The provider and service already validate module scope, preserve source metadata, and support public search plus confirmed context. The app has `FeedItem`, `FeedState`, local saved/hidden/history IDs, `FeedLogic.validate/merge/edition`, an 8 MiB Feed DataStore/backup boundary, and detail rendering for summaries/key points. `AiSuggestionSet097` already carries five AI-authored questions for item/chat/module targets.
- Existing model web results require an actual consulted source for factual URLs. AI_WEB_GROUNDED requires an actual web-search response with a matching consulted URL; AI_SOURCE_GROUNDED records cached-source or other source-backed AI transformations without claiming a new web search. Public RSS/API records do not need provider search annotations: the adapter's own publisher/record URL is their acquisition evidence. These are different provenance paths.

The 0.9.8 implementation should touch these seams narrowly: `FeedDiscoveryResult`/provider parser, `FeedItem` validation and merge, the existing `SourceReader`/`PublicDiscovery` registration, existing Feed UI detail rendering, and the optional service's corresponding strict schema/tests only if the chosen generation path uses that service. Avoid a generic plug-in system.

## Proposed record contract

### Acquisition and authorship are separate

Add a small persisted acquisition code to each sourced item, e.g. `publisher_rss`, `public_api`, `ai_web_grounded`, `original_read`, or `legacy_unknown`. Store it as a string code with a safe unknown fallback, not a closed serialized enum that can make a future value fail FeedState decoding. New records always set it; legacy records stay `legacy_unknown` and continue to show their existing links. Do not retroactively infer provenance from `aiGenerated`, `sourceUrl`, or a model label.

Keep the meanings distinct:

- `sourceUrl` remains the direct publisher/record destination.
- Existing evidence citations are for external claims and, on the AI-web-grounded path, must come from the provider's actual search/citation metadata.
- Add optional **further-reading** links as a separate field; do not count them as evidence for claims in an original read.
- Carry an explicit content-authorship code (`publisher`, `ai`, `mixed`, `legacy_unknown`) independently of acquisition. A source item may be AI-ranked or annotated without being relabelled as an AI-acquired article.

### Original Read shape

Prefer an additive optional body plus bounded section list on the existing `FeedItem` so current saved/hidden IDs, detail navigation, persistence, and backup continue to work. Use a dedicated `contentType` such as `original_read` and explicit `acquisition=original_read`, `authorship=ai`. Keep the existing `summary` as the list teaser; it is not the stored reading itself.

An accepted Original Read must have a meaningful body (proposed acceptance floor: at least 1,000 characters of section body text, with 2–4 substantive nonempty sections), a bounded total body (proposed maximum 12,000 characters), and bounded headings/links. Final limits can be tuned against readable EN/SW fixtures, but a summary or a few key points must fail validation rather than be padded. Persist `publishedAtMillis=0`, leave `sourceUrl` blank, and use the real `fetchedAtMillis` only as local creation time. UI copy must say “AI-authored” / “Created”, never present an imaginary source, date, or byline. If the reading makes verifiable external claims, require claim-evidence links from the sources actually consulted; otherwise keep it clearly framed as a reflection/plan and leave evidence empty. Further reading remains optional and separate.

This is additive and backward-compatible: missing fields decode to legacy defaults, unknown provenance remains safe, and existing notes, feed items, saved IDs, chats, and imports are retained. Validate body/sections and record count before commit; enforce DataStore/backup size bounds. A bad new read must not discard valid source items from the same response.

### AI questions stay independent

Keep the five-question cache/target contract separate from candidate count and from original-reading sections/workflow steps. A response with zero, one, or several feed candidates must not change the expected five questions for an applicable target. Reject an incomplete or malformed set; never pad it with canned or local questions. For chat, target the selected conversation and its bounded recent request history. Cache invalidation continues to include exact item/chat/module, provider/language, and confirmed-context scope.

## Runtime and request policy

1. Refresh public sources first and persist each successful source batch; do not wait on AI to make RSS/API reading usable.
2. For a module already making its normal authorized AI discovery request, allow that **same response** to optionally include one valid Original Read/workflow idea when it is relevant to that enabled module and the user’s confirmed interests/context under the requested personal-reading feature. This may increase output tokens, so disclose it; it is not zero-cost merely because it is one request. A missing/invalid read does not reject otherwise valid source items.
3. If there is no suitable existing request/result, generation is an explicit user action that reserves exactly one existing local request-budget attempt through the selected configured provider. No automatic retries/loops, extra call on sheet/detail open, credential fallback, or account switch. Cache/read it under the same exact scope revision, and commit only if scope, provider identity, language, and module are still current. Cancellation or stale data leaves prior public content intact.
4. For original reading, send only the requesting module's confirmed scoped notes, explicit topic/settings, and bounded relevant source metadata. Never send unrelated notes, whole history, or unsent attachments. Do not claim ChatGPT-account memory. “Religion” content is eligible only from an explicitly chosen religion-specific module/query or direct user scope; it is not a cultural default.
5. The Android app and optional service must return the same strict typed contract. An old service response with no read means “no new read”; it does not cause a second request. Keep the existing credential mode, per-request budget, and service-side budget semantics.

Daily selection/ranking stays distinct from publication time: evergreen reads can appear as “created” and be locally recommended, but cannot masquerade as today’s news. An API observation period (e.g. a World Bank year) is not a publication date.

## Candidate public-source adapters

Verified official endpoint references below were checked on 2026-10-27. This verifies the documented endpoint patterns, not service availability, source licensing for redistribution, or the quality of a particular record. Before adapter implementation, confirm terms and exercise the exact endpoint with bounded fixtures/live shape checks. Public adapters are deterministic, credential-free and independent from AI.

| Source | Bounded first step | Provenance and caveat |
|---|---|---|
| Existing Daily News Tanzania / BBC News Swahili | Preserve current `RegionalDefaults` URLs (`https://dailynews.co.tz/feed/`, `https://feeds.bbci.co.uk/swahili/rss.xml`) and business feed; keep RSS parsing in `SourceReader`. | Existing source identity is publisher RSS. No AI search citation is required. Keep source failures partial; do not silently replace custom feeds. BBC publishes language headline feeds; validate the configured URL/feed on adapter tests. |
| Official AndroidX release notes | Optional user-enabled technology source: `https://developer.android.com/feeds/androidx-release-notes.xml`. | Official Android Developers release page publishes this RSS URL. Show the library/channel and release date as the publisher reports them; distinguish alpha/beta/RC/stable. Do not silently add it to a user's custom source list. |
| arXiv subject RSS/Atom | User-selected categories only, e.g. `https://rss.arxiv.org/rss/cs.AI` or `/atom/cs.AI`; read metadata and link to the abstract. | arXiv documents category URL construction, daily feeds and formats. Its API terms require at most one request every three seconds and one connection at a time across RSS/Atom/API; implement a single global queue/rate limiter and cache, even across modules. No PDF/full-text harvesting in this slice. Label as preprint and preserve submitted/updated dates separately. |
| GitHub Releases API | Public `GET https://api.github.com/repos/{owner}/{repo}/releases` for a small list of explicitly configured repositories. | GitHub documents public unauthenticated retrieval for public resources. No user-following/account access or token is implied: “followed” means the person explicitly adds public repo URLs in MeBot. Link `html_url`, use `published_at` when present, mark prerelease/draft accurately, and never infer a security audit from release text. Cache and honor response/rate metadata. |
| World Bank Indicators API v2 | A small user-selected country/indicator set via `https://api.worldbank.org/v2/country/{code}/indicator/{code}?format=json`. | Official docs say v2 replaces v1 and does not require API authentication. Store indicator code/name/unit, source organization, observation period/status and direct data reference. Treat this as dated data, not breaking news; avoid broad “latest economy news” claims or arbitrary unbounded indicator queries. |

Explicitly out of scope for 0.9.8: scraping undocumented NBS, Bank of Tanzania, or TCRA pages; inferring logged-in GitHub follows; private site scraping; full-text arXiv downloads; new OAuth or paid source/provider; or representing a data point/preprint as confirmed local news.

## Delivery slices

1. **Contract and migration:** add backward-compatible acquisition/authorship/body/reference fields and validation first. Test old fixtures/backup imports and unknown provenance before any adapter work.
2. **Public metadata:** keep current RSS and add a user-configured AndroidX RSS source; add arXiv behind one global queue/rate limiter and bounded cache; then add explicitly configured GitHub public-repo releases and a limited World Bank indicator source. Each adapter is independently skippable/offline-safe.
3. **AI read:** extend the already authorized discovery response with an optional structured read/workflow output; add explicit one-request generation only if an existing response is unavailable, with clear one-request copy and existing budget semantics. Add service support in the same slice or treat older service output as “no read”.
4. **Presentation:** show acquisition and authorship plainly, render full sections, keep evidence and further reading visually separate, preserve saved/offline details, and avoid “news/today” labels for original reads or old data.

No new general-purpose source registry, embeddings/vector database, autonomous agent, or article-crawler should be added for these slices.

## Acceptance and test plan

- **Migration/persistence:** decode 0.9.7 FeedState/backup without new fields; tolerate unknown provenance codes; round-trip a full Original Read and its saved ID; malformed read validation leaves existing notes/items/saves unchanged; enforce count/byte bounds.
- **Three contracts remain separate:** tests with candidate counts 0, 1, and several show candidate feed result count never pads or truncates the exact five-question contract; only complete provider-produced question sets display. No generation occurs on opening a detail/sheet.
- **Provenance:** fixture RSS and public API records carry correct acquisition code, direct source URL and only real provider timestamps; public RSS/API items pass without AI search annotations. AI-web-grounded claims with an unconsulted URL fail; `furtherReading` does not satisfy evidence checks. Original Read has `sourceUrl=""`, `publishedAtMillis=0`, explicit AI authorship, at least two substantive sections, and no fake citation/date.
- **Public offline independence:** with AI disconnected, budget exhausted, one adapter timing out, or another failing, successful RSS/API items and prior cached reads remain; there are no AI/provider calls. Revalidation/cache invalidation uses module/query/category/country/indicator and source version.
- **Privacy/cancellation:** serialize only confirmed context relevant to the selected module; no name, unrelated notes, account tokens, unsent attachments, or hidden ChatGPT history. A changed note/module/provider/language, lock, navigation, or cancelled request cannot commit an Original Read. One explicit Generate reserves exactly one budget attempt and does not fall back to another provider.
- **Authorship/religion/freshness:** no default religion content for Tanzania/Swahili; explicit topic is the only path. Original Reads show Created/AI-authored, not a fabricated publisher/date; World Bank observation periods and arXiv preprint dates are visible as such, not “today's news”.
- **Adapter behavior:** fixture tests cover RSS/Atom parsing, stable IDs/dedup, unknown timestamps, malformed payloads, max response size/timeouts, arXiv 3-second single-connection serialization, GitHub public releases and `published_at`, World Bank period/unit/status, caching, partial failures and cancellation. No automated test calls a paid provider.
- **Service parity:** if service schema is changed, use strict tests for request scope, output limits, unconsulted citation rejection, and backward compatibility; run its documented pytest suite.

## Official references checked

- AndroidX official release notes RSS: [Android Developers, AndroidX release notes](https://developer.android.com/jetpack/androidx/versions/all-channel) (documents `https://developer.android.com/feeds/androidx-release-notes.xml`).
- arXiv: [official RSS/Atom category feed instructions](https://info.arxiv.org/help/rss.html), [API terms/rate limits](https://info.arxiv.org/help/api/tou.html), and [API user manual](https://info.arxiv.org/help/api/user-manual.html).
- GitHub: [REST release endpoints](https://docs.github.com/en/rest/releases/releases), including public release retrieval.
- World Bank: [Indicators API v2 documentation](https://datahelpdesk.worldbank.org/knowledgebase/articles/889392-about-the-indicators-api-documentation) and [basic call structures](https://datahelpdesk.worldbank.org/knowledgebase/articles/898581-api-basic-call-structures).
- Existing BBC language-feed index: [BBC Developer — headline feeds](https://support.bbc.co.uk/platform/feeds/LanguageNews.htm). Existing Daily News feed URLs are recorded in `core/.../RegionalDefaults.kt`; RSS responses should be validated in the adapter's bounded fixture/network test before any change.

## First implementation boundary
The first implementation slice is the migration-safe model, provenance validation, and actual read body in existing discovery/detail paths. It must deliver a useful personal reading experience without requiring Generate on every visit: normal authorized module discovery may prepare a relevant Read in its existing response. Extra explicit generation remains a separately disclosed request. Add at most one documented public source integration at a time after that contract is tested; do not delay the core reading capability for a broad adapter catalogue. The original adapter list is a staged backlog, not a promise that every integration ships in the first patch. Personal daily selection needs an explicit, stable local selection record or equivalent derivation; preparation time must not be reinterpreted as publisher time.
