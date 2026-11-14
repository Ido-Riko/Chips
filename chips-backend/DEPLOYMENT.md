# Personal backend deployment

These files prepare the existing FastAPI/PostgreSQL/OpenRC service for M2. Packaging is offline by default. Uploading an archive does not install it, modify the database, restart services or enroll a device. The actual host, installed version, database state and HTTPS access still need inspection when the server is available.

## 1. Prepare the archive without a server

Use Python 3.12. No application dependencies or `.env` are needed to package. From `chips-backend/`:

```sh
python -m app.scripts.package_release --output dist/chips-backend.tar.gz
```

Or run `./chips-backend/deploy.ps1` with PowerShell from any directory. Set `-PythonCommand` to the Python executable path when needed. The archive contains application sources, SQL migrations, requirements, OpenRC/proxy templates and this guide. `.env`, databases, caches and virtual environments are excluded. Required files are checked before replacing an archive; packaging/upload errors stop the script.

When the server becomes available, provision the destination directory as the intended unprivileged account, then optionally transfer:

```powershell
./chips-backend/deploy.ps1 -Upload -ServerIP YOUR_HOST -User idori -RemoteDir /home/idori/chips-backend
```

SSH/SCP credentials are handled by the local SSH client, not stored by this script. It never copies `.env`, invokes sudo remotely, installs a proxy, changes the database or restarts the app. The upload destination is just an archive until the operator completes the steps below.

## 2. Inspect and install on the actual server

Before changing an existing installation, record its version/configuration, confirm its Python/OpenRC/PostgreSQL setup, and take a restorable PostgreSQL backup. Keep the previous application release and requirements so it can be restored if needed. Stop `chips-api` before extracting into its application directory; extract as the service user, not root. These are operator actions, not archive-upload side effects.

From the intended application directory, as its unprivileged owner:

```sh
tar -xzf chips-backend.tar.gz
python3.12 -m venv .venv
.venv/bin/python -m pip install -r requirements.txt
```

For an existing installation preserve `.env`. For a first installation copy `.env.example` to `.env`, enter the actual database/Redis settings, and set `chmod 600 .env`. Never replace an existing `.env` with the example. Keep `ALLOW_INSECURE_LOCAL=false`. Provider keys stay here on the backend; activity logging does not require an AI provider.

Run the explicit, additive migration as the same service user. The command applies numbered SQL files in order, including `002_beat_saber.sql` for owner-scoped score snapshots and public map metadata. Preserve both migrations in the release:

```sh
.venv/bin/python -m app.scripts.migrate
```

The migration is transactional/repeatable and preserves existing voice rows. Do not automatically roll back or drop its tables if application startup fails. Keep the app stopped while diagnosing a failed migration. Restore the prior app if needed; restoring a database backup is a deliberate recovery operation that can discard newer records.

## 3. Install the loopback service and HTTPS proxy

On an OpenRC host, install `chips-api.openrc` as `/etc/init.d/chips-api` with executable permissions, and `deploy/chips-api.confd` as `/etc/conf.d/chips-api`. Edit `chips_directory` and `chips_user` to match the inspected installation. The defaults retain the earlier `/home/idori/chips-backend` location/account. Service startup checks the executable/migration files, creates a restricted log directory, and never applies migrations itself.

Uvicorn listens only on `127.0.0.1:8000`, accepting forwarded scheme/client headers only from `127.0.0.1`. Keep the proxy on the same host. A remote/containerized proxy needs an explicit reviewed upstream/trust configuration instead of broadening the trusted addresses to `*`. Review log retention on the real host.

The supplied `deploy/Caddyfile.example` is one optional proxy configuration. Preserve an existing working TLS proxy if one is already installed. For Caddy, replace `chips.example.com` with the final DNS name, install it as the host's Caddy configuration, and run `caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile` before reloading it. Supply the domain/DNS/reachability requirements for Caddy's automatic certificate management. Do not use an HTTP URL, a certificate-verification bypass or an untrusted local certificate for the phone's actual enrollment. A LAN-only service needs a certificate that Android trusts and a hostname/network path the phone can reach.

Only after migration/config validation start the API and proxy using the host's service manager. Verify local health at `http://127.0.0.1:8000/health`, then external HTTPS health and an unauthenticated `/api/v1/device` request (expected 401). An HTTP personal-data request must be rejected or redirected to HTTPS, and port 8000 must not be a public upstream. Health alone does not verify migration/authentication/sync.

Source guidance: [OpenRC service variables and hooks](https://github.com/OpenRC/openrc/blob/master/man/openrc-run.8), [Uvicorn proxy trust](https://www.uvicorn.org/deployment/), and [Caddy HTTPS](https://caddyserver.com/docs/automatic-https) / [reverse proxy](https://caddyserver.com/docs/caddyfile/directives/reverse_proxy).

## 4. Enroll and verify

As the service user in the application directory:

```sh
.venv/bin/python -m app.scripts.devices enroll --owner OWNER_UUID
```

Record the stable owner/device UUIDs and store the token securely. Enter the final trusted `https://HOST/api/v1` address and token in Android Settings. Use that same service address, owner UUID and device UUID for later token rotation; the Android database binds to them before uploading. Treat enrollment as an operator step, not a public registration endpoint.

Once the phone is available, verify pending logs upload once, repeated retries/restarts retain only the newest revision, deleted IDs never return, revocation blocks server reads/writes while local logging works, and rotating the same device token recovers pending changes. Verify real midnight/timezone behavior separately. These gates stay open until actual host/Poco results are recorded.

## Drawing images and vision

The drawing routes require migration `003_drawings.sql` (applied by the existing migration command) and the updated Pillow dependency. Image bytes live in PostgreSQL with owner-scoped metadata/feedback; include them in normal database backup planning. Configure `DRAWING_VISION_URL`, `DRAWING_VISION_MODEL` and, only if needed, `DRAWING_VISION_API_KEY` in the private server environment. The URL is a complete compatible chat-completions endpoint and the selected model must accept images. No model/cloud fallback is selected automatically. Missing or failed vision analysis preserves the uploaded image. See [drawing deployment and acceptance](../docs/JAPANESE_DRAWING.md). This is separate from the deferred M3 voice protocol update.

## Pantry

Deploy migration `004_pantry.sql` with the updated release. Pantry transactions are owner-scoped PostgreSQL rows and belong in backups. Receipt transcription reuses the explicitly configured drawing vision runtime; images are not retained by CHIPS on the server. The existing assistant route receives owner inventory context, but the M3 voice protocol/service update remains deferred. See [PANTRY.md](../docs/PANTRY.md).

## Heart-rate archives and linked Beat Saber activities

Deploy the updated backend and run the existing `python -m app.scripts.migrate` command before phone HR synchronization. Additive migration **005_heart_rate.sql** adds owner-scoped immutable HR manifests/chunks, terminal deletion markers and a unique nullable HR link on activities; earlier activity, pantry, drawing and voice rows remain intact. The release package and OpenRC gate require migration 005. See [HR behavior and sync contract](../docs/HEART_RATE.md).

The phone saves locally first. Finalized HR archives upload in bounded run/event pages and are marked complete after count validation. A new linked activity is accepted only after its owner HR archive completes (or exists as an explicit deletion marker). Retrying identical chunks/activities is idempotent; conflicting content is rejected. Normal Stop offers the optional Beat Saber activity without waiting for the server. No running capture is streamed to this API. Include `heart_rate_archives`, `heart_rate_chunks` and activity links in PostgreSQL backups. HR deletion removes server readings/events while retaining a tombstone; activity deletion does not remove its capture.

This change prepares the backend and migration; production rollout and physical phone/server acceptance must be recorded separately.
