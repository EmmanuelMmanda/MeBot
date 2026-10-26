# 0.9.5 native UI evidence

These are normal-size full-activity captures from Android host tests at 411dp width. Feed and detail views are intentionally scrolled to the suggested-question area; MeBot shows the prepared editable draft before Send.

All story titles, publishers, provider responses and preferences are synthetic test fixtures. They are not live articles, an owner account, a provider round trip or phone acceptance evidence. English story fixture text remains English in the Kiswahili UI captures. The selected provider label is fixture configuration, not a statement that a subscription or live model was tested.

- `en-feed.png`, `sw-feed.png`: item-grounded question alongside the existing feed controls.
- `en-detail.png`, `sw-detail.png`: secondary source access and suggested question in story detail.
- `en-mebot-prefilled.png`, `sw-mebot-prefilled.png`: editable item-scoped question; selecting it did not send a request.

User-facing galleries must keep these labelled as sample content and distinguish accessibility stress captures from normal sizing.
