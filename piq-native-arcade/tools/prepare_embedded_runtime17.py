"""Verify runtime37, then stage only the six Native17 payloads as opaque resources.

No network, native loading, helper execution, source-resource edits or installation.
The CLI profile is deliberately fixed. --plan performs all input checks without writes.
"""
from __future__ import annotations

import argparse
from dataclasses import dataclass
import hashlib
import json
from pathlib import Path, PurePosixPath
import shutil
import stat
import tempfile
import zipfile


PREFIX = "native-runtime/win-x64-v1/"
LICENSE_PREFIX = "META-INF/licenses/native-runtime17/"
CHUNK = 1024 * 1024
PROJECT = Path(__file__).resolve().parents[1]
DEFAULT_ARCHIVE = PROJECT.parent / "piq-fc-arcade/build/runtime-pack37-v1/piq-runtime-pack-v1.zip"
DEFAULT_OUTPUT = PROJECT / "build/generated/embeddedRuntime17"


class VerificationError(RuntimeError):
    pass


@dataclass(frozen=True)
class Artifact:
    path: str
    size: int
    sha256: str
    embedded: bool = True


@dataclass(frozen=True)
class Profile:
    archive_size: int
    archive_sha256: str
    artifacts: tuple[Artifact, ...]
    licenses: tuple[Artifact, ...]


PROFILE = Profile(127026327,
    "681FDAB15CCF7BD3739B74598D1724E637415E6EB60C505DEEF0EFDAED0617AA", (
    Artifact("piq-native-arcade/runtime/mame_libretro.dll", 372431360,
             "6172A988AB67FE68F4177A6FC8FBB82619EB2044C330930F0F572F7B1EDC2301"),
    Artifact("piq-native-arcade/runtime/piq-native-helper.jar", 18571,
             "20F6F3028D76DAEB01212D1808BE90E35BFB5429D1E06153B7D8B32DD73E943C"),
    Artifact("piq-native-arcade/runtime/jna-5.14.0.jar", 1878533,
             "34ED1E1F27FA896BCA50DBC4E99CF3732967CEC387A7A0D5E3486C09673FE8C6"),
    Artifact("piq-native-arcade/runtime-snapshot-v1/piqneogeo_libretro.dll", 56494080,
             "E8F435903332AC80468769604779295A6965046DC25D4706A583D14D91C35201"),
    Artifact("piq-native-arcade/runtime-snapshot-v1/piq-snapshot-helper.jar", 43610,
             "F175B7CB60B37A95E1F5B2ED5FB48CE066FC1B6AE1ECD8089B8E6F957EF76C97"),
    Artifact("piq-native-arcade/runtime-snapshot-v1/jna-5.14.0.jar", 1878533,
             "34ED1E1F27FA896BCA50DBC4E99CF3732967CEC387A7A0D5E3486C09673FE8C6"),
    Artifact("piq-gba/runtime/mgba_libretro.dll", 2955998,
             "D1BA96BC1AF23997D5C8003A6F6F8BE7ACBA9D770D4D42D14557AAEB469FA16B", False),
    Artifact("piq-gba/runtime/piq-gba-helper.jar", 20182,
             "AF687B20AFD470992F9C02C80173356E20E9D9E98FABDDCF3800979F11A4B28C", False),
    Artifact("piq-gba/runtime/jna-5.14.0.jar", 1878533,
             "34ED1E1F27FA896BCA50DBC4E99CF3732967CEC387A7A0D5E3486C09673FE8C6", False),
), (
    Artifact("MAME-COPYING.txt", 11549,
             "60E9B2E1E0832D74696A9A0317693BB6CBE317747CAAD589A338E2C7636933E4"),
    Artifact("MAME-GPL-2.0.txt", 17958,
             "BA20C40872287B52EECA54D48E875879B33D841B98F8CEDBA3558B405467D5DE"),
    Artifact("JNA-LICENSE.txt", 788,
             "521BB271AC56E0E29A1B1B688B94AF17D00D378FC8E63478D8C8B2A7C4A229D0"),
    Artifact("JNA-Apache-2.0.txt", 10174,
             "0D542E0C8804E39AA7F37EB00DA5A762149DC682D7829451287E11B938E94594"),
))

NOTICE = """Game Console: Arcade alpha.17 — embedded runtime provenance

The six opaque Windows x64 runtime files are copied byte-for-byte from the fixed
piq-runtime-pack-v1.zip (runtime37). See manifest.json in
native-runtime/win-x64-v1/ for archive and per-file SHA-256 values.
The original archive is read-only; its three GBA entries are not embedded.

MAME/libretro, the experimental Neo Geo snapshot core, JNA and their upstream
authors retain their copyrights. The four adjacent license texts are unmodified.
MAME upstream source reference:
https://github.com/libretro/mame/tree/4fc9a9312baaf34963847f884961ad9793fbbc1d
JNA 5.14.0: https://github.com/java-native-access/jna/tree/5.14.0
JNA is dual-licensed; this distribution selects the Apache-2.0 option.

These files are not Java mod dependencies. Helpers and JNA remain nested opaque
resources for a separate helper process; they must not enter the mod classloader.
No commercial game ROMs, user saves or GBA runtime are supplied by this payload.
Runtime binaries have not been rebuilt by this packaging step. Source-to-binary
correspondence, complete transitive corresponding source and public redistribution
clearance remain separate review items; including these texts is not certification
that every licensing obligation has been satisfied.
""".encode("utf-8")


def digest_stream(stream, sink=None) -> tuple[int, str]:
    digest = hashlib.sha256()
    size = 0
    while chunk := stream.read(CHUNK):
        size += len(chunk)
        digest.update(chunk)
        if sink is not None:
            sink.write(chunk)
    return size, digest.hexdigest().upper()


def file_identity(path: Path) -> tuple[int, str]:
    if path.is_symlink() or not path.is_file():
        raise VerificationError(f"Missing or unsafe input file: {path}")
    with path.open("rb") as stream:
        return digest_stream(stream)


def require_identity(actual: tuple[int, str], size: int, sha256: str, label: str):
    if actual != (size, sha256.upper()):
        raise VerificationError(f"Identity mismatch: {label}: actual={actual}")


def safe_relative(name: str):
    path = PurePosixPath(name)
    if (not name or "\\" in name or ":" in name or path.is_absolute()
            or any(part in ("", ".", "..") for part in name.split("/"))
            or str(path) != name):
        raise VerificationError(f"Unsafe resource path: {name!r}")


def inspect_zip(archive: Path, profile: Profile):
    require_identity(file_identity(archive), profile.archive_size,
                     profile.archive_sha256, "source archive")
    with zipfile.ZipFile(archive) as source:
        entries = source.infolist()
        names = [entry.filename for entry in entries]
        if len(names) != len(set(names)):
            raise VerificationError("Duplicate archive entries")
        if set(names) != {artifact.path for artifact in profile.artifacts}:
            raise VerificationError("Archive entry set differs from the fixed nine-entry profile")
        for entry in entries:
            safe_relative(entry.filename)
            mode = entry.external_attr >> 16
            if entry.is_dir() or stat.S_ISLNK(mode) or entry.flag_bits & 1:
                raise VerificationError(f"Unsupported archive entry: {entry.filename}")
        for artifact in profile.artifacts:
            entry = source.getinfo(artifact.path)
            if entry.file_size != artifact.size:
                raise VerificationError(f"Uncompressed size mismatch: {artifact.path}")
            with source.open(entry) as stream:
                require_identity(digest_stream(stream), artifact.size, artifact.sha256, artifact.path)


def inspect_licenses(license_root: Path, profile: Profile):
    for artifact in profile.licenses:
        safe_relative(artifact.path)
        require_identity(file_identity(license_root / artifact.path),
                         artifact.size, artifact.sha256, artifact.path)


def manifest_bytes(profile: Profile) -> bytes:
    def record(artifact: Artifact, prefix: str = ""):
        return {"relativePath": artifact.path, "resourcePath": prefix + artifact.path,
                "size": artifact.size, "sha256": artifact.sha256.upper()}
    manifest = {
        "schema": 1, "modId": "piq_native_arcade", "modVersion": "0.1.0-alpha.17",
        "sourceArchive": {"label": "runtime37/piq-runtime-pack-v1.zip",
                          "size": profile.archive_size, "sha256": profile.archive_sha256.upper()},
        "artifacts": [record(item, PREFIX) for item in profile.artifacts if item.embedded],
        "excludedArtifacts": [record(item) for item in profile.artifacts if not item.embedded],
        "licenses": [record(item, LICENSE_PREFIX) for item in profile.licenses],
        "noticeResource": LICENSE_PREFIX + "NOTICE.md",
        "runtimeUse": "Opaque resources for an external helper process, not mod dependencies.",
    }
    return (json.dumps(manifest, ensure_ascii=False, indent=2) + "\n").encode("utf-8")


def expected_output(profile: Profile) -> dict[str, tuple[int, str]]:
    expected = {PREFIX + item.path: (item.size, item.sha256.upper())
                for item in profile.artifacts if item.embedded}
    expected.update({LICENSE_PREFIX + item.path: (item.size, item.sha256.upper())
                     for item in profile.licenses})
    for name, content in ((PREFIX + "manifest.json", manifest_bytes(profile)),
                          (LICENSE_PREFIX + "NOTICE.md", NOTICE)):
        expected[name] = (len(content), hashlib.sha256(content).hexdigest().upper())
    return expected


def reject_link_chain(path: Path):
    for current in (path, *path.parents):
        if current.is_symlink() or (hasattr(current, "is_junction") and current.is_junction()):
            raise VerificationError(f"Symlink/junction path is not allowed: {current}")


def verify_output(output: Path, profile: Profile):
    reject_link_chain(output)
    if not output.is_dir():
        raise VerificationError(f"Generated output is not a directory: {output}")
    expected = expected_output(profile)
    expected_directories = {str(parent) for name in expected
                            for parent in PurePosixPath(name).parents if str(parent) != "."}
    actual = set()
    for path in output.rglob("*"):
        reject_link_chain(path)
        if path.is_file():
            name = path.relative_to(output).as_posix()
            actual.add(name)
            if name not in expected:
                raise VerificationError(f"Unexpected generated resource: {name}")
            require_identity(file_identity(path), *expected[name], name)
        elif path.is_dir():
            if path.relative_to(output).as_posix() not in expected_directories:
                raise VerificationError(f"Unexpected generated directory: {path}")
        else:
            raise VerificationError(f"Unexpected generated node: {path}")
    if actual != set(expected):
        raise VerificationError("Generated resource set is incomplete")


def plan(archive: Path, license_root: Path, profile: Profile = PROFILE) -> dict:
    inspect_zip(archive, profile)
    inspect_licenses(license_root, profile)
    require_identity(file_identity(archive), profile.archive_size,
                     profile.archive_sha256, "source archive after inspection")
    return {"ok": True, "operation": "plan", "readOnly": True,
            "sourceArchive": json.loads(manifest_bytes(profile))["sourceArchive"],
            "archiveEntries": len(profile.artifacts),
            "embeddedPayloads": sum(item.embedded for item in profile.artifacts),
            "excludedPayloads": sum(not item.embedded for item in profile.artifacts),
            "embeddedPayloadBytes": sum(item.size for item in profile.artifacts if item.embedded),
            "outputEntries": [{"resourcePath": name, "size": identity[0], "sha256": identity[1]}
                              for name, identity in sorted(expected_output(profile).items())]}


def prepare(archive: Path, license_root: Path, output: Path, profile: Profile = PROFILE) -> dict:
    report = plan(archive, license_root, profile)
    reject_link_chain(output)
    # Gradle creates declared output directories before Exec. Its exact empty child
    # is safe to replace non-recursively; any existing content must match in full.
    if output.exists():
        if output.is_dir() and next(output.iterdir(), None) is None:
            output.rmdir()  # Atomic refusal if another process has added any content.
        else:
            verify_output(output, profile)
            return dict(report, operation="prepare", readOnly=False, reused=True)
    output.parent.mkdir(parents=True, exist_ok=True)
    temporary = Path(tempfile.mkdtemp(prefix=".embeddedRuntime17-", dir=output.parent))
    try:
        with zipfile.ZipFile(archive) as source:
            for artifact in profile.artifacts:
                if not artifact.embedded:
                    continue
                target = temporary / (PREFIX + artifact.path)
                target.parent.mkdir(parents=True, exist_ok=True)
                with source.open(artifact.path) as stream, target.open("xb") as sink:
                    require_identity(digest_stream(stream, sink), artifact.size,
                                     artifact.sha256, artifact.path)
        for artifact in profile.licenses:
            target = temporary / (LICENSE_PREFIX + artifact.path)
            target.parent.mkdir(parents=True, exist_ok=True)
            with (license_root / artifact.path).open("rb") as stream, target.open("xb") as sink:
                require_identity(digest_stream(stream, sink), artifact.size,
                                 artifact.sha256, artifact.path)
        for name, content in ((PREFIX + "manifest.json", manifest_bytes(profile)),
                              (LICENSE_PREFIX + "NOTICE.md", NOTICE)):
            target = temporary / name
            target.parent.mkdir(parents=True, exist_ok=True)
            with target.open("xb") as sink:
                sink.write(content)
        verify_output(temporary, profile)
        inspect_licenses(license_root, profile)
        require_identity(file_identity(archive), profile.archive_size,
                         profile.archive_sha256, "source archive after extraction")
        if output.exists():
            raise VerificationError("Output appeared while preparing; refusing overwrite")
        temporary.rename(output)
        verify_output(output, profile)
    finally:
        # Only this invocation's exclusive, validated staging child may be removed.
        if temporary.exists():
            reject_link_chain(temporary)
            if temporary.parent.resolve() != output.parent.resolve() or not temporary.name.startswith(".embeddedRuntime17-"):
                raise VerificationError("Refusing unsafe staging cleanup")
            shutil.rmtree(temporary)
    return dict(report, operation="prepare", readOnly=False, reused=False)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    action = parser.add_mutually_exclusive_group(required=True)
    action.add_argument("--plan", action="store_true", help="Verify inputs without creating resources")
    action.add_argument("--prepare", action="store_true", help="Verify and stage the fixed resource tree")
    parser.add_argument("--archive", type=Path, default=DEFAULT_ARCHIVE)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    args = parser.parse_args()
    try:
        # Public CLI cannot write outside this module's owned generated directory.
        reject_link_chain(args.output.absolute())
        if args.output.resolve() != DEFAULT_OUTPUT.resolve():
            raise VerificationError("--output must be this project's build/generated/embeddedRuntime17")
        if args.plan:
            report = plan(args.archive, PROJECT / "docs/licenses")
        else:
            report = prepare(args.archive, PROJECT / "docs/licenses", args.output)
        print(json.dumps(report, ensure_ascii=False, indent=2))
        return 0
    except (VerificationError, OSError, ValueError, zipfile.BadZipFile) as error:
        print(json.dumps({"ok": False, "error": str(error)}, ensure_ascii=False))
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
