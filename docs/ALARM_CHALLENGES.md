# Alarm challenges

MeBot supports three dismissal modes. They use the same native alarm scheduler,
foreground audio service, snooze policy, and emergency-stop path.

| Mode | Completion | Configuration |
| --- | --- | --- |
| Standard | Tap Dismiss | Snooze and sound settings |
| Math | Answer every generated question | Easy, medium, or hard; 1–5 questions |
| QR | Scan a saved checkpoint | Checkpoint and optional follow-up delay |

New alarms choose three easy math questions. This is a creation default in the
UI, not a change to the meaning of older saved alarms. `AlarmPlan.dismissMode`
defaults to `STANDARD` when absent. `effectiveDismissMode` maps legacy
`requireCheckpoint = true` plans to QR, unless an explicit math mode is selected.
A mode change in the editor updates both fields. Backup validation rejects a QR
mode without a checkpoint and math counts outside 1–5.

## Math generation

`core/MathChallenge.kt` generates local integer arithmetic. Easy uses addition
and nonnegative subtraction; medium also uses multiplication; hard uses a
parenthesized calculation followed by addition or multiplication. Division is
constructed to have a whole-number result. A bounded parser validates only these
arithmetic operations; expressions never execute as code. No web service, model,
or network access is used. Every answer fits the numeric keypad.

Each occurrence gets fresh question identifiers and a newly generated set.
`AlarmSession.mathChallenge` stores the complete questions and current index in
the device-protected operational store. This is operational alarm state, not
part of a portable personal backup. Snoozing, recreating the activity, or creating
a new controller retains the same questions and progress.

On read, the operational store checks the question count, unique identifiers,
progress bounds, answer range, and arithmetic. A missing or damaged challenge
inside a valid session is replaced once and persisted with fresh identifiers.
Recovery preserves the occurrence, phase, snooze usage, and deadlines, records a
diagnostic, and resets only question progress. It never treats corrupt challenge
data as successful completion.

`submitMathAnswer(sessionId, questionId, answer)` validates both identifiers,
checks that the occurrence is ringing, and advances the stored index under the
controller lock. An incorrect answer does not change progress. A duplicate or
stale submission cannot answer the next question. Only the last correct answer
completes the occurrence and allows a queued occurrence to begin. The ordinary
Dismiss action is rejected for math and QR modes.

The visible partial answer is saveable activity UI state. It survives activity
recreation; completed questions live in the independent alarm store. Notifications
open the challenge rather than exposing a normal Dismiss action. Emergency stop
remains on the alarm screen and asks for confirmation before ending the current
and queued occurrences.

## Verification

`MathChallengeTest` checks generated arithmetic, bounds, unique identifiers,
backup round trips, and legacy migration. `MathAlarmControllerTest` checks wrong
answers, stale identifiers, completion, snooze, test-alarm settings, persistence,
and emergency stop. `MathAlarmRecoveryTest` injects damaged operational challenge
data and checks repair, stable persistence, and stale-answer rejection.
`MeBotAlarmJourneyTest` operates the actual Compose keypad,
recreates the activity mid-answer, and verifies compact-screen access.

These challenges are interaction requirements. They do not independently prove
wakefulness. Physical alarm delivery, sound, lock-screen behavior, and camera
use still require testing on the owner's device.
