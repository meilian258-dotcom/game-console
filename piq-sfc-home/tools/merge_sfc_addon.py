"""Merge SFC home with the immutable SFC6 core; never install or launch it.

Only NeoForge metadata and the manifest are synthesized. Every other class,
asset, access transformer and core binary must retain its original bytes.
"""
from __future__ import annotations

import argparse
from dataclasses import dataclass
import hashlib
import io
import json
import os
from pathlib import Path
import re
import stat
import tempfile
import tomllib
import zipfile

ROOT = Path(__file__).resolve().parents[2]
FROZEN_CORE = ROOT / "制作Mod/03-街机模拟/PIQ-SFC街机/piq_sfc_arcade-0.2.0-alpha.6.jar"
FROZEN_SHA = "38FA46C5D283EAD9E1F6666D01398F495E2E1A3260A1517BE8EE959710963363"
META = "META-INF/neoforge.mods.toml"
MANIFEST = "META-INF/MANIFEST.MF"
AT = "META-INF/accesstransformer.cfg"
WASM = "assets/piq_sfc_arcade/core/piq_sfc_wasm.wasm"
SYNTHESIZED = frozenset({META, MANIFEST})
MAX_ARCHIVE = 64 * 1024 * 1024
MAX_ENTRIES = 4096
DATE = (2026, 9, 10, 0, 0, 0)
VERSION = re.compile(r"[0-9]+\.[0-9]+\.[0-9]+(?:-[A-Za-z0-9.-]+)?\Z")


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ValueError(message)


def sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest().upper()


def safe_path(path: Path, *, existing: bool) -> Path:
    path = Path(os.path.abspath(path))
    for part in (path, *path.parents):
        try:
            mode = part.lstat()
        except FileNotFoundError:
            continue
        require(not stat.S_ISLNK(mode.st_mode), f"Symlink path rejected: {part}")
        require(not (getattr(mode, "st_file_attributes", 0) & 0x400), f"Reparse path rejected: {part}")
    if existing:
        require(path.is_file(), f"Input is not a regular file: {path}")
    return path


def read_archive(path: Path) -> tuple[str, dict[str, bytes]]:
    path = safe_path(path, existing=True)
    require(path.stat().st_size <= MAX_ARCHIVE, "Archive exceeds 64 MiB")
    raw = path.read_bytes()
    # The hash and ZIP parser see exactly the same immutable bytes.
    entries: dict[str, bytes] = {}
    names: set[str] = set()
    with zipfile.ZipFile(io.BytesIO(raw)) as archive:
        require(len(archive.infolist()) <= MAX_ENTRIES, "Too many archive entries")
        total = 0
        for info in archive.infolist():
            # ZipInfo normalizes Windows separators/truncates NULs when reading.
            # Validate the original ZIP name before accepting its canonical form.
            name = info.orig_filename
            require(name and not name.startswith("/") and "\\" not in name and ":" not in name
                    and not any(ord(c) < 32 for c in name) and name == info.filename
                    and not any(x in (".", "..", "") for x in name.rstrip("/").split("/")), "Unsafe ZIP path")
            require(name not in names, f"Duplicate ZIP entry: {name}")
            names.add(name)
            require(not info.flag_bits & 1, "Encrypted ZIP entry")
            require(not stat.S_ISLNK(info.external_attr >> 16), "ZIP symlink entry")
            require(not re.match(r"META-INF/[^/]+\.(SF|RSA|DSA|EC)$", name, re.I), "Signed input rejected")
            total += info.file_size
            require(total <= MAX_ARCHIVE, "Expanded archive exceeds 64 MiB")
            if not info.is_dir():
                entries[name] = archive.read(info)
        require(archive.testzip() is None, "ZIP CRC mismatch")
    require(META in entries and MANIFEST in entries, "Missing NeoForge metadata/manifest")
    return sha(raw), entries


def parsed_metadata(data: bytes, expected_id: str) -> dict:
    metadata = tomllib.loads(data.decode("utf-8"))
    require(set(metadata) == {"modLoader", "loaderVersion", "license", "mods", "dependencies"},
            "Unexpected metadata structure; refusing to discard fields")
    require(metadata["modLoader"] == "javafml" and metadata["loaderVersion"] == "[4,)", "Incompatible JavaFML loader")
    require(metadata["license"] == "GPL-3.0-or-later", "Unexpected license")
    require(len(metadata["mods"]) == 1 and metadata["mods"][0]["modId"] == expected_id, "Unexpected input mod ownership")
    require(set(metadata["dependencies"]) == {expected_id}, "Unexpected dependency owner")
    return metadata


def combined_metadata(core: dict[str, bytes], home: dict[str, bytes], version: str) -> bytes:
    require(VERSION.fullmatch(version) is not None, "Invalid release version")
    c = parsed_metadata(core[META], "piq_sfc_arcade")
    h = parsed_metadata(home[META], "piq_sfc_home")
    require(c["mods"][0]["version"] == "0.2.0-alpha.6", "Core version is not frozen SFC6")
    require(h["mods"][0]["version"] == version, "Home version does not match delivery version")
    def body(data: bytes) -> str:
        text = data.decode("utf-8").replace("\r\n", "\n")
        require(text.count("[[mods]]") == 1, "Ambiguous mod table")
        return text[text.index("[[mods]]"):].rstrip() + "\n"
    data = ('modLoader="javafml"\nloaderVersion="[4,)"\nlicense="GPL-3.0-or-later"\n\n'
            + body(core[META]) + "\n" + body(home[META])).encode("utf-8")
    expected = dict(c)
    expected["mods"] = c["mods"] + h["mods"]
    expected["dependencies"] = c["dependencies"] | h["dependencies"]
    require(tomllib.loads(data.decode("utf-8")) == expected, "Combined metadata changed semantics")
    deps = {d["modId"]: d for d in h["dependencies"]["piq_sfc_home"]}
    require(len(deps) == len(h["dependencies"]["piq_sfc_home"]), "Duplicate dependency")
    require(set(deps) == {"neoforge", "minecraft", "piq_fc_arcade", "piq_sfc_arcade"},
            "Expected only FC main mod, bundled core, Minecraft and NeoForge dependencies")
    require(deps["piq_fc_arcade"]["versionRange"] == "[0.31.0-alpha.19,0.32.0)", "Expected FC19 dependency")
    require(deps["piq_sfc_arcade"]["versionRange"] == "[0.2.0-alpha.6,0.3.0)", "Bundled core dependency changed")
    require(all(d["type"] == "required" and d["side"] == "BOTH" for d in deps.values()), "Dependency side/ownership loosened")
    return data


def ownership(entries: dict[str, bytes], namespace: str) -> None:
    for name in entries:
        if name.endswith(".class"):
            require(name.startswith(f"cn/piq/{namespace}/"), f"Foreign/duplicate class owner: {name}")
        else:
            mod_id = "piq_sfc_arcade" if namespace == "sfcarcade" else "piq_sfc_home"
            known = {META, MANIFEST, "META-INF/LICENSE"}
            if namespace == "sfcarcade":
                known |= {AT, "pack.mcmeta", "META-INF/LICENSE-piq-sfc-arcade.txt"}
            require(name in known or name.startswith((f"assets/{mod_id}/", f"data/{mod_id}/")),
                    f"Foreign resource owner: {name}")
            require(not name.lower().endswith((".zip", ".7z", ".nes", ".sfc", ".smc", ".bin", ".exe")),
                    "ROM/archive/program resource rejected")
        require(not name.startswith(("META-INF/jarjar/", "META-INF/versions/")), "Nested/multirelease runtime rejected")
        require(not name.lower().endswith((".jar", ".dll", ".so", ".dylib")), "Embedded runtime rejected")
        if name.endswith(".wasm"):
            require(namespace == "sfcarcade" and name == WASM, "Duplicate or foreign WASM owner")


@dataclass(frozen=True)
class Plan:
    entries: dict[str, bytes]
    core: dict[str, bytes]
    home: dict[str, bytes]
    core_sha: str
    home_sha: str
    version: str


def plan(core_path: Path, home_path: Path, expected_home_sha: str, version: str) -> Plan:
    core_sha, core = read_archive(core_path)
    require(core_sha == FROZEN_SHA, "Frozen SFC6 archive SHA mismatch")
    home_sha, home = read_archive(home_path)
    require(re.fullmatch(r"[0-9a-fA-F]{64}", expected_home_sha) is not None and home_sha == expected_home_sha.upper(),
            "Home archive SHA mismatch")
    ownership(core, "sfcarcade")
    ownership(home, "sfchome")
    require(set(core) & set(home) == SYNTHESIZED, "Unapproved shared archive entry")
    require(AT in core and AT not in home and WASM in core, "Missing/duplicate AT or WASM ownership")
    require(core[AT] == b"public com.mojang.blaze3d.platform.NativeImage pixels\n", "Frozen AT changed")
    merged = {k: v for k, v in core.items() if k not in SYNTHESIZED}
    merged.update({k: v for k, v in home.items() if k not in SYNTHESIZED})
    merged[META] = combined_metadata(core, home, version)
    merged[MANIFEST] = ("Manifest-Version: 1.0\r\nImplementation-Title: PIQ SFC\r\n"
                        f"Implementation-Version: {version}\r\nImplementation-Vendor: PIQ\r\n\r\n").encode("ascii")
    return Plan(merged, core, home, core_sha, home_sha, version)


def verify(delivery: Path, p: Plan) -> dict:
    delivery_sha, actual = read_archive(delivery)
    require(set(actual) == set(p.entries), "Delivery entry set mismatch")
    for name, expected in p.entries.items():
        require(actual[name] == expected, f"Delivery bytes changed: {name}")
    protected = {}
    for label, original in (("frozen_sfc6", p.core), ("home", p.home)):
        values = {name: sha(data) for name, data in original.items() if name not in SYNTHESIZED}
        protected[label] = {"files": len(values), "classes": sum(n.endswith(".class") for n in values),
                            "sha256_by_entry": dict(sorted(values.items()))}
    return {"ok": True, "schema": "piq-sfc-merged-1", "version": p.version, "delivery": str(delivery),
            "delivery_sha256": delivery_sha, "bytes": delivery.stat().st_size,
            "mod_ids": ["piq_sfc_arcade", "piq_sfc_home"], "frozen_core_sha256": p.core_sha,
            "home_input_sha256": p.home_sha, "protected": protected,
            "synthesized_entries": sorted(SYNTHESIZED), "duplicate_classes": 0, "wasm_owners": [WASM],
            "embedded_wasmtime_or_native_runtimes": 0, "nativeimage_at_preserved": True,
            "installed": False, "minecraft_started": False, "commercial_roms_or_bios_added": False}


def build(delivery: Path, p: Plan) -> dict:
    delivery = safe_path(delivery, existing=False)
    require(not delivery.exists(), "Refusing to overwrite an existing delivery")
    delivery.parent.mkdir(parents=True, exist_ok=True)
    safe_path(delivery.parent, existing=False)
    handle, name = tempfile.mkstemp(prefix=".piq-sfc-merge-", suffix=".jar", dir=delivery.parent)
    staged = Path(name)
    os.close(handle)
    try:
        with zipfile.ZipFile(staged, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
            for entry, data in sorted(p.entries.items()):
                info = zipfile.ZipInfo(entry, DATE)
                info.compress_type = zipfile.ZIP_DEFLATED
                info.create_system = 3
                info.external_attr = 0o100644 << 16
                archive.writestr(info, data, compress_type=zipfile.ZIP_DEFLATED, compresslevel=9)
        verify(staged, p)
        # Exclusive creation never replaces an existing candidate or frozen file.
        with delivery.open("xb") as output:
            with staged.open("rb") as source:
                import shutil
                shutil.copyfileobj(source, output)
        return verify(delivery, p)
    finally:
        staged.unlink(missing_ok=True)  # only this invocation's mkstemp file


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--core", type=Path, default=FROZEN_CORE)
    parser.add_argument("--home", type=Path, required=True)
    parser.add_argument("--home-sha256", required=True)
    parser.add_argument("--version", default="0.1.0-alpha.6")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--report", type=Path, required=True)
    parser.add_argument("--audit-only", action="store_true")
    args = parser.parse_args()
    inputs = {safe_path(args.core, existing=True), safe_path(args.home, existing=True)}
    output = safe_path(args.output, existing=args.audit_only)
    report = safe_path(args.report, existing=False)
    require(output not in inputs and report not in inputs and report != output, "Input/output/report paths must differ")
    require(not report.exists(), "Refusing to overwrite an existing report")
    p = plan(args.core, args.home, args.home_sha256, args.version)
    result = verify(output, p) if args.audit_only else build(output, p)
    require(read_archive(args.core)[0] == p.core_sha and read_archive(args.home)[0] == p.home_sha,
            "Input changed during packaging")
    report.parent.mkdir(parents=True, exist_ok=True)
    safe_path(report.parent, existing=False)
    with report.open("x", encoding="utf-8") as stream:
        json.dump(result, stream, ensure_ascii=False, indent=2)
        stream.write("\n")
    print(json.dumps({k: v for k, v in result.items() if k != "protected"}, ensure_ascii=False))


if __name__ == "__main__":
    main()
