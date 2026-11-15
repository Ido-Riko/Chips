"""Private timestamped PostgreSQL custom-format backup; no automatic pruning."""
import os
from pathlib import Path
from datetime import datetime, timezone
import subprocess

root = Path(__file__).resolve().parents[1]
backups = root / ".runtime/backups"
backups.mkdir(parents=True, exist_ok=True, mode=0o700)
name = datetime.now(timezone.utc).strftime("chips-%Y%m%dT%H%M%SZ.dump")
temporary = backups / (name + ".partial")
try:
    with temporary.open("xb") as output:
        os.chmod(temporary, 0o600)
        subprocess.run(["podman", "exec", "chips-db", "pg_dump", "-U", "chips", "-d", "chips", "-Fc"], stdout=output, check=True)
    temporary.rename(backups / name)
    print(f"Backup created: {backups / name}")
finally:
    temporary.unlink(missing_ok=True)
