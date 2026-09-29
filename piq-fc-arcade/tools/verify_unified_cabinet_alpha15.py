"""Independent alpha15 final-JAR audit against immutable FC alpha14.

This checker neither imports nor changes older checkers. All production probes load
the delivered JAR; javac may compile the probe only, never production source files.
"""
from __future__ import annotations

import argparse
from collections import Counter
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import struct
import subprocess
import tempfile
import tomllib
import zipfile

ROOT = Path(__file__).resolve().parents[1]
DELIVERY = ROOT.parent / "制作Mod" / "03-街机模拟"
VERSION = "0.31.0-alpha.15"
BASE_SHA = "BC17E1B483FAD115DAE156C6ECB56D3911915BC31B3C44058D56F3D61002E68A"
SFC_CORE_SHA = "38FA46C5D283EAD9E1F6666D01398F495E2E1A3260A1517BE8EE959710963363"
BASELINE = DELIVERY / "PIQ-FC街机" / "piq_fc_arcade-0.31.0-alpha.14.jar"
SFC_CORE = DELIVERY / "PIQ-SFC街机" / "piq_sfc_arcade-0.2.0-alpha.6.jar"
PREFIX = "cn/piq/fcarcade/"

# Enumerated existing bytecode changes, not a package-level wildcard.
CHANGED_CLASSES = {
    PREFIX + name + ".class" for name in (
        "FcArcadeMod", "FcNetwork", "home/AvCableItem",
        "world/FcArcadeBlock", "world/LegacyFcArcadeBlock", "world/LegacyFcArcadeBlockEntity",
        "world/DualCabinetBlock", "world/DualCabinetBlockEntity", "world/DualCabinetStructure",
        "server/ServerArcadeSessions", "server/ServerArcadeSessions$Manager",
    )
}
# Their source bodies were not changed. Inserting guarded entry methods shifts only
# javac's line tables in these enclosing-source companions; require all other bytes.
DEBUG_ONLY_CLASSES = {
    PREFIX + name + ".class" for name in (
        "server/ServerArcadeSessions$1", "server/ServerArcadeSessions$Session", "server/ServerArcadeSessions$SessionKey",
        "server/ServerArcadeSessions$Manager$IncomingUpload", "server/ServerArcadeSessions$Manager$OutgoingDownload",
        "server/ServerArcadeSessions$Manager$PendingExit", "server/ServerArcadeSessions$Manager$ResumeChoice",
        "server/ServerArcadeSessions$Manager$SaveSlotChoice", "world/DualCabinetStructure$Owner", "world/DualCabinetStructure$Placed",
    )
}
COMMON_CLASSES = {
    PREFIX + "cabinet/" + name + ".class" for name in (
        "CabinetBackends", "CabinetBackends$Entry", "CabinetEmulator", "CabinetFrame",
        "CabinetLeaseLedger", "CabinetLeaseLedger$Lease", "CabinetNetwork", "CabinetNetwork$Choose",
        "CabinetNetwork$ClientSink", "CabinetNetwork$Closed", "CabinetNetwork$Heartbeat",
        "CabinetNetwork$Launch", "CabinetNetwork$Menu", "CabinetNetwork$Release", "CabinetTarget",
        "ServerCabinets", "ServerCabinets$Binding", "ServerCabinets$MenuBinding", "ServerCabinets$State",
    )
}
CLIENT_CLASSES = {
    PREFIX + "client/cabinet/" + name + ".class" for name in (
        "CabinetAudio", "CabinetBackend", "CabinetClientBackends", "CabinetClientBackends$Setup",
        "CabinetKeys", "CabinetMenuScreen", "CabinetPlayScreen", "CabinetSetupScreen", "CabinetVideoDisplay",
        "CabinetCleanup", "CabinetMenuLayout", "CabinetMenuLayout$Layout", "CabinetUi",
    )
}
GEOMETRY_CLASSES = {
    PREFIX + "layout/" + name + ".class" for name in (
        "CabinetVideoGeometry", "CabinetVideoGeometry$Frame", "CabinetVideoGeometry$Uv", "CabinetVideoGeometry$Vertex",
    )
}
ADDED_CLASSES = COMMON_CLASSES | CLIENT_CLASSES | GEOMETRY_CLASSES
LANG_LABELS = {
    "assets/piq_fc_arcade/lang/zh_cn.json": "PIQ 复古游戏",
    "assets/piq_fc_arcade/lang/en_us.json": "PIQ Retro Gaming",
}
CHANGED_NONCLASS = {"META-INF/MANIFEST.MF", "META-INF/neoforge.mods.toml"} | LANG_LABELS.keys()
FORBIDDEN_COMMON = (b"net/minecraft/client/", b"cn/piq/fcarcade/client/", b"org/lwjgl/", b"WasmNesCore", b"WasmSfcCore", b"NativeProcessSession")


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ValueError(message)


def sha(path: Path) -> str:
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest().upper()


def unique(archive: zipfile.ZipFile) -> None:
    duplicates = [n for n, count in Counter(archive.namelist()).items() if count != 1]
    require(not duplicates, "Duplicate ZIP entries: " + repr(duplicates))
    require(all(not n.startswith(("/", "\\")) and ".." not in Path(n).parts and "\\" not in n
                for n in archive.namelist()), "Unsafe ZIP member path")


def class21(raw: bytes, name: str) -> None:
    require(len(raw) >= 8 and raw[:4] == b"\xca\xfe\xba\xbe" and struct.unpack_from(">H", raw, 6)[0] == 65,
            "Not a Java 21 class: " + name)


def without_code_debug(raw: bytes) -> bytes:
    """Remove only Code's three debug tables; retain raw pool, opcodes and all other attributes."""
    class Reader:
        def __init__(self, data): self.data, self.pos = data, 0
        def take(self, size):
            require(size >= 0 and self.pos + size <= len(self.data), "Truncated class structure")
            value = self.data[self.pos:self.pos + size]; self.pos += size; return value
        def u2(self): return int.from_bytes(self.take(2), "big")
        def u4(self): return int.from_bytes(self.take(4), "big")
    def number(value, size): return value.to_bytes(size, "big")
    reader = Reader(raw)
    require(reader.take(4) == b"\xca\xfe\xba\xbe", "Invalid class magic")
    reader.take(4); count = reader.u2(); names = {}; index = 1
    while index < count:
        tag = reader.take(1)[0]
        if tag == 1:
            value = reader.take(reader.u2()); names[index] = value.decode("utf-8", errors="replace")
        elif tag in (3, 4): reader.take(4)
        elif tag in (5, 6): reader.take(8); index += 1
        elif tag in (7, 8, 16, 19, 20): reader.take(2)
        elif tag in (9, 10, 11, 12, 17, 18): reader.take(4)
        elif tag == 15: reader.take(3)
        else: raise ValueError("Unknown constant pool tag: " + str(tag))
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


def validate_delta(archive: zipfile.ZipFile, previous: zipfile.ZipFile) -> dict:
    unique(archive); unique(previous)
    before, after = set(previous.namelist()), set(archive.namelist())
    require(not before - after, "Inherited entries removed: " + repr(sorted(before - after)))
    added = after - before
    new_files = {n for n in added if not n.endswith("/")}
    require(new_files == ADDED_CLASSES, "New files differ from explicit 36-class contract: " + repr(sorted(new_files ^ ADDED_CLASSES)))
    # Only structural ZIP directories for already-enumerated additions may be new.
    require(all(any(path.startswith(n) for path in ADDED_CLASSES) for n in added if n.endswith("/")), "Unexpected new ZIP directory")
    changed = {n for n in before if previous.read(n) != archive.read(n)}
    unexpected = changed - CHANGED_CLASSES - CHANGED_NONCLASS - DEBUG_ONLY_CLASSES
    require(not unexpected, "Unauthorized old bytes changed: " + repr(sorted(unexpected)))
    debug_only = []
    for name in sorted(DEBUG_ONLY_CLASSES & before):
        old_raw, new_raw = previous.read(name), archive.read(name)
        require(without_code_debug(old_raw) == without_code_debug(new_raw), "Implementation changed in debug-only companion: " + name)
        debug_only.append(name)
    for name, label in LANG_LABELS.items():
        old = json.loads(previous.read(name)); new = json.loads(archive.read(name))
        require(new.get("itemGroup.piq_fc_arcade") == label, "Unified tab label missing: " + name)
        old.pop("itemGroup.piq_fc_arcade", None); new.pop("itemGroup.piq_fc_arcade", None)
        require(old == new, "Only the unified creative-page label may change in " + name)
    appearances = [n for n in before if n.startswith("assets/") and n not in LANG_LABELS and not n.endswith("/")]
    require(all(archive.read(n) == previous.read(n) for n in appearances), "An inherited appearance resource changed")
    for name in ADDED_CLASSES:
        raw = archive.read(name); class21(raw, name)
        require(not any(token in name for token in ("Test", "Probe", "Runner", "helper")), "Test/helper accidentally packaged: " + name)
    for name in COMMON_CLASSES:
        raw = archive.read(name)
        require(not any(token in raw for token in FORBIDDEN_COMMON), "New common class links client/core/runtime: " + name)
    protected = sorted(n for n in before if n.endswith(".class") and n not in CHANGED_CLASSES and n not in DEBUG_ONLY_CLASSES)
    require(all(archive.read(n) == previous.read(n) for n in protected), "An unapproved inherited class changed")
    runtime = sorted(n for n in before if n.startswith(("core/", "natives/")) and not n.endswith("/"))
    require(runtime and all(archive.read(n) == previous.read(n) for n in runtime), "Existing WASM/native runtime changed")
    return {"added_classes": sorted(new_files), "added_directories": sorted(added - new_files), "changed_entries": sorted(changed),
            "permitted_changed_classes": sorted(CHANGED_CLASSES), "unchanged_entries": len(before) - len(changed),
            "debug_only_identical_pool_opcodes_and_nondebug_attributes": debug_only,
            "byte_identical_appearance_files": len(appearances), "byte_identical_unapproved_old_classes": len(protected),
            "byte_identical_runtime_files": runtime, "new_common_no_client_link_classes": len(COMMON_CLASSES)}


def mod_metadata(archive: zipfile.ZipFile, mod_id: str, version: str) -> dict:
    data = tomllib.loads(archive.read("META-INF/neoforge.mods.toml").decode("utf-8"))
    mods = data.get("mods", [])
    require(len(mods) == 1 and mods[0].get("modId") == mod_id and mods[0].get("version") == version,
            "Unexpected mod id/version/count for " + mod_id)
    return data


def addon(path: Path, mod_id: str) -> dict:
    before = sha(path)
    with zipfile.ZipFile(path) as archive:
        unique(archive); data = mod_metadata(archive, mod_id, "0.1.0-alpha.2")
        dependencies = data.get("dependencies", {}).get(mod_id, [])
        fc = [entry for entry in dependencies if entry.get("modId") == "piq_fc_arcade"]
        require(len(fc) == 1, "Addon must declare exactly one FC dependency: " + mod_id)
        require(all(fc[0].get(key) == value for key, value in {
            "type": "required", "versionRange": "[0.31.0-alpha.15,0.32.0)", "ordering": "AFTER", "side": "BOTH"
        }.items()), "Addon must require FC alpha15 on both sides: " + mod_id)
        if mod_id == "piq_sfc_home":
            core = [entry for entry in dependencies if entry.get("modId") == "piq_sfc_arcade"]
            require(len(core) == 1 and core[0].get("type") == "required" and core[0].get("side") == "BOTH"
                    and core[0].get("versionRange") == "[0.2.0-alpha.6,0.3.0)", "SFC home lost its required old core dependency")
        for name in archive.namelist():
            if name.endswith(".class"): class21(archive.read(name), name)
        count = len(archive.infolist())
    require(sha(path) == before, "Addon changed during audit: " + str(path))
    return {"path": str(path.resolve()), "sha256": before, "bytes": path.stat().st_size, "entries": count,
            "mod_id": mod_id, "version": "0.1.0-alpha.2", "required_fc": fc[0]}


def run(arguments: list[str], timeout: int = 45) -> str:
    process = subprocess.run(arguments, capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=timeout)
    require(process.returncode == 0, "Command failed: " + repr(arguments[:3]) + "\n" + process.stderr[:3000] + process.stdout[:1500])
    return process.stdout


def bytecode(jar: Path, javap: Path) -> list[dict]:
    names = ("FcNetwork", "home.CartridgeNetwork", "FcArcadeMod", "cabinet.CabinetNetwork", "cabinet.ServerCabinets",
             "cabinet.CabinetTarget", "world.LegacyFcArcadeBlockEntity", "world.FcArcadeBlock", "world.DualCabinetStructure",
             "client.cabinet.CabinetVideoDisplay", "layout.CabinetVideoGeometry")
    code = {name: run([str(javap), "-p", "-c", "-constants", "-classpath", str(jar), "cn.piq.fcarcade." + name]) for name in names}
    checks = []
    def check(label: str, ok: bool) -> None: checks.append({"check": label, "ok": bool(ok)})
    check("main_protocol_31", 'PROTOCOL_VERSION = "31"' in code["FcNetwork"] and "// String 31" in code["FcNetwork"])
    check("cartridge_protocol_stays_30", "// String 30" in code["home.CartridgeNetwork"] and "RegisterPayloadHandlersEvent.registrar" in code["home.CartridgeNetwork"])
    check("new_independent_cabinet_protocol", "// String cabinet-1" in code["cabinet.CabinetNetwork"] and "IPayloadContext.enqueueWork" in code["cabinet.CabinetNetwork"])
    check("common_entry_registers_service", "ServerCabinets.register" in code["FcArcadeMod"])
    check("actual_clicks_route_to_shared_cabinet", all("ServerCabinets.interact" in code[n] for n in ("world.FcArcadeBlock", "world.DualCabinetStructure")))
    check("persistent_identity_and_backend", all(x in code["world.LegacyFcArcadeBlockEntity"] for x in ("CabinetId", "CabinetBackend", "CabinetBackends.NES", "UUID.randomUUID")))
    check("target_requires_complete_dual_and_supported_cabinets", all(x in code["cabinet.CabinetTarget"] for x in ("DualCabinetStructure.complete", "LegacyFcArcadeBlock", "LegacyFcArcadeBlockEntity.cabinetId", "Level.hasChunkAt")))
    server = code["cabinet.ServerCabinets"]
    check("server_menu_lease_permissions_and_identity", all(x in server for x in ("MENU_TICKS = 600", "isSameThread", "isDedicatedServer", "isPublished", "isAlive", "isSpectator",
          "mayInteract", "mayUseItemAt", "RightClickBlock", "isCanceled", "getUseBlock", "getUseItem", "CabinetLeaseLedger.heartbeat", "CabinetLeaseLedger.release", "hasCabinetSession")))
    check("renderer_checks_identity_and_preserves_nes", all(x in code["client.cabinet.CabinetVideoDisplay"] for x in ("AFTER_BLOCK_ENTITIES", "CabinetTarget.matches", "CabinetBackends.NES", "CabinetVideoGeometry.frame", "PoseStack.popPose")))
    check("real_wide_glass_and_raw_aspect_fit", all(x in code["layout.CabinetVideoGeometry"] for x in ("ScreenAspectFit$Aspect.WIDE", "DualScreenPresentation.frame", "RocketArcadeGeometry.screen", "ScreenAspectFit.fit", "displayAspect:")))
    require(all(item["ok"] for item in checks), "Packaged bytecode contracts failed: " + repr([x["check"] for x in checks if not x["ok"]]))
    return checks


def packaged_probe(jar: Path, javap: Path) -> dict:
    source = ROOT / "tools/probes/Alpha15CabinetProbe.java"
    with tempfile.TemporaryDirectory(prefix="piq-alpha15-final-probe-") as directory:
        temporary = Path(directory); empty_source = temporary / "empty-source"; empty_source.mkdir()
        classes = temporary / "classes"; classes.mkdir()
        run([str(javap.with_name("javac.exe")), "-encoding", "UTF-8", "-proc:none", "-sourcepath", str(empty_source),
             "-cp", str(jar), "-d", str(classes), str(source)])
        compiled = sorted(p.relative_to(classes).as_posix() for p in classes.rglob("*.class"))
        require(compiled == ["cn/piq/fcarcade/cabinet/Alpha15CabinetProbe.class"], "Probe compiled replacement production/helper classes: " + repr(compiled))
        output = run([str(javap.with_name("java.exe")), "-cp", str(jar) + ";" + str(classes),
                      "cn.piq.fcarcade.cabinet.Alpha15CabinetProbe", str(jar)], timeout=60)
        result = json.loads(output)
        require(result.get("ok") is True and result.get("assertions", 0) >= 22000 and result.get("geometry_scenarios") == 1120
                and result.get("production_origin") == "final-jar-only" and result.get("minecraft_or_native_core_started") is False,
                "Insufficient final-JAR probe coverage")
        return result


def inspect(jar: Path, expected_sha: str, sfc_home: Path, native: Path, javap: Path,
            baseline: Path = BASELINE, sfc_core: Path = SFC_CORE) -> dict:
    jar = jar.resolve(); baseline = baseline.resolve(); sfc_core = sfc_core.resolve()
    require(re.fullmatch(r"[A-Fa-f0-9]{64}", expected_sha) is not None, "Final JAR SHA256 is mandatory")
    require(jar.name == "piq_fc_arcade-" + VERSION + ".jar", "Wrong final FC filename")
    digest = sha(jar); require(digest == expected_sha.upper(), "Final JAR differs from supplied SHA256")
    require(sha(baseline) == BASE_SHA, "Immutable FC alpha14 baseline changed")
    require(sha(sfc_core) == SFC_CORE_SHA, "Immutable old SFC alpha6 core changed")
    report = {"schema": 1, "version": VERSION, "main_protocol": 31, "cartridge_protocol": 30, "cabinet_protocol": "cabinet-1",
              "jar_path": str(jar), "jar_sha256": digest, "jar_bytes": jar.stat().st_size,
              "baseline_path": str(baseline), "baseline_sha256": BASE_SHA, "old_sfc_core_path": str(sfc_core),
              "old_sfc_core_sha256": SFC_CORE_SHA, "validated_at_utc": datetime.now(timezone.utc).isoformat()}
    with zipfile.ZipFile(jar) as archive, zipfile.ZipFile(baseline) as prior:
        report["delta"] = validate_delta(archive, prior); report["entries"] = len(archive.infolist())
        mod_metadata(archive, "piq_fc_arcade", VERSION)
    report["addons"] = [addon(sfc_home, "piq_sfc_home"), addon(native, "piq_native_arcade")]
    report["bytecode_checks"] = bytecode(jar, javap)
    report["actual_final_jar_pure_probe"] = packaged_probe(jar, javap)
    require(sha(jar) == digest and sha(baseline) == BASE_SHA and sha(sfc_core) == SFC_CORE_SHA, "An input changed during audit")
    report["ok"] = True; report["errors"] = []
    report["limits"] = ["Offline final-JAR byte preservation, metadata, bytecode and actual pure-class execution only.",
                        "No Minecraft, SFC ROM, MAME process, network gameplay or commercial game compatibility was tested.",
                        "No existing alpha14 checker, manifest, report, game installation or runtime was modified."]
    return report


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", type=Path, required=True); parser.add_argument("--jar-sha256", required=True)
    parser.add_argument("--sfc-home-jar", type=Path, required=True); parser.add_argument("--native-jar", type=Path, required=True)
    parser.add_argument("--baseline", type=Path, default=BASELINE); parser.add_argument("--sfc-core-jar", type=Path, default=SFC_CORE)
    parser.add_argument("--javap", type=Path, default=Path("C:/Program Files/Microsoft/jdk-21.0.11.10-hotspot/bin/javap.exe"))
    parser.add_argument("--report", type=Path); parser.add_argument("--check-only", action="store_true")
    args = parser.parse_args()
    try:
        if not args.check_only:
            require(args.report is not None, "Choose a new report path or --check-only")
            args.report.resolve().relative_to(ROOT.parent.resolve())
            require(not args.report.exists(), "Never overwrite an audit report")
        report = inspect(args.jar, args.jar_sha256, args.sfc_home_jar, args.native_jar, args.javap, args.baseline, args.sfc_core_jar)
        if not args.check_only:
            args.report.parent.mkdir(parents=True, exist_ok=True)
            with args.report.open("x", encoding="utf-8") as output:
                json.dump(report, output, ensure_ascii=False, indent=2); output.write("\n")
        print(json.dumps(report, ensure_ascii=True)); return 0
    except (ValueError, OSError, KeyError, zipfile.BadZipFile, subprocess.TimeoutExpired) as error:
        print(json.dumps({"ok": False, "errors": [str(error)]}, ensure_ascii=True)); return 1


if __name__ == "__main__":
    raise SystemExit(main())
