"""Private scratch audio, including cleanup after an unclean single-worker restart."""
import os
from pathlib import Path
import stat
import tempfile


def directory():
    path = Path(tempfile.gettempdir()) / f"chips-voice-{os.getuid()}"
    path.mkdir(mode=0o700, exist_ok=True)
    info = path.lstat()
    if not stat.S_ISDIR(info.st_mode) or info.st_uid != os.getuid() or info.st_mode & 0o077:
        raise RuntimeError("Voice scratch directory must be private and owned by the service user")
    return path


def cleanup():
    for path in directory().glob("request-*.m4a"):
        if path.lstat().st_uid == os.getuid():
            path.unlink()
