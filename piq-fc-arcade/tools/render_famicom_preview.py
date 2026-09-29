"""Render an orthographic preview of the repo's Famicom block-model JSON."""

from __future__ import annotations

import json
import math
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont


MODEL_PATH = Path(__file__).parents[1] / "src/main/resources/assets/piq_fc_arcade/models/block/famicom_console.json"
OUTPUT_PATH = Path(__file__).parents[2] / "制作Mod/03-街机模拟/PIQ-FC街机/红白机FC主机-手柄间距模型草案.png"

BASE_COLORS = {
    "cream": (238, 223, 181),
    "cream_shadow": (214, 201, 171),
    "red": (190, 35, 43),
    "dark_red": (112, 24, 29),
    "gold": (210, 160, 45),
    "black": (23, 25, 29),
    "gray": (61, 66, 70),
    "blue": (32, 73, 128),
    "contact": (89, 164, 206),
    "wire": (18, 20, 23),
    "brand": (166, 28, 39),
    "power_label": (236, 221, 180),
    "eject_label": (236, 221, 180),
    "reset_label": (236, 221, 180),
    "controller_1": (227, 204, 144),
    "controller_2": (227, 204, 144),
    "led": (255, 43, 34),
}


def vec_sub(a, b):
    return tuple(a[i] - b[i] for i in range(3))


def dot(a, b):
    return sum(a[i] * b[i] for i in range(3))


def cross(a, b):
    return (
        a[1] * b[2] - a[2] * b[1],
        a[2] * b[0] - a[0] * b[2],
        a[0] * b[1] - a[1] * b[0],
    )


def normalize(v):
    length = math.sqrt(dot(v, v))
    return tuple(x / length for x in v)


def shade(color, factor):
    return tuple(max(0, min(255, int(channel * factor))) for channel in color)


def font(size, bold=False):
    candidates = [
        Path("C:/Windows/Fonts/msyhbd.ttc" if bold else "C:/Windows/Fonts/msyh.ttc"),
        Path("C:/Windows/Fonts/simhei.ttf"),
    ]
    for candidate in candidates:
        if candidate.exists():
            return ImageFont.truetype(str(candidate), size)
    return ImageFont.load_default()


def face_geometry(start, end):
    x0, y0, z0 = start
    x1, y1, z1 = end
    return {
        "up": ([(x0, y1, z0), (x1, y1, z0), (x1, y1, z1), (x0, y1, z1)], (0, 1, 0), 1.06),
        "down": ([(x0, y0, z1), (x1, y0, z1), (x1, y0, z0), (x0, y0, z0)], (0, -1, 0), 0.63),
        "north": ([(x0, y0, z0), (x1, y0, z0), (x1, y1, z0), (x0, y1, z0)], (0, 0, -1), 0.92),
        "south": ([(x1, y0, z1), (x0, y0, z1), (x0, y1, z1), (x1, y1, z1)], (0, 0, 1), 0.78),
        "west": ([(x0, y0, z1), (x0, y0, z0), (x0, y1, z0), (x0, y1, z1)], (-1, 0, 0), 0.82),
        "east": ([(x1, y0, z0), (x1, y0, z1), (x1, y1, z1), (x1, y1, z0)], (1, 0, 0), 0.88),
    }


def render_view(model, direction, size=(720, 560)):
    camera = normalize(direction)
    right = normalize(cross((0, 1, 0), camera))
    screen_up = normalize(cross(camera, right))
    center = (8, 3.2, 8)

    polygons = []
    projected_points = []
    for element in model["elements"]:
        geometry = face_geometry(element["from"], element["to"])
        for face_name, face in element.get("faces", {}).items():
            vertices, normal, light = geometry[face_name]
            if dot(normal, camera) <= 0.001:
                continue
            projected = []
            depths = []
            for vertex in vertices:
                relative = vec_sub(vertex, center)
                projected.append((dot(relative, right), -dot(relative, screen_up)))
                depths.append(dot(relative, camera))
            texture_key = face.get("texture", "#cream").removeprefix("#")
            color = shade(BASE_COLORS.get(texture_key, (180, 180, 180)), light)
            polygons.append((sum(depths) / len(depths), projected, color))
            projected_points.extend(projected)

    min_x = min(p[0] for p in projected_points)
    max_x = max(p[0] for p in projected_points)
    min_y = min(p[1] for p in projected_points)
    max_y = max(p[1] for p in projected_points)
    padding = 54
    scale = min((size[0] - 2 * padding) / (max_x - min_x), (size[1] - 2 * padding) / (max_y - min_y))
    offset_x = (size[0] - (min_x + max_x) * scale) / 2
    offset_y = (size[1] - (min_y + max_y) * scale) / 2

    image = Image.new("RGBA", size, (0, 0, 0, 0))
    draw = ImageDraw.Draw(image)
    for _, polygon, color in sorted(polygons, key=lambda item: item[0]):
        points = [(x * scale + offset_x, y * scale + offset_y) for x, y in polygon]
        draw.polygon(points, fill=(*color, 255), outline=(48, 33, 34, 255), width=3)
        # A soft inner highlight keeps the small cuboids readable after downsampling.
        draw.line(points[:2], fill=(255, 244, 218, 100), width=1)
    return image


def rounded_panel(draw, box, radius, fill, outline):
    draw.rounded_rectangle(box, radius=radius, fill=fill, outline=outline, width=3)


def main():
    with MODEL_PATH.open("r", encoding="utf-8") as handle:
        model = json.load(handle)

    scale_factor = 2
    canvas = Image.new("RGB", (1600 * scale_factor, 1180 * scale_factor), (245, 239, 226))
    draw = ImageDraw.Draw(canvas)

    # Warm red header and restrained cream background match the physical console palette.
    draw.rectangle((0, 0, canvas.width, 142 * scale_factor), fill=(126, 24, 31))
    draw.text((68 * scale_factor, 35 * scale_factor), "PIQ FC  红白机主机", font=font(52 * scale_factor, True), fill=(255, 245, 218))
    draw.text((1015 * scale_factor, 49 * scale_factor), "模型草案 · 暂不打包", font=font(30 * scale_factor), fill=(232, 197, 111))

    panels = [
        ((50, 180, 775, 770), (-1.15, 1.15, -1.0), "左侧收纳槽 · 1P 手柄"),
        ((825, 180, 1550, 770), (1.15, 1.15, -1.0), "右侧收纳槽 · 2P 手柄"),
    ]
    for box, direction, label in panels:
        scaled_box = tuple(v * scale_factor for v in box)
        rounded_panel(draw, scaled_box, 22 * scale_factor, (255, 252, 244), (194, 169, 125))
        view = render_view(model, direction, ((box[2] - box[0] - 24) * scale_factor, (box[3] - box[1] - 75) * scale_factor))
        canvas.paste(view, ((box[0] + 12) * scale_factor, (box[1] + 12) * scale_factor), view)
        draw.text(((box[0] + 30) * scale_factor, (box[3] - 56) * scale_factor), label, font=font(28 * scale_factor, True), fill=(74, 46, 40))

    feature_y = 817 * scale_factor
    draw.text((72 * scale_factor, feature_y), "1P：方向键  ·  SELECT  ·  START  ·  B  ·  A", font=font(27 * scale_factor, True), fill=(112, 24, 29))
    draw.text((840 * scale_factor, feature_y), "2P：方向键  ·  VOLUME  ·  MIC  ·  B  ·  A", font=font(27 * scale_factor, True), fill=(112, 24, 29))

    texture_root = MODEL_PATH.parents[2] / "textures/block"
    controller_cards = [
        (texture_root / "famicom_controller_1.png", (90, 885), "I 控制器：横向原始布局"),
        (texture_root / "famicom_controller_2.png", (805, 885), "II 控制器：横向原始布局"),
    ]
    for path, position, label in controller_cards:
        # Textures are stored rotated for the side slot. Rotate them back here
        # so the review card shows the physical handheld orientation.
        texture = Image.open(path).convert("RGBA").rotate(-90, expand=True).resize((360 * scale_factor, 112 * scale_factor), Image.Resampling.NEAREST)
        canvas.paste(texture, (position[0] * scale_factor, position[1] * scale_factor), texture)
        draw.text(((position[0] + 380) * scale_factor, (position[1] + 18) * scale_factor), label, font=font(24 * scale_factor, True), fill=(74, 46, 40))
        draw.text(((position[0] + 380) * scale_factor, (position[1] + 66) * scale_factor), "左：方向键　　大留白　　右：B / A", font=font(18 * scale_factor), fill=(92, 83, 72))
    draw.text(
        (72 * scale_factor, 1135 * scale_factor),
        "从当前模型 JSON 与控制器贴图直接渲染；本图仅用于确认比例和间距，未生成新 JAR。",
        font=font(20 * scale_factor),
        fill=(92, 83, 72),
    )

    canvas.resize((1600, 1180), Image.Resampling.LANCZOS).save(OUTPUT_PATH, optimize=True)
    print(OUTPUT_PATH)


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        print(f"preview render failed: {exc}", file=sys.stderr)
        raise
