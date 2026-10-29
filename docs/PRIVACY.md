# Privacy and data

This describes the latest published MeBot app, including story-specific AI questions and Original Read. See [release status](INSTALL.md).

## Provider and update privacy

Selected provider requests go only to that configured provider/endpoint with approved scoped context. Credentials are isolated and omitted from portable exports. Claude/Gemini/OpenRouter use their own billing and capability rules. A compatible private/localhost HTTP endpoint requires explicit consent and cannot receive a saved API key over HTTP. Software Updates contacts the public GitHub release API and asset hosts without personal context or AI credentials. The app has no added analytics service. Relevance feedback stays in local persisted state and may be summarized within the same module’s approved AI request. Capture inputs in public documentation are public records, not a user's personal profile.


MeBot keeps local utilities, conversations and cached reading on the device, retrieves configured public feeds, and optionally uses an explicitly connected ChatGPT account, API key or personal service. Recorded voice-note transcription, when configured, uses a separate optional service and API allowance; it is not enabled by the text-provider connection. There is no advertising, analytics client, separate MeBot account service or automatic ChatGPT memory sync. Daily notes, alarm plans and focus records are not included in AI prompts. Personal context is a separate, editable collection of explicitly supplied notes.

This document describes the code in this repository. It does not cover another application, a modified build, an operating-system service, or a document provider you choose when exporting a file.

## What is stored

| Data | Where it is kept | Purpose |
| --- | --- | --- |
| Optional name, intention, preferences, daily notes, arrival/departure choices, outcomes, focus records, and alarm plans | App-private credential-protected DataStore JSON | Restore your personal space after closing the app. |
| Feed profile, scoped context, module configuration/order/preferences, cached stories, saved/hidden items and delivery/request counters | A separate app-private credential-protected DataStore JSON | Personalize and retain the information experience. |
| Local AI conversations, selected text attachments, completed story explanations and citations | The same app-private feed DataStore JSON | Reopen threads, retain explicitly supplied conversation material and reuse completed explanations. They are included in an explicit feed export. |
| Voice-note recording for an unsent message, where offered | Temporary M4A file in the app-private cache, linked to its editable draft in workspace state | Keep audio on the device until you discard the draft, send its text, or delete the conversation. The recording is not included in portable backups; only a separate, confirmed transcription request uploads it. |
| Opened-item identifiers and timestamps, when reading history is enabled | The same app-private feed DataStore JSON, bounded to 1,000 entries | Temporarily reduce repeats in local ranking. The switch stops new recording and use for ranking; clear removes the saved timestamps. |
| Provider key or service token | AES-GCM ciphertext in private preferences; encryption key in Android Keystore | Authenticate the explicitly configured AI connection. It is excluded from exports. |
| Optional voice-service URL and token | A separate AES-GCM encrypted preferences namespace; encryption key in Android Keystore | Authenticate to the separately configured voice service. It is distinct from text-provider credentials and excluded from feed exports. |
| ChatGPT identity labels, issued client registrations, installation identifier, scopes, discovered model catalogue and OAuth tokens | One atomic AES-GCM encrypted file in app-private no-backup storage; encryption key in Android Keystore | Maintain separate renewable sessions. Tokens and registration data are excluded from exports and saved UI state. Only an opaque active account reference is stored in local feed configuration; that reference is removed from exports. |
| Alarm plans, labels, checkpoint code/label, language, occurrence identifiers, active/queued sessions, generated math questions/progress, snooze/follow-up state, audio restoration values, and recent operational events | App-private device-protected SharedPreferences | Restore and operate alarms across reboot, including before first unlock. |
| Audio-file URI identifiers for READ grants acquired by the alarm picker, and draft restoration deadlines | Separate app-private device-protected SharedPreferences; the grants themselves are maintained by Android | Retain selected-file access while needed and release only the grants this feature acquired. |
| An exported JSON backup | The document location you select | A portable copy you control. |
| An exported checkpoint QR image | The image destination you select | Display or print a checkpoint away from the phone. |

The personal repository does not open from the Direct Boot alarm receivers. Daily notes and the profile intention are not copied into the device-protected alarm store. Alarm labels, checkpoint labels, chosen sound identifiers/URIs/display names and optional spoken wake-up messages **are** copied because the alarm needs them while the device is locked. A spoken message is audible to anyone nearby when its alarm rings.

The app relies on Android's application storage boundary for personal JSON and operational preferences; the AI credential has an additional Keystore encryption layer. Optional app lock is a UI privacy gate, not encryption of these records. Someone who can use the unlocked app can read them. Exported JSON is readable text; local-tool exports can include notes and checkpoint values, and feed exports include personal context.

## Suggestions and Original Read

Tapping a story’s question icon opens its AI angles; when no saved set exists, that tap requests item-specific questions from the selected provider but does not send a question. When available, the first set contains five distinct questions. **More angles** requests one additional provider batch when connected and capacity remains, appending rather than replacing earlier choices. There is no local offline fallback. **Send** submits a selected question; **Edit** prepares an editable draft. Existing scoped-context and provider billing rules apply. Original Read is AI-authored and labelled with its Created time, separate from publisher dates. Supporting evidence and optional further reading are distinct; neither is permission to broaden personal-context access.

## Network and AI processing

Starting the feed, returning to stale content or explicitly refreshing can contact the enabled public adapters and configured source sites. Connected AI uses web research first; optional RSS sources include OpenAI, GitHub Blog and Google AI. Hacker News/Algolia provides public technical-news fallback. GitHub search receives the owner-entered query and selected focus/programming-language filters. Open-Meteo geocoding receives the module location override or profile city/region, then its forecast API receives that resolved location; the app does not request GPS. These requests do not include private context notes, ChatGPT tokens or API keys.

Publisher-image enrichment requests the linked source page and its published image URL, which may use a publisher CDN. The remote sites receive normal request/network metadata. Images are cached on-device (12 MiB memory, 32 MiB disk, up to seven days) and downsampled before display. Images and public discovery do not consume the local AI request allowance. Opening a resource launches its original URL in the chosen app/browser; that destination’s privacy practices apply.

After an AI connection is configured, a refresh can send the module query/preferences, relevant confirmed context, region, date and language to the configured provider. Reusable module defaults describe a domain and research requirements; they contain no personal identity and grant no extra access. Context is filtered by module kind and limited to 32 entries per module. Opening a story starts an explanation request when no reusable completed explanation exists; it sends the stored story, module defaults/preferences and its explicitly scoped context. Keyword search sends the submitted query and selected enabled factual module configurations, retaining separate note scopes for each module. The provider may receive identifying details that the owner deliberately puts in context. Local check-in notes and focus/alarm records are not automatically copied into it.

The AI workspace stores local conversation turns, but a new general thread sends no personal-context notes. Choosing a module or opening a story permits only confirmed notes scoped to its kind. Sending a message includes the current question, a bounded recent history of at most 16 turns / 36,000 characters, and that thread's attached text. Up to three UTF-8 `.txt` or `.md` files may be selected through the document picker, at most 48 KiB per file and 24,000 combined characters per conversation. Attachments are visible, reviewable and removable before sending. Selecting a file stores its text locally; the content reaches the configured AI provider when a message is sent. It is not a general file-system upload or access to other files.

Completed story explanations are cached locally for reuse for up to six hours when their source and relevant configuration match. Partial streamed text is transient and is not saved as a completed assistant turn. Previously completed explanations may remain readable offline after that reuse window. There is no promise of full publisher article access or a bypass of paywalls. Video/resource actions open the verified original destination, whose account and privacy rules apply.

**ChatGPT mode** opens the official OpenAI authorization page in the system browser. MeBot requests identity, renewable session and direct plan-usage permissions. It validates the returned identity and uses the issued OAuth access token only with the documented public API. A temporary callback listener accepts a matching one-use response on the phone's loopback address; it is not exposed on the local network. Pending authorization URLs, codes and verifier values are not written to backups or UI saved state. A completed connection check sends no personal context.

Eligible ChatGPT requests share the account's applicable plan limits. The model catalogue and web-search permission come from that account/workspace. Sign-in does not provide ChatGPT chat history, memories or private connectors. The app uses only the explicit context described above. See OpenAI's [Sign in with ChatGPT explanation](https://learn.chatgpt.com/docs/sign-in-with-chatgpt) and the [integration contract](CHATGPT-CONNECTION.md).

**API-key mode** calls OpenAI with the owner's key and separate API billing. The key is encrypted on the device and never embedded in the APK, source, JSON backup or model prompt. The optional personal service keeps the provider key on that server and gives the app a separate service token. Neither API-key mode receives or forwards ChatGPT OAuth credentials. The application never switches to API billing because a subscription request fails.

Responses requests set `store: false`. That flag is not a promise that the provider has no other processing or retention obligations; the provider's account settings and current policies apply. See OpenAI's [API data controls](https://developers.openai.com/api/docs/guides/your-data) and [key-safety guidance](https://help.openai.com/en/articles/5112595-best-practices-for-api-key-safety).

The optional service stores feed items, request-cache records and scheduled-request context in a protected SQLite file. The database is not application-encrypted. Credentials come from environment/secret configuration and are not stored in that database. Service reset, backup and host access are separate from erasing the phone. See [service data handling](../service/README.md).

There is no automatic connection to the owner's ChatGPT history or memory. This applies to both official account sign-in and API-key mode. The supported import is reviewed MeBot context JSON, selected as a file or pasted into **You → My context**. **Prepare in ChatGPT** copies a generic schema-compatible prompt to the Android clipboard and opens `https://chatgpt.com/`. It does not copy existing app notes into that prompt, submit it automatically or retrieve an account export. The user sends the prompt inside ChatGPT and chooses which generated information to bring back. That separate ChatGPT interaction follows the account's settings and policies. Empty or unknown personal information should remain empty; generation does not guarantee that remembered information is accurate.

The setup prompt and FAQ are also readable in the app without connecting an account. Import requires review before replacing personal context. A private build may include an initial profile asset; anyone who receives that personalized APK can inspect the asset, so it must not contain credentials or confidential documents. The public source archive excludes that private asset.

Turning on feed delivery allows periodic background requests, including AI calls when connected, subject to the configured attempted-request limit. New profiles start at 28 attempts per local day; existing chosen limits are retained. **Unlimited** removes MeBot's local cap while retaining usage counts. It does not remove provider quotas, rate limits, API charges or a personal service's independent cap. Quiet hours suppress notifications, not all background retrieval. At most one feed notification is posted per local day. Exact execution time depends on Android scheduling. Turning delivery off cancels its periodic work; disconnecting removes the credential and invalidates pending AI results.

## Lock-screen exposure

The ringing interface can appear over the lock screen, and the alarm notification uses public visibility. The alarm label and operational instructions can therefore be visible without opening the main app. The alarm screen may also show the checkpoint label. Use labels you are comfortable displaying there; daily diary content is not included in these notifications.

An emergency stop is available without completing the checkpoint. The checkpoint is a personal waking aid, not an access-control system. Its QR code can be copied; scanning it does not establish a person's location, identity, or alertness.

Feed notifications use private lock-screen visibility. Their normal unlocked content includes a story title. Android notification settings still determine what is shown on a particular device.

## Camera and permissions

The camera is optional. The daily visit, focus timer, history, settings, and alarms without a required QR checkpoint work without it. Camera permission is requested when the person chooses to scan a checkpoint from the active alarm interface.

The scanner decodes the QR locally. Barcode image output is disabled, and this app does not retain camera photos or video. A scan is compared with the code stored for the active alarm; it is not opened as a web link. The generated checkpoint PNG is created only when you explicitly export it.

Alarm-related permissions support precise scheduling, notifications, the full-screen alarm interface, foreground audio playback, vibration, a wake lock, audio-volume handling, and rescheduling after boot. The setup screen shows relevant permission status and links to Android's controls. Internet/network-state permissions support public feeds and the optional configured AI connection. No permission provides access to ChatGPT account memory.

The app does not request contacts, location, or broad media-library access. Where a build exposes the optional voice-note action and a separate transcription service reports that it is available, the app requests microphone permission only after you choose to record. The M4A remains an editable draft in the app-private cache; it is not uploaded automatically or included in portable backups. Discarding it, sending its text, deleting its conversation, or erasing app data removes the app-owned recording. Before a configured service receives audio for transcription, the app shows the service and asks for confirmation; the service uses separate configuration/API access from ChatGPT sign-in, and its API usage may be billed separately. The returned transcript remains editable and is not sent as a chat message until you choose Send. If transcription is unavailable, the recording action is unavailable. Read-aloud is separate and passes requested text to the selected Android speech engine; that service may apply its own network processing.

The configured transcription service must have separate service credentials and an authorized OpenAI API key/model; the app’s ChatGPT sign-in or text-provider connection does not enable it. Provider account settings and current data policies apply.

Read-aloud is separate: the chosen answer is passed to an installed text-to-speech engine only when you request it. That speech service can apply its own network processing and policies; MeBot does not claim it operates offline. File import/export uses Android's document picker for the chosen file rather than unrestricted browsing of device storage. The permission declarations are in [AndroidManifest.xml](../app/src/main/AndroidManifest.xml).

Alarm audio uses original bundled waveforms, a selected system alarm sound or one selected document URI. Choosing your own audio requests persistent READ access to that file, not the media library. Its display name and URI are saved with the alarm; MeBot does not copy the audio file into its own storage or upload it to an AI provider. A selected cloud-backed document may require that document provider to retrieve or cache it. Optional alarm speech specifically requires an installed offline voice and otherwise leaves the alarm tone playing. Its message is passed to the chosen phone speech engine when the alarm or preview plays, and is not sent to MeBot's AI connection.

The alarm-file permission ledger retains access while a saved plan, active/queued/scheduled occurrence or editor draft refers to the URI. A bounded 24-hour restoration window protects newly selected files and interrupted drafts; expired, unreferenced grants are cleaned up when alarms are next reconciled. Cleanup releases only READ grants acquired by this alarm picker and leaves unrelated existing grants intact. Revocation failures are retried during later reconciliation. The selected audio file itself is not deleted.

## Backups and document providers

The manifest disables normal automatic app backup, and [data extraction rules](../app/src/main/res/xml/data_extraction_rules.xml) exclude both credential-protected and device-protected app storage from cloud backup and device transfer. Exports are explicit and cover separate stores:

| Export action | What it covers |
| --- | --- |
| **You → Data and backups → Export feed backup** | Feed profile/context, module configuration, cached/saved reading, completed explanations, conversations/attachments and reading history. |
| **You → Preferences → Export a backup** | Local-tool profile/settings, check-ins, focus history and alarm plans. |
| **You → My context → Export context** | A narrower portable profile/context file, including the current module settings. |

To preserve both feed and local-tool data, use both full-backup actions. A context export does not include conversations or local tools. The [user guide](USER-GUIDE.md#data-controls) explains the matching import paths and their different replacement effects.

You choose where an export is written. If you select a cloud-backed document provider, that provider may upload the file using its own service and permissions. The app does not encrypt the exported backup or control retention and sharing at that destination.

Context and feed imports validate the selected file before showing its filename, counts and replacement impact for confirmation. Context can also be pasted as JSON and uses the same validation/review flow. MeBot reads the clipboard only through the user's normal paste action; it does not scan it for context in the background. Cancellation or validation failure does not replace current data; imports are not merges. Local-tool backup replaces its profile, settings, visits, focus history and alarm plans; imported alarms start disabled and an active focus interval is cleared. Feed backup includes and replaces the feed profile, context, cached items/explanations, preferences/order, conversations/attachments and local reading history; it clears local AI credentials after the replacement is durably stored and disables background delivery. Context import replaces its profile and notes, and module settings when included; an empty module list keeps the existing configuration. It retains the current connection, saved reading and local tools. Both full backup formats use schema version 1 with an 8 MiB limit; context uses the explicit `mebot-context-v1` format and a 512 KiB limit. The paste editor accepts at most 131,072 characters. Bounds are documented in [ARCHITECTURE.md](ARCHITECTURE.md) and [FEED-CONTRACT.md](FEED-CONTRACT.md).

The operational alarm store and its diagnostic events are not exported in the personal JSON backup. Alarm plans are exported, including their checkpoint values, sound selection and optional spoken message. Selected audio bytes and Android file grants are not exported; a restored file selection may need to be chosen again on that device, with the bundled tone available as a fallback. Keep a backup or QR image private if you do not want others to read or copy it.

## Retention and erasure

The app keeps saved personal entries until you replace or erase them. Turning off an optional module retains its personal records. The app does not automatically discard older diary entries or completed focus sessions to make room for new ones.

Feed refresh retains saved items and up to 250 recent unsaved items. Hidden IDs prevent repeat presentation. Context edits clear old personalized relevance explanations; previous factual summaries can remain cached. A past horoscope no longer appears in Feed, but a saved reading remains in Saved. Removing a module does not erase saved reading. Repeated opens within five minutes do not rewrite the same reading timestamp; this reduces local writes without adding new tracking. The image cache shares concurrent loads and removes invalid entries, retaining its existing size/age bounds.

Local conversations are bounded to 24 threads, 60 turns each and 800,000 combined text characters across turns and attachments. Delete a thread from the workspace history to remove its stored turns and attached text. Completed explanation caching retains at most 24 items. Reading-history clearing removes opened-item timestamps without deleting saved stories or conversations. Explicit feed exports can retain copies of these records until you remove those files.

The alarm store keeps a bounded list of the latest 200 operational events. These record timestamps, event types, alarm identifiers, and limited diagnostic details such as snooze counts or error classes. They are local; the app does not upload them. They do not contain diary notes or retained camera images.

**Sign out** removes the selected ChatGPT account's local tokens and attempts remote refresh-token revocation. If remote revocation cannot be confirmed, the app says so and links to ChatGPT usage settings. It retains a local registration and identity label for a future sign-in. Other accounts, cached reading and local tools remain available.

**Erase data on this device** requires confirmation. It clears both local repositories, all encrypted AI credentials and ChatGPT registrations, scheduled feed work, operational alarms, pending occurrences and the diagnostic store. Where voice-note capture is available, erasure stops active voice work and removes MeBot-owned recordings from the app-private cache. It attempts to release alarm-file READ grants acquired by MeBot. A cancelled document import cannot later restore erased context. The optional initial profile is not reapplied after reset. Erasure does not delete selected alarm-audio files, exported files, provider-side account grants/data or a separately hosted service database; manage those at their destinations.

This feature is an application-level reset, not a promise of forensic secure deletion from a device, document provider, or its backups. The app also has no remote recovery service for erased notes. Export a private copy first when you intend to preserve them.

## Changes to this design

A contribution that adds a new private connector, telemetry, another processing destination, accounts or cloud synchronization must make the behavior explicit and update this document. Configurability does not authorize broader data access.

For implementation detail, see [AppRepository.kt](../app/src/main/java/mebot/nguli/com/data/AppRepository.kt), [AlarmStore.kt](../app/src/main/java/mebot/nguli/com/alarm/AlarmStore.kt), and the [checkpoint scanner](../app/src/main/java/mebot/nguli/com/alarm/AlarmActivity.kt). For responsible reports about the code, follow the repository's [security guidance](../SECURITY.md).

## Connection recovery details

Only a bounded stage label, HTTP status, sanitized request ID and allowlisted response-shape description may appear under Connection details. Raw provider response bodies, OAuth URLs, codes and tokens never enter that view. The custom Android return URI contains navigation only. Existing note permissions remain explicit; selecting notes for one module does not grant them to unrelated module kinds.

Data and backups includes a collapsed troubleshooting view for recent local operation/error codes and a short correlation identifier. Diagnostic logging uses bounded operation/code metadata, exception class and code locations, without provider bodies, URLs, attachments or conversation text. These records are not uploaded by MeBot and do not constitute an analytics feed.

## Today and optional app lock

Today separates a personal selection of already retrieved content from items with verified source-date metadata. Publication, forecast and event dates keep their own meaning; a recent check does not make a source newly published. It retains source/module identity and existing context boundaries; it
makes no additional inference request merely to open or paginate cached items.
It reuses existing optional local reading history and explicit save/hide choices,
not new telemetry or dwell tracking. Existing explicit relevance feedback retains
its separately documented scoped provider behavior. Publisher-date metadata is
extracted from an existing bounded HTML retrieval and is not accepted as verified
just because an AI/service response says so. Unknown dates remain unknown.

App lock is opt-in, using a user-chosen app PIN and optional supported strong
biometrics. Its salted slow verifier is protected by a separate Android Keystore
key in a no-backup record. A separate nonpersonal enabled marker lets direct-boot
alarms use generic labels. PINs, verifier state and unlocked sessions are not feed
backup fields; importing/restoring a feed cannot turn protection off. Disabling
or changing protection requires fresh app-PIN authentication.

This is a UI privacy gate, **not encryption of feed.json, conversations, cached
media or exported backups**. It does not protect a compromised/rooted process.
The app locks conservatively on leaving the foreground/screen-off and checks
Keyguard on resume; it does not change device timeout. Alarm stop/snooze remain
usable with generic labels. There is no automatic wipe or silent forgotten-PIN
bypass. Android's explicit Clear storage loses local data and provider connections,
not remote provider records. Physical-device/biometric acceptance remains pending.
