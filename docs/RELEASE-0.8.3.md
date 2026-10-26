> **Publication archive:** Historical source-only checkpoint; no production-signed APK is supplied. [Versioned files](https://github.com/EmmanuelMmanda/MeBot/releases/tag/v0.8.3) · [Recommended 0.9.3](https://github.com/EmmanuelMmanda/MeBot/releases/tag/v0.9.3). The verification-stage status below is retained as a dated record, not the current publication state.

# MeBot 0.8.3 candidate guide

Status: completed automated candidate build, not a published official APK. Version code 14; package remains `mebot.nguli.com`. Built on the 0.8.2 source. The signed downloadable release remains [0.8.2](https://github.com/EmmanuelMmanda/MeBot/releases/tag/v0.8.2) until a device-verified update is signed with the existing release identity.

## Follow and organize

- Star a story to follow its topic. Manage 6-hour, daily, 3-day or weekly follow-up cadence, or pause it in Saved → Following. Hiding a story pauses its follow-up. Refresh uses the configured provider and normal allowance; no new coverage is guaranteed.
- Saved becomes a searchable library with topic filters, reading order and named collections. Existing saved items stay available.
- Story actions include save, star, share, open and hide where applicable. Less-like-this feedback remains available without being the main action.

## Read and ask

- Recognized media and document links expose appropriate resource controls. PDFs have a page preview and recovery actions.
- The composer clears after a question is accepted. Shared attachments remain visible in the same conversation and can be removed; they are not automatically shared with other modules.
- Phone speech input and installed-voice read-aloud controls include voice selection and speed. This is **not full-duplex realtime voice** and does not establish that a ChatGPT subscription includes realtime API usage.
- Browser-owned Custom Tabs offer a route back to the app. Authentication remains in the provider-supported browser flow, with no embedded credential collection.

## Make it yours

- Searchable onboarding interests and clearer module filters. New areas: Food & cooking, Farming & gardening, Family & everyday life, Sports, Home & practical skills, and Rest & wellbeing.
- About you uses optional plain-language prompts for interests, work, learning, goals and preferred information style. Topic scope remains explicit.
- Compare prices is an offline utility using price per unit. Both options must use the same currency and unit; it does not include quality, delivery or extra costs.

## Reading and notifications

- Cached paging in batches of five, then at most one automatic network request per filter session after scrolling, with a 60-second throttle. Manual loading and deduplication remain available. New-item controls reveal newly received stories without shifting content mid-read.
- Progress is based on observed request stages, results and received character counts, not fabricated internal reasoning.
- Configurable image and title previews for optional notifications. Quiet hours and a maximum daily digest remain in effect.
- Existing settings and request allowances remain intact. New topics are not silently enabled on upgrade.

## Release and verification limits

The acceptance tracker records implementation and checks: [0.8.3 tracker](RELEASE-TRACKER-0.8.3.md). The completed candidate passed 817 automated tests (181 core, 467 Android and 169 service), with no failures, errors or skips. Debug and release APKs built successfully. Android lint reported zero errors, 216 debug warnings, 217 release warnings and one hint per variant; this is not a warning-free release.

Physical-phone installation, playback, file saving, live provider authorization, notification delivery and overnight alarm behavior remain separate checks. Original release signing inputs are not available in the fresh build workspace. A debug or unsigned candidate is not a safe replacement for the installed official APK. Do not uninstall the existing app to try an incompatible signature.

[Illustrated preview and usage guide](preview-083.html) · [Official installation guide](INSTALL.md) · [Privacy](PRIVACY.md)
