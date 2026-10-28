# MeBot personal service

This optional service keeps the provider key on your server, builds source-grounded feed items, answers contextual questions, caches the results and can prepare scheduled briefings. The Android app can also use its personal direct-key mode without this service.

The service is intended for **one person per deployment**. It has no multi-user account or tenant system. Every endpoint requires the same private service token. Keep it behind HTTPS when connecting from the Android app. Android requires an HTTPS hostname resolving to a public address, normally on port 443; localhost, LAN endpoints and custom ports are not supported by this build.

## Start with Docker

1. Copy `.env.example` to `.env`.
2. Set `OPENAI_API_KEY` privately. Generate a different `MEBOT_SERVICE_TOKEN` using `python3 -c "import secrets; print(secrets.token_urlsafe(32))"` and put it in `.env`.
3. Run `docker compose up -d --build` from this directory.
4. Put your existing HTTPS reverse proxy in front of `127.0.0.1:8787`. Forward the Authorization header and allow sufficient upstream response time for a search-backed briefing. The provided Compose file binds only to the host loopback interface.
5. Open **You → AI connection → Use an API key instead → Advanced settings → My service**. Enter the public HTTPS service URL and a **Model** matching `MEBOT_MODEL`, then enter the **Service token** and choose **Check and connect**. Do not enter the OpenAI key in service mode.

The two credentials have different jobs. The provider key authorizes paid OpenAI calls from the service. The service token authorizes access to your personal MeBot deployment. Neither credential is sent in a model prompt or saved in SQLite.

`/health` checks the service connection; the first feed request checks actual model access and billing availability. This repository does not contain a deployed public endpoint or a working provider credential.

## Run locally with Python

Use Python 3.12 or later:

```sh
python3 -m venv .venv
.venv/bin/python -m pip install -r requirements.txt
```

Provide `OPENAI_API_KEY` and `MEBOT_SERVICE_TOKEN` as process environment variables using your shell's secure environment setup, then run:

```sh
.venv/bin/python -m mebot_service.app
```

The local listener is `127.0.0.1:8787`. The Python entry point does not automatically load `.env`; Docker Compose loads the configured file. Avoid putting real credentials in shared commands, screenshots or source control.

## API

Send `Authorization: Bearer <service-token>` on every request. JSON field names follow the Android contract in `../docs/FEED-CONTRACT.md`.

| Method and path | Result |
| --- | --- |
| `GET /health` | Authenticated service status and version |
| `POST /v1/feed` | Generate or reuse a briefing for supplied modules and scoped context |
| `GET /v1/feed/latest` | Read the most recently stored items without an AI call |
| `POST /v1/ask` | Answer a question with source citations |
| `POST /v1/search` | Search the selected enabled modules for up to eight sourced results |
| `GET /v1/status` | Configured model, request usage, limit and scheduler state |
| `PUT /v1/schedules/{id}` | Create or replace a scheduled briefing |
| `GET /v1/schedules` | Read schedule status without returning private context |
| `DELETE /v1/schedules/{id}` | Remove a schedule |

Example non-personal requests are in `examples/feed-request.json` and `examples/search-request.json`. They can be edited for a private deployment; no personal configuration is baked into the service.

Errors use stable codes: `unauthorized`, `invalid_input`, `model_mismatch`, `provider_error`, `invalid_response`, `rate_limit`, `limit_reached`, or `operation_error`. Provider errors and validation responses do not echo keys, prompts or upstream response bodies. A failed refresh preserves the last successful feed.

The service uses its configured model. If a request explicitly names a different model, it returns HTTP 400 with `model_mismatch` before inference or a cached success; it never silently substitutes a model during a model-access check. `GET /v1/status` returns the configured model. Match the app's selected model to that value, or deliberately change the server configuration. Existing clients omitting `model` use the server setting.

## Optional voice capabilities

Two-way voice and recorded-audio transcription are separate **OpenAI API** capabilities, not features supplied by ChatGPT sign-in or a ChatGPT plan. They are disabled by default. After an owner explicitly chooses an API project/model and accepts API billing, an operator can set `MEBOT_REALTIME_MODEL` and/or `MEBOT_TRANSCRIPTION_MODEL` in the private service environment. `MEBOT_REALTIME_VOICE` is an optional server-side voice choice. The provider API key must remain on the service; it is never returned to Android. There is no alternate GPT-Live or ChatGPT-entitlement path.

The authenticated `GET /v1/realtime/capability` reports configured capabilities, the shared daily budget, and whether a Realtime call is already active for the authenticated service principal. `POST /v1/realtime/calls` accepts a bounded final ICE-gathered SDP offer and only confirmed, scoped context plus separately labeled selected-item evidence. The service sends one server-owned request to the fixed official `https://api.openai.com/v1/realtime/calls` endpoint, stores only a request hash and the validated upstream call ID, then returns `{answerSdp, callReference}`. `callReference` is the caller's opaque UUID; clients never provide provider URLs or provider call IDs. `POST /v1/realtime/calls/end` requests an idempotent server-side hangup. A SQLite-backed lifespan reaper terminates calls on explicit end or after 15 minutes and retries failed hangups; deploy with durable storage so this state survives restarts. The current shared service bearer is one principal, not an individual MeBot-user identity, so the active-call cap is one call across that principal. An uncertain provider-create response fails closed for up to the provider maximum call duration rather than risking an untracked overlapping call.

These are safety bounds, not a guaranteed dollar cap: API usage is billed separately by OpenAI and may continue until hangup succeeds or provider limits apply. Realtime requests cap each response at 1,200 output tokens, and the app stops a call at 15 minutes or 24 user turns and on background, lock, or context change. The service reserves the shared daily request budget and the feature cap atomically before each call creation (`MEBOT_REALTIME_DAILY_SESSION_LIMIT=2`) or transcription (`MEBOT_TRANSCRIPTION_DAILY_REQUEST_LIMIT=6`); limits of zero disable that capability. Realtime instructions have no web-search/source-verification tool: personal context is not factual evidence, caller citation references are not independently verified, and optional further-reading links are not evidence. Missing current evidence must result in an honest inability to verify, not invented news or dates.

`POST /v1/transcriptions` accepts one explicit `audio/mp4` body (up to 2 MiB), forwards it in memory to the official OpenAI audio transcription endpoint using the configured `MEBOT_TRANSCRIPTION_MODEL`, and returns editable text. Audio is not saved. The client must ask before uploading, must not automatically send the transcript, and may not fall back to Android recognizer intents or ChatGPT OAuth. Live paid sessions, model access, service deployment, API-key provisioning, and billing acceptance require separate owner approval; automated tests use a fake HTTP transport and never call a paid provider.

## Sources and personalization

Each enabled feed module makes its own provider request. It receives only confirmed context entries whose `scopes` contain its kind or identifier. A horoscope request, for example, does not receive entries scoped only to news or opportunities. Entries retain their review timestamp in the prompt. Unconfirmed entries are excluded even when sent by a custom client. Repeated context IDs are deduplicated before the model request.

Factual modules use the Responses API web-search tool with a required search, structured output and `store: false`. The service accepts a factual item's main URL only when it appears in the API's search-source metadata or citation annotations. Merely writing a URL in model output does not qualify. Duplicate canonical article URLs are combined across modules. This verifies source provenance, not every semantic assertion in a summary; the reader should still have access to the original sources.

Horoscopes are optional entertainment, generated without web search. Their output has no invented factual sources or deadlines. An enabled horoscope without a configured sign produces no item.

Configured source URLs are retrieval preferences, not server-side fetch targets. This service does not fetch arbitrary request-provided URLs or connect to private accounts. GitHub, calendar and other private connectors require separate implementations and permissions.

Version 0.6.0 accepts the mobile catalog's 17 module kinds, including `media`, and applies a purpose-specific research instruction to each. `media` selects useful videos from original creators or official channels and links to their authorized watch pages. Requests can include up to 12 recent items per module in `recentlyShown`; only that module's history is passed to its model call. Direct video, course, repository, event and application links carry a `contentType` so Android can show an appropriate action. Model output cannot invent preview-image metadata. Android obtains imagery from the cited publisher page after text is saved.

Each module accepts a `preferences` map using the same explicit keys and choices as the Android module editor. Examples include a learning level, event window, media duration, location and response language. Unknown keys, unsupported choices, control characters and locations longer than 160 characters are rejected. An absent map preserves the existing defaults. Preferences select content without expanding a module's access to personal context. A module's configured response language overrides the request language; its configured location overrides the general region.

Generated feed items can include up to four source-supported `keyPoints` (400 characters each), a `nextStep` (800 characters), and a `caveat` (600 characters). Unknown detail stays empty. These fields use the same source evidence as the item; verifying a URL's provenance does not independently prove every statement in the text.

The service accepts optional publisher media and typed weather data in items supplied to `/v1/ask`, keeping the mobile item contract intact. MeBot's direct public weather, public GitHub search and technical-news fallback adapters run on Android; they are not private-account connections or remotely hosted service features. See [discovery and media](../docs/DISCOVERY-MEDIA.md) for their actual capabilities and limits.

## Conversations and search

`POST /v1/ask` remains compatible with existing question/item requests. Version 0.6 clients may also send:

- `history`: up to 16 recent `{role, text}` messages, with `user` or `assistant` roles and at most 36,000 characters combined. The current question remains in `question`.
- `attachments`: up to three `{name, text}` files, with names up to 160 characters and at most 24,000 characters of text combined. NUL characters and control characters in filenames are rejected. Files and quoted history are supplied as untrusted data; they cannot grant permissions or alter the service instructions.
- `moduleKind`: the selected catalog kind, or an empty string for a general conversation with no saved personal context. An attached item's kind takes precedence; a conflicting nonempty kind is rejected. Older clients omitting this field retain the original news scope for questions without an item.
- `module` (v0.6.1): optional validated module settings for the current question, including its custom focus and preferences. Its kind must match a supplied `moduleKind`; its ID and kind must match an attached item. The service supplies the default editorial guidance for every module kind, and applies the selected response language and preferences. These fields do not grant access to unrelated context. Arbitrary client-provided instructions or `defaultContext` fields are not accepted.

The service does not persist conversation history or attachments. Android keeps the durable thread locally and submits the bounded recent window. This service returns a completed JSON answer; it does not expose a streaming or voice endpoint.

`POST /v1/search` accepts `query`, `modules`, `context`, `region`, `timezone`, `language`, `model`, and `limit` (1–8, default 8). All selected modules must be enabled. Weather uses its dedicated forecast flow and horoscope is entertainment, so neither is included in this factual search endpoint. Duplicate module IDs are rejected.

Search performs one web-search-backed provider call. It sends only deduplicated confirmed notes relevant to the selected modules, with an explicit list of permitted context IDs for each module. Every result is assigned to one supplied module ID, and the service sets its kind from that selected module. Main links must be present in provider source metadata; invented links and unknown modules are discarded. A response claiming results but containing no valid sourced items fails with `invalid_response`. An empty result list is a valid outcome. Search shares the configured model and daily request budget, and never replaces the cached daily briefing.

## Scheduling and delivery

Set `MEBOT_SCHEDULER_ENABLED=true` to activate the in-process worker. The default is off. Create a schedule by sending the JSON in `examples/schedule-request.json` to `PUT /v1/schedules/morning` with your service token.

A schedule runs at its configured interval after creation. Quiet hours use its IANA timezone, including a quiet period crossing midnight. Set equal quiet-start and quiet-end hours to disable quiet hours. A persisted lease prevents the same due job from being claimed twice; changes and deletion invalidate old completion callbacks. After a process interruption, an abandoned lease becomes eligible again after 30 minutes.

The worker stores new feed items. If `MEBOT_WEBHOOK_URL` is explicitly configured, it sends an HTTPS JSON event only when new item IDs were stored:

```json
{"event":"mebot.feed.ready","newItemCount":3}
```

The optional webhook token is sent as a Bearer header. No article text or personal context is included. A failed webhook is shown as `delivery_error`; it is not automatically retried. A notification service must be connected by the operator to deliver that event to a device. **This service does not claim an already configured Android push channel.** The Android app's own background refresh remains available independently.

Use one Uvicorn worker as configured. The scheduler is intended for periodic preparation, not precise clock-time alarm delivery.

## Configuration and limits

| Setting | Default | Behavior |
| --- | --- | --- |
| `MEBOT_MODEL` | `gpt-6-luna` | Server model; an explicitly different request model is rejected |
| `MEBOT_DAILY_REQUEST_LIMIT` | `48` | Maximum attempted model calls per UTC day, reserved atomically before a call |
| `MEBOT_TIMEOUT_SECONDS` | `75` | Timeout for an individual upstream request |
| `MEBOT_CACHE_SECONDS` | `300` | Reuse identical briefings to prevent repeated calls |
| `MEBOT_DATABASE_PATH` | `./data/mebot.sqlite3` | Private persistent data |
| `MEBOT_SCHEDULER_ENABLED` | `false` | Enable the periodic worker |
| `MEBOT_UPSTREAM_URL` | `https://api.openai.com/v1` | Operator-controlled HTTPS Responses-compatible endpoint |

The daily cap counts upstream attempts, including failures whose billing outcome may be uncertain. Each module can make one call, and each search-backed call can invoke up to three web-search tool calls. This is a request limit, **not a guaranteed currency-denominated spending cap**. Set an appropriate provider-side spend limit as well. Cached briefings and reading stored items do not consume the service's model-call allowance.

Request bodies are bounded to 192 KiB. A request supports at most 32 modules, 64 context entries and 64 recently shown item references. The conversation and search limits above apply in addition to this byte limit. The service retains at most 500 feed items and removes request-cache records older than a day during successful updates. Scheduled request context remains stored until its schedule is replaced or deleted. The database is written with restrictive file permissions; it is not itself encrypted. Use protected server storage, encrypted disks/backups where needed, and private server access.

## Verification

```sh
.venv/bin/python -m pip install -r requirements-dev.txt
.venv/bin/python -m pytest -q
```

Tests exercise authentication, bounded preferences/history/attachments, confirmed scoped memory, multi-module search assignment, source metadata, ungrounded-link rejection, error sanitization, caching, budget concurrency, schedule persistence and revision handling, quiet hours, and real HTTP traffic to a local fake provider. A real OpenAI key is not required for those tests. Paid OpenAI generation, Docker daemon operation, an external HTTPS proxy and actual device push delivery require deployment-specific verification.

Official API references: [Responses web search](https://developers.openai.com/api/docs/guides/tools-web-search), [structured output](https://developers.openai.com/api/docs/guides/structured-outputs), [GPT-6 Luna](https://developers.openai.com/api/docs/models/gpt-6-luna).

Blank module topics receive a useful module discovery goal when the request is prepared. Explicit topics override that goal, and missing profile details are never treated as confirmed preferences. This behavior does not alter saved module choices.
