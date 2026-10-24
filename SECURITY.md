# Security policy

MeBot is local Android software with optional public-source retrieval, ChatGPT subscription access, direct API access and a separately operated personal service. Sensitive data can include personal context, conversations/attachments, cached explanations, local check-ins, alarm plans, exports, OAuth sessions, API/service credentials and release-signing material. The app does not include analytics or operate a shared MeBot account server.

This document describes the current v0.6.1 implementation and how to report a problem. See [privacy](docs/PRIVACY.md) for user-facing data handling and [verification](docs/VERIFICATION.md) for tested behavior and remaining device/provider checks. It is not a claim that the code has undergone an independent security audit.

## Report a vulnerability privately

Use the repository's **Security → Report a vulnerability** action when private vulnerability reporting is enabled. On GitHub this is the [MeBot security-advisory form](https://github.com/EmmanuelMmanda/MeBot/security/advisories/new). Its availability depends on the repository configuration; this document does not assume that a private channel has already been activated.

If that action is unavailable, ask the maintainer through an existing private channel. If none is available, open an issue containing only a request for a private security contact. Do not put exploit details, sensitive files or exposed credentials in that public request. No separate security email address, response-time commitment or bounty program is established here.

A useful private report includes:

- App/release or commit version, affected component and Android/device details; for the service, its version and affected endpoint.
- Expected behavior, observed behavior, impact and minimal reproduction steps.
- A small synthetic proof of concept or redacted evidence sufficient to reproduce the issue.
- Whether a credential or private data may have been exposed, without sending the actual secret.

Never attach a personal backup, raw OAuth callback URL/code, token, key, authorization header, signing keystore, QR checkpoint secret or private provider request/response to a public report. Safe operation codes or sanitized request identifiers may help; verify that screenshots do not show identity or personal notes.

There is no published long-term-support window for older releases. Identify the affected version and, where practical, check the latest release/current development branch. Fix availability and disclosure timing should be coordinated with the maintainer rather than assumed.

## Trust boundaries

### Device storage and exports

Ordinary app data relies on Android's application sandbox and device storage protections. MeBot does not provide a separate app passcode or application-level encryption for the complete feed, conversation or local-tool stores. A compromised device, privileged operating system or compromised app process can exceed these protections.

API keys and service tokens are AES-GCM encrypted using Android Keystore. ChatGPT registrations and token pairs use a separate authenticated encrypted atomic file in no-backup storage. Portable context/feed schemas do not contain these credentials; account references are stripped from portable exports. Secret form drafts are not saved into recreation state.

Manual exports are readable personal JSON. The user selects a destination through Android's document picker, and a chosen cloud-backed provider can upload the file under its own policies. Exports are not encrypted by MeBot. Android automatic app backup and device transfer are disabled in the manifest/extraction rules. Review the [manifest](app/src/main/AndroidManifest.xml) and [extraction rules](app/src/main/res/xml/data_extraction_rules.xml).

Import input is bounded, decoded and validated before replacement. Unsupported versions and wrong formats are rejected. Context/feed imports require a review confirmation; cancellations or stale completions must not replace current state. Feed restoration clears credentials only after durable replacement and disables background delivery. Local-tool restoration starts imported alarms disabled.

Application reset clears local repositories, credentials, account registrations, scheduled feed work and operational alarms. It does not retract an already transmitted request, delete separately exported files or selected source audio, revoke all provider-side grants/data, erase a remote service database, or promise forensic secure deletion from storage.

### ChatGPT authorization and inference

The integration uses official public endpoints with state, nonce and S256 PKCE. It verifies the issuer's advertised RS256 signature/JWKS, issuer, audience/authorized party and token times. A returning registration must retain its verified subject; email alone is not an account identity. No client secret or copied browser session is shipped in the APK.

The temporary callback listens only on `127.0.0.1` with an ephemeral port. It checks the expected host/path/method, single-use state and bounded request shape, and rejects malformed/duplicate parameters. The custom Android return link carries navigation only, not authentication data. Pending authorization is cancelled on explicit cancellation/reset; process death requires a new attempt.

Refresh rotation is serialized and committed atomically. Reset/generation guards prevent an old transaction from restoring erased credentials. A stale unauthorized response must not invalidate a newer token pair. Sign-out removes local tokens and attempts remote revocation; if provider revocation cannot be confirmed, the UI says so. Provider-side grants remain manageable in ChatGPT settings.

OpenAI documents eligible Plus/Pro use in participating local/open-source applications with separate inference consent. Account identity does not grant ChatGPT conversations, saved memory, account history or private connectors. This route also does not provide image-generation/audio/video/Files APIs or hosted MCP; web search depends on account/model support. Native Android voice features and reviewed text attachments are separate from those provider APIs. See the official [quickstart](https://developers.openai.com/siwc/quickstart), [models and inference](https://developers.openai.com/siwc/token-sharing-open-source/models-and-inference) and [limitations](https://developers.openai.com/siwc/token-sharing-open-source/preview-limitations), checked on 2026-10-23.

Subscription inference uses the public Responses endpoint with `store: false`, `stream: true` and array input. It does not call private ChatGPT backend routes or silently fall back to separately billed API keys. Model availability comes from the authenticated account catalog; only a completed usable inference check commits a connection/model change. A signed-in identity alone is insufficient.

### Network and AI data

Network clients use bounded HTTPS requests with timeouts and public-host protections where appropriate. They do not forward authorization through redirects. Credentials, authorization URLs, callback/query values, raw provider errors, prompt bodies and private text must not enter logs or UI diagnostics. Current diagnostics expose bounded allowlisted stage/code/shape metadata rather than raw bodies.

Only confirmed personal context relevant to the selected module kind is sent to AI. A general conversation starts without saved context notes. Sources, attachments, history and configured topics are untrusted data, not permission to execute commands, access accounts or expand scope. A new connector or telemetry destination requires a separate explicit product/privacy decision.

Factual main URLs must occur in search-source metadata or validated citation annotations. This establishes source provenance, not the truth of every generated claim. Original source links remain available. Only a validated completed response may be persisted as a completed answer/cache entry; provisional streaming deltas and interrupted output are not success. Horoscope is optional entertainment.

Requests set `store: false`, but that is not a separate zero-retention guarantee. The provider's processing policies and account controls still apply. MeBot's daily request cap counts attempted calls, including failures/cancellations with uncertain upstream outcome. **Unlimited** removes only that local cap; provider allowances, rate limits, API charges and personal-service limits remain.

### Alarms, camera and speech

Local alarm scheduling/ringing does not require AI or connectivity. Operational metadata needed after reboot is in device-protected storage; diary/profile data is not copied there. Alarm labels and operational instructions may be visible over the lock screen. Avoid private information in labels or spoken wake-up messages.

QR scanning is on-device and optional. The app does not retain camera frames or treat a scanned checkpoint as a URL to open. A QR checkpoint can be copied and does not establish location, identity or wakefulness. Emergency stop remains available; the app has no device-admin, accessibility-service, anti-uninstall or permission-bypass mechanism.

Selecting custom audio grants access to one document, not the media library. The app retains its URI/display name rather than copying or uploading its audio bytes. The permission ledger releases only READ grants acquired by that picker when no saved/active/scheduled/draft reference needs them, with a bounded restoration window for interrupted drafts. Imported audio URIs/grants may be unavailable on another device; bundled fallback remains available.

Optional alarm speech uses an installed offline voice and leaves the tone ringing when speech is unavailable. The text is handled by the selected phone engine, not MeBot's AI connection. Workspace voice input/read-aloud use available Android services whose own processing/network policies may differ. MeBot does not retain voice recordings or request broad microphone/media access itself.

Force-stop, power-off, revoked permissions, DND/audio routes and manufacturer restrictions can prevent ringing. Host tests do not establish physical-device reliability. See the [device acceptance procedure](docs/BUILD.md#device-acceptance-before-relying-on-alarms).

### Optional personal service

The service requires a private Bearer token on every API endpoint and keeps its provider key in operator-controlled configuration. It is designed for one person per deployment; its shared service token is not a multi-user authorization system. Do not expose it as a public anonymous generation endpoint.

The supplied Compose configuration binds to host loopback. The operator must supply HTTPS routing, protected host access, credential management and private backups. The SQLite file has restrictive permissions but is not application-encrypted. It can contain feed/cache data and scheduled-request context; deleting data on the phone does not erase that server.

Scheduled work and optional webhooks require explicit configuration. A webhook carries a new-item count, not article text or personal context, and does not imply an already configured Android push service. Server request limits constrain attempts rather than money; provider spending controls still matter. See [service deployment and security boundaries](service/README.md).

## Public source and release integrity

Keep personal seed assets, exports, credentials, OAuth material, signing stores, service databases, environments, local paths and generated builds out of public source/history/archives. Inspect archive contents explicitly; Git ignore patterns do not protect an arbitrary ZIP of a working directory. Use synthetic identities, fixtures and screenshots.

Release signing keys stay private. A public signing certificate or APK checksum can be shared for verification, but does not replace protection of the corresponding private key. A compatible existing signing identity is required for in-place Android updates. The default pull-request workflow does not receive release-signing secrets or publish releases.

Keep dependencies and CI actions pinned, review new licenses/permissions/network destinations, and document changes to these boundaries. The [development guide](docs/DEVELOPMENT.md) and [contribution guidance](CONTRIBUTING.md) describe the required verification and review process.
