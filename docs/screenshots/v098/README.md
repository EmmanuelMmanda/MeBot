# MeBot 0.9.8 Original Read and Today captures

All images are 822 × 1782 native Android host renders at 411 dp, captured with UTC. Synthetic records are used throughout; there are no real user profiles or live provider responses.

## Original Read detail

These images render the production `FeedDetail` composable in a standard 411 dp Compose test scaffold labeled “Preview edition.” This is not a MainActivity or physical-device capture. The Original Read text, evidence and further-reading references are synthetic UI/model-contract fixtures authored in `MeBotEditionUiTest`; the “AI-authored” label verifies product copy and is not a claim that the captured text came from a live model.

| File | What it shows |
| --- | --- |
| `v098-original-read-en-overview.png` | English AI-authored and Created provenance, summary, and the beginning of the canonical sections. |
| `v098-original-read-en-sections.png` | English reading position through the section bodies. |
| `v098-original-read-sw-overview.png` | Kiswahili provenance, date, tab, and evidence labels; fixture body remains synthetic English text. |
| `v098-original-read-sw-sections.png` | Kiswahili detail with evidence and optional further reading separated. |

## Today personal selection and verified source day

These four images are PixelCopy captures from the full `MainActivity` journey in `MeBotToday090JourneyTest`. The personal selection contains a synthetic valid Original Read and synthetic source-backed technology items with publisher timestamps. They verify that “For you today” is distinct from the remaining “Verified today from sources” section, preserve source-day dates and show Original Read AI-authored/Created provenance. The test asserts zero requests, question-generation calls, and answer-generation calls; the UI does not invoke a provider to create the selection. “Example publisher” and the illustrative Original Read content are test fixtures, not real reporting or a live AI response.

| File | What it shows |
| --- | --- |
| `v098-today-selection-original-en.png` | English MainActivity Today view with the item-attached publisher time and a synthetic Original Read marked AI-authored/Created. |
| `v098-today-selection-original-sw.png` | Kiswahili localization of the same synthetic personal selection and provenance. |
| `v098-today-selection-verified-en.png` | English verified source-day section, separate from the personal selection. |
| `v098-today-selection-verified-sw.png` | Kiswahili verified source-day section, separate from the personal selection. |

These are standard-layout review images, not accessibility stress captures.
