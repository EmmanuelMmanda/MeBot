<!-- preview-085:start -->
> **0.8.5 candidate:** Custom interests, a simpler About you screen and clearer navigation; 878 local automated tests passed. [Current candidate guide](RELEASE-0.8.5.md). The official download remains **0.8.2**. Earlier candidate release notes are preserved as history.
<!-- preview-085:end -->

# MeBot user guide — 0.8.2

[Start here](../START-HERE.md) · [Illustrated guide](guides/release-082.html) · [Release notes](RELEASE-0.8.2.md)

## Install and start
Install the official `mebot.nguli.com` package. It is separate from earlier development installations. Choose English or Kiswahili, interests and an optional city. Skip the introduction or replay the quick guide from Help. Alarm, Focus and Daily Check-in are optional.

## Feed and discovery
Public sources are usable without AI. Each module owns its preferences, fallback sources and permitted context. Connected AI prepares richer information only when that provider supports it. Sources remain visible. Refresh the selected module by pulling down. Completed modules appear while others work; failures preserve completed and cached content. While reading, use Show new items to accept newly prepared content without losing your position.

## Item details and MeBot
The source-backed summary introduction remains visible while the explanation loads and streams. Show more expands original notes; it does not gate the introduction. Answers are separate from media/files, so a failed preview does not erase text. The detail response surface is square to prevent clipping. The fixed activity panel follows actual operation state. Tap it for an equally wide popover of events and provider-published summaries when supplied. Private internal reasoning and invented progress percentages are not shown.

## Relevant and Not relevant
Use these controls to guide that module's future results, not to give social likes. One current vote per item is stored. Change or undo it; repeated taps do not multiply the vote. Signals decay, are bounded and do not replace your explicit preferences. Reset relevance feedback separately. Feedback in one module is not automatically used in another.

## AI connection
Choose ChatGPT sign-in where eligible, an OpenAI API key, Claude API key, Gemini API key, OpenRouter authorization/key, a compatible personal endpoint or a personal service. Sign-in does not import assistant memory. Claude.ai subscription credentials are not supported. OpenRouter has its own credits. Gemini uses supplied public source material without native Google Search grounding. The personal service remains OpenAI-backed. Personal endpoint HTTP is limited to explicit private/localhost consent without an API key over HTTP.

Select a model and run the connection check. Credentials are isolated by provider. Usage has one durable daily attempted-request count; presets, custom and unlimited control only MeBot's local cap. Failed attempts can count because provider outcome may be uncertain. Provider limits and billing still apply. No automatic provider or paid billing fallback occurs.

## Your preferences
Onboarding interests are enough to begin. You may add editable notes or use Copy starter prompt with an assistant to generate MeBot JSON. Use Import file or Paste JSON, inspect the preview and apply. Notes require approved scopes. Never import passwords or API keys as preferences.

## Background settings
Use cards to configure automatic refresh, interval, preparation batches, network/battery conditions, notifications and quiet hours. Refresh now is explicit. Android determines actual background timing. Manage the request budget in AI connection, not here.

## Software Updates
Automatic checks inspect the trusted public release list. Optional Wi-Fi download preparation is separate from AI requests. The candidate must match the installed package and signing certificate, have a higher version code and a supported Android minimum. Downloads are bounded and checked against their published size and SHA-256; Android's archive metadata is checked before installation. Approve installation through Android. Interrupted/invalid downloads do not replace the installed app. Provider connections and personal data are not in the release metadata.

## Data controls
Clear reading history asks for confirmation and explains its scope. It does not erase saved items, conversations, preferences or relevance feedback. Those are separate controls. Export personal data only to destinations you trust. Keys are not included in portable JSON exports.

## Media and files
Tap media to load it; provider permission/availability may prevent playback. Keep Open original as a fallback. Supported public file links are checked before verified file counts and Save file. Files are bounded to 20 MiB and are rechecked when saved. Android's picker chooses the destination. This does not bypass sign-in or paywalls.

## Troubleshooting
A failed connection does not block public-source reading or daily tools. Check provider credentials, capabilities and model access. A completed response is required to report a usable AI connection. If an update is not offered, it may be the same/older version or a different package/certificate. If no new stories appear, the source may have nothing new; MeBot must not manufacture freshness.

Test alarm permissions, audio, lock-screen behavior and overnight delivery on your actual phone. Automated UI captures and controlled provider tests do not establish those properties.

## 0.9.0 candidate additions

These controls belong to the unreleased 0.9.0 candidate, not the official 0.8.2
APK. See [candidate details and limitations](RELEASE-0.9.0.md).

- **Feed → Today** shows only items with qualifying source dates for your selected
  local day. Publication, forecast validity and event dates are labeled separately.
  Older and unknown-date items remain in your regular feed and Saved. Opening Today
  does not call AI; deliberate pull-to-refresh uses your usual connection/allowance.
- Compact onboarding icons retain topic labels. Your optional typed interests are
  saved as the existing module-scoped note. Revisiting with an empty draft preserves
  it; edit/delete the note in your context editor when that is your intent.
- **You → App lock** lets you choose and confirm a separate app PIN, then optionally
  enable supported strong biometrics using the current app PIN. Never enter your
  device PIN into an app-owned prompt. Locking hides personal screens, not encryption
  of the feed database. Change/disable requires current authentication. Keep the PIN;
  biometric enrollment changes or lockout may require it. Forgot-PIN guidance does
  not bypass protection or erase data automatically.
- **You → Your data** retains reading-history disable/clear controls. Clearing history
  changes local ranking without deleting your notes or saved stories.
