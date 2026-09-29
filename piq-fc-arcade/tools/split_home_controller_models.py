"""Mechanical partition of the reviewed alpha.2 console, never remodel or repaint.

The original complete model remains the inventory/placement item. The world
model is body + two separately dockable groups; held models only translate and
uniformly unscale the original controller cubes. Run --write for new outputs.
"""
from __future__ import annotations

import argparse
import copy
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "src/main/resources/assets/piq_fc_arcade"
SOURCE = ASSETS / "models/block/home_famicom_console.json"
SOURCE_SHA = "609F0E125C77EF480701A87D8DAE4D7B0ADAC62C00A97A5798B42AE2AD23C8DA"
PREFIXES = (("一号", "Ⅰ手柄"), ("二号", "Ⅱ手柄"))


def partition(source):
    groups = [[e for e in source["elements"] if e["name"].startswith(names)] for names in PREFIXES]
    body = [e for e in source["elements"] if not e["name"].startswith(PREFIXES[0] + PREFIXES[1])]
    if [len(body), *(len(g) for g in groups)] != [101, 47, 60]:
        raise ValueError("Reviewed console partition counts changed")
    return body, groups


def held_model(source, elements, prefix):
    cubes = copy.deepcopy([e for e in elements if e["name"].startswith(prefix)])
    lo = [min(e["from"][i] for e in cubes) for i in range(3)]
    hi = [max(e["to"][i] for e in cubes) for i in range(3)]
    center = [(lo[i] + hi[i]) / 2 for i in range(3)]
    def transform(point):
        return [round(8 + (point[i] - center[i]) / 0.6, 9) for i in range(3)]
    for cube in cubes:
        cube["from"], cube["to"] = transform(cube["from"]), transform(cube["to"])
        if "rotation" in cube:
            cube["rotation"]["origin"] = transform(cube["rotation"]["origin"])
    return dict(source, elements=cubes), center


def collect():
    original = SOURCE.read_bytes()
    if hashlib.sha256(original).hexdigest().upper() != SOURCE_SHA:
        raise ValueError("Source differs from frozen alpha.2 console")
    source = json.loads(original)
    body, groups = partition(source)
    files = {"models/block/home_console_body.json": dict(source, elements=body)}
    for port, elements in enumerate(groups):
        files[f"models/block/home_controller_p{port + 1}_docked.json"] = dict(source, elements=elements)
        held, _ = held_model(source, elements, PREFIXES[port][0])
        files[f"models/block/home_controller_p{port + 1}_held.json"] = held
    files["models/item/fc_controller.json"] = {
        "parent": "builtin/entity", "gui_light": "front",
        "textures": {"particle": "piq_fc_arcade:block/home_famicom_console"},
        "display": {
            "gui": {"rotation": [12, 0, 0], "scale": [0.95, 0.95, 0.95]},
            "ground": {"translation": [0, 2, 0], "scale": [0.6, 0.6, 0.6]},
            "fixed": {"scale": [0.9, 0.9, 0.9]},
            # Alpha.4 public hand hooks: .6 third-person size, inward wrists, neutral first-person display.
            "thirdperson_righthand": {"rotation": [-8.23849004, -25.80682901, 28.91855928], "translation": [-2.97557615, -2.01331410, -1.16238744], "scale": [0.6, 0.6, 0.6]},
            "thirdperson_lefthand": {"rotation": [-8.23849004, -25.80682901, 28.91855928], "translation": [-2.97557615, -2.01331410, -1.16238744], "scale": [0.6, 0.6, 0.6]},
            "firstperson_righthand": {"rotation": [0, 0, 0], "translation": [0, 0, 0], "scale": [0.85, 0.85, 0.85]},
            "firstperson_lefthand": {"rotation": [0, 0, 0], "translation": [0, 0, 0], "scale": [0.85, 0.85, 0.85]},
        },
    }
    return files


def write_new(files):
    encoded = {ASSETS / name: (json.dumps(value, ensure_ascii=False, separators=(",", ":")) + "\n").encode("utf-8")
               for name, value in files.items()}
    for path, data in encoded.items():
        if path.is_symlink() or (path.exists() and path.read_bytes() != data):
            raise FileExistsError(f"Refusing to overwrite changed asset: {path}")
    for path, data in encoded.items():
        path.parent.mkdir(parents=True, exist_ok=True)
        if not path.exists():
            with path.open("xb") as out:
                out.write(data)
        print(f"{path.relative_to(ASSETS)} {len(data)} {hashlib.sha256(data).hexdigest().upper()}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--write", action="store_true")
    args = parser.parse_args()
    outputs = collect()
    if args.write:
        write_new(outputs)
    else:
        for name, value in outputs.items():
            print(name, len(value.get("elements", [])))
