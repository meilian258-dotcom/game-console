"""Offline alpha16 ROM-picker audit against three SHA-frozen alpha15/alpha2 JARs.

No Gradle, game, emulator, ROM content or old checker is executed. Only explicit
test probes are compiled, against delivered JARs with an empty sourcepath.
"""
from __future__ import annotations

import argparse
from collections import Counter
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import struct
import subprocess
import tempfile
import tomllib
import zipfile

ROOT = Path(__file__).resolve().parents[1]
DELIVERY = ROOT.parent / "制作Mod" / "03-街机模拟"
FC = "cn/piq/fcarcade/"
SFC = "cn/piq/sfchome/"
NATIVE = "cn/piq/nativearcade/"
META = "META-INF/neoforge.mods.toml"
MANIFEST = "META-INF/MANIFEST.MF"
SFC_CORE = DELIVERY / "PIQ-SFC街机/piq_sfc_arcade-0.2.0-alpha.6.jar"
SFC_CORE_SHA = "38FA46C5D283EAD9E1F6666D01398F495E2E1A3260A1517BE8EE959710963363"


def classes(prefix, names):
    return frozenset(prefix + name + ".class" for name in names)


# Explicit candidate contract. Update individual entries only after reading the
# corresponding source and examining the final archive, never allow a directory.
CONTRACTS = {
    "fc": {
        "id": "piq_fc_arcade", "old_version": "0.31.0-alpha.15", "version": "0.31.0-alpha.16",
        "baseline": DELIVERY / "PIQ-FC街机/alpha15-reviewed-v2/piq_fc_arcade-0.31.0-alpha.15.jar",
        "sha": "7FF788233234A25AA67AAEFFB171E4275C3597452B6C62781508E2EF4A5B6499",
        "changed": classes(FC, ("client/cabinet/CabinetBackend", "client/cabinet/CabinetSetupScreen")),
        "added": classes(FC, (
            "client/rom/LocalRomLibrary", "client/rom/LocalRomLibrary$Entry", "client/rom/LocalRomLibrary$Scan",
            "client/rom/LocalRomPickerScreen", "client/rom/LocalRomPickerLayout", "client/rom/LocalRomPickerLayout$Rect",
            "client/rom/LocalRomPickerLayout$Layout",
        )),
    },
    "sfc": {
        "id": "piq_sfc_home", "old_version": "0.1.0-alpha.2", "version": "0.1.0-alpha.3",
        "baseline": DELIVERY / "PIQ-SFC家用/piq_sfc_home-0.1.0-alpha.2.jar",
        "sha": "CD6A117779470DFEBBF1E9A594C9A687A6BDEA36AE46A62EE54E51B01D9FBA78",
        "changed": classes(SFC, ("client/SfcCardEditorScreen", "client/cabinet/SfcCabinetProvider")),
        "added": classes(SFC, ("client/SfcCardEditorScreen$Phase", "client/SfcCardEditorScreen$Imported",
            "client/SfcCardLibrary", "client/SfcCardLibrary$Row", "client/SfcEditorWork",
            "client/SfcCardEditorLayout", "client/SfcUploadTitle")),
    },
    "native": {
        "id": "piq_native_arcade", "old_version": "0.1.0-alpha.2", "version": "0.1.0-alpha.3",
        "baseline": DELIVERY / "PIQ原生街机/0.1.0-alpha.2/piq_native_arcade-0.1.0-alpha.2.jar",
        "sha": "B3DC23F4DFAE53CD87730FEBB7668D470D111ADDCEE577803B09351CE34EFA56",
        "changed": classes(NATIVE, ("client/NativeArcadeSetupScreen", "client/NativeCabinetBackend")),
        "added": frozenset(),
        "debug_only": classes(NATIVE, ("client/NativeCabinetBackend$1",)),
    },
}


def require(ok, message):
    if not ok:
        raise ValueError(message)


def sha(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest().upper()


def unique(archive):
    names = [info.orig_filename for info in archive.infolist()]
    require(not [n for n, count in Counter(names).items() if count != 1], "Duplicate ZIP entry")
    require(all(n and not n.startswith(("/", "\\")) and "\\" not in n and ":" not in n
                and ".." not in PurePosixPath(n).parts for n in names), "Unsafe ZIP path")
    require(all(info.filename == info.orig_filename for info in archive.infolist()), "Unsafe normalized ZIP path")
    require(not any(info.flag_bits & 1 for info in archive.infolist()), "Encrypted ZIP entry")
    require(archive.testzip() is None, "ZIP CRC failure")


def metadata_delta(old_raw, new_raw, contract):
    old = tomllib.loads(old_raw.decode("utf-8"))
    new = tomllib.loads(new_raw.decode("utf-8"))
    require(len(old.get("mods", [])) == len(new.get("mods", [])) == 1, "Unexpected mod count")
    require(old["mods"][0].get("modId") == new["mods"][0].get("modId") == contract["id"], "Mod id changed")
    require(old["mods"][0].get("version") == contract["old_version"]
            and new["mods"][0].get("version") == contract["version"], "Unexpected mod version")
    new["mods"][0]["version"] = contract["old_version"]
    if contract["id"] != "piq_fc_arcade":
        dependencies = new.get("dependencies", {}).get(contract["id"], [])
        fc = [d for d in dependencies if d.get("modId") == "piq_fc_arcade"]
        require(len(fc) == 1 and all(fc[0].get(k) == v for k, v in {
            "versionRange": "[0.31.0-alpha.16,0.32.0)", "type": "required", "side": "BOTH", "ordering": "AFTER",
        }.items()), "Addon must require FC alpha16 on both sides")
        fc[0]["versionRange"] = "[0.31.0-alpha.15,0.32.0)"
    require(old == new, "Unauthorized metadata change beyond versions (including old SFC core dependency)")


def manifest_delta(old_raw, new_raw, contract):
    old = old_raw.decode("utf-8").replace("\r", "")
    new = new_raw.decode("utf-8").replace("\r", "")
    before = "Implementation-Version: " + contract["old_version"] + "\n"
    after = "Implementation-Version: " + contract["version"] + "\n"
    require(old.count(before) == new.count(after) == 1, "Unexpected manifest version")
    require(old.replace(before, after) == new, "Unauthorized manifest fields changed")


def without_code_debug(raw):
    """Strip only nested Code debug tables; preserve pool, bytecode and all other attributes."""
    class Reader:
        def __init__(self, data): self.data, self.pos = data, 0
        def take(self, size):
            require(size >= 0 and self.pos + size <= len(self.data), "Truncated class structure")
            result = self.data[self.pos:self.pos + size]; self.pos += size; return result
        def u2(self): return int.from_bytes(self.take(2), "big")
        def u4(self): return int.from_bytes(self.take(4), "big")
    def number(value, size): return value.to_bytes(size, "big")
    reader = Reader(raw)
    require(reader.take(4) == b"\xca\xfe\xba\xbe", "Invalid class magic")
    reader.take(4); count = reader.u2(); names = {}; index = 1
    while index < count:
        tag = reader.take(1)[0]
        if tag == 1: names[index] = reader.take(reader.u2()).decode("utf-8", errors="replace")
        elif tag in (3, 4): reader.take(4)
        elif tag in (5, 6): reader.take(8); index += 1
        elif tag in (7, 8, 16, 19, 20): reader.take(2)
        elif tag in (9, 10, 11, 12, 17, 18): reader.take(4)
        elif tag == 15: reader.take(3)
        else: raise ValueError("Unknown constant pool tag")
        index += 1
    prefix = raw[:reader.pos]
    def attributes(r, strip=False):
        kept = []
        for _ in range(r.u2()):
            name = r.u2(); payload = r.take(r.u4()); label = names.get(name, "")
            if strip and label in ("LineNumberTable", "LocalVariableTable", "LocalVariableTypeTable"): continue
            if label == "Code":
                code = Reader(payload)
                body = code.take(4); length = code.u4(); body += number(length, 4) + code.take(length)
                exceptions = code.u2(); body += number(exceptions, 2) + code.take(exceptions * 8)
                body += attributes(code, True)
                require(code.pos == len(payload), "Unexpected trailing Code bytes")
                payload = body
            kept.append(number(name, 2) + number(len(payload), 4) + payload)
        return number(len(kept), 2) + b"".join(kept)
    output = prefix + reader.take(6)
    interfaces = reader.u2(); output += number(interfaces, 2) + reader.take(interfaces * 2)
    for _ in range(2):
        members = reader.u2(); output += number(members, 2)
        for _ in range(members): output += reader.take(6) + attributes(reader)
    output += attributes(reader)
    require(reader.pos == len(raw), "Unexpected trailing class bytes")
    return output


def delta(archive, previous, contract):
    unique(archive)
    unique(previous)
    before, after = set(previous.namelist()), set(archive.namelist())
    require(not before - after, "Inherited entries removed: " + repr(sorted(before - after)))
    added = after - before
    new_files = {n for n in added if not n.endswith("/")}
    require(new_files == contract["added"], "New files differ from explicit whitelist: " + repr(sorted(new_files ^ contract["added"])))
    require(all(any(p.startswith(n) for p in new_files) and archive.read(n) == b""
                for n in added if n.endswith("/")), "Unexpected structural ZIP directory")
    changed = {n for n in before if archive.read(n) != previous.read(n)}
    debug_only = contract.get("debug_only", frozenset()) & changed
    unexpected = changed - contract["changed"] - {META, MANIFEST} - debug_only
    require(not unexpected, "Unauthorized inherited bytes changed: " + repr(sorted(unexpected)))
    for name in debug_only:
        require(without_code_debug(archive.read(name)) == without_code_debug(previous.read(name)),
                "Native execution body changed beyond debug tables: " + name)
    require(contract["changed"] <= before, "Changed-class whitelist names a nonexistent inherited class")
    for name in (new_files | contract["changed"]):
        raw = archive.read(name)
        require(name.endswith(".class") and len(raw) >= 8 and raw[:4] == b"\xca\xfe\xba\xbe"
                and struct.unpack_from(">H", raw, 6)[0] == 65, "Not a Java 21 class: " + name)
        require(not any(t in name.lower() for t in ("test", "probe", "runner")), "Test/probe accidentally packaged")
    metadata_delta(previous.read(META), archive.read(META), contract)
    manifest_delta(previous.read(MANIFEST), archive.read(MANIFEST), contract)
    preserved = sorted(before - changed)
    protected_classes = [n for n in before if n.endswith(".class") and n not in contract["changed"] and n not in debug_only]
    assets = [n for n in before if n.startswith("assets/") and not n.endswith("/")]
    runtime = [n for n in before if n.startswith(("core/", "natives/", "runtime/")) and not n.endswith("/")]
    return {"added_files": sorted(new_files), "added_directories": sorted(added - new_files),
            "changed_entries": sorted(changed), "permitted_changed_classes": sorted(contract["changed"]),
            "debug_only_identical_pool_opcodes_and_other_attributes": sorted(debug_only),
            "byte_identical_entries": len(preserved), "byte_identical_old_classes": len(protected_classes),
            "byte_identical_all_assets": len(assets), "byte_identical_runtime_files": sorted(runtime)}


def run(arguments, timeout=45):
    result = subprocess.run([str(a) for a in arguments], capture_output=True, text=True,
                            encoding="utf-8", errors="replace", timeout=timeout)
    require(result.returncode == 0, "Command failed: " + repr(arguments[:3]) + "\n" + result.stderr[:3000] + result.stdout[:1000])
    return result.stdout


def disassemble(jar, javap, name):
    return run([javap, "-J-Dfile.encoding=UTF-8", "-J-Dstdout.encoding=UTF-8", "-J-Dstderr.encoding=UTF-8",
                "-p", "-c", "-constants", "-classpath", jar, name])


def methods(code):
    result, signature, lines = {}, None, []
    def finish():
        if signature is not None:
            normalized = re.sub(r"#\d+", "#CP", "\n".join(lines).strip())
            result[signature] = re.sub(r"[ \t]+//", " //", normalized)
    for line in code.splitlines():
        if re.match(r"^  \S.*\(.*\).*;\s*$", line):
            finish(); signature = line.strip(); lines = []
        elif signature is not None and line == "}":
            finish(); signature = None
        elif signature is not None:
            lines.append(line.rstrip())
    finish()
    return result


def backend_metadata_only(path, baseline, javap, name):
    old = methods(disassemble(baseline, javap, name))
    new = methods(disassemble(path, javap, name))
    require(old and old.keys() <= new.keys(), "Backend inherited method removed: " + name)
    require(all(new[key] == value for key, value in old.items()), "Backend behavior changed beyond directory metadata: " + name)
    added = new.keys() - old.keys()
    require(all(any(signature.endswith(method + "();") for method in ("romDirectory", "romExtensions", "romExcludedNames"))
                for signature in added), "Unexpected new backend method: " + name)
    return {"class": name, "unchanged_old_method_count": len(old), "added_directory_metadata_methods": sorted(added)}


def bytecode_checks(paths, javap):
    code = {name: disassemble(paths["fc"], javap, "cn.piq.fcarcade." + name) for name in (
        "client.cabinet.CabinetSetupScreen", "client.cabinet.CabinetClientBackends",
        "client.cabinet.CabinetBackend", "client.rom.LocalRomPickerScreen", "client.rom.LocalRomLibrary",
        "FcNetwork", "home.CartridgeNetwork", "cabinet.CabinetNetwork",
    )}
    checks = {}
    checks["setup_extends_public_picker"] = "CabinetSetupScreen extends cn.piq.fcarcade.client.rom.LocalRomPickerScreen" in code["client.cabinet.CabinetSetupScreen"]
    checks["inherited_setup_identity_remains_in_unchanged_lifecycle"] = "instanceof" in code["client.cabinet.CabinetClientBackends"] and "client/cabinet/CabinetSetupScreen" in code["client.cabinet.CabinetClientBackends"]
    checks["picker_opens_os_folder"] = "openFile" in code["client.rom.LocalRomPickerScreen"]
    checks["picker_uses_bounded_io"] = "LocalRomLibrary.submit" in code["client.rom.LocalRomPickerScreen"]
    tick = methods(code["client.rom.LocalRomPickerScreen"]).get("public void tick();", "")
    checks["small_window_never_starts_unbound_scan"] = all(x in tick for x in ("Field initialized:Z", "Field closed:Z", "isWindowActive")) and tick.index("Field initialized:Z") < tick.index("isWindowActive")
    checks["library_rejects_linked_paths_and_uses_metadata_only"] = all(x in code["client.rom.LocalRomLibrary"] for x in ("NOFOLLOW_LINKS", "toRealPath", "newDirectoryStream", "MAX_ENTRIES = 512", "MAX_INSPECTED = 2048", "ArrayBlockingQueue", "AbortPolicy")) and not any(x in code["client.rom.LocalRomLibrary"] for x in ("readAllBytes", "readString", "ZipFile", "newInputStream"))
    checks["main_protocol_unchanged_31"] = 'PROTOCOL_VERSION = "31"' in code["FcNetwork"]
    checks["cartridge_protocol_unchanged_30"] = "// String 30" in code["home.CartridgeNetwork"]
    checks["cabinet_protocol_unchanged"] = "// String cabinet-1" in code["cabinet.CabinetNetwork"]
    require(all(checks.values()), "Packaged UI contracts failed: " + repr([n for n, ok in checks.items() if not ok]))
    return checks


def packaged_probe(jar, javap, source_name, main_class, threshold):
    source = ROOT / "tools/probes" / source_name
    with tempfile.TemporaryDirectory(prefix="piq-alpha16-final-probe-") as directory:
        temporary = Path(directory)
        empty = temporary / "empty-source"; empty.mkdir()
        output = temporary / "classes"; output.mkdir()
        run([javap.with_name("javac.exe"), "-encoding", "UTF-8", "-proc:none", "-sourcepath", empty,
             "-cp", jar, "-d", output, source])
        compiled = sorted(p.relative_to(output).as_posix() for p in output.rglob("*.class"))
        require(compiled == [main_class.replace(".", "/") + ".class"], "Probe compiled/shadowed production classes")
        result = json.loads(run([javap.with_name("java.exe"), "-cp", str(jar) + os.pathsep + str(output), main_class, jar], 60))
        require(result.get("ok") is True and result.get("assertions", 0) >= threshold
                and result.get("production_origin") == "final-jar-only"
                and result.get("minecraft_or_native_core_started") is False, "Insufficient packaged probe coverage")
        return result


def inspect(paths, hashes, javap):
    initial = {}
    for key, contract in CONTRACTS.items():
        require(re.fullmatch(r"[A-Fa-f0-9]{64}", hashes[key]) is not None, "Three final SHA256 values are mandatory")
        require(paths[key].name == contract["id"] + "-" + contract["version"] + ".jar", "Wrong final filename: " + key)
        require(sha(paths[key]) == hashes[key].upper(), "Final SHA mismatch: " + key)
        require(sha(contract["baseline"]) == contract["sha"], "Frozen baseline changed: " + key)
        initial[key] = sha(paths[key])
    require(sha(SFC_CORE) == SFC_CORE_SHA, "Old SFC alpha6 core changed")
    report = {"schema": 1, "audit": "alpha16-ui-only", "validated_at_utc": datetime.now(timezone.utc).isoformat(), "archives": {}}
    for key, contract in CONTRACTS.items():
        with zipfile.ZipFile(paths[key]) as archive, zipfile.ZipFile(contract["baseline"]) as previous:
            report["archives"][key] = {"path": str(paths[key]), "sha256": initial[key], "bytes": paths[key].stat().st_size,
                "entries": len(archive.infolist()), "baseline": str(contract["baseline"]), "baseline_sha256": contract["sha"],
                "delta": delta(archive, previous, contract)}
    report["bytecode_checks"] = bytecode_checks(paths, javap)
    report["backend_existing_behavior_unchanged"] = [backend_metadata_only(paths[key], CONTRACTS[key]["baseline"], javap, name)
        for key, name in (("fc", "cn.piq.fcarcade.client.cabinet.CabinetBackend"),
                          ("sfc", "cn.piq.sfchome.client.cabinet.SfcCabinetProvider"),
                          ("native", "cn.piq.nativearcade.client.NativeCabinetBackend"))]
    report["retained_cabinet_probe"] = packaged_probe(paths["fc"], javap, "Alpha15CabinetProbe.java", "cn.piq.fcarcade.cabinet.Alpha15CabinetProbe", 22000)
    report["new_picker_probe"] = packaged_probe(paths["fc"], javap, "Alpha16PickerProbe.java", "cn.piq.fcarcade.client.rom.Alpha16PickerProbe", 1000)
    report["sfc_draft_paging_and_cancellation_probe"] = packaged_probe(paths["sfc"], javap, "Alpha16SfcPickerProbe.java", "cn.piq.sfchome.client.Alpha16SfcPickerProbe", 1000)
    require(all(sha(paths[k]) == initial[k] and sha(c["baseline"]) == c["sha"] for k, c in CONTRACTS.items())
            and sha(SFC_CORE) == SFC_CORE_SHA, "Input changed during audit")
    report.update(ok=True, errors=[], old_sfc_core_sha256=SFC_CORE_SHA, limits=[
        "Offline archive, bytecode and actual final-JAR pure Java probes only; no Minecraft screenshots or GUI interaction.",
        "No core/game process, ROM content, commercial game compatibility, installation, upload or deployment tested.",
        "Frozen JARs and alpha15 checker are read-only; this checker never builds production code.",
    ])
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for key in CONTRACTS:
        parser.add_argument("--" + key + "-jar", type=Path, required=True)
        parser.add_argument("--" + key + "-sha256", required=True)
    parser.add_argument("--javap", type=Path, default=Path("C:/Program Files/Microsoft/jdk-21.0.11.10-hotspot/bin/javap.exe"))
    parser.add_argument("--report", type=Path)
    parser.add_argument("--check-only", action="store_true")
    args = parser.parse_args()
    try:
        if not args.check_only:
            require(args.report is not None, "Choose a new report path or --check-only")
            args.report.resolve().relative_to(ROOT.parent.resolve())
            require(not args.report.exists(), "Never overwrite an audit report")
        report = inspect({k: getattr(args, k + "_jar").resolve() for k in CONTRACTS},
                         {k: getattr(args, k + "_sha256") for k in CONTRACTS}, args.javap)
        if not args.check_only:
            args.report.parent.mkdir(parents=True, exist_ok=True)
            with args.report.open("x", encoding="utf-8") as output:
                json.dump(report, output, ensure_ascii=False, indent=2); output.write("\n")
        print(json.dumps(report, ensure_ascii=True))
        return 0
    except (ValueError, OSError, KeyError, zipfile.BadZipFile, subprocess.TimeoutExpired) as error:
        print(json.dumps({"ok": False, "errors": [str(error)]}, ensure_ascii=True))
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
