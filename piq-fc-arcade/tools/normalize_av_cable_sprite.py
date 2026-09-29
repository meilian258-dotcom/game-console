"""User-authorized deterministic AV sprite normalization; no generation or repainting.

Only the pinned original is accepted. Existing different outputs are never overwritten.
The model JSON reference is deliberately not edited by this tool.
"""
from __future__ import annotations

import argparse
import hashlib
import io
import json
from pathlib import Path

from PIL import Image, __version__ as PILLOW_VERSION

PROJECT = Path(__file__).resolve().parents[1]
SOURCE = PROJECT / "design/av-cable-alpha2/av-cable-generated-original.png"
SOURCE_SHA256 = "0898A906507FC7D2296B7973D87F9117FCFD54B9093DF4E1F0A2699E0F77B66F"
OUTPUT = PROJECT / "src/main/resources/assets/piq_fc_arcade/textures/item/av_cable.png"
ARTIFACT_DIR = PROJECT.parent / "制作Mod/03-街机模拟/PIQ-FC街机/AV线像素图-alpha2"
ALPHA_THRESHOLD = 128


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest().upper()


def png_bytes(image: Image.Image) -> bytes:
    buffer = io.BytesIO()
    image.save(buffer, format="PNG", optimize=False, compress_level=9)
    return buffer.getvalue()


def normalize_pixels(original: Image.Image) -> Image.Image:
    """Nearest sample the complete canvas; retain every visible sample's RGB."""
    sampled = original.convert("RGBA").resize((64, 64), Image.Resampling.NEAREST)
    sprite = Image.new("RGBA", (64, 64))
    sprite.putdata([
        (red, green, blue, 255) if alpha >= ALPHA_THRESHOLD else (0, 0, 0, 0)
        for red, green, blue, alpha in sampled.getdata()
    ])
    return sprite


def inspect_sprite(sprite: Image.Image) -> dict:
    if sprite.mode != "RGBA" or sprite.size != (64, 64):
        raise ValueError("Final sprite must be 64x64 RGBA")
    alpha = sprite.getchannel("A")
    histogram = alpha.histogram()
    if sum(histogram[1:255]) or not histogram[0] or not histogram[255]:
        raise ValueError("Final sprite must contain both transparent and opaque pixels, with binary alpha")
    bbox = alpha.getbbox()
    margins = [bbox[0], bbox[1], 64 - bbox[2], 64 - bbox[3]]
    if min(margins) < 1:
        raise ValueError("Sprite requires transparent margin on all four edges")
    if any((red, green, blue) != (0, 0, 0) for red, green, blue, a in sprite.getdata() if a == 0):
        raise ValueError("Transparent RGB must be zero")
    return {
        "size": list(sprite.size), "mode": sprite.mode,
        "alpha_values": [0, 255], "transparent_pixels": histogram[0],
        "opaque_pixels": histogram[255], "partial_alpha_pixels": 0,
        "visible_bbox_exclusive": list(bbox), "margins_left_top_right_bottom": margins,
    }


def build_artifacts(source: Path = SOURCE) -> tuple[bytes, bytes, dict]:
    source_bytes = source.read_bytes()
    if sha256(source_bytes) != SOURCE_SHA256:
        raise ValueError("Source SHA256 differs from the approved original")
    with Image.open(io.BytesIO(source_bytes)) as original:
        if original.size != (1254, 1254) or original.mode != "RGBA":
            raise ValueError("Approved original must be 1254x1254 RGBA")
        sprite = normalize_pixels(original)
    details = inspect_sprite(sprite)
    native = png_bytes(sprite)
    preview = png_bytes(sprite.resize((512, 512), Image.Resampling.NEAREST))
    report = {
        "authorization": "2026-09-08: 用户明确回复‘可以’，许可64x64最近邻缩放与透明边缘规范，并接入打包。",
        "generation_mode": "Original: built-in image_gen; this step: deterministic Pillow only, no API/CLI generation.",
        "source": str(source.resolve()), "source_sha256": SOURCE_SHA256,
        "source_bytes": len(source_bytes), "source_size": [1254, 1254],
        "original_prompt_record": str((PROJECT / "design/av-cable-alpha2/GENERATION.md").resolve()),
        "operations": ["Resize complete canvas to 64x64 using NEAREST",
                       "Alpha >= 128 becomes 255; alpha < 128 becomes 0",
                       "Zero RGB only for fully transparent pixels",
                       "Preview enlarges final sprite to 512x512 using NEAREST"],
        "invariants": ["No crop, repaint, recolor, palette quantization or added pixels",
                       "Visible sampled RGB unchanged", "Original source bytes preserved",
                       "Existing different output files rejected"],
        "pillow_version": PILLOW_VERSION, "alpha_threshold": ALPHA_THRESHOLD,
        "sprite": details,
        "files": {
            "av_cable.png": {"bytes": len(native), "sha256": sha256(native)},
            "av_cable-512-nearest.png": {"bytes": len(preview), "sha256": sha256(preview)},
        },
    }
    return native, preview, report


def publish_files(files: dict[Path, bytes], protected_source: Path) -> None:
    """Preflight every destination, then exclusive-create missing files; identical reruns are no-ops."""
    source_path = protected_source.resolve()
    targets: dict[Path, bytes] = {}
    for path, data in files.items():
        if path.is_symlink():
            raise ValueError(f"Refusing symbolic-link output: {path}")
        resolved = path.resolve()
        if resolved == source_path:
            raise ValueError("Refusing to replace the original source")
        if resolved in targets and targets[resolved] != data:
            raise ValueError(f"Conflicting output destinations: {path}")
        if path.exists() and (not path.is_file() or path.read_bytes() != data):
            raise FileExistsError(f"Refusing to overwrite different existing file: {path}")
        targets[resolved] = data
    for path, data in targets.items():
        path.parent.mkdir(parents=True, exist_ok=True)
        try:
            with path.open("xb") as output:
                output.write(data)
        except FileExistsError:
            if not path.is_file() or path.read_bytes() != data:
                raise FileExistsError(f"Output changed during publication: {path}") from None


def run(source: Path, output: Path, artifact_dir: Path) -> dict:
    native, preview, report = build_artifacts(source)
    report_bytes = (json.dumps(report, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
    publish_files({output: native, artifact_dir / "av_cable.png": native,
                   artifact_dir / "av_cable-512-nearest.png": preview,
                   artifact_dir / "normalization-report.json": report_bytes}, source)
    return report


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, default=SOURCE)
    parser.add_argument("--output", type=Path, default=OUTPUT)
    parser.add_argument("--artifact-dir", type=Path, default=ARTIFACT_DIR)
    args = parser.parse_args()
    print(json.dumps(run(args.source, args.output, args.artifact_dir), ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
