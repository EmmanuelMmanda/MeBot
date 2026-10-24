# MeBot documentation assets

The public documentation serves Android users who want to install MeBot, connect their own AI account, choose relevant modules, and understand their data. Its visual direction follows the app: locally hosted Figtree, raspberry actions, calm neutral surfaces, generous reading margins, and a clear Feed-first hierarchy. The primary website journey is Home → Download/first launch → AI connection → reviewed context → modules. Individual guides retain a searchable directory, section anchors, and previous/next navigation.

`mebot.svg` reproduces the existing code-drawn `MeBotMark` geometry. `figtree.ttf` is copied from the Android resource; its SIL Open Font License is in `../FONT-LICENSE.txt`. Screenshots are linked directly from `../screenshots` and remain unaltered. They are native Android host renders with explicit test fixtures, not invented interface mockups or evidence of a live account. See `../screenshots/README.md` for provenance and third-party imagery rights.

The site has no framework, tracker, CDN, build step, or remote font dependency. `site.js` supplies local appearance preferences, mobile navigation, directory filtering, copy actions, image enlargement, section tracking, and release metadata. Native links and guide content remain usable without JavaScript. Light/dark/system choices, visible focus, keyboard navigation, flexible layouts, and reduced-motion support are shared across pages.

## Publishing a clean release

`release.json` is the only download-availability switch. Keep `status` as `pending` until the corresponding clean public release and its assets are verified on GitHub. The pages retain their release-directory and source-build links while pending or when local metadata cannot load. The site derives standard GitHub URLs from validated version/tag values; it never infers publication from an APK stored elsewhere.

The top-level record describes the latest release. `releases` contains each historical directory entry. Set the top-level and matching entry statuses independently to `published` only after verifying their public availability. Artifact type and publication status are separate: `apk: true` means an APK is prepared for that release, not that it has already been published. Version 0.2.0 has `apk: false` and is source-only; 0.1.0 is history without a recovered public artifact and has no invented manifest entry.

```json
{
  "schemaVersion": 1,
  "version": "0.6.1",
  "tag": "v0.6.1",
  "status": "pending",
  "filename": "mebot-0.6.1.apk",
  "sourceCommit": "40-character source commit",
  "minimumAndroid": "8.0",
  "sha256": "APK SHA-256",
  "sizeBytes": 15774571,
  "sourceFilename": "mebot-0.6.1-source.zip",
  "sourceSha256": "matching source archive SHA-256",
  "sourceSizeBytes": 7640220,
  "releases": [
    {
      "version": "0.6.1",
      "tag": "v0.6.1",
      "status": "pending",
      "apk": true,
      "filename": "mebot-0.6.1.apk",
      "sourceCommit": "40-character source commit",
      "sha256": "APK SHA-256",
      "sizeBytes": 15774571,
      "sourceFilename": "mebot-0.6.1-source.zip",
      "sourceSha256": "matching source archive SHA-256",
      "sourceSizeBytes": 7640220
    }
  ]
}
```

`filename`, APK `sha256`, and APK `sizeBytes` are null for a source-only release. URLs use `https://github.com/EmmanuelMmanda/MeBot/releases/download/vVERSION/mebot-VERSION.apk` and `mebot-VERSION-source.zip`; source downloads are the explicit matching release assets rather than an unrelated branch archive. Checksums and source commits come from the reviewed public publication manifest. No private-build checksum or personal profile data is copied into this file.

The runtime also validates artifact metadata before revealing downloads: expected filenames, 64-hex SHA-256 hashes, positive safe-integer byte sizes, and a 40-hex source commit. A malformed published record retains the safe GitHub-directory fallback and shows the metadata error notice. Source-only entries require valid source metadata but do not require an APK hash or size.

Guide content and navigation are plain static HTML, with no generator required for hosting or editing. Keep navigation, guide search keywords, section anchors, and previous/next links consistent when adding a page. Relative links within this site remain under `/MeBot/`; repository source/policy links use the appropriate GitHub branch instead of escaping the Pages site root.

Do not put personal context, owner account details, API keys, signing secrets, or a personalized APK in this directory.
