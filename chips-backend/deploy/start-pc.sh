#!/usr/bin/env bash
# Start the already-provisioned personal PC deployment. Safe to run repeatedly.
set -euo pipefail
trap 'echo "Chips startup failed. Check the message above; no database or enrollment was reset." >&2' ERR

if [[ $EUID -eq 0 ]]; then
    echo "Run this script as your normal desktop user, without sudo." >&2
    exit 1
fi
for command in systemctl tailscale curl python3; do
    command -v "$command" >/dev/null || { echo "Missing command: $command" >&2; exit 1; }
done

# Only the system VPN daemon needs administrative privileges if it is stopped.
if ! systemctl is-active --quiet tailscaled.service; then
    echo "Starting Tailscale (your PC may ask for your sudo password)..."
    sudo systemctl start tailscaled.service
fi
vpn_state() {
    tailscale status --json | python3 -c 'import json,sys; print(json.load(sys.stdin).get("BackendState", "Unknown"))'
}
if [[ $(vpn_state) != Running ]]; then
    echo "Connecting Tailscale; follow its sign-in instructions if needed..."
    sudo tailscale up --timeout=30s
fi
if [[ $(vpn_state) != Running ]]; then
    echo "Tailscale is not connected. Complete its sign-in and run this script again." >&2
    exit 1
fi

echo "Starting PostgreSQL, Ollama, Chips and the backup timer..."
systemctl --user start chips-db.service ollama.service chips-api.service chips-backup.timer

wait_for_http() {
    local url=$1 label=$2
    for ((attempt=0; attempt<30; attempt++)); do
        if curl --fail --silent --max-time 2 "$url" >/dev/null; then
            return 0
        fi
        sleep 1
    done
    echo "$label did not become ready. Check: journalctl --user -u chips-api -u ollama -u chips-db -n 50" >&2
    return 1
}
wait_for_http http://127.0.0.1:11434/api/tags Ollama
wait_for_http http://127.0.0.1:8000/health Chips

# Preserve all other Serve ports, including ComfyUI on 443. Never enable Funnel.
tailscale serve --bg --https=8443 http://127.0.0.1:8000
chips_dns_name=$(tailscale status --json | python3 -c 'import json,sys; print(json.load(sys.stdin)["Self"]["DNSName"].rstrip("."))')
if [[ -z $chips_dns_name ]]; then
    echo "Tailscale did not provide this PC's DNS name." >&2
    exit 1
fi
wait_for_http "https://${chips_dns_name}:8443/health" "Chips HTTPS"
printf '\nChips is running. Phone service address:\nhttps://%s:8443/api/v1\n' "$chips_dns_name"
echo "Keep this PC awake and Tailscale connected on the phone. You can close this terminal."
echo "Service checks passed; they do not test an actual model answer."
