# Complete MeBot user manual

[Open the download page](download.html) · [Install and update](guides/install.html) · [Troubleshooting](guides/troubleshooting.html) · [Privacy](guides/privacy.html)

> **Availability note:** This manual describes the current app flow, including setup and voice controls that may not appear in the APK currently offered on the download page. A documentation update does not change an installed APK. The download page shows only a release that GitHub currently publishes.

## 1. Install and start

1. Open [Download MeBot](download.html) and use its verified download action. If the page cannot verify an APK, follow its link to GitHub Releases and check what is actually published. Do not install a file sent through an unrelated link.
2. Open the downloaded APK and approve Android’s installer prompt. If Android blocks installation, use the device setting it names, then return to the installer. Keep your existing installation and data when updating.
3. Choose English or Kiswahili on first launch. Grant Android permissions only when you choose a feature that needs them, such as notifications or microphone access.
4. The newer setup flow offers three stages: **Connect ChatGPT**, **Add context**, and **Start your feed**. You can continue with public feeds without connecting AI and can skip the context step. If you instead see the optional welcome tour, it explains Feed, Modules, Explain, Search, Tools, and Context; finish with **Connect**, choose **Open feed**, or skip it. These layouts depend on the installed build.
5. Choose topics that match what you want to follow. You can change them later. You do not need to create an account to read public-source feeds or use local tools.

For an existing installation, use the compatible in-app update path in [Software updates](#software-updates). A signing conflict means Android sees a different signing identity; do not uninstall or clear data to work around it. See [troubleshooting](guides/troubleshooting.html).

## 2. Choose interests and modules

### Pick interests

Open **You → About you → Choose feed topics** when that action is available, or choose topics during onboarding. Use **Find your interests** to search, select a category to narrow the list, and use **Show more topics** or **Show fewer topics** to expand or shorten it. Selected topics save on this device and do not start an AI request. To remove a choice, deselect it and save.

Some builds place topic selection inside the welcome flow or **About you** editor instead of a separate topic screen. If the named action is absent, use the interests controls that are present or search **Help & FAQ**.

### Select modules

Open **Modules** from the main navigation. Search or use the category and enabled/disabled filters to find a module. Enable the modules you want in your feed; disable ones you do not want. Use **My modules** to review installed modules and **Explore** to browse available ones. If offered, **Add custom** creates a local module that you can name and configure.

Open a module’s settings to edit its focus, preferences, ordering, and permitted context. A module may require a connected AI provider for some personalized actions, but its public-source reading can still work without AI where sources are available. Alarms, Focus, Daily Check-in, and History are local tools, not feed modules.

## 3. Connect AI only if you want it

Open **You → AI connection** or tap **Continue with ChatGPT** during setup.

### Sign in with ChatGPT

1. Choose **Continue with ChatGPT**. MeBot opens an external browser for the provider’s consent and sign-in flow.
2. Return to MeBot after consent. Choose an available model and run **Check connection**. MeBot activates the account only after model discovery and a completed inference check.
3. If several accounts are saved, choose the account you want to use. Use **Manage usage** to review MeBot’s local request cap and **Accounts** to manage saved accounts.

ChatGPT sign-in connects MeBot to an eligible plan-backed model route where supported. It does **not** give MeBot your ChatGPT conversations, saved memory, files, private connectors, or subscription API credits. The provider determines account eligibility, model access, limits, and service availability. ChatGPT sign-in does not configure the separate voice API used by optional live-call features.

If the browser does not return to the app, reopen MeBot and check **AI connection**. Cancel a pending sign-in and retry. If the model list or connection check fails, verify the account’s access and network, then retry; public feeds and local tools remain available.

### Other provider choices

Under **Choose your connection**, select the provider you intend to use: **OpenAI API**, **Claude API**, **Gemini API**, **OpenRouter**, **Personal server**, or **My service**. Follow its credential or authorization prompt, select or enter a model, then run **Check connection**. Keep each credential in its own provider connection; do not paste credentials into About You notes or a context import.

Provider limits and billing belong to that provider. The daily attempted-request count and custom or unlimited presets control MeBot’s local cap only; they do not grant provider access or credits. A failed attempt may count if the provider’s result is uncertain. MeBot does not silently switch to a different provider or start paid API billing. Claude.ai subscription credentials are not supported; OpenRouter uses its own authorization or credits; Gemini does not add native Google Search grounding; and **My service** is the configured OpenAI-backed service. A Personal server must use a supported endpoint; plain HTTP is limited to explicit private or localhost consent without an API key.

To disconnect an account, open its account controls and choose the remove/sign-out action shown. This removes the saved connection from this device; it does not delete your provider account or revoke all sessions at that provider. Revoke keys or provider sessions with the provider if needed.

## 4. Add personal context safely

**About you** contains optional profile details such as name, region, timezone, and global context notes. Enter only details you want MeBot to use. Save changes explicitly. A global About You note is available to modules only within the scopes you approve.

A module note is different: it belongs to that module and can be scoped to other allowed modules only when you explicitly choose them. Review a note’s scope before saving. Editing a note does not make it global. You can leave context empty and still use public feeds.

### Import context prepared with ChatGPT

1. In About You or the context setup screen, choose **Copy starter prompt**.
2. Open ChatGPT yourself, paste the prompt, review what you are comfortable sharing, and send it there. MeBot does not open or read ChatGPT history automatically.
3. Return to MeBot and choose **Choose file** for a downloaded JSON file or **Paste JSON** to enter text. The context import accepts files up to 512 KiB and pasted text up to 131,072 characters.
4. Choose **Review**. Check the proposed topics, profile fields, notes, and module scopes before applying the replacement.
5. Confirm to save. MeBot returns to **About you** and shows the imported notes. If the JSON is invalid, or you cancel the review, your current draft stays in place.

Context import changes the context/topics represented by that JSON. It is not a full app backup or restore. An empty module list in an import keeps your existing module choices. Never import passwords, API keys, or other secrets as preferences.

## 5. Read your feed and Today

The main feed offers **For you**, **Today**, and filters for enabled modules. **For you** combines your selected interests and module preferences with available public sources. Open a module filter to focus on that area.

**Today** separates your personal selection, labelled **For you today**, from material labelled **Verified today from sources**. The first is selected for your interests and does not mean it was published today. The verified section uses publisher dates or supported event/forecast dates. A recent fetch does not change an article’s publication date; an unknown date remains unknown. Check the linked publisher for current facts, eligibility, and deadlines.

To refresh the selected feed or module, pull down. When new content is ready, choose **Show new items** to add it while keeping your reading position. If a source is unavailable, completed content remains visible and MeBot may show a cached result; check its age and open the source before relying on it. Public-source reading does not require an AI connection. AI personalization or generated content does.

## 6. Open a story, media, or source

Tap a feed item to open its details. Read the source and date labels, then use the publisher link to verify current details. AI-authored **Original Read** content is labelled with a **Created** time; that is not a publisher publication date. Further reading is separate and does not by itself verify the reflection.

Tap a media preview to load it. Playback may depend on source availability, provider permissions, and your connection. Use **Open original** when the preview cannot load or you prefer the publisher. Public file links are checked before showing file counts or a **Save file** action; supported downloads are bounded to 20 MiB and checked again when saved. Android’s document picker chooses the destination. MeBot does not bypass a sign-in or paywall.

For a larger image, open the media and use the full-screen preview. Close it with the on-screen control or Android back. If the preview fails, open the original source instead.

## 7. Save, follow, and star stories

Use the bookmark/save control to add an item to **Saved**. Open **Saved** in the main navigation, then use the **Saved** or **Following** library tab to review items, search, filter, or reopen one. Saving keeps an item in your library when it later leaves an active feed.

A star is separate from a bookmark. Star a story to follow up on it; manage its follow-up settings from **You → Starred stories**. Background checks depend on the configured connection, request allowance, source, network, Android scheduling, and your settings. MeBot does not promise an exact check time. Starred items are not the same as saved items.

## 8. Ask questions and use chat

On a story, tap its question icon to request story-specific suggestions. When an AI connection is ready, MeBot can offer up to five questions grounded in that item. Opening the sheet alone does not send a question. Choose **More angles** to request another AI-generated batch; the provider and batch limit may affect the result.

Choose **Send** to submit the selected question directly. Choose **Edit** to put it into the composer so you can change it; editing does not send it. New suggestions require a working AI connection and network. There is no offline question generator. If the request fails, check the selected provider and retry, or ask a question yourself in MeBot.

In the MeBot chat/workspace, type a message and choose **Send**. Use the conversation list to reopen saved conversations. Copy or share a response with its available action; check the text and source links before sharing. **Read aloud** uses Android’s installed text-to-speech service. The microphone input, when available, uses an Android speech-recognition service and may depend on a separate system app and its permissions.

### Recording and live voice

Recording, transcription, and two-way voice are separate from read-aloud and speech-recognition input. They appear only when the installed build and service are configured for them.

For a voice note, use the recording control, grant microphone permission, and stop the recording before the two-minute limit. The recording stays local until you choose **Transcribe recording** and confirm the request. Review and edit the returned text before sending it; the audio is not attached to the chat automatically. Transcription requires the separately configured MeBot voice service and its authorized API access. ChatGPT sign-in alone is not enough.

A live two-way call requires the configured MeBot voice service, separate authorization for voice API access, a network connection, microphone permission, and your confirmation before the call. End the call with its visible control. If setup or the service capability is unavailable, the call controls remain hidden or setup explains what is missing; use text chat or read-aloud instead.

## 9. Notifications and background updates

Open **You → Background updates**. Turn on **Allow background updates**, choose an interval and preparation batch if offered, then set network or battery conditions. **Refresh now** starts an explicit refresh. Set **Quiet hours** to control when delivery notifications should be suppressed.

When Android asks for notification permission, allow it if you want MeBot notifications. If permission is off, open Android app settings and re-enable notifications. Android may defer background work because of battery optimization, device restart, network constraints, or its own schedule. Refresh timing is approximate, and the app limits notification digests to at most one per local day. Quiet hours affect delivery notifications; they do not guarantee that source checks stop.

MeBot may show a setup tip when a useful next step is available, such as connecting ChatGPT, adding context, configuring background delivery, checking an eligible app update, or setting up an available voice service. Choose its action to open the relevant screen, or use its later/dismiss action. Dismissing a tip does not grant a permission or change a setting. Use **Help & FAQ** to replay the quick guide.

If you are not receiving updates, open the app and use **Refresh now**, confirm the module is enabled, check the provider connection if that module uses AI, and review Android’s notification and battery settings. Do not treat a quiet feed as proof that new source content exists.

## 10. Personalize MeBot and protect access
<a id="your-preferences"></a>

Open **Me** (shown as **You** in some navigation layouts), then **Your MeBot** or **Personalization**.

- **Language:** choose English or Kiswahili.
- **Appearance:** choose system, light, or dark mode; select an accent; choose a page style. Deep black is intended for dark mode.
- **Region and timezone:** edit About You. Choose the region you want used; location access is only needed for features that explicitly ask for device location.
- **App lock:** under **Personalization → App lock**, set a PIN and, if supported, biometrics. It is an app-access gate, not encryption of the feed database.

To restore the system theme, choose **System**. If a biometric prompt is unavailable, use the configured PIN or Android’s device authentication route.

## 11. Export, import, and remove local data
<a id="data-controls"></a>

Open **You → Data and backups**.

- **Export a backup** creates a portable file. Save it only to a location you trust.
- **Import a backup** shows a review before replacing local data. Confirm only after checking the selected file and its scope. Imported alarms start disabled so they do not ring unexpectedly.
- **Clear reading history** is a separate action. It does not erase saved items, conversations, preferences, or relevance feedback.
- **Erase data on this device** removes local app data after confirmation. Export anything you need first.

A full backup is different from the context JSON import. Portable exports do not include provider credentials or app-lock secrets. MeBot has no cloud account deletion flow in these controls; removing an AI account from this phone is not deletion of the account at the provider.

## Software updates

Open **You → Software Updates** and choose **Check now**. If a compatible published update is available, review it and choose **Download**. If you enabled **Automatic checks** or **Download on Wi-Fi**, MeBot may check or prepare the file under those conditions. Download preparation does not spend AI requests.

MeBot checks that the release is published, newer than the installed build, for the same application package and signing certificate, and compatible with the Android version. It bounds the download and verifies its size and SHA-256 before Android’s installer opens. Review the Android prompt and approve installation yourself.

If no update appears, there may be no newer compatible public release. If Android reports a signature or package conflict, stop and use the [installation guide](guides/install.html); do not uninstall, clear storage, or install a file from an unrelated source to force it.

## 13. Quick recovery checklist

- **Sign-in does not return:** cancel the pending browser flow, reopen AI connection, and try again with the eligible provider account.
- **No models or failed check:** verify account/model access, credentials, network, provider limits, and billing at the provider. Retry after the provider is reachable.
- **No new feed items:** confirm the module and topics are enabled, refresh, and check the official source. MeBot should not manufacture freshness.
- **Import did not apply:** validate the JSON, use a supported file or paste size, review scopes, and confirm the import. Cancelling keeps the current draft.
- **AI suggestion unavailable:** use text chat or reconnect the selected provider. Story suggestions require AI and network.
- **Media does not play:** open the publisher’s original page.
- **Notification missing:** check Android permission, battery/network restrictions, quiet hours, and the module’s background setting.
- **Voice control missing:** confirm the feature is present in your installed build, the voice service is configured, and Android permissions are available.
- **Update is not offered:** check the release page and installed package. Do not force an update across a signing conflict.

For alarms, audio, background scheduling, and additional recovery steps, open [Troubleshooting](guides/troubleshooting.html) or the in-app **Help & FAQ**. The Markdown source for this manual is [USER-GUIDE.md](USER-GUIDE.md).
