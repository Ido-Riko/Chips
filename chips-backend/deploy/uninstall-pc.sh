#!/usr/bin/env bash
set -euo pipefail

AUTO_CONFIRM=0
PURGE_DATA=0
for option in "$@"; do
    case "$option" in
        --yes|-y) AUTO_CONFIRM=1 ;;
        --purge-data) PURGE_DATA=1 ;;
        --help|-h)
            cat <<'EOF'
Remove Chips user services while keeping data by default.

Usage: deploy/uninstall-pc.sh [--yes] [--purge-data]

--yes          Skip confirmations but display every operation.
--purge-data   Also remove .env, .runtime and the chips-pgdata Podman volume.
               This is destructive and remains a separate explicit option.
EOF
            exit 0
            ;;
        *) echo "Unknown option: $option" >&2; exit 2 ;;
    esac
done

if [[ $EUID -eq 0 ]]; then
    echo "Run this script as the normal service user, without sudo." >&2
    exit 1
fi

backend_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
cd "$backend_dir"
step_number=0

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
            *) echo "Stopped; no later steps were run." >&2; return 1 ;;
        esac
    fi
    "$@"
}

remove_units() {
    for unit in chips-api.service chips-db.service chips-backup.timer; do
        if systemctl --user cat "$unit" >/dev/null 2>&1 ||
            systemctl --user is-active --quiet "$unit"; then
            systemctl --user disable --now "$unit"
        fi
    done
    rm -f "$HOME/.config/systemd/user/chips-api.service" \
        "$HOME/.config/systemd/user/chips-backup.service" \
        "$HOME/.config/systemd/user/chips-backup.timer" \
        "$HOME/.config/systemd/user/chips-db.service" \
        "$HOME/.config/containers/systemd/chips-db.container"
    if [[ -f "$HOME/.config/systemd/user/chips-ollama-unit.managed" ]]; then
        systemctl --user disable --now ollama.service
        rm -f "$HOME/.config/systemd/user/ollama.service" \
            "$HOME/.config/systemd/user/chips-ollama-unit.managed"
    else
        echo "Preserving Ollama; its user service was not created by Chips provisioning."
    fi
    systemctl --user daemon-reload
    echo "Removed Chips user units. Shared Ollama/Tailscale installations and settings were left untouched."
}

remove_virtualenv() {
    if [[ -d .venv ]]; then
        rm -rf -- .venv
    else
        echo "No backend virtual environment found."
    fi
}

remove_database_volume() {
    if command -v podman >/dev/null 2>&1 && podman volume exists chips-pgdata; then
        podman volume rm chips-pgdata
    else
        echo "No chips-pgdata Podman volume found."
    fi
}

remove_configuration() {
    if [[ -f .env ]]; then rm -f -- .env; else echo "No .env file found."; fi
}

remove_runtime_data() {
    if [[ -d .runtime ]]; then rm -rf -- .runtime; else echo "No .runtime directory found."; fi
}

restore_system_ollama() {
    sudo systemctl enable --now ollama.service
    rm -f .runtime/ollama-system-service-disabled-by-chips
}

step "Stop and remove Chips user services (preserving the database and configuration)" remove_units
if [[ -f .runtime/ollama-system-service-disabled-by-chips ]]; then
    step "Restore the system Ollama service disabled by Chips provisioning" restore_system_ollama
fi
step "Remove the backend virtual environment" remove_virtualenv
if ((PURGE_DATA)); then
    echo
    echo "DESTRUCTIVE OPTION ENABLED: --purge-data removes .env, .runtime and Podman volume chips-pgdata."
    step "Permanently delete the Chips PostgreSQL volume" remove_database_volume
    step "Delete the Chips private environment file" remove_configuration
    step "Delete enrollment, backups, Whisper model and other local runtime data" remove_runtime_data
else
    echo "Preserved .env, .runtime, database volume and downloaded models. Shared Ollama models are never removed."
fi
