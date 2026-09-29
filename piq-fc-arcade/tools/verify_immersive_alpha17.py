"""Offline alpha17 immersive-input audit against three SHA-frozen installed alpha16/alpha3 JARs.

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
SFC_CONTRACT_FROZEN = True  # Both SFC owners supplied their explicit reviewed production class lists.
CONTRACTS = {
    "fc": {
        "id": "piq_fc_arcade", "old_version": "0.31.0-alpha.16", "version": "0.31.0-alpha.17",
        "baseline": DELIVERY / "PIQ-FC街机/alpha16-rom-browser/piq_fc_arcade-0.31.0-alpha.16.jar",
        "sha": "9E02D350DAED8450F6C27651BC61F019C5229C4AED86FC9F398137F9BA049ADE",
        "changed": classes(FC, ("client/cabinet/CabinetClientBackends", "client/cabinet/CabinetSetupScreen")),
        "added": classes(FC, ("client/cabinet/CabinetImmersiveInput", "client/cabinet/CabinetClientOwner")),
        "debug_only": classes(FC, ("client/cabinet/CabinetClientBackends$Setup",)),
    },
    "sfc": {
        "id": "piq_sfc_home", "old_version": "0.1.0-alpha.3", "version": "0.1.0-alpha.4",
        "baseline": DELIVERY / "PIQ-SFC家用/0.1.0-alpha.3/piq_sfc_home-0.1.0-alpha.3.jar",
        "sha": "1077392ECFE5AC3AEF82DE23310E186FEB6C3E807652201EBE4FD2CBBBDE509F",
        "changed": classes(SFC, (
            "client/SfcCardEditorScreen", "client/SfcCardEditorLayout", "client/SfcHardwareRenderer",
            "client/SfcHomeClient", "client/SfcPlayback", "data/SfcCartridgeData", "item/SfcCartridgeItem",
            "net/SfcHomeNetwork", "net/SfcHomeNetwork$Editor", "net/SfcHomeNetwork$EditorAction",
            "server/SfcCartridgeEditorService", "server/SfcCartridgeEditorService$Edit",
            "server/SfcHomeServer", "server/SfcHomeServer$State", "server/SfcHomeServer$Lease",
        )),
        "added": classes(SFC, (
            "server/SfcCoverStore", "server/SfcCoverService", "server/SfcCoverService$State", "server/SfcCoverService$Transfer",
            "client/SfcCartridgeCovers", "client/SfcCoverGeometry", "client/SfcCoverGeometry$Face",
            "client/SfcCartridgeRenderer", "client/SfcCartridgeRenderer$1", "client/SfcCartridgeRenderer$ItemModel",
            "net/SfcHomeNetwork$CoverRequest", "net/SfcHomeNetwork$CoverChunk",
            "server/SfcHomeStartPolicy", "server/SfcHomeStartPolicy$Plan", "server/SfcHomeStartPolicy$InteractionGate",
            "client/SfcStartupProgress", "client/SfcStartupProgress$Stage",
        )),
        "debug_only": classes(SFC, (
            "client/SfcCardEditorScreen$Phase", "client/SfcCardEditorScreen$Imported",
            "client/SfcHardwareRenderer$CachedModel", "client/SfcHardwareRenderer$1",
            "client/SfcHomeClient$Setup", "client/SfcPlayback$Picture",
            "server/SfcHomeServer$1", "server/SfcHomeServer$Session",
            "server/SfcCartridgeEditorService$State", "server/SfcCartridgeEditorService$Download",
            "server/SfcCartridgeEditorService$Work", "net/SfcHomeNetwork$ClientHandler",
            "net/SfcHomeNetwork$Session", "net/SfcHomeNetwork$Ready", "net/SfcHomeNetwork$Input",
            "net/SfcHomeNetwork$Frames", "net/SfcHomeNetwork$Leave", "net/SfcHomeNetwork$Stopped",
            "net/SfcHomeNetwork$RomRequest", "net/SfcHomeNetwork$RomChunk", "net/SfcHomeNetwork$RomEntry",
        )),
    },
    "native": {
        "id": "piq_native_arcade", "old_version": "0.1.0-alpha.3", "version": "0.1.0-alpha.4",
        "baseline": DELIVERY / "PIQ原生街机/0.1.0-alpha.3/piq_native_arcade-0.1.0-alpha.3.jar",
        "sha": "133FB7076135E24F2388BF7F29BD8A37B7A60BF57FFB88E5AD98ED7BCD174FC6",
        "changed": classes(NATIVE, ("client/NativeArcadeClient",)),
        "added": frozenset(),
        "debug_only": classes(NATIVE, ("client/NativeArcadeClient$Setup",)),
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
            "versionRange": "[0.31.0-alpha.17,0.32.0)", "type": "required", "side": "BOTH", "ordering": "AFTER",
        }.items()), "Addon must require FC alpha17 on both sides")
        fc[0]["versionRange"] = "[0.31.0-alpha.16,0.32.0)"
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
                "Execution body changed beyond debug tables: " + name)
    require(contract["changed"] <= before, "Changed-class whitelist names a nonexistent inherited class")
    for name in (new_files | contract["changed"]):
        raw = archive.read(name)
        require(name.endswith(".class") and len(raw) >= 8 and raw[:4] == b"\xca\xfe\xba\xbe"
                and struct.unpack_from(">H", raw, 6)[0] == 65, "Not a Java 21 class: " + name)
        require(not any(t in name.lower() for t in ("test", "probe", "runner")), "Test/probe accidentally packaged")
        if name.startswith(SFC) and "/client/" not in name:
            require(not any(link in raw for link in (b"net/minecraft/client/", b"cn/piq/sfchome/client/",
                b"cn/piq/fcarcade/client/", b"com/mojang/blaze3d/", b"neoforge/client/")),
                "New or modified SFC common class links client implementation: " + name)
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


def bytecode_checks(paths, javap):
    hosts = {
        "fc": disassemble(paths["fc"], javap, "cn.piq.fcarcade.client.cabinet.CabinetClientBackends"),
        "native": disassemble(paths["native"], javap, "cn.piq.nativearcade.client.NativeArcadeClient"),
    }
    checks = {}
    for key, code in hosts.items():
        compiled = methods(code)
        take = lambda token: next((text for signature, text in compiled.items() if token in signature), "")
        checks[key + "_does_not_construct_old_play_screen"] = not re.search(
            r"\bnew\s+#\d+\s+// class .*?(?:Cabinet|NativeArcade)PlayScreen", code)
        focus = take(" syncInput()")
        checks[key + "_focus_gate"] = all(word in focus for word in (
            "Field playing:Z", "Method current:()Z", "Method running:()Z", "screen:",
            "isWindowActive", "isPaused", "CabinetImmersiveInput.activate", "Method clearInput:()V"))
        key_input = take(" key(")
        checks[key + "_event_edges_p1_only"] = bool(re.search(
            r"CabinetImmersiveInput.mask:\(\)I\s+\d+: iconst_0\s+\d+: invokestatic.*Method input:\(II\)V", key_input))
        opening = take(" screenOpening(")
        checks[key + "_gui_clears_and_pause_cancels"] = all(word in opening for word in (
            "CabinetImmersiveInput.reset", "Method clearInput:()V", "PauseScreen", "setCanceled"))
        stop = take(" stop(")
        checks[key + "_release_exact_owner"] = "CabinetClientOwner.release" in stop and "Field INPUT_OWNER:Ljava/lang/Object;" in stop
        checks[key + "_acquires_shared_owner"] = "CabinetClientOwner.acquire" in code
        checks[key + "_leaves_if_legacy_nes_takes_control"] = "ClientArcadeEvents.isControlling" in take(" current()")
        checks[key + "_invalidates_pending_factory"] = all(word in code for word in (
            "AtomicReference.getAndSet", "Field generation:I", "Field playing:Z"))
        checks[key + "_does_not_mutate_mc_bindings"] = not any(word in code for word in (
            "KeyMapping.set", "syncOtherMappings", "grabMouse", "releaseMouse", "glfwSetInputMode"))
    native_tick = next((text for signature, text in methods(hosts["native"]).items() if " tick(" in signature), "")
    checks["native_setup_lifetime_is_guarded"] = all(word in native_tick for word in (
        "Field anchor:", "Method current:()Z", "Field playing:Z", "NativeArcadeSetupScreen", "Method stop:"))
    for name, needle in (("FcNetwork", 'PROTOCOL_VERSION = "31"'),
                         ("home.CartridgeNetwork", "// String 30"), ("cabinet.CabinetNetwork", "// String cabinet-1")):
        checks["retained_protocol_" + name] = needle in disassemble(paths["fc"], javap, "cn.piq.fcarcade." + name)
    checks["setup_retains_picker_lifecycle"] = "CabinetSetupScreen extends cn.piq.fcarcade.client.rom.LocalRomPickerScreen" in disassemble(
        paths["fc"], javap, "cn.piq.fcarcade.client.cabinet.CabinetSetupScreen")
    if "sfc" in paths:
        sfc_network = disassemble(paths["sfc"], javap, "cn.piq.sfchome.net.SfcHomeNetwork")
        register = next((text for signature, text in methods(sfc_network).items() if " register(" in signature), "")
        checks["sfc_extended_editor_cover_protocol_2"] = "// String 2" in register
        checks["sfc_cover_transfer_bounds"] = "MAX_COVER = 2097152" in sfc_network and "CHUNK = 65536" in sfc_network
        checks["sfc_cover_common_consumer_hook"] = "java/util/function/Consumer.accept" in sfc_network and "cn/piq/sfchome/client/" not in sfc_network
        sfc_client = disassemble(paths["sfc"], javap, "cn.piq.sfchome.client.SfcHomeClient")
        checks["sfc_home_uses_same_owner"] = all(word in sfc_client for word in ("CabinetClientOwner.acquire", "CabinetClientOwner.release", "Field INPUT_OWNER:"))
        sfc_methods = methods(sfc_client)
        checks["sfc_home_legacy_fc_gate_at_accept_tick_and_input"] = all(
            "ClientArcadeEvents.isControlling" in next((text for signature, text in sfc_methods.items() if token in signature), "")
            for token in (" session(", " tick(", " acceptsInput()"))
    require(all(checks.values()), "Packaged immersive contracts failed: " + repr([n for n, ok in checks.items() if not ok]))
    return checks


def packaged_probe(jar, javap, source_name, main_class, threshold):
    source = ROOT / "tools/probes" / source_name
    with tempfile.TemporaryDirectory(prefix="piq-alpha17-final-probe-") as directory:
        temporary = Path(directory)
        empty = temporary / "empty-source"; empty.mkdir()
        output = temporary / "classes"; output.mkdir()
        run([javap.with_name("javac.exe"), "-encoding", "UTF-8", "-proc:none", "-sourcepath", empty,
             "-cp", jar, "-d", output, source])
        compiled = sorted(p.relative_to(output).as_posix() for p in output.rglob("*.class"))
        require(compiled == [main_class.replace(".", "/") + ".class"], "Probe compiled/shadowed production classes")
        result = json.loads(run([javap.with_name("java.exe"), "-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8",
            "-Dstderr.encoding=UTF-8", "-cp", str(jar) + os.pathsep + str(output), main_class, jar], 60))
        require(result.get("ok") is True and result.get("assertions", 0) >= threshold
                and result.get("production_origin") == "final-jar-only"
                and result.get("minecraft_or_native_core_started") is False, "Insufficient packaged probe coverage")
        return result


def inspect(paths, hashes, javap):
    require(SFC_CONTRACT_FROZEN, "SFC alpha4 class contract is not frozen; final audit intentionally blocked")
    initial = {}
    for key, contract in CONTRACTS.items():
        require(re.fullmatch(r"[A-Fa-f0-9]{64}", hashes[key]) is not None, "Three final SHA256 values are mandatory")
        require(paths[key].name == contract["id"] + "-" + contract["version"] + ".jar", "Wrong final filename: " + key)
        require(sha(paths[key]) == hashes[key].upper(), "Final SHA mismatch: " + key)
        require(sha(contract["baseline"]) == contract["sha"], "Frozen baseline changed: " + key)
        initial[key] = sha(paths[key])
    require(sha(SFC_CORE) == SFC_CORE_SHA, "Old SFC alpha6 core changed")
    report = {"schema": 1, "audit": "alpha17-immersive-and-sfc-safety", "validated_at_utc": datetime.now(timezone.utc).isoformat(), "archives": {}}
    for key, contract in CONTRACTS.items():
        with zipfile.ZipFile(paths[key]) as archive, zipfile.ZipFile(contract["baseline"]) as previous:
            report["archives"][key] = {"path": str(paths[key]), "sha256": initial[key], "bytes": paths[key].stat().st_size,
                "entries": len(archive.infolist()), "baseline": str(contract["baseline"]), "baseline_sha256": contract["sha"],
                "delta": delta(archive, previous, contract)}
    report["bytecode_checks"] = bytecode_checks(paths, javap)
    report["immersive_input_and_owner_probe"] = packaged_probe(paths["fc"], javap, "Alpha17ImmersiveProbe.java", "cn.piq.fcarcade.client.cabinet.Alpha17ImmersiveProbe", 145000)
    report["retained_cabinet_probe"] = packaged_probe(paths["fc"], javap, "Alpha15CabinetProbe.java", "cn.piq.fcarcade.cabinet.Alpha15CabinetProbe", 22000)
    report["new_picker_probe"] = packaged_probe(paths["fc"], javap, "Alpha16PickerProbe.java", "cn.piq.fcarcade.client.rom.Alpha16PickerProbe", 1000)
    report["sfc_draft_paging_and_cancellation_probe"] = packaged_probe(paths["sfc"], javap, "Alpha16SfcPickerProbe.java", "cn.piq.sfchome.client.Alpha16SfcPickerProbe", 1000)
    report["sfc_startup_and_player_policy_probe"] = packaged_probe(paths["sfc"], javap, "Alpha17SfcStartupProbe.java", "cn.piq.sfchome.client.Alpha17SfcStartupProbe", 1000)
    report["sfc_new_cover_geometry_probe"] = packaged_probe(paths["sfc"], javap, "Alpha17SfcCoverProbe.java", "cn.piq.sfchome.client.Alpha17SfcCoverProbe", 20)
    require(all(sha(paths[k]) == initial[k] and sha(c["baseline"]) == c["sha"] for k, c in CONTRACTS.items())
            and sha(SFC_CORE) == SFC_CORE_SHA, "Input changed during audit")
    report.update(ok=True, errors=[], old_sfc_core_sha256=SFC_CORE_SHA, limits=[
        "Offline archive, bytecode and actual final-JAR pure Java probes only; no Minecraft screenshots or GUI interaction.",
        "No core/game process, ROM content, commercial game compatibility, installation, upload or deployment tested.",
        "Frozen JARs and all prior checkers/reports are read-only; this standalone checker never builds production code.",
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
