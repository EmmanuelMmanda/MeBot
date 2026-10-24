# MeBot interface

MeBot opens to a personal information feed. Sixteen built-in information modules and configurable topic watches form the catalog; alarms, a brief daily check-in and focus remain useful tools within the same app. The interface gives the user a small set of relevant updates and a clear stopping point. Setup, permission details and less frequent controls appear where the action needs them.

The main navigation is **Feed · Modules · AI · Saved · You**, with the AI workspace in the middle. There is no full-screen alarm promotion, required questionnaire or chat session to complete before using the app. Personal identity and interests come from editable data, never screen code.

Version 0.6.1 preserves the feed's visual direction. Its changes make existing controls easier to discover: a brief optional welcome, visible module filters and defaults, flat context setup, searchable help, and editable background limits. More available modules do not mean more items in the first edition or more required setup decisions.

## Design tokens

`ui/Theme.kt` owns shared colors, type, shapes and spacing. Screen files use semantic Material colors and `MeBotPalette` feature colors. The direction is an airy editorial feed with raspberry accents, publisher imagery, a visual lead story, paired secondary discoveries and compact action rows and a floating navigation bar. Saturated brand surfaces are reserved for actions and existing alarm/focus views.

| Token | Light | Dark / behavior |
| --- | --- | --- |
| Background | `#F7F8FA` | `#111218` |
| Surface | `#FFFFFF` | `#1C1E27` |
| Primary text | `#171922` | `#F4F4F8` |
| Secondary text | `#626878` | `#B1B5C6` |
| Raspberry primary | `#E60046` | `#FF7A9F` |
| Primary container | `#FFEAF1` | `#471A2C` |
| Saturated brand card | `#E60046` | Optional fixed brand surface with white text |
| Blue feature surface | `#E8F3FF` | `#19324B` |
| Violet feature surface | `#F0EAFF` | `#312942` |
| Mint feature surface | `#E4F4EF` | `#183B30` |
| Spacing | 4, 8, 12, 16, 24, 32 dp | Same scale |
| Screen inset | 20 dp | Maximum content width 680 dp |
| Card radius | 24 dp | Same |
| Control radius | 16 dp | Same |
| Primary button height | At least 54 dp | Grows with text |
| Touch target | At least 48 dp for primary controls | Same |

Sage and blue remain selectable accents. The stored accent key `ember` now selects raspberry, preserving existing settings and backup compatibility. Light, dark and system appearance are available. The alarm ringing screen uses a deliberate dark setting and a saturated brand card.

Figtree is bundled locally. The hierarchy uses 64/52/40 sp displays, 32/28/24 sp headings, 22/16/14 sp titles and 16/14/12 sp body text. Display numbers are reserved for alarm times and the focus timer. Supporting copy is short and uses the secondary text color.

## Shared components

- `ScreenContainer`: bounded width, vertical scrolling and keyboard insets.
- `WaveCard`: rounded brand surface with vector wave decoration; accepts explicit container and foreground colors. Decoration is drawn locally and does not convey state.
- `AppCard`: neutral container for related content.
- `PrimaryAction` and `SecondaryAction`: consistent full-width actions.
- `FeatureIcon`: rounded icon tile using a feature color pair.
- `CompactAction`: compact home shortcut with an icon and a visible label.
- `SectionHeader`: section label and optional action.
- `ChoiceRow`: wrapping selection controls for preferences and durations.
- `HorizontalRail`: one scroll owner, stable item identity, subtle overflow edges and automatic visibility for the selected item. Scrolling a rail does not reset its enclosing reading page.
- `MeBotMark`: code-drawn app mark.
- `FeedSettingsRow`: labelled destination with an optional concise status.
- `FeedStatus`: localized, actionable errors and partial-result notices.
- `LeadStory` / `StoryRow`: distinct editorial hierarchy with source, time and saved state.

Icons supplement visible labels. Destructive actions retain confirmation. Copy that describes permissions, data replacement or alarm limitations is retained at the relevant decision, rather than placed on every screen.

## Screen behavior

### Onboarding

The welcome is a centered, six-step tour: the daily feed, modules, story explanations, search and conversations, local tools, then personal context and connection. Each step has a concise heading, short explanation and explicitly labelled example preview. Back and progress show where the user is; on first use, skip opens Feed from every step. The last step offers AI setup or opening Feed. Help & FAQ can replay the tour without resetting data or onboarding state; leaving that replay returns to help.

Language is available immediately. The content scrolls when a small screen or larger text needs room, while actions stay reachable. Transitions use the same short native motion as the rest of the app. No name, personal goal or account is required. A configured name can personalize the greeting without creating a form; example content is never inserted into the real feed. Public source fetching starts after the user enters the app or explicitly refreshes.

Connecting AI starts with **Continue with ChatGPT**. A single neutral card explains plan use, and the approved monochrome ChatGPT mark identifies the browser sign-in action. API keys and a self-hosted service are an explicit, collapsed alternative. The separate API billing explanation appears at that decision. No limit or authentication failure switches billing modes.

The browser signs the user in while MeBot shows a cancellable waiting state. Returning checks the account and completes an actual model response before showing Connected. Back cancels the attempt; rotation and temporarily leaving for the browser do not. The return intent only opens the connection screen and never accepts authentication data. Account labels distinguish registrations even when email addresses match. Models come from the selected account catalog, preserve server order, and only change after a successful check.

A first-use plan confirmation appears after verified connection and is acknowledged once per account. **Using ChatGPT plan** and **Manage usage** remain beside the model selector and Ask composer. A limit message directs the user to ChatGPT usage settings; the app does not invent a quota percentage or reset time. Identity-only accounts show that plan access is off and offer re-consent. Connection errors preserve the feed, active account, and question draft.

The API key draft is deliberately not saveable; rotation, leaving the screen, closing the alternative, or starting ChatGPT sign-in clears the unsaved secret. Model and service controls remain under Advanced. Every connection has a visible, verified state and a clear continuation action.

### Feed

The 0.6.1 direction keeps a finite visual edition for a person who wants to open once, catch useful developments and act without a long reading session. A compact branded header carries the editable greeting and date; module filters follow the saved module order. Their horizontal rail brings the selected filter into view, and an always-available catalog action opens all modules. Search opens a separate focused query area. The first item has publisher artwork, a clear title beneath it, a short summary and direct source/AI/save actions. Putting the title beneath the artwork also handles text-heavy Open Graph images without stacking two headlines on top of one another.

Five stories form the first edition. Ranking considers freshness, relevance, approaching deadlines and optional local reading history while interleaving enabled modules and publishers. Opened stories lose some priority temporarily but remain accessible. AI recommendations use the selected preferences and relevant confirmed notes; default module guidance supplies useful research direction when no personal notes are shared. Two secondary discoveries use media tiles on normal phones; at widths below 360 dp or font scales above 1.15 they become full-width rows. Remaining stories use compact thumbnails. A typed weather strip is independent of that five-story limit. The small next-alarm destination remains a utility. **Add to your day** opens the catalog filtered to disabled modules, including custom topics.

Tapping the active **Feed** destination returns the edition to its top and requests a refresh. Switching filters and revealing another cached page do not send AI requests. **Show more** adds five available stories at a time and retains the visible count for each filter; there is a clear end when the cached edition is exhausted.

Every resource opens its returned destination: Read source, Watch, Listen, View project, View course, View event or View opportunity. These are navigation actions, not claims that an application or enrollment has been submitted. Details retain all references and image provenance. Missing images use visibly abstract category artwork; they never masquerade as a photo of a real event. Publication time is shown only when available; retrieved content and forecast validity are distinguished.

Publisher images fade in over 180 ms. Card-height changes use the same short duration; both use the native animation duration scale, including disabled motion. No autoplay, infinite carousel or artificial urgency is used. Refresh progress reflects real requests. Cached text commits before optional images and remains available while another source is slow or fails. Empty, loading, filtered, disconnected, stale, partial and failure states retain useful navigation and one relevant recovery action.

### Story details and AI

The detail view retains the original source, full saved summary, publisher attribution and optional deadline. Source-supported key points, next steps and caveats appear when supplied. With AI connected, opening a story starts its explanation automatically. Real response deltas appear as they arrive; there is no simulated typing over a completed answer. The personal-service route can return its completed answer without streaming. Only a validated completed explanation enters the six-hour cache, keyed to the story, module settings, selected connection, language and approved context. Cached explanations remain readable offline. This does not promise full article access or bypass a publisher restriction.

Short follow-up prompts and an editable question continue the story discussion. Related cached items, source references, save and share actions remain available. Loading disables duplicate requests; cancellation stops the current request; an error retains the question for retry. Citations open their actual returned URLs. Saved stories remain accessible when their module is paused or removed.

The middle **AI** destination is a conversational workspace with locally saved threads, recent conversation history, citations and a persistent composer. A general conversation starts without personal-context notes. Selecting a module or opening a story permits only confirmed notes scoped to that kind. The scope and note count are visible. Plain UTF-8 `.txt` and `.md` attachments are explicitly selected, reviewable and removable; at most three files and 24,000 combined characters belong to a thread, with a 48 KiB limit per imported file. Content is sent when the person sends a message, without claiming PDF, image or arbitrary file support.

Voice input opens the phone's speech-recognition activity only when one is available, and inserts its returned text into the editable draft. Read-aloud uses a supported installed text-to-speech engine. Neither starts automatically or implies model-native audio. Speech stops on leaving the foreground.

Search sends one explicit keyword request across enabled factual modules, or one selected module. Weather and horoscope are excluded. Chips retain the user's module order; choosing a chip or typing does not send a request. Drafts survive recreation. Loading, cancellation, error/retry, initial guidance and completed-no-results have distinct states. Real results expose source, citations, save and detail actions.

### Modules

The catalog opens with all available modules. Search combines with **All**, **Enabled** and **Disabled** status choices and **All categories**, **Updates**, **Work & learning**, and **Life** categories. Counts make the size and enabled state of the catalog clear. Enabled results retain their saved order; disabled built-ins and custom topics appear under **Add to your day**. No matches has a clear reset action. Local tools participate in status and text filtering without becoming AI feed sources.

Sixteen built-in modules cover news, technology, security, opportunities, scholarships, business, science, learning, entertainment, music, events, travel, weather, GitHub discoveries, horoscope and Clips. Custom topic watches are added separately. Existing users keep their configured modules; new built-ins arrive paused. Clips seeks short videos from original creators and official channels and opens their source destinations; it does not introduce in-app streaming video.

Hold a visible drag handle to reorder enabled modules. The list scrolls near its edges, commits once on drop and restores the previous order if persistence fails. The handle also exposes move-up/down actions through its menu and accessibility semantics. Reordering filtered results changes only those visible slots; hidden and paused modules keep their positions. The complete module ID order drives home and search filter ordering.

Each module shows a short **Default focus** alongside its small validated settings schema: learning level/format, opportunity type/workplace, event window, GitHub language/focus and other relevant controls. Canonical domain guidance accompanies AI discovery, search and detail requests; an optional custom topic replaces the starter topic while keeping that domain guidance. **Use default focus**, where available, clears only the custom topic and leaves selected preferences and shared notes intact. Preference rows stack on narrow screens or with larger text so the selected value remains readable. Each factual AI module also has a collapsed Personalization section with saved, confirmed notes. Choosing notes and saving the module is one atomic change. A latest cached item provides a direct route back to a real detail page.

Weather accepts a module-specific location with the profile region as fallback and uses the public forecast API. GitHub and news have public API paths without AI. Optional RSS sources stay under Advanced. Horoscope presents all twelve signs in a compact grid, plus focus and tone. Opening its setup does not turn it on; enabling it requires a selected sign. Its content remains labelled entertainment. No integration is represented as connected merely because its card exists.

Alarm, focus, check-in and history rows open their existing working flows. They retain their own persistence and alarm scheduling. The editor closes only when the durable module and selected context scopes match the requested save; failures preserve both drafts.

### You and personal context

You groups **My context**, AI connection, Background updates, Preferences, and Data and backups. My context is a reviewable list of independently editable notes with an optional name. Each note quietly identifies its module permissions and offers a compact module selector. Editing its wording, including multiline text, preserves its identity, scope and origin; removing or inserting a note cannot transfer another note's permissions. New notes have the documented default scope. Import file, Paste JSON, export, region and timezone are visible on this page without an Advanced section.

**Prepare in ChatGPT** copies a schema-compatible prompt and opens the official ChatGPT site. Copy and view actions also remain available. The user submits the prompt there and brings the result back for review. The prompt requests reliably known preferences, leaves unknown information empty and preserves module configuration with `modules: []`. Saving explicitly confirms edited context; signing in does not grant ChatGPT history or memory access.

Context imports accept a selected file or pasted JSON. Feed backups use the file picker. Both validate the candidate, then show its name, counts and replacement impact. Confirmation applies that reviewed candidate; cancellation or validation failure leaves current data intact. Controls remain disabled while replacement is underway, and a failed write retains a retryable preview. Feed backups include local conversations and attached text, exclude credentials and require AI reconnection after import. **Data and backups** also provides a local reading-history switch, clear action and collapsed technical diagnostics containing operation/code/correlation metadata.

Background delivery is off until enabled. Notification permission is requested at that action. **Background updates** displays today's attempted requests and offers 28 (the new-profile default), 56, 112, 224, Custom and Unlimited. Previously saved limits remain selected, including amounts outside the presets. Unlimited removes the app's daily cap; provider and service limits remain independent. Refresh intervals offer presets and custom whole hours from 1 to 24; quiet hours accept 0–23, with equal start/end disabling the quiet window. Validation and saving stay visible, and the page closes only after durable success. Copy describes approximate Android scheduling and at most one digest a day. An external permission denial has an actionable settings link.

### Check-in

The entire visit fits on one short screen. Mood is optional and can be deselected. MeBot offers a visible next-step suggestion; the user can accept it, choose another, edit it or add a note. Suggestions are deterministic and local. They do not imply an AI model, clinical assessment or knowledge of the user's private circumstances.

Accepting a plan creates a substantive selected outcome if the old draft has none. Existing nonblank outcomes are preserved. No emotional state is filled in silently. The draft uses a saveable representation for rotation, and unfinished new visits also persist through the repository.

### Alarms

The first alarm path uses a math challenge. Standard dismissal and QR checkpoint flows remain configurable. Advanced controls are available when needed. The ringing screen presents one question at a time, a large keypad and explicit feedback. Emergency stopping remains accessible.

Sound choices are part of the alarm editor. Pulse is the assertive built-in option; Chime, Bell and the original tone provide alternatives. Phone sounds and a selected audio file use the same saved alarm settings. A preview makes the choice audible before saving. Optional spoken text is explicit and editable, and remains supplementary to the repeating alarm sound. Changing sound does not silently replace an existing alarm's volume or ramp policy. The bundled fallback remains available when a selected file or phone voice cannot be used.

### Focus

A session can start without typing. The task field is optional; a saved next step is used when available. The timer offers pause, resume, end and explicit save on completion. The screen says that completion is silent. The app does not claim to block other apps or verify attention.

### History

Check-ins and focus use separate compact lists with real record counts. A check-in opens its details. Legacy arrival and departure values remain readable; blank values are omitted. Lists page through saved records rather than rendering the full history at once.

### Settings

Language, appearance, default focus duration and utility switches are opened from compact rows. **Help & FAQ** has searchable, expandable answers with setup first, the context prompt, tour replay and direct context/connection actions. Empty search results offer concise guidance. Answers explain the actual connection, memory, source, quota, offline, voice and alarm boundaries. The old duplicate profile entry is removed; editable feed context is under You. Local tool backups, import and deletion remain available. Feed data has its own clearly labelled import/export controls. Import and erase ask for confirmation at the destructive action; erase resets both feed and local tool state and removes the connection.

## Journey and accessibility rules

Draft fields use saveable state with screen-specific keys; API keys do not. Module, context and delivery editors navigate only after the persisted result matches the requested save. Existing alarms and daily drafts retain their previous domain persistence. Back navigation returns to the relevant parent or originating feed/saved view. Notification intents are consumed once and do not reopen a stale destination after rotation.

Primary controls have at least 48 dp touch targets, forms scroll above the keyboard, and content has a 680 dp maximum width. Text wraps or truncates deliberately where a compact preview is intended; full content remains in details. Native journeys exercise compact Swahili/dark and regular English/light configurations. Stable semantic tags identify navigation, onboarding, module actions, feed items, save actions and editable fields for integration tests. Source titles and provider text are data; interface copy is paired in English and Kiswahili resources.

## Copy and extension rules

Use the smallest useful label. Prefer “Save alarm”, “Duration” and “Add a note” to motivational paragraphs. Do not add invented statistics, placeholder activity or unrelated financial content from the visual reference. Identity, preferences and active modules remain data, not hardcoded production fixtures.

New features should register as modules, reuse the shared tokens, and include real empty, loading, failure and saved states before gaining a permanent navigation position. English and Kiswahili string resources must remain paired. UI previews and tests may use labelled synthetic fixtures; that data must never become production defaults. Rendering and test evidence is reported separately from unverified physical-device behavior.


## ChatGPT branding reference

The ChatGPT mark in `res/drawable/ic_chatgpt.xml` retains the unmodified path from the official [sign-in asset](https://developers.openai.com/assets/siwc/sign-in-buttons/chatgpt-logo-white.svg). The button uses the black or white **Continue with ChatGPT** format described by [OpenAI](https://developers.openai.com/siwc/website). Plan confirmation, persistent attribution, and limit actions follow the [SIWC UI guidelines](https://developers.openai.com/siwc/ui-ux-guidelines). The exact `https://chatgpt.com/settings/usage` link is present in that official page's rendered HTML. MeBot's own identity, accent colors, and navigation stay distinct.

Native UI fixtures in `MeBotChatGptUiTest` exercise first use, cancellation, explicit API billing choice, consent absence, model/account confirmation, usage navigation, first-use acknowledgement, and a compact Swahili dark view. Their account and model values are synthetic test data; they are not evidence of a live subscription connection.

## Connection recovery

The browser callback page uses the MeBot brand, supports light/dark appearance and has a real return button with a matching package-specific BROWSABLE activity. It does not claim connection success; model inference still must complete in the app. A saved plan-enabled account shows Finish connecting, its account/model choice and a retry action. Identity, model-list and provider-protocol errors are distinct. Optional support details contain safe metadata only. Pre-connect model choices and the primary retry action share one draft owner, so the visible model is the one actually checked.

Native preview content is explicitly marked as a preview. Real publisher media used in those captures is sourced from its article metadata and excluded from the APK fixtures. UI tests and images do not establish owner-account eligibility or a physical Brave handoff.
