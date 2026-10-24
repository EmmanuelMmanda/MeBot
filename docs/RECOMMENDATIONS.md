# Recommendation and activity engine

## Candidate discovery

Enabled modules supply a default brief, the user's explicit settings, scoped approved notes and a bounded history of recently supplied resources. Connected AI searches sources and returns candidates with source references. Unverified links and invalid records fail validation. Public source adapters remain available independently.

## Explainable local ranking

`RecommendationPolicy.Score` separates semantic relevance, explicit interest overlap, same-module saved/hidden feedback, freshness, novelty, approaching deadlines and controlled exploration. News-like topics decay faster than evergreen learning or cultural material. Disabling reading history removes the view-based penalty. Hidden items and disabled modules are excluded before ranking. A deterministic small exploration score cannot replace strong relevance with arbitrary randomness.

The existing edition builder still spreads publishers and modules. Scores are computed once per eligible item rather than repeatedly in comparator calls. Ranking does not call an AI service on every scroll. Saved content is retained under existing storage bounds. Source/quality claims remain tied to evidence; model relevance is an opinion, not a factual confidence probability.

## Context boundaries

A topic-specific conversation uses that module's selected notes. General chat routes an identifiable question to enabled topics and supplies only approved interest/preference/goal notes with matching scopes. Unrecognized questions receive no ambient notes. Explicit daily-overview questions may use the approved cross-topic preferences. Unconfirmed and unrelated private notes stay out. This conservative router is bilingual and intentionally inspectable; it is not an inference that the account granted ChatGPT memory access.

## Queue and progress

`AiActivityRegistry` tracks per-request, ephemeral scope, operation and provider stage. Two managed requests may run together; one background discovery slot leaves capacity for a foreground answer. Cancellation and failure release permits and remove activity state. Queued work is not presented as a running search. The UI uses elapsed seconds, never fake percentages or finish-time estimates.

Only provider-published `reasoning_summary_text` may appear as a short summary. Raw reasoning events, encrypted reasoning and provider credentials never enter activity presentation. A YouTube icon is used only when an observed search action names YouTube; topic icons describe the module, not proof that a particular external tool ran.

## Evaluation and evolution

The current tests cover scope isolation, cancellation, queue capacity, event authenticity, bounded summaries, fresh-versus-old ranking, explicit-feedback isolation, hidden/disabled exclusions, locale-aware suggestions and safe media URL classification. Before expanding recommendations, add anonymized opt-in evaluation with explicit useful/not-useful feedback; do not optimize for time spent or clicks alone. No claim of proven long-term ranking quality is made by the beta.
