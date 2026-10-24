# MeBot Daily 0.7.0-beta.1

This is a separate **MeBot Test** installation (`app.mynguli.beta`), not an in-place update for `app.mynguli`. It leaves the existing stable app and its data intact. This beta is signed with the build environment's testing certificate, not the owner's stable release key. Never uninstall stable MeBot to install this beta.

## Included

- Opened stories have independent scroll positions and start at the top; the existing detail-page structure remains.
- AI suggestion chips execute directly once and adapt to the selected topic and language.
- The AI workspace can select a model in place. ChatGPT choices use the account's returned catalog; API users can enter a model ID. The existing inference check runs before a connection is accepted.
- Native rich text renders headings, bullets, emphasis, code and links. Cited media and document destinations get appropriate source cards. YouTube/direct media players load only when opened. Original links stay accessible.
- Request-scoped AI activity shows actual provider events and, when supplied, provider-published reasoning summaries. Raw internal reasoning is not exposed. Elapsed time is not an ETA.
- A bounded request queue reserves capacity for interactive work. Background discovery uses one slot; the overall limit is two concurrent managed requests.
- Configurable one, two or three discovery batches per topic. Existing daily request limits and provider limits still apply.
- Hybrid recommendations combine source-backed AI relevance with approved interests, same-topic explicit save/hide feedback, freshness, reading history, deadlines and a small exploration allowance. Publisher/topic diversity and hidden-item exclusions remain.
- Onboarding collects chosen topics, optional region and optional interests. No JSON file is required. Imports and paste dialogs remain optional, compact and validated.
- New AI language instructions and revised prominent interface copy use plain English and everyday Tanzanian Kiswahili.

## Important limits

This is a test candidate, not a zero-defect guarantee. Automated host tests do not establish physical-phone alarm delivery, speaker volume, camera behavior, real provider account eligibility, model-specific capabilities, or video availability in a country/account. Test the built-in short alarm before overnight use.

YouTube playback depends on the official embed permitting the selected video; age, region, owner and provider restrictions remain. There is no extraction of YouTube audio, bypassing of playback controls, or downloading of private social feeds. Facebook and Instagram private feeds are not connected by this beta.

The recommendation layer is not a newly trained proprietary model. AI discovers and evaluates candidates; the local, readable ranking policy applies personalization and constraints. It has unit coverage but no longitudinal user-study or relevance benchmark yet. Repeated content can still appear when verified new sources are scarce; the app must not invent stories to fill space.

General chat routes named topics to approved preference/interest/goal notes. An ambiguous general question gets no ambient profile notes. A selected topic retains its own scoped context. Source text is treated as untrusted evidence, not instructions.

The optional Python service remains a separate API-key route and does not receive private ChatGPT credentials. UI locale does not rewrite old cached stories in place; new discovery and explanations use the selected response language, and brief cache fingerprints include language.

## Testing and updates

Use the accompanying verification report for exact test counts and any unresolved warnings. Reconnect your account inside MeBot Test; account tokens are not transferred from stable MeBot. To bring preferences across, export from stable and use the beta's reviewed context/backup import. Stable and beta have independent alarms—avoid enabling duplicate wake-up alarms.

Future stable updates need the original signing key. No private signing key, API key, personal starter profile or reusable user token is included in this source.
