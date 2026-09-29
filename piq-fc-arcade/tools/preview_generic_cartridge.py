"""Read-only asset consumer: actual cartridge JSON/PNG -> independent offline preview.

Reuses the existing exact face/UV CPU renderer; never generates or alters a
model, source texture, Java file or Minecraft process. The sheet is not an
in-game screenshot and does not emulate item display transforms, AO or lighting.
"""
from __future__ import annotations

import argparse
import hashlib
import io
import json
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw

from render_rocket_arcade_preview import collect_quads, font, render_view, texture_path

PROJECT = Path(__file__).resolve().parents[1]
ASSETS = PROJECT / "src/main/resources/assets"
MODEL = ASSETS / "piq_fc_arcade/models/block/home_fc_cartridge.json"
SKIN = "piq_fc_arcade:block/home_fc_cartridge_skin"
EXPECTED_SKIN_SHA = "EBD76E0B5A56FC36C3F474CC0E4345377506E6AEA7B2EC14D8FECFECA246CCDA"
OUTPUT = PROJECT.parent / "制作Mod/03-街机模拟/PIQ-FC街机/通用卡带封面-alpha3"


def sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest().upper()


def store_new(path: Path, data: bytes) -> None:
    if path.exists():
        if path.read_bytes() != data:
            raise FileExistsError(f"Refusing to replace a different existing preview: {path}")
        return
    path.write_bytes(data)


def encoded(image: Image.Image, kind: str, **options) -> bytes:
    target = io.BytesIO()
    image.save(target, format=kind, **options)
    return target.getvalue()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=OUTPUT)
    args = parser.parse_args()
    model_bytes = MODEL.read_bytes()
    model = json.loads(model_bytes.decode("utf-8-sig"))
    if len(model["elements"]) != 107:
        raise ValueError("Expected the original 107-element cartridge model")
    quads = collect_quads(model)
    if len(quads) != 632 or {quad.texture for quad in quads} != {SKIN}:
        raise ValueError("Unexpected cartridge face count or texture aliases")
    skin_path = texture_path(ASSETS, SKIN)
    skin_bytes = skin_path.read_bytes()
    if sha(skin_bytes) != EXPECTED_SKIN_SHA:
        raise ValueError("The frozen alpha.3 generic cartridge skin SHA-256 does not match")
    with Image.open(io.BytesIO(skin_bytes)) as image:
        if image.size != (1024, 1024):
            raise ValueError("Expected the original 1024x1024 skin layout")
        texture = np.array(image.convert("RGBA"))
    intersections = []
    for quad in quads:
        low, high = quad.uv.min(axis=0) * 64, quad.uv.max(axis=0) * 64
        if np.all(np.maximum(low, (28, 28)) < np.minimum(high, (548, 292))):
            intersections.append({"element": quad.element_index, "face": quad.direction,
                                  "pixel_rectangle": [*low.tolist(), *high.tolist()]})
    if len(intersections) != 1 or intersections[0]["face"] != "north" or intersections[0]["pixel_rectangle"] != [32, 32, 544, 288]:
        raise ValueError("The real label plus 4px padding does not match the audited isolated UV rectangle")
    views = [("正面 · 原模型正投影", (0.0, 0.0, -1.0)),
             ("略侧面 · 原几何与标签 UV", (0.6, 0.24, -1.7))]
    sheet = Image.new("RGB", (1472, 768), (234, 238, 244))
    draw = ImageDraw.Draw(sheet)
    draw.text((24, 17), "通用 FC 卡带封面 · alpha.3", font=font(32, True), fill=(24, 37, 56))
    draw.text((24, 65), "实际 107 元素 JSON + 冻结 1024×1024 PNG · 未重画机身、未裁改原贴图", font=font(20), fill=(64, 79, 98))
    details = []
    for index, (label, direction) in enumerate(views):
        view, info = render_view(quads, {SKIN: texture}, direction, size=(700, 520), supersample=2)
        left, top = 24 + index * 724, 106
        draw.rectangle((left, top, left + 699, top + 565), fill=(250, 251, 253))
        sheet.paste(view, (left, top), view)
        draw.text((left + 16, top + 526), label, font=font(22, True), fill=(28, 43, 63))
        details.append({"label": label, **info})
    draw.text((24, 689), "离线原模型渲染，不是游戏截图；不含游戏光照/AO、纹理图集收边或物品显示变换。", font=font(20), fill=(55, 70, 90))
    draw.text((24, 725), "真实几何 / 真实 UV / 最近纹素采样 / 2× 超采样仅用于预览", font=font(17), fill=(86, 100, 119))
    png = encoded(sheet, "PNG")
    jpg = encoded(sheet, "JPEG", quality=90, optimize=True)
    output = args.output.resolve()
    if output == MODEL.parent or output == skin_path.parent:
        raise ValueError("Preview output may not target source asset directories")
    output.mkdir(parents=True, exist_ok=True)
    png_path, jpg_path = output / "卡带模型预览.png", output / "卡带模型预览.jpg"
    store_new(png_path, png)
    store_new(jpg_path, jpg)
    report = {"version": "0.31.0-alpha.3", "offline_not_game_screenshot": True,
              "model": {"path": str(MODEL), "sha256": sha(model_bytes), "elements": 107, "quads": len(quads)},
              "skin": {"path": str(skin_path), "sha256": sha(skin_bytes), "dimensions": [1024, 1024]},
              "label_padding_intersections": intersections, "views": details,
              "previews": {png_path.name: {"sha256": sha(png), "bytes": len(png)},
                           jpg_path.name: {"sha256": sha(jpg), "bytes": len(jpg)}},
              "limits": ["No Minecraft game execution, atlas shrink, game lighting/AO or item display transforms",
                         "CPU orthographic depth test; alpha cutout at 0.1; view-only 2x supersampling",
                         "No source model or texture modification; no generated or repainted cabinet"]}
    store_new(output / "卡带模型预览-校验.json", (json.dumps(report, ensure_ascii=False, indent=2) + "\n").encode("utf-8"))
    if MODEL.read_bytes() != model_bytes or skin_path.read_bytes() != skin_bytes:
        raise RuntimeError("Source assets changed during preview; do not treat the result as frozen")
    print(json.dumps(report, ensure_ascii=True))


if __name__ == "__main__":
    main()
