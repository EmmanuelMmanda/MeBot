# Jobs workspace

Jobs is a native destination in MeBot, independent of an AI connection. The five primary destinations are Feed, Jobs, MeBot, Saved and You. Existing Modules remain accessible from Feed → All modules.

## Data and privacy

The first listing provider is Remotive's public remote-jobs API. Each listing keeps Remotive attribution and links back to its original Remotive URL. Provider terms describe a 24-hour publication delay and recommend no more than four requests per day; the repository persists a six-hour attempt interval, including failures. Queries and preferences are matched locally and are not sent to this provider. This is not comprehensive local or worldwide job coverage.

Jobs live in a separate bounded on-device DataStore. Successful provider refresh replaces the current provider snapshot. A failure preserves the previous cache. Application records preserve a job snapshot independently, so a removed listing does not destroy application notes. Existing general feed backup/import does not yet include this separate Jobs store; do not claim Jobs backup support.

## Matching

Roles, skills, location and employment-type filtering are deterministic. Reasons are explanatory, not probabilities of being hired. Remote status does not establish country eligibility. Unknown employer restrictions stay unknown. Salary is original source text; a salary preference is advisory until amount, currency and pay period can be normalized reliably.

## AI and documents

Jobs explicitly opens a stable job-scoped conversation in the existing MeBot assistant. The bounded listing attachment is untrusted source data. Existing streaming, cancellation, provider configuration and conversation history are reused. No background AI requests occur. The user can import a completed assistant answer into an editable application draft, review it and export a PDF through Android's document picker. Documents are drafts, not submitted applications. CV PDF parsing and automatic job submissions are not provided by this implementation.

## Alerts

Alerts are optional and use Android WorkManager with local matching. Only a persisted opt-in schedules work. Notification permission remains user-controlled. Seen listing IDs deduplicate alerts. Notifications do not expose employer, job-search or salary details on the lock screen. WorkManager timing is approximate and depends on Android battery/network conditions.

## Verification and release

Run the repository's Android/core test, lint and build gates, then render actual UI and test interrupted flows. A successful source push is not an app update. A production APK must retain applicationId `mebot.nguli.com`, use the existing signing certificate, increment versionCode beyond 26, and publish verified updater metadata/assets. Preserve user data; never ask for uninstall as an update workaround.
