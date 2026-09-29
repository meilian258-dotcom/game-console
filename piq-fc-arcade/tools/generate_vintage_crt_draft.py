"""Generate a standalone vintage CRT television model draft and preview.

The draft intentionally lives outside src/main/resources so it cannot enter a
release JAR by accident.  Once the appearance is approved, the model and its
textures can be promoted into the mod as a separately registered cabinet.
"""

from __future__ import annotations

import json
import math
import random
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont


WORKSPACE = Path(__file__).parents[2]
OUTPUT_DIR = WORKSPACE / "制作Mod/03-街机模拟/PIQ-FC街机/复古CRT电视机草案"
TEXTURE_DIR = OUTPUT_DIR / "textures"
MODEL_PATH = OUTPUT_DIR / "vintage_crt_tv_draft.json"
PREVIEW_PATH = OUTPUT_DIR.parent / "复古CRT电视机-模型草案.png"
LARGE_MODEL_PATH = OUTPUT_DIR / "vintage_crt_tv_2x2_draft.json"
COMPARISON_PREVIEW_PATH = OUTPUT_DIR.parent / "复古CRT电视机-标准与2x2模型草案.png"

TEXTURES = {
    "wood": "piq_fc_arcade:block/vintage_crt_wood",
    "wood_dark": "piq_fc_arcade:block/vintage_crt_wood_dark",
    "trim": "piq_fc_arcade:block/vintage_crt_trim",
    "bezel": "piq_fc_arcade:block/vintage_crt_bezel",
    "screen": "piq_fc_arcade:block/vintage_crt_screen",
    "panel": "piq_fc_arcade:block/vintage_crt_panel",
    "metal": "piq_fc_arcade:block/vintage_crt_metal",
    "speaker": "piq_fc_arcade:block/vintage_crt_speaker",
    "red": "piq_fc_arcade:block/vintage_crt_red",
    "black": "piq_fc_arcade:block/vintage_crt_black",
    "badge": "piq_fc_arcade:block/vintage_crt_badge",
    "color_shell": "piq_fc_arcade:block/color_crt_shell",
    "color_shell_dark": "piq_fc_arcade:block/color_crt_shell_dark",
    "color_bezel": "piq_fc_arcade:block/color_crt_bezel",
    "color_screen": "piq_fc_arcade:block/color_crt_screen",
    "color_speaker": "piq_fc_arcade:block/color_crt_speaker",
    "color_button": "piq_fc_arcade:block/color_crt_button",
    "color_led": "piq_fc_arcade:block/color_crt_led",
    "color_badge": "piq_fc_arcade:block/color_crt_badge",
}

BASE_COLORS = {
    "wood": (116, 68, 39),
    "wood_dark": (69, 39, 28),
    "trim": (182, 137, 79),
    "bezel": (25, 23, 21),
    "screen": (18, 35, 42),
    "panel": (196, 168, 116),
    "metal": (154, 159, 155),
    "speaker": (45, 39, 33),
    "red": (160, 39, 34),
    "black": (20, 19, 18),
    "badge": (202, 170, 86),
    "color_shell": (48, 51, 54),
    "color_shell_dark": (25, 27, 29),
    "color_bezel": (13, 15, 16),
    "color_screen": (39, 55, 55),
    "color_speaker": (31, 34, 35),
    "color_button": (71, 76, 79),
    "color_led": (45, 192, 132),
    "color_badge": (69, 151, 155),
}


def font(size: int, bold: bool = False):
    candidates = [
        Path("C:/Windows/Fonts/msyhbd.ttc" if bold else "C:/Windows/Fonts/msyh.ttc"),
        Path("C:/Windows/Fonts/simhei.ttf"),
    ]
    for candidate in candidates:
        if candidate.exists():
            return ImageFont.truetype(str(candidate), size)
    return ImageFont.load_default()


def faces(texture: str, only: tuple[str, ...] | None = None):
    names = only or ("down", "up", "north", "south", "west", "east")
    return {name: {"uv": [0, 0, 16, 16], "texture": f"#{texture}"} for name in names}


def add_cube(elements, name, start, end, texture, rotation=None, only=None):
    element = {
        "name": name,
        "from": [round(v, 4) for v in start],
        "to": [round(v, 4) for v in end],
        "faces": faces(texture, only),
    }
    if rotation:
        element["rotation"] = rotation
    elements.append(element)


def build_model():
    elements = []

    # Deep walnut cabinet and stepped rear CRT bulge.
    add_cube(elements, "cabinet", (0.6, 2.3, 5.4), (15.4, 14.2, 15.2), "wood")
    add_cube(elements, "cabinet_lower_shadow", (0.8, 2.45, 4.95), (15.2, 3.45, 15.4), "wood_dark")
    add_cube(elements, "rear_bulge_1", (2.0, 4.0, 14.9), (14.0, 13.35, 16.0), "wood_dark")
    add_cube(elements, "rear_bulge_2", (3.15, 5.0, 15.9), (12.85, 12.7, 16.65), "black")

    # Brass-like front frame; a large black plate makes the CRT recess deep.
    add_cube(elements, "front_top_trim", (0.85, 13.2, 4.7), (15.15, 14.0, 6.0), "trim")
    add_cube(elements, "front_left_trim", (0.85, 3.35, 4.7), (1.55, 13.35, 6.0), "trim")
    add_cube(elements, "front_right_trim", (14.55, 3.35, 4.7), (15.15, 13.35, 6.0), "trim")
    add_cube(elements, "front_bottom_trim", (0.85, 3.25, 4.7), (15.15, 4.0, 6.0), "trim")
    add_cube(elements, "screen_recess", (1.25, 4.55, 4.35), (11.45, 12.85, 5.45), "bezel")

    # 4:3 screen opening (8.8 x 6.6), with stepped corner masks to imply a rounded CRT.
    add_cube(elements, "screen_surface", (1.95, 5.35, 4.08), (10.75, 11.95, 4.46), "screen", only=("north", "up", "down", "west", "east"))
    for name, start, end in (
        ("screen_corner_tl", (1.95, 11.58, 3.98), (2.32, 11.95, 4.4)),
        ("screen_corner_tr", (10.38, 11.58, 3.98), (10.75, 11.95, 4.4)),
        ("screen_corner_bl", (1.95, 5.35, 3.98), (2.32, 5.72, 4.4)),
        ("screen_corner_br", (10.38, 5.35, 3.98), (10.75, 5.72, 4.4)),
    ):
        add_cube(elements, name, start, end, "bezel")

    # Right-side controls: two chunky rotary dials, speaker, power light and label.
    add_cube(elements, "control_panel", (11.55, 4.55, 4.28), (14.55, 12.85, 5.45), "panel")
    add_cube(elements, "channel_ring", (12.0, 10.35, 3.76), (14.1, 12.45, 4.36), "bezel")
    add_cube(elements, "channel_knob", (12.34, 10.69, 3.38), (13.76, 12.11, 3.82), "metal")
    add_cube(elements, "channel_marker", (13.0, 11.8, 3.22), (13.18, 12.2, 3.42), "black")
    add_cube(elements, "volume_ring", (12.35, 8.55, 3.82), (13.8, 10.0, 4.36), "bezel")
    add_cube(elements, "volume_knob", (12.63, 8.83, 3.48), (13.52, 9.72, 3.86), "metal")
    add_cube(elements, "volume_marker", (13.0, 9.54, 3.3), (13.13, 9.84, 3.5), "black")
    add_cube(elements, "power_lamp", (13.85, 4.85, 3.85), (14.28, 5.28, 4.32), "red")
    add_cube(elements, "piq_badge", (7.05, 3.35, 4.26), (9.75, 3.82, 4.72), "badge")

    # Six recessed horizontal speaker slots.
    for index in range(6):
        y = 5.55 + index * 0.42
        add_cube(elements, f"speaker_slot_{index + 1}", (12.0, y, 3.88), (14.1, y + 0.16, 4.32), "speaker")

    # Top ventilation slots and right-side ventilation slits.
    for index in range(7):
        x = 3.0 + index * 1.45
        add_cube(elements, f"top_vent_{index + 1}", (x, 14.17, 8.4), (x + 0.78, 14.36, 12.9), "speaker")
    for index in range(5):
        y = 6.5 + index * 1.08
        add_cube(elements, f"side_vent_{index + 1}", (15.28, y, 9.2), (15.55, y + 0.48, 13.1), "speaker")
    for index in range(5):
        y = 5.75 + index * 1.18
        add_cube(elements, f"rear_vent_{index + 1}", (4.1, y, 16.53), (11.9, y + 0.38, 16.78), "speaker")

    # Short feet: deliberately narrower than the first draft so they read as
    # supports instead of two oversized plinths when viewed from the front.
    for side, x0, x1 in (("left", 2.55, 4.5), ("right", 11.5, 13.45)):
        add_cube(elements, f"{side}_foot_stem", (x0 + 0.28, 0.75, 7.75), (x1 - 0.28, 2.45, 12.65), "wood_dark")
        add_cube(elements, f"{side}_foot_pad", (x0, 0.35, 7.35), (x1, 0.88, 13.05), "black")

    # Rabbit-ear antenna. Rotated cuboids stay faithful to the Minecraft model format.
    add_cube(elements, "antenna_base", (6.15, 14.1, 10.0), (9.85, 14.82, 13.2), "bezel")
    add_cube(elements, "antenna_left_hinge", (6.95, 14.58, 11.0), (7.65, 15.3, 11.7), "metal")
    add_cube(elements, "antenna_right_hinge", (8.35, 14.58, 11.0), (9.05, 15.3, 11.7), "metal")
    add_cube(
        elements,
        "antenna_left_rod",
        (7.14, 15.0, 11.2),
        (7.46, 18.8, 11.52),
        "metal",
        {"origin": [7.3, 15.0, 11.36], "axis": "z", "angle": 22.5, "rescale": False},
    )
    add_cube(
        elements,
        "antenna_right_rod",
        (8.54, 15.0, 11.2),
        (8.86, 18.8, 11.52),
        "metal",
        {"origin": [8.7, 15.0, 11.36], "axis": "z", "angle": -22.5, "rescale": False},
    )
    add_cube(
        elements,
        "antenna_left_tip",
        (7.03, 18.55, 11.09),
        (7.57, 19.25, 11.63),
        "trim",
        {"origin": [7.3, 15.0, 11.36], "axis": "z", "angle": 22.5, "rescale": False},
    )
    add_cube(
        elements,
        "antenna_right_tip",
        (8.43, 18.55, 11.09),
        (8.97, 19.25, 11.63),
        "trim",
        {"origin": [8.7, 15.0, 11.36], "axis": "z", "angle": -22.5, "rescale": False},
    )

    return {
        "credit": "PIQ original vintage CRT draft; generated outside release resources",
        "texture_size": [64, 64],
        "textures": TEXTURES,
        "elements": elements,
    }


def build_large_model(source_model):
    """Build an independent 2x2 black color CRT instead of scaling the wood set."""
    elements = []

    # Thick, boxy graphite cabinet based on late-1980s/1990s color CRT sets.
    # The front is deliberately square while the back steps inward into a deep tube bulge.
    add_cube(elements, "color_cabinet", (1.0, 2.0, 5.2), (31.0, 31.0, 17.2), "color_shell")
    add_cube(elements, "color_rear_step", (2.4, 4.0, 16.9), (29.6, 29.5, 20.2), "color_shell_dark")
    add_cube(elements, "color_rear_bulge", (5.0, 6.0, 19.9), (27.0, 27.8, 22.3), "color_shell_dark")
    add_cube(elements, "color_rear_cap", (8.0, 8.0, 22.0), (24.0, 25.8, 23.4), "color_bezel")

    # Narrow black front frame and a large 4:3 curved-glass opening.
    add_cube(elements, "color_top_frame", (1.0, 27.4, 3.65), (31.0, 31.0, 6.2), "color_bezel")
    add_cube(elements, "color_left_frame", (1.0, 8.1, 3.65), (4.25, 28.1, 6.2), "color_bezel")
    add_cube(elements, "color_right_frame", (27.75, 8.1, 3.65), (31.0, 28.1, 6.2), "color_bezel")
    add_cube(elements, "color_screen_recess", (3.45, 8.3, 3.05), (28.55, 28.45, 5.5), "color_bezel")
    add_cube(
        elements,
        "color_screen_surface",
        (4.6, 9.65, 2.72),
        (27.4, 26.75, 3.32),
        "color_screen",
        only=("north", "up", "down", "west", "east"),
    )
    for name, start, end in (
        ("color_screen_corner_tl", (4.6, 26.05, 2.58), (5.3, 26.75, 3.28)),
        ("color_screen_corner_tr", (26.7, 26.05, 2.58), (27.4, 26.75, 3.28)),
        ("color_screen_corner_bl", (4.6, 9.65, 2.58), (5.3, 10.35, 3.28)),
        ("color_screen_corner_br", (26.7, 9.65, 2.58), (27.4, 10.35, 3.28)),
    ):
        add_cube(elements, name, start, end, "color_bezel")

    # One continuous lower fascia, like the reference television: speaker on the
    # left, brand badge in the middle and a compact bank of push buttons on the right.
    add_cube(elements, "color_lower_fascia", (1.0, 2.0, 3.45), (31.0, 9.0, 6.4), "color_shell_dark")
    add_cube(elements, "color_speaker_recess", (3.0, 3.25, 2.9), (15.8, 6.8, 3.55), "color_speaker")
    for index in range(8):
        x = 3.55 + index * 1.42
        add_cube(elements, f"color_speaker_slot_{index + 1}", (x, 3.65, 2.48), (x + 0.42, 6.35, 3.02), "color_bezel")

    add_cube(elements, "color_badge_plate", (13.2, 7.0, 2.7), (18.8, 8.2, 3.45), "color_badge")
    add_cube(elements, "color_control_recess", (18.0, 3.15, 2.72), (29.4, 6.85, 3.55), "color_bezel")
    add_cube(elements, "color_power_button", (18.7, 3.8, 2.3), (20.45, 6.15, 2.8), "color_button")
    add_cube(elements, "color_power_led", (20.75, 5.2, 2.22), (21.2, 5.65, 2.78), "color_led")
    for index in range(5):
        x = 22.0 + index * 1.38
        add_cube(elements, f"color_control_button_{index + 1}", (x, 4.05, 2.3), (x + 0.92, 5.9, 2.82), "color_button")
    add_cube(elements, "color_ir_window", (28.9, 4.0, 2.28), (29.55, 5.95, 2.83), "color_bezel")

    # Low integral plinth rather than legs or an ornate wooden stand.
    add_cube(elements, "color_bottom_plinth", (0.65, 0.6, 5.0), (31.35, 2.25, 18.8), "color_bezel")
    add_cube(elements, "color_plinth_highlight", (1.3, 1.85, 4.25), (30.7, 2.45, 7.0), "color_shell")

    # Top/rear ventilation and the recessed carry handle seen on boxy CRT sets.
    for index in range(10):
        x = 4.0 + index * 2.45
        add_cube(elements, f"color_top_vent_{index + 1}", (x, 30.82, 9.0), (x + 1.2, 31.18, 15.9), "color_speaker")
    for index in range(7):
        y = 8.0 + index * 2.25
        add_cube(elements, f"color_rear_vent_{index + 1}", (7.0, y, 23.18), (25.0, y + 0.68, 23.55), "color_speaker")
    add_cube(elements, "color_left_handle_recess", (0.72, 20.2, 10.0), (1.22, 25.8, 15.5), "color_bezel")
    add_cube(elements, "color_left_handle_grip", (0.45, 21.1, 11.0), (0.9, 24.9, 14.5), "color_button")

    return {
        "credit": "PIQ original 2x2 boxy color CRT draft; generated outside release resources",
        "texture_size": source_model["texture_size"],
        "textures": TEXTURES,
        "display_bounds": {"width_blocks": 2, "height_blocks": 2},
        "elements": elements,
    }


def save_textures():
    TEXTURE_DIR.mkdir(parents=True, exist_ok=True)
    rng = random.Random(1978)

    wood = Image.new("RGB", (64, 64), (113, 65, 37))
    wd = ImageDraw.Draw(wood)
    for y in range(64):
        base = 94 + int(13 * math.sin(y / 4.5))
        wd.line((0, y, 63, y), fill=(base + 24, base - 10, max(20, base - 38)))
    for _ in range(34):
        y = rng.randrange(2, 62)
        x = rng.randrange(-12, 54)
        wd.arc((x, y - 4, x + rng.randrange(15, 34), y + 4), 185, 355, fill=(62, 35, 24), width=1)
    wood.save(TEXTURE_DIR / "vintage_crt_wood.png")

    wood_dark = Image.new("RGB", (64, 64), (62, 36, 28))
    dd = ImageDraw.Draw(wood_dark)
    for y in range(0, 64, 3):
        dd.line((0, y, 63, y), fill=(78, 43, 29))
    wood_dark.save(TEXTURE_DIR / "vintage_crt_wood_dark.png")

    screen = Image.new("RGBA", (128, 96), (8, 19, 25, 255))
    sd = ImageDraw.Draw(screen)
    for y in range(96):
        value = int(10 + 22 * (1 - y / 95))
        sd.line((0, y, 127, y), fill=(value // 2, value + 7, value + 12, 255))
    sd.rounded_rectangle((2, 2, 125, 93), radius=12, outline=(74, 98, 102, 255), width=3)
    sd.polygon(((15, 8), (70, 8), (34, 34), (8, 38)), fill=(143, 176, 179, 30))
    screen.save(TEXTURE_DIR / "vintage_crt_screen.png")

    simple = {
        "vintage_crt_trim.png": (180, 136, 78),
        "vintage_crt_bezel.png": (27, 24, 22),
        "vintage_crt_panel.png": (196, 168, 116),
        "vintage_crt_metal.png": (157, 161, 157),
        "vintage_crt_speaker.png": (44, 38, 33),
        "vintage_crt_red.png": (158, 38, 33),
        "vintage_crt_black.png": (18, 18, 17),
        "color_crt_shell.png": (48, 51, 54),
        "color_crt_shell_dark.png": (25, 27, 29),
        "color_crt_bezel.png": (13, 15, 16),
        "color_crt_speaker.png": (31, 34, 35),
        "color_crt_button.png": (71, 76, 79),
        "color_crt_led.png": (45, 192, 132),
    }
    for name, color in simple.items():
        image = Image.new("RGB", (64, 64), color)
        draw = ImageDraw.Draw(image)
        draw.line((0, 0, 63, 0), fill=tuple(min(255, c + 22) for c in color))
        draw.line((0, 63, 63, 63), fill=tuple(max(0, c - 18) for c in color))
        image.save(TEXTURE_DIR / name)

    badge = Image.new("RGB", (96, 24), (68, 36, 24))
    bd = ImageDraw.Draw(badge)
    bd.rounded_rectangle((1, 1, 94, 22), radius=4, outline=(217, 183, 100), width=2)
    bd.text((12, 2), "PIQ  CRT", font=font(15, True), fill=(231, 198, 112))
    badge.save(TEXTURE_DIR / "vintage_crt_badge.png")

    color_screen = Image.new("RGB", (128, 96), (34, 48, 49))
    csd = ImageDraw.Draw(color_screen)
    for y in range(96):
        value = int(17 * (1 - y / 95))
        csd.line((0, y, 127, y), fill=(34 + value // 3, 48 + value, 49 + value))
    csd.rounded_rectangle((2, 2, 125, 93), radius=11, outline=(86, 100, 98), width=3)
    csd.polygon(((11, 8), (76, 8), (39, 35), (7, 41)), fill=(92, 111, 107))
    color_screen.save(TEXTURE_DIR / "color_crt_screen.png")

    color_badge = Image.new("RGB", (112, 24), (21, 28, 30))
    cbd = ImageDraw.Draw(color_badge)
    cbd.rounded_rectangle((1, 1, 110, 22), radius=3, outline=(69, 151, 155), width=2)
    cbd.text((9, 2), "PIQ COLOR", font=font(14, True), fill=(116, 207, 205))
    color_badge.save(TEXTURE_DIR / "color_crt_badge.png")


def rotate_point(point, rotation):
    if not rotation:
        return point
    ox, oy, oz = rotation["origin"]
    x, y, z = point[0] - ox, point[1] - oy, point[2] - oz
    angle = math.radians(rotation["angle"])
    c, s = math.cos(angle), math.sin(angle)
    if rotation["axis"] == "x":
        y, z = y * c - z * s, y * s + z * c
    elif rotation["axis"] == "y":
        x, z = x * c + z * s, -x * s + z * c
    else:
        x, y = x * c - y * s, x * s + y * c
    return x + ox, y + oy, z + oz


def vec_sub(a, b):
    return tuple(a[index] - b[index] for index in range(3))


def dot(a, b):
    return sum(a[index] * b[index] for index in range(3))


def cross(a, b):
    return (a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])


def normalize(vector):
    length = math.sqrt(dot(vector, vector))
    return tuple(value / length for value in vector)


def face_geometry(start, end):
    x0, y0, z0 = start
    x1, y1, z1 = end
    return {
        "up": ([(x0, y1, z0), (x1, y1, z0), (x1, y1, z1), (x0, y1, z1)], (0, 1, 0), 1.08),
        "down": ([(x0, y0, z1), (x1, y0, z1), (x1, y0, z0), (x0, y0, z0)], (0, -1, 0), 0.62),
        "north": ([(x0, y0, z0), (x1, y0, z0), (x1, y1, z0), (x0, y1, z0)], (0, 0, -1), 0.96),
        "south": ([(x1, y0, z1), (x0, y0, z1), (x0, y1, z1), (x1, y1, z1)], (0, 0, 1), 0.76),
        "west": ([(x0, y0, z1), (x0, y0, z0), (x0, y1, z0), (x0, y1, z1)], (-1, 0, 0), 0.82),
        "east": ([(x1, y0, z0), (x1, y0, z1), (x1, y1, z1), (x1, y1, z0)], (1, 0, 0), 0.88),
    }


def shade(color, factor):
    return tuple(max(0, min(255, int(channel * factor))) for channel in color)


def render_view(model, direction, size):
    camera = normalize(direction)
    # Keep cabinet +X on screen-right, matching the real model coordinates.
    # The opposite cross-product mirrors the front view and falsely places
    # the channel/volume controls on the left side of the television.
    right = normalize(cross(camera, (0, 1, 0)))
    screen_up = normalize(cross(right, camera))
    center = (8, 9.2, 10.0)
    polygons = []
    projected_points = []

    for element in model["elements"]:
        rotation = element.get("rotation")
        geometry = face_geometry(element["from"], element["to"])
        for face_name, face in element.get("faces", {}).items():
            vertices, normal, light = geometry[face_name]
            rotated_vertices = [rotate_point(vertex, rotation) for vertex in vertices]
            rotated_normal_point = rotate_point(normal, {**rotation, "origin": [0, 0, 0]} if rotation else None)
            normal = normalize(rotated_normal_point)
            if dot(normal, camera) <= 0.001:
                continue
            projected = []
            depths = []
            for vertex in rotated_vertices:
                relative = vec_sub(vertex, center)
                projected.append((dot(relative, right), -dot(relative, screen_up)))
                depths.append(dot(relative, camera))
            texture_key = face.get("texture", "#wood").removeprefix("#")
            color = shade(BASE_COLORS.get(texture_key, (160, 150, 130)), light)
            polygons.append((sum(depths) / len(depths), projected, color, texture_key))
            projected_points.extend(projected)

    min_x, max_x = min(p[0] for p in projected_points), max(p[0] for p in projected_points)
    min_y, max_y = min(p[1] for p in projected_points), max(p[1] for p in projected_points)
    padding = 34
    scale = min((size[0] - padding * 2) / (max_x - min_x), (size[1] - padding * 2) / (max_y - min_y))
    offset_x = (size[0] - (min_x + max_x) * scale) / 2
    offset_y = (size[1] - (min_y + max_y) * scale) / 2

    image = Image.new("RGBA", size, (0, 0, 0, 0))
    draw = ImageDraw.Draw(image)
    for _, polygon, color, texture_key in sorted(polygons, key=lambda item: item[0]):
        points = [(x * scale + offset_x, y * scale + offset_y) for x, y in polygon]
        outline = (38, 28, 23, 255)
        draw.polygon(points, fill=(*color, 255), outline=outline, width=2)
        if texture_key == "wood":
            draw.line(points[:2], fill=(205, 151, 93, 100), width=1)
        elif texture_key in ("screen", "color_screen"):
            draw.line(points[:2], fill=(85, 121, 127, 150), width=2)
    return image


def render_preview(model):
    scale = 2
    canvas = Image.new("RGB", (1600 * scale, 1120 * scale), (232, 224, 206))
    draw = ImageDraw.Draw(canvas)
    draw.rectangle((0, 0, canvas.width, 134 * scale), fill=(75, 41, 29))
    draw.text((62 * scale, 28 * scale), "PIQ 复古 CRT 电视机", font=font(49 * scale, True), fill=(244, 220, 164))
    draw.text((1115 * scale, 45 * scale), "模型草案 · 不进 JAR", font=font(25 * scale), fill=(204, 168, 105))

    panels = [
        ((42, 162, 786, 760), (-1.1, 0.72, -1.4), "正面：4:3 鼓屏 + 机械旋钮"),
        ((814, 162, 1558, 760), (1.15, 0.66, 1.18), "背面：CRT 鼓包 + 散热结构"),
    ]
    for box, direction, label in panels:
        scaled = tuple(value * scale for value in box)
        draw.rounded_rectangle(scaled, radius=22 * scale, fill=(250, 246, 235), outline=(164, 127, 80), width=3 * scale)
        view = render_view(model, direction, ((box[2] - box[0] - 18) * scale, (box[3] - box[1] - 70) * scale))
        canvas.paste(view, ((box[0] + 9) * scale, (box[1] + 7) * scale), view)
        draw.text(((box[0] + 28) * scale, (box[3] - 52) * scale), label, font=font(26 * scale, True), fill=(72, 43, 31))

    # Bottom feature strip conveys intended usage without pretending it is in-game tested.
    draw.rounded_rectangle((42 * scale, 800 * scale, 1558 * scale, 1055 * scale), radius=22 * scale, fill=(104, 61, 40), outline=(58, 36, 26), width=3 * scale)
    front = render_view(model, (0.0, 0.32, -1.0), (410 * scale, 230 * scale))
    canvas.paste(front, (65 * scale, 810 * scale), front)
    draw.text((505 * scale, 835 * scale), "设计特征", font=font(31 * scale, True), fill=(244, 218, 158))
    features = [
        "• 木纹厚机壳、短脚与兔耳天线",
        "• 左侧独立 4:3 屏幕区域，可接 FC / SFC 动态画面",
        "• 右侧频道旋钮、音量旋钮、红色电源灯与六道扬声器格栅",
        "• 原创 PIQ 标识，不复刻现实品牌商标",
    ]
    for index, text_value in enumerate(features):
        draw.text((505 * scale, (892 + index * 39) * scale), text_value, font=font(22 * scale), fill=(244, 235, 211))

    draw.text(
        (62 * scale, 1075 * scale),
        f"由模型 JSON 直接渲染 · 共 {len(model['elements'])} 个元素 · 当前仅确认造型，未编译、未打包、未进行游戏内测试",
        font=font(19 * scale),
        fill=(91, 75, 62),
    )
    canvas.resize((1600, 1120), Image.Resampling.LANCZOS).save(PREVIEW_PATH, optimize=True)


def translated_model(model, x_offset: float, y_offset: float = 0.0):
    """Return a render-only translated copy without changing either draft."""
    translated = json.loads(json.dumps(model))
    for element in translated["elements"]:
        element["from"][0] += x_offset
        element["to"][0] += x_offset
        element["from"][1] += y_offset
        element["to"][1] += y_offset
        if "rotation" in element:
            element["rotation"]["origin"][0] += x_offset
            element["rotation"]["origin"][1] += y_offset
    return translated


def render_comparison_preview(standard_model, large_model):
    """Show both cabinets at the same world scale, plus two large-model views."""
    scale = 2
    canvas = Image.new("RGB", (1600 * scale, 1160 * scale), (231, 223, 205))
    draw = ImageDraw.Draw(canvas)
    draw.rectangle((0, 0, canvas.width, 132 * scale), fill=(35, 40, 43))
    draw.text((58 * scale, 26 * scale), "PIQ 电视机模型：木壳小电视与 2×2 彩电", font=font(43 * scale, True), fill=(226, 236, 232))
    draw.text((1248 * scale, 46 * scale), "模型草案 · 不进 JAR", font=font(23 * scale), fill=(119, 193, 190))

    # Render the pair as one combined model so their apparent scale remains comparable.
    pair = {
        "textures": standard_model["textures"],
        "elements": translated_model(standard_model, 0.0)["elements"]
        + translated_model(large_model, 22.0)["elements"],
    }
    pair_box = (42, 156, 1558, 700)
    draw.rounded_rectangle(tuple(value * scale for value in pair_box), radius=22 * scale, fill=(250, 246, 235), outline=(164, 127, 80), width=3 * scale)
    pair_view = render_view(pair, (-0.95, 0.42, -1.35), ((pair_box[2] - pair_box[0] - 18) * scale, (pair_box[3] - pair_box[1] - 72) * scale))
    canvas.paste(pair_view, ((pair_box[0] + 9) * scale, (pair_box[1] + 7) * scale), pair_view)
    draw.text((220 * scale, 646 * scale), "木壳小电视：窄脚", font=font(27 * scale, True), fill=(76, 45, 31))
    draw.text((995 * scale, 646 * scale), "2×2 方壳彩电", font=font(27 * scale, True), fill=(37, 68, 69))

    panels = [
        ((42, 734, 786, 1069), (-1.05, 0.48, -1.35), "彩电正面：大 4:3 弧面屏 · 底部按键"),
        ((814, 734, 1558, 1069), (1.12, 0.52, 1.2), "彩电背面：深机身 · 阶梯式 CRT 鼓包"),
    ]
    for box, direction, label in panels:
        scaled = tuple(value * scale for value in box)
        draw.rounded_rectangle(scaled, radius=20 * scale, fill=(250, 246, 235), outline=(164, 127, 80), width=3 * scale)
        view = render_view(large_model, direction, ((box[2] - box[0] - 18) * scale, (box[3] - box[1] - 62) * scale))
        canvas.paste(view, ((box[0] + 9) * scale, (box[1] + 5) * scale), view)
        draw.text(((box[0] + 25) * scale, (box[3] - 48) * scale), label, font=font(24 * scale, True), fill=(72, 43, 31))

    draw.text(
        (58 * scale, 1102 * scale),
        "小电视保留木壳与兔耳天线；2×2 大电视改为黑色塑料方壳彩电，不含木纹、机械旋钮或天线。",
        font=font(20 * scale),
        fill=(91, 75, 62),
    )
    canvas.resize((1600, 1160), Image.Resampling.LANCZOS).save(COMPARISON_PREVIEW_PATH, optimize=True)


def main():
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    model = build_model()
    large_model = build_large_model(model)
    MODEL_PATH.write_text(json.dumps(model, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    LARGE_MODEL_PATH.write_text(json.dumps(large_model, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    save_textures()
    render_preview(model)
    render_comparison_preview(model, large_model)
    print(f"model={MODEL_PATH}")
    print(f"elements={len(model['elements'])}")
    print(f"large_model={LARGE_MODEL_PATH}")
    print(f"large_elements={len(large_model['elements'])}")
    print(f"preview={PREVIEW_PATH}")
    print(f"comparison_preview={COMPARISON_PREVIEW_PATH}")


if __name__ == "__main__":
    main()
