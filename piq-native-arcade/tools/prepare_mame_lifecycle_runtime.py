"""Stage the reviewed JNI-only MAME lifecycle core, without touching legacy runtimes.

Only a fixed manifest identity is accepted. This command never downloads or
executes a library, and never overwrites an existing generated resource tree.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import stat
import struct
import tempfile

PROJECT = Path(__file__).resolve().parents[1]
LOCK = PROJECT / "native/mame-lifecycle/runtime.json"
PATCH = PROJECT / "native/mame-lifecycle/lifecycle-4fc9a931.patch"
RESOURCE = "core/mame-jni/windows-x64/mame_piq_lifecycle1.dll"
NOTICE = "META-INF/piq-native/mame-lifecycle/runtime.json"
UPSTREAM = "4fc9a9312baaf34963847f884961ad9793fbbc1d"


def no_links(path: Path) -> Path:
    path = path.absolute()
    for part in (path, *path.parents):
        if part.exists() or part.is_symlink():
            value = part.lstat()
            if stat.S_ISLNK(value.st_mode) or getattr(value, "st_file_attributes", 0) & 0x400:
                raise ValueError("Linked input/output is not accepted")
    return path


def sha(path: Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def load_manifest(path: Path, patch: Path) -> dict:
    data = json.loads(no_links(path).read_text(encoding="utf-8"))
    if (data.get("schema") != 1 or data.get("upstreamCommit") != UPSTREAM
            or data.get("resource") != RESOURCE or data.get("platform") != "windows-x64"
            or data.get("savePolicy") != "separate-identity-no-migration"):
        raise ValueError("Unsupported MAME lifecycle manifest")
    if data.get("patchSha256") != sha(no_links(patch)):
        raise ValueError("MAME lifecycle source patch changed; identity review required")
    artifact = data.get("artifact", {})
    if (not re.fullmatch(r"[0-9a-f]{64}", artifact.get("sha256", ""))
            or type(artifact.get("bytes")) is not int
            or not 4096 <= artifact["bytes"] <= 512 * 1024 * 1024):
        raise ValueError("Missing reviewed lifecycle artifact identity; build and verify the core first")
    # The embedded notice is a small allowlist, not a copy of a private build receipt.
    result = {key: data[key] for key in ("schema", "upstreamCommit", "resource", "platform",
                                        "savePolicy", "patchSha256")}
    result["artifact"] = {key: artifact[key] for key in ("bytes", "sha256")}
    return result


def verify_core(path: Path, manifest: dict) -> None:
    path = no_links(path)
    info = path.stat()
    artifact = manifest["artifact"]
    if not stat.S_ISREG(info.st_mode) or info.st_size != artifact["bytes"] or sha(path) != artifact["sha256"]:
        raise ValueError("MAME lifecycle DLL does not match its reviewed identity")
    with path.open("rb") as stream:
        header = stream.read(4096)
    if header[:2] != b"MZ":
        raise ValueError("MAME lifecycle artifact is not a PE image")
    offset = struct.unpack_from("<I", header, 0x3c)[0]
    if (offset > len(header) - 26 or header[offset:offset + 4] != b"PE\0\0"
            or struct.unpack_from("<H", header, offset + 4)[0] != 0x8664
            or not struct.unpack_from("<H", header, offset + 22)[0] & 0x2000
            or struct.unpack_from("<H", header, offset + 24)[0] != 0x20b):
        raise ValueError("MAME lifecycle artifact is not a Windows x64 DLL")


def notice_bytes(manifest: dict) -> bytes:
    return (json.dumps(manifest, sort_keys=True, indent=2) + "\n").encode("utf-8")


def verify_output(output: Path, manifest: dict) -> None:
    no_links(output)
    expected = {RESOURCE, NOTICE}
    actual = set()
    directories = {str(parent).replace("\\", "/") for name in expected
                   for parent in Path(name).parents if str(parent) != "."}
    for path in output.rglob("*"):
        no_links(path)
        name = path.relative_to(output).as_posix()
        if path.is_file():
            actual.add(name)
        elif not path.is_dir() or name not in directories:
            raise ValueError("Unexpected lifecycle resource directory")
    if actual != expected:
        raise ValueError("Missing or unexpected lifecycle resource")
    verify_core(output / RESOURCE, manifest)
    if (output / NOTICE).read_bytes() != notice_bytes(manifest):
        raise ValueError("Lifecycle embedded manifest differs")


def prepare(core: Path, manifest: dict, output: Path) -> dict:
    core, output = no_links(core), no_links(output)
    verify_core(core, manifest)
    if output.exists() and (not output.is_dir() or any(output.iterdir())):
        verify_output(output, manifest)
        return {"reused": True, "artifact": manifest["artifact"]}
    # A fresh or Gradle-created empty target is the only destination we fill.
    output.parent.mkdir(parents=True, exist_ok=True)
    temporary = Path(tempfile.mkdtemp(prefix=".mame-lifecycle-", dir=output.parent))
    try:
        target = temporary / RESOURCE
        target.parent.mkdir(parents=True)
        with core.open("rb") as source, target.open("xb") as destination:
            shutil.copyfileobj(source, destination)
        notice = temporary / NOTICE
        notice.parent.mkdir(parents=True)
        with notice.open("xb") as stream:
            stream.write(notice_bytes(manifest))
        verify_output(temporary, manifest)
        verify_core(core, manifest)
        if output.exists():
            if not output.is_dir() or any(output.iterdir()):
                raise ValueError("Lifecycle output appeared during preparation")
            output.rmdir()  # Empty, exact generated output only; never recursive.
        temporary.rename(output)
        verify_output(output, manifest)
        return {"reused": False, "artifact": manifest["artifact"]}
    finally:
        if temporary.exists():
            if temporary.resolve().parent != output.parent.resolve() or not temporary.name.startswith(".mame-lifecycle-"):
                raise ValueError("Unsafe temporary cleanup target")
            shutil.rmtree(temporary)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--core", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    print(json.dumps(prepare(args.core, load_manifest(LOCK, PATCH), args.output)))


if __name__ == "__main__":
    main()
