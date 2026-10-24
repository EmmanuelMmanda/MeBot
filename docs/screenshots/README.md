# MeBot 0.6.1 native interface previews

These images come from the actual Android Compose activities, rendered with Robolectric native graphics on API 35. Light layouts use a 411 dp viewport. The Kiswahili dark feed uses 320 dp. They are native rendering evidence, not browser mockups or photographs of a physical phone.

The name “Alex”, context entries, example.com stories, example answers, plans and alarm records are synthetic test fixtures. They are excluded from the application. Automated feed journeys use deterministic source and AI transports with the production repository, engine and ViewModel. These images do not claim a live provider response or a signed-in owner account. The connected ChatGPT fixture is explicitly labelled “Example account · TEST-A”.

## Version 0.6.1 welcome, discovery and alarm audio

These are the latest renders for the updated 0.6.1 screens. Other sections retain the most recent render of unchanged screens and identify the version in which each was captured.

| File | Screen and verified behavior |
| --- | --- |
| `54-mebot-module-discovery-v061.png` | Searchable module catalog, status and category filters |
| `55-mebot-module-defaults-small-sw-v061.png` | Default module brief in a narrow Kiswahili layout |
| `56-mebot-background-v061.png` | Refresh schedule and configurable daily request allowance |
| `57-mebot-background-small-sw-v061.png` | Background settings at narrow width with larger text |
| `58-mebot-feed-polish-v061.png` | Current feed, module filters and progressive reading |
| `60-mebot-context-v061.png` | Flat context import/paste and ChatGPT preparation actions |
| `61-mebot-faq-v061.png` | Searchable in-app setup and help |
| `62-mebot-faq-small-sw-v061.png` | FAQ reachability in narrow Kiswahili/dark mode |
| `63-mebot-welcome-v061.png` | First screen of the optional six-step tour |
| `64-mebot-tour-modules-v061.png` | Tour step explaining configurable modules |
| `65-mebot-tour-context-v061.png` | Tour step explaining personal setup |
| `66-mebot-welcome-small-sw-v061.png` | Compact Kiswahili/dark welcome with larger text |
| `67-mebot-context-paste-v061.png` | JSON paste with validation before review |
| `69-mebot-alarm-audio-v061.png` | Built-in, phone and custom audio plus optional speech |
| `70-mebot-alarm-audio-small-sw-v061.png` | Alarm audio controls at narrow width in Kiswahili |

## Version 0.6 detail, workspace, search and preferences

These new screens use explicitly synthetic source/answer fixtures to verify the production Compose components. Streaming previews represent real UI state callbacks in tests; they do not claim live model output. The integrated Activity journeys separately verify navigation, persistence, request counts and selected credentials through controlled provider adapters.

| File | Screen and verified behavior |
| --- | --- |
| `47-mebot-detail-v06.png` | Automatic explanation, completed answer with cited source and follow-up composer |
| `48-mebot-workspace-v06.png` | Middle AI destination, saved conversation, selected module and reviewed text attachment |
| `49-mebot-workspace-small-sw-v06.png` | Narrow Kiswahili, dark theme, cached messages and disconnected recovery |
| `50-mebot-module-order-v06.png` | Installed module handles and persisted home-filter order |
| `51-mebot-horoscope-small-sw-v06.png` | Narrow horoscope editor with explicit sign, focus and tone |
| `52-mebot-search-v06.png` | Query, ordered enabled-module filters and a sourced result |
| `53-mebot-data-controls-v06.png` | Feed exports/imports, reading-history controls and hidden-item recovery |

## Visual edition and expanded modules

The new edition images are labelled **Preview edition**. They render the actual production feed and bottom navigation, with two real publisher images and synthetic supporting records. Publisher photos are private test inputs; they are not packaged in the APK or used as feed defaults. Actual editions fetch their content and images from configured sources. All third-party image rights remain with their respective owners; the application's Apache license does not relicense publisher imagery visible in these review screenshots.

| File | Screen and verified behavior |
| --- | --- |
| `40-mebot-edition-v5.png` | First page, actual publisher art, compact header, source/date, direct resource and AI actions |
| `40b-mebot-edition-across-modules-v5.png` | Media discoveries across modules and the one-tap contextual question entry |
| `41-mebot-edition-small-swahili-v5.png` | 320 dp, Kiswahili, dark theme, 1.3 font scale, long title and missing-image fallback |
| `41b-mebot-edition-small-swahili-cards-v5.png` | Full-width narrow-screen cards and reachable source actions |
| `42-mebot-modules-v5.png` | Active modules and grouped catalog; existing utilities remain separate |
| `43-mebot-module-personalization-small-sw.png` | Context-note scope controls in a narrow Kiswahili editor |
| `34-mebot-chatgpt-saved-session-recovery.png` | Saved synthetic plan account, catalog failure and primary connection retry |
| `44-mebot-connection-polish-v051.png` | Saved synthetic plan account, one primary model/check control and expanded account management without a duplicate card |

The Google preview source is [The Keyword: AI reconstruction of lost artists' studios](https://blog.google/company-news/outreach-and-initiatives/arts-culture/visionaries-petit-palais-art-basel-paris-prune-nourry/), with its publisher-supplied [hero image](https://storage.googleapis.com/gweb-uniblog-publish-prod/images/Prune_Nourry_Hero.width-1300.png). The science image comes from [NASA Earth Observatory: Extratropical Cyclone Whips Past Iceland](https://science.nasa.gov/earth/earth-observatory/extratropical-cyclone-whips-past-iceland/). Captions, sources and publication dates in those two fixtures derive from their public pages; the remaining preview cards are labelled synthetic examples.

## ChatGPT connection

| File | Screen and verified behavior |
| --- | --- |
| `30-mebot-chatgpt-connection.png` | Primary Continue with ChatGPT action and the collapsed API-key alternative |
| `31-mebot-chatgpt-waiting.png` | Pending external sign-in with a reachable cancellation action |
| `32-mebot-chatgpt-small-swahili-dark.png` | 320 dp Kiswahili dark layout at 1.3 font scale |
| `33-mebot-chatgpt-connected-fixture.png` | Synthetic connected account, returned model selection, plan badge and usage link |
| `34-mebot-chatgpt-native-connection.png` | Actual MainActivity with navigation chrome; no simulated signed-in account |

The native connected journey tests browser launch, pending-state rotation, completion of an inference check before activation, first-use acknowledgement, account selection and Ask routing. Recovery tests also cover retrying a saved account without OAuth, retaining per-account model drafts while showing the primary selector only once, rotation, and failed account switches that preserve the active connection. Auth and inference transports are test adapters; the ViewModel, engine, repository and interface are production code. Live consent, browser return, token renewal and provider eligibility still require the owner's Android device and account.

## Personal feed and modules

| File | Screen and verified behavior |
| --- | --- |
| `20-mebot-feed-onboarding.png` | One-screen onboarding; start without personal questions or connect AI first |
| `21-mebot-personal-feed.png` | Source-backed feed cards, save controls and the five-tab shell with search entry |
| `22-mebot-saved.png` | Saved updates reopened after activity recreation |
| `23-mebot-update-detail.png` | Full update, original source and Ask entry point |
| `24-mebot-modules.png` | Installed and optional modules, with separate utilities below |
| `25-mebot-add-watch.png` | Watch configuration draft restored after recreation |
| `26-mebot-ask.png` | A synthetic answer and source link through the production question flow |
| `27-mebot-feed-small-swahili-dark.png` | Narrow Kiswahili layout, dark appearance and reachable controls |
| `28-mebot-context.png` | Editable multiline entries with visible module scope; editing preserves identity and scope |

## Local tools and supporting screens

| File | Screen |
| --- | --- |
| `02-mebot-home-empty.png` | Empty feed state on a new installation |
| `03-mebot-check-in.png` | Optional daily check-in and suggested next step |
| `05-mebot-alarm-editor.png` | Time, repeats, math challenge and sound controls |
| `06-mebot-alarms.png` | Saved alarm list and access status |
| `07-mebot-focus.png` | Active focus interval and pause controls |
| `09-mebot-preferences.png` | Personal context, AI, delivery, appearance and data entry points |
| `10-mebot-math-alarm.png` | Ringing alarm with math question and keypad |

These review images are not a pixel-baseline test suite. See [VERIFICATION.md](../VERIFICATION.md) and [test-results-summary.json](../test-results-summary.json) for executable coverage and its limits. Real-phone installation, alarm sound, lock-screen delivery and overnight behavior still require device qualification.

The 0.5.1 edition tests also exercise three cached pages, filter and saved-instance restoration, stable reading position after insertion/hide, and a retained horizontal chip position. These checks measure composition/state behavior, not physical-device frame rate.
