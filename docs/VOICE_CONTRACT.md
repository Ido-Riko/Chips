# M3 voice client/service contract (version 1)

The Android implementation is ready for this contract. Server implementation is deferred; the existing stub/plain-text server is incompatible. This document specifies the handoff, not a deployed capability.

## Transport and enrollment

Use the enrolled HTTPS endpoint from Settings, ending in `/api/v1`, and the existing bearer device credential. Android binds each assistant window's retries to one endpoint/token snapshot. Updating enrollment requires closing and reopening voice. Activity Disconnect pauses activity sync; explicit voice requests still use saved enrollment. Provider/API credentials never enter Android. The server independently configures STT and LLM endpoints/models as PC-hosted or explicitly chosen API providers; unavailable PC services never silently switch to an API.

The client does not follow redirects or automatically retry HTTP connections. Each call has a 120-second total deadline, with a 15-second connection, 30-second write and 90-second read timeout. Successful/error response bodies are limited to 256 KiB. Client cancellation closes the HTTP call during upload, processing waits and response reads. Server inference cancellation must be implemented and accepted separately.

## Compatibility check

Before uploading the first recording in an assistant window, Android sends authenticated `GET /voice-capabilities`. This must have no inference side effects and return:

```json
{"protocol_version": 1, "idempotency": true}
```

Only a compatible service receives the audio POST. A missing endpoint, invalid version or absent/false idempotency support produces an actionable update error. A transient capability failure may be retried without performing inference. The server must advertise idempotency only after implementing the guarantees below.

## Submission

Authenticated `POST /voice-log`, with headers:

- `Accept: application/json`
- `X-Chips-Voice-Protocol: 1`
- `Idempotency-Key: <request UUID>`

Multipart fields:

- `request_id`: the same UUID as the header.
- `protocol_version`: `1`.
- `audio`: MPEG-4 container, AAC codec, `.m4a` filename, `audio/mp4` MIME type. Captures shorter than 400 ms are rejected locally; recording stops/discards at 60 seconds. Server must independently validate duration, format and upload size.

The client creates one UUID after each valid recording. Explicit Retry request reuses that UUID, capture timestamp and audio. Multipart boundaries may differ. The idempotency payload identity is the audio bytes plus request metadata, not raw multipart framing. Closing/discarding cancels the session and deletes cached audio. There is no automatic background voice queue or replay on startup.

The server must authenticate and scope the key by owner before inference; atomically claim it, reject different audio under an existing key, and join/resume the existing operation on repeated/concurrent requests. Repeated keys must not invoke STT/LLM again or duplicate history, including after a lost response and service restart. Retain the result/terminal request identity according to an explicit retention policy; do not expire a key and silently treat a retry as new inference. Revoked access must remain rejected. These are server acceptance gates, not claims proven by the Android tests.

## Success

Return HTTP 200 with `application/json` and:

```json
{
  "protocol_version": 1,
  "request_id": "<the submitted UUID>",
  "is_demo": false,
  "transcript": "The words actually spoken",
  "answer": "The corresponding model reply"
}
```

The client requires version 1, explicit `is_demo: false`, an exactly matching request ID and nonblank string transcript/answer. Transcript length is at most 10,000 characters; answer length is at most 64,000. Malformed, mismatched, demo and legacy plain-text responses are rejected and never saved as personal conversations.

After validation, Android deletes audio and saves the transcript/reply locally before reporting success. The UUID is the local primary key; identical saves are idempotent and conflicting content under the same ID is rejected. If local saving fails, Retry saving repeats only the local transaction and never calls the service again. If the window closes during a completed-reply save, the transaction may finish, but cannot update a later assistant session.

## Failure

Use an appropriate non-2xx HTTP status, with optional structured body:

```json
{"error": {"code": "stt_unavailable"}}
```

Android recognizes `stt_unavailable`, `llm_unavailable`, and `no_speech`. It maps 401/403 to enrollment guidance, 413 to shorter-recording guidance, 429 to busy/retry, 404/415/426 to service-update guidance, and other failures to safe generic errors. It never displays raw server diagnostics. Network/timeout failures, 408/429 and 5xx responses allow explicit retry using the same request ID. Other 4xx and invalid/demo success responses discard the clip and require a new recording or service/enrollment fix. No-speech should use a terminal 4xx response (for example 422).

## Local history, audio and lock policy

History → Conversations reads saved transcripts/replies, expands full conversations and confirms deletion from this phone. Activity records are unaffected. Server copies have separate retention/deletion; local deletion does not claim remote deletion. The existing version-1 SQLite voice_history table is used, without a destructive migration.

Audio is kept only in the app cache while recording, uploading or waiting for an explicit retry. It is removed after success, terminal failure, discard, cancellation or dismissal. Abandoned `chips_voice_*` files are removed on cold process startup. Audio is not stored in history or Android backup.

Owner decision, 13 November 2026: assistant access while locked is enabled by default. Settings → Assistant security → Allow assistant while locked persists the owner’s choice; changing it requires an unlocked phone. The notice explains that voice replies and activity screens may reveal personal information to anyone holding the phone. Voice/retry/feature actions all read the same policy. When disabled, locking cancels captures/requests and hides private content behind unlock guidance.

The service advertises launch from keyguard and implements [onLaunchVoiceAssistFromKeyguard](https://developer.android.com/reference/android/service/voice/VoiceInteractionService#onLaunchVoiceAssistFromKeyguard()) by opening an internal, non-exported LockscreenAssistantActivity when enabled. Its show-when-locked flag follows the preference; ordinary launcher windows do not gain lockscreen visibility. Session invocation and feature handoff also respect the preference. Show-when-locked does not dismiss or unlock Android’s keyguard. Closing the activity-based assistant while locked finishes that assistant window. Physical Poco invocation/firmware behavior and real speech inference remain separate acceptance gates.

## Acceptance split

Android can be validated using a local test service for the protocol, same-ID retry, error states, transcript display, SQLite reopen/deletion, UI cancellation, resource cleanup and privacy gating. This does not prove real STT, answer quality, server-side idempotency or provider cancellation. Final M3 acceptance still requires ten representative real utterances, independently selectable PC/API inference, unavailable-PC recovery and physical-device scenarios.

## Pantry context for the deferred service

The main assistant handles meal recommendations using authenticated-owner pantry inventory (`app.routers.pantry.inventory`). The existing legacy voice route now includes this reference context; the M3-compatible implementation must retain it without treating item names as instructions. Show missing ingredients, distinguish count from package size, check negative/conflicting stock and disclose last-sync freshness. Never decrement pantry stock as a side effect of generated suggestions. Pantry work does not change the legacy route’s protocol incompatibility.

## Local assistant shortcuts

The assistant also exposes Workout, Beat Saber, Japanese, Drawing and Pantry routes. These are local UI actions, independent of speech inference and microphone permission. Busy/recording states hide shortcuts; selecting one cancels any retained recording/request before routing. The Android session uses [startAssistantActivity](https://developer.android.com/reference/android/service/voice/VoiceInteractionSession#startAssistantActivity(android.content.Intent)) to open the app, with a validated feature extra consumed once by MainActivity. Opening a route never creates/deletes completion records. Workout requires manual confirmation; Beat Saber logs only through its explicit completed-session action.
