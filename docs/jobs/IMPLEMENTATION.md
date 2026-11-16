# Jobs workspace

Jobs is a native destination in MeBot, independent of an AI connection. The five primary destinations are Feed, Jobs, MeBot, Saved and You. Existing Modules remain accessible from Feed → All modules.

## Data and privacy

Listing providers are Remotive and Jobicy public remote-jobs APIs. Jobicy adds recent listings (seven-day window, three-hour delay) with cursor pagination. Each listing keeps its provider attribution and original canonical link. Remotive terms describe a 24-hour publication delay and recommend no more than four requests per day; the repository persists a six-hour attempt interval for both sources, including contacted-source failures. Offline checks do not consume this interval. Queries and preferences are matched locally and are not sent to either provider. This is not comprehensive local or worldwide job coverage. For a Tanzania preference, external Work.tz and official Ajira links provide a separate browsing route; those sites are not ingested, matched or included in alerts. Stored preferences and documents are not added to their URLs.

Jobs live in a separate bounded on-device DataStore. Successful provider refresh replaces the current provider snapshot. A failure preserves the previous cache. Application records preserve a job snapshot independently, so a removed listing does not destroy application notes. Personal-data backup includes Jobs preferences, application snapshots, notes and document drafts, not the refreshable listing catalog. Import shows the Jobs record count, disables alerts, and restores both stores through a durable pending-import marker. After interruption, repository access completes the pending restore before exposing either store. Legacy backups without Jobs preserve existing Jobs data.

## Matching

Roles, skills, location and employment-type filtering are deterministic. Reasons are explanatory, not probabilities of being hired. Remote status does not establish country eligibility. Unknown employer restrictions stay unknown. Salary retains original source text. Where a provider supplies structured amount/currency/period, a minimum filters comparable ranges. Month/year conversion is supported within the same currency; currency conversion and assumed hours are not. Unknown pay stays visible and explicitly unverified.

## AI and documents

Jobs explicitly opens a stable job-scoped conversation in the existing MeBot assistant. The bounded listing attachment is untrusted source data. Existing streaming, cancellation, provider configuration and conversation history are reused. No background AI requests occur. The user can import a completed assistant answer into an editable application draft, review it and export a PDF through Android's document picker. Local PDFBox generation embeds Figtree for English/Kiswahili and supported Latin characters; unsupported characters fail explicitly rather than being silently changed. No document bytes are uploaded. Export survives rotation; if Android restores the process during export, the operation is reported interrupted and the retained draft can be retried, not silently re-exported. Documents are drafts, not submitted applications. CV PDF parsing and automatic job submissions are not provided by this implementation.

## Alerts

Alerts are optional and use Android WorkManager with local matching. Only a persisted opt-in schedules work. Notification permission remains user-controlled. Seen listing IDs deduplicate alerts. Notifications do not expose employer, job-search or salary details on the lock screen. WorkManager timing is approximate and depends on Android battery/network conditions.

## Verification and release

Run the repository's Android/core test, lint and build gates, then render actual UI and test interrupted flows. A successful source push is not an app update. A production APK must retain applicationId `mebot.nguli.com`, use the existing signing certificate, increment versionCode beyond 26, and publish verified updater metadata/assets. Preserve user data; never ask for uninstall as an update workaround.

## State and queues

Manual refresh uses a unique one-time WorkManager request with a network constraint and bounded backoff, separate from periodic alerts. Its queued, offline, running, retrying, cancelled and completed states are visible. Repeated taps do not create concurrent refreshes, and retries never bypass source limits. Application/preferences mutations are serialized and bookmarks are idempotent. Interrupted unacknowledged saves require review/retry rather than claiming success. AI conversation streaming retains the existing cancellation/retry lifecycle; there is no durable background AI generation or auto-submission queue.
