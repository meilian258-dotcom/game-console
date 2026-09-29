"""Offline asset tests plus explicit source-ZIP audit and actual-asset preview output.

Run unittest without accessing the network. Use --audit with the original ZIP
available to prove the source relationship and write the packaging allowlist.
"""
import argparse
import io
from dataclasses import replace
import hashlib
import json
from functools import lru_cache
from itertools import product
from pathlib import Path
import unittest
import zipfile

import numpy as np
from PIL import Image, ImageDraw

from import_home_hardware_assets import (ASSETS, DEFAULT_ARCHIVE, ROOT, SPECS, ALPHA1_JAR,
                                         WORLD_TRANSFORMS, DISPLAY_WRAPPERS, collect, scale_model, rotate_xyz)
from render_rocket_arcade_preview import collect_quads, font, render_view, texture_path
from normalize_cartridge_label import original_bytes, outside_equal

NAMES = [spec[2] for spec in SPECS]
MODEL_SHA = {
    "home_famicom_console": "609f0e125c77ef480701a87d8dae4d7b0adac62c00a97a5798b42ae2ad23c8da",
    "home_retro_tv": "63ebda3a69e0abc05aa5fba39d4dee1262cae511e529b9efaa2c1f492680c4d0",
    "home_fc_cartridge": "d311209af1443f18566b588fbe0f35248e847acbdaf54d87043f326910f556fb",
}
EXTRA = ["models/block/famicom_console.json", "blockstates/retro_tv.json",
         "models/item/retro_tv.json", "models/item/fc_cartridge.json", "models/item/av_cable.json"]


def load(name):
    return json.loads((ASSETS / "models/block" / (name + ".json")).read_bytes())


@lru_cache(maxsize=8)
def baseline(relative):
    with zipfile.ZipFile(ALPHA1_JAR) as archive:
        raw = archive.read("assets/piq_fc_arcade/" + relative)
    frozen = json.loads((ROOT / "tools/home-fc-reviewed-assets.json").read_bytes())["assets"]
    if hashlib.sha256(raw).hexdigest() != frozen["assets/piq_fc_arcade/" + relative]:
        raise ValueError("Unexpected alpha.1 baseline: " + relative)
    return json.loads(raw)


def console_vertices(vertices):
    scale, pivot = WORLD_TRANSFORMS["home_famicom_console"]
    return (vertices - pivot) * scale + pivot


def item_vertices(vertices, transform, left):
    transformed = (vertices - 8) * transform.get("scale", [1, 1, 1])
    rotated = np.array([rotate_xyz(point, transform.get("rotation", [0, 0, 0]), left) for point in transformed])
    translation = np.array(transform.get("translation", [0, 0, 0]), dtype=float)
    if left:
        translation[0] *= -1
    return rotated + translation


class HomeAssetTests(unittest.TestCase):
    def test_only_reviewed_original_geometry_is_used(self):
        for spec in SPECS:
            name, count = spec[2], spec[5]
            self.assertEqual(count, len(load(name)["elements"]))
            self.assertEqual(MODEL_SHA[name], hashlib.sha256(
                (ASSETS / "models/block" / (name + ".json")).read_bytes()).hexdigest())

    def test_all_face_textures_resolve_and_model_coordinates_are_legal(self):
        for name in NAMES:
            for quad in collect_quads(load(name)):
                self.assertTrue(texture_path(ASSETS.parent, quad.texture).is_file())
                self.assertTrue(np.isfinite(quad.vertices).all())
                self.assertTrue((quad.vertices >= -16 - 1e-9).all() and (quad.vertices <= 32 + 1e-9).all())
                self.assertTrue((quad.uv >= 0).all() and (quad.uv <= 16).all())

    def test_card_generic_label_preserves_original_skin_and_uv_contract(self):
        path = ASSETS / "textures/block/home_fc_cartridge_skin.png"
        self.assertEqual((1024, 1024), Image.open(path).size)
        self.assertEqual("ebd76e0b5a56fc36c3f474cc0e4345377506e6aea7b2ec14d8fecfeca246ccda",
                         hashlib.sha256(path.read_bytes()).hexdigest())
        with Image.open(io.BytesIO(original_bytes())) as old, Image.open(path) as current:
            self.assertTrue(outside_equal(old, current))
        label = next(e for e in load("home_fc_cartridge")["elements"] if e["name"] == "中央游戏标签")
        self.assertEqual([32, 32, 544, 288], [v * 64 for v in label["faces"]["north"]["uv"]])
        self.assertEqual({"north"}, set(label["faces"]))

    def test_idle_tv_screen_is_opaque_black_and_four_by_three(self):
        pixels = np.asarray(Image.open(ASSETS / "textures/block/home_retro_tv_screen.png").convert("RGBA"))
        self.assertTrue((pixels[:, :, :3] == 0).all())
        self.assertTrue((pixels[:, :, 3] == 255).all())
        screen = next(e for e in load("home_retro_tv")["elements"] if e["name"] == "空白显像管屏幕")
        self.assertAlmostEqual(4 / 3, (screen["to"][0] - screen["from"][0]) /
                               (screen["to"][1] - screen["from"][1]))
        self.assertEqual([3.76, 4.8, 0.814], screen["from"])

    def test_card_fits_slot_at_renderer_translation_without_scaling(self):
        vertices = np.concatenate([q.vertices for q in collect_quads(load("home_fc_cartridge"))])
        translated = console_vertices(vertices + (0, 4.1, 3.72))
        low, high = translated.min(axis=0), translated.max(axis=0)
        inner_low = console_vertices(np.array((1.81, 3.94, 10.87)))
        inner_high = console_vertices(np.array((14.19, 5.52, 12.57)))
        self.assertGreater(low[0], inner_low[0])
        self.assertLess(high[0], inner_high[0])
        self.assertGreater(low[2], inner_low[2])
        self.assertLess(high[2], inner_high[2])
        self.assertGreater(low[1], inner_low[1])
        self.assertLess(high[1], 16)

    def test_four_tv_rotations_and_item_renderer_contract(self):
        variants = json.loads((ASSETS / "blockstates/retro_tv.json").read_bytes())["variants"]
        self.assertEqual([0, 90, 180, 270], [variants["facing=" + direction].get("y", 0)
                         for direction in ("north", "east", "south", "west")])
        item = json.loads((ASSETS / "models/item/fc_cartridge.json").read_bytes())
        self.assertEqual("builtin/entity", item["parent"])
        for context, transform in load("home_fc_cartridge")["display"].items():
            self.assertEqual(transform, item["display"][context])
        self.assertEqual("piq_fc_arcade:block/home_famicom_console", load("famicom_console")["parent"])

    def test_every_mechanical_element_is_only_uniformly_scaled_from_frozen_alpha1(self):
        for name, (scale, pivot) in WORLD_TRANSFORMS.items():
            original = baseline("models/block/" + name + ".json")
            self.assertEqual(scale_model(original, scale, pivot), load(name))
            for old, current in zip(original["elements"], load(name)["elements"]):
                self.assertEqual(old["faces"], current["faces"])
                if "rotation" in old:
                    self.assertEqual(old["rotation"]["angle"], current["rotation"]["angle"])
                    self.assertEqual(old["rotation"]["axis"], current["rotation"]["axis"])

    def test_rotated_vertices_obey_the_same_uniform_transform(self):
        for name, (scale, pivot) in WORLD_TRANSFORMS.items():
            old = collect_quads(baseline("models/block/" + name + ".json"))
            current = collect_quads(load(name))
            for before, after in zip(old, current):
                np.testing.assert_allclose(after.vertices, (before.vertices - pivot) * scale + pivot,
                                           atol=1e-9, rtol=0)
                np.testing.assert_array_equal(before.uv, after.uv)

    def test_tv_and_inserted_console_fit_reserved_world_bounds(self):
        tv = np.concatenate([q.vertices for q in collect_quads(load("home_retro_tv"))])
        np.testing.assert_allclose(tv.min(axis=0), (0, 0, 0.798), atol=1e-9)
        np.testing.assert_allclose(tv.max(axis=0), (32, 25.4, 28.91), atol=1e-9)
        console = np.concatenate([q.vertices for q in collect_quads(load("home_famicom_console"))])
        card = console_vertices(np.concatenate([q.vertices for q in collect_quads(load("home_fc_cartridge"))])
                                + (0, 4.1, 3.72))
        complete = np.concatenate([console, card])
        self.assertTrue((complete >= 0).all() and (complete <= 16).all())

    def test_gui_ground_and_both_hands_preserve_all_original_item_space_vertices(self):
        for relative, name in DISPLAY_WRAPPERS.items():
            old_model = baseline("models/block/" + name + ".json")
            before = np.concatenate([q.vertices for q in collect_quads(old_model)])
            after = np.concatenate([q.vertices for q in collect_quads(load(name))])
            old_display = baseline(relative).get("display", {})
            new_display = json.loads((ASSETS / relative).read_bytes())["display"]
            for context, transform in new_display.items():
                old = old_display.get(context, old_display.get(context.replace("lefthand", "righthand"), {}))
                left = context.endswith("lefthand")
                np.testing.assert_allclose(item_vertices(after, transform, left), item_vertices(before, old, left),
                                           atol=1e-8, rtol=0, err_msg=name + ": " + context)

    def test_tv_has_one_global_ber_and_no_duplicate_static_model_path(self):
        renderer = (ROOT / "src/main/java/cn/piq/fcarcade/client/HomeHardwareRenderer.java").read_text(encoding="utf-8")
        block = (ROOT / "src/main/java/cn/piq/fcarcade/home/RetroTvBlock.java").read_text(encoding="utf-8")
        self.assertEqual(1, renderer.count("registerBlockEntityRenderer(ModBlockEntities.HOME_TV.get()"))
        self.assertIn("return RenderShape.ENTITYBLOCK_ANIMATED;", block)
        self.assertIn("public boolean shouldRenderOffScreen(HomeTvBlockEntity tv)", renderer)
        self.assertRegex(renderer, r"shouldRenderOffScreen\(HomeTvBlockEntity tv\)\s*\{\s*return true;")
        self.assertIn("return tvRenderBounds(tv.getBlockPos(), tv.getBlockState());", renderer)
        self.assertNotIn("ClientArcadeEvents.texture", renderer)
        self.assertIn("cached.model() != baked", renderer)

    def test_same_scale_preview_frame_contains_all_eight_bounding_corners(self):
        self.assertEqual(8, len(fixed_frame_corners()))
        self.assertEqual({(-24, 0, -5), (-24, 0, 33), (-24, 32, -5), (-24, 32, 33),
                          (55, 0, -5), (55, 0, 33), (55, 32, -5), (55, 32, 33)}, set(fixed_frame_corners()))


def audit(archive):
    files, source = collect(archive.read_bytes())
    for relative, expected in files.items():
        if (ASSETS / relative).read_bytes() != expected:
            raise ValueError("Source mismatch: " + relative)
    assets = {"assets/piq_fc_arcade/" + relative: hashlib.sha256((ASSETS / relative).read_bytes()).hexdigest()
              for relative in sorted(set([*files, *EXTRA]))}
    optional_icon = ASSETS / "textures/item/av_cable.png"
    if optional_icon.is_file():
        assets["assets/piq_fc_arcade/textures/item/av_cable.png"] = hashlib.sha256(optional_icon.read_bytes()).hexdigest()
    output = {"schema": 1, "version": "0.31.0-alpha.2", "source": {"archive": str(archive), **source}, "assets": assets,
              "notes": ["Only 03 console, 04 retro TV and 05 cartridge imported.",
                        "Console scaled uniformly by 0.6 about (8,0,8); TV scaled by 2 about origin; UV and every element preserved.",
                        "Item wrappers compensate scale and rotated translation to preserve original item-space geometry.",
                        "alpha.1 manifest and delivered JAR are preserved; optional AV icon is separate native pixel art."]}
    path = ROOT / "tools/home-fc-alpha2-reviewed-assets.json"
    data = json.dumps(output, ensure_ascii=False, indent=2) + "\n"
    if path.exists() and path.read_text(encoding="utf-8") != data:
        raise ValueError("Existing alpha.2 audit differs; review before replacing it")
    path.write_text(data, encoding="utf-8")
    print(f"Audit: {path} ({len(assets)} locked assets)")


def preview(output):
    output.mkdir(parents=True, exist_ok=True)
    models = {name: collect_quads(load(name)) for name in NAMES}
    textures = {quad.texture: np.asarray(Image.open(texture_path(ASSETS.parent, quad.texture)).convert("RGBA"))
                for quads in models.values() for quad in quads}
    inserted = models["home_famicom_console"] + [replace(q, vertices=console_vertices(q.vertices + (0, 4.1, 3.72)))
                                                 for q in models["home_fc_cartridge"]]
    views = [("console-inserted-front", "红白机 + 卡带 · 整体缩至 60%", inserted, (1.15, 1.0, -1.65)),
             ("console-inserted-rear", "背面 · 保留原手柄及机身线", inserted, (-1.25, 0.9, 1.6)),
             ("retro-tv-front", "复古彩电 · 空闲纯黑屏", models["home_retro_tv"], (1.15, 0.6, -1.65)),
             ("retro-tv-rear", "复古彩电 · 原 AV / RF 端口", models["home_retro_tv"], (-1.25, 0.55, 1.6)),
             ("cartridge-front", "独立卡带 · 原火箭车封面", models["home_fc_cartridge"], (0, 0, -1)),
             ("cartridge-rear", "独立卡带 · 原背面", models["home_fc_cartridge"], (-1, 0.35, 1.65))]
    width, height = 700, 600
    sheet = Image.new("RGB", (width * 2 + 72, (height + 54) * 3 + 200), (234, 238, 244))
    draw = ImageDraw.Draw(sheet)
    draw.text((24, 22), "家用 FC · 用户原模型资源核验预览", font=font(34, True), fill=(25, 34, 48))
    draw.text((24, 72), "alpha.2：主机 60% / 电视 200%；保留原机械结构、比例和 UV。", font=font(21), fill=(69, 81, 101))
    stats = []
    for index, (name, title, quads, direction) in enumerate(views):
        image, result = render_view(quads, textures, direction, (width, height), supersample=1)
        image.save(output / (name + ".png"))
        x, y = 24 + (index % 2) * (width + 24), 122 + (index // 2) * (height + 54)
        draw.rectangle((x, y, x + width, y + height + 42), fill=(249, 250, 252))
        sheet.paste(image, (x, y), image)
        draw.text((x + 16, y + height + 8), title, font=font(23, True), fill=(30, 43, 61))
        stats.append({"name": name, **result})
    draw.text((24, sheet.height - 55), "离线实际资产预览，不是游戏截图；不含游戏光照、AO、动态封面/画面及线缆。",
              font=font(20), fill=(86, 95, 110))
    sheet.save(output / "home-hardware-contact-sheet.png")
    (output / "preview-report.json").write_text(json.dumps(stats, indent=2), encoding="utf-8")
    print(f"Preview: {output / 'home-hardware-contact-sheet.png'}")
    same_scale_preview(output, models, textures, inserted)


def same_scale_preview(output, models, textures, inserted):
    """Both scenes share exact bounds and camera, hence identical pixels per block."""
    reference = {"textures": {"0": "preview:grid"}, "elements": [{"from": [-24, 0, 0], "to": [-8, 16, 16],
                 "faces": {side: {"uv": [0, 0, 16, 16], "texture": "#0"}
                           for side in ("up", "down", "north", "south", "east", "west")}}]}
    grid = np.full((16, 16, 4), (213, 218, 225, 255), dtype=np.uint8)
    grid[[0, -1], :, :3], grid[:, [0, -1], :3] = 105, 105
    textures = {**textures, "preview:grid": grid}
    old = {name: collect_quads(baseline("models/block/" + name + ".json")) for name in NAMES}
    old_inserted = old["home_famicom_console"] + [replace(q, vertices=q.vertices + (0, 4.1, 3.72))
                                                 for q in old["home_fc_cartridge"]]
    scenes = []
    for tv, console in ((old["home_retro_tv"], old_inserted), (models["home_retro_tv"], inserted)):
        scene = tv + [replace(q, vertices=q.vertices + (36, 0, 0)) for q in console] + collect_quads(reference)
        # Zero-area quads are not drawn, but lock framing to the same model-unit bounds.
        scene += [replace(scene[0], vertices=np.tile(point, (4, 1)))
                  for point in fixed_frame_corners()]
        scenes.append(scene)
    width, height = 1600, 670
    sheet = Image.new("RGB", (width + 48, 2 * height + 245), (234, 238, 244))
    draw = ImageDraw.Draw(sheet)
    draw.text((24, 18), "同一世界比例对比 · 1 格参考 / 电视 / 插卡主机", font=font(34, True), fill=(25, 34, 48))
    draw.text((24, 70), "上下使用完全相同相机与像素/方块比例；并非分别放大居中。", font=font(22), fill=(69, 81, 101))
    stats = []
    reference_masks = []
    for index, (title, scene) in enumerate(zip(("alpha.1 原尺寸", "alpha.2 电视 ×2 / 主机含卡带 ×0.6"), scenes)):
        y = 120 + index * (height + 45)
        view, result = render_view(scene, textures, (0.4, 0.6, -1.8), (width, height), supersample=1)
        view.save(output / ("same-scale-alpha" + str(index + 1) + ".png"))
        pixels = np.asarray(view)
        reference_mask = (pixels == (213, 218, 225, 255)).all(axis=2)
        reference_masks.append(reference_mask)
        reference_y, reference_x = np.where(reference_mask)
        result["reference_cube_pixel_bounds"] = [int(reference_x.min()), int(reference_y.min()),
                                                   int(reference_x.max()), int(reference_y.max())]
        sheet.paste(view, (24, y), view)
        draw.text((24, y + 8), title, font=font(27, True), fill=(30, 43, 61))
        stats.append({"version": title, "fixed_bounds_model_units": [[-24, 0, -5], [55, 32, 33]],
                      "reference_cube_blocks": [1, 1, 1], **result})
    draw.text((24, sheet.height - 42), "电视占位 2×2×2，原比例实体高 1.5875 格；离线真实资源，不是游戏截图。",
              font=font(21), fill=(86, 95, 110))
    sheet.save(output / "home-hardware-same-scale-comparison.png")
    if not np.array_equal(reference_masks[0], reference_masks[1]):
        raise ValueError("Reference cube changed screen size/position; comparison is not truly same-scale")
    (output / "same-scale-report.json").write_text(json.dumps(stats, ensure_ascii=False, indent=2), encoding="utf-8")


def fixed_frame_corners():
    # Two opposing corners do NOT lock projected bounds at an oblique camera angle.
    return tuple(product((-24, 55), (0, 32), (-5, 33)))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--audit", action="store_true")
    parser.add_argument("--archive", type=Path, default=DEFAULT_ARCHIVE)
    parser.add_argument("--preview", type=Path)
    args = parser.parse_args()
    result = unittest.TextTestRunner(verbosity=2).run(unittest.defaultTestLoader.loadTestsFromTestCase(HomeAssetTests))
    if not result.wasSuccessful():
        raise SystemExit(1)
    if args.audit:
        audit(args.archive)
    if args.preview:
        preview(args.preview)
