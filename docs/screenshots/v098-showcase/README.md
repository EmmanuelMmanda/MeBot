# MeBot 0.9.8 showcase captures

These are full-shell Android captures from the native-rendered `MeBotShowcase098JourneyTest` (Activity/PixelCopy; 411 × 891 dp, rendered at 822 × 1782 px). The test uses a synthetic, checked-in editorial fixture built from the public-source research pack reviewed on **28 October 2026**. It does not fetch these stories from RSS or an API during the capture, and it does not connect to or call an AI provider. The images are product UI examples, not evidence of a live account, live feed refresh, or AI-generated answer.

The fixture preserves each publisher's exact source title and URL. The concise display headlines—especially the Swahili versions—are editorial UI headlines, not claimed official translations. A missing source publication date stays unknown; the app's local capture time is not represented as a publication date. The public-source facts and their caveats are in `app/src/test/resources/v098/showcase-content-pack.json`.

“Today” is the personal daily reading selection; the separate verified-source-day view only includes stories with a source publication date matching 28 October 2026. None of these fixture stories has that publication date, so the verified-source-day section is honestly empty. The app remains useful with AI disconnected; no requests were made in this test.

| File | View | Locale and appearance | Source(s) represented |
|---|---|---|---|
| `en-feed-light-ember.png` | Main public feed | English, light, Ember accent, Classic skin | EMAICON and the other seeded public items in the feed |
| `sw-today-light-sage.png` | “Kwa ajili yako leo” personal selection | Swahili, light, Sage accent, Paper skin | FSDT, PDPC, TCRA, APICon, EMAICON |
| `sw-today-verified-light-sage.png` | Verified source-day section (empty for the fixture date) | Swahili, light, Sage accent, Paper skin | Same public-source fixture; no item is dated 28 October |
| `en-detail-dark-slate.png` | TCRA communications report detail | English, dark, Slate accent, Classic skin | [TCRA report](https://tcra.go.tz/publications/statistics/2026/q4) |
| `sw-opportunity-dark-teal.png` | FSDT Impact Grant Fund opportunity detail, with story notes expanded | Swahili, dark, Teal accent, Tinted skin | [FSDT Impact Grant Fund](https://www.fsdt.or.tz/igf/) |
| `en-appearance-light-violet.png` | Appearance dialog, showing theme and accent choices | English, light, Violet accent, Classic skin | — |
| `en-appearance-page-style-light-violet.png` | The same real dialog scrolled to all four page-style choices | English, light, Violet accent, Classic skin | — |

The TCRA report describes the quarter ending June 2026; subscription totals are subscriptions, not unique people. The FSDT cycle 2 call closes 5 November 2026 and is restricted by region, sector, and applicant eligibility. Dates and status are as reviewed on 28 October 2026; readers should confirm current details at the linked source before acting.
