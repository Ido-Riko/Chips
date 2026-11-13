"""Build a backend source archive without credentials, caches, or network access."""
import argparse
import gzip
import os
from pathlib import Path
import tarfile
import tempfile


REQUIRED_FILES = (
    "app/main.py",
    "migrations/001_activity_sync.sql",
    "migrations/002_beat_saber.sql",
    "requirements.txt",
    "chips-api.openrc",
    "deploy/Caddyfile.example",
    "deploy/chips-api.confd",
    ".env.example",
    "DEPLOYMENT.md",
)


def build_archive(source: Path, destination: Path) -> Path:
    source = source.resolve()
    destination = destination.resolve()
    if not destination.name.endswith(".tar.gz"):
        raise ValueError("Output must end in .tar.gz")
    for name in REQUIRED_FILES:
        if not (source / name).is_file():
            raise ValueError(f"Required deployment file missing: {name}")
    # Explicit source types exclude .env, local databases, venvs and arbitrary app data.
    paths = set(REQUIRED_FILES)
    for directory, pattern in (("app", "*.py"), ("migrations", "*.sql")):
        for path in (source / directory).rglob("*"):
            if path.is_symlink():
                raise ValueError("Deployment sources must not contain symlinks")
            if path.is_file() and path.match(pattern) and "__pycache__" not in path.parts:
                paths.add(path.relative_to(source).as_posix())
    for name in paths:
        path = source / name
        if any(parent.is_symlink() for parent in (path, *path.parents) if parent != source.parent):
            raise ValueError("Deployment sources must not contain symlinks")
    destination.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary = tempfile.mkstemp(dir=destination.parent, suffix=".tar.gz")
    try:
        with os.fdopen(descriptor, "wb") as output:
            with gzip.GzipFile(fileobj=output, mode="wb", mtime=0) as compressed:
                with tarfile.open(fileobj=compressed, mode="w") as archive:
                    for name in sorted(paths):
                        path = source / name
                        entry = archive.gettarinfo(str(path), arcname=name)
                        entry.uid = entry.gid = entry.mtime = 0
                        entry.uname = entry.gname = ""
                        entry.mode = 0o755 if name == "chips-api.openrc" else 0o644
                        with path.open("rb") as content:
                            archive.addfile(entry, content)
        os.replace(temporary, destination)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)
    return destination


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        archive = build_archive(Path(__file__).resolve().parents[2], args.output)
    except (ValueError, OSError) as error:
        parser.exit(1, f"Packaging failed: {error}\n")
    print(f"Created {archive}")


if __name__ == "__main__":
    main()
