"""Offline alpha18 device UI / right-click / SFC hardware-and-join audit against SHA-frozen alpha17/alpha4 JARs.

No Gradle, game, emulator, ROM content or old checker is executed. Only explicit
test probes are compiled, against delivered JARs with an empty sourcepath.
"""
from __future__ import annotations

import argparse
from collections import Counter
from datetime import datetime, timezone
import hashlib
import io
import json
import os
from pathlib import Path, PurePosixPath
import re
import struct
import subprocess
import sys
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
        "id": "piq_fc_arcade", "old_version": "0.31.0-alpha.17", "version": "0.31.0-alpha.18",
        "baseline": DELIVERY / "PIQ-FC街机/alpha17-immersive/piq_fc_arcade-0.31.0-alpha.17.jar",
        "sha": "BB80909B99B4F357F71C3CBC86E580CA7F80D172C5153D52B9F14F6F90494540",
        "changed": classes(FC, (
            "cabinet/ServerCabinets", "client/ArcadeSaveCatalogScreen", "client/ArcadeSaveSlotsScreen",
            "client/ArcadeSettingsScreen", "client/CartridgeScreenCompat", "client/ClientArcadeEvents",
            "client/ClientCartridgeEditor", "client/FcRomDeleteScreen", "client/LeaderboardPanelScreen",
            "client/RomLibraryScreen", "client/RomRenameScreen", "client/SkinLibraryScreen",
            "client/cabinet/CabinetClientBackends", "client/cabinet/CabinetMenuLayout",
            "client/cabinet/CabinetMenuLayout$Layout", "client/cabinet/CabinetMenuScreen",
            "client/rom/LocalRomPickerLayout", "client/rom/LocalRomPickerLayout$Layout", "client/rom/LocalRomPickerScreen",
        )),
        "added": classes(FC, (
            "client/cabinet/CabinetUseGuard", "client/ui/DeviceUi", "client/ui/DeviceUi$Tone", "client/ui/DeviceUi$DeviceButton",
            "client/ui/DeviceLayout", "client/ui/DeviceLayout$Rect", "client/ui/DeviceLayout$Browser",
            "client/ui/DeviceFormLayout", "client/ui/DeviceConfirmScreen",
        )),
        "debug_only": classes(FC, (
            "client/ClientCartridgeEditor$Entry", "client/ClientCartridgeEditor$LocalCover", "client/ClientCartridgeEditor$LocalRom",
            "client/ClientCartridgeEditor$Upload", "client/RomLibraryScreen$Entry", "client/RomLibraryScreen$EntryBuilder",
            "client/SkinLibraryScreen$Entry", "client/cabinet/CabinetClientBackends$Setup", "client/rom/LocalRomPickerLayout$Rect",
        )),
        "removed": frozenset(), "resources": {},
    },
    "sfc": {
        "id": "piq_sfc_home", "old_version": "0.1.0-alpha.4", "version": "0.1.0-alpha.5",
        "baseline": DELIVERY / "PIQ-SFC家用/0.1.0-alpha.4/piq_sfc_home-0.1.0-alpha.4.jar",
        "sha": "8EF4A49DA04289426AC0AF71DC3DBC28EA4605445F8F2965FE669004DDB0A133",
        "changed": classes(SFC, (
            "client/SfcCardEditorScreen", "client/SfcHomeClient", "client/SfcHomeClient$Setup", "client/SfcPlayback",
            "client/SfcHardwareRenderer", "client/SfcCartridgeRenderer", "net/SfcHomeNetwork",
            "server/SfcHomeServer", "server/SfcHomeServer$State", "server/SfcHomeServer$Lease", "server/SfcHomeServer$Session",
            "server/SfcHomeStartPolicy", "server/SfcInputHealth",
        )),
        "added": classes(SFC, (
            "client/SfcHardwareMeshData", "client/SfcHardwareMeshData$Part", "client/SfcHardwareMesh", "client/SfcHardwareMesh$Part",
            "client/SfcHardwareItems", "client/SfcHardwareItems$1", "client/SfcHardwareItems$HardwareItemRenderer", "client/SfcHardwareItems$ItemModel",
            "client/SfcControllerPoseLayout", "client/SfcControllerPoseLayout$Rig", "client/SfcControllerPoseLayout$Arm", "client/SfcControllerPose",
            "client/SfcPlayback$Restore", "client/SfcJoinClient", "client/SfcJoinScreen", "server/SfcHomeServer$Joining",
            "server/SfcJoinGate", "server/SfcJoinGate$Phase",
            "net/SfcJoinNetwork", "net/SfcJoinNetwork$Client", "net/SfcJoinNetwork$Offer", "net/SfcJoinNetwork$Allow",
            "net/SfcJoinNetwork$Approval", "net/SfcJoinNetwork$Decision", "net/SfcJoinNetwork$Capture", "net/SfcJoinNetwork$State",
            "net/SfcJoinNetwork$Upload", "net/SfcJoinNetwork$Applied", "net/SfcJoinNetwork$Result", "net/SfcJoinNetwork$ControllerInput",
        )),
        "debug_only": classes(SFC, (
            "client/SfcCardEditorScreen$Phase", "client/SfcCardEditorScreen$Imported", "client/SfcPlayback$Picture",
            "client/SfcHardwareRenderer$1", "client/SfcCartridgeRenderer$1", "client/SfcCartridgeRenderer$ItemModel",
            "server/SfcHomeServer$1", "server/SfcHomeStartPolicy$Plan", "server/SfcHomeStartPolicy$InteractionGate",
            "net/SfcHomeNetwork$ClientHandler", "net/SfcHomeNetwork$CoverChunk", "net/SfcHomeNetwork$CoverRequest",
            "net/SfcHomeNetwork$Editor", "net/SfcHomeNetwork$EditorAction", "net/SfcHomeNetwork$Frames", "net/SfcHomeNetwork$Input",
            "net/SfcHomeNetwork$Leave", "net/SfcHomeNetwork$Ready", "net/SfcHomeNetwork$RomChunk", "net/SfcHomeNetwork$RomEntry",
            "net/SfcHomeNetwork$RomRequest", "net/SfcHomeNetwork$Session", "net/SfcHomeNetwork$Stopped",
        )),
        "removed": classes(SFC, ("client/SfcHardwareRenderer$CachedModel",)),
        "resources": {"assets/piq_sfc_home/meshes/sfc_hardware.json": "F6EBE22F24F84D4383AB409876BC3C6A43E20DB81E1B2A98869A2340ADDF0AAC"},
    },
    "native": {
        "id": "piq_native_arcade", "old_version": "0.1.0-alpha.4", "version": "0.1.0-alpha.5",
        "baseline": DELIVERY / "PIQ原生街机/0.1.0-alpha.4/piq_native_arcade-0.1.0-alpha.4.jar",
        "sha": "E3ECCCFF776BBB1C5AE372C52D160DA8C59F89D0C940A1194BEDCC14A1468956",
        "changed": classes(NATIVE, ("client/NativeArcadeClient",)), "added": frozenset(),
        "debug_only": classes(NATIVE, ("client/NativeArcadeClient$Setup",)), "removed": frozenset(), "resources": {},
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
            "versionRange": "[0.31.0-alpha.18,0.32.0)", "type": "required", "side": "BOTH", "ordering": "AFTER",
        }.items()), "Addon must require FC alpha18 on both sides")
        fc[0]["versionRange"] = "[0.31.0-alpha.17,0.32.0)"
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
    removed = before - after
    require(removed == contract["removed"], "Unexpected inherited deletion set: " + repr(sorted(removed ^ contract["removed"])))
    added = after - before
    new_files = {n for n in added if not n.endswith("/")}
    expected_new = contract["added"] | set(contract["resources"])
    require(new_files == expected_new, "New files differ from explicit whitelist: " + repr(sorted(new_files ^ expected_new)))
    for name, expected_sha in contract["resources"].items():
        require(hashlib.sha256(archive.read(name)).hexdigest().upper() == expected_sha, "Unreviewed resource bytes: " + name)
    require(all(any(p.startswith(n) for p in new_files) and archive.read(n) == b""
                for n in added if n.endswith("/")), "Unexpected structural ZIP directory")
    changed = {n for n in before & after if archive.read(n) != previous.read(n)}
    debug_only = contract.get("debug_only", frozenset()) & changed
    unexpected = changed - contract["changed"] - {META, MANIFEST} - debug_only
    require(not unexpected, "Unauthorized inherited bytes changed: " + repr(sorted(unexpected)))
    for name in debug_only:
        require(without_code_debug(archive.read(name)) == without_code_debug(previous.read(name)),
                "Execution body changed beyond debug tables: " + name)
    require(contract["changed"] <= before, "Changed-class whitelist names a nonexistent inherited class")
    for name in (contract["added"] | contract["changed"]):
        raw = archive.read(name)
        require(name.endswith(".class") and len(raw) >= 8 and raw[:4] == b"\xca\xfe\xba\xbe"
                and struct.unpack_from(">H", raw, 6)[0] == 65, "Not a Java 21 class: " + name)
        require(not any(t in name.lower() for t in ("test", "probe", "runner")), "Test/probe accidentally packaged")
        if name.startswith((FC, SFC, NATIVE)) and "/client/" not in name:
            require(not any(link in raw for link in (b"net/minecraft/client/", b"cn/piq/sfchome/client/",
                b"cn/piq/fcarcade/client/", b"cn/piq/nativearcade/client/", b"com/mojang/blaze3d/", b"neoforge/client/")),
                "New or modified common class links client implementation: " + name)
    metadata_delta(previous.read(META), archive.read(META), contract)
    manifest_delta(previous.read(MANIFEST), archive.read(MANIFEST), contract)
    preserved = sorted(before - changed - removed)
    protected_classes = [n for n in before if n.endswith(".class") and n not in contract["changed"] and n not in debug_only and n not in removed]
    assets = [n for n in before if n.startswith("assets/") and not n.endswith("/")]
    runtime = [n for n in before if n.startswith(("core/", "natives/", "runtime/")) and not n.endswith("/")]
    return {"removed_entries": sorted(removed), "added_resources_sha256": contract["resources"], "added_files": sorted(new_files), "added_directories": sorted(added - new_files),
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
        if re.match(r"^  (?:\S.*\(.*\).*;|static \{\};)\s*$", line):
            finish(); signature = line.strip(); lines = []
        elif signature is not None and line == "}":
            finish(); signature = None
        elif signature is not None:
            lines.append(line.rstrip())
    finish()
    return result


def assert_methods_unchanged(old_path, new_path, javap, class_name, allowed):
    """Ignore pool indexes and compiler lambda suffix numbering, not instructions/operands."""
    def normalize(raw):
        groups = {}
        for signature, body in methods(raw).items():
            stable = re.sub(r"(lambda\$[^$]+\$)\d+", r"\1N", signature)
            body = re.sub(r"(lambda\$[^$]+\$)\d+", r"\1N", body)
            groups.setdefault(stable, []).append(body)
        return {k: sorted(v) for k, v in groups.items()}
    before = normalize(disassemble(old_path, javap, class_name))
    after = normalize(disassemble(new_path, javap, class_name))
    for signature, bodies in before.items():
        if any(token in signature for token in allowed):
            continue
        require(after.get(signature) == bodies, "Unreviewed method change: " + class_name + " " + signature)
    require(all(signature in before or any(token in signature for token in allowed) for signature in after),
            "Unreviewed new method in " + class_name)
    return len([s for s in before if not any(token in s for token in allowed)])


def assert_selected_methods_unchanged(old_path,new_path,javap,class_name,tokens):
    before=methods(disassemble(old_path,javap,class_name));after=methods(disassemble(new_path,javap,class_name))
    count=0
    for token in tokens:
        selected=[signature for signature in before if token in signature]
        require(bool(selected),"Protected method absent from baseline: "+class_name+token)
        for signature in selected:
            require(after.get(signature)==before[signature],"Protected lifecycle method changed: "+class_name+" "+signature);count+=1
    return count


def bytecode_checks(paths, javap):
    cached = {}
    def code(key, name):
        pair=(key,name)
        if pair not in cached:cached[pair]=disassemble(paths[key],javap,name)
        return cached[pair]
    def take(raw, token):
        return next((text for signature,text in methods(raw).items() if token in signature),"")
    checks={}
    hosts={"fc":code("fc","cn.piq.fcarcade.client.cabinet.CabinetClientBackends"),
           "native":code("native","cn.piq.nativearcade.client.NativeArcadeClient")}
    for key,host in hosts.items():
        checks[key+"_no_old_play_screen_construction"]=not re.search(r"\bnew\s+#\d+\s+// class .*?(?:Cabinet|NativeArcade)PlayScreen",host)
        focus=take(host," syncInput()")
        checks[key+"_focus_and_gui_gate"]=all(t in focus for t in ("Field playing:Z","Method current:()Z","Method running:()Z","screen:","isWindowActive","isPaused","CabinetImmersiveInput.activate","Method clearInput:()V"))
        checks[key+"_one_player_input_mask"]=bool(re.search(r"CabinetImmersiveInput.mask:\(\)I\s+\d+: iconst_0\s+\d+: invokestatic.*Method input:\(II\)V",take(host," key(")))
        opening=take(host," screenOpening(")
        checks[key+"_pause_or_other_gui_clears_without_ending"]=all(t in opening for t in ("CabinetImmersiveInput.reset","Method clearInput:()V")) and not any(t in opening for t in ("Method stop:","setCanceled","PauseScreen"))
        checks[key+"_exact_owner_release"]="CabinetClientOwner.release" in take(host," stop(") and "Field INPUT_OWNER:" in take(host," stop(")
        checks[key+"_owner_acquire"]="CabinetClientOwner.acquire" in host
        checks[key+"_legacy_nes_not_overridden"]="ClientArcadeEvents.isControlling" in take(host," current()")
        checks[key+"_pending_generation_cleanup"]=all(t in host for t in ("AtomicReference.getAndSet","Field generation:I","Field playing:Z"))
        checks[key+"_no_minecraft_binding_mutation"]=not any(t in host for t in ("KeyMapping.set","syncOtherMappings","grabMouse","releaseMouse","glfwSetInputMode"))
    fc=hosts["fc"];native=hosts["native"]
    checks["fc_held_use_blocks_launch_and_releases_new_lease"]=all(t in take(fc," openBackend(") for t in ("CabinetUseGuard.blocked","Method release:","CabinetNetwork$Launch.lease"))
    checks["fc_server_closed_matches_lease_then_suppresses_held_use"]=all(t in take(fc," closed(") for t in ("CabinetNetwork$Launch.lease","UUID.equals","CabinetUseGuard.suppressWhileHeld","Method stop:"))
    checks["fc_guard_observed_even_without_launch"]=take(fc," tick(").find("CabinetUseGuard.blocked")<take(fc," tick(").find("Field launch:")
    native_open=take(native," openAt(")
    checks["native_same_machine_click_stops_with_identity_gate"]=all(t in native_open for t in ("CabinetUseGuard.blocked","Method matches:","Field playing:Z","Field identity:","Field dimension:","CabinetUseGuard.suppressWhileHeld","Method stop:"))
    checks["native_setup_remains_bound"]=all(t in take(native," tick(") for t in ("Field anchor:","Method current:()Z","Field playing:Z","NativeArcadeSetupScreen","Method stop:"))
    use_guard=code("fc","cn.piq.fcarcade.client.cabinet.CabinetUseGuard")
    checks["held_use_guard_only_reads_key_and_resets_on_release"]=all(t in use_guard for t in ("keyUse:","KeyMapping.isDown","Field waitingRelease:Z","player:","level:")) and "KeyMapping.set" not in use_guard
    server=code("fc","cn.piq.fcarcade.cabinet.ServerCabinets");interaction=take(server," interact(")
    checks["server_rightclick_owner_only_after_permission"]=all(t in interaction for t in ("Method allowClick:","Method valid:","CabinetLeaseLedger$Lease.owner","ServerPlayer.getUUID","Method close:")) and interaction.find("Method valid:")<interaction.find("Method close:")
    checks["server_rightclick_preserves_nes_passthrough"]=all(t in interaction for t in ("CabinetBackends.NES","CabinetLeaseLedger.target","Field","ireturn"))
    for name,needle in (("FcNetwork",'PROTOCOL_VERSION = "31"'),("home.CartridgeNetwork","// String 30"),("cabinet.CabinetNetwork","// String cabinet-1")):
        checks["retained_protocol_"+name]=needle in code("fc","cn.piq.fcarcade."+name)
    # Changing a screen does not authorize hidden changes to FC keyboard/session algorithms.
    stable_methods={
        "ServerCabinets":assert_methods_unchanged(CONTRACTS["fc"]["baseline"],paths["fc"],javap,"cn.piq.fcarcade.cabinet.ServerCabinets",(" interact(",)),
        "ClientArcadeEvents":assert_methods_unchanged(CONTRACTS["fc"]["baseline"],paths["fc"],javap,"cn.piq.fcarcade.client.ClientArcadeEvents",
            (" promptExit("," promptResume("," offerMultiplayer("," requestJoinApproval(","lambda$promptExit$","lambda$promptResume$","lambda$offerMultiplayer$","lambda$requestJoinApproval$")),
        "SfcHomeNetwork":assert_methods_unchanged(CONTRACTS["sfc"]["baseline"],paths["sfc"],javap,"cn.piq.sfchome.net.SfcHomeNetwork",(" register(",)),
    }
    stable_methods["FC_host"]=assert_methods_unchanged(CONTRACTS["fc"]["baseline"],paths["fc"],javap,
        "cn.piq.fcarcade.client.cabinet.CabinetClientBackends",
        (" openMenu("," openBackend("," closed("," start("," key("," screenOpening("," tick("))
    stable_methods["Native_host"]=assert_methods_unchanged(CONTRACTS["native"]["baseline"],paths["native"],javap,
        "cn.piq.nativearcade.client.NativeArcadeClient",(" openAt("," start("," key("," screenOpening("," tick("))
    stable_methods["cartridge_lifecycle"]=assert_selected_methods_unchanged(CONTRACTS["fc"]["baseline"],paths["fc"],javap,
        "cn.piq.fcarcade.client.ClientCartridgeEditor",(" current("," cancel("," send("," tick("," write("," removed("," onClose("))
    stable_methods["picker_lifecycle"]=assert_selected_methods_unchanged(CONTRACTS["fc"]["baseline"],paths["fc"],javap,
        "cn.piq.fcarcade.client.rom.LocalRomPickerScreen",(" current("," choose("," chooseExplicit("," tick("," removed("," onClose("," openDirectory("))
    stable_methods["sfc_original_health_budget"]=assert_selected_methods_unchanged(CONTRACTS["sfc"]["baseline"],paths["sfc"],javap,
        "cn.piq.sfchome.server.SfcInputHealth",(" start("," packet("))
    ui_names=("ClientCartridgeEditor","RomLibraryScreen","RomRenameScreen","ArcadeSettingsScreen","LeaderboardPanelScreen","ArcadeSaveSlotsScreen","ArcadeSaveCatalogScreen","SkinLibraryScreen","rom.LocalRomPickerScreen","cabinet.CabinetMenuScreen")
    for name in ui_names:
        ui=code("fc","cn.piq.fcarcade.client."+name)
        checks["flat_device_ui_"+name]="DeviceUi.panel" in ui and "renderBackground:" not in ui
    cartridge=code("fc","cn.piq.fcarcade.client.ClientCartridgeEditor")
    cover_write=take(cartridge," writeCover(")
    checks["cover_update_uses_only_committed_rom_and_title"]=all(t in cover_write for t in (
        "Method current:()Z","Field busy:Z","Field romSha:","Field currentCardTitle:","CartridgeNetwork$Request", "Method send:")) and not any(t in cover_write for t in ("Field draftTitle:","Field titleBox:","Field chosenRomSha:")) and bool(re.search(
        r"Field romSha:[^\n]+\n\s+\d+: aload_1\n\s+\d+: aload_0\n\s+\d+: getfield\s+#CP\s+// Field currentCardTitle:",cover_write))
    cover_actions=[body for signature,body in methods(cartridge).items() if "lambda$init$" in signature and "Method writeCover:" in body]
    checks["clear_and_restore_cover_never_commit_draft"]=len(cover_actions)==2 and sum("Field originalCover:" in body for body in cover_actions)==1 and sum(bool(re.search(r"// String\s*\n",body)) for body in cover_actions)==1 and all("Method write:" not in body and "Field draftTitle:" not in body for body in cover_actions)
    menu=code("fc","cn.piq.fcarcade.client.cabinet.CabinetMenuScreen")
    checks["menu_has_explicit_single_use_action_and_expiry"]=all(t in menu for t in ("DeviceUi.row","DeviceUi.button","Field sent:Z","CabinetNetwork$Choose","sipush        600","CabinetTarget.matches"))
    picker=code("fc","cn.piq.fcarcade.client.rom.LocalRomPickerScreen")
    checks["picker_selection_is_not_immediate_load"]=all(t in picker for t in ("Field focusedPath:","Method focusedEntry:","DeviceUi.row","DeviceUi.button","Method choose:"))
    current=take(picker," current(")
    checks["picker_retains_screen_connection_generation"]=all(t in current for t in ("Field closed:Z","Field revision:I","screen:","getConnection","Field connection:"))
    checks["picker_retains_file_validation_and_async_bounds"]="LocalRomLibrary.validateFile" in picker and "LocalRomLibrary.submit" in picker
    button=code("fc","cn.piq.fcarcade.client.ui.DeviceUi$DeviceButton")
    checks["dynamic_button_message_and_tooltip"]=all(t in button for t in ("getMessage:","Component.getString","Tooltip.create","Method setTooltip:"))
    confirm=code("fc","cn.piq.fcarcade.client.ui.DeviceConfirmScreen")
    checks["confirmation_exactly_once_and_long_text_scroll"]=all(t in confirm for t in ("Field answered:Z","Consumer.accept","Field scroll:I","Field maxScroll:I","enableScissor","disableScissor"))
    checks["delete_retains_exact_wrapper"]="FcRomDeleteScreen extends cn.piq.fcarcade.client.ui.DeviceConfirmScreen" in code("fc","cn.piq.fcarcade.client.FcRomDeleteScreen")
    saves=code("fc","cn.piq.fcarcade.client.ArcadeSaveSlotsScreen")
    checks["save_slot_tabs_and_explicit_restart"]=all(t in saves for t in ("Field selectedSlot:I","Method confirmRestart:","Method confirmDelete:","DeviceConfirmScreen","ArcadeSaveSlotActionPayload"))
    compat=code("fc","cn.piq.fcarcade.client.CartridgeScreenCompat")
    checks["compat_preserves_foreign_public_blacklist"]=all(t in compat for t in ("FcMenuState.publicBlacklist","FcMenuState.addNames","loadBlacklist")) and not any(t in compat for t in ("class net/minecraft/client/gui/screens/Screen","class net/minecraft/client/gui/screens/ConfirmScreen","mBlurEnabled"))
    sfc_network=code("sfc","cn.piq.sfchome.net.SfcHomeNetwork")
    checks["sfc_protocol_3_and_additive_join_register"]=all(t in take(sfc_network," register(") for t in ("// String 3","SfcJoinNetwork.register"))
    checks["sfc_cover_limits_retained"]="MAX_COVER = 2097152" in sfc_network and "CHUNK = 65536" in sfc_network
    join=code("sfc","cn.piq.sfchome.net.SfcJoinNetwork")
    checks["join_protocol_serverthread_dispatch"]=all(t in join for t in ("// String 3","enqueueWork","ServerPlayer","SfcHomeServer.joinInput","SfcHomeServer.decideJoin"))
    gate=code("sfc","cn.piq.sfchome.server.SfcJoinGate")
    checks["join_state_and_payload_limits"]="MAX_STATE = 16777216" in gate and "CHUNK = 30720" in gate
    sfc_server=code("sfc","cn.piq.sfchome.server.SfcHomeServer")
    checks["p2_input_bound_to_new_lease"]=all(t in take(sfc_server," joinInput(") for t in ("SfcJoinNetwork$ControllerInput.lease","UUID.equals","SfcHomeServer$Lease.player","Method acceptInput:"))
    legacy_input=take(sfc_server," input(")
    checks["p1_only_legacy_input"]=all(t in legacy_input for t in ("Method member:","Method port:","iconst_0","Method acceptInput:")) and bool(re.search(r"Method port:[^\n]+\n\s+\d+: ifeq\s+(\d+)\n\s+\d+: return\n\s+\1: aload_0",legacy_input))
    checks["p2_health_failure_releases_only_second"]=all(t in take(sfc_server," acceptInput(") for t in ("SfcInputHealth.packet","iconst_1","Method release:","Method stop:")) and all(t in take(sfc_server," tick(") for t in ("SfcInputHealth.expiredPort","Method release:","Method stop:"))
    checks["candidate_not_committed_before_applied"]=all(t in take(sfc_server," joinApplied(") for t in ("SfcJoinGate.commit","SfcHomeServer$Session.ports","SfcHomeServer$Joining.second"))
    playback=code("sfc","cn.piq.sfchome.client.SfcPlayback")
    checks["worker_snapshot_and_restore_no_new_core"]=all(t in playback for t in ("Method run:","SfcCore.loadState","SfcCore.saveState","SfcClientFiles.hash","SfcJoinClient.captured")) or all(t in playback for t in ("loadState","saveState","SfcClientFiles.hash","SfcJoinClient.captured","Field restore:","Field capture:"))
    checks["sfc_new_hardware_mesh_used"]=all(t in code("sfc","cn.piq.sfchome.client.SfcHardwareRenderer") for t in ("SfcHardwareMesh.draw","SfcModelPresentation.visible","SfcCartridgeRenderer.label"))
    checks["sfc_item_new_mesh_preserves_dynamic_cover"]=all(t in code("sfc","cn.piq.sfchome.client.SfcCartridgeRenderer") for t in ("SfcHardwareMesh.draw","SfcCoverGeometry.label","SfcCartridgeCovers.texture"))
    require(all(checks.values()),"Packaged alpha18 contracts failed: "+repr([n for n,ok in checks.items() if not ok]))
    return {"checks":checks,"unchanged_method_counts":stable_methods}


def packaged_probe(jar, javap, source_name, main_class, threshold, extras=()):
    source = ROOT / "tools/probes" / source_name
    with tempfile.TemporaryDirectory(prefix="piq-alpha18-final-probe-") as directory:
        temporary = Path(directory)
        empty = temporary / "empty-source"; empty.mkdir()
        output = temporary / "classes"; output.mkdir()
        run([javap.with_name("javac.exe"), "-encoding", "UTF-8", "-proc:none", "-sourcepath", empty,
             "-cp", os.pathsep.join(map(str,(jar,*extras))), "-d", output, source])
        compiled = sorted(p.relative_to(output).as_posix() for p in output.rglob("*.class"))
        require(compiled == [main_class.replace(".", "/") + ".class"], "Probe compiled/shadowed production classes")
        result = json.loads(run([javap.with_name("java.exe"), "-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8",
            "-Dstderr.encoding=UTF-8", "-cp", os.pathsep.join(map(str,(jar,output,*extras))), main_class, jar], 60))
        require(result.get("ok") is True and result.get("assertions", 0) >= threshold
                and result.get("production_origin") == "final-jar-only"
                and result.get("minecraft_or_native_core_started") is False, "Insufficient packaged probe coverage")
        return result


def negative_tests(paths):
    """Real in-memory ZIP mutations only. Never writes an altered production JAR."""
    cases=(
        ("fc","old_texture_bytes","assets/piq_fc_arcade/textures/block/famicom_controller_1.png","flip"),
        ("fc","old_wasm_core_bytes","core/nes_rust_wasm_bg.wasm","flip"),
        ("fc","unauthorized_rom","roms/forbidden-audit-rom.nes","add"),
        ("fc","packaged_test_class","cn/piq/fcarcade/client/ui/ForbiddenTest.class","add"),
        ("fc","wrong_mod_version",META,"version"),
        ("fc","unauthorized_old_entry_deletion","core/nes_rust_wasm_bg.wasm","delete"),
        ("sfc","unreviewed_new_hardware_mesh","assets/piq_sfc_home/meshes/sfc_hardware.json","flip"),
        ("sfc","old_fc_dependency_range",META,"dependency"),
    )
    results=[]
    for key,label,target,operation in cases:
        contract=CONTRACTS[key]
        with zipfile.ZipFile(paths[key]) as original,zipfile.ZipFile(contract["baseline"]) as previous:
            changed={}
            if operation=="flip":
                raw=original.read(target);require(bool(raw),"Negative-test fixture is empty")
                changed[target]=bytes((raw[0]^1,))+raw[1:]
            elif operation=="add":
                changed[target]=b"Audit negative fixture, not a ROM or production class."
            elif operation=="version":
                raw=original.read(target);changed[target]=raw.replace(contract["version"].encode(),b"0.0.0-unapproved")
                require(raw!=changed[target],"Negative version mutation had no effect")
            elif operation=="dependency":
                raw=original.read(target);changed[target]=raw.replace(b"[0.31.0-alpha.18,0.32.0)",b"[0.31.0-alpha.17,0.32.0)")
                require(raw!=changed[target],"Negative dependency mutation had no effect")
            buffer=io.BytesIO()
            with zipfile.ZipFile(buffer,"w",compression=zipfile.ZIP_STORED) as mutated:
                for info in original.infolist():
                    if operation=="delete" and info.filename==target:continue
                    mutated.writestr(info.filename,changed.pop(info.filename,original.read(info.filename)))
                for name,data in changed.items():mutated.writestr(name,data)
            buffer.seek(0);rejected=None
            try:
                with zipfile.ZipFile(buffer) as malicious:delta(malicious,previous,contract)
            except ValueError as failure:rejected=str(failure)
            require(rejected is not None,"Checker accepted negative fixture: "+label)
            results.append({"case":label,"rejected":True,"reason":rejected[:500]})
    return {"in_memory_only":True,"all_rejected":True,"cases":results}


def inspect(paths, hashes, javap):
    initial = {}
    for key, contract in CONTRACTS.items():
        require(re.fullmatch(r"[A-Fa-f0-9]{64}", hashes[key]) is not None, "Three final SHA256 values are mandatory")
        require(paths[key].name == contract["id"] + "-" + contract["version"] + ".jar", "Wrong final filename: " + key)
        require(sha(paths[key]) == hashes[key].upper(), "Final SHA mismatch: " + key)
        require(sha(contract["baseline"]) == contract["sha"], "Frozen baseline changed: " + key)
        initial[key] = sha(paths[key])
    require(sha(SFC_CORE) == SFC_CORE_SHA, "Old SFC alpha6 core changed")
    report = {"schema": 1, "audit": "alpha18-device-ui-rightclick-and-sfc-hardware-join", "validated_at_utc": datetime.now(timezone.utc).isoformat(), "archives": {}}
    for key, contract in CONTRACTS.items():
        with zipfile.ZipFile(paths[key]) as archive, zipfile.ZipFile(contract["baseline"]) as previous:
            report["archives"][key] = {"path": str(paths[key]), "sha256": initial[key], "bytes": paths[key].stat().st_size,
                "entries": len(archive.infolist()), "baseline": str(contract["baseline"]), "baseline_sha256": contract["sha"],
                "delta": delta(archive, previous, contract)}
    report["bytecode_checks"] = bytecode_checks(paths, javap)
    report["negative_mutation_tests"] = negative_tests(paths)
    report["immersive_input_and_owner_probe"] = packaged_probe(paths["fc"], javap, "Alpha17ImmersiveProbe.java", "cn.piq.fcarcade.client.cabinet.Alpha17ImmersiveProbe", 145000)
    report["retained_cabinet_probe"] = packaged_probe(paths["fc"], javap, "Alpha15CabinetProbe.java", "cn.piq.fcarcade.cabinet.Alpha15CabinetProbe", 22000)
    report["actual_device_layout_and_picker_probe"] = packaged_probe(paths["fc"], javap, "Alpha18PickerProbe.java", "cn.piq.fcarcade.client.rom.Alpha18PickerProbe", 50000)
    report["sfc_draft_paging_and_cancellation_probe"] = packaged_probe(paths["sfc"], javap, "Alpha16SfcPickerProbe.java", "cn.piq.sfchome.client.Alpha16SfcPickerProbe", 1000)
    report["sfc_startup_and_player_policy_probe"] = packaged_probe(paths["sfc"], javap, "Alpha18SfcStartupProbe.java", "cn.piq.sfchome.client.Alpha18SfcStartupProbe", 7000)
    report["sfc_new_cover_geometry_probe"] = packaged_probe(paths["sfc"], javap, "Alpha17SfcCoverProbe.java", "cn.piq.sfchome.client.Alpha17SfcCoverProbe", 20)
    report["sfc_join_transaction_probe"] = packaged_probe(paths["sfc"], javap, "Alpha18SfcJoinProbe.java", "cn.piq.sfchome.server.Alpha18SfcJoinProbe", 380)
    report["sfc_per_port_health_probe"] = packaged_probe(paths["sfc"], javap, "Alpha18SfcHealthProbe.java", "cn.piq.sfchome.server.Alpha18SfcHealthProbe", 35000)
    gson_candidates=sorted(Path("C:/Users/13498/.gradle/caches/modules-2/files-2.1/com.google.code.gson/gson").rglob("gson-2.10.1.jar"))
    require(bool(gson_candidates),"Cached Gson dependency is required for final-JAR mesh probe")
    gson=gson_candidates[0]
    report["sfc_hardware_mesh_and_pose_probe"] = packaged_probe(paths["sfc"], javap, "Alpha18SfcHardwareProbe.java", "cn.piq.sfchome.client.Alpha18SfcHardwareProbe", 500000,(gson,))
    report["probe_support_dependency"]={"path":str(gson),"sha256":sha(gson),"purpose":"Gson only; no production class override"}
    with tempfile.TemporaryDirectory(prefix="piq-alpha18-network-audit-") as folder:
        network_report=Path(folder)/"result.json"
        run([sys.executable,ROOT.parent/"piq-sfc-home/tools/check_sfc_join_packets.py","--jar",paths["sfc"],"--fc",paths["fc"],"--report",network_report],60)
        result=json.loads(network_report.read_text(encoding="utf-8"))
        require(result.get("passed") is True and result.get("mode")=="built-jar" and result.get("jar_sha256","").upper()==initial["sfc"],"Join codec probe did not use actual final SFC JAR")
        report["sfc_actual_packet_codecs"]=result
    report["probe_migrations"]=[
        "Alpha16PickerProbe old footer+59 assertion is replaced, not weakened: Alpha18PickerProbe checks actual DeviceLayout/DeviceFormLayout/CabinetMenuLayout and every new status/details region; all original file safety tests retained.",
        "Alpha17SfcStartupProbe forced WAIT_FOR_SECOND expectation is replaced by explicit SINGLE-until-approved policy for every original input combination; all startup timing/input replay tests retained.",
        "Alpha16SfcPickerProbe remains a retained draft/cancellation/legacy-helper regression only; actual SFC UI now consumes FC DeviceLayout covered by Alpha18PickerProbe and bytecode wiring.",
        "Alpha17SfcCoverProbe remains valid; Alpha18SfcHardwareProbe additionally measures actual final mesh against the dynamic cover plane and new first-person pose."
    ]
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
