> Current test candidate: see [0.7.0-beta.1](RELEASE-0.7.0-beta.1.md). Older figures below describe archived releases, not new beta verification.

# Install MeBot

## First install or update

Download `mebot-0.6.1.apk` from the [official release](https://github.com/EmmanuelMmanda/MeBot/releases/latest), open it on an Android 8.0+ phone, and follow the system installation prompt. This release is version **0.6.1**, version code **8**. Public release APKs omit the optional personal starter profile. The [release-integrity record](release-integrity.json) contains the public APK's checksum and signing-certificate fingerprint. If Android asks, allow the browser or file manager to install the APK; that permission can be turned off afterwards.

For an existing compatible mynguli or MeBot installation, install the update over it. Official releases retain application ID `app.mynguli` and the existing signing identity. A fork signed with another key cannot replace that installation; debug builds use a separate package. Updating preserves existing profiles, plans, language, reading, history and alarms in their original storage. After installation, check your saved data and alarm access on the phone; see the [verification record](VERIFICATION.md) for completed checks and remaining device-specific validation.

Do not uninstall first if you want to keep local data. A manually exported backup is useful before any update.

## Check a saved ChatGPT account

After installing 0.6.1, keep the saved account. Open **MeBot → You → AI connection**. If your account is already saved with plan access, use **Check connection**; it reuses that session and verifies model access. Choose a returned model if the first model is unavailable. **Continue with ChatGPT** remains available for signed-out accounts or permission changes.

New sign-in pages use the actual installed application ID and a matching browser-safe return route. Release is `app.mynguli.connect://chatgpt/return`; debug uses its separate package. That link only returns to the app and never supplies authentication credentials. If the browser blocks external-app navigation, use Android Recents to return while MeBot finishes the check. Model-list failures preserve the account and expose bounded, nonsecret connection details.

## Start

The optional welcome has six short steps covering Feed, modules, explanations, search and conversations, local tools, and personalization. Skip to Feed from any step, or continue to AI setup at the end. Language is available immediately. No questionnaire, account or API key is required to begin with public sources. Existing users keep their completed onboarding; the tour can be replayed from **You → Preferences → Help & FAQ**.

The five destinations are **Feed**, **Modules**, **AI**, **Saved** and **You**. Feed starts with five stories, publisher media and a separate forecast when enabled; use **Show more** when you want more. Open a story for its saved summary, original source and available practical details. With AI connected, its explanation begins automatically and completed explanations are reused for up to six hours when the story and relevant settings are unchanged. Follow up in the detail page or continue in **AI**. Save a story to keep it after refresh; hide it to remove it from Feed. Saved reading remains accessible if its module is paused.

The Feed search action opens a keyword field with your module filters. Search all enabled factual modules or choose one; weather and horoscope stay separate. Search runs only after you submit and offers direct result links, save and detail actions. It uses one attempted AI request, regardless of how many modules are selected.

When you return to Feed after its configured refresh interval, it refreshes automatically while keeping cached reading visible. Tap **Feed** again while already there to return to the top and request a refresh, or use the refresh button. Horizontal module filters keep the selected choice visible. Failed automatic attempts are throttled so switching screens does not cause a retry loop. Source/date labels update while the app is in the foreground.

On a fresh installation, the public APK starts without an embedded personal identity or interests. Add or import your preferences under **You → My context**. An update preserves any context you already saved, and existing notes keep their module scopes when edited. This is explicit app context, not automatic access to ChatGPT account memory. Developers considering an optional private profile seed should read the [build configuration guidance](DEVELOPMENT.md#public-defaults-and-personal-context).

## Personalize and get help

Open **You → My context**. Import, paste, export, name, region and timezone are on the page directly; no Advanced section is required.

1. Choose **Prepare in ChatGPT** to copy the setup prompt and open the official ChatGPT site. **Copy prompt** and **View prompt** are also available.
2. Paste and submit the prompt inside ChatGPT. It requests a MeBot JSON file using reliably known preferences, leaves unknown information empty and keeps `modules: []` so your module choices remain intact. It cannot guarantee that remembered information is current; review the result.
3. Return to MeBot. Use **Import file** for the JSON file or **Paste JSON** for its text, then choose **Review import**.
4. Check the profile/context replacement summary and confirm. Review the notes and their module permissions, then refresh Feed to use them for new information.

This flow does not give MeBot your ChatGPT history or memory. Sign-in and API keys alone cannot import that information. A context-only import keeps saved stories, accounts and local tools; an empty note list is valid if there is nothing reliable to add. Whole ChatGPT export ZIPs are unsupported. The paste editor accepts up to 131,072 characters; use a JSON file for longer content, within the 512 KiB context limit.

**You → Preferences → Help & FAQ** opens searchable answers, with setup first. It includes the same prompt and direct setup actions, plus explanations of modules, sources, limits, background updates, privacy, backups, offline use, voice and alarms. A search with no match shows an empty state so you can clear or change the term.

## Connect AI

1. Open **You → AI connection**.
2. Choose **Continue with ChatGPT**. Complete sign-in and plan permission in your browser. Use **Open MeBot**, switch back to the app or use the browser's Back action. Keep the app running while finishing consent. If the system closes it, restart sign-in; the app does not replay an expired code.
3. Wait for the connection check. MeBot discovers models from your account and sends a small request; it switches the selected connection only after a completed answer. The check counts as one attempted AI request. A first-use confirmation explains plan usage.
4. Choose **Refresh my feed**. Open a story for its explanation and follow-up questions, or start a thread from the middle **AI** destination. The connection settings let you choose a discovered model or add/switch an account. **Manage usage** opens ChatGPT usage settings.

Your ChatGPT plan must be eligible for this integration. Model and web-search access follow its account/workspace rules. Identity-only permission does not enable AI. Limits may apply to the whole plan or to MeBot; the app does not invent quota percentages or reset dates. At an actionable plan limit, automatic AI refresh pauses while public sources still update. A deliberate refresh or question can retry; switching billing modes always requires your action. No ChatGPT history or memory is imported by sign-in.

To stop a ChatGPT session, open **Accounts → Sign out**. The app removes local tokens and attempts remote revocation. If revocation cannot be confirmed, a message directs you to ChatGPT settings. Saved reading remains on the device. The remembered registration can be signed into again.

### API key or personal service

Open **You → AI connection → Use an API key instead**, enter your own OpenAI API key and choose **Check and connect**. This is a separately billed alternative; it is never selected automatically when a ChatGPT request fails.

Under **Advanced settings**, **Connection → OpenAI** selects direct API access and **Model** sets its model ID. The initial model field is `gpt-6-luna`, an editable default rather than a guarantee of access for your project. The connection check makes a small real inference request and can incur API usage. A successful check does not guarantee future API balance or availability; a billing or provider error is shown without removing existing reading. Provider API usage is billed separately from ChatGPT. No working key is embedded in the app.

For a self-hosted setup, follow [service/README.md](../service/README.md), then open **You → AI connection → Use an API key instead → Advanced settings → My service**. Enter its public **HTTPS service URL**, set **Model** to match the server's `MEBOT_MODEL`, enter the separate **Service token**, and choose **Check and connect**. A mismatched model is rejected; the app field does not override the server configuration. Android requires a hostname resolving to a public address, normally on port 443; localhost, LAN endpoints and custom ports are not supported by this build. The provider API key stays on that server. There is no pre-deployed MeBot server to connect to.

## Modules and delivery

Use **Modules** or Feed's **All modules** action to browse the complete catalog. Search by name or topic, choose **All**, **Enabled** or **Disabled**, and narrow by Updates, Work & learning, or Life. **Add to your day** opens disabled modules directly, including your paused custom topics. Clear the search or reset filters if no modules match.

The catalog contains 16 built-in feed modules plus custom Watchlists, representing 17 kinds. News, Tech & AI, Security, Opportunities, Scholarships, Business, Research, Learn, Watch, Music & practice, Events, Places and Clips use web research when AI is connected. Watch is the films/series module; Watchlists follow your custom topics. Each module shows a **Default focus** and relevant settings such as learning level, funding, event window or content language, plus an optional custom focus. Where **Personalization** is shown, select saved notes for that module kind without changing unrelated kinds' permissions. All custom Watchlists share the `watch` kind's notes. **Use default focus**, where available, clears the custom focus while retaining preferences and note selections. Domain guidance accompanies AI discovery, search and story explanations even when no personal notes are shared. The [user guide](USER-GUIDE.md#every-feed-kind) lists every kind and preference.

Hold an enabled module's drag handle and move it to change the order of your home filters. Tap the handle for accessible move-up/down choices. Your changes are saved on drop. When the list is filtered, reordering moves only the visible enabled modules; hidden modules retain their positions. New built-ins arrive switched off during an update; Clips is optional and opens videos from their original creators or official sources.

Weather uses its own configured location, falling back to your profile city and country; it does not request GPS. Open source uses GitHub to load recent public repositories without an AI key, with focus and programming-language filters. News has a public technical-news fallback. Connected AI takes priority over configured RSS sources; optional feeds remain under **Advanced settings**. Horoscope is entertainment: select your sign, optionally choose focus/tone, then explicitly turn on **Show in feed**.

Cards link directly to the article, video, podcast, project, course, event or opportunity actually returned by the source. Images use publisher-supplied metadata. Their attribution and resource references appear in details. Content from paused modules stays in Saved but leaves the home edition.

Use **You → Background updates** to configure the local request allowance and optional background retrieval. Background delivery starts off. Choose a refresh preset of 1, 3, 6, 12 or 24 hours, or a custom whole number from 1 to 24. Quiet-hour values use the 24-hour clock, 0–23; equal start and end means no quiet window. Module alert choices decide which new items can notify you. Save to apply the settings; a failed save keeps the draft available. The app posts at most one feed notification per local day. Android schedules background work approximately, so this is not a promise of a notification at a precise clock time. Quiet hours suppress notifications; background retrieval can still occur.

New profiles start with **28** attempted AI requests per local day. Choose **28**, **56**, **112**, **224**, **Custom** for another positive amount, or **Unlimited**. Existing limits, including a previously selected 12, remain unchanged after updating. Today's usage remains visible with Unlimited, which removes only MeBot's own cap. ChatGPT plan allowances, provider rate limits, API charges and any personal service cap still apply.

Each enabled AI module can use one call per refresh. A new story explanation, conversation message, keyword search or ChatGPT connection/model check uses another; a reusable completed explanation does not. Failures and cancelled attempts can count. Public-source retrieval, cached paging and local tools do not use this AI allowance.

## AI conversations

Start a conversation from **AI**, return to a local thread from its history, or delete a thread you no longer need. A general thread starts without personal-context notes. Select a module to use only its confirmed scoped notes, or continue a story discussion with that story's context. The scope is shown above the composer. Conversation history is stored on this device and is included in an explicit feed backup; it does not import or synchronize your ChatGPT chats.

You can attach up to three UTF-8 `.txt` or `.md` files, each at most 48 KiB, with 24,000 characters combined per conversation. Review or remove attached text before sending. Files become part of that thread's request when you send a message. PDF, image and other binary attachments are not supported.

When the phone provides speech recognition, its microphone action fills your editable draft; sending remains explicit. Read-aloud is available when an installed speech engine supports the selected language. These are device speech services, which may use their own network processing. They are not model audio features, and playback stops when MeBot leaves the foreground.

Real streaming responses appear as received. The personal service can return a completed response instead. A story explanation uses available source material and citations; it does not guarantee full article access. Videos and other media open their original destination, with no in-app streaming-video promise.

## Alarms

Open **Modules → Alarms**, select a time and repeat days, and choose **Math**, **QR scan**, or **A tap**. No repeat days means the next occurrence only. Open **Sound & snooze** for volume, ramp, vibration and snooze limits. New alarms begin with three easy math questions; no alarm is scheduled until explicitly saved with the required access.

Choose **Pulse**, **Rising chime**, **Steady bell** or **Classic**, or use **Phone alarm sounds** or **Choose audio file** to open Android's picker. The original sounds are bundled and do not need a download. Pulse has the most sustained energy and is selected for new alarms; older alarms keep Classic until changed. **Preview sound** plays for up to 12 seconds at your phone's current alarm volume, without changing that volume or applying the alarm's saved volume and ramp. **Stop preview** ends it immediately; leaving the editor or starting an alarm test also stops it. Volume and gradual ramp remain separate controls, including the existing 80% volume and 20-second ramp defaults for new alarms.

Turn on **Spoken wake-up message** to use an installed offline phone voice. Leave its text blank to use the short translated default, or enter your own message. During an alarm, speech starts after the gradual ramp, with a minimum delay of 1.5 seconds; preview demonstrates it after about one second. The voice supplements the looping sound and uses no AI request. A missing voice does not stop the tone. A selected file may be unavailable before the first unlock after a restart, after it is moved or removed, or after restoring a backup on another phone; MeBot uses a bundled fallback. Reselect the file on the destination phone. Backups retain the selection and message, not the audio bytes or another device's file permission.

**Math:** choose Easy/Medium/Hard and 1–5 questions. Enter each answer on the alarm keypad and submit the answer. Incorrect answers do not dismiss it. Completed questions persist across screen recreation and snooze. An emergency stop remains available behind a confirmation.

**QR:** create a named code, save its image, print it and put it somewhere safe away from bed. The alarm accepts that code only. A configurable follow-up can require a second scan. Set follow-up to **No follow-up** for a single scan. Camera processing is local.

**A tap:** one explicit dismissal ends the occurrence.

### Access and testing

The compact **Alarm access** sheet opens precise timing, notification and lock-screen settings. If access is unavailable, **Save switched off** preserves the plan without scheduling it. The sheet reflects granted Android access; it cannot inspect every manufacturer battery policy.

1. Grant precise timing and the available notification/lock-screen access.
2. Run **Test in 10 seconds**, lock the screen, and verify the selected sound, saved volume/ramp, spoken message if enabled, presentation and dismissal. Preview alone does not test the saved volume or ramp.
3. Test an actual near-future scheduled alarm with the screen off.
4. Check Do Not Disturb and your phone's battery/deep-sleep controls.
5. Keep an established backup alarm during initial overnight testing.

The phone must be on. Force-stopping the app can prevent delivery until reopened. Test sound routing, system volume controls, installed voice availability, selected-file access before first unlock, battery restrictions, camera hardware and overnight delivery on your own phone. Solving a question or scanning a code does not prove sustained wakefulness.

Turning an alarm off or deleting it cancels its active occurrence and schedule. Disabling the entire alarm feature also cancels tests and queued occurrences while retaining saved plans. Existing one-time alarms become disabled after firing; their current occurrence may still finish.

## Daily check-in and focus

**Modules → Daily check-in** shows an optional mood row and an offered next step. **Use this plan** accepts it directly. **Change**, **Edit**, and **Add a note** are available when useful. No arrival/departure response is compulsory. Reopening returns to today's saved plan, using the phone's local date.

**Focus** starts with a duration and optional title. It can use today's next step automatically. Pause, resume or end a session; save completion after the timer finishes. The timer is silent, including in the background. Recorded minutes are timer completions, not a measurement of attention.

## Data and future updates

**You → Data and backups** provides a feed backup, validated feed import, reading-history controls, hidden-item restoration and a link to local-tool data settings. A feed backup includes context, cached/saved reading, module order/preferences, completed explanations, conversations and attached text, but never credentials or the local ChatGPT account identifier. Choose a file first, review its counts and replacement impact, then confirm. Import replaces that data, clears local AI credentials and switches background delivery off until reviewed. Sign in again explicitly. A validation failure leaves current data intact.

Reading history remembers opened items locally to reduce repeats without hiding them. Turn it off to stop recording new opens and using that history for ranking, or clear the stored history. Reopening the same item within five minutes avoids another timestamp write. Saved stories and conversations are separate. Publisher-image caching keeps its size/age bounds, shares duplicate loads and can repair corrupt cached images; an unavailable image leaves the story readable. Troubleshooting details remain collapsed and show only operation, error code and correlation ID.

Local-tool data settings separately export/import alarms, check-ins, focus and their preferences. Import clears active focus and leaves imported alarms off until reviewed. Old mynguli local-tool backups are accepted; new fields in MeBot exports are not compatible with the older 0.1.0 importer.

Personal context has its own visible import/paste/export actions in **You → My context**. It accepts `mebot-context-v1` JSON; see [context-example.json](context-example.json). It does not accept an entire ChatGPT export ZIP. Keep all personal exports private.

**You → Preferences → Erase data on this device** clears MeBot's feed/context, local AI credentials and registrations, local-tool records, background delivery and operational alarm data, then returns to onboarding. This resets MeBot; it does not wipe the Android device or other apps. An optional private build's starter profile is not reapplied. Previously exported files, source audio files and data on a separate personal service remain at those destinations. Manage provider-side grants separately in ChatGPT settings.

Official APK updates must retain the compatible signing identity recorded in [release integrity](release-integrity.json). If you build an independent distribution, protect and privately back up your own signing key; its future updates need that identity. A public certificate can be shared for verification, but private keys and passwords must never enter a source repository or public archive. See the [signed-release guide](DEVELOPMENT.md#signed-release-and-public-source).

## Feed paging and context-only imports

The feed opens with five stories. **Show more** reveals another five from the current cached edition and shows the revealed/total count. Each module filter retains its reading position and page count. **Refresh** retrieves newer information; showing another cached page does not consume an AI request.

Open **You → My context → Import file** for a `mebot-context-v1` JSON file, or choose **Paste JSON**. After validation, review its entry/module counts before confirming replacement. An empty `modules` array preserves the current module configuration and order. The profile and note list are replaced, while saved stories, account credentials and alarms remain. Review note scopes after importing and refresh to apply them to new items. No ChatGPT memory is imported by signing in alone.
