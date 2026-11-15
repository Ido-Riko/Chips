"""Install this checkout's user services. Explicit operator command, no sudo."""
from pathlib import Path
import os
import secrets
import subprocess

os.umask(0o077)
root = Path(__file__).resolve().parents[1]
state = root / ".runtime"
state.mkdir(mode=0o700, exist_ok=True)
os.chmod(state, 0o700)
env = root / ".env"
pg_env = state / "postgres.env"
if not env.exists():
    password = secrets.token_urlsafe(32)
    pg_env.write_text(f"POSTGRES_USER=chips\nPOSTGRES_DB=chips\nPOSTGRES_PASSWORD={password}\n")
    env.write_text(f"DATABASE_URL=postgresql+asyncpg://chips:{password}@127.0.0.1:55432/chips\n"
                   "REDIS_URL=redis://127.0.0.1:6379/0\nALLOW_INSECURE_LOCAL=false\n"
                   f"STT_PROVIDER=local\nSTT_MODEL={state}/whisper-small\n"
                   "LLM_URL=http://127.0.0.1:11434/v1/chat/completions\nLLM_MODEL=qwen3.5:9b\n"
                   "LLM_REASONING_EFFORT=low\nLLM_MAX_TOKENS=2400\nDRAWING_REASONING_EFFORT=none\n"
                   "DRAWING_VISION_URL=http://127.0.0.1:11434/v1/chat/completions\nDRAWING_VISION_MODEL=qwen3.5:9b\n")
    os.chmod(env, 0o600)
    os.chmod(pg_env, 0o600)
if not pg_env.exists():
    raise SystemExit("Existing environment found without postgres.env; inspect before provisioning")
units = Path.home() / ".config/systemd/user"
units.mkdir(parents=True, exist_ok=True)
quadlets = Path.home() / ".config/containers/systemd"
quadlets.mkdir(parents=True, exist_ok=True)
(quadlets / "chips-db.container").write_text(f"""[Unit]
Description=Chips private PostgreSQL
[Container]
Image=docker.io/library/postgres:17
ContainerName=chips-db
EnvironmentFile={pg_env}
PublishPort=127.0.0.1:55432:5432
Volume=chips-pgdata:/var/lib/postgresql/data
[Service]
Restart=always
[Install]
WantedBy=default.target
""")
(units / "chips-api.service").write_text(f"""[Unit]
Description=Chips personal API
Requires=chips-db.service
After=chips-db.service network-online.target
[Service]
WorkingDirectory={root}
ExecStart={root}/.venv/bin/uvicorn app.main:app --host 127.0.0.1 --port 8000 --workers 1 --proxy-headers --forwarded-allow-ips 127.0.0.1 --no-access-log
Restart=on-failure
RestartSec=5
TimeoutStopSec=15
UMask=0077
NoNewPrivileges=true
[Install]
WantedBy=default.target
""")
(units / "chips-backup.service").write_text(f"""[Unit]
Description=Private Chips database backup
Requires=chips-db.service
After=chips-db.service
[Service]
Type=oneshot
ExecStart={root}/.venv/bin/python {root}/deploy/backup-pc.py
UMask=0077
""")
(units / "chips-backup.timer").write_text((root / "deploy/chips-backup.timer").read_text())
subprocess.run(["systemctl", "--user", "daemon-reload"], check=True)
subprocess.run(["systemctl", "--user", "start", "chips-db"], check=True)
print("Database service installed; run migrations explicitly before starting chips-api.")
