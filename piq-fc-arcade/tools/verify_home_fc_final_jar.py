"""Independent, read-only home FC final-JAR audit. Never repacks or extracts the JAR.

The expected final JAR hash must be supplied explicitly. Alpha.4/5 use their frozen
manifest hash by default; an optional explicit hash must match that fixed review.
Only --report writes a JSON audit, and only inside the selected version's preview folder.
--check-only prints results without writing. DLL/WASM here means byte/header
verification, not execution; the root agent performs packaged runtime smoke.
"""
from __future__ import annotations

import argparse
import copy
from collections import Counter
from datetime import datetime, timezone
import hashlib
import io
import json
from pathlib import Path
import re
import struct
import subprocess
import tomllib
import zipfile

from PIL import Image
import numpy as np

ROOT = Path(__file__).resolve().parents[1]
DELIVERY = ROOT.parent / "制作Mod/03-街机模拟/PIQ-FC街机"
ASSETS = ROOT / "src/main/resources"
ALPHA2_MANIFEST = ROOT / "tools/home-fc-alpha2-final-reviewed-assets.json"
ALPHA3_MANIFEST = ROOT / "tools/home-fc-alpha3-final-reviewed-assets.json"
ALPHA4_MANIFEST = ROOT / "tools/home-fc-alpha4-final-reviewed-assets.json"
ALPHA5_MANIFEST = ROOT / "tools/home-fc-alpha5-final-reviewed-assets.json"
ALPHA6_MANIFEST = ROOT / "tools/home-fc-alpha6-final-reviewed-assets.json"
ALPHA7_MANIFEST = ROOT / "tools/home-fc-alpha7-final-reviewed-assets.json"
ALPHA8_MANIFEST = ROOT / "tools/home-fc-alpha8-final-reviewed-assets.json"
ALPHA9_INITIAL_MANIFEST = ROOT / "tools/home-fc-alpha9-final-reviewed-assets.json"
ALPHA9_INITIAL_MANIFEST_SHA = "DAE926EBEC54220DCF5BF2C4A6276E29C7271DF1297CC27871E3E1B8CA066748"
ALPHA9_MANIFEST = ROOT / "tools/home-fc-alpha9-final-reviewed-assets-v2.json"
# Independently checked: 57 paths, 46 retained, one header changed, ten new assets.
ALPHA9_MANIFEST_SHA = "08E9FFFD36EB13B2A5428305DF543C928FB417A1FF320CCE77663BF0CB2B9B73"
ALPHA8_JAR_SHA = "1A3C29126F4B1C06887367F9BB884D4A6188E9AEF40718EA78BCA29A8F3212A2"
ALPHA8_MANIFEST_SHA = "4CE2ABC1732C3909D75341F852D20332DDE29A27EB0E0961F50AB8553EC7BFE7"
ALPHA7_JAR_SHA = "B2DAF27EA28F33A7DFA5138E6D324BE15BECDE140305C6FD280B3542F3FA52A4"
# Fixed only after independent 47-path/45-unchanged review and numeric geometry checks.
ALPHA7_MANIFEST_SHA = "0984715042265467B50251EC043A0E77832C8DA8563A53D79894B5EDCD27AF32"
ALPHA6_MANIFEST_SHA = "39783A052DA1AAC658A3C2597CB1A2BCD91390C4E6FA31740BEDC044DC8ED297"
ALPHA6_JAR_SHA = "BF4DF8ECC2F7D4F542ADB3843CAE35AB446B11809A8DD3AF35FAB50906D0FE78"
ALPHA5_MANIFEST_SHA = "A7B169ED12C753154BF9C4B607EDD5E9D14B6447506B11F41948D41C8A69BD93"
ALPHA5_JAR_SHA = "9D8883581620C055CDC421D18B20559A169B30BB32C8D3C167CB9E6D2E780185"
ALPHA4_MANIFEST_SHA = "0CC1BF7B6D1EA7CF74E9F265487F79978868534848A99C3CE4E291F974A9B8C5"
ALPHA4_JAR_SHA = "BD1BBA85F405A220154BE9C2A32C1322BCA2414098D0F9451B3CBF5F76E2E54F"
ALPHA3_MANIFEST_SHA = "79546C62BB8D3A0282DF9AF16D870AE190CDA4DFD3D420916B8909EE6011C5F5"
ALPHA3_JAR_SHA = "1758B328069F2E68001AE10CF06E04A7704BC4A1C2200308CA3ABA1DC78F22B9"
REVIEWS = {
    "0.31.0-alpha.3": {"protocol": 23, "manifest": ALPHA3_MANIFEST},
    "0.31.0-alpha.4": {"protocol": 24, "manifest": ALPHA4_MANIFEST},
    "0.31.0-alpha.5": {"protocol": 25, "manifest": ALPHA5_MANIFEST},
    "0.31.0-alpha.6": {"protocol": 26, "manifest": ALPHA6_MANIFEST},
    "0.31.0-alpha.7": {"protocol": 26, "manifest": ALPHA7_MANIFEST},
    "0.31.0-alpha.8": {"protocol": 26, "manifest": ALPHA8_MANIFEST},
    "0.31.0-alpha.9": {"protocol": 27, "manifest": ALPHA9_MANIFEST},
}
CONTROLLER_ITEM = "assets/piq_fc_arcade/models/item/fc_controller.json"
ENUM_EXTENSIONS = "META-INF/piq-fc-controller-enumextensions.json"
ARM_POSE_PARAMETER_CLASS = "cn/piq/fcarcade/client/ControllerArmPoseParameters"
ARM_POSE_FIELDS = {
    "PIQ_FC_ARCADE_CONTROLLER_TWO_HANDS": "TWO_HANDS",
    "PIQ_FC_ARCADE_CONTROLLER_SINGLE_HAND": "SINGLE_HAND",
}
ALPHA2_MANIFEST_SHA = "1D928AB5673508728FDBBCB15BE145A35796982994ED0CDF9ECCFA029A6EF33A"
BETA3_SHA = "253D0F6433F2BA901447E05C9A0FB5183ACAB911BD560FCD8BBBFF2998619503"
SKIN = "assets/piq_fc_arcade/textures/block/home_fc_cartridge_skin.png"
EXTRA_ASSETS = {
    "assets/piq_fc_arcade/models/block/home_console_body.json": "989E0372E7E22CDB8680C3F1CC437A7C23C35568C72FA7E47A0DBF35FE46F2EC",
    "assets/piq_fc_arcade/models/block/home_controller_p1_docked.json": "321A2FF24782F0DE6F687D6170DA32EB51C514B96E811C8701F2E898DA0451F5",
    "assets/piq_fc_arcade/models/block/home_controller_p2_docked.json": "37858B19B6C9E7ADC664C308C2EC2FD2E0C7E243D30A4EA18BC58E0C0190DF67",
    "assets/piq_fc_arcade/models/block/home_controller_p1_held.json": "65855F6C9DAB5BB8B75001F5D2E85FA99141F12E5948B855B55478465CFBECDE",
    "assets/piq_fc_arcade/models/block/home_controller_p2_held.json": "5AD06FAAFC693E105F4985B7DBF714A5A394F11E648E7878ACF0A448F785E39A",
    "assets/piq_fc_arcade/models/item/fc_controller.json": "5A776D309EBF93559158A872A93552B8D7A31729442505D43B0212D024351D5F",
    "assets/piq_fc_arcade/blockstates/famicom_console.json": "34DC5755EBF504D284F18E89813EACA297891C48C8498F5460694CFCDCCE2A5D",
}
DRAFTS = (
    "assets/piq_fc_arcade/textures/block/famicom_controller_1.png",
    "assets/piq_fc_arcade/textures/block/famicom_controller_2.png",
)
RUNTIME = {
    "core/nes_rust_wasm_bg.wasm": "110711E30B64444414A8BE2D0A3B1AB45A442CC9B9452AC7D74DAAB508C933FF",
    "natives/windows-x86_64/wasmtime4j.dll": "AB44831073D409ED84FEE85746B81226FEB28DFE5B29EF179FAC6EAC4ECB65EB",
}
PRESERVED = {
    ROOT / "tools/home-fc-reviewed-assets.json": "161C254C631148DBF6567E630E5D3E47ABDBF8BC2419B66655DA075300DFAC00",
    ROOT / "tools/home-fc-alpha2-reviewed-assets.json": "1CD976F4299AFE9E9AB2310716CF8523001E71392E29103BB55A9653EEE9F974",
    ALPHA2_MANIFEST: ALPHA2_MANIFEST_SHA,
    DELIVERY / "piq_fc_arcade-0.31.0-alpha.1.jar": "7574CDEF4896467E7B94CE65C519E1DB7D425D8686D8FC8C1BB964540FA1928A",
    DELIVERY / "piq_fc_arcade-0.31.0-alpha.2.jar": "A928C4FBC984E756CFA939FEA8A60DB6000CCBB73C6AD19DE3B7413148F89D00",
    DELIVERY / "piq_fc_arcade-0.30.0-beta.3.jar": BETA3_SHA,
}
REQUIRED_CLASSES = (
    "FcArcadeMod", "FcNetwork", "storage/FcStoragePaths", "storage/FcStoragePaths$Area",
    "client/ClientFcDirectories", "client/LocalArcadePreferences", "client/FcMenuLayout",
    "client/ClientCartridgeCovers", "client/ClientCartridgeEditor", "client/RomLibraryScreen",
    "client/HomeHardwareRenderLayout", "client/HomeHardwareRenderer", "client/HomeHardwareRenderer$TvRenderer",
    "client/HomeHardwareRenderer$ControllerItemRenderer", "client/HomeHardwareRenderer$CartridgeItemRenderer",
    "home/AvCableItem", "home/CartridgeCoverCodec", "home/CartridgeCoverRepository", "home/CartridgeNetwork",
    "home/CartridgeEditBinding", "home/CartridgeLimits", "home/CartridgeTransfer", "home/CartridgeTransferBudget",
    "home/FcCartridgeData", "home/FcCartridgeItem", "home/FcControllerItem", "home/HomeControllerData",
    "home/HomeControllerInventory", "home/HomeControllerLedger", "home/HomeControllerService",
    "home/HomeCartridgeSlot", "home/HomeConsoleBlockEntity", "home/HomeEndpointBlockEntity", "home/HomeHardware",
    "home/HomeHardwareScale", "home/HomeLinkData", "home/HomeLinkLedger", "home/HomeTvAssemblyData",
    "home/HomeTvAssemblyLedger", "home/HomeTvBlockEntity", "home/HomeTvFootprint", "home/HomeTvPartBlock",
    "home/HomeTvPartBlockEntity", "home/HomeTvRemovalGate", "home/HomeTvStructure", "home/RetroTvBlock", "home/RetroTvBlockItem",
    "registry/CreativeTabCatalog", "registry/ModBlockEntities", "registry/ModBlocks", "registry/ModCreativeTabs", "registry/ModItems",
    "server/ServerCartridgeService", "server/ServerArcadeSessions", "core/NesCore", "core/wasm/WasmNesCore",
)
ALPHA4_CLASSES = ("client/ControllerPose", "client/ControllerPoseLayout", "client/ControllerArmPoseParameters")
ALPHA8_CLASSES = ("layout/ScreenAspectFit", "layout/ScreenAspectFit$Aspect", "layout/DualScreenPresentation")
ALPHA9_CLASSES = ("client/ControllerButtonAnimation", "client/ControllerButtonGeometry", "client/ControllerButtonRenderer",
                  "client/ClientControllerAnimation", "client/ClientCartridgeAssembly", "server/ServerCartridgeAssemblyService",
                  "home/CartridgeAssemblyBinding", "home/CartridgeAssemblyGate", "home/CartridgeAssemblyInventory", "home/CartridgeAssemblyMetadata", "home/CartridgeParts",
                  "home/FcCartridgeBoardItem", "home/FcCartridgeShellItem", "layout/WideLcdPresentation",
                  "home/WideLcdTvBlock", "home/WideLcdTvBlockItem", "home/WideLcdTvLayout", "home/WideLcdTvFootprint",
                  "home/WideLcdTvPartBlock", "home/WideLcdTvPartBlockEntity", "home/WideLcdTvAssemblyData",
                  "home/WideLcdTvAssemblyLedger", "home/WideLcdTvRemovalGate", "home/WideLcdTvStructure")
ALPHA5_CLASSES = ("client/SuborHardwareMesh", "client/HomeHardwareRenderer$SuborItemRenderer",
                  "home/HomeConsoleLayout", "home/HomeConsoleLayout$Bounds", "home/SuborConsoleBlock", "home/HomeControllerData$Style")
ALPHA6_CLASSES = ("client/HomeAvCableLayout", "client/HomeAvCableMesh", "client/HomeAvCableMesh$Quad", "client/HomeAvCableRenderer",
                  "home/SuborFootprint", "home/SuborAssemblyLedger", "home/SuborAssemblyData",
                  "home/SuborRemovalGate", "home/SuborStructure", "home/SuborPartBlock",
                  "home/SuborPartBlockEntity", "home/SuborConsoleBlockItem", "home/LcdTvBlock", "home/LcdTvLayout",
                  "world/DualCabinetBlock", "world/DualCabinetBlockEntity", "world/DualCabinetBlockItem",
                  "world/DualCabinetPartBlock", "world/DualCabinetPartBlockEntity", "world/DualCabinetFootprint",
                  "world/DualCabinetAssemblyLedger", "world/DualCabinetAssemblyData", "world/DualCabinetRemovalGate",
                  "world/DualCabinetStructure", "client/DualCabinetRenderer", "client/DualCabinetRenderer$ItemRenderer",
                  "client/DualCabinetRenderer$SkinUv", "layout/DualCabinetGeometry")
SUBOR_ASSETS = {
    "assets/piq_fc_arcade/models/item/subor_console.json",
    "assets/piq_fc_arcade/models/block/subor_console.json",
    "assets/piq_fc_arcade/blockstates/subor_console.json",
    "assets/piq_fc_arcade/textures/block/home_subor_sb926.png",
    "assets/piq_fc_arcade/meshes/home_subor_sb926.json",
}
SUBOR_MESH = "assets/piq_fc_arcade/meshes/home_subor_sb926.json"
SUBOR_WIDE_MESH = "assets/piq_fc_arcade/meshes/home_subor_sb926_wide.json"
SUBOR_PART_STATE = "assets/piq_fc_arcade/blockstates/subor_part.json"
DUAL_BODY = "assets/piq_fc_arcade/models/block/dual_arcade_body.json"
LCD_BODY = "assets/piq_fc_arcade/models/block/home_lcd_tv.json"
ALPHA7_CHANGED_ASSETS = {SUBOR_WIDE_MESH, DUAL_BODY}
ALPHA8_CHANGED_ASSETS = {SUBOR_WIDE_MESH, DUAL_BODY}
ALPHA9_ADDED_ASSETS = {"assets/piq_fc_arcade/" + path for path in (
    "models/block/home_wide_lcd_tv.json", "models/item/wide_lcd_tv.json",
    "blockstates/wide_lcd_tv.json", "blockstates/wide_lcd_tv_part.json",
    "models/block/home_fc_board_0.json", "models/block/home_fc_board_1.json", "models/block/home_fc_board_2.json",
    "models/block/home_fc_cartridge_shell.json", "models/item/fc_cartridge_board.json", "models/item/fc_cartridge_shell.json")}
ALPHA6_ASSETS = {SUBOR_WIDE_MESH, SUBOR_PART_STATE, DUAL_BODY, LCD_BODY,
                "assets/piq_fc_arcade/models/block/dual_cabinet.json",
                "assets/piq_fc_arcade/blockstates/dual_cabinet.json", "assets/piq_fc_arcade/blockstates/dual_cabinet_part.json",
                "assets/piq_fc_arcade/models/item/dual_cabinet.json", "assets/piq_fc_arcade/models/item/lcd_tv.json",
                "assets/piq_fc_arcade/blockstates/lcd_tv.json"}
DUAL_SOURCE_ARCHIVE = ROOT.parent / "backups/fc-rocket-model-20260908/user-models-original.zip"
DUAL_SOURCE_SHA = "E3CF2113E30D81FFA6B96411805B804044A4D5DF05799488F3249B1C4B35E728"
LEGACY_BODY = "assets/piq_fc_arcade/models/block/rocket_arcade_body.json"
LEGACY_BODY_SHA = "E4BB95E7EBBB6B989070B952E7BA5D90CF1B16D8A00078BEE513A39344ED98F7"
LEGACY_TEXTURE = "assets/piq_fc_arcade/textures/block/rocket_arcade_skin.png"
LEGACY_TEXTURE_SHA = "789512ED7F867C015C6666D40809845DE430E85834CCF7BA4E48BCA57DE815E8"
SUBOR_TEXTURE = "assets/piq_fc_arcade/textures/block/home_subor_sb926.png"
SUBOR_SOURCE = DELIVERY / "小霸王SB926模型草案-v1/小霸王SB926_完整套装.bbmodel"
SUBOR_SOURCE_TEXTURE = DELIVERY / "小霸王SB926模型草案-v1/小霸王_统一UV.png"
SUBOR_SOURCE_SHA = "A113F1BD0A9EDCED23129EBE9725B0DBCAE4D12D0DB31BB4376421B20377F795"
SUBOR_TEXTURE_SHA = "39A7F6DE2FE4CB669A0CE4E88BFDAE23C234CE7F9A7314A150D13B6EB2A89C4C"
SUBOR_MESH_SHA = "02671F529B09883842970D7ECEF4C67F679821CC8B5BCAF31078353586D85D42"
SUBOR_GROUPS = {"body": (174, 2124), "p1_docked": (14, 636), "p2_docked": (14, 636),
                "p1_held": (13, 300), "p2_held": (13, 300)}


def sha(data):
    return hashlib.sha256(data).hexdigest().upper()


def file_sha(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest().upper()


def appearance(name, include_meshes=False):
    categories = "models|textures|blockstates|meshes" if include_meshes else "models|textures|blockstates"
    return bool(re.match(r"^assets/piq_fc_arcade/(" + categories + r")/.+[^/]$", name))


def duplicate_entries(archive):
    return {name: count for name, count in Counter(archive.namelist()).items() if count != 1}


def asset_checks(archive, expected):
    counts = Counter(archive.namelist())
    result = []
    for name, digest in sorted(expected.items()):
        actual = sha(archive.read(name)) if counts[name] == 1 else None
        result.append({"path": name, "count": counts[name], "expected_sha256": digest.upper(),
                       "actual_sha256": actual, "ok": counts[name] == 1 and actual == digest.upper()})
    return result


def pe_x64(data):
    if len(data) < 64 or data[:2] != b"MZ":
        return False
    offset = struct.unpack_from("<I", data, 0x3C)[0]
    return offset + 6 <= len(data) and data[offset:offset + 4] == b"PE\0\0" and struct.unpack_from("<H", data, offset + 4)[0] == 0x8664


def mod_version(data):
    parsed = tomllib.loads(data.decode("utf-8"))
    mods = [entry for entry in parsed.get("mods", []) if entry.get("modId") == "piq_fc_arcade"]
    return mods[0].get("version") if len(mods) == 1 else None


def review_for(version):
    if version not in REVIEWS:
        raise ValueError("Unsupported final review version: " + version)
    return REVIEWS[version]


def reviewed_manifest_sha(version, supplied=None):
    review_for(version)
    if version == "0.31.0-alpha.9":
        if not ALPHA9_MANIFEST_SHA:
            raise ValueError("Alpha.9 review is not frozen; no provisional resource hash is accepted")
        if supplied and supplied.upper() != ALPHA9_MANIFEST_SHA:
            raise ValueError("Alpha.9 manifest SHA must match the immutable frozen review")
        return ALPHA9_MANIFEST_SHA
    if version == "0.31.0-alpha.8":
        if not ALPHA8_MANIFEST_SHA:
            raise ValueError("Alpha.8 review is not frozen; no provisional resource hash is accepted")
        if supplied and supplied.upper() != ALPHA8_MANIFEST_SHA:
            raise ValueError("Alpha.8 manifest SHA must match the immutable frozen review")
        return ALPHA8_MANIFEST_SHA
    if version == "0.31.0-alpha.7":
        if not ALPHA7_MANIFEST_SHA:
            raise ValueError("Alpha.7 review is not frozen; no provisional resource hash is accepted")
        if supplied and supplied.upper() != ALPHA7_MANIFEST_SHA:
            raise ValueError("Alpha.7 manifest SHA must match the immutable frozen review")
        return ALPHA7_MANIFEST_SHA
    if version == "0.31.0-alpha.6":
        if not ALPHA6_MANIFEST_SHA:
            raise ValueError("Alpha.6 review is not frozen; no provisional resource hash is accepted")
        if supplied and supplied.upper() != ALPHA6_MANIFEST_SHA:
            raise ValueError("Alpha.6 manifest SHA must match the immutable frozen review")
        return ALPHA6_MANIFEST_SHA
    if version == "0.31.0-alpha.5":
        if supplied and supplied.upper() != ALPHA5_MANIFEST_SHA:
            raise ValueError("Alpha.5 manifest SHA must match the immutable frozen review")
        return ALPHA5_MANIFEST_SHA
    if version == "0.31.0-alpha.4":
        if supplied and supplied.upper() != ALPHA4_MANIFEST_SHA:
            raise ValueError("Alpha.4 manifest SHA must match the immutable frozen review")
        return ALPHA4_MANIFEST_SHA
    if not supplied or not re.fullmatch(r"[0-9A-Fa-f]{64}", supplied):
        raise ValueError("Prior reviews require an explicit --manifest-sha256")
    return supplied.upper()


def validate_manifest(manifest, version="0.31.0-alpha.3"):
    review = review_for(version)
    if version == "0.31.0-alpha.9":
        reviewed_manifest_sha(version)
    if version in ("0.31.0-alpha.6", "0.31.0-alpha.7", "0.31.0-alpha.8", "0.31.0-alpha.9"):
        reviewed_manifest_sha(version)
    if file_sha(ALPHA2_MANIFEST) != ALPHA2_MANIFEST_SHA:
        raise ValueError("Frozen alpha.2 manifest changed")
    previous = json.loads(ALPHA2_MANIFEST.read_bytes())["assets"]
    assets = manifest.get("assets", {})
    if manifest.get("version") != version or manifest.get("protocol") != review["protocol"]:
        raise ValueError(f"Manifest must be reviewed {version}/protocol {review['protocol']}")
    if not isinstance(assets, dict) or any(not isinstance(value, str) or not re.fullmatch(r"[0-9A-Fa-f]{64}", value) for value in assets.values()):
        raise ValueError("Manifest contains an invalid SHA256")
    if version == "0.31.0-alpha.9":
        if file_sha(ALPHA8_MANIFEST) != ALPHA8_MANIFEST_SHA:
            raise ValueError("Frozen alpha.8 manifest changed")
        previous = validate_manifest(json.loads(ALPHA8_MANIFEST.read_bytes()), "0.31.0-alpha.8")
        if len(assets) != 57 or set(assets) != set(previous) | ALPHA9_ADDED_ASSETS:
            raise ValueError("Alpha.9 requires exactly 47 inherited paths and ten reviewed new LCD/PCB/shell assets")
        if {name for name, value in previous.items() if assets[name].upper() != value.upper()} != {DUAL_BODY}:
            raise ValueError("Alpha.9 changes only inherited dual header body; other 46 old assets and all PNGs stay exact")
        return assets
    if version == "0.31.0-alpha.8":
        reviewed_manifest_sha(version)
        if file_sha(ALPHA7_MANIFEST) != ALPHA7_MANIFEST_SHA:
            raise ValueError("Frozen alpha.7 manifest changed")
        previous = validate_manifest(json.loads(ALPHA7_MANIFEST.read_bytes()), "0.31.0-alpha.7")
        if len(assets) != 47 or set(assets) != set(previous):
            raise ValueError("Alpha.8 must retain precisely 47 alpha.7 appearance paths")
        if {name for name, value in previous.items() if assets[name].upper() != value.upper()} != ALPHA8_CHANGED_ASSETS:
            raise ValueError("Alpha.8 may change only compact Subor mesh and native-size dual cabinet; other 45 assets stay exact")
        return assets
    if version == "0.31.0-alpha.7":
        if file_sha(ALPHA6_MANIFEST) != ALPHA6_MANIFEST_SHA:
            raise ValueError("Frozen alpha.6 manifest changed")
        previous = validate_manifest(json.loads(ALPHA6_MANIFEST.read_bytes()), "0.31.0-alpha.6")
        if len(assets) != 47 or set(assets) != set(previous):
            raise ValueError("Alpha.7 must retain exactly the 47 frozen alpha.6 appearance paths")
        changed = {name for name, value in previous.items() if assets[name].upper() != value.upper()}
        if changed != ALPHA7_CHANGED_ASSETS:
            raise ValueError("Alpha.7 must change only the slim Subor wide mesh and lowered dual body; other 45 assets stay exact")
        return assets
    if version == "0.31.0-alpha.6":
        if file_sha(ALPHA5_MANIFEST) != ALPHA5_MANIFEST_SHA:
            raise ValueError("Frozen alpha.5 manifest changed")
        previous = validate_manifest(json.loads(ALPHA5_MANIFEST.read_bytes()), "0.31.0-alpha.5")
        if len(assets) != 47 or set(assets) != set(previous) | ALPHA6_ASSETS:
            raise ValueError("Alpha.6 requires exactly 37 unchanged alpha.5 assets and ten reviewed wide/Dual/LCD additions")
        if any(assets[name].upper() != value.upper() for name, value in previous.items()):
            raise ValueError("Alpha.6 must retain all 37 prior assets, including the Subor item JSON and original mesh")
        return assets
    if version == "0.31.0-alpha.5":
        if file_sha(ALPHA4_MANIFEST) != ALPHA4_MANIFEST_SHA:
            raise ValueError("Frozen alpha.4 manifest changed")
        previous = validate_manifest(json.loads(ALPHA4_MANIFEST.read_bytes()), "0.31.0-alpha.4")
        if len(assets) != 37 or set(assets) != set(previous) | SUBOR_ASSETS:
            raise ValueError("Alpha.5 requires exactly 32 unchanged alpha.4 paths and five new Subor assets")
        if any(assets[name].upper() != value.upper() for name, value in previous.items()):
            raise ValueError("Alpha.5 must preserve all 32 prior assets, including the controller item pose")
        if assets[SUBOR_MESH].upper() != SUBOR_MESH_SHA or assets[SUBOR_TEXTURE].upper() != SUBOR_TEXTURE_SHA:
            raise ValueError("Subor mesh/atlas differs from the frozen independent source review")
        return assets
    if version == "0.31.0-alpha.4":
        if file_sha(ALPHA3_MANIFEST) != ALPHA3_MANIFEST_SHA:
            raise ValueError("Frozen alpha.3 manifest changed")
        previous = validate_manifest(json.loads(ALPHA3_MANIFEST.read_bytes()))
        if len(assets) != 32 or set(assets) != set(previous):
            raise ValueError("Alpha.4 must retain exactly the 32 frozen alpha.3 appearance paths")
        if any(assets[name].upper() != value.upper() for name, value in previous.items() if name != CONTROLLER_ITEM):
            raise ValueError("Only the reviewed alpha.4 controller item pose resource may change")
        return assets
    if len(assets) != 32 or set(assets) != set(previous) | set(EXTRA_ASSETS):
        raise ValueError("Expected precisely 25 previous home paths + 7 reviewed controller/body paths")
    if any(not re.fullmatch(r"[0-9A-Fa-f]{64}", value) for value in assets.values()):
        raise ValueError("Manifest contains an invalid SHA256")
    if any(assets[name].upper() != value.upper() for name, value in previous.items() if name != SKIN):
        raise ValueError("An unauthorized alpha.2 home asset changed")
    if any(assets[name].upper() != value for name, value in EXTRA_ASSETS.items()):
        raise ValueError("Controller assets differ from the independent mechanical review")
    if assets[SKIN].upper() == previous[SKIN].upper():
        raise ValueError("The newly authorized default cover has not been embedded")
    return assets


def enum_name_has_mod_prefix(name, mod_id="piq_fc_arcade"):
    # Local FML 4.0.43 EnumPrototype: name.toLowerCase(Locale.ROOT).startsWith(modId).
    return isinstance(name, str) and name.lower().startswith(mod_id)


def has_protocol_literal(disassembly, protocol):
    return bool(re.search(r"// String " + re.escape(str(protocol)) + r"\s*$", disassembly, re.MULTILINE))


def has_double_constant(disassembly, name, expected):
    match = re.search(r"\b" + re.escape(name) + r"\s*=\s*([-+0-9.eE]+)[dDfF]?\s*;", disassembly)
    return bool(match and abs(float(match[1])-expected) < 1e-12)


def method_returns_constant_true(disassembly, name, parameter):
    signature = r"public boolean " + re.escape(name) + r"\(" + re.escape(parameter) + r"\);"
    return bool(re.search(signature + r"\s+Code:\s+0:\s+iconst_1\s+1:\s+ireturn\s", disassembly))


def javap_method(disassembly, signature):
    start = disassembly.find(signature)
    if start < 0: return ""
    end = re.search(r"\n  \S", disassembly[start + len(signature):])
    return disassembly[start:start + len(signature) + end.start()] if end else disassembly[start:]


def dual_source():
    with zipfile.ZipFile(DUAL_SOURCE_ARCHIVE) as archive:
        names = [n for n in archive.namelist() if "/01_" in n and n.endswith("/arcade.json") and "/minecraft_assets/" not in n]
        if len(names) != 1: raise ValueError("Frozen original dual model must have one unique ZIP entry")
        raw = archive.read(names[0])
    if sha(raw) != DUAL_SOURCE_SHA: raise ValueError("Original dual model source SHA changed")
    return json.loads(raw)


def alpha6_asset(path):
    """Read the immutable prior release, not a source tree changed by this review."""
    jar = DELIVERY / "piq_fc_arcade-0.31.0-alpha.6.jar"
    if file_sha(jar) != ALPHA6_JAR_SHA:
        raise ValueError("Frozen alpha.6 JAR changed")
    with zipfile.ZipFile(jar) as archive:
        if archive.namelist().count(path) != 1:
            raise ValueError("Frozen alpha.6 asset missing or duplicated: " + path)
        return archive.read(path)


def alpha7_asset(path):
    """Old alpha7 acceptance always reads its frozen JAR rather than active alpha8 sources."""
    jar = DELIVERY / "piq_fc_arcade-0.31.0-alpha.7.jar"
    if file_sha(jar) != ALPHA7_JAR_SHA: raise ValueError("Frozen alpha.7 JAR changed")
    with zipfile.ZipFile(jar) as archive:
        if archive.namelist().count(path) != 1: raise ValueError("Frozen alpha.7 asset missing/duplicated: " + path)
        return archive.read(path)


def alpha8_asset(path):
    """Historical acceptance reads immutable alpha8 archive bytes, never current sources."""
    jar = DELIVERY / "piq_fc_arcade-0.31.0-alpha.8.jar"
    if file_sha(jar) != ALPHA8_JAR_SHA: raise ValueError("Frozen alpha.8 JAR changed")
    with zipfile.ZipFile(jar) as archive:
        if archive.namelist().count(path) != 1: raise ValueError("Frozen alpha.8 asset missing/duplicated: " + path)
        return archive.read(path)


def validate_lowered_dual_model(model, source, legacy, texture):
    """Independently lower only the authorized components from the frozen alpha.6 body."""
    from render_rocket_arcade_preview import collect_quads
    def require(condition, message):
        if not condition: raise ValueError(message)
    original = json.loads(alpha6_asset(DUAL_BODY))
    prior = validate_dual_model(original, source, legacy, texture)
    expected = copy.deepcopy(original)
    expected["credit"] = "PIQ alpha7 dual cabinet: lower cabinet shortened by 6.4 model units; unchanged 4:3 screen and two control sets shifted down; BER scale 1.5."
    def lower_shell(y):
        if y <= 1.06: return y
        if y >= 13.4: return y - 6.4
        return 1.06 + (y - 1.06) * (13.4 - 6.4 - 1.06) / (13.4 - 1.06)
    preserve = {0, 3}
    shell = {1, 2, 4, 8, 18, 22, 32, 222}
    for i, element in enumerate(expected["elements"]):
        if i in preserve: continue
        if i in shell:
            require("rotation" not in element, "Compressible dual shell must remain axis-aligned")
            for field in ("from", "to"): element[field][1] = lower_shell(element[field][1])
            continue
        shift = -4 if 202 <= i <= 216 else -.6 if 217 <= i <= 221 else -6.4
        if i in (223, 225):
            center = (element["from"][1] + element["to"][1]) / 2
            shift = lower_shell(center) - center
        for field in ("from", "to"): element[field][1] += shift
        if "rotation" in element: element["rotation"]["origin"][1] += shift
    require(len(model.get("elements", [])) == 227, "Lowered dual must preserve all 227 original components")
    require({key: value for key, value in model.items() if key != "elements"}
            == {key: value for key, value in expected.items() if key != "elements"}, "Lowered dual changes non-geometry metadata/texture/display")
    for i, (actual, target) in enumerate(zip(model["elements"], expected["elements"])):
        require(actual.keys() == target.keys(), "Lowered dual element fields changed: " + str(i))
        for key in actual:
            if key in ("from", "to"):
                require(np.allclose(actual[key], target[key], atol=1e-8, rtol=0), "Wrong lowered dual coordinates: " + str(i))
            elif key == "rotation":
                require(actual[key].keys() == target[key].keys()
                        and all(np.allclose(actual[key][k], target[key][k], atol=1e-8, rtol=0) if k == "origin"
                                else actual[key][k] == target[key][k] for k in target[key]), "Wrong lowered dual pivot: " + str(i))
            else: require(actual[key] == target[key], "Lowered dual changed UV or component properties: " + str(i))
    quads = collect_quads(model)
    points = np.concatenate([q.vertices for q in quads]) * 1.5
    low, high = points.min(0), points.max(0)
    require(np.allclose([low, high], [[.6, 0, 5.299007575951], [31.4, 38.4, 26.700992424049]], atol=1e-8, rtol=0),
            "Lowered dual is not the reviewed 2-block-wide, 2.4-block-tall body")
    screen = [q for q in quads if q.element_index == 47]
    require(len(screen) == 1, "Lowered dual screen must remain one quad")
    p = screen[0].vertices * 1.5
    old_screen = np.asarray(prior["screen_quad"])
    require(np.allclose(p, old_screen - [0, 9.6, 0], atol=1e-8, rtol=0), "Lowered dual screen was scaled/distorted instead of rigidly lowered")
    edges = np.linalg.norm(np.roll(p, -1, axis=0) - p, axis=1)
    require(np.allclose(sorted(edges), [11.835,11.835,15.78,15.78], atol=1e-8, rtol=0), "Lowered dual lost its 4:3 screen")
    eye = 1.62 * 16
    require(p[:, 1].min() < eye < p[:, 1].max(), "Normal standing eye height does not intersect the screen vertical interval")
    return {"ok": True, "elements": 227, "unchanged_uv_components": 227,
            "original_sha256": sha(alpha6_asset(DUAL_BODY)), "bounds_model_units": [low.tolist(), high.tolist()],
            "world_scale": 1.5, "height_blocks": float(high[1]/16), "screen_quad": p.tolist(), "screen_aspect": 4/3,
            "screen_normal": prior["screen_normal"], "standing_eye_height_blocks": 1.62,
            "standing_eye_within_screen": True, "top_reserved_cell_fraction": .4,
            "preserved_footprint_cells": 12, "limits": ["Offline geometry/view-height audit, not a Minecraft visual test."]}


def validate_dual_model(model, source, legacy, texture):
    """Inspect actual exported elements and UVs against frozen inputs; never rebuild assets."""
    from render_rocket_arcade_preview import collect_quads
    def require(condition, message):
        if not condition: raise ValueError(message)
    elements = model.get("elements", [])
    require(len(elements) == len(source["elements"]) == 227 and len(legacy["elements"]) == 155,
            "Dual model must retain the complete 227-element inventory")
    require(model.get("textures") == legacy["textures"] and sha(texture) == LEGACY_TEXTURE_SHA,
            "Dual atlas must be the exact existing seam-fixed skin")
    widen = set(range(8)) | set(range(36,42)) | {48,49,50,51,55}
    offset = np.array([8/3, 0, 3.150661616032783]); shift = 2.9466666666666668
    donors = []
    for i, actual in enumerate(elements):
        donor = i if i < 68 else i+10 if i < 135 else i-57 if i < 210 else i-62 if i < 217 else i-149
        donors.append(donor)
        original = legacy["elements"][i] if i < 68 else source["elements"][i]
        require(actual.get("faces") == legacy["elements"][donor]["faces"], "Dual original UV mapping changed at element " + str(i))
        low, high = np.array(original["from"], dtype=float), np.array(original["to"], dtype=float)
        delta = offset.copy()
        if i in widen: low[0] -= shift; high[0] += shift
        elif 8 <= i < 22 or i == 52 or 56 <= i < 62 or 68 <= i < 135: delta[0] += shift
        elif 22 <= i < 36 or i == 53 or 62 <= i < 68 or 135 <= i < 202: delta[0] -= shift
        elif i == 42: high[0] += shift
        elif i == 43: low[0] -= shift
        require(np.allclose(actual.get("from"), low+delta, atol=1e-9, rtol=0)
                and np.allclose(actual.get("to"), high+delta, atol=1e-9, rtol=0), "Dual shell/control transform changed at element " + str(i))
        rotate = original.get("rotation")
        if rotate:
            expected = dict(rotate, origin=(np.array(rotate["origin"])+delta).tolist())
            require(actual.get("rotation", {}).keys() == expected.keys()
                    and all(np.allclose(actual["rotation"][k], v, atol=1e-9, rtol=0) if k == "origin" else actual["rotation"][k] == v
                            for k,v in expected.items()), "Dual element rotation/pivot changed")
        else: require("rotation" not in actual, "Unexpected new dual element rotation")
        require(np.isfinite([actual["from"], actual["to"]]).all() and min(actual["from"]) >= -16
                and max(actual["to"]) <= 32, "Dual elements exceed vanilla model limits")
    quads = collect_quads(model); points = np.concatenate([q.vertices for q in quads])*1.5
    bounds = np.array([points.min(0), points.max(0)])
    require(np.allclose(bounds, [[.6,0,5.299007575951],[31.4,48,26.700992424049]], atol=1e-8, rtol=0), "Dual final 2x3x2 world bounds changed")
    screen = [q for q in quads if q.element_index == 47]
    require(len(screen) == 1, "Dual static screen must be one quad")
    p = screen[0].vertices*1.5
    edges = np.linalg.norm(np.roll(p,-1,axis=0)-p, axis=1)
    normal = np.cross(p[1]-p[0], p[2]-p[0]); normal /= np.linalg.norm(normal)
    require(np.allclose(sorted(edges),[11.835,11.835,15.78,15.78],atol=1e-8,rtol=0)
            and np.allclose(normal,[0,.3826834323650898,-.9238795325112867],atol=1e-8,rtol=0), "Dual screen lost the 4:3 aspect or outward tilted normal")
    with Image.open(io.BytesIO(texture)) as atlas: pixels=np.array(atlas.convert("RGBA"))
    uv=np.array(elements[47]["faces"]["north"]["uv"])*128
    x0,y0,x1,y1=uv
    require(pixels.shape == (2048,2048,4) and (pixels[int(np.ceil(y0)):int(np.floor(y1)),int(np.ceil(x0)):int(np.floor(x1))] == [0,0,0,255]).all(),
            "Dual static glass is not pure opaque black")
    rotated=[]
    for turn in range(4):
        angle=-np.pi/2*turn; c,s=np.cos(angle),np.sin(angle)
        r=np.array([[c,0,s],[0,1,0],[-s,0,c]])
        a=(points-[8,0,8])@r.T+[8,0,8]
        rotated.append([a.min(0).tolist(),a.max(0).tolist()])
    return {"ok":True,"elements":227,"original_uv_elements":227,"world_scale":1.5,
            "source_sha256":DUAL_SOURCE_SHA,"screen_quad":p.tolist(),"screen_normal":normal.tolist(),
            "screen_aspect":4/3,"rotation_pivot":[8,0,8],"four_facing_bounds":rotated,"atlas_donors":donors}


def validate_lcd_model(model, textures):
    from render_rocket_arcade_preview import collect_quads
    def require(condition, message):
        if not condition: raise ValueError(message)
    colors={"screen","dark","rim","back","metal","red","white","yellow"}
    expected={name:"piq_fc_arcade:block/home_retro_tv_"+name for name in colors}
    require(model.get("textures") == dict(expected, particle=expected["dark"]), "LCD must reuse only existing CRT solid materials")
    for name in colors:
        with Image.open(io.BytesIO(textures[name])) as image: p=np.array(image.convert("RGBA"))
        unique=np.unique(p.reshape(-1,4),axis=0)
        require(len(unique)==1 and unique[0,3]==255, "LCD material is no longer opaque and uniform")
        if name=="screen": require(np.array_equal(unique,[[0,0,0,255]]), "LCD glass must be opaque pure black")
    elements=model.get("elements",[])
    require(len(elements)==42, "LCD native 42-cube inventory changed")
    for element in elements:
        a=np.asarray([element.get("from"),element.get("to")],dtype=float)
        require(a.shape==(2,3) and np.isfinite(a).all() and (a[0]>=0).all() and (a[1]<=16).all()
                and (a[1]>a[0]).all() and "rotation" not in element, "LCD cube exceeds one block or has a degenerate volume")
        require(all(f.get("uv")==[0,0,16,16] and f.get("texture","")[1:] in colors for f in element.get("faces",{}).values()),
                "LCD face uses an unreviewed UV/material")
    quads=collect_quads(model); points=np.concatenate([q.vertices for q in quads])
    bounds=np.array([points.min(0),points.max(0)])
    require(np.allclose(bounds,[[0,0,5],[16,13,11]],atol=1e-9,rtol=0), "LCD one-block bounds changed")
    screen=[e for e in elements if e.get("name")=="4比3纯黑液晶屏"]
    require(len(screen)==1 and screen[0]["from"]==[1,1.5,6] and screen[0]["to"]==[15,12,6.08]
            and screen[0]["faces"]=={"north":{"uv":[0,0,16,16],"texture":"#screen"}}, "LCD screen is not the reviewed single 14x10.5 black face")
    sockets=[]
    for x,color in ((5,"yellow"),(8,"white"),(11,"red")):
        rim=[e for e in elements if e.get("name","").startswith(color+"方像素RCA")]
        require(len(rim)==4 and all(e["to"][2]==8.23 and all(f["texture"]=="#"+color for f in e["faces"].values()) for e in rim),
                "LCD RCA connector color or outward plane changed")
        # Looking straight through each center from +Z must reach the recessed
        # bottom, never a false socket painted on an uncut outer backplate.
        hits=[e["to"][2] for e in elements if e["from"][0]<x<e["to"][0] and e["from"][1]<4<e["to"][1]]
        require(hits and abs(max(hits)-7.68)<1e-9, "LCD connector center is blocked by the rear shell")
        sockets.append([x,4,8.23])
    return {"ok":True,"elements":42,"bounds":bounds.tolist(),"screen_bounds":[1,1.5,15,12,6],
            "screen_aspect":4/3,"rca_yellow_white_red":sockets,"recessed_sockets_verified":3,
            "texture_policy":"Eight inherited opaque solid CRT PNGs; no new or modified PNG"}


def validate_native_dual_model(model, source, legacy, texture):
    """Independent alpha8 export audit against frozen single-cabinet donor parts and exact screen geometry."""
    from render_rocket_arcade_preview import collect_quads
    def require(condition,message):
        if not condition:raise ValueError(message)
    require(sha(texture)==LEGACY_TEXTURE_SHA,"Native dual texture differs from frozen generic skin")
    require(len(model.get('elements',[]))==227 and model.get('textures')==legacy['textures'],"Native dual components/atlas changed")
    expected=[]
    for i,original in enumerate(source['elements']):
        donor=i if i<68 else i+10 if i<135 else i-57 if i<210 else i-62 if i<217 else i-149
        part=copy.deepcopy(legacy['elements'][i] if i<68 else legacy['elements'][donor] if i<202 else original)
        part['faces']=copy.deepcopy(legacy['elements'][donor]['faces'])
        delta=np.zeros(3)
        if 68<=i<202:
            delta=[16 if i<135 else 0,.035,0]
            part['name']=('P1 ' if i<135 else 'P2 ')+part.get('name','control')
        else:
            if i in set(range(8))|set(range(36,42))|{48,49,50,51,55}:
                part['from'][0]-=8.08;part['to'][0]+=8.08
            elif 8<=i<22 or i==52 or 56<=i<62:delta[0]+=8.08
            elif 22<=i<36 or i==53 or 62<=i<68:delta[0]-=8.08
            delta[0]+=8
        for key in ('from','to'):part[key]=(np.array(part[key])+delta).tolist()
        if 'rotation' in part:part['rotation']['origin']=(np.array(part['rotation']['origin'])+delta).tolist()
        expected.append(part)
    shapes={
        40:([1.42,16.05,4.78],[30.58,16.45,5.38]),41:([1.46,16.2,5.38],[30.54,30.26,6.0]),
        42:([28,16.15,4.8],[28.65,30.25,5.35]),43:([3.35,16.15,4.8],[4,30.25,5.35]),
        44:([4,16.15,4.8],[28,16.55,5.35]),45:([4,30.05,4.9],[28,30.25,5.35]),
        46:([3.87,16.42,5.02],[28.13,30.18,5.11]),47:([4,16.55,5],[28,30.05,5.2]),
        48:([1.24,30.05,3.03],[30.76,31.72,13.85]),50:([1.18,29.87,2.705],[30.82,30.25,4.65]),
        51:([1.18,31.55,2.705],[30.82,31.9,3.2]),52:([30.18,30.16,2.72],[30.83,31.7,3.21]),
        53:([1.17,30.16,2.72],[1.82,31.7,3.21]),55:([1.42,29.245,10.60],[30.58,30.04,11.2])}
    width=12.26*1.3/2.76
    shapes[54]=([16-width/2,30.31,2.99],[16+width/2,31.61,3.1])
    for i,(low,high) in shapes.items():
        expected[i]['from']=low;expected[i]['to']=high
        if 40<=i<=47:expected[i]['rotation']={'angle':22.5,'axis':'x','origin':[16,16.55,5],'rescale':False}
        else:expected[i].pop('rotation',None)
    expected[47]['name']='16:9宽屏黑玻璃'
    for start,x in ((56,26),(62,6)):
        center=(np.array(legacy['elements'][start]['from'])+legacy['elements'][start]['to'])/2
        for i in range(start,start+6):
            for key in ('from','to'):expected[i][key]=((np.array(legacy['elements'][i][key])-center)*.6+[x,29.57,10.31]).tolist()
    for i,(part,wanted) in enumerate(zip(model['elements'],expected)):
        require(part.keys()==wanted.keys(),"Native dual element fields changed: "+str(i))
        for key in part:
            if key in ('from','to'):require(np.allclose(part[key],wanted[key],atol=1e-8,rtol=0),"Native dual dimensions/placement mismatch: "+str(i))
            elif key=='rotation':
                require(part[key].keys()==wanted[key].keys() and all(np.allclose(part[key][k],wanted[key][k],atol=1e-8,rtol=0)
                        if k=='origin' else part[key][k]==wanted[key][k] for k in wanted[key]),"Native dual rotation/pivot mismatch: "+str(i))
            else:require(part[key]==wanted[key],"Native dual UV/part property changed: "+str(i))
    quads=collect_quads(model);points=np.concatenate([q.vertices for q in quads])
    bounds=[points.min(0).tolist(),points.max(0).tolist()]
    require(np.allclose(bounds,[[.6,0,.3820101013],[31.4,32,14.65]],atol=1e-8,rtol=0),"Dual is not native two-block height and original front depth")
    screen=[q for q in quads if q.element_index==47]
    expected_screen=np.array([[28,29.0223736889024,10.1662263369287],[28,16.55,5],
                              [4,16.55,5],[4,29.0223736889024,10.1662263369287]])
    require(len(screen)==1 and np.allclose(screen[0].vertices,expected_screen,atol=1e-8,rtol=0),"Native dual physical 16:9 glass corners changed")
    p=screen[0].vertices;edges=np.linalg.norm(np.roll(p,-1,axis=0)-p,axis=1)
    require(np.allclose(sorted(edges),[13.5,13.5,24,24],atol=1e-8,rtol=0),"Dual glass is not 24x13.5")
    return {'ok':True,'elements':227,'world_scale':1,'bounds_model_units':bounds,'height_blocks':2,
            'physical_screen_aspect':16/9,'screen_quad':p.tolist(),'unchanged_uv_components':227,
            'controls_native_scale':True,'standing_eye_within_screen':float(p[:,1].min())<25.92<float(p[:,1].max()),
            'preserved_footprint_cells':12,'limits':['Contain-fit game aspect is checked in packaged Java separately; not a gameplay test.']}


def validate_subor_wide_mesh(mesh, original, source, slim=False, compact=False):
    """Independent numeric audit of the new mesh, not an import/rebuild of its generator."""
    def require(condition, message):
        if not condition: raise ValueError(message)
    reduction = 1.2 if slim or compact else 0
    raise_y, top = (1.25, 6.3) if slim or compact else (2.45, 7.5)
    lower_bound, upper_bound = ([3.2,0,6.5],[28.8,6.3,23.8]) if compact else ([.2,0,5.2],[31.8,top,25.65])
    counts = {"body": 2732, "p1_docked": 636, "p2_docked": 636, "p1_held": 300,
              "p2_held": 300, "lid_closed": 24, "lid_open": 24}
    groups, metadata = mesh.get("groups", {}), mesh.get("metadata", {})
    require(set(groups) == set(counts), "Wide mesh needs precisely seven body/controller/lid groups")
    require(all(mesh.get(key) == original.get(key) for key in ("version", "texture", "texture_size", "units", "front")),
            "Wide mesh cannot replace the original atlas or coordinate convention")
    require(metadata.get("source_sha256") == SUBOR_SOURCE_SHA and metadata.get("alpha5_mesh_sha256") == SUBOR_MESH_SHA
            and metadata.get("texture_sha256") == SUBOR_TEXTURE_SHA, "Wide source provenance changed")
    require(metadata.get("layout_size") == [32, 16, 32] and metadata.get("keys_uniform_scale") == .94
            and metadata.get("upper_assembly_y_raise") == raise_y, "Wide world layout/keyboard transform changed")
    if compact:
        require(metadata.get("revision") == "v5-compact-chassis" and metadata.get("lower_shell_reduction") == 1.2
                and metadata.get("chassis_planar_scale") == .8 and metadata.get("chassis_planar_center") == [16,16]
                and metadata.get("slot_full_size_translation") == [0,0,-1.541]
                and metadata.get("alpha7_mesh_sha256") == sha(alpha7_asset(SUBOR_WIDE_MESH)), "Compact chassis provenance changed")
    elif slim:
        require(metadata.get("revision") == "v4-slim-lower-shell" and metadata.get("lower_shell_reduction") == 1.2,
                "Slim wide mesh lacks the reviewed bottom-only reduction provenance")
        require(metadata.get("alpha6_mesh_sha256") == sha(alpha6_asset(SUBOR_WIDE_MESH)),
                "Slim provenance does not refer to the immutable prior wide mesh")
    arrays, parts, results = {}, {}, []
    for name, count in counts.items():
        group = groups[name]; triangles = group.get("triangles", [])
        require(len(triangles) == count == group.get("triangle_count"), "Wide triangle count mismatch: " + name)
        p = np.asarray([t["p"] for t in triangles], dtype=float)
        uv = np.asarray([t["uv"] for t in triangles], dtype=float)
        normals = np.asarray([t["n"] for t in triangles], dtype=float)
        require(p.shape == (count, 3, 3) and uv.shape == (count, 3, 2) and normals.shape == (count, 3), "Malformed wide triangle dimensions")
        require(np.isfinite(p).all() and np.isfinite(uv).all() and np.isfinite(normals).all()
                and uv.min() >= 0 and uv.max() <= 1, "Wide coordinate/UV is non-finite or out of bounds")
        crosses = np.cross(p[:, 1]-p[:, 0], p[:, 2]-p[:, 0]); lengths = np.linalg.norm(crosses, axis=1)
        require((lengths > 1e-12).all() and np.allclose(normals, crosses/lengths[:, None], atol=1e-7, rtol=0),
                "Wide degenerate triangles or wrong normal winding")
        flat = p.reshape(-1, 3); bounds = np.array([flat.min(0), flat.max(0)])
        require(np.allclose(group.get("bounds"), bounds, atol=1e-8, rtol=0), "Wide recorded bounds do not describe its triangles")
        if not name.endswith("_held"):
            require((bounds[0] >= lower_bound).all() and (bounds[1] <= upper_bound).all(), "Wide world geometry exceeds its four-cell shape")
        at = 0; parts[name] = {}
        for part in group.get("parts", []):
            require(part.get("start") == at and isinstance(part.get("count"), int) and part["count"] > 0
                    and part.get("name") not in parts[name], "Wide part ranges overlap, omit triangles, or duplicate labels")
            parts[name][part["name"]] = triangles[at:at+part["count"]]; at += part["count"]
        require(at == count, "Wide part metadata does not cover every triangle")
        arrays[name] = p
        results.append({"group": name, "triangles": count, "bounds": bounds.tolist(), "parts": len(parts[name])})
    require(metadata.get("held_contract") == original["metadata"]["held_contract"], "Wide update must not change handheld controls")
    for port in (1, 2):
        name = f"p{port}_held"
        require(groups[name]["triangles"] == original["groups"][name]["triangles"], "Handheld triangles changed during wide-console update")
        dock = groups[f"p{port}_docked"]["triangles"]
        matrix = np.array([[-1, 0, 0], [0, 0, 1], [0, 1, 0]])*.6
        center = ([22.2 if port == 1 else 9.8, .18 + (.92*(12.69/7)*.6)/2, 8.5] if compact
                  else [23.75 if port == 1 else 8.25, .18 + (.92*(12.69/7)*.6)/2, 7.3])
        expected = (arrays[name]-8) @ matrix.T + center
        require(np.allclose(np.asarray([t["p"] for t in dock[:300]]), expected, atol=1e-8, rtol=0)
                and all(t["uv"] == old["uv"] for t, old in zip(dock[:300], original["groups"][name]["triangles"])),
                "Docked controller no longer preserves canonical scale, buttons, or original UV")
        require(all(t["uv"] == old["uv"] for t, old in zip(dock[300:], original["groups"][f"p{port}_docked"]["triangles"][-336:])),
                "Rerouted wide controller cable changed original UV")
    # Independently follow original face order for every retained source element.
    elements = {e["uuid"]: e for e in source["elements"]}
    slot_ids = set(source["outliner"][5]["children"])
    checked_uv = 0; inherited_labels = set()
    for element_id in [key for group in source["outliner"][:6] for key in group["children"]]:
        element = elements[element_id]; name = element["name"]
        if element_id in slot_ids or name.startswith("背部AV与电源接口"): continue
        require(name in parts["body"], "Wide mesh omitted a retained original part: " + name)
        inherited_labels.add(name); at = 0
        for face_name, face in element["faces"].items():
            if (name == "下壳" and face_name in ("f1", "f3")) or (name == "一体斜面上壳" and face_name in ("f0", "f1")): continue
            keys = face["vertices"]
            for i in range(1, len(keys)-1):
                selected = [keys[0], keys[i], keys[i+1]]
                p = np.array([element["vertices"][key] for key in selected])
                expected = p*[-.94, .94, -.94]+[16, raise_y, 18.3]
                if name.startswith("橡胶脚"): expected[:, 1] -= raise_y
                elif name == "下壳": expected[:, 1] -= np.where(p[:, 1] < .5, raise_y, 0)
                if compact:
                    expected=(expected-[16,0,16])*[.8,1,.8]+[16,0,16]
                    if name=="型号": expected[:,0]=8.48+(expected[:,0]-8.48)*.98-.70
                    elif name=="品牌": expected[:,0]+=.10
                actual = parts["body"][name][at]
                require(np.allclose(actual["p"], expected, atol=1e-8, rtol=0)
                        and np.array_equal(actual["uv"], np.array([face["uv"][key] for key in selected])/2048),
                        "Retained wide body source geometry/UV changed: " + name)
                checked_uv += 1; at += 1
        require(at == len(parts["body"][name]), "Retained wide part has extra/omitted faces")
    samples = list(metadata.get("material_uv_samples", {}).values())
    source_names = {e["name"]: e for e in source["elements"]}
    sample_faces = {"shell": "一体斜面上壳", "base": "下壳", "dark": "主键盘底盘", "yellow": "背部AV与电源接口0",
                    "white": "背部AV与电源接口1", "red": "指示灯0", "metal": "触点1"}
    expected_samples = {label: (np.mean(list(source_names[name]["faces"]["f1"]["uv"].values()), axis=0)/2048).tolist()
                        for label, name in sample_faces.items()}
    require(metadata.get("material_uv_samples") == expected_samples, "Wide material samples do not retain the original atlas tiles")
    with Image.open(SUBOR_SOURCE_TEXTURE) as atlas:
        pixels = np.array(atlas.convert("RGBA"))
    for uv in samples:
        x, y = (np.array(uv)*2048).astype(int)
        tile = pixels[y-2:y+3, x-2:x+3].reshape(-1, 4)
        require(len(tile) == 25 and len(np.unique(tile, axis=0)) == 1 and tile[0, 3] == 255,
                "Wide new-face sample is not an opaque uniform original material tile")
    for name, triangles in parts["body"].items():
        if name not in inherited_labels:
            require(all(t["uv"][0] in samples and t["uv"] == [t["uv"][0]]*3 for t in triangles), "New body face does not sample an approved original uniform tile")
    lid = metadata.get("lid", {})
    require(lid == {"closed_group": "lid_closed", "open_group": "lid_open", "hinge": [16., 3.0025 if slim or compact else 4.2025, 23.059 if compact else 24.6],
                    "open_x_degrees": 110., "mutually_exclusive": True}, "Wide lid contract changed")
    angle = np.radians(110); c, s = np.cos(angle), np.sin(angle)
    rotation = np.array([[1, 0, 0], [0, c, -s], [0, s, c]]); hinge = np.array(lid["hinge"])
    require(np.allclose(arrays["lid_open"], (arrays["lid_closed"]-hinge) @ rotation.T+hinge, atol=1e-8, rtol=0)
            and all(a["uv"] == b["uv"] for a, b in zip(groups["lid_open"]["triangles"], groups["lid_closed"]["triangles"])),
            "Open lid is not the same lid rigidly rotated around its rear hinge")
    anchors = metadata.get("anchors", {})
    av_y = 1.05 if slim or compact else 2.25
    av_z = 23.632 if compact else 25.54
    rca_x = [24.32,22.72,21.12] if compact else [26.4,24.8,23.2]
    require(anchors.get("cartridge_bottom_center") == [16., 1.52 if slim or compact else 2.72, 22.164 if compact else 23.705] and anchors.get("cartridge_render_scale") == .6
            and anchors.get("av_cable_start") == [rca_x[0], av_y, av_z]
            and anchors.get("rca_sockets") == {label:[x,av_y,av_z] for label,x in zip(("yellow","white","red"),rca_x)},
            "Wide card/RCA anchor contract changed")
    card = np.asarray(anchors.get("cartridge_inserted_bounds"))
    require(card.shape == (2, 3) and np.isfinite(card).all() and (card[0] >= lower_bound).all() and (card[1] <= upper_bound).all()
            and card[1, 2] < np.min(arrays["lid_open"][:, :, 2]), "Inserted card or open lid exceeds safe bounds/intersects")
    # Vectorized vertical rays independently rule out a painted-over slot cap.
    p = arrays["body"]; a = p[:, 0][:, [0, 2]]; ab = p[:, 1][:, [0, 2]]-a; ac = p[:, 2][:, [0, 2]]-a
    det = ab[:, 0]*ac[:, 1]-ab[:, 1]*ac[:, 0]; eligible = abs(det) > 1e-10; rays = []
    for x in (11.7, 12.5, 14, 16, 18, 19.5, 20.3):
        for z in np.array((23.1, 23.4, 23.705, 24.1, 24.3)) + (-1.541 if compact else 0):
            q = np.array([x, z])-a[eligible]; d = det[eligible]
            u = (q[:, 0]*ac[eligible, 1]-q[:, 1]*ac[eligible, 0])/d
            v = (ab[eligible, 0]*q[:, 1]-ab[eligible, 1]*q[:, 0])/d
            inside = (u >= -1e-8) & (v >= -1e-8) & (u+v <= 1+1e-8)
            selected = p[eligible]
            ys = selected[:, 0, 1]+u*(selected[:, 1, 1]-selected[:, 0, 1])+v*(selected[:, 2, 1]-selected[:, 0, 1])
            require(inside.any() and max(ys[inside]) <= 3.02-reduction+1e-6, "Wide slot has a solid cap above its recessed floor")
            rays.append({"x": x, "z": z, "highest_body_y": float(max(ys[inside]))})
    if compact:
        prior=json.loads(alpha7_asset(SUBOR_WIDE_MESH))
        require(np.allclose(groups['body']['bounds'],[[3.72736,0,12.1248],[28.27264,3.2580092,23.632]],atol=1e-8,rtol=0),
                "Compact body size differs or chassis Y was flattened")
        require(np.allclose(metadata.get('opening_xz'),[11.45,21.359,20.55,23.009],atol=1e-9)
                and metadata.get('cavity_floor_y')==1.82 and metadata.get('contact_bottom_y')==1.30,
                "Compact full-size slot changed its opening/depth")
        require(np.allclose(card,np.array(prior['metadata']['anchors']['cartridge_inserted_bounds'])+[0,0,-1.541],atol=1e-8,rtol=0),
                "Cartridge was scaled rather than translated into compact chassis")
        for group in ('lid_closed','lid_open'):
            old_points=np.array([t['p'] for t in prior['groups'][group]['triangles']])
            require(np.allclose(arrays[group],old_points+[0,0,-1.541],atol=1e-8,rtol=0),"Compact lid was shrunk or moved off the full-size slot")
    elif slim:
        prior = json.loads(alpha6_asset(SUBOR_WIDE_MESH))
        require(np.allclose(groups["body"]["bounds"], [[.6592,0,11.156],[31.3408,3.2580092,25.54]], atol=1e-8, rtol=0),
                "Slim keyboard bounds were globally flattened or did not lose the 1.2-unit base")
        for name in ("p1_docked", "p2_docked"):
            require(groups[name]["triangles"][:300] == prior["groups"][name]["triangles"][:300],
                    "Slim update changed docked controller buttons/body instead of lowering only its chassis connector")
        for name in groups:
            require(all(a["uv"] == b["uv"] for a, b in zip(groups[name]["triangles"], prior["groups"][name]["triangles"])),
                    "Slim update changed an original UV triangle: " + name)
    return {"ok": True, "groups": results, "slim_lower_shell": slim, "compact_planar_chassis":compact,"lower_shell_reduction_model_units": reduction,
            "retained_source_uv_geometry_triangles": checked_uv,
            "held_triangles_unchanged": 600, "dock_original_uv_triangles": 1272,
            "lid_rigid_rotation_verified": True, "slot_vertical_rays": rays, "anchors": anchors,
            "limits": ["New slot/ports are authorized mechanical geometry; original material tiles are reused.",
                       "Offline triangle audit does not replace Minecraft visual or protection-mod interaction testing."]}


def validate_header_dual_model(model, source, legacy, texture):
    from verify_home_fc_alpha9_geometry import dual
    prior = json.loads(alpha8_asset(DUAL_BODY))
    validate_native_dual_model(prior, source, legacy, texture)
    return dual(model, prior, legacy)


def validate_subor_mesh(mesh, source=None):
    """Validate packaged triangles numerically; optional frozen source proves UV/body/held provenance."""
    def require(condition, message):
        if not condition: raise ValueError(message)
    require(mesh.get("version") == 1 and mesh.get("units") == "model_16" and mesh.get("front") == "north_-z", "Subor mesh units/version/front mismatch")
    require(mesh.get("texture") == "piq_fc_arcade:textures/block/home_subor_sb926.png"
            and mesh.get("texture_size") == [2048, 2048], "Subor atlas reference/size mismatch")
    metadata = mesh.get("metadata", {})
    require(metadata.get("source_sha256") == SUBOR_SOURCE_SHA and metadata.get("texture_sha256") == SUBOR_TEXTURE_SHA,
            "Subor frozen source/texture provenance mismatch")
    require(metadata.get("source_to_world") == {"scale": .45, "rotation_y_degrees": 180, "translation": [8, 0, 9.15]},
            "Subor source/world transform changed")
    groups = mesh.get("groups", {})
    require(set(groups) == set(SUBOR_GROUPS), "Subor must have precisely body, two docks and two held groups")
    result = []
    for name, (elements, triangle_count) in SUBOR_GROUPS.items():
        group = groups[name]
        triangles = group.get("triangles", [])
        require(group.get("elements") == elements and group.get("triangle_count") == triangle_count
                and len(triangles) == triangle_count, "Subor group topology/count mismatch: " + name)
        try:
            p = np.asarray([t["p"] for t in triangles], dtype=float)
            uv = np.asarray([t["uv"] for t in triangles], dtype=float)
            normals = np.asarray([t["n"] for t in triangles], dtype=float)
        except (KeyError, TypeError, ValueError) as error:
            raise ValueError("Malformed Subor triangle: " + name) from error
        require(p.shape == (triangle_count, 3, 3) and uv.shape == (triangle_count, 3, 2)
                and normals.shape == (triangle_count, 3), "Subor triangle dimension mismatch: " + name)
        require(np.isfinite(p).all() and np.isfinite(uv).all() and np.isfinite(normals).all(), "Non-finite Subor coordinates")
        require(np.abs(p).max() <= 1024 and uv.min() >= 0 and uv.max() <= 1, "Subor vertex/UV out of bounds")
        crosses = np.cross(p[:, 1]-p[:, 0], p[:, 2]-p[:, 0])
        lengths = np.linalg.norm(crosses, axis=1)
        require((lengths > 1e-12).all(), "Degenerate Subor source triangle")
        require(np.allclose(normals, crosses/lengths[:, None], atol=1e-7, rtol=0), "Subor normal does not match triangle winding")
        vertices = p.reshape(-1, 3)
        box = np.array([vertices.min(0), vertices.max(0)])
        require(np.asarray(group.get("bounds")).shape == (2, 3) and np.allclose(box, group["bounds"], atol=1e-8, rtol=0),
                "Subor recorded/actual bounds differ: " + name)
        if name.endswith("_held"):
            require(np.allclose(box.mean(0), [8, 8, 8], atol=1e-8, rtol=0) and abs(box[1, 0]-box[0, 0]-12.69) < 1e-8,
                    "Subor held canonical center/width mismatch")
        else:
            require((box[0] >= [.30, 0, 3.34]).all() and (box[1] <= [15.70, 3.2, 12.68]).all(), "Subor world mesh exceeds reviewed collision")
        result.append({"group": name, "elements": elements, "triangles": triangle_count, "bounds": box.tolist(),
                       "uv_range": [float(uv.min()), float(uv.max())], "unit_normals_match_winding": True})
    anchors = metadata.get("anchors", {})
    require(anchors.get("cartridge_bottom_center") == [8, .81, 11.7375]
            and anchors.get("cartridge_render_scale") == .30 and anchors.get("cartridge_source_bottom_center") == [8, 0, 8]
            and anchors.get("cartridge_front_normal") == [0, 0, -1]
            and anchors.get("av_cable_start") == [12.77, .50175, 12.68], "Subor cartridge/AV anchor contract mismatch")
    card_bounds = np.asarray(anchors.get("cartridge_inserted_bounds"), dtype=float)
    require(card_bounds.shape == (2, 3) and np.isfinite(card_bounds).all()
            and (card_bounds[0] >= [.30, 0, 3.34]).all() and (card_bounds[1] <= [15.70, 3.2, 12.68]).all(),
            "Inserted cartridge exceeds the reviewed one-block collision")
    held = metadata.get("held_contract", {})
    require(held.get("center") == [8, 8, 8] and held.get("button_normal") == [0, 0, 1]
            and held.get("dpad_side") == "-X" and held.get("a_side") == "+X" and held.get("extra_held_yaw") is False
            and held.get("width") == 12.69, "Subor held controls/yaw contract mismatch")
    report = {"ok": True, "groups": result, "source_sha256": SUBOR_SOURCE_SHA, "anchors": anchors,
              "held_contract": held, "source_geometry_verified": source is not None}
    if source is not None:
        by_id = {element["uuid"]: element for element in source["elements"]}
        source_groups = [[by_id[key] for key in group["children"]] for group in source["outliner"]]
        selected = {"body": sum(source_groups[:6], []), "p1_docked": source_groups[7], "p2_docked": source_groups[8]}
        for port in (1, 2): selected[f"p{port}_held"] = [e for e in source_groups[6+port] if e["name"] != "独立手柄线"]
        uv_count = geometry_count = 0
        for name, elements in selected.items():
            source_vertices = np.concatenate([np.asarray(list(e["vertices"].values()), dtype=float) for e in elements])
            center = (source_vertices.min(0)+source_vertices.max(0))/2
            index = 0
            for element in elements:
                for face in element["faces"].values():
                    keys = face["vertices"]
                    require(face["texture"] == 0 and len(keys) in (3, 4), "Frozen source face contract changed")
                    for i in range(1, len(keys)-1):
                        triangle_keys = (keys[0], keys[i], keys[i+1])
                        triangle = groups[name]["triangles"][index]
                        expected_uv = np.array([face["uv"][key] for key in triangle_keys])/2048
                        require(np.array_equal(expected_uv, triangle["uv"]), "Subor triangle UV differs from original atlas: " + name)
                        uv_count += 1
                        p = np.array([element["vertices"][key] for key in triangle_keys])
                        if name.endswith("_held"):
                            relative = p-center
                            expected = np.c_[relative[:, 0], -relative[:, 2], relative[:, 1]]*(12.69/7)+8
                        else:
                            if name.endswith("_docked"): p = p+[.7 if name.startswith("p1") else .1, 0, 0]
                            expected = p*[-.45, .45, -.45]+[8, 0, 9.15]
                        # External cords are the sole intentionally rerouted geometry;
                        # their full topology/UV/winding/bounds are still checked above.
                        if element["name"] != "独立手柄线":
                            require(np.allclose(expected, triangle["p"], atol=1e-8, rtol=0), "Subor geometry differs from reviewed uniform transform: " + name)
                            geometry_count += 1
                        index += 1
            require(index == len(groups[name]["triangles"]), "Subor omitted/added source triangles")
        report.update(source_uv_triangles_verified=uv_count, source_noncord_geometry_triangles_verified=geometry_count)
    return report


def validate_enum_extensions(metadata, document):
    mods = [entry for entry in tomllib.loads(metadata.decode("utf-8")).get("mods", []) if entry.get("modId") == "piq_fc_arcade"]
    if len(mods) != 1 or mods[0].get("enumExtensions") != ENUM_EXTENSIONS:
        raise ValueError("PIQ mod TOML must declare its exact enumExtensions resource")
    entries = document.get("entries") if isinstance(document, dict) else None
    if not isinstance(entries, list) or len(entries) != 2:
        raise ValueError("Expected exactly two reviewed controller ArmPose extensions")
    names = [entry.get("name") if isinstance(entry, dict) else None for entry in entries]
    if any(not enum_name_has_mod_prefix(name) for name in names) or set(names) != set(ARM_POSE_FIELDS):
        raise ValueError("ArmPose names must be the two unique mod-prefixed reviewed fields")
    for entry in entries:
        if entry.get("enum") != "net/minecraft/client/model/HumanoidModel$ArmPose" or entry.get("constructor") != "(ZLnet/neoforged/neoforge/client/IArmPoseTransformer;)V":
            raise ValueError("ArmPose target/constructor differs from the public NeoForge extension contract")
        if entry.get("parameters") != {"class": ARM_POSE_PARAMETER_CLASS, "field": ARM_POSE_FIELDS[entry["name"]]}:
            raise ValueError("ArmPose must use its reviewed EnumProxy field reference")
    # Do not load/reflection-initialize a client enum on a dedicated JVM. The
    # declaration is legal there; FML transforms the target only when it exists.
    return entries


def bytecode_checks(jar, javap, version="0.31.0-alpha.3"):
    protocol = str(review_for(version)["protocol"])
    checks = []
    def check(name, condition, evidence):
        checks.append({"check": name, "ok": bool(condition), "evidence": evidence})
    def disassemble(name, verbose=False):
        result = subprocess.run([str(javap), "-classpath", str(jar), "-p", "-c", "-constants"] + (["-v"] if verbose else []) + ["cn.piq.fcarcade." + name],
                                capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=30)
        if result.returncode:
            raise RuntimeError("javap failed: " + name + ": " + result.stderr[:500])
        return result.stdout
    network = disassemble("FcNetwork")
    check("main_protocol_" + protocol, f'PROTOCOL_VERSION = "{protocol}"' in network and has_protocol_literal(network, protocol),
          [line.strip() for line in network.splitlines() if "PROTOCOL_VERSION" in line or "// String " + protocol in line])
    cartridge = disassemble("home.CartridgeNetwork")
    check("cartridge_protocol_" + protocol, has_protocol_literal(cartridge, protocol) and "RegisterPayloadHandlersEvent.registrar" in cartridge,
          [line.strip() for line in cartridge.splitlines() if "// String " + protocol in line or "RegisterPayloadHandlersEvent.registrar" in line])
    renderer = disassemble("client.HomeHardwareRenderer")
    check("controller_item_and_four_meshes_registered", "ModItems.FC_CONTROLLER" in renderer and all(
        "// String " + name in renderer for name in ("home_controller_p1_docked", "home_controller_p2_docked", "home_controller_p1_held", "home_controller_p2_held")),
        [line.strip() for line in renderer.splitlines() if "FC_CONTROLLER" in line or "// String home_controller_" in line])
    held = disassemble("client.HomeHardwareRenderer$ControllerItemRenderer")
    check("held_renderer_uses_reviewed_yaw", "HomeHardwareRenderLayout.heldControllerYaw" in held,
          [line.strip() for line in held.splitlines() if "heldControllerYaw" in line])
    registry = disassemble("registry.ModItems")
    check("controller_item_registered", "// String fc_controller" in registry and "home/FcControllerItem" in registry,
          [line.strip() for line in registry.splitlines() if "// String fc_controller" in line or "home/FcControllerItem" in line])
    catalog = disassemble("registry.CreativeTabCatalog")
    check("no_free_controller_in_creative_catalog", "// String fc_controller" not in catalog and all(
        "// String " + name in catalog for name in ("famicom_console", "retro_tv", "fc_cartridge", "av_cable")),
        [line.strip() for line in catalog.splitlines() if "// String" in line])
    mod = disassemble("FcArcadeMod")
    check("client_hardware_entrypoint_registered", "HomeHardwareRenderer.register" in mod,
          [line.strip() for line in mod.splitlines() if "HomeHardwareRenderer.register" in line])
    hardware = disassemble("home.HomeHardware")
    check("controller_events_registered", "HomeControllerService.register" in hardware,
          [line.strip() for line in hardware.splitlines() if "HomeControllerService.register" in line])
    tv = disassemble("home.RetroTvBlock")
    check("tv_static_shell_disabled", "RenderShape.ENTITYBLOCK_ANIMATED" in tv,
          [line.strip() for line in tv.splitlines() if "RenderShape." in line])
    if version in ("0.31.0-alpha.4", "0.31.0-alpha.5", "0.31.0-alpha.6", "0.31.0-alpha.7", "0.31.0-alpha.8", "0.31.0-alpha.9"):
        parameters = disassemble("client.ControllerArmPoseParameters")
        check("controller_enum_proxy_fields_present", "EnumProxy" in parameters and all(field in parameters for field in ARM_POSE_FIELDS.values()),
              [line.strip() for line in parameters.splitlines() if "EnumProxy" in line or any(field in line for field in ARM_POSE_FIELDS.values())])
        check("controller_pose_client_registration", "ControllerPose.register" in renderer,
              [line.strip() for line in renderer.splitlines() if "ControllerPose.register" in line])
    if version == "0.31.0-alpha.5":
        blocks = disassemble("registry.ModBlocks")
        entities = disassemble("registry.ModBlockEntities")
        check("subor_block_item_shared_be_and_catalog_registered", "// String subor_console" in blocks
              and "home/SuborConsoleBlock" in blocks and "// String subor_console" in registry
              and "ModBlocks.SUBOR_CONSOLE" in registry and "ModBlocks.SUBOR_CONSOLE" in entities
              and "ModBlocks.FAMICOM_CONSOLE" in entities and "// String subor_console" in catalog,
              [line.strip() for text in (blocks, registry, entities, catalog) for line in text.splitlines() if "subor" in line.lower()])
        subor = disassemble("home.SuborConsoleBlock")
        console_layout = disassemble("home.HomeConsoleLayout")
        check("subor_single_shell_and_shared_four_way_shape", "extends cn.piq.fcarcade.world.FamicomConsoleBlock" in subor
              and "RenderShape.ENTITYBLOCK_ANIMATED" in subor and "HomeConsoleLayout.suborBounds" in subor
              and "Math.floorMod" in console_layout and all("// double " + n + "d" in console_layout for n in ("0.3", "15.7", "3.34", "12.68", "3.2")),
              [line.strip() for line in subor.splitlines() if "extends " in line or "HomeConsoleLayout" in line or "RenderShape" in line])
        anchors = {"CARD_SCALE": .30, "CARD_X": .5, "CARD_Y": .81/16, "CARD_Z": 11.7375/16,
                   "AV_X": 12.77/16, "AV_Y": .50175/16, "AV_Z": 12.68/16}
        check("subor_card_and_av_layout_constants", all(has_double_constant(console_layout, name, value) for name, value in anchors.items()),
              [line.strip() for line in console_layout.splitlines() if "public static final double" in line])
        mesh = disassemble("client.SuborHardwareMesh", True)
        renderer_verbose = disassemble("client.HomeHardwareRenderer", True)
        check("subor_mesh_reload_is_registered_and_discards_stale_cache", "SuborHardwareMesh.registerReload" in renderer_verbose
              and "RegisterClientReloadListenersEvent.registerReloadListener" in mesh and "SuborHardwareMesh.reload" in mesh
              and "java/util/Map.copyOf" in mesh and "java/util/Map.of" in mesh
              and "meshes/home_subor_sb926.json" in mesh and "textures/block/home_subor_sb926.png" in mesh,
              [line.strip() for line in renderer_verbose.splitlines() if "SuborHardwareMesh.registerReload" in line])
        check("subor_raw_triangle_draw_and_finite_guard", all(value in mesh for value in (
            "RenderType.entityCutoutNoCull", "java/lang/Math.min", "java/lang/Float.isFinite", "VertexConsumer.setUv", "VertexConsumer.setNormal")),
            [line.strip() for line in mesh.splitlines() if "Float.isFinite" in line or "Math.min" in line or "VertexConsumer.setUv" in line])
        check("subor_body_and_docks_use_raw_mesh", "ModItems.SUBOR_CONSOLE" in renderer and "home/SuborConsoleBlock" in renderer
              and "SuborHardwareMesh.draw" in renderer and "HomeConsoleBlockEntity.controllerDocked" in renderer
              and all("// String " + name in renderer for name in ("body", "p1_docked", "p2_docked")),
              [line.strip() for line in renderer.splitlines() if "SuborHardwareMesh.draw" in line or "controllerDocked" in line])
        check("console_cross_section_registration_and_cable_bounds", method_returns_constant_true(renderer,
              "shouldRenderOffScreen", "cn.piq.fcarcade.home.HomeConsoleBlockEntity")
              and "getRenderBoundingBox(cn.piq.fcarcade.home.HomeConsoleBlockEntity)" in renderer
              and "AABB.minmax" in renderer and "AABB.inflate" in renderer and "tvRenderBounds:" in renderer
              and "public boolean shouldRender(" not in renderer,
              "Packaged console BER always registers globally, unions connected TV/cable bounds, and retains inherited distance culling")
        item_mesh = disassemble("client.HomeHardwareRenderer$SuborItemRenderer")
        check("subor_item_renders_complete_console_once", item_mesh.count("SuborHardwareMesh.draw") == 3
              and all("// String " + name in item_mesh for name in ("body", "p1_docked", "p2_docked")),
              [line.strip() for line in item_mesh.splitlines() if "// String" in line or "SuborHardwareMesh.draw" in line])
        call, yaw = held.find("SuborHardwareMesh.draw"), held.find("HomeHardwareRenderLayout.heldControllerYaw")
        check("subor_held_style_bypasses_original_fc_yaw", "HomeControllerData.style" in held and "HomeControllerData$Style.SUBOR" in held
              and 0 <= call < yaw and bool(re.search(r":\s+return\s", held[call:yaw]))
              and all("// String " + name in held for name in ("p1_held", "p2_held")),
              [line.strip() for line in held.splitlines() if "Style.SUBOR" in line or "SuborHardwareMesh.draw" in line or "heldControllerYaw" in line])
        style = disassemble("home.HomeControllerData$Style")
        data = disassemble("home.HomeControllerData")
        loans = disassemble("home.HomeControllerService")
        check("subor_controller_style_survives_loan_binding", "// String subor" in style and "FAMICOM" in style
              and "// String Style" in data and "Style.serializedName" in data
              and "home/SuborConsoleBlock" in loans and "HomeControllerData$Style.SUBOR" in loans and "HomeControllerData.bind" in loans,
              [line.strip() for line in loans.splitlines() if "SuborConsoleBlock" in line or "Style.SUBOR" in line or "HomeControllerData.bind" in line])
        pose = disassemble("client.ControllerPoseLayout")
        check("alpha5_first_person_common_rig_lowered_without_json_change", all(has_double_constant(pose, name, value) for name, value in {
            "FIRST_IDLE_Y": -.62, "FIRST_TWO_HAND_Z": -1.46, "FIRST_MIXED_Z": -1.58, "FIRST_PITCH": -16, "FIRST_ARM_SCALE": .82}.items()),
            [line.strip() for line in pose.splitlines() if "static final double FIRST_" in line])
    if version in ("0.31.0-alpha.6", "0.31.0-alpha.7", "0.31.0-alpha.8", "0.31.0-alpha.9"):
        renderer_verbose = disassemble("client.HomeHardwareRenderer", True)
        mesh = disassemble("client.SuborHardwareMesh", True)
        subor = disassemble("home.SuborConsoleBlock")
        structure = disassemble("home.SuborStructure")
        placement_item = disassemble("home.SuborConsoleBlockItem")
        part = disassemble("home.SuborPartBlock")
        entities = disassemble("registry.ModBlockEntities")
        blocks = disassemble("registry.ModBlocks")
        check("alpha6_subor_four_cell_registration_and_transactions", "SuborConsoleBlockItem" in registry
              and "ModBlocks.SUBOR_CONSOLE" in registry and "ModBlocks.SUBOR_PART" in entities
              and "home/SuborPartBlock" in blocks and "RenderShape.INVISIBLE" in part
              and "SuborStructure.place" in placement_item and "DataComponents.BLOCK_ENTITY_DATA" in placement_item
              and "DataComponents.BLOCK_STATE" in placement_item,
              [line.strip() for text in (registry, entities, part, placement_item) for line in text.splitlines()
               if any(name in line for name in ("SuborConsoleBlockItem", "SUBOR_PART", "RenderShape.INVISIBLE", "SuborStructure.place", "DataComponents.BLOCK_"))])
        check("alpha6_subor_wide_state_retains_legacy_and_shared_sessions", "// String wide" in subor
              and "SuborStructure.canPlace" in subor and "SuborStructure.breakByPlayer" in subor
              and "RenderShape.ENTITYBLOCK_ANIMATED" in subor and "SuborStructure.complete" in hardware
              and "SuborStructure.resolveAnchor" in hardware and "HomeControllerService.register" in hardware,
              "Wide state uses four-cell placement/removal; incomplete assemblies cannot run shared FC hardware")
        console_layout = disassemble("home.HomeConsoleLayout")
        compact = version in ("0.31.0-alpha.8", "0.31.0-alpha.9")
        slim = version in ("0.31.0-alpha.7", "0.31.0-alpha.8", "0.31.0-alpha.9")
        check("wide_card_rca_constants_match_reviewed_mesh", all(has_double_constant(console_layout, name, value) for name, value in {
              "WIDE_CARD_X": 1, "WIDE_CARD_Y": (1.52 if slim else 2.72)/16, "WIDE_CARD_Z": (22.164 if compact else 23.705)/16, "WIDE_CARD_SCALE": .6,
              "WIDE_AV_X": (24.32 if compact else 26.4)/16, "WIDE_AV_Y": (1.05 if slim else 2.25)/16, "WIDE_AV_Z": (23.632 if compact else 25.54)/16, "WIDE_AV_SPACING": 1.6/16}.items())
              and all("// double " + value + "d" in console_layout for value in (("6.5","23.8","6.3") if compact else ("5.2", "25.65", "6.3" if slim else "7.5"))),
              [line.strip() for line in console_layout.splitlines() if "static final double WIDE_" in line])
        check("alpha6_subor_all_cells_guarded_without_forced_load", all(token in structure for token in (
              "SuborFootprint.canPlace", "hasChunkAt", "isOutsideBuildHeight", "WorldBorder.isWithinBounds",
              "mayInteract", "mayUseItemAt", "canBeReplaced", "isUnobstructed", "SuborRemovalGate.attempt",
              "restoringBlockSnapshots", "SuborAssemblyLedger.cancelPlacement", "removedConsoleIdentity"))
              and "getChunk:" not in structure,
              "Packaged placement/removal retain loaded-cell, permission, snapshot rollback, and exact hardware cleanup gates")
        tv_placement = javap_method(tv, "getStateForPlacement(net.minecraft.world.item.context.BlockPlaceContext);")
        check("alpha6_new_tv_eight_cells_old_centered_state_retained", bool(re.search(
              r"Field CENTERED:.*?iconst_0.*?Boolean.valueOf.*?setValue", tv_placement, re.DOTALL))
              and "HomeTvStructure.canPlace" in tv_placement and "// String centered" in tv,
              [line.strip() for line in tv_placement.splitlines() if "CENTERED" in line or "iconst_" in line or "canPlace" in line])
        check("alpha6_two_model_reload_and_separate_lids", "SuborHardwareMesh.registerReload" in renderer_verbose
              and all(token in mesh for token in ("home_subor_sb926.json", "home_subor_sb926_wide.json", "lid_closed", "lid_open",
                  "RegisterClientReloadListenersEvent.registerReloadListener", "wideMeshes", "Map.copyOf", "Map.of")),
              "Original and wide meshes load separately, with explicit closed/open lid groups and failed-load empty caches")
        check("alpha6_world_lid_follows_real_cartridge_and_docks", all(token in renderer for token in (
              "SuborConsoleBlock.wide", "HomeConsoleBlockEntity.insertedCartridge", "ItemStack.isEmpty",
              "HomeConsoleBlockEntity.controllerDocked", "SuborHardwareMesh.drawWide", "SuborHardwareMesh.draw",
              "// String lid_closed", "// String lid_open", "// String p1_docked", "// String p2_docked")),
              "One body/lid path is selected from persisted wide state; the inserted cartridge selects closed/open lid")
        item_mesh = disassemble("client.HomeHardwareRenderer$SuborItemRenderer")
        check("alpha6_inventory_wide_model_half_scale_and_closed_lid", item_mesh.count("SuborHardwareMesh.drawWide") == 4
              and "// float 0.5f" in item_mesh and "PoseStack.scale" in item_mesh
              and all("// String " + name in item_mesh for name in ("body", "p1_docked", "p2_docked", "lid_closed")),
              [line.strip() for line in item_mesh.splitlines() if "// String" in line or "0.5f" in line or "PoseStack.scale" in line])
        call, yaw = held.find("SuborHardwareMesh.draw"), held.find("HomeHardwareRenderLayout.heldControllerYaw")
        check("alpha6_handheld_subor_keeps_original_canonical_mesh", "HomeControllerData$Style.SUBOR" in held
              and 0 <= call < yaw and bool(re.search(r":\s+return\s", held[call:yaw])) and "SuborHardwareMesh.drawWide" not in held,
              "Handheld Subor still selects alpha.5 canonical mesh and returns before original-FC side-controller yaw")
        cable = disassemble("client.HomeAvCableRenderer", True)
        cable_mesh = disassemble("client.HomeAvCableMesh")
        check("alpha6_solid_av_white_texture_reload_registered", "HomeAvCableRenderer.registerReload" in renderer_verbose
              and all(token in cable for token in ("dynamic/av_solid_white", "RegisterClientReloadListenersEvent.registerReloadListener",
                  "NativeImage", "setPixelRGBA", "DynamicTexture", "TextureManager.register", "RenderType.entityCutoutNoCull",
                  "VertexConsumer.setColor", "VertexConsumer.setUv")),
              "AV geometry uses a code-created white pixel with per-vertex colors; no external PNG mutation")
        check("alpha6_av_mesh_bounded_cached_and_colored", "MAX_QUADS = 6000" in cable_mesh
              and all(name in cable_mesh for name in ("YELLOW =", "WHITE =", "RED =", "HomeAvCableLayout.route"))
              and all(name in renderer for name in ("java/util/WeakHashMap", "HomeAvCableMesh.build", "HomeAvCableRenderer.draw", "CachedCable")),
              "Three channel colors and a 6000-quad cap are present; the renderer caches by weak console identity/state")
        if slim:
            cable_layout = disassemble("client.HomeAvCableLayout")
            check("alpha7_shared_base_av_clearance", has_double_constant(cable_layout, "TABLE_CENTERLINE_CLEARANCE", .027)
                  and "remainingLift" in cable_layout and "lowerJunction" in cable_layout,
                  "Same-level routing targets common base + 0.027; independent runtime geometry tests verify contact and droop. Different-height routing does not infer arbitrary tabletop terrain.")
        check("alpha6_full_console_global_registration_and_bounds", method_returns_constant_true(renderer,
              "shouldRenderOffScreen", "cn.piq.fcarcade.home.HomeConsoleBlockEntity")
              and "HomeConsoleLayout.suborBounds" in renderer and "AABB.minmax" in renderer
              and "AABB.inflate" in renderer and "public boolean shouldRender(" not in renderer,
              "Wide shell and routed cable bounds are retained beyond the anchor section, with inherited distance culling")
        dual = disassemble("world.DualCabinetBlock")
        dual_structure = disassemble("world.DualCabinetStructure")
        dual_be = disassemble("world.DualCabinetBlockEntity")
        dual_part = disassemble("world.DualCabinetPartBlock")
        dual_item = disassemble("world.DualCabinetBlockItem")
        dual_render = disassemble("client.DualCabinetRenderer", True)
        dual_render_plain = disassemble("client.DualCabinetRenderer")
        session = disassemble("server.ServerArcadeSessions")
        skin = disassemble("server.ServerSkinService$Service")
        arcade_structure = disassemble("world.ArcadeStructure")
        check("alpha6_dual_registered_with_invisible_no_item_proxy", all("// String " + name in blocks for name in ("dual_cabinet", "dual_cabinet_part"))
              and "ModBlocks.DUAL_CABINET" in entities and "ModBlocks.DUAL_CABINET_PART" in entities
              and "DualCabinetBlockItem" in registry and "// String dual_cabinet" in catalog
              and "// String dual_cabinet_part" not in registry and "// String dual_cabinet_part" not in catalog
              and "RenderShape.INVISIBLE" in dual_part and "RenderShape.ENTITYBLOCK_ANIMATED" in dual,
              "One creative/item anchor and 11 invisible proxies; no independent proxy item")
        check("alpha6_dual_twelve_cell_exact_owner_lifecycle", all(token in dual_structure for token in (
              "DualCabinetFootprint.canPlace", "DualCabinetRemovalGate.attempt", "DualCabinetAssemblyLedger.close",
              "DualCabinetAssemblyLedger.acknowledge", "DualCabinetAssemblyLedger.cancelPlacement", "restoringBlockSnapshots",
              "hasChunkAt", "mayInteract", "mayUseItemAt", "isUnobstructed", "getBlockEntity"))
              and "getChunk:" not in dual_structure and "HomeHardware" not in dual_structure
              and "DataComponents.BLOCK_ENTITY_DATA" in dual_item and "DataComponents.BLOCK_STATE" in dual_item,
              "Independent 12-cell transaction retains loaded/permission/ownership/rollback gates and rejects post-placement identity components")
        check("alpha6_dual_shared_session_skin_and_proxy_resolution_gates", all(token in dual_structure for token in (
              "ServerArcadeSessions.openLibrary", "ServerArcadeSessions.interact", "ServerArcadeSessions.stopHomeConsole",
              "ServerArcadeSessions.removeMachineDisplays", "PlayerInteractEvent$RightClickBlock"))
              and "extends cn.piq.fcarcade.world.LegacyFcArcadeBlockEntity" in dual_be
              and "DualCabinetStructure.complete" in session and "DualCabinetStructure.complete" in skin
              and "DualCabinetStructure.resolveAnchor" in arcade_structure and "ArcadeDisplayStyle.DUAL_CABINET" in arcade_structure,
              "Dual proxies normalize to anchor; complete structures share existing session/save/skin pathways")
        check("alpha6_dual_single_global_shell_and_resource_reload_identity", "DualCabinetRenderer.register" in renderer
              and all(token in dual_render for token in ("ModBlockEntities.DUAL_CABINET", "ModItems.DUAL_CABINET", "block/dual_arcade_body",
                  "ModelEvent$RegisterAdditional", "DualCabinetRenderer$Cached.model", "ClientSkinManager.textureFor", "isScreen"))
              and method_returns_constant_true(dual_render_plain, "shouldRenderOffScreen", "cn.piq.fcarcade.world.DualCabinetBlockEntity")
              and "DualCabinetGeometry.bounds" in dual_render_plain and "PoseStack.scale" in dual_render_plain
              and ("fconst_1" in dual_render_plain if compact else "// float 1.5f" in dual_render_plain) and "public boolean shouldRender(" not in dual_render_plain,
              "Anchor-only cached standalone body, reviewed world scale, full rotated bounds and inherited distance culling")
        if slim and not compact:
            dual_geometry = disassemble("layout.DualCabinetGeometry")
            dual_footprint = disassemble("world.DualCabinetFootprint")
            display = disassemble("client.ArcadeBlockScreenRenderer")
            occupancy = disassemble("server.ArcadeOccupancyDisplay")
            label = disassemble("layout.ArcadeOccupancyLabelLayout")
            check("alpha7_lowered_screen_shape_and_occupancy_match_geometry", has_double_constant(dual_geometry, "MODEL_TOP", 2.4)
                  and has_double_constant(dual_footprint, "BODY_HEIGHT", 38.4) and "CELL_COUNT = 12" in dual_footprint
                  and all("// double " + str(value) + "d" in dual_geometry for value in (18.51747517055, 29.451589437822))
                  and "DualCabinetGeometry.screen" in display and "DualCabinetGeometry.occupancy" in occupancy
                  and "DualCabinetGeometry.leaderboardTextOrigin" in occupancy and "DualCabinetGeometry.occupancy" in label,
                  "The packaged lowered 4:3 screen, 2.4-block body, 12-cell clipped collision, and occupancy/leaderboard use the same geometry")
        if compact:
            dual_geometry=disassemble("layout.DualCabinetGeometry")
            fit=disassemble("layout.ScreenAspectFit")
            presentation=disassemble("layout.DualScreenPresentation")
            display=disassemble("client.ArcadeBlockScreenRenderer")
            events=disassemble("client.ClientArcadeEvents")
            preferences=disassemble("client.LocalArcadePreferences")
            check("native_dual_and_contain_aspect_are_wired",has_double_constant(dual_geometry,"MODEL_TOP",2.35 if version=="0.31.0-alpha.9" else 2)
                  and has_double_constant(dual_geometry,"MODEL_SCALE",1)
                  and "ScreenAspectFit.fit" in presentation and "DualCabinetGeometry.screen" in presentation
                  and "DualScreenPresentation.frame" in display and "ClientArcadeEvents.dualScreenAspect" in display
                  and "Math.min" in fit and "ScreenQuad" in fit,
                  "Native-size body and client contain-fit all share actual 16:9 glass; no old 1.5x world scale")
            check("alpha8_client_aspect_command_preference",all(token in events for token in
                  ("// String aspect","// String 4:3","// String 1:1","// String 16:9","LocalArcadePreferences.setDualScreenAspect"))
                  and "dualScreenAspect" in preferences and "// String 4:3" in preferences,
                  "Aspect choice is local, persisted, defaults 4:3; no shared ROM/session mutation")
        lcd = disassemble("home.LcdTvBlock")
        tv_structure = disassemble("home.HomeTvStructure")
        tv_renderer = disassemble("client.HomeHardwareRenderer$TvRenderer")
        check("alpha6_lcd_one_cell_shared_tv_be_and_single_shell", "extends cn.piq.fcarcade.home.RetroTvBlock" in lcd
              and method_returns_constant_true(lcd,"singleBlockTv","")
              and "ArcadeDisplayStyle.HOME_LCD_TV" in lcd and "ModBlocks.LCD_TV" in entities
              and "// String lcd_tv" in catalog and "RetroTvBlock.singleBlockTv" in tv_structure
              and "LcdTvLayout.bounds" in renderer and "getBlockModel" in tv_renderer,
              "LCD shares the TV endpoint/AV protocol, explicitly short-circuits to one physical cell, and uses only the TV BER shell")
    if version == "0.31.0-alpha.9":
        animation = disassemble("client.ControllerButtonRenderer")
        local_animation = disassemble("client.ClientControllerAnimation", True)
        worker = disassemble("client.ClientNesWorker")
        client_session = disassemble("client.ClientArcadeSession")
        pose = disassemble("client.ControllerPoseLayout")
        assembly = disassemble("server.ServerCartridgeAssemblyService")
        transaction = disassemble("home.CartridgeAssemblyInventory")
        service = disassemble("server.ServerCartridgeService")
        cartridge_data = disassemble("home.FcCartridgeData")
        check("alpha9_hand_size_and_actual_core_input_animation",has_double_constant(pose,"DEFAULT_HAND_SIZE",1.18)
              and all(has_double_constant(pose,n,v) for n,v in {"FIRST_IDLE_Y":-.62,"FIRST_TWO_HAND_Z":-1.46,"FIRST_PITCH":-16}.items())
              and "volatile long appliedControllerState" in worker and "NesCore.runFrame" in worker
              and "appliedControllerMask" in worker and "ClientNesWorker.appliedControllerMask" in client_session
              and "ClientArcadeEvents.controllerAnimationMask" in local_animation and "ControllerButtonAnimation.clear" in local_animation
              and "ControllerButtonRenderer.drawFamicom" in renderer and "SuborHardwareMesh.drawHeld" in held
              and "HomeHardwareRenderer.drawQuads" in animation and "home_famicom_console.png" in animation,
              "Completed core masks drive only leased local held models; low pose retained, hand-size default 1.18, original raw PNG route")
        check("alpha9_server_authoritative_cartridge_assembly",all(t in assembly for t in
              ("CartridgeAssemblyBinding.permits","CartridgeAssemblyInventory.split","CartridgeAssemblyInventory.combine",
               "ServerCartridgeService.cancelForAssembly","getCarried","isShiftKeyDown"))
              and "addFreshEntity" not in assembly and "instabuild" not in assembly
              and "CartridgeNetwork$DismantleRequest" in cartridge and "cancelForAssembly" in service
              and "List.set" in transaction and "getOffhandItem" in assembly,
              "Bound server main-hand transaction, one PCB plus one shell, no creative bypass/drop fallback, editor invalidation")
        check("alpha9_nonstandard_metadata_is_preserved_by_refusal",assembly.count("FcCartridgeData.supportsAssembly")>=3
              and "ItemStack.getComponentsPatch" in cartridge_data and "CartridgeAssemblyMetadata.supported" in cartridge_data,
              "Only explicit component patches are inspected; unsupported names/lore/third-party data reject before output construction, not duplicated or silently dropped")
        wide = disassemble("home.WideLcdTvBlock")
        wide_structure = disassemble("home.WideLcdTvStructure")
        wide_presentation = disassemble("layout.WideLcdPresentation")
        check("alpha9_wide_lcd_registered_separate_two_cell_transaction",all(t in registry for t in
              ("wide_lcd_tv","WideLcdTvBlockItem","fc_cartridge_board","fc_cartridge_shell"))
              and "ModBlocks.WIDE_LCD_TV" in entities and "ModBlocks.WIDE_LCD_TV_PART" in entities
              and "home/WideLcdTvPartBlock" in blocks and "ArcadeDisplayStyle.HOME_WIDE_LCD_TV" in wide
              and "WideLcdTvStructure" in tv_structure and all(t in wide_structure for t in
              ("WideLcdTvAssemblyLedger","WideLcdTvRemovalGate","hasChunkAt","mayInteract","restoringBlockSnapshots"))
              and "getChunk:" not in wide_structure and "ScreenAspectFit.fit" in wide_presentation,
              "Separate two-cell ledger retains loaded-cell/permission/rollback gates; 16:9 physical screen uses contain-fit")
        check("alpha9_header_ber_rebase_matches_loader_safe_json",has_double_constant(dual_geometry,"MODEL_Y_OFFSET",.35)
              and has_double_constant(dual_geometry,"MODEL_TOP",2.35) and "// double 0.35d" in dual_render_plain,
              "Raw JSON Y -5.6 plus BER +.35 block, without changing playable surfaces")
    return checks


def inspect(jar, manifest_path, expected_jar, expected_manifest, baseline, javap, version="0.31.0-alpha.3"):
    expected_manifest = reviewed_manifest_sha(version, expected_manifest)
    review = review_for(version)
    report = {"schema": 3, "version": version, "validated_at_utc": datetime.now(timezone.utc).isoformat(),
              "operation": "Independent read-only final archive audit; no extraction, repacking or native execution",
              "jar_path": str(jar.resolve()), "manifest_path": str(manifest_path.resolve()), "errors": []}
    def require(condition, error):
        if not condition: report["errors"].append(error)
        return bool(condition)
    report["jar_sha256"] = file_sha(jar)
    report["jar_bytes"] = jar.stat().st_size
    report["manifest_sha256"] = file_sha(manifest_path)
    require(report["jar_sha256"] == expected_jar.upper(), "Final JAR SHA256 differs from the supplied release candidate")
    require(report["manifest_sha256"] == expected_manifest.upper(), "Final resource manifest SHA256 differs from the frozen review")
    require(manifest_path.resolve() == review["manifest"].resolve(), "Target version must use its fixed review manifest path")
    require(file_sha(baseline) == BETA3_SHA, "Appearance baseline must be the protected beta.3 JAR")
    assets = validate_manifest(json.loads(manifest_path.read_bytes()), version)
    report["approved_asset_count"] = len(assets)
    preserved = dict(PRESERVED)
    if version in ("0.31.0-alpha.4", "0.31.0-alpha.5", "0.31.0-alpha.6", "0.31.0-alpha.7", "0.31.0-alpha.8", "0.31.0-alpha.9"):
        preserved[ALPHA3_MANIFEST] = ALPHA3_MANIFEST_SHA
        preserved[DELIVERY / "piq_fc_arcade-0.31.0-alpha.3.jar"] = ALPHA3_JAR_SHA
    if version in ("0.31.0-alpha.5", "0.31.0-alpha.6", "0.31.0-alpha.7", "0.31.0-alpha.8", "0.31.0-alpha.9"):
        preserved[ALPHA4_MANIFEST] = ALPHA4_MANIFEST_SHA
        preserved[DELIVERY / "piq_fc_arcade-0.31.0-alpha.4.jar"] = ALPHA4_JAR_SHA
        preserved[SUBOR_SOURCE] = SUBOR_SOURCE_SHA
        preserved[SUBOR_SOURCE_TEXTURE] = SUBOR_TEXTURE_SHA
    if version in ("0.31.0-alpha.6", "0.31.0-alpha.7", "0.31.0-alpha.8", "0.31.0-alpha.9"):
        preserved[ALPHA5_MANIFEST] = ALPHA5_MANIFEST_SHA
        preserved[DELIVERY / "piq_fc_arcade-0.31.0-alpha.5.jar"] = ALPHA5_JAR_SHA
    if version in ("0.31.0-alpha.7", "0.31.0-alpha.8", "0.31.0-alpha.9"):
        preserved[ALPHA6_MANIFEST] = ALPHA6_MANIFEST_SHA
        preserved[DELIVERY / "piq_fc_arcade-0.31.0-alpha.6.jar"] = ALPHA6_JAR_SHA
    if version in ("0.31.0-alpha.8", "0.31.0-alpha.9"):
        preserved[ALPHA7_MANIFEST]=ALPHA7_MANIFEST_SHA
        preserved[DELIVERY / "piq_fc_arcade-0.31.0-alpha.7.jar"]=ALPHA7_JAR_SHA
    if version == "0.31.0-alpha.9":
        preserved[ALPHA8_MANIFEST]=ALPHA8_MANIFEST_SHA
        preserved[DELIVERY / "piq_fc_arcade-0.31.0-alpha.8.jar"]=ALPHA8_JAR_SHA
        preserved[ALPHA9_INITIAL_MANIFEST]=ALPHA9_INITIAL_MANIFEST_SHA
    report["preserved_artifacts"] = [{"path": str(path), "expected_sha256": digest, "actual_sha256": file_sha(path),
                                       "ok": file_sha(path) == digest} for path, digest in preserved.items()]
    require(all(entry["ok"] for entry in report["preserved_artifacts"]), "A protected prior artifact changed")
    with zipfile.ZipFile(jar) as archive, zipfile.ZipFile(baseline) as old:
        report["jar_entry_count"] = len(archive.infolist())
        report["duplicate_entries"] = duplicate_entries(archive)
        require(not report["duplicate_entries"], "Duplicate archive entries found")
        report["approved_assets"] = asset_checks(archive, assets)
        require(all(entry["ok"] for entry in report["approved_assets"]), "Reviewed appearance assets are missing, duplicated or changed")
        has_subor = version in ("0.31.0-alpha.5", "0.31.0-alpha.6", "0.31.0-alpha.7", "0.31.0-alpha.8", "0.31.0-alpha.9")
        other = {name: sha(old.read(name)) for name in old.namelist() if appearance(name, has_subor) and name not in assets}
        report["unchanged_appearance_assets"] = asset_checks(archive, other)
        report["unchanged_appearance_count"] = len(other)
        require(len(other) == 43 and all(entry["ok"] for entry in report["unchanged_appearance_assets"]), "Expected exactly 43 protected other appearance assets")
        report["unreviewed_appearance_entries"] = sorted(name for name in archive.namelist() if appearance(name, has_subor) and name not in assets and name not in other)
        require(not report["unreviewed_appearance_entries"], "Unreviewed appearance resources were added")
        report["restored_source_drafts"] = asset_checks(archive, {name: sha(old.read(name)) for name in DRAFTS})
        for entry in report["restored_source_drafts"]:
            entry["source_draft_sha256"] = file_sha(ASSETS / entry["path"])
            entry["source_draft_differs_from_baseline"] = entry["source_draft_sha256"] != entry["expected_sha256"]
        require(all(entry["ok"] and entry["source_draft_differs_from_baseline"] for entry in report["restored_source_drafts"]), "The two protected draft textures were not restored only in the JAR")
        metadata = archive.read("META-INF/neoforge.mods.toml")
        report["mod_version"] = mod_version(metadata)
        require(report["mod_version"] == version, "Packaged mod metadata differs from the target review version")
        counts = Counter(archive.namelist())
        if version in ("0.31.0-alpha.4", "0.31.0-alpha.5", "0.31.0-alpha.6", "0.31.0-alpha.7", "0.31.0-alpha.8", "0.31.0-alpha.9"):
            report["controller_enum_extensions"] = {"path": ENUM_EXTENSIONS, "count": counts[ENUM_EXTENSIONS], "ok": False}
            try:
                if counts[ENUM_EXTENSIONS] != 1: raise ValueError("Enum extension JSON is missing or duplicated")
                entries = validate_enum_extensions(metadata, json.loads(archive.read(ENUM_EXTENSIONS)))
                report["controller_enum_extensions"].update(ok=True, entries=entries)
            except (ValueError, TypeError, UnicodeError) as error:
                report["controller_enum_extensions"]["error"] = str(error)
            require(report["controller_enum_extensions"]["ok"], "Controller enum extension resource/declaration failed")
        report["required_classes"] = []
        required_classes = REQUIRED_CLASSES + (ALPHA4_CLASSES if version != "0.31.0-alpha.3" else ())
        required_classes += ALPHA5_CLASSES if has_subor else ()
        required_classes += ALPHA6_CLASSES if version in ("0.31.0-alpha.6", "0.31.0-alpha.7", "0.31.0-alpha.8", "0.31.0-alpha.9") else ()
        required_classes += ALPHA8_CLASSES if version in ("0.31.0-alpha.8", "0.31.0-alpha.9") else ()
        required_classes += ALPHA9_CLASSES if version == "0.31.0-alpha.9" else ()
        for name in required_classes:
            path = "cn/piq/fcarcade/" + name + ".class"
            raw = archive.read(path) if counts[path] == 1 else b""
            valid = len(raw) >= 8 and raw[:4] == b"\xca\xfe\xba\xbe" and struct.unpack_from(">H", raw, 6)[0] == 65
            report["required_classes"].append({"path": path, "count": counts[path], "ok": valid})
        require(all(entry["ok"] for entry in report["required_classes"]), "Required Java 21 classes missing, duplicated or invalid")
        report["runtime_blobs"] = asset_checks(archive, RUNTIME)
        for entry in report["runtime_blobs"]:
            raw = archive.read(entry["path"]) if entry["count"] == 1 else b""
            entry["header_ok"] = pe_x64(raw) if entry["path"].endswith(".dll") else raw[:8] == b"\0asm\x01\0\0\0"
            entry["ok"] &= entry["header_ok"]
        require(all(entry["ok"] for entry in report["runtime_blobs"]), "Windows x64 DLL/WASM bytes or headers differ from the reviewed runtime")
        icon = np.array(Image.open(io.BytesIO(archive.read("assets/piq_fc_arcade/textures/item/av_cable.png"))).convert("RGBA"))
        report["av_icon"] = {"size": list(icon.shape[1::-1]), "alpha_values": np.unique(icon[:, :, 3]).tolist(),
                             "transparent_rgb_zero": bool((icon[:, :, :3][icon[:, :, 3] == 0] == 0).all())}
        require(report["av_icon"]["size"] == [64, 64] and report["av_icon"]["alpha_values"] == [0, 255]
                and report["av_icon"]["transparent_rgb_zero"], "AV icon lost reviewed 64x64 binary transparency")
        proxy = json.loads(archive.read("assets/piq_fc_arcade/models/block/retro_tv_part.json"))
        require(proxy.get("elements") == [] and proxy.get("textures", {}).get("particle") == "piq_fc_arcade:block/home_retro_tv_housing", "TV proxy must stay invisible with housing particles")
        loot_path = "data/piq_fc_arcade/loot_table/blocks/retro_tv.json"
        loot = json.loads(archive.read(loot_path)) if counts[loot_path] == 1 else {}
        report["retro_tv_loot"] = {"path": loot_path, "count": counts[loot_path], "ok": any(
            entry.get("name") == "piq_fc_arcade:retro_tv" for pool in loot.get("pools", []) for entry in pool.get("entries", []))}
        require(report["retro_tv_loot"]["ok"], "TV survival drop is absent or duplicated")
        if has_subor:
            report["subor_mesh"] = validate_subor_mesh(json.loads(archive.read(SUBOR_MESH)), json.loads(SUBOR_SOURCE.read_bytes()))
            texture = archive.read(SUBOR_TEXTURE)
            with Image.open(io.BytesIO(texture)) as atlas:
                report["subor_atlas"] = {"size": list(atlas.size), "sha256": sha(texture), "ok": atlas.size == (2048, 2048) and sha(texture) == SUBOR_TEXTURE_SHA}
            require(report["subor_atlas"]["ok"], "Subor source atlas size/bytes changed")
            item_model = json.loads(archive.read("assets/piq_fc_arcade/models/item/subor_console.json"))
            shell_model = json.loads(archive.read("assets/piq_fc_arcade/models/block/subor_console.json"))
            blockstate = json.loads(archive.read("assets/piq_fc_arcade/blockstates/subor_console.json"))
            require(item_model.get("parent") == "builtin/entity" and shell_model.get("elements") == []
                    and shell_model.get("textures", {}).get("particle") == "piq_fc_arcade:block/home_subor_sb926"
                    and blockstate.get("variants") == {"": {"model": "piq_fc_arcade:block/subor_console"}}, "Subor static/BER resource route would omit or duplicate the shell")
            loot_path = "data/piq_fc_arcade/loot_table/blocks/subor_console.json"
            loot = json.loads(archive.read(loot_path)) if counts[loot_path] == 1 else {}
            report["subor_loot"] = {"path": loot_path, "count": counts[loot_path], "ok": counts[loot_path] == 1 and any(
                entry.get("name") == "piq_fc_arcade:subor_console" for pool in loot.get("pools", []) for entry in pool.get("entries", []))}
            require(report["subor_loot"]["ok"], "Subor survival drop is absent or duplicated")
        if version in ("0.31.0-alpha.6", "0.31.0-alpha.7", "0.31.0-alpha.8", "0.31.0-alpha.9"):
            report["subor_wide_mesh"] = validate_subor_wide_mesh(json.loads(archive.read(SUBOR_WIDE_MESH)),
                    json.loads(archive.read(SUBOR_MESH)), json.loads(SUBOR_SOURCE.read_bytes()), version == "0.31.0-alpha.7",compact=version in ("0.31.0-alpha.8","0.31.0-alpha.9"))
            require(json.loads(archive.read(SUBOR_PART_STATE)) == {"variants":{"":{"model":"piq_fc_arcade:block/subor_console"}}},
                    "Subor proxy must route to the original empty shell")
            require(sha(archive.read(LEGACY_BODY)) == LEGACY_BODY_SHA, "Dual legacy donor body changed")
            dual_validator = validate_header_dual_model if version == "0.31.0-alpha.9" else validate_native_dual_model if version == "0.31.0-alpha.8" else validate_lowered_dual_model if version == "0.31.0-alpha.7" else validate_dual_model
            report["dual_model"] = dual_validator(json.loads(archive.read(DUAL_BODY)), dual_source(),
                    json.loads(archive.read(LEGACY_BODY)), archive.read(LEGACY_TEXTURE))
            report["lcd_model"] = validate_lcd_model(json.loads(archive.read(LCD_BODY)), {
                    name:archive.read("assets/piq_fc_arcade/textures/block/home_retro_tv_"+name+".png")
                    for name in ("screen","dark","rim","back","metal","red","white","yellow")})
            empty = json.loads(archive.read("assets/piq_fc_arcade/models/block/dual_cabinet.json"))
            require(empty == {"textures":{"particle":"piq_fc_arcade:block/rocket_arcade_skin"},"elements":[]}, "Dual static shell must be empty")
            for suffix in ("dual_cabinet", "dual_cabinet_part"):
                require(json.loads(archive.read("assets/piq_fc_arcade/blockstates/"+suffix+".json")) ==
                        {"variants":{"":{"model":"piq_fc_arcade:block/dual_cabinet"}}}, "Dual block/proxy must use the empty route")
            require(json.loads(archive.read("assets/piq_fc_arcade/models/item/dual_cabinet.json")).get("parent") == "builtin/entity",
                    "Dual item must use the registered one-body renderer")
            require(json.loads(archive.read("assets/piq_fc_arcade/models/item/lcd_tv.json")).get("parent") == "piq_fc_arcade:block/home_lcd_tv",
                    "LCD item must use its one-block body")
            require(json.loads(archive.read("assets/piq_fc_arcade/blockstates/lcd_tv.json")) == {"variants":{
                    "facing="+direction:dict({"model":"piq_fc_arcade:block/home_lcd_tv"}, **({"y":i*90} if i else {}))
                    for i,direction in enumerate(("north","east","south","west"))}}, "LCD must rotate once in its four-state baked model")
            report["new_hardware_loot"]=[]
            for block in ("lcd_tv","dual_cabinet"):
                path="data/piq_fc_arcade/loot_table/blocks/"+block+".json"
                loot=json.loads(archive.read(path)) if counts[path]==1 else {}
                valid=counts[path]==1 and any(entry.get("name")=="piq_fc_arcade:"+block
                      for pool in loot.get("pools",[]) for entry in pool.get("entries",[]))
                report["new_hardware_loot"].append({"path":path,"count":counts[path],"ok":valid})
                require(valid,"New hardware loot declaration missing/duplicated: "+block)
        if version == "0.31.0-alpha.9":
            from verify_home_fc_alpha9_geometry import wide_lcd, cartridge
            prefix="assets/piq_fc_arcade/models/"
            report["wide_lcd_model"] = wide_lcd(json.loads(archive.read(prefix+"block/home_wide_lcd_tv.json")))
            report["cartridge_parts"] = cartridge({**{"board_"+str(i):json.loads(archive.read(prefix+"block/home_fc_board_"+str(i)+".json"))
                    for i in range(3)},"shell":json.loads(archive.read(prefix+"block/home_fc_cartridge_shell.json"))},
                    json.loads(archive.read(prefix+"block/home_fc_cartridge.json")))
            for suffix in ("fc_cartridge_board","fc_cartridge_shell"):
                require(json.loads(archive.read(prefix+"item/"+suffix+".json")).get("parent")=="builtin/entity","Cartridge part missing custom renderer route")
            require(json.loads(archive.read("assets/piq_fc_arcade/blockstates/wide_lcd_tv_part.json"))==
                    {"variants":{"":{"model":"piq_fc_arcade:block/dual_cabinet"}}},"Wide LCD proxy must stay empty")
            require(json.loads(archive.read(prefix+"item/wide_lcd_tv.json")).get("parent")=="piq_fc_arcade:block/home_wide_lcd_tv","Wide LCD item model route mismatch")
            for suffix in ("wide_lcd_tv","wide_lcd_tv_part"):
                loot=json.loads(archive.read("data/piq_fc_arcade/loot_table/blocks/"+suffix+".json"))
                require(loot.get("pools")==[],"Wide LCD drop must be owned exactly once by the assembly ledger")
        # The approved embedding can only replace the label and its outer 4px bleed.
        current_skin = np.array(Image.open(io.BytesIO(archive.read(SKIN))).convert("RGBA"))
        with zipfile.ZipFile(DELIVERY / "piq_fc_arcade-0.31.0-alpha.2.jar") as previous:
            old_skin = np.array(Image.open(io.BytesIO(previous.read(SKIN))).convert("RGBA"))
        valid_size = current_skin.shape == old_skin.shape == (1024, 1024, 4)
        outside = np.ones((1024, 1024), dtype=bool); outside[28:292, 28:548] = False
        report["default_cover_embedding"] = {"size": list(current_skin.shape[1::-1]), "label_uv_pixels": [32, 32, 544, 288],
            "allowed_bleed_pixels": [28, 28, 548, 292], "outside_unchanged": valid_size and bool((current_skin[outside] == old_skin[outside]).all())}
        require(report["default_cover_embedding"]["outside_unchanged"], "Default cover changed pixels outside the original UV + 4px bleed")
    report["bytecode_validation"] = bytecode_checks(jar, javap, version)
    require(all(entry["ok"] for entry in report["bytecode_validation"]), "A packaged protocol/registration/renderer contract failed")
    require(file_sha(jar) == report["jar_sha256"], "JAR changed during read-only audit")
    report["scope_limits"] = ["No Minecraft gameplay, multiplayer visual test or native runtime execution was performed.",
                              "All native checks here are immutable bytes and format headers; root supplies runtime smoke separately."]
    report["ok"] = not report["errors"]
    return report


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", type=Path, required=True)
    parser.add_argument("--jar-sha256", required=True)
    parser.add_argument("--version", choices=tuple(REVIEWS), default="0.31.0-alpha.3")
    parser.add_argument("--manifest", type=Path)
    parser.add_argument("--manifest-sha256", help="Optional fixed-review SHA for alpha.4/5/6/7; required for alpha.3")
    parser.add_argument("--baseline", type=Path, default=DELIVERY / "piq_fc_arcade-0.30.0-beta.3.jar")
    parser.add_argument("--javap", type=Path, default=Path("C:/Program Files/Microsoft/jdk-21.0.11.10-hotspot/bin/javap.exe"))
    parser.add_argument("--report", type=Path)
    parser.add_argument("--check-only", action="store_true")
    args = parser.parse_args()
    args.manifest = args.manifest or review_for(args.version)["manifest"]
    try:
        args.manifest_sha256 = reviewed_manifest_sha(args.version, args.manifest_sha256)
    except ValueError as error:
        parser.error(str(error))
    if args.check_only and args.report:
        parser.error("--check-only never writes --report")
    if args.report and args.report.parent.name != f"家用FC-{args.version}-模型预览":
        parser.error("Report must target the selected version's model preview directory")
    result = inspect(args.jar, args.manifest, args.jar_sha256, args.manifest_sha256, args.baseline, args.javap, args.version)
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({key: result[key] for key in ("ok", "jar_sha256", "jar_bytes", "jar_entry_count", "approved_asset_count", "unchanged_appearance_count", "errors")}, ensure_ascii=False, indent=2))
    if args.report:
        print("REPORT_SHA256=" + file_sha(args.report))
    raise SystemExit(0 if result["ok"] else 1)
