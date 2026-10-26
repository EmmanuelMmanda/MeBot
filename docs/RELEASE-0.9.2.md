# MeBot 0.9.2 — document and settings repair

Version code 19 updates 0.9.1/code18 using the same production signer. Do not uninstall or erase data to update.

The design keeps the existing MeBot shell and controls while restoring visible import/location, a guided context-import screen, a clearer AI connection layout, local-only saved-account removal, dedicated starred follow-up settings and Personalization-owned app lock. Ordinary app use does not silently enable background updates, biometrics or a new provider connection.

Document handling covers launch failure, cancellation, returned result, recreation, locked return and stale target/reset isolation. Parsing/review remains separate from committing imported data; invalid content must not replace existing information. Tests cannot guarantee that an app will never crash, and no owner-device stack trace or live provider success is claimed.

See [repair acceptance and scheduling evidence](diagnostics/REPAIR-0.9.2.md), [document-picker diagnostics](diagnostics/DOCUMENT-PICKERS-0.9.2.md) and [journey design](design/journeys-092.md). Final test, lint, native-render and signature results accompany the delivered artifact. New previews use synthetic fixtures, not the owner's accounts, data or PIN.

Public source/release/site promotion is separate from private signed delivery and must not be claimed while publication remains blocked.
