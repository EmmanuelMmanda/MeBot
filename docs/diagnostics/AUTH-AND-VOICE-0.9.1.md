# 0.9.1 callback responsiveness and voice boundary

Reviewed 2026-10-25. Source-level diagnosis and synthetic regression coverage are not a trace
of the reported phone failure. No account access, live inference, microphone capture or paid
provider request was used for this work.

## Browser return is a handoff, not completed connection

The localized “Return to MeBot / Rudi MeBot” page confirms that the temporary loopback listener
received a matching sign-in response. Token exchange, signed identity verification, durable
credential storage, model discovery and completed inference are separate steps in the app.
A browser page must not label those later checks as successful before they actually complete.

Chrome requires a user gesture for external-app intents, so a timer-driven automatic redirect
is not a reliable repair. The existing explicit return button and recent-apps fallback remain.
The package-specific return URI carries no OAuth code/token and changes navigation only;
PKCE, state, nonce, identity checks and account isolation remain authoritative.
[Chrome intent guidance](https://developer.chrome.com/docs/android/intents).

Two concrete listener issues were found:

1. A single accept/read loop let an idle/speculative browser connection hold the real callback
   behind its socket-header timeout. Header parsing now runs on at most four concurrent loopback
   clients, keeping the existing per-client size/time bounds. Excess connections are closed.
2. A valid, state-matched request could be thrown away if writing the browser HTML failed.
   Once valid consent has arrived, display failure must not force the user to repeat consent.
   The callback is now atomically claimed once, the HTML response is best effort, and the valid
   result survives an I/O failure writing that response. Wrong-state and incomplete requests
   still cannot consume the attempt.

Cancellation closes the listening socket and all admitted clients. A single-use winner plus
existing manager attempt-generation checks prevent duplicate exchanges and stale-account writes.
No provider content, codes, PKCE material or OAuth URLs are added to logs or diagnostic exports.
Synthetic tests cover idle preconnection, response-write failure, rejected wrong state, competing
callbacks, saturated-reader recovery, malformed-request HTTP errors, shutdown and cancellation
while a matched response writer is blocked (no subsequent token exchange). Actual browser/OEM handoff and process death still need phone acceptance.
Android killing the process during consent still requires a fresh attempt; secrets are not saved
as restorable browser state.

## Inference compatibility review

No concrete endpoint mismatch was found in the existing subscription transport. Authorization,
issued-client token exchange, loopback redirect and requested resource/scopes match the current
[sign-in contract](https://developers.openai.com/siwc/token-sharing-open-source/sign-in).
Model discovery uses `/v1/models`; requests use public `/v1/responses`, a bearer token,
`store: false`, `stream: true`, array input and instructions, omitting unsupported sampling/output
controls. The selected connection changes only after completed inference, not after browser return.

Callback improvements therefore do not prove the reported AI failure is resolved. Remaining causes
can include unavailable account/model permission, workspace web-search policy, provider allowance,
local request cap, network errors or interrupted streams. Diagnose the actual failing stage with
existing safe HTTP status/request ID/allowlisted error metadata. Never weaken authentication,
substitute an undocumented endpoint, or silently switch the billing route to make a check pass.

## What voice currently means

The existing microphone invokes Android's installed speech recognizer to fill editable text.
Read-aloud uses the device's text-to-speech engine. These are not an OpenAI Realtime session,
continuous conversation, interruptible speech-to-speech, or proof of offline recognition.
Recognizer/TTS availability and networking depend on the installed Android services.

## Realtime feasibility — supported route only

The current Sign in with ChatGPT documentation specifies authenticated model discovery and the
public Responses endpoint for plan inference, with streamed completion. Its documented input
limitations exclude audio/video and transcription for that flow. There is no documented basis
here for treating MeBot's ChatGPT OAuth token as Realtime voice authorization. Do not send that
token to an undocumented endpoint or silently fall back to API billing.
[SIWC models/inference](https://developers.openai.com/siwc/token-sharing-open-source/models-and-inference),
[SIWC limitations](https://developers.openai.com/siwc/token-sharing-open-source/preview-limitations).

A separately configured API-backed implementation is feasible. Official Realtime guidance
supports mobile WebRTC and short-lived client credentials obtained through a controlled server;
current GA routes include `/v1/realtime/client_secrets` and `/v1/realtime/calls`. API-account usage
is billed separately from ChatGPT subscriptions. This is a future implementation boundary, not
an enabled or tested MeBot capability.
[Realtime overview](https://developers.openai.com/api/docs/guides/realtime),
[WebRTC authentication](https://developers.openai.com/api/docs/guides/voice-webrtc?voice-api=realtime),
[Billing separation](https://help.openai.com/en/articles/9039756-managing-billing-for-chatgpt-and-the-api-platform).

A complete implementation would need explicit route/cost consent; an authenticated ephemeral-token
broker with no shared key embedded in the APK; microphone permission and visible recording state;
WebRTC/audio-focus/route management; mute, stop, interruption and reconnect semantics; bounded
session duration/spend controls; no personal notes beyond the selected scope; and immediate track
shutdown on app lock/background. Provider/model/region availability and real-device audio behavior
must be verified separately. No fake realtime toggle or subscription-access claim is introduced.
