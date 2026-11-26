#!/usr/bin/env bash
set -euo pipefail

AUTO_CONFIRM=0
WSL_MODE=0
for option in "$@"; do
    case "$option" in
        --yes|-y) AUTO_CONFIRM=1 ;;
        --wsl) WSL_MODE=1 ;;
        --help|-h)
            cat <<'EOF'
Provision a fresh Chips server on Bazzite/Fedora or Ubuntu (including WSL2).

Usage: deploy/provision-pc.sh [--yes] [--wsl]

Each operation is displayed and confirmed separately. --yes skips confirmations
but still displays every operation. --wsl uses Tailscale on the Windows host
and starts only the Linux services in WSL2.
EOF
            exit 0
            ;;
        *) echo "Unknown option: $option" >&2; exit 2 ;;
    esac
done

if [[ $EUID -eq 0 ]]; then
    echo "Run this script as your normal user, without sudo." >&2
    exit 1
fi

backend_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
cd "$backend_dir"
step_number=0
OLLAMA_INSTALLED_BY_SCRIPT=0

step() {
    local title=$1
    shift
    step_number=$((step_number + 1))
    printf '\n[%02d] %s\n' "$step_number" "$title"
    printf '     Command:'
    printf ' %q' "$@"
    printf '\n'
    if ((AUTO_CONFIRM)); then
        echo "     Auto-confirmed (--yes)."
    else
        local answer
        read -r -p "     Run this step? [y/N] " answer
        case "$answer" in
            y|Y|yes|YES) ;;
            *) echo "Stopped at step $step_number; no later steps were run." >&2; return 1 ;;
        esac
    fi
    "$@"
}

check_prerequisites() {
    local missing=()
    local required=(python3 systemctl loginctl podman ollama curl ffmpeg ffprobe)
    if ((!WSL_MODE)); then
        required+=(tailscale)
    fi
    for command in "${required[@]}"; do
        command -v "$command" >/dev/null 2>&1 || missing+=("$command")
    done
    if ((${#missing[@]})); then
        printf 'Missing required commands: %s\n' "${missing[*]}" >&2
        echo "Install the missing Python, systemd, Podman, FFmpeg or Ollama dependencies, then rerun." >&2
        if ((!WSL_MODE)); then
            echo "Tailscale is also required outside WSL2." >&2
        fi
        echo "For WSL2, rerun the Windows provision-pc.ps1 wrapper to install the Linux prerequisites." >&2
        return 1
    fi
    if ! systemctl --user show-environment >/dev/null 2>&1; then
        echo "No usable systemd user manager. On WSL2, enable systemd and restart the Ubuntu distribution." >&2
        return 1
    fi
    if [[ -e .env && ! -e .runtime/postgres.env ]]; then
        echo "Found .env without .runtime/postgres.env. Inspect this existing setup; refusing to overwrite it." >&2
        return 1
    fi
    if (( ! OLLAMA_INSTALLED_BY_SCRIPT )) &&
        systemctl is-active --quiet ollama.service 2>/dev/null; then
        echo "An existing system Ollama service is active and may conflict with the user service expected by start-pc.sh." >&2
        echo "Preserve it and adapt the service configuration before provisioning." >&2
        return 1
    fi
}

write_ollama_unit() {
    local unit_dir="$HOME/.config/systemd/user"
    local unit="$unit_dir/ollama.service"
    local ollama_path
    ollama_path=$(command -v ollama)
    if [[ -e $unit ]]; then
        echo "Preserving existing $unit"
        return
    fi
    mkdir -p "$unit_dir"
    cat >"$unit" <<EOF
[Unit]
Description=Local Ollama for Chips
After=network-online.target
[Service]
ExecStart=$ollama_path serve
Environment=OLLAMA_HOST=127.0.0.1:11434
Restart=on-failure
RestartSec=5
[Install]
WantedBy=default.target
EOF
    printf 'managed by Chips provisioning\n' >"$unit_dir/chips-ollama-unit.managed"
}

download_whisper() {
    .venv/bin/python - <<'PY'
from pathlib import Path
from huggingface_hub import snapshot_download
from faster_whisper import WhisperModel

root = Path(".runtime/whisper-small").resolve()
root.mkdir(mode=0o700, parents=True, exist_ok=True)
snapshot_download(repo_id="Systran/faster-whisper-small", local_dir=str(root))
WhisperModel(str(root), device="cpu", compute_type="int8")
print(f"Whisper small is ready in {root}")
PY
}

record_enrollment() {
    local owner_id=$1
    local output device_id token
    output=$(.venv/bin/python -m app.scripts.devices enroll --owner "$owner_id")
    printf '%s\n' "$output"
    device_id=$(sed -n 's/^Device: //p' <<<"$output")
    token=$(sed -n 's/^Token (store securely): //p' <<<"$output")
    if [[ -z $device_id || -z $token ]]; then
        echo "Enrollment completed but its output could not be saved; retain the token shown above." >&2
        return 1
    fi
    umask 077
    output=".runtime/phone-enrollment.json"
    OWNER_ID="$owner_id" DEVICE_ID="$device_id" DEVICE_TOKEN="$token" \
        .venv/bin/python - "$output" <<'PY'
import json
import os
import sys
from pathlib import Path

path = Path(sys.argv[1])
path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
path.parent.chmod(0o700)
with path.open("x", encoding="utf-8") as file:
    json.dump({
        "owner_id": os.environ["OWNER_ID"],
        "device_id": os.environ["DEVICE_ID"],
        "token": os.environ["DEVICE_TOKEN"],
    }, file, indent=2)
    file.write("\n")
path.chmod(0o600)
PY
    echo "Enrollment details were also saved privately to $output"
}

if command -v apt-get >/dev/null 2>&1; then
    step "Install Ubuntu/WSL build and runtime prerequisites" \
        sudo apt-get update
    step "Install Python venv, FFmpeg and rootless Podman" \
        sudo apt-get install -y python3-venv ffmpeg podman curl ca-certificates
    # These are installed from their official vendor installers only when absent.
    if ((!WSL_MODE)) && ! command -v tailscale >/dev/null 2>&1; then
        step "Install Tailscale from its official installer" \
            bash -c 'curl -fsSL https://tailscale.com/install.sh | sh'
    fi
    if ! command -v ollama >/dev/null 2>&1; then
        step "Install Ollama from its official installer" \
            bash -c 'curl -fsSL https://ollama.com/install.sh | sh'
        OLLAMA_INSTALLED_BY_SCRIPT=1
    fi
fi

step "Check required commands and protect any existing configuration" check_prerequisites

step "Create the backend virtual environment and install API plus local speech dependencies" \
    bash -c 'python3 -m venv .venv && .venv/bin/python -m pip install --upgrade pip && .venv/bin/pip install -r requirements.txt -r requirements-local-stt.txt'

step "Download the local Whisper small speech model" download_whisper
step "Install Chips database, API and backup user-service files" \
    .venv/bin/python deploy/install-pc.py
step "Install the Ollama user service without replacing an existing service" \
    write_ollama_unit
step "Enable user services after logout and across system restarts" \
    sudo loginctl enable-linger "$USER"
step "Reload user services after installing the service files" \
    systemctl --user daemon-reload
if ((OLLAMA_INSTALLED_BY_SCRIPT)) &&
    { systemctl is-enabled --quiet ollama.service 2>/dev/null || systemctl is-active --quiet ollama.service 2>/dev/null; }; then
    step "Disable the new system Ollama unit so the Chips user service owns its local endpoint" \
        bash -c 'sudo systemctl disable --now ollama.service && (umask 077; touch .runtime/ollama-system-service-disabled-by-chips)'
fi
step "Enable and start Ollama" \
    systemctl --user enable --now ollama.service
step "Download the configured Qwen model (several GB)" ollama pull qwen3.5:9b

step "Wait for PostgreSQL and apply the additive database migrations" \
    bash -c 'for attempt in $(seq 1 60); do podman exec chips-db pg_isready -U chips -d chips >/dev/null 2>&1 && break; sleep 1; done; podman exec chips-db pg_isready -U chips -d chips && .venv/bin/python -m app.scripts.migrate'

step "Enable the API, database and daily backup timer" \
    systemctl --user enable chips-db.service chips-api.service chips-backup.timer
if ((WSL_MODE)); then
    step "Start Chips API and backup timer inside WSL2" \
        systemctl --user start chips-api.service chips-backup.timer
    step "Verify the local API is healthy" \
        bash -c 'for attempt in $(seq 1 30); do curl --fail --silent --max-time 2 http://127.0.0.1:8000/health >/dev/null && exit 0; sleep 1; done; echo "Chips API did not become ready." >&2; exit 1'
else
    step "Start Chips and configure private Tailscale HTTPS" \
        deploy/start-pc.sh
fi

if [[ -e .runtime/phone-enrollment.json ]]; then
    echo "Preserving the existing phone enrollment; no token was rotated."
else
    if [[ ! -e .runtime/owner-id ]]; then
        owner_id=$(python3 -c 'import uuid; print(uuid.uuid4())')
        step "Create a new private owner identity ($owner_id)" \
            bash -c 'umask 077; mkdir -p .runtime; printf "%s\n" "$1" > .runtime/owner-id' _ "$owner_id"
    else
        owner_id=$(<.runtime/owner-id)
    fi
    step "Enroll this server and save the phone token privately" \
        record_enrollment "$owner_id"
fi

if ((WSL_MODE)); then
    cat <<'EOF'

Linux provisioning finished. The Windows wrapper configures the HTTPS URL.
On the phone, enable Tailscale and copy the token from
.runtime/phone-enrollment.json into CHIPS Settings.
EOF
else
    cat <<'EOF'

Provisioning finished. Keep the PC awake and online. On the phone, enable
Tailscale, use the HTTPS service URL printed above, and copy the token from
.runtime/phone-enrollment.json into CHIPS Settings.
EOF
fi
