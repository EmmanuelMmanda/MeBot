# MeBot 0.8.3 candidate captures

Captured 25 October 2026 from the Android Compose implementation with Robolectric host tests. These are actual native renders, not website recreations. Story text uses synthetic or dated public-source fixtures; account/provider state and example media are controlled test fixtures. They do not establish live inference, media playback, device installation or overnight alarm acceptance.

- `feed-stars.png`: full feed render with a synthetic starred news story.
- `rich-chat.png`: conversation fixture with recognized video resource actions and an empty composer.
- `saved-library.png`: full app Following tab with a dated public-source fixture, collection and refresh cadence. Source: `app/build/outputs/screenshots/v083/saved.png`.
- `module-filters.png`: Modules search narrowed to Farming & gardening.
- `price-comparison.png`: calculator component after comparing sample packs (2500 for 1 versus 6000 for 3). Not a complete app-screen capture. Currency and unit are optional and unset in this fixture.

Source tests generate these as `v083-feed-stars.png`, `083-rich-chat.png`, `v083-module-filters.png` and `v083-price-comparison.png` under `app/build/outputs/screenshots/`. The images were visually inspected before inclusion. Full-suite release acceptance was still in progress at capture time; see the [acceptance tracker](../../RELEASE-TRACKER-0.8.3.md).

The older `v082` gallery remains unchanged for the official 0.8.2 release. The website's official APK link remains 0.8.2 until an original-identity signed 0.8.3 release is verified.
