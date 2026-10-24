# ChatGPT connection

This is the MeBot 0.6.1 implementation contract. The underlying account integration was checked against official documentation on 2026-10-23. It is a local Android integration. It does not proxy a browser session, reuse private ChatGPT endpoints or route subscription tokens through the optional Python service.

## Setup and personal context

Open **You → AI connection → Continue with ChatGPT**, finish browser consent and allow the model check to complete. A saved account can use **Check connection** without being deleted or added again. If the selected model is unavailable, choose another model actually returned for that account. API-key mode remains an explicitly selected, separately billed alternative.

Official sign-in provides verified identity and the model permissions granted by the account. It does **not** provide ChatGPT conversations, saved memory, private connectors or an account-history export. An API key also does not provide ChatGPT memory. MeBot therefore cannot automatically build a personal context file by reading either credential's account history.

The supported setup is **You → My context → Prepare in ChatGPT**. This copies a ready-to-use prompt and opens the official ChatGPT site. The user pastes and submits it there; the prompt asks for only reliably known preferences, unknown profile fields left empty, concise confirmed notes with relevant module scopes, and `modules: []` to retain the app's module choices. Return to MeBot and use **Import file** or **Paste JSON**, review the replacement summary, then confirm. The generated information remains reviewable and editable. If nothing reliable is known, an empty note list is valid; sign-in alone never fills it.

**You → Preferences → Help & FAQ** contains the setup guide first, the copyable prompt, tour replay and searchable answers about connection choice, module defaults, source access, requests, privacy, offline use, voice and alarms. The prompt does not submit itself and the import does not accept an entire ChatGPT export ZIP. Context-only imports preserve accounts and saved reading; a full feed-backup restore has separate replacement behavior and requires reconnecting AI.

## User journey

1. **Continue with ChatGPT** starts an external system-browser sign-in. MeBot has already bound its temporary callback listener before opening the browser.
2. The app shows a cancellable waiting state. Consent returns a one-use code to the loopback listener. The callback page says to return to MeBot; it does not claim that connection is complete.
3. MeBot exchanges the code, verifies the signed identity, checks granted scopes and durably saves the renewable session. The engine then discovers available models and sends a small inference check. Model discovery does not run twice during one connection attempt.
4. Only a completed, nonempty response commits the selected account/model. A failed or cancelled attempt keeps the prior selection. The first successful connection shows the plan-use acknowledgement once for that registration.
5. The active model and Ask composer identify ChatGPT plan use. Manage usage opens `https://chatgpt.com/settings/usage`. API-key billing remains a separate action.

Account labels include a stable local registration suffix so identical email addresses are distinguishable. A new account does not become active merely by appearing in the list. Subscription model slugs are taken from the returned catalogue, without a hardcoded default. Permission to identify the person alone does not authorize plan requests.

## Authentication and identity

The initial authorization request uses the documented dynamic public-client entry point, a stable per-installation host ID, S256 PKCE and fresh state/nonce. The issued client ID is retained and used for token exchange and future sign-in for that registration. The exact redirect URI, including its port, is reused for the exchange. There is no client secret in the APK.

The listener binds `127.0.0.1` on an ephemeral port and accepts only the expected `/auth/callback` request shape. It rejects wrong state, host/path/method, duplicate query fields, malformed encodings, cross-origin request headers and oversized/slow headers. A valid callback is single-use. The browser response has no token/code reflection, caching or referrer forwarding. Its return intent uses `ACTION_VIEW`, the installed package's `${applicationId}.connect://chatgpt/return` scheme, and a matching `DEFAULT`/`BROWSABLE` manifest filter. No explicit component is required. This changes navigation only; authentication data is never accepted from an Android intent. The return matcher rejects query values, fragments, user information and ports. The return flags preserve other Activities, including an active alarm. The callback page has self-contained light/dark styling protected by a CSP style hash, and a manual recent-apps fallback.

ID tokens are verified against the issuer's discovered JWKS. The implementation accepts the advertised RS256 algorithm, validates signature, issuer, audience/authorized party, expiration, issued-at/not-before and the attempt nonce. A returning registration must retain the verified subject. JWT-supplied key URLs and algorithm substitution are rejected. Network requests are confined to the documented OpenAI hosts and do not follow authorization redirects internally.

## Storage and renewal

`EncryptedChatGptStore` holds account bindings, display labels, model metadata and token pairs in one AES-GCM authenticated record, using Android Keystore and `AtomicFile` in no-backup storage. Nonsecret UI snapshots never contain tokens. The selected provider/account/model belongs to FeedState; account references are stripped from portable exports. Pending browser URLs and PKCE material are never saved for process restoration.

A per-account mutex serializes refresh. The new access token, expiry, scopes and replacement refresh token are stored together. Once refresh can rotate a token at the server, a bounded protected transaction completes validation/persistence even if the caller cancels. Reset and saved-token checks prevent an obsolete transaction from recreating erased credentials. A stale unauthorized inference response can invalidate only the access token it actually used.

Every module obtains a current access token before its own inference request. Temporary provider/network failures retain the credential pair. Confirmed terminal refresh failures clear credentials while retaining the registration for a fresh sign-in. Sign-out removes local tokens even if remote revocation fails, and reports storage failures or unconfirmed revocation. Full reset removes local registrations as well; provider-side grants remain managed in ChatGPT settings.

The retrieved token reference names `earliest_refresh_at` without specifying its wire type. MeBot conservatively accepts an absolute Unix-seconds value or RFC3339 timestamp and rejects ambiguous formats. When absent, normal near-expiry refresh applies. This compatibility assumption requires validation with a real account response; it is not claimed as a fully documented format guarantee.

## Inference and limits

Model discovery first recognizes the documented SIWC `models` array, preserving the server's display order and `visibility:list` filter. It also understands the public Models API's documented `object:list`/`data` envelope, using only model IDs actually returned by the account's authenticated request. This compatibility branch is covered by fixtures; a live SIWC server returning it has not been observed in the test environment. Model metadata does not prove task capability: connection still requires a completed inference request, and the user can choose another returned model when a model is unsuitable. Unknown metadata and malformed individual rows cannot invalidate otherwise usable catalog rows. Catalog downloads have a separate 2 MiB bound; token and identity documents retain the smaller bound.

Model failures use a model/connection error, never the identity-verification message. The last failed authentication or model request exposes only its stage, HTTP status, safe request ID, bounded MIME/status fields and allowlisted response-shape/provider-code/parameter descriptions. OAuth URLs, codes, tokens, provider text and personal context never enter diagnostics. A retry clears old diagnostic metadata. Failed model discovery preserves the already verified session and previous active connection.

The subscription transport sends OAuth bearer credentials to the public Responses endpoint only. Requests use `store:false`, `stream:true` and array input. API-only generation controls are omitted. For feed JSON, the schema is supplied as an instruction and the result is locally parsed/validated; the app does not assume the account supports API-key structured-output settings.

The bounded event reader emits identified assistant output-text deltas to the visible UI while keeping them provisional. It ignores provisional deltas for persistence. Direct API and subscription modes both use this real streaming reader for story explanations and conversations. It retains finalized `response.output_item.done` records and reconstructs output only if the completed envelope omits its output list. Nullable content on reasoning/tool records is not interpreted as assistant text. Output indices and item identities are checked, and LF, CRLF, CR and generic SSE message framing are supported. Only `response.completed` with completed status is successful. Failed/incomplete responses, malformed events, explicit errors, premature EOF and timeouts preserve previous content. A limit after emitted text is still a failed request. Factual feed links must occur in search source metadata or citation annotations; fabricated links are rejected. This checks provenance, not every assertion's truth.

The account/workspace may restrict web search or a model. Capability and policy errors offer a model/connection recovery path. Plan limits link to usage settings and pause automatic AI while public sources continue. A deliberate request may retry. No quota percentage or reset date is invented, and no subscription error triggers API billing.

MeBot's configurable daily attempted-request cap is independent of provider allowance. New profiles start at 28; Background updates offers 28, 56, 112, 224, a custom positive amount and Unlimited. Existing stored limits are preserved. Unlimited removes only the app's local cap; account/workspace limits, rate limits and API billing still apply, as does a personal service's independent cap when that route is selected. Feed generation uses one attempt per enabled AI module; an automatic explanation, a conversation turn, a search or a connection/model check uses one. Reusing a current completed explanation does not consume an attempt. A cancelled or failed attempted request remains counted. Public discovery, RSS and local tools do not count as inference attempts.

Module requests include reusable domain defaults and the selected topic/preferences. Personalization still receives only relevant confirmed context with that module's scope; a default brief grants no new access. General AI workspace threads start without personal-context notes. Search retains separate module scopes, while story explanations use the story's module or its kind as the fallback for domain guidance.

## Verification boundary

The 0.5 browser-return repair is retained and remains covered by manifest, cold/warm navigation and callback tests. A saved account and a returned model are insufficient evidence of a working connection; MeBot requires a completed inference check before activating the selection.

Version 0.6.1 retains the finalized-output reconstruction repairs and inspects actual response framing before rejecting a mismatched MIME type. Genuine SSE can complete even when labeled as JSON or plain text; HTTP 200 alone is never sufficient. A structured admission error, an HTML page, an empty body, a plain JSON subscription response without the required stream, an interrupted stream or a failed/incomplete terminal event still fails. Diagnostics identify these bounded shapes without retaining raw body text. No retry is hidden inside the transport.

Direct API checks now send one minimal non-stream Responses inference request rather than only listing models. Personal-service checks call its authenticated ask endpoint with empty personal context; an explicitly selected model must match the configured service model. Existing active credentials are committed only after the new check succeeds. There is no automatic credential-mode fallback.

Opening a story uses the selected connection to stream a deeper explanation. Completed responses alone enter the six-hour, 24-item brief cache, keyed by source content, scoped notes, module settings, selected connection/model and output language. Failed/partial responses do not become a completed cache entry. History and follow-ups keep completed text and validated citations locally. Search uses one attempt across the selected enabled factual modules; result links must occur in official web-search source metadata or assistant output-text URL citation annotations. Unrelated tool output, prose URLs and non-URL annotations cannot certify a source.

Automated tests use synthetic identities, generated RSA signatures, controlled provider responses and real host loopback sockets. Native Compose journeys exercise the real ViewModel/engine with test-only auth/provider adapters. These tests establish application behavior for the covered cases; they do not prove live OpenAI account eligibility or a working browser/Keystore implementation on a particular phone.

The release must still be installed on the owner's phone to complete official consent, a real completed request, web-search access, session renewal and return-from-browser behavior. Killing the Android process during consent requires a fresh attempt. Browser handling of intent return links varies, so the callback also supports switching back manually. Alarms remain independent and retain their own physical-device acceptance steps.

This route's documented availability is for eligible local/open-source use and selected additional applications. Commercial or remotely hosted distribution requires checking the applicable access terms separately. An open-source APK release is separate from permission to operate a paid or remotely hosted distribution.

## Official references

- [Registration and sign-in](https://developers.openai.com/siwc/token-sharing-open-source/sign-in)
- [Accounts and sessions](https://developers.openai.com/siwc/token-sharing-open-source/profiles-and-sessions)
- [Responses streaming events](https://developers.openai.com/api/reference/resources/responses/streaming-events)
- [Models and inference](https://developers.openai.com/siwc/token-sharing-open-source/models-and-inference)
- [Public Models API response](https://developers.openai.com/api/reference/resources/models/methods/list)
- [Token reference](https://developers.openai.com/siwc/token-sharing-open-source/token-reference)
- [Errors and recovery](https://developers.openai.com/siwc/token-sharing-open-source/errors-and-recovery)
- [Preview limitations](https://developers.openai.com/siwc/token-sharing-open-source/preview-limitations)
- [UI guidance](https://developers.openai.com/siwc/ui-ux-guidelines)
- [User explanation](https://learn.chatgpt.com/docs/sign-in-with-chatgpt)
- [Android browser intent requirements](https://developer.chrome.com/docs/android/intents)
