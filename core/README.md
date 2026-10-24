# Core domain

This Kotlin/JVM module has no Android dependency. It owns the portable data schema,
daily-visit rules, focus-duration calculations, alarm recurrence and math challenges, and
backup validation. Android components own storage, notifications, permissions,
audio, and scheduling.

## Data and configuration

`AppState` schema version 1 contains a profile, settings, daily visits, one active
focus interval, completed focus records, and alarm plans. Defaults do not contain
someone's identity, location, projects, records, or an enabled alarm occurrence.
Arrival and departure labels are strings so product vocabulary can evolve without
turning self-reported feelings into scores or diagnoses.

`StateValidation` checks imported bounds and references. `BackupCodec.decode`
returns a fully parsed, validated candidate or throws `IllegalArgumentException`.
The caller must only replace its stored state after this function succeeds.
Unknown fields and future schema versions are rejected rather than silently lost.
The UI should map diagnostic exceptions to localized, actionable messages.

Backups are limited to 8 MiB. Record limits are 3,650 daily visits, 10,000 completed
focus intervals, 32 alarms, and three outcomes per visit. These limits are named
in `AppLimits`. They protect an on-device import from unbounded input; a future
schema can introduce a different archive format or retention policy. They do not
authorize deleting existing records.

## Daily visits

`DailyLogic.draftForDate` returns the existing visit for that local calendar date,
including when the visit is already complete. Callers should derive the date from
the device/user's selected zone, then persist a new draft once it is edited.

Completing a visit requires a selected nonempty outcome and a concrete next step.
Arrival and departure choices are optional. It trims text, removes unused empty
outcome slots, and records the visit's completion time. It does not mark the
outcome achieved. Subsequent edits retain the first completion timestamp.

Energy-based duration suggestions are optional hints. They never alter a saved
plan themselves. The caller must show and accept a proposed change explicitly.

## Focus intervals

The persisted deadline lets a timer be reconstructed after process death.
Pausing stores the remaining duration; resuming creates a new deadline from that
duration. Repeating pause/resume calls is safe. Remaining time is clamped between
zero and the original selected duration, including after a backward wall-clock
change. Clock changes can still affect wall-clock timers; this is not a measure
of independently verified attention or work.

`FocusLogic.complete` only creates a record after the duration has elapsed. A
cancelled interval should be discarded or recorded separately by the caller,
never presented as a fully completed interval.

## Alarm dates

`nextAlarmOccurrence` computes the first requested occurrence strictly after the
given `ZonedDateTime`. Weekdays use ISO numbering: Monday is 1 and Sunday is 7.
An empty weekday set means a one-time alarm at the next matching local time.
The platform layer must disable that one-time alarm after it fires.

The calculation uses calendar dates, not fixed 24-hour durations. During a DST
gap, `java.time` shifts the requested local time forward by the gap. During a DST
overlap, the earlier offset is used once; after that occurrence passes, the next
eligible date is selected. This prevents rescheduling the same local alarm twice
on an overlap date. The platform scheduler must still cancel stale callbacks and
track the occurrence being handled.

Disabled plans can be passed to the function for display purposes. The platform
layer must check `enabled` before scheduling.

## Math challenges

`MathQuestions` generates local integer-answer questions at three difficulty levels.
`MathChallenge` stores the question IDs, expressions, answers, and current progress.
The platform controller persists this snapshot and atomically checks both the
session and question ID before accepting an answer. Snoozing retains progress.
Invalid stored challenge data is recovered without treating it as completion.
See [ALARM_CHALLENGES.md](../docs/ALARM_CHALLENGES.md) for the dismissal protocol.

## Tests

Run `./gradlew :core:test`. The suite covers local dates and zones, DST gaps and
overlaps, weekday recurrence, import rejection, portable Unicode data, daily
resume/completion, and persisted focus pause/resume behaviour. Android lifecycle
and real-device behaviour require separate platform tests.
