"""Restore only the twelve user-approved device-label UV regions from fixed originals.

This is an explicitly authorized raster edit, not an asset/model rebuild. FC's
small Nintendo wordmark is removed while its original FAMILY COMPUTER plate and
gold rule are retained. Other devices retain the original labels requested by
the user. Historical archives and release38 derivation tools are read-only.
"""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import io
import json
import os
from pathlib import Path
import tempfile
import zipfile

import numpy as np
from PIL import Image, ImageDraw, ImageFont


ROOT = Path(__file__).resolve().parents[2]
BASE = ROOT / "piq-fc-arcade/build/review-runtime37-v1"
ARCHIVES = {
    "fc": ("piq_fc_arcade-0.31.0-alpha.37.jar", "809A4FEE17B8805C642C4DF1A0272B5C369D02B179A98E41253262E268ACD406"),
    "sfc": ("piq_sfc-0.1.0-alpha.21.jar", "62A53BEF68D9D181DFEC74E2926F6D431F61D6CD8B989C62DF36593771C94774"),
    "gba": ("piq_gba-0.1.0-alpha.5.jar", "DCB0F05BF4CD3880430DB9985B6E37DA1B116509A4B7A0E84F59CCCAF679BDB1"),
}
PROJECTS = {"fc": "piq-fc-arcade", "sfc": "piq-sfc-home", "gba": "piq-gba"}
SPECS = [
    dict(kind="fc", entry="assets/piq_fc_arcade/textures/block/home_famicom_console.png",
         original="5EA8EE7C97F54BC0FC83949FDC7F196C4F053A798EC98B1AEF9D18579D852935",
         current="423D530669BC3BB61DEF666811F85CCBFF7812DAF1E1AB1B9A08090FE464DD61",
         size=(1024, 1024), regions=[(16, 897, 1008, 1021)]),
    dict(kind="fc", entry="assets/piq_fc_arcade/textures/block/home_subor_sb926.png",
         original="39A7F6DE2FE4CB669A0CE4E88BFDAE23C234CE7F9A7314A150D13B6EB2A89C4C",
         current="D15BE845F4279F6DC304C00D49CCE3383C97C4AFB1E087E62F9B6E0DC2BF9414",
         size=(2048, 2048), regions=[(8, 528, 520, 656), (528, 528, 1168, 608), (1176, 528, 1496, 592)]),
    dict(kind="fc", entry="assets/piq_fc_arcade/textures/item/zapper/skin.png",
         original="AB5C925B7BD21AD2CBBFCC96A0F38CC4FE6C4E9013BCE57DF8F866E9EA16167F",
         current="A5471BF21972C6C247F975F3735C3731AD1CC24BE8A3556BBDA7D76B47FE58E0",
         size=(2048, 2048), regions=[(32, 32, 662, 203), (708, 32, 1344, 189), (32, 1064, 654, 1190)]),
    dict(kind="sfc", entry="assets/piq_sfc_home/textures/block/user_sfc_20260911.png",
         original="4BBBA0F53697D69A919F5FC12750E608AA50A4D71D8281F23D6428A5D02BD920",
         current="935A3E0D21CB15810854F4C6B091E0BFDAEAE539734AB2FA2AC06B16A8061330",
         size=(2048, 2048), regions=[(566, 563, 1072, 611), (1080, 563, 1366, 611)]),
    dict(kind="gba", entry="assets/piq_gba/textures/item/handheld.png",
         original="376FB935DEB9D6F5F4682A24FC4DF94D5EF9A5793D14B4255F573FE6FF921BCC",
         current="1AB8E0C85A3FC42466E5A2269439031312EA3EF4C9AE002380F25DDD2E50D0FA",
         size=(2048, 2048), regions=[(1640, 32, 1910, 108), (880, 534, 1580, 629), (44, 527, 720, 613)]),
]

# Original 1024 atlas coordinates, right/bottom exclusive. This encloses only
# the small wordmark and its registration mark, not FAMILY COMPUTER or its TM.
FC_WORDMARK_BOX = (735, 971, 864, 1003)
FC_RULE_REFERENCE_X = 900  # Clean original background and continuous gold rule.


def sha(raw: bytes) -> str:
    return hashlib.sha256(raw).hexdigest().upper()


def load_image(raw: bytes, size: tuple[int, int]) -> Image.Image:
    image = Image.open(io.BytesIO(raw))
    image.load()
    if image.mode != "RGBA" or image.size != size:
        raise ValueError("Unreviewed atlas mode or size")
    return image


def exclusive(path: Path, raw: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    if path.exists():
        if path.read_bytes() != raw:
            raise ValueError("Refusing conflicting audit artifact: " + str(path))
    else:
        with path.open("xb") as stream:
            stream.write(raw)


def protected_assets() -> dict[str, str]:
    targets = {str(Path(PROJECTS[s["kind"]]) / "src/main/resources" / s["entry"]).replace("\\", "/") for s in SPECS}
    result = {}
    for project in PROJECTS.values():
        for path in sorted((ROOT / project / "src/main/resources").rglob("*")):
            if not path.is_file():
                continue
            relative = path.relative_to(ROOT).as_posix()
            if relative in targets:
                continue
            if "models" in path.parts or path.suffix.lower() in (".png", ".mesh", ".obj", ".mtl", ".bbmodel"):
                if path.is_symlink():
                    raise ValueError("Unexpected protected asset symlink: " + str(path))
                result[relative] = sha(path.read_bytes())
    return result


def restore_atlas(raw: bytes, current: bytes, spec: dict) -> tuple[bytes, dict, tuple]:
    if sha(raw) != spec["original"] or sha(current) != spec["current"]:
        raise ValueError("Unexpected original/current atlas identity: " + spec["entry"])
    original_image = load_image(raw, spec["size"])
    current_image = load_image(current, spec["size"])
    original = np.asarray(original_image)
    before = np.asarray(current_image)
    restored = np.array(before, copy=True)
    mask = np.zeros(before.shape[:2], dtype=bool)
    for box in spec["regions"]:
        x0, y0, x1, y1 = box
        if not (0 <= x0 < x1 <= spec["size"][0] and 0 <= y0 < y1 <= spec["size"][1]) or mask[y0:y1, x0:x1].any():
            raise ValueError("Invalid or overlapping approved UV region")
        mask[y0:y1, x0:x1] = True
        restored[y0:y1, x0:x1] = original[y0:y1, x0:x1]
    if not np.array_equal(original[~mask], before[~mask]):
        raise ValueError("Original/current differ outside authorized labels")
    expected = np.array(original, copy=True)
    if spec["entry"].endswith("home_famicom_console.png"):
        x0, y0, x1, y1 = FC_WORDMARK_BOX
        if not mask[y0:y1, x0:x1].all():
            raise ValueError("FC removal leaves approved label region")
        # Each original row supplies its own red/gold/antialiased edge colour.
        # Only RGB is copied; original alpha remains pixel-for-pixel identical.
        restored[y0:y1, x0:x1, :3] = original[y0:y1, FC_RULE_REFERENCE_X:FC_RULE_REFERENCE_X+1, :3]
        expected[y0:y1, x0:x1, :3] = original[y0:y1, FC_RULE_REFERENCE_X:FC_RULE_REFERENCE_X+1, :3]
    if not np.array_equal(restored[~mask], before[~mask]) or not np.array_equal(restored[:, :, 3], before[:, :, 3]):
        raise ValueError("Unexpected out-of-region or alpha change")
    if not np.array_equal(restored, expected):
        raise ValueError("Unexpected difference from approved restoration")
    result_image = Image.fromarray(restored)
    # Four unmodified originals can preserve their complete original PNG bytes.
    if np.array_equal(restored, original):
        result_raw = raw
    else:
        buffer = io.BytesIO()
        result_image.save(buffer, format="PNG", compress_level=9)
        result_raw = buffer.getvalue()
    if not np.array_equal(np.asarray(load_image(result_raw, spec["size"])), restored):
        raise ValueError("PNG encoding did not round-trip")
    detail = dict(entry=spec["entry"], source=f'{PROJECTS[spec["kind"]]}/src/main/resources/{spec["entry"]}',
                  original_sha256=sha(raw), before_sha256=sha(current), after_sha256=sha(result_raw),
                  size=spec["size"], mode="RGBA", approved_regions=spec["regions"],
                  changed_pixels=int(np.any(before != restored, axis=2).sum()),
                  changed_from_original_pixels=int(np.any(original != restored, axis=2).sum()),
                  outside_regions_identical=True, alpha_identical=True,
                  original_png_bytes_restored=result_raw == raw,
                  regions=[dict(box=box, changed_pixels=int(np.any(before[box[1]:box[3], box[0]:box[2]] != restored[box[1]:box[3], box[0]:box[2]], axis=2).sum())) for box in spec["regions"]])
    return result_raw, detail, (original_image, current_image, result_image)


def png(image: Image.Image) -> bytes:
    stream = io.BytesIO()
    image.save(stream, format="PNG", compress_level=9)
    return stream.getvalue()


def make_preview(images: tuple, boxes: list[tuple], title: str) -> bytes:
    width, column, row_height = 1536, 512, 160
    sheet = Image.new("RGB", (width, 96 + len(boxes) * row_height), "#ece9df")
    draw = ImageDraw.Draw(sheet)
    font = ImageFont.truetype("C:/Windows/Fonts/arial.ttf", 19)
    draw.text((18, 14), title, fill="#222222", font=font)
    for col, label in enumerate(("ORIGINAL FIXED BASELINE", "BEFORE / RELEASE 39", "RESTORED / BRAND 40")):
        draw.text((18+col*column, 46), label, fill="#222222", font=font)
    for index, box in enumerate(boxes):
        for col, image in enumerate(images):
            crop = image.crop(box)
            ratio = min(480/crop.width, 120/crop.height)
            crop = crop.resize((max(1, int(crop.width*ratio)), max(1, int(crop.height*ratio))), Image.Resampling.NEAREST)
            position = (col*column+16, 90+index*row_height)
            sheet.paste(crop, position, crop)
            draw.text((position[0], position[1]+124), str(box), font=font, fill="#444444")
    return png(sheet)


def replace_exact(path: Path, before: bytes, after: bytes) -> None:
    if path.is_symlink() or path.read_bytes() != before:
        raise ValueError("Source changed before replacement: " + str(path))
    descriptor, name = tempfile.mkstemp(prefix=".brand40-", suffix=".tmp", dir=path.parent)
    temporary = Path(name)
    try:
        with os.fdopen(descriptor, "wb") as stream:
            stream.write(after)
            stream.flush()
            os.fsync(stream.fileno())
        if path.read_bytes() != before:
            raise ValueError("Source changed during replacement: " + str(path))
        os.replace(temporary, path)
    finally:
        if temporary.exists():
            temporary.unlink()
    if path.read_bytes() != after:
        raise ValueError("Source readback mismatch: " + str(path))


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--apply", action="store_true")
    args = parser.parse_args()
    output = args.output.resolve()
    allowed_output = ROOT / "out/brand40"
    if not output.is_relative_to(allowed_output) or output == allowed_output:
        raise ValueError("Use a new independent run directory under out/brand40")
    if output.exists():
        raise ValueError("Refusing to overwrite an existing audit run")
    protected_before = protected_assets()
    originals, archive_audit = {}, {}
    for kind, (name, expected_sha) in ARCHIVES.items():
        path = BASE / name
        raw = path.read_bytes()
        if sha(raw) != expected_sha:
            raise ValueError("Frozen baseline changed: " + str(path))
        archive_audit[kind] = dict(path=path.relative_to(ROOT).as_posix(), sha256=sha(raw), bytes=len(raw))
        with zipfile.ZipFile(io.BytesIO(raw)) as archive:
            for spec in SPECS:
                if spec["kind"] == kind:
                    originals[spec["entry"]] = archive.read(spec["entry"])
    staged, audits = [], []
    for spec in SPECS:
        path = ROOT / PROJECTS[spec["kind"]] / "src/main/resources" / spec["entry"]
        if path.is_symlink():
            raise ValueError("Unexpected source symlink: " + str(path))
        current = path.read_bytes()
        result, audit, images = restore_atlas(originals[spec["entry"]], current, spec)
        staged.append((path, current, result))
        audits.append(audit)
        relative = path.relative_to(ROOT)
        exclusive(output / "before" / relative, current)
        exclusive(output / "restored" / relative, result)
        name = f'{spec["kind"]}-{path.stem}'
        exclusive(output / "previews" / f"{name}.png", make_preview(images, spec["regions"], name))
        if spec["entry"].endswith("home_famicom_console.png"):
            exclusive(output / "previews/fc-wordmark-detail.png", make_preview(images, [(700, 950, 900, 1021)], "FC Nintendo removal; original FAMILY COMPUTER / logo / rule retained"))
    if protected_assets() != protected_before:
        raise ValueError("Protected model/texture changed during preparation")
    for path, before, _ in staged:
        if path.read_bytes() != before:
            raise ValueError("Atlas source changed during preparation")
    if args.apply:
        for path, before, after in staged:
            replace_exact(path, before, after)
    protected_after = protected_assets()
    if protected_after != protected_before:
        raise ValueError("Protected models/other textures changed")
    for kind, archive in archive_audit.items():
        if sha((ROOT / archive["path"]).read_bytes()) != archive["sha256"]:
            raise ValueError("Frozen baseline changed during operation")
    models = [key for key in protected_before if "/models/" in key or Path(key).suffix in (".mesh", ".obj", ".mtl", ".bbmodel")]
    report = dict(schema="block-arcade-brand40-textures-1", ok=True, applied=args.apply,
                  created_at=datetime.now(timezone.utc).isoformat(), assets=audits, asset_count=len(audits),
                  approved_uv_regions=sum(len(spec["regions"]) for spec in SPECS),
                  fc_nintendo_removal=dict(box=FC_WORDMARK_BOX, right_bottom_exclusive=True,
                       method="Copy RGB of clean original column 900 row-by-row, retaining original per-pixel alpha",
                       original_family_computer_and_left_logo_retained=True),
                  protected_asset_count=len(protected_before), model_count=len(models), protected_unchanged=True,
                  protected_before=protected_before, protected_after=protected_after,
                  baseline_archives=archive_audit, baseline_archives_unchanged=True,
                  caveats=["Only the five authorized atlases / twelve prior label regions were edited.",
                           "Nintendo labels on SFC, GBA and Zapper were restored as requested; only FC console excludes Nintendo.",
                           "Original learning-computer atlas says SUBOR / 小霸王, not the previous proposed 大霸王.",
                           "This does not remove internal piq namespaces, author credits or licenses.",
                           "No legal clearance, Minecraft visual verification or JAR packaging was performed."])
    exclusive(output / "texture-restoration-audit.json", (json.dumps(report, ensure_ascii=False, indent=2)+"\n").encode("utf-8"))
    print(json.dumps(dict(ok=True, applied=args.apply, assets=len(audits), protected_assets=len(protected_before),
                         models=len(models), output=str(output), hashes={a["source"]: a["after_sha256"] for a in audits}), ensure_ascii=False))


if __name__ == "__main__":
    main()
