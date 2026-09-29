"""Refresh only the authorized leftover FC plate; never run the full generator."""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import tempfile

import numpy as np
from PIL import Image, ImageDraw, ImageFont


ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "piq-fc-arcade/src/main/resources/assets/piq_fc_arcade/textures/block/famicom_brand.png"
GENERATOR = ROOT / "piq-fc-arcade/tools/generate_famicom_textures.py"
EXPECTED_BEFORE = "B6F45658964EBF1A1FD333ECE31BE5F0F7E76F4FA3CAED0373DDF4FEF6F8D14B"
TEXT_BOX = (12, 18, 244, 48)  # Exclusive right/bottom; excludes both gold rules.
PROJECTS = ("piq-fc-arcade", "piq-sfc-home", "piq-gba")


def sha(raw: bytes) -> str:
    return hashlib.sha256(raw).hexdigest().upper()


def protected() -> dict[str, str]:
    result = {}
    for project in PROJECTS:
        for path in sorted((ROOT / project / "src/main/resources").rglob("*")):
            if path == SOURCE or not path.is_file():
                continue
            if "models" in path.parts or path.suffix.lower() in (".png", ".mesh", ".obj", ".mtl", ".bbmodel"):
                if path.is_symlink():
                    raise ValueError("Unexpected protected asset symlink")
                result[path.relative_to(ROOT).as_posix()] = sha(path.read_bytes())
    return result


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--apply", action="store_true")
    args = parser.parse_args()
    output = args.output.resolve()
    parent = ROOT / "out/brand40"
    if output == parent or not output.is_relative_to(parent) or output.exists():
        raise ValueError("Use a new independent audit directory beneath out/brand40")
    before = SOURCE.read_bytes()
    if SOURCE.is_symlink() or sha(before) != EXPECTED_BEFORE:
        raise ValueError("Unreviewed FC plate identity")
    protected_before = protected()
    generator_before = sha(GENERATOR.read_bytes())
    spec = importlib.util.spec_from_file_location("fc_texture_generator_plate_only", GENERATOR)
    generator = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(generator)
    output.mkdir(parents=True)
    generator.OUTPUT = output
    generator.save_brand()  # Do NOT call main(), save_control() or save_controller().
    candidate = output / "famicom_brand.png"
    after = candidate.read_bytes()
    original = Image.open(io.BytesIO(before)); original.load()
    result = Image.open(io.BytesIO(after)); result.load()
    if original.size != (256, 64) or result.size != original.size or original.mode != "RGBA" or result.mode != "RGBA":
        raise ValueError("Unexpected image dimensions or mode")
    a, b = np.asarray(original), np.asarray(result)
    mask = np.zeros(a.shape[:2], dtype=bool)
    x0, y0, x1, y1 = TEXT_BOX
    mask[y0:y1, x0:x1] = True
    if not np.array_equal(a[~mask], b[~mask]) or not np.array_equal(a[:, :, 3], b[:, :, 3]):
        raise ValueError("Non-text pixel or alpha changed")
    if protected() != protected_before or SOURCE.read_bytes() != before or sha(GENERATOR.read_bytes()) != generator_before:
        raise ValueError("Concurrent asset/generator modification")
    with (output / "famicom_brand-before.png").open("xb") as stream:
        stream.write(before)
    sheet = Image.new("RGB", (1120, 220), "#e7e4dc")
    draw = ImageDraw.Draw(sheet)
    font = ImageFont.truetype("C:/Windows/Fonts/arial.ttf", 22)
    for index, (label, image) in enumerate((("BEFORE / RELEASE 39", original), ("AFTER / FAMILY COMPUTER", result))):
        draw.text((16+index*560, 18), label, fill="#222222", font=font)
        tile = image.resize((512, 128), Image.Resampling.NEAREST)
        sheet.paste(tile, (16+index*560, 64), tile)
    sheet.save(output / "before-after.png", format="PNG")
    if args.apply:
        descriptor, name = tempfile.mkstemp(prefix=".brand40-", suffix=".tmp", dir=SOURCE.parent)
        temporary = Path(name)
        try:
            with os.fdopen(descriptor, "wb") as stream:
                stream.write(after); stream.flush(); os.fsync(stream.fileno())
            if SOURCE.read_bytes() != before:
                raise ValueError("Concurrent source change before apply")
            os.replace(temporary, SOURCE)
        finally:
            if temporary.exists():
                temporary.unlink()
        if SOURCE.read_bytes() != after:
            raise ValueError("Applied source readback mismatch")
    protected_after = protected()
    if protected_before != protected_after:
        raise ValueError("Protected models/textures changed")
    report = dict(schema="block-arcade-brand40-legacy-plate-1", ok=True, applied=args.apply,
                  created_at=datetime.now(timezone.utc).isoformat(), source=SOURCE.relative_to(ROOT).as_posix(),
                  before_sha256=sha(before), after_sha256=sha(after), size=[256,64], mode="RGBA", text="FAMILY COMPUTER",
                  approved_text_box=TEXT_BOX, changed_pixels=int(np.any(a != b, axis=2).sum()),
                  outside_text_box_identical=True, alpha_identical=True, source_readback_identical=True,
                  generator=GENERATOR.relative_to(ROOT).as_posix(), generator_sha256=generator_before,
                  only_generator_branch_called="save_brand", full_generator_not_called=True,
                  protected_asset_count=len(protected_before), protected_before=protected_before,
                  protected_after=protected_after, protected_unchanged=True,
                  note="The earlier five-atlas restoration audit is retained unchanged. No models or JARs were rebuilt.")
    with (output / "legacy-plate-audit.json").open("x", encoding="utf-8") as stream:
        json.dump(report, stream, ensure_ascii=False, indent=2); stream.write("\n")
    print(json.dumps(dict(ok=True, applied=args.apply, after_sha256=sha(after), changed_pixels=report["changed_pixels"],
                         protected_assets=len(protected_before), output=str(output)), ensure_ascii=True))


if __name__ == "__main__":
    main()
