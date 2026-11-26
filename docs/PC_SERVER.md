# Chips on the owner's PC

Deployment checkpoint: **14 November 2026**. The owner explicitly chose a **fresh database**. The offline former `chips-server` was not migrated or modified. This PC is now the personal server; initial real-phone drawing and voice use has now succeeded; broader phone/band checks remain open. Updated 15 November 2026.

## Phone connection

- Service address in CHIPS Settings: **`https://bazzite.tail830ec2.ts.net:8443/api/v1`**.
- Enable Tailscale on the Poco and keep the PC awake and online.
- The new phone enrollment is in the private, ignored file `chips-backend/.runtime/phone-enrollment.json` (mode 0600, parent directory 0700). Copy its `token` into CHIPS Settings. Do not put it in a commit, screenshot or issue.
- This is a new owner/device identity. If Android reports an existing enrollment identity mismatch, preserve/export its local data and inspect the binding before changing anything. Do not clear app data or uninstall to bypass the check.
- No phone was attached by USB during deployment. Subsequent real-phone use reached this server: a drawing was stored with completed AI feedback, and the owner received voice answers. No Android installation, instrumentation, uninstall or data deletion was performed.
- ComfyUI's existing HTTPS port 443 routes are unchanged. Chips uses tailnet-only HTTPS port 8443; raw API and database ports listen on loopback.

## Installed services and storage

| Component | Configuration |
| --- | --- |
| API | `systemctl --user status chips-api`; single Uvicorn worker on `127.0.0.1:8000` |
| PostgreSQL 17 | Rootless Podman Quadlet `chips-db.service`; persistent `chips-pgdata` volume; `127.0.0.1:55432` |
| HTTPS | Background Tailscale Serve, port 8443, valid TLS certificate |
| Local speech | Multilingual Whisper small, CPU int8; bounded cancellable subprocess; model in `.runtime/whisper-small` |
| Answers and vision | Existing Ollama `qwen3.5:9b`, RTX 5080; `127.0.0.1:11434`; persistent user service |
| Backup | `chips-backup.timer`, daily custom-format PostgreSQL dumps in `.runtime/backups` |
| Boot/logout | User services enabled and user linger enabled; actual reboot acceptance has not been run |

The PC has a Ryzen 7 9800X3D, 32 GB RAM and an RTX 5080 with 16 GB VRAM. Ollama is shared with ComfyUI; simultaneous GPU workloads can affect latency. STT uses the CPU to leave GPU capacity for the selected multimodal model. No cloud inference fallback is configured. Voice uses low reasoning with a 2400-token budget after a no-reasoning sample produced an incorrect Japanese romanization; vision uses no reasoning to keep feedback within its output budget. This improves a checked example, not a guarantee of correctness. Redis is a retained configuration field, not a service dependency of the implemented routes.

The database holds activity records/tombstones, drawings/images/feedback, pantry transactions, HR archives/chunks, and voice results/deduplication identities. Japanese, BeatLeader exploration, Health Connect sleep and HR reports continue to work locally on Android as documented in their feature guides.

## Everyday startup

The server is configured to start automatically when this PC boots, including after logout (user linger is enabled). A full reboot test has not yet been recorded. Keep the PC awake and online; suspended or powered-off hardware cannot serve the phone. You do not need to open a terminal, Android Studio or this chat for normal use.

To start everything manually, open a terminal on this PC and run:

```sh
/var/home/ido/Documents/ChatGPT/Chips/chips-backend/deploy/start-pc.sh
```

Run it as your normal desktop user, **without sudo**. It starts the system Tailscale daemon if needed, connects Tailscale, starts Ollama, PostgreSQL, Chips and the backup timer, waits for local services, and verifies private HTTPS on port 8443. It may ask for your sudo password to start/reconnect Tailscale or show a sign-in link. Existing Tailscale preferences are preserved; if Tailscale requests explicit flags, resolve that message instead of using `--reset`. ComfyUI on port 443 is preserved. Repeated runs leave already-running services running and do not reset data or enrollment.

The script starts an already-provisioned PC; it does not install packages, download models or apply migrations. You can close the terminal after it succeeds.

Check them:

```sh
systemctl --user is-active chips-db.service ollama.service chips-api.service chips-backup.timer
curl --fail https://bazzite.tail830ec2.ts.net:8443/health
```

Expect four `active` lines and `{"status":"ok"}`. The health response confirms the API is reachable, not that model inference has completed. Allow a few seconds after starting before checking.

On the phone, turn **Tailscale on**, then open Chips. Keep the saved service address and token. Offline features do not need Tailscale, but server sync, voice answers, drawing feedback and receipt transcription do. The PC also needs its Tailscale connection; `tailscale status` and `tailscale serve status` show its state. HTTPS forwarding was configured persistently and normally needs no startup command.

If only Chips is stuck, restart its API (this interrupts an active voice request):

```sh
systemctl --user restart chips-api.service
```

If the model service itself is stuck, use `systemctl --user restart ollama.service`; Ollama is shared with ComfyUI, so this also interrupts its active model requests. Do not rerun provisioning, create a new database, or enroll a new device as part of ordinary startup.

## Actual phone use and intermittent voice failure

Confirmed by 15 November 2026: the server received the owner's drawing and retained both the image and Qwen 3.5 9B feedback. The owner reported receiving text from a voice request, then an “answer service is unavailable” error, then another successful new request. This demonstrates working real-phone transport and inference, not completion of all voice/quality checks or the full activity-sync acceptance sequence.

The intermittent failure is **not yet diagnosed or confirmed fixed**. The model was running when inspected. Nearby model logs showed an HTTP 200 generation that consumed the entire 2400-token budget; exhausting the budget before producing final answer text is a possible cause, but the exact failed response was not captured. A later successful request does not establish that the earlier problem was only connectivity. No model configuration change was made in response to this incident.

If this happens again, note the approximate time and whether other requests still work. Make a new recording: a failed request's identity is retained, so replaying the same failed request does not rerun inference. Do not clear phone data or change enrollment to fix this error.

## Operations

Run commands from `chips-backend/` as the owner:

```sh
systemctl --user status chips-api chips-db ollama
systemctl --user list-timers chips-backup.timer
curl --fail https://bazzite.tail830ec2.ts.net:8443/health
journalctl --user -u chips-api -n 50 --no-pager
.venv/bin/python deploy/backup-pc.py
```

`/health` is liveness only. Authenticated `GET /api/v1/voice-capabilities` confirms protocol support, not model availability. Check a real voice/drawing request after an inference configuration change. Plain HTTP personal-data access is rejected; do not enable `ALLOW_INSECURE_LOCAL` for phone use.

For an update: take a backup, stop the API, install reviewed dependencies in `.venv`, run `.venv/bin/python -m app.scripts.migrate`, then start the API and verify HTTPS. Never run multiple API workers: startup recovery deliberately makes unfinished voice requests terminal. Migrations are explicit and additive, not automatically run at service start.

The guided first-run provisioner is `deploy/provision-pc.sh` (or `deploy/provision-pc.ps1` on Windows); `deploy/install-pc.py` is its lower-level service setup step. The lower-level installer creates private database/API configuration only when `.env` is absent, installs database/API user units and starts the database. Migration, API activation, trusted HTTPS, model download and enrollment remain explicit steps. The guided flow also installs backup unit files and enables the timer. Existing Ollama services are preserved; the new-install flow installs and configures a user service. It never migrates another host. Local Whisper uses `requirements-local-stt.txt`, including the verified PyAV pin; newer unpinned PyAV broke the older Whisper decoder interface during preparation.

### New PC provisioning

The guided provisioners run each step only after confirmation and print what they are doing. `--yes` (`-Yes` in PowerShell) auto-confirms while still displaying every step. They prepare a fresh database and device enrollment; they do not import another PC's database or credentials.

On Bazzite/Fedora, from the repository root, run:

```sh
bash chips-backend/deploy/provision-pc.sh
```

On Windows 11, open PowerShell as Administrator and run:

```powershell
.\chips-backend\deploy\provision-pc.ps1
```

The Windows entry point installs WSL2 with Ubuntu if needed. Complete Ubuntu's first-run username/password setup and reboot if Windows requests it, then rerun the script. WSL2 is a Linux environment integrated with Windows; Chips' server services run in Ubuntu, not as native Windows services. The installer enables systemd, clones the repository into Ubuntu's Linux home, and invokes the same Linux provisioner. It installs Tailscale on Windows (not inside WSL2, to avoid running two Tailscale clients), configures Windows Tailscale Serve to proxy HTTPS to the WSL API, and starts Ubuntu at Windows sign-in. `-Yes` also auto-confirms Linux steps but cannot bypass Windows' reboot, Ubuntu's first-run prompts, sudo authentication, or Tailscale sign-in.

The Linux provisioner installs the API and local Whisper Python dependencies, downloads Whisper small and Ollama's configured `qwen3.5:9b` model, prepares the private PostgreSQL database and user services, applies migrations, configures HTTPS and enrolls a device. In Ubuntu/WSL it installs Python, FFmpeg and Podman; Ollama is installed from its official vendor installer when absent. On Bazzite, install any missing tools (including Python, FFmpeg, Podman, Ollama and Tailscale) using the OS-supported method first; the script will not replace or reset pre-existing service configuration. The model downloads require internet access and several GB of storage. Windows GPU inference requires the PC's supported GPU driver; the model can run on CPU but will be slower. The phone and server each need Tailscale access.

To remove the server services, run `bash chips-backend/deploy/uninstall-pc.sh` on Linux/Bazzite or `.\chips-backend\deploy\uninstall-pc.ps1` on Windows. By default the database volume, `.env`, `.runtime` (including backups and enrollment), and downloaded models remain. Add `--purge-data` (`-PurgeData` in PowerShell) only when you intend to permanently delete the Chips configuration and database. Shared Tailscale/Ollama installations and shared Ollama models are not removed. The Windows uninstaller removes the logon task but leaves WSL/Ubuntu and Tailscale Serve configuration installed.

Backups are private and never pruned automatically. A backup was restored into an isolated temporary database and migration version 6 verified; that temporary database was removed afterward. Same-disk backups protect against some application mistakes, **not disk loss**. Arrange an owner-selected second-disk/encrypted backup destination before treating this as disaster recovery. Monitor free disk space and backup growth. Config/enrollment files are separate from database dumps and should be retained privately too.

## Voice request behavior

Version 1 capabilities and JSON responses now match Android. AAC/MPEG-4, 0.4–60 seconds, at most 8 MiB of audio; a 10 MiB total request-body limit applies before multipart spooling. Audio files are removed after processing. Local decoding is bounded independently of container metadata. Requests have an 80-second server processing deadline; cancellation kills the local inference process group or closes the selected HTTP provider call. Closing a remote HTTP call cannot guarantee that a remote provider stops its internal computation or billing.

Owner + request UUID + audio hash identify a request permanently. Concurrent duplicates join the existing operation; completed results replay after restart without STT/LLM repetition or duplicate voice history. Different audio under a used UUID is rejected. In-progress joins may return 429 after a bounded wait.

A provider failure, cancellation or process crash after claiming a key is terminal for that key. There is no safe way to prove whether a remote provider executed a call whose response was lost. Consequently uncertain work returns 409 and requires **a new recording after fixing the provider**, rather than silently rerunning inference; no-speech returns 422. Completed keys/results and failure identities have no automatic expiration. Local history deletion does not delete server copies; owner-facing remote voice export/deletion remains M5 work.

STT (`STT_PROVIDER`, `STT_MODEL`, `STT_URL`, `STT_API_KEY`) and LLM (`LLM_URL`, `LLM_MODEL`, `LLM_API_KEY`) are independent. API alternatives use compatible transcription/chat-completions protocols. Merely setting an old provider key never selects it. Contract tests exercise explicit API selection; no paid/live external provider was called. Language defaults to automatic detection; set `STT_LANGUAGE` only when a fixed language is desired.

## Readiness and remaining acceptance

| Milestone | Server checkpoint | Still required |
| --- | --- | --- |
| M1 | Existing completed Android milestone reviewed; no server dependency | No new M1 work |
| M2 | Fresh PostgreSQL migration, authentication, isolation, concurrent sync and tombstones verified | New enrollment works for drawing/voice; verify activity sync/recovery on this host and real midnight/timezone behavior |
| M3 | Compatible protocol, real PC STT/LLM, durable retry identity, cancellation and restart recovery verified | Initial owner voice answers succeeded; investigate intermittent failure, then ten representative utterances, locked/unlocked invocation and interruptions; live optional API acceptance |
| M4 | Drawing storage/real vision/deletion, real receipt drafts without stock mutation, pantry and HR archive contracts verified | Owner drawing upload/analysis reached server; assess feedback quality, actual receipts, remaining phone sync, Smart Band/Gadgetbridge/Health Connect/Quest and per-play behavior |

Ten initial synthetic utterances completed in 1.43–3.13 seconds with reasoning disabled; one transcription confused “a count” with “account.” A subsequent low-reasoning language check returned a correct answer in about 8 seconds. Synthetic utterances/images exercise actual models and transport but do not substitute for the owner's speech, receipt/drawing quality or hardware acceptance. The drawing model can make unsupported visual claims; its feedback is a suggestion, not an objective skill measurement. The historical accidental phone uninstall remains recorded in `ANDROID_PROGRESS.md`; do not run connected instrumentation against the owner's phone.

References used for runtime selection: [Whisper CPU int8 and model usage](https://github.com/SYSTRAN/faster-whisper), [Qwen 3.5 vision capability](https://registry.ollama.com/library/qwen3.5), and [Tailscale Serve configuration](https://tailscale.com/docs/reference/tailscale-cli/serve).
