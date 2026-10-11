"""Resolve locally retained historical evidence; never download or publish it."""
from pathlib import Path, PurePosixPath
import os
import stat


ENVIRONMENT_VARIABLE = "PIQ_PRIVATE_EVIDENCE_ROOT"


def private_evidence(relative_path):
    """Return an existing private file under an explicitly configured evidence root.

    Keep the original repository-relative directory layout in that root. These
    files are historical test evidence, not public source or release assets.
    No fallback to a tracked file or generated replacement is permitted.
    """
    text = str(relative_path)
    relative = PurePosixPath(text)
    if (not text or relative.is_absolute() or "\\" in text or ":" in text
            or any(part in ("", ".", "..") for part in text.split("/"))):
        raise ValueError("Private evidence requires a normalized repository-relative path")
    configured = os.environ.get(ENVIRONMENT_VARIABLE)
    if not configured:
        raise RuntimeError(
            f"Historical private evidence is required: set {ENVIRONMENT_VARIABLE} "
            f"to its local root (preserve repository-relative paths); required file: {text}")
    root = Path(configured).expanduser()
    if not root.is_absolute():
        raise ValueError(f"{ENVIRONMENT_VARIABLE} must be an absolute directory")
    candidate = root.joinpath(*relative.parts)
    for parent in (candidate, *candidate.parents):
        try:
            attributes = parent.lstat()
        except FileNotFoundError:
            continue
        if stat.S_ISLNK(attributes.st_mode) or getattr(attributes, "st_file_attributes", 0) & 0x400:
            raise ValueError("Private evidence cannot use symbolic links or reparse points")
    if not candidate.is_file():
        raise FileNotFoundError(
            f"Historical private evidence is missing under {ENVIRONMENT_VARIABLE}: {text}; "
            "restore the original local evidence; do not bypass its integrity checks")
    return candidate
