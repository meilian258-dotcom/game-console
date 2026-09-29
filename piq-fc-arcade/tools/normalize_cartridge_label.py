"""User-approved deterministic alpha.3 cover normalization; never redraw UV islands.

The image was generated with built-in image_gen. On 2026-09-08 the user explicitly
approved programmatic sizing and exact UV compositing. Original images and alpha.2
are retained. Only --apply replaces the pinned old default skin resource.
"""
from __future__ import annotations

import argparse
import hashlib
import io
import json
from pathlib import Path
import zipfile

import numpy as np
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
DESIGN = ROOT / "design/cartridge-default-alpha3"
ASSET_NAME = "assets/piq_fc_arcade/textures/block/home_fc_cartridge_skin.png"
TARGET = ROOT / "src/main/resources" / ASSET_NAME
ALPHA2 = ROOT.parent / "制作Mod/03-街机模拟/PIQ-FC街机/piq_fc_arcade-0.31.0-alpha.2.jar"
ALPHA2_SHA = "A928C4FBC984E756CFA939FEA8A60DB6000CCBB73C6AD19DE3B7413148F89D00"
ORIGINAL_SHA = "3F071A850BBD033F4311982B7510EA5C214DE4991B64130DC93E0F5A2616F14C"
GENERATED_SHA = "E0B67298F946D1F80830A48D77527B287C29C773E8838A675825847890D1D761"
LABEL_RECT = (32, 32, 544, 288)
PAD_RECT = (28, 28, 548, 292)


def sha(raw):
    return hashlib.sha256(raw).hexdigest().upper()


def original_bytes():
    if sha(ALPHA2.read_bytes()) != ALPHA2_SHA:
        raise ValueError("The immutable alpha.2 JAR changed")
    with zipfile.ZipFile(ALPHA2) as archive:
        raw = archive.read(ASSET_NAME)
    if sha(raw) != ORIGINAL_SHA:
        raise ValueError("The original cartridge skin changed")
    return raw


def normalize(image):
    if image.width != 2 * image.height:
        raise ValueError("Expected a complete 2:1 generated label, never crop it")
    rgba = image.convert("RGBA")
    if rgba.getchannel("A").getextrema() != (255, 255):
        raise ValueError("Do not silently replace generated transparency")
    return rgba.resize((512, 256), Image.Resampling.NEAREST)


def compose(base, label):
    if base.size != (1024, 1024) or label.size != (512, 256):
        raise ValueError("Unexpected original skin or normalized label dimensions")
    pixels = np.asarray(base.convert("RGBA")).copy()
    rgba = np.asarray(label.convert("RGBA"))
    if not (rgba[:, :, 3] == 255).all():
        raise ValueError("The label must be opaque")
    pixels[28:292, 28:548] = np.pad(rgba, ((4, 4), (4, 4), (0, 0)), mode="edge")
    return Image.fromarray(pixels)


def outside_equal(original, result):
    a, b = np.asarray(original.convert("RGBA")), np.asarray(result.convert("RGBA"))
    if a.shape != (1024, 1024, 4) or b.shape != a.shape:
        return False
    mask = np.ones((1024, 1024), dtype=bool)
    mask[28:292, 28:548] = False
    return bool(np.array_equal(a[mask], b[mask]))


def png(image):
    output = io.BytesIO()
    image.save(output, format="PNG")
    return output.getvalue()


def keep(path, raw):
    if path.exists():
        if path.read_bytes() != raw:
            raise ValueError(f"Refusing to overwrite a different preserved artifact: {path}")
    else:
        with path.open("xb") as stream:
            stream.write(raw)


def apply():
    generated = (DESIGN / "generic-cartridge-label-generated.png").read_bytes()
    if sha(generated) != GENERATED_SHA:
        raise ValueError("Generated input changed; review before processing")
    old = original_bytes()
    with Image.open(io.BytesIO(generated)) as source, Image.open(io.BytesIO(old)) as base:
        label = normalize(source)
        skin = compose(base, label)
        if not outside_equal(base, skin):
            raise AssertionError("A pixel outside the approved region changed")
        label_raw, skin_raw = png(label), png(skin)
        source_size = list(source.size)
    current = TARGET.read_bytes()
    if current != old and current != skin_raw:
        raise ValueError("Default skin was edited independently; refusing overwrite")
    keep(DESIGN / "home-fc-cartridge-skin-alpha2-original.png", old)
    keep(DESIGN / "generic-cartridge-label-512x256.png", label_raw)
    keep(DESIGN / "generic-cartridge-skin-1024.png", skin_raw)
    report = {
        "authorization": "User approved exact UV programmatic compositing on 2026-09-08",
        "generation_mode": "built-in image_gen; original generation prompt in GENERATION.md",
        "operation": "full-canvas NEAREST 512x256; paste only original label UV and 4px edge padding",
        "generated_size": source_size, "generated_sha256": sha(generated),
        "original_skin_sha256": sha(old), "label_sha256": sha(label_raw), "skin_sha256": sha(skin_raw),
        "label_rect_exclusive": LABEL_RECT, "approved_rect_exclusive": PAD_RECT,
        "outside_rgba_identical": True, "original_jar_sha256": ALPHA2_SHA,
    }
    keep(DESIGN / "normalization-review.json", (json.dumps(report, ensure_ascii=False, indent=2) + "\n").encode("utf-8"))
    if current != skin_raw:
        TARGET.write_bytes(skin_raw)
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apply", action="store_true", help="Apply the explicitly approved pinned transformation")
    args = parser.parse_args()
    if not args.apply:
        parser.error("Use --apply only for the approved default cartridge label replacement")
    apply()
