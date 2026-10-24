# MeBot 0.6.1 integration contract

Android keeps existing `AppState` and alarm runtime unchanged. A separate version-one `FeedState` DataStore owns the new information experience. `FeedModels.kt` is authoritative for JSON shape (camelCase).

## UI contract

`FeedViewModel(application)` (package `app.mynguli.feed`) exposes `ui: StateFlow<FeedUiState>`:

- `state: FeedState?`, `loading: Boolean`, `refreshing: Boolean`, `connecting: Boolean`, `connected: Boolean`, `error: String?`, `notice: String?`, `asking: Boolean`, `answer: String?`, `answerCitations: List<FeedCitation>`.
- `chatGpt: ChatGptAuthState` contains only nonsecret accounts, models, selected reference, phase, error code and bounded diagnostic metadata (stage/status/request ID/response shape); `chatGptVerifying` identifies the completed-inference check. Browser authorization URLs are transient events on `browserLaunches`, never saved UI state.
- `activeConversationId`, `question`, `detailItemId`, `detailLoading`, `detailText`, `detailTurns`, `detailCitations` and `detailError` separate workspace and story operations. Streamed text is provisional UI state; completed turns and explanations belong to `FeedState`.
- `searchQuery`, `searchModuleId`, `searching`, `searchCompleted`, `searchResults` and `searchError` distinguish a retained query, an active request, a completed empty result and a failure.
- `importWorking`, `importPreview: FeedImportPreview?`, `importSummary` and `diagnostics` support reviewed replacement and local troubleshooting. A preview contains `kind` (`context` or `backup`), `name`, and counts for `entries`, `modules`, `saved` and `conversations`. `deliverySaving` keeps background settings pending until their durable result is known.
- Error and notice are stable codes localized by the UI: offline, unauthorized, rate_limit, quota, provider_error, invalid_response, storage_error, invalid_input, partial_refresh, no_results, limit_reached, connected, imported, saved.
- `finishOnboarding()`, `refresh()`, `refreshIfStale()`, `clearMessage()`, `clearNotice()`, `clearAnswer()`.
- `connect(key: String, model: String = "gpt-6-luna", mode: String = "direct", serviceUrl: String = "")`, `disconnect()`.
- `beginChatGptSignIn(accountId: String? = null)`, `cancelChatGptSignIn()`, `browserLaunchFailed()`, `selectChatGptAccount(id)`, `selectChatGptModel(slug)`, `useChatGptAccount(id, model)`, `acknowledgeChatGptPlan()`, `disconnectChatGptAccount(id)`.
- `toggleSaved(id: String)`, `hideItem(id: String)`, `restoreHidden()`, `markViewed(id: String)`, `setReadingHistory(enabled: Boolean)`, `clearReadingHistory()`.
- `setModuleEnabled(id: String, enabled: Boolean)`, `saveModule(module: FeedModule, sharedContextIds: Set<String>? = null)`, `removeModule(id: String)`, `reorderModules(ids: List<String>)`.
- `saveContext(name: String, region: String, timezone: String, entries: List<ContextEntry>)`.
- `prepareContextImport(uri: Uri)`, `prepareContextText(text: String)`, `prepareFeedBackup(uri: Uri)`, `confirmImport()`, `cancelImport()`, `exportContext(uri: Uri)`, `exportFeedBackup(uri: Uri)`. File and pasted context use the same prepare/review/confirm replacement flow; legacy immediate `importContext` and `importFeedBackup` entry points remain for compatibility tests.
- `saveDelivery(enabled: Boolean, refreshHours: Int, dailyLimit: Int, quietStart: Int = 22, quietEnd: Int = 7)`.
- `ask(itemId: String?, question: String)`, `reset()`.
- `newConversation(moduleId: String? = null)`, `selectConversation(id)`, `deleteConversation(id)`, `openConversation(itemId, question: String? = null)`, `sendConversation(question)`, `importTextAttachment(uri)`, `removeAttachment(id)`.
- `loadDetail(itemId, force: Boolean = false)`, `retryDetail(itemId)`, `followUpDetail(itemId, question)`, `cancelDetail()`, `search(query, moduleId: String? = null)`, `cancelSearch()`.

UI owns navigation, Compose screens, localized display strings, edits/drafts. Keys never go in rememberSaveable, AppState, FeedState, context, exports, screenshots or logs. Use an ordinary non-saveable text field for entry; retain only encrypted secret storage after connection check. Provider error bodies never go to UI.

Primary setup uses Continue with ChatGPT and the account-specific model catalogue. The serialized `AiConnection` default remains `direct` so old state retains its meaning. No connection is active merely because a default exists. `mode=chatgpt` stores a local opaque account reference and discovered model slug; all OAuth credentials live in the separate encrypted store. API-key and personal-service modes are explicit alternatives with separate billing. A shared developer key must never be shipped. See [CHATGPT-CONNECTION.md](CHATGPT-CONNECTION.md).

The engine counts attempted inference atomically, accepts subscription output only after `response.completed`, preserves cached reading on error, and prevents stale account/context results from committing. Actionable subscription errors persist an automatic-AI pause. `FeedWorker.refresh()` respects the pause; a deliberate UI refresh can retry. No error path selects another provider or credential type.

The five destinations are Feed, Modules, AI, Saved and You. `ModulesScreen` accepts `onReorder: (List<String>) -> Unit` and `initialFilter: String = "all"`, with status values `all`, `enabled` and `disabled`. Search and category filters intersect that status; the catalog opens all statuses normally and disabled status from Add to your day. `ModuleOrder.reorder` requires the exact complete ID permutation and preserves every module object. Drag/drop and accessible move actions reorder the visible enabled subset, retaining hidden/paused slots. Home and search filters use persisted order. `SearchScreen` accepts explicit search/item/save/connect/cancel callbacks; typing or changing a filter does not submit a request.

Selecting the active Feed tab explicitly scrolls the current edition to the top and refreshes. Revealing another cached page uses no provider request. Module, search and short-action rails own their horizontal scroll state; selecting a module keeps that filter visible without scrolling the reading page.

## Daily request allowance

`FeedState.dailyRequestLimit` is an integer: `0` means **Unlimited**, and any positive value is a finite local attempted-request cap. `RequestBudget.DEFAULT_LIMIT` is `28`; UI presets are `28`, `56`, `112` and `224`, plus Custom and Unlimited. Existing stored finite limits, including the previous default of `12`, are preserved rather than migrated to `28`. Missing fields in new/default state use `28`. There is no schema-version change.

`RequestBudget.reserve` executes inside the repository's atomic update. It resets the used count on the configured local date, rejects a finite request at its limit, and counts attempted AI operations including failures and cancellations. Unlimited still records usage and saturates the integer counter instead of wrapping or turning a storage ceiling into a cap. A feed module request, new explanation, conversation turn, submitted search or connection/model check can each consume an attempt; cached reading and reusable completed explanations do not. Provider plan limits, API billing and the optional service's independently configured cap still apply.

`refreshHours` remains 1–24 and quiet-hour fields remain 0–23. Equal quiet start/end disables that quiet window. Existing feed, context, conversation, attachment and backup bounds remain in place; Unlimited applies only to the Android request cap. A settings save persists before work is rescheduled, and failure retains the editable draft.

## Service contract

Optional self-hosted service, authenticated via Bearer service token. Ordinary API key resides on server via environment/secret configuration. Android `service` mode never sends an OpenAI key: entered credential is service token.

- `GET /health`: authenticated connection check; returns `{ "ok": true, "version": "0.6.1" }`.
- `POST /v1/feed`: request `{ "model": "gpt-6-luna", "name": "", "region": "", "timezone": "", "modules": [FeedModule], "context": [ContextEntry], "language": "en", "limit": 8 }`. Response `{ "items": [FeedItem] }`. Model/provider configuration may be server-authoritative. Every factual AI item must include source URLs grounded in returned search metadata. Horoscope is explicitly entertainment and has kind horoscope, no fictional factual sources.
- `POST /v1/ask`: request `{ "model": "...", "question": "...", "item": FeedItem|null, "module": FeedModule|null, "context": [ContextEntry], "language": "en", "moduleKind": "news", "history": [{"role":"user","text":"..."}], "attachments": [{"name":"notes.md","text":"..."}] }`. Response `{ "answer": "...", "citations": [FeedCitation] }`. The optional v0.6.1 `module` carries validated preferences and topic data. Its ID and kind must match a supplied item, and its kind must match a supplied `moduleKind`. Server-owned `MODULE_GUIDANCE` provides domain defaults; arbitrary client `defaultContext` fields are rejected. History is limited to 16 turns / 36,000 characters; attachments to three / 24,000 combined characters. An explicit empty `moduleKind` with no item/module is a general workspace thread with no personal context; omitting all scope selectors retains legacy news behavior. The service returns completed JSON, not a simulated token stream.
- `POST /v1/search`: request `{ "model": "...", "query": "...", "region": "", "timezone": "", "modules": [FeedModule], "context": [ContextEntry], "language": "en", "limit": 8 }`. Selected modules must be enabled and factual; weather and horoscope are excluded. One bounded search returns at most eight distinct grounded items, each assigned to an allowed module. Context remains filtered per module.
- HTTP 401 unauthorized, 429 budget/rate, 502 upstream failure. Never return raw upstream errors/secrets.

Service owns optional scheduled jobs and persistence under service/. Do not expose arbitrary unauthenticated inference endpoints. Configurable request budget and bounded inputs. No public deployment is claimed.

The Android app independently supports direct public data adapters, publisher media, optional RSS retrieval, cached/offline reading, optional direct OpenAI source-grounded refresh and contextual questions. Background WorkManager refresh is approximate, opt-in, deduplicated and respects quiet hours. Exact local alarms remain separate.

## Catalog and media contract

`ModuleCatalog.presets` defines kind/group/default query/research guidance/content type/public-source availability. `mergeCatalog(existing)` is append-only for missing built-ins and leaves them disabled. `FeedLogic.edition` diversifies visible content without deleting saved or hidden state.

The catalog contains 16 built-ins and the custom `watch` kind. The optional `media` kind appears as Clips and seeks original/official short-video destinations. `FeedModule.preferences` defaults to an empty map. `ModulePreferences` defines allowed keys, enumerated values and bounded location text per kind; unknown keys and cross-kind settings are rejected. Custom topics replace starter topics; validated preferences augment request guidance. None of these settings grant new personal-context permissions. The Python service validates the same registry.

The first listed choice is the default when a key is omitted. All kinds except weather also accept `language: auto | en | sw`; `auto` uses the app's supported display language. `location` is optional text of at most 160 characters with no control characters.

| Kind | Additional preference keys and allowed choices |
| --- | --- |
| `news` | `coverage`: balanced / local / world; `location` |
| `technology` | `focus`: all / ai / software / infrastructure / hardware |
| `security` | `focus`: all / software / cloud / networks; `urgency`: all / critical |
| `business` | `focus`: all / regional / operations / markets; `location` |
| `science` | `focus`: all / ai / health / climate / space |
| `opportunities` | `type`: all / jobs / contracts / grants; `workplace`: any / local / remote; `location` |
| `scholarships` | `level`: any / masters / phd; `funding`: any / full |
| `learning` | `level`: any / beginner / intermediate / advanced; `format`: any / guides / courses / videos; `cost`: any / free |
| `github` | `focus`: all / tools / libraries / self_hosted; `code_language`: any / python / javascript / php / go / rust |
| `weather` | `location`; blank or omitted uses the profile region |
| `entertainment` | `type`: both / films / series; `mood`: any / tense / thoughtful / light |
| `music` | `focus`: mixed / listening / practice; `format`: any / performances / lessons |
| `events` | `attendance`: any / nearby / online; `window`: month / week (next 30 / 7 days); `location` |
| `travel` | `focus`: all / weekend / outdoors / culture; `cost`: any / low_cost; `location` |
| `horoscope` | `focus`: general / work / relationships / reflection; `tone`: calm / playful; the selected sign remains in `zodiacSign` |
| `media` | `focus`: all / explainers / demonstrations / interviews / news; `duration`: short / quick / any (up to 10 / 3 minutes, or unrestricted) |
| `watch` | `format`: any / articles / videos |

`FeedItem` adds optional `media: FeedMedia?`, `contentType: String = "article"`, and `weather: FeedWeather?`. Media contains HTTPS image URL, source-page URL and credit; it is obtained from the publisher, never supplied by model text. Weather contains finite temperature/feels-like values, probability, WMO condition and resolved location. Weather is forecast data, not a station observation. Existing JSON without these fields remains valid.

Optional `keyPoints`, `nextStep` and `caveat` add source-supported detail without manufacturing a full article. `FeedState` adds backward-compatible defaults for local `conversations`, completed `briefs`, `readingHistoryEnabled`, `viewedAtMillis` and `editionSequence`. Reading history is bounded to 1,000 item timestamps and affects local ranking only.

`PublicDiscovery.discover(state,module,language,now)` and `FeedEnricher.enrich(items)` are injectable boundaries. Production uses the concrete public adapters and publisher enrichment. Test fixtures inject their own transports and do not make paid requests.

Optional publisher images use a 12 MiB memory cache and 32 MiB disk cache with a seven-day age bound. Concurrent requests for the same URL share one load; different URLs retain bounded download concurrency. Invalid cached images are evicted and can be refetched in the same load. Failed images do not remove story text. Repository no-op updates avoid repeat validation/writes, and repeated opens within five minutes do not rewrite the same reading timestamp. Durable changes still pass domain validation and the 8 MiB feed-store bound.

`ConversationLogic` validates at most 24 threads with 60 turns each and 800,000 aggregate text characters. New general workspace threads include no scoped notes; story/module threads use their explicit kind. Android imports only valid UTF-8 `.txt`/`.md` files, at most 48 KiB each and three files / 24,000 characters combined per thread. `BriefCache` reuses up to 24 completed explanations for six hours when source content, module configuration, approved notes, connection, region/timezone and language match. A partial, cancelled or failed stream is never stored as a completed answer. Story explanations do not guarantee full article retrieval.

## Request defaults and reviewed import

A blank module topic resolves to its catalog discovery goal when preparing an AI request. An explicit topic overrides the starter goal; neither path rewrites saved configuration. `ModuleDefaults.requestContext(module)` combines the catalog purpose, domain editorial requirements and selected preferences without taking private `FeedState`. It is supplied as `input.defaultContext` for direct/ChatGPT feed discovery and story/module answers, and as `input.modules[].defaultContext` for search. An existing item's kind supplies the default when its module is no longer installed. A general workspace thread has no module default or personal-context notes.

Each of the 17 supported kinds has reusable domain guidance; the module editor displays a short localized Default focus. Query text, preferences, retrieved material and personal notes remain data, never operational instructions. Recommendations should explain a concrete match only when supported by selected preferences or confirmed notes. Unknown interests are not invented. Android sends only confirmed context scoped to the requesting module, up to 32 notes, and search retains each module's permitted context IDs. Default guidance does not grant additional scopes or account-memory access.

The optional service receives resolved query and validated module preferences from Android, derives its own `MODULE_GUIDANCE`, and retains its existing confirmed kind/module-ID scope checks. It does not accept arbitrary client instructions as `defaultContext`. Weather and public adapters retain their dedicated flows; the shared guidance does not require them to make AI calls.

A personal-context file with `modules: []` replaces profile fields and context entries while retaining existing module objects, connections, saved/hidden items, delivery settings and local tools. Strict schema and domain validation occurs before clearing active answer/refresh state. Item `why` and relevance are reset so explanations derived from old context are not presented as current.

My context exposes file import, Paste JSON, the generation prompt and export directly. Prepare-import reads and validates the complete candidate before publishing a preview; it does not replace current state. Pasted text may be raw JSON or one JSON code block, with surrounding prose and multiple blocks rejected. The paste editor is bounded to 131,072 characters; the shared context validator retains its 512 KiB UTF-8 limit. Confirm applies the staged object, and cancel discards it. Feed backups remain limited to 8 MiB. A backup includes conversations, attachments, module settings/order and reading history but excludes credentials/account references. Successful full-backup replacement leaves the feed disconnected and delivery off; a context-only import preserves the connection. Validation or storage failure is reported without claiming a successful import.

Prepare in ChatGPT copies a compatible prompt and opens the official ChatGPT site. It does not submit a message, fetch history or read saved memory. The prompt asks ChatGPT to use only reliably available information, leave unknown profile fields empty, and return `modules: []` to retain module choices. Empty entries are valid when nothing reliable is known. MeBot accepts `mebot-context-v1` JSON, not ChatGPT export ZIPs or arbitrary documents. Help & FAQ presents this setup path and the unsupported cases explicitly.
