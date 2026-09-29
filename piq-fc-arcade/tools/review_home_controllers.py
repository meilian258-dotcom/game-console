"""Independent read-only alpha.3 controller partition review and actual-quad previews.

Never changes models/textures. Does not run Minecraft, emulate shaders or invent
textures. Preview outputs are rendered from exported assets with the established
FaceBakery-compatible offline rasterizer. --output must be the new alpha.3 folder.
"""
from __future__ import annotations

import argparse
from collections import Counter
from dataclasses import replace
import hashlib
import json
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw

from render_rocket_arcade_preview import collect_quads, font, render_view, texture_path
from import_home_hardware_assets import rotate_xyz

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "src/main/resources/assets/piq_fc_arcade"
SOURCE_HASH = "609F0E125C77EF480701A87D8DAE4D7B0ADAC62C00A97A5798B42AE2AD23C8DA"


def sha(data):
    return hashlib.sha256(data).hexdigest().upper()


def load(name):
    return json.loads((ASSETS / ("models/block/" + name + ".json")).read_bytes())


def quad_key(quad):
    return (tuple(np.round(quad.vertices, 10).flat), tuple(quad.uv.flat), quad.texture, quad.direction)


def yaw(vertices, angle):
    r = np.radians(angle)
    matrix = np.array(((np.cos(r), 0, np.sin(r)), (0, 1, 0), (-np.sin(r), 0, np.cos(r))))
    return (vertices - 8) @ matrix.T + 8


def fixed_frame(quads, low, high):
    # Degenerate framing vertices influence only the orthographic fit, never pixels.
    framing = replace(quads[0], vertices=np.array((low, high, low, high), dtype=float))
    return [*quads, framing]


def sheet(tiles, columns, title, subtitle, footer):
    width, height = tiles[0][0].size
    gap, heading, label, tail = 24, 115, 60, 78
    rows = (len(tiles) + columns - 1) // columns
    result = Image.new("RGB", (columns * (width + gap) + gap, rows * (height + label + gap) + heading + tail), (231, 237, 243))
    draw = ImageDraw.Draw(result)
    draw.text((gap, 18), title, font=font(34, True), fill=(23, 37, 53))
    draw.text((gap, 69), subtitle, font=font(21), fill=(63, 79, 94))
    for i, (image, caption) in enumerate(tiles):
        x, y = gap + (i % columns) * (width + gap), heading + (i // columns) * (height + label + gap)
        draw.rectangle((x, y, x + width, y + height + label), fill=(250, 251, 253))
        result.paste(image, (x, y), image)
        draw.text((x + 14, y + height + 14), caption, font=font(23, True), fill=(31, 48, 68))
    draw.text((gap, result.height - tail + 16), footer, font=font(19), fill=(69, 86, 100))
    return result


def review(output):
    source_file = ASSETS / "models/block/home_famicom_console.json"
    assert sha(source_file.read_bytes()) == SOURCE_HASH
    full = load("home_famicom_console")
    body = load("home_console_body")
    docks = [load("home_controller_p1_docked"), load("home_controller_p2_docked")]
    held = [load("home_controller_p1_held"), load("home_controller_p2_held")]
    all_elements = body["elements"] + docks[0]["elements"] + docks[1]["elements"]
    encode = lambda value: json.dumps(value, ensure_ascii=False, sort_keys=True)
    assert Counter(map(encode, full["elements"])) == Counter(map(encode, all_elements))
    assert [len(body["elements"]), *(len(model["elements"]) for model in docks)] == [101, 47, 60]
    full_quads = collect_quads(full)
    body_quads = collect_quads(body)
    dock_quads = [collect_quads(model) for model in docks]
    assembled = body_quads + dock_quads[0] + dock_quads[1]
    assert Counter(map(quad_key, full_quads)) == Counter(map(quad_key, assembled))
    duplicate_before = sum(n - 1 for n in Counter(map(quad_key, full_quads)).values() if n > 1)
    duplicate_after = sum(n - 1 for n in Counter(map(quad_key, assembled)).values() if n > 1)

    held_metrics = []
    held_quads = []
    for port, model in enumerate(held):
        prefix = ("一号", "二号")[port]
        original = [element for element in full["elements"] if element["name"].startswith(prefix)]
        lo = np.min([e["from"] for e in original], axis=0)
        hi = np.max([e["to"] for e in original], axis=0)
        center = (lo + hi) / 2
        assert len(model["elements"]) == [40, 53][port]
        for before, after in zip(original, model["elements"]):
            assert before["name"] == after["name"] and before["faces"] == after["faces"]
        original_quads = collect_quads(dict(full, elements=original))
        actual_quads = collect_quads(model)
        for before, after in zip(original_quads, actual_quads):
            np.testing.assert_allclose(after.vertices, (before.vertices - center) / .6 + 8, atol=1e-8, rtol=0)
            np.testing.assert_array_equal(before.uv, after.uv)
        angle = (-90, 90)[port]
        front_quads = [replace(quad, vertices=yaw(quad.vertices, angle)) for quad in actual_quads]
        by_name = {e["name"]: e for e in model["elements"]}
        centers = {key: yaw(np.array([(np.array(by_name[prefix + suffix]["from"]) + by_name[prefix + suffix]["to"]) / 2]), angle)[0]
                   for key, suffix in {"dpad": "十字键横", "a": "A黑色圆钮·横芯"}.items()}
        assert centers["dpad"][0] < centers["a"][0]
        vertices = np.concatenate([quad.vertices for quad in front_quads])
        held_metrics.append({"port": port + 1, "elements": len(model["elements"]), "quads": len(actual_quads),
                             "yaw_degrees": angle, "dpad_center": centers["dpad"].tolist(), "a_center": centers["a"].tolist(),
                             "front_bounds_min": vertices.min(axis=0).tolist(), "front_bounds_max": vertices.max(axis=0).tolist(),
                             "uv_unchanged": True, "original_docked_cords_omitted": True})
        held_quads.append(front_quads)

    variants = json.loads((ASSETS / "blockstates/famicom_console.json").read_bytes())["variants"]
    assert all(value["model"] == "piq_fc_arcade:block/home_console_body" for value in variants.values())
    assert [variants["facing=" + direction].get("y", 0) for direction in ("north", "east", "south", "west")] == [0, 90, 180, 270]
    assert json.loads((ASSETS / "models/item/famicom_console.json").read_bytes())["parent"] == "piq_fc_arcade:block/famicom_console"
    assert load("famicom_console")["parent"] == "piq_fc_arcade:block/home_famicom_console"
    renderer_path = ROOT / "src/main/java/cn/piq/fcarcade/client/HomeHardwareRenderer.java"
    layout_path = ROOT / "src/main/java/cn/piq/fcarcade/client/HomeHardwareRenderLayout.java"
    renderer = renderer_path.read_text(encoding="utf-8")
    layout = layout_path.read_text(encoding="utf-8")
    assert "return port == 0 ? -90f : 90f;" in layout
    assert "HomeHardwareRenderLayout.heldControllerYaw(port)" in renderer
    assert "cached.model() != baked" in renderer
    assert "for (ModelResourceLocation model : CONTROLLER_MODELS) event.register(model);" in renderer
    assert "if (console.controllerDocked(port)) drawController(port, poses, buffers, light, overlay);" in renderer
    dock_render = renderer.split("public void render(HomeConsoleBlockEntity", 1)[1].split("ItemStack cartridge =", 1)[0]
    assert "poses.scale(" not in dock_render
    for turn in range(4):
        rotated_full = [replace(q, vertices=yaw(q.vertices, -90 * turn)) for q in full_quads]
        rotated_split = [replace(q, vertices=yaw(q.vertices, -90 * turn)) for q in assembled]
        assert Counter(map(quad_key, rotated_full)) == Counter(map(quad_key, rotated_split))

    resources = {q.texture for q in full_quads}
    textures = {resource: np.asarray(Image.open(texture_path(ASSETS.parent, resource)).convert("RGBA")) for resource in resources}
    world_vertices = np.concatenate([q.vertices for q in full_quads])
    low, high = world_vertices.min(axis=0), world_vertices.max(axis=0)
    world_modes = [(assembled, "两只在位 · 208 元素"), (body_quads + dock_quads[1], "取走 P1 · 161 元素"), (body_quads, "取走两只 · 101 元素")]
    tiles = [(render_view(fixed_frame(quads, low, high), textures, (1.25, .9, -1.6), size=(700, 540), supersample=2)[0], label)
             for quads, label in world_modes]
    output.mkdir(parents=True, exist_ok=True)
    world_path = output / "controllers-world-three-states.png"
    sheet(tiles, 3, "家用 FC alpha.3 · 原模型机械拆分", "相同世界坐标 / 相同投影比例 · 两侧底槽保持在主机本体", "真实 JSON 顶点与原 PNG；非游戏截图，不含 AO、玩家、动态手柄线或光照。").save(world_path)

    item = json.loads((ASSETS / "models/item/fc_controller.json").read_bytes())
    held_tiles = []
    contexts = [(None, "正面原尺寸"), ("gui", "物品 GUI 角度"), ("firstperson_righthand", "右手 display"), ("firstperson_lefthand", "左手 display")]
    for context, label in contexts:
        for port, quads in enumerate(held_quads):
            if context is not None:
                display = item["display"][context]
                left = context.endswith("lefthand")
                translation = np.array(display.get("translation", (0, 0, 0)), dtype=float)
                if left: translation[0] *= -1
                scale = display.get("scale", (1, 1, 1))
                quads = [replace(q, vertices=np.array([rotate_xyz(v, display.get("rotation", (0, 0, 0)), left)
                            for v in (q.vertices - 8) * scale]) + 8 + translation) for q in quads]
            image = render_view(fixed_frame(quads, (0, 1, 2), (16, 15, 14)), textures, (0, 0, 1), size=(700, 420), supersample=2)[0]
            held_tiles.append((image, f"P{port + 1} · {label}"))
    held_path = output / "controllers-held-orientation.png"
    sheet(held_tiles, 2, "P1 / P2 手柄 · D-pad 在左、A/B 在右", "按 Java heldYaw：P1 -90° / P2 +90°；原 UV 和文字不镜像", "左手按本机 ItemTransform 反转 Y/Z 角与 X 平移；未模拟第一人称手臂动画/世界持握位置。").save(held_path)

    paths = [source_file, ASSETS / "blockstates/famicom_console.json", ASSETS / "models/item/famicom_console.json",
             ASSETS / "models/block/famicom_console.json", ASSETS / "models/item/fc_controller.json"]
    paths += [ASSETS / ("models/block/" + name + ".json") for name in
              ("home_console_body", "home_controller_p1_docked", "home_controller_p2_docked", "home_controller_p1_held", "home_controller_p2_held")]
    report = {"schema": 1, "version": "0.31.0-alpha.3", "ok": True,
              "method": "Independent exported-element/quad multiset comparison; deterministic original-texture offline preview",
              "world_elements": [208, 161, 101], "partition": [101, 47, 60], "world_quads": [len(assembled), len(body_quads + dock_quads[1]), len(body_quads)],
              "exact_quad_duplicates_original": duplicate_before, "exact_quad_duplicates_split": duplicate_after,
              "no_new_duplicate_quads": duplicate_before == duplicate_after,
              "four_world_rotations_equivalent": True, "original_full_model_still_used_by_console_item": True,
              "held": held_metrics, "assets": {str(p.relative_to(ASSETS)): sha(p.read_bytes()) for p in paths},
              "textures": {resource: sha(texture_path(ASSETS.parent, resource).read_bytes()) for resource in resources},
              "java_contract_sha256": {str(p.relative_to(ROOT)): sha(p.read_bytes()) for p in (renderer_path, layout_path)},
              "previews": {p.name: sha(p.read_bytes()) for p in (world_path, held_path)},
              "limits": ["Not a Minecraft screenshot; no shader/AO, neighbours, player arms or tether animation.",
                         "F3+T cache and registration verified by source review; not an in-game reload test.",
                         "Original source hidden/intersecting geometry is preserved; no faces were added by partition."]}
    (output / "controller-model-review.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"ok": True, "partition": report["partition"], "world_quads": report["world_quads"], "duplicates": duplicate_after,
                      "output": str(output), "previews": report["previews"]}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    arguments = parser.parse_args()
    if "alpha.3" not in arguments.output.name:
        raise ValueError("Use a new alpha.3 preview directory, never the frozen alpha.2 folder")
    review(arguments.output)
