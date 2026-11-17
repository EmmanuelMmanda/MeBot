# Jobs discovery update: 0.10.2 candidate

This candidate narrows Jobs to guided preferences, relevant remote listings, saved roles,
source-linked full details and optional role explanation. It removes application stages,
application-document workflows and preparation actions from Jobs navigation.

Existing saved records, notes, drafts, snapshots and backup compatibility remain intact.
Bookmark visibility is independent of the retained legacy record. Unsave never deletes
legacy writing. Opening a source or asking AI never records an application.

A paginated source larger than the 10-page traversal limit keeps bounded fetched results
and prior cached rows, with an explicit partial-refresh notice. The catalog has an 8 MiB
byte budget to protect room for saved user data; reaching it is also reported as partial.

Jobicy source-reported active/closed/unknown checks are bounded and run within the existing
public-source refresh window. Only explicit closed results remove a listing from discovery.
Unknown or failed checks are not proof of an open role. Source timestamps remain visible;
closed saved snapshots remain readable. Remotive and Jobicy coverage is remote-focused,
not exhaustive local/global coverage. There is no guaranteed count of new matches per day.

Release gates: full regression tests, both lint variants, actual native discovery and
persistence journeys, source schema verification, website browser checks, exact-source
same-key signed artifact verification, in-place upgrade and public updater discovery.
This document records intent, not completed acceptance evidence.
