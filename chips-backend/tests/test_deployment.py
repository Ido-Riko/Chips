"""Deployment preparation tests use temporary copies and fake transfer commands."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tarfile

import pytest
from app.scripts.package_release import build_archive

ROOT = Path(__file__).resolve().parents[1]


@pytest.fixture
def source(tmp_path):
    copy = tmp_path / "backend source"
    shutil.copytree(ROOT, copy, ignore=shutil.ignore_patterns(".env", ".venv", "__pycache__", ".pytest_cache", "dist"))
    (copy / ".env").write_text("SECRET=must-not-ship")
    (copy / "app/__pycache__").mkdir(exist_ok=True)
    (copy / "app/__pycache__/secret.pyc").write_text("cached secret")
    return copy


def test_release_contains_migration_and_service_without_secrets_or_caches(source, tmp_path):
    archive = build_archive(source, tmp_path / "release.tar.gz")
    with tarfile.open(archive) as bundle:
        names = bundle.getnames()
        assert "migrations/001_activity_sync.sql" in names
        assert "app/scripts/migrate.py" in names
        assert "app/scripts/devices.py" in names
        assert "chips-api.openrc" in names and "deploy/Caddyfile.example" in names
        assert ".env.example" in names and ".env" not in names
        assert all(not name.startswith("/") and ".." not in Path(name).parts for name in names)
        assert not any("__pycache__" in name or ".venv" in name or name.endswith(".pyc") for name in names)
        assert b"must-not-ship" not in b"".join(bundle.extractfile(member).read() for member in bundle.getmembers())


def test_missing_migration_preserves_previous_archive(source, tmp_path):
    output = tmp_path / "release.tar.gz"
    output.write_bytes(b"previous-release")
    (source / "migrations/001_activity_sync.sql").unlink()
    with pytest.raises(ValueError, match="Required deployment file missing"):
        build_archive(source, output)
    assert output.read_bytes() == b"previous-release"


def test_source_symlink_cannot_escape_release(source, tmp_path):
    secret = tmp_path / "secret.py"
    secret.write_text("secret")
    (source / "app/leak.py").symlink_to(secret)
    with pytest.raises(ValueError, match="symlinks"):
        build_archive(source, tmp_path / "release.tar.gz")


def test_openrc_binds_loopback_and_trusts_only_local_proxy():
    script = '''. "$1"
printf '%s\\n' "$directory" "$command_user" "$command" "$command_args"
'''
    env = os.environ | {"chips_directory": "/srv/chips", "chips_user": "chips"}
    result = subprocess.run(["sh", "-c", script, "test", str(ROOT / "chips-api.openrc")], env=env, text=True, capture_output=True, check=True)
    directory, user, command, arguments = result.stdout.splitlines()
    assert (directory, user, command) == ("/srv/chips", "chips", "/srv/chips/.venv/bin/uvicorn")
    assert "--host 127.0.0.1" in arguments and "0.0.0.0" not in arguments
    assert "--proxy-headers --forwarded-allow-ips 127.0.0.1" in arguments


def test_openrc_refuses_incomplete_installation(tmp_path):
    install = tmp_path / "install"
    env = os.environ | {"chips_directory": str(install), "chips_user": "chips"}
    script = ''' . "$1"
eerror() { printf '%s\\n' "$*" >&2; }
checkpath() { return 0; }
start_pre
'''
    def start():
        return subprocess.run(["sh", "-c", script, "test", str(ROOT / "chips-api.openrc")], env=env, text=True, capture_output=True)
    result = start()
    assert result.returncode != 0 and "Install backend dependencies" in result.stderr
    executable = install / ".venv/bin/uvicorn"
    executable.parent.mkdir(parents=True)
    executable.touch()
    executable.chmod(0o755)
    result = start()
    assert result.returncode != 0 and "migration files are missing" in result.stderr
    migration = install / "migrations/001_activity_sync.sql"
    migration.parent.mkdir()
    migration.touch()
    assert start().returncode == 0


@pytest.fixture
def powershell(source, tmp_path):
    executable = shutil.which("pwsh")
    if executable is None:
        pytest.skip("PowerShell unavailable; CI runs these checks with pwsh installed")
    commands = tmp_path / "commands"
    commands.mkdir()
    transfer = commands / "scp"
    transfer.write_text(f'''#!{sys.executable}
import json, os, sys
from pathlib import Path
Path(os.environ["SCP_RECORD"]).write_text(json.dumps(sys.argv[1:]))
sys.exit(int(os.environ.get("SCP_EXIT", "0")))
''')
    transfer.chmod(0o755)
    record = tmp_path / "transfer.json"
    env = os.environ | {"PATH": str(commands) + os.pathsep + os.environ["PATH"], "SCP_RECORD": str(record)}
    output = tmp_path / "output folder/release.tar.gz"
    def invoke(*arguments, exit_code=0):
        return subprocess.run([executable, "-NoProfile", "-NonInteractive", "-File", str(source / "deploy.ps1"),
                               "-PythonCommand", sys.executable, "-OutputArchive", str(output), *arguments],
                              cwd=tmp_path, env=env | {"SCP_EXIT": str(exit_code)}, text=True, capture_output=True, timeout=30)
    return invoke, output, record


def test_powershell_defaults_to_offline_and_works_outside_source_directory(powershell):
    invoke, output, record = powershell
    result = invoke()
    assert result.returncode == 0, result.stderr
    assert output.is_file() and not record.exists()
    assert "Offline package ready" in result.stdout


def test_powershell_upload_failure_cannot_report_success(powershell):
    invoke, output, record = powershell
    result = invoke("-Upload", "-ServerIP", "example.invalid", exit_code=23)
    assert result.returncode != 0
    assert "Upload failed" in result.stderr
    assert "Archive uploaded" not in result.stdout
    assert json.loads(record.read_text()) == ["-O", "--", str(output), "idori@example.invalid:/home/idori/chips-backend/"]


def test_powershell_packaging_failure_never_attempts_upload(source, powershell):
    invoke, _, record = powershell
    (source / "migrations/001_activity_sync.sql").unlink()
    result = invoke("-Upload", "-ServerIP", "example.invalid")
    assert result.returncode != 0 and not record.exists()
    assert "Archive uploaded" not in result.stdout


def test_powershell_rejects_unsafe_remote_arguments(powershell):
    invoke, output, record = powershell
    result = invoke("-Upload", "-ServerIP", "host;invalid")
    assert result.returncode != 0
    assert not output.exists() and not record.exists()
