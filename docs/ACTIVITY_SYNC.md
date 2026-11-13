# Daily logging and device synchronization

M2 implements local-first activity logging for the first personal deployment. Android stores stable UUID records in `chips.db` using the platform SQLite helper. Room was proposed, not required; native SQLite avoids adding a Kotlin code-generation toolchain to the existing AGP setup. Each row is both the visible record and its durable pending mutation, so saving a session and enqueueing its change cannot diverge. WorkManager is a delivery trigger; a process crash between the database write and scheduling is recovered on the next app launch.

## Rules and conflict policy

- Updated 13 November 2026: Quick Log, rhythm and streak buttons open their corresponding implemented features. Japanese reviews and drawing imports create their own stable linked completions. Workout explicitly confirms a manual log; Beat Saber offers Log completed session inside its explorer. Opening or revisiting a feature does not create/delete completions. History still supports explicit editing/deletion and manual log Undo. Today and streaks count each activity once per recorded calendar date, excluding deleted sessions. The earlier tap-to-toggle dashboard behavior is superseded.
- The capture timestamp is UTC epoch milliseconds. The phone’s IANA timezone and calendar date are recorded together. Existing sessions retain that date during travel. History can explicitly correct the completion date, type and note while retaining the capture timestamp.
- A current streak ends today, or yesterday when today has no completion. A missing date breaks it. Dates are compared as calendar dates rather than 24-hour durations, including daylight-saving transitions. Future entries do not extend today’s streak.
- The originating enrolled device is the only writer for a record. It increases the record’s integer revision for each edit/delete. Other devices belonging to that owner can read the snapshot, but cannot edit the record. This is an explicit first-personal-deployment limitation, not general multi-device editing support.
- The server returns its authoritative row for a stale retry. Equal revisions with different content return 409. Higher revisions replace active records. Deleted IDs are permanent tombstones: a late create or edit cannot resurrect them, even with a higher revision. A new session gets a new UUID.
- Android ignores an acknowledgement older than a local edit made during the request. A server tombstone always wins. Undoing a just-added session writes its tombstone; if other sessions exist on the same day, completion remains.
- Tombstones are retained indefinitely for correctness. Activity notes are cleared on local deletion. Voice retention/export, database upgrades beyond schema v1, backup restore and multi-device reconciliation remain their respective roadmap work.

## Backend setup

Use [the deployment guide](../chips-backend/DEPLOYMENT.md) for offline packaging, the explicit upload step, OpenRC installation and the HTTPS proxy template. `deploy.ps1` now packages by default; uploading requires `-Upload -ServerIP HOST` and never transfers `.env` or applies a migration. The archive includes the actual `migrations/` directory. The OpenRC service binds only to loopback and trusts forwarded headers only from the same-host proxy.

Use Python 3.12 and PostgreSQL. From `chips-backend/`:

```sh
python3.12 -m venv .venv
. .venv/bin/activate
pip install -r requirements.txt
```

Set `DATABASE_URL=postgresql+asyncpg://...` and `REDIS_URL=redis://...` in the environment or an ignored `.env`. Set only the chosen AI provider key on the backend for the existing voice demo. Activity logging never calls an AI provider. Apply the additive migration before running the updated server:

```sh
python -m app.scripts.migrate
```

The migration creates device/activity tables, tracks version 1 and adds nullable ownership to existing voice logs without assigning old logs to a new owner. It runs transactionally and is repeatable. It does not delete existing data or automatically run at app startup. Back up the existing database before applying changes to an actual deployment.

Create a stable owner UUID, then enroll the phone (substitute the real owner UUID):

```sh
python -m app.scripts.devices enroll --owner OWNER_UUID
```

The command prints the device UUID and a randomly generated token once. Only its SHA-256 digest is stored by the server. Store the token securely and enter it on Android in Settings → Synchronization, together with `https://YOUR_HOST/api/v1`. Token values must not be committed, shared in logs or placed in Android build configuration.

Rotate a token while retaining record ownership:

```sh
python -m app.scripts.devices enroll --owner OWNER_UUID --device EXISTING_DEVICE_UUID
python -m app.scripts.devices revoke --device EXISTING_DEVICE_UUID
```

Rotation invalidates the previous token immediately; revocation rejects subsequent reads/writes. Requests already in a transaction may complete before revocation acquires the device lock. There is no public registration/enrollment endpoint. An operator must have database access to enroll a device.

Run Uvicorn behind a TLS reverse proxy. Only trust forwarded headers from the actual proxy; do not expose the unencrypted upstream publicly. Example for a proxy on loopback:

```sh
uvicorn app.main:app --host 127.0.0.1 --port 8000 --proxy-headers --forwarded-allow-ips 127.0.0.1
```

Personal-data routes reject unencrypted requests by default. `ALLOW_INSECURE_LOCAL=true` is an explicit backend-only local-development option; it does not disable authentication. Android synchronization always requires HTTPS with normal certificate verification and refuses redirects. Release Android builds disable cleartext traffic globally. The existing voice route also requires enrollment and HTTPS, and Android sends its token only to the configured enrolled service. Transcription remains the M3 stub.

## Android credentials and retries

The enrollment token and endpoint are encrypted together with AES-GCM using a non-exportable Android Keystore key. The encrypted preferences are excluded from both cloud backup and device transfer. The activity database binds to the first successfully verified service URL, owner UUID and device UUID, before uploading anything. Changing credentials cannot silently send this database to another account or service. Rotate the existing device credential; moving accounts or service addresses needs an explicit future data-migration flow.

Local-only sessions remain pending until enrollment. Once configured, network-constrained unique WorkManager jobs send pending revisions, then fetch a complete owned snapshot including tombstones. Periodic work checks for remote changes; exponential retry handles network/server failures. Manual Retry sync replaces the pending one-time job. Workers serialize in-process, and HTTP calls cancel with their coroutine. Authentication, ownership, validation and binding failures need correction and remain visible in Settings/History. Disconnect persists a paused state and cancels background work while retaining the encrypted service URL/token and local records. Both scheduling and worker execution check that synchronization is connected, so reopening the app or logging more activities cannot silently resume it. Reconnect resumes with the saved enrollment; saving sync settings also reconnects. Existing enrollments without a connection flag remain connected after upgrade. Force-stop suspends Android background execution until the app is opened again; the app reschedules synchronization on launch.

Implementation follows the platform guidance for [persistent WorkManager work](https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/manage-work), [SQLiteOpenHelper](https://developer.android.com/reference/android/database/sqlite/SQLiteOpenHelper) and the [Android Keystore](https://developer.android.com/privacy-and-security/keystore).

## HTTP contract

All routes below require `Authorization: Bearer DEVICE_TOKEN`. Missing, invalid and revoked tokens return 401; unencrypted requests return 403. Ownership is taken from the verified token, never a caller-supplied owner field.

| Route | Behavior |
| --- | --- |
| `GET /api/v1/device` | Current owner/device UUIDs for local binding |
| `GET /api/v1/activities` | Complete owned snapshot, including deletions |
| `GET /api/v1/activities/{uuid}` | Owned record or 404 |
| `PUT /api/v1/activities/{uuid}` | Idempotent create/update/tombstone; path/body UUIDs must match |
| `DELETE /api/v1/activities/{uuid}` | Owned originating-device record becomes a tombstone; repeated delete is idempotent |

A record contains `id` (UUID), `type` (`WORKOUT`, `BEAT_SABER`, `DRAWING`, `JAPANESE`), `occurred_at` (UTC epoch milliseconds), `zone_id` (IANA name), `local_day` (`YYYY-MM-DD`), `note` (up to 2000 characters), `revision` (positive integer), and `deleted` (boolean). PUT can create a tombstone before the server has received the original create, covering offline undo. Full snapshots are appropriate to this personal dataset; paging and tombstone compaction need a defined protocol before broader use.

## Automated and device verification

```sh
# Android, from chips-mobile/
bash ./gradlew :app:assembleDebug :app:lintDebug :app:testDebugUnitTest
bash ./gradlew :app:connectedDebugAndroidTest

# Backend, from chips-backend/
pip install -r requirements-test.txt
python -m pytest -q
```

SQLite-backed backend tests exercise the real routes/auth dependencies. The optional `TEST_POSTGRES_URL` must refer to a disposable PostgreSQL test database: the integration test creates and drops a random isolated schema, applies the migration twice over existing voice data, and submits concurrent revisions through the routes. CI provisions that test database. Android instrumentation exercises the real SQLite database across reopen, UI logging/recreation/undo and lost-response/retry/edit ordering through an injected remote. See `ANDROID_PROGRESS.md` for executed results and the Android README for physical-device acceptance steps.
