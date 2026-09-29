"""Import exactly the user's 03/04/05 hardware models; never extract arbitrary ZIP paths.

alpha.1 only remaps texture names. alpha.2 uniformly scales console geometry about
(8,0,8) by 0.6 and TV geometry about the origin by 2.0. Rotation angles, UVs and
every mechanical element remain unchanged. TV's solid grey idle screen becomes
opaque black; all other source PNGs are copied verbatim. --check never writes.
"""
import argparse
import copy
import hashlib
import io
import json
import math
from pathlib import Path
import zipfile

from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "src/main/resources/assets/piq_fc_arcade"
DEFAULT_ARCHIVE = Path(r"\\Kkp220\梅子共享\传输\最终模型合集_20260907.zip")
ARCHIVE_SHA = "1f1a5491a70b2c53f6134e2f08d1130574ce85552f9f340c296b866920009402"
PREFIX = "最终模型合集_20260907/"
SPECS = (
    ("03_红白机", "famicom.json", "home_famicom_console", "famicom:block/famicom",
     "piq_fc_arcade:block/home_famicom_console", 208),
    ("04_复古彩电", "retro_tv.json", "home_retro_tv", "retro_tv:block/",
     "piq_fc_arcade:block/home_retro_tv_", 182),
    ("05_FC火箭车卡带", "fc_cartridge.json", "home_fc_cartridge", "fc_cartridge:block/skin",
     "piq_fc_arcade:block/home_fc_cartridge_skin", 107),
)
TV_TEXTURES = ("back", "button", "dark", "fascia", "glass_edge", "housing", "metal",
               "red", "rim", "screen", "white", "yellow")
WORLD_TRANSFORMS = {"home_famicom_console": (0.6, (8, 0, 8)),
                    "home_retro_tv": (2.0, (0, 0, 0))}
ALPHA1_JAR = ROOT.parent / "制作Mod/03-街机模拟/PIQ-FC街机/piq_fc_arcade-0.31.0-alpha.1.jar"
DISPLAY_WRAPPERS = {"models/block/famicom_console.json": "home_famicom_console",
                    "models/item/retro_tv.json": "home_retro_tv"}


def rotate_xyz(point, angles, left=False):
    """JOML rotationXYZ used by ItemTransform: column vectors, Rz then Ry then Rx."""
    rx, ry, rz = [math.radians(value) for value in angles]
    if left:
        ry, rz = -ry, -rz
    x, y, z = point
    x, y = math.cos(rz) * x - math.sin(rz) * y, math.sin(rz) * x + math.cos(rz) * y
    x, z = math.cos(ry) * x + math.sin(ry) * z, -math.sin(ry) * x + math.cos(ry) * z
    return (x, math.cos(rx) * y - math.sin(rx) * z, math.sin(rx) * y + math.cos(rx) * z)


def compensate_display(display, scale, pivot):
    """Keep original item-space vertices, including MC's mirrored left-hand transform."""
    result = {}
    for context in ("gui", "ground", "fixed", "head", "thirdperson_righthand", "thirdperson_lefthand",
                    "firstperson_righthand", "firstperson_lefthand"):
        old = display.get(context, display.get(context.replace("lefthand", "righthand"), {}))
        current = copy.deepcopy(old)
        if any(old.get("right_rotation", [0, 0, 0])):
            raise ValueError("Unexpected extra item rotation; manual review required")
        old_scale = old.get("scale", [1, 1, 1])
        extra = [old_scale[i] * (1 - scale) / scale * (pivot[i] - 8) for i in range(3)]
        left = context.endswith("lefthand")
        rotated = rotate_xyz(extra, old.get("rotation", [0, 0, 0]), left)
        translation = old.get("translation", [0, 0, 0])
        current["translation"] = [round(translation[i] - rotated[i] * (-1 if left and i == 0 else 1), 12)
                                  for i in range(3)]
        current["scale"] = [round(value / scale, 12) for value in old_scale]
        result[context] = current
    return result


def scale_model(model, scale, pivot):
    """Scale cuboids and their rotation origins together; never touch UV/angles."""
    result = copy.deepcopy(model)
    def point(values):
        return [round(pivot[i] + scale * (value - pivot[i]), 12) for i, value in enumerate(values)]
    for element in result["elements"]:
        element["from"], element["to"] = point(element["from"]), point(element["to"])
        if "rotation" in element:
            element["rotation"]["origin"] = point(element["rotation"]["origin"])
    return result


def sha(data):
    return hashlib.sha256(data).hexdigest()


def collect(archive, layout="alpha2"):
    """Return a bounded destination map and source audit, without filesystem writes."""
    if layout not in ("alpha1", "alpha2"):
        raise ValueError("Unknown hardware layout")
    files, audit = {}, {"archive_sha256": sha(archive), "layout": layout, "models": [], "textures": []}
    if audit["archive_sha256"] != ARCHIVE_SHA:
        raise ValueError("Archive differs from the approved original; refusing import")
    with zipfile.ZipFile(io.BytesIO(archive)) as source:
        for folder, filename, target, old, new, count in SPECS:
            original = source.read(PREFIX + folder + "/" + filename)
            mapped = original.decode("utf-8-sig").replace(old, new).encode("utf-8")
            before, after = json.loads(original), json.loads(mapped)
            assert len(after["elements"]) == count
            assert before["elements"] == after["elements"]
            assert before.get("display") == after.get("display")
            transform = None
            if layout == "alpha2" and target in WORLD_TRANSFORMS:
                scale, pivot = WORLD_TRANSFORMS[target]
                scaled = scale_model(after, scale, pivot)
                for old_element, new_element in zip(after["elements"], scaled["elements"]):
                    assert old_element["faces"] == new_element["faces"]
                    if "rotation" in old_element:
                        assert old_element["rotation"]["angle"] == new_element["rotation"]["angle"]
                mapped = (json.dumps(scaled, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
                transform = {"scale": scale, "pivot_model_units": list(pivot)}
            files["models/block/" + target + ".json"] = mapped
            audit["models"].append({"name": target, "elements": count,
                                    "original_sha256": sha(original), "sha256": sha(mapped),
                                    "world_transform": transform})
        png_specs = [
            ("03_红白机/textures/famicom.png", "home_famicom_console"),
            ("05_FC火箭车卡带/FC卡带_完整UV.png", "home_fc_cartridge_skin"),
            *[("04_复古彩电/textures/" + name + ".png", "home_retro_tv_" + name)
              for name in TV_TEXTURES],
        ]
        for entry, name in png_specs:
            original = source.read(PREFIX + entry)
            png = original
            image = Image.open(io.BytesIO(original))
            if name == "home_retro_tv_screen":
                # Deterministic idle-screen normalization, not a redesign of the model/skin.
                output = io.BytesIO()
                Image.new("RGBA", image.size, (0, 0, 0, 255)).save(output, format="PNG")
                png = output.getvalue()
            files["textures/block/" + name + ".png"] = png
            audit["textures"].append({"name": name, "size": list(image.size),
                                      "original_sha256": sha(original), "sha256": sha(png),
                                      "black_idle_screen": name == "home_retro_tv_screen"})
    if layout == "alpha2":
        frozen = json.loads((ROOT / "tools/home-fc-reviewed-assets.json").read_bytes())["assets"]
        with zipfile.ZipFile(ALPHA1_JAR) as baseline:
            for relative, name in DISPLAY_WRAPPERS.items():
                entry = "assets/piq_fc_arcade/" + relative
                original = baseline.read(entry)
                if sha(original) != frozen[entry]:
                    raise ValueError("Frozen alpha.1 item wrapper mismatch: " + entry)
                wrapper = json.loads(original)
                wrapper["display"] = compensate_display(wrapper.get("display", {}), *WORLD_TRANSFORMS[name])
                files[relative] = (json.dumps(wrapper, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
        audit["item_display_baseline"] = {"path": str(ALPHA1_JAR), "sha256": sha(ALPHA1_JAR.read_bytes()),
                                           "policy": "Inverse scale and rotated translation preserve all item-space vertices"}
    return files, audit


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--archive", type=Path, default=DEFAULT_ARCHIVE)
    parser.add_argument("--check", action="store_true")
    parser.add_argument("--layout", choices=("alpha1", "alpha2"), default="alpha2")
    parser.add_argument("--update-reviewed", action="store_true",
                        help="Permit only the two scaled models to replace exact frozen alpha.1 bytes")
    args = parser.parse_args()
    files, audit = collect(args.archive.read_bytes(), args.layout)
    frozen = json.loads((ROOT / "tools/home-fc-reviewed-assets.json").read_bytes())["assets"]
    for relative, data in files.items():
        destination = ASSETS / relative
        if destination.exists():
            if destination.read_bytes() != data:
                may_scale = (args.update_reviewed and not args.check and args.layout == "alpha2"
                             and relative in ({"models/block/" + name + ".json" for name in WORLD_TRANSFORMS}
                                              | set(DISPLAY_WRAPPERS))
                             and sha(destination.read_bytes()) == frozen.get("assets/piq_fc_arcade/" + relative))
                if not may_scale:
                    raise ValueError(f"Existing asset differs: {relative}; refusing overwrite")
                destination.write_bytes(data)
        elif args.check:
            raise ValueError(f"Missing asset: {relative}")
        else:
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_bytes(data)
    print(json.dumps({"ok": True, "mode": "check" if args.check else "import",
                      "asset_count": len(files), **audit}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
