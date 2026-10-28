# MeBot 0.9.7 UI review captures

These full-shell Android Compose captures were rendered by the `MeBotFeedJourneyTest` Robolectric journeys with the normal app screens and native bottom-sheet dialog. Content is synthetic and clearly presented as a preview; it is not a live article, account, or provider response.

The connected suggestion sheets use five distinct questions returned by the deterministic test AI provider through the real `FeedEngine` request-budget, validation, and cache path. No suggestion question is seeded into the screen or supplied as a local fallback. Opening the sheet does not start inference. Closing it cancels any in-flight generation started from that sheet; it never sends a question. Send and pencil Edit remain separate explicit actions.

The offline captures show the real unavailable state over a locally/RSS-usable synthetic story. They contain no suggestion questions and make no provider request. The Key Points captures use synthetic Markdown, source URLs, and citation markers; rendered text is cleaned up and source links remain separate, accessible actions.

| Capture | Contents |
| --- | --- |
| `en-feed.png`, `sw-feed.png` | Feed shell and synthetic preview story |
| `en-feed-suggestions.png`, `sw-feed-suggestions.png` | Five provider-generated story questions in the Suggestions sheet |
| `en-chat-suggestions.png`, `sw-chat-suggestions.png` | Five provider-generated questions scoped to the current conversation |
| `en-detail-suggestions.png`, `sw-detail-suggestions.png` | Five provider-generated questions from story detail |
| `en-detail-key-points.png`, `sw-detail-key-points.png` | Expanded, sanitized story Key Points and their source actions |
| `en-offline-suggestions.png`, `sw-offline-suggestions.png` | Honest AI-unavailable state; story feed remains usable |
