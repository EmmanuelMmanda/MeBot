# MeBot 0.8.1

Official MeBot update, signed with the original release certificate. Android 8.0+.
Install over your existing official MeBot; do not uninstall it. The older MeBot Test package remains separate.

## Changes
- Fixed top activity panel stays outside page scrolling. Expand it for actual request stages and provider-published summaries when available.
- Rounded activity rings follow the associated request and stop when it finishes. Obsolete feed loaders are removed.
- Discovery, search and answers use different status labels. Counts appear only after validation; no invented thoughts or estimates.
- The conversation destination is MeBot. Original details remain readable while an explanation loads, with a compact Show more / Show less action.
- Pull-to-refresh, refresh and Feed reselection respect the selected module and refresh its public-source cache.
- Editable Tanzania defaults use local public sources where appropriate; explicit preferences and custom sources win. Small per-install variation breaks ties, not relevance.
- Public file availability is checked before verified Files counts and Save file. Media checks use independent capacity.

## Installation and verification
Package `app.mynguli`, version code 12. Original certificate SHA-256: `b1b69dcefe26c815dbaec76223b7b80be76af06ba30da774637a806a7f75e3e9`.

693 tests passed: 143 core, 405 Android-host, 145 service, no failures or skipped tests. Release compilation passed. The full CI lint report contains zero errors, 144 warnings and one hint. The earlier local summary counted 143 warnings; this release reports the independent CI result.

The signed APK is assembled from the exact verified unsigned build plus its locally generated public APK signature block; no private signing key is sent to GitHub. Every application ZIP entry is unchanged and the result is verified with Android apksigner and zipalign.

The public source ZIP is an archive of the release tag. Application sources match tested commit `59ef0bf50c4e7d5a1d66d9221535f5d571eba94f`; later changes are publication scripts, documentation and screenshots only.

Physical-phone alarms, live AI account access, provider playback and Android's file picker still need device checks. File availability can change after checking. Protected files and paywalls are not bypassed. Public downloads remain bounded to 20 MiB.

[Illustrated activity and discovery guide](https://emmanuelmmanda.github.io/MeBot/guides/activity.html) · [Downloads](https://emmanuelmmanda.github.io/MeBot/download.html)
