"""Generate an independently authored PIQ upright arcade cabinet draft.

The draft keeps only the generic functional language of a traditional upright
arcade cabinet: marquee, recessed screen, control deck and lower coin cabinet.
Its proportions, topology, UV plan and artwork are newly authored and do not
reuse the experimental ArcadeMod OBJ or texture atlas.

All generated files live outside ``src/main/resources``.  Running this tool
therefore cannot silently place the draft in a release JAR.
"""

from __future__ import annotations

import base64
import json
import math
import uuid
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont


WORKSPACE = Path(__file__).parents[2]
OUTPUT_DIR = WORKSPACE / "制作Mod/03-街机模拟/PIQ-FC街机/原创立式街机模型草案"
TEXTURE_DIR = OUTPUT_DIR / "textures"
MODEL_PATH = OUTPUT_DIR / "piq_original_upright_arcade_draft.json"
BBMODEL_PATH = OUTPUT_DIR / "piq_original_upright_arcade_draft.bbmodel"
ALIGNED_BBMODEL_PATH = OUTPUT_DIR / "piq_original_upright_arcade_aligned_v4.bbmodel"
PREVIEW_PATH = OUTPUT_DIR.parent / "原创立式街机-模型草案.png"

TEXTURES = {
    "shell": "piq_fc_arcade:block/original_upright_shell",
    "shell_dark": "piq_fc_arcade:block/original_upright_shell_dark",
    "edge": "piq_fc_arcade:block/original_upright_edge",
    "accent": "piq_fc_arcade:block/original_upright_accent",
    "accent_warm": "piq_fc_arcade:block/original_upright_accent_warm",
    "bezel": "piq_fc_arcade:block/original_upright_bezel",
    "screen": "piq_fc_arcade:block/original_upright_screen",
    "control": "piq_fc_arcade:block/original_upright_control",
    "metal": "piq_fc_arcade:block/original_upright_metal",
    "black": "piq_fc_arcade:block/original_upright_black",
    "red": "piq_fc_arcade:block/original_upright_red",
    "yellow": "piq_fc_arcade:block/original_upright_yellow",
    "blue": "piq_fc_arcade:block/original_upright_blue",
    "marquee": "piq_fc_arcade:block/original_upright_marquee",
    "side_art": "piq_fc_arcade:block/original_upright_side_art",
    "racer_left_lower": "piq_fc_arcade:block/original_upright_racer_left_lower",
    "racer_left_upper": "piq_fc_arcade:block/original_upright_racer_left_upper",
    "racer_left_marquee": "piq_fc_arcade:block/original_upright_racer_left_marquee",
    "racer_right_lower": "piq_fc_arcade:block/original_upright_racer_right_lower",
    "racer_right_upper": "piq_fc_arcade:block/original_upright_racer_right_upper",
    "racer_right_marquee": "piq_fc_arcade:block/original_upright_racer_right_marquee",
    "coin": "piq_fc_arcade:block/original_upright_coin",
}

BASE_COLORS = {
    "shell": (32, 43, 59),
    "shell_dark": (18, 25, 36),
    "edge": (8, 15, 25),
    "accent": (24, 184, 185),
    "accent_warm": (246, 150, 40),
    "bezel": (11, 12, 17),
    "screen": (19, 35, 42),
    "control": (30, 105, 133),
    "metal": (119, 133, 145),
    "black": (9, 11, 15),
    "red": (221, 58, 66),
    "yellow": (246, 185, 42),
    "blue": (55, 111, 220),
    "marquee": (41, 78, 125),
    "side_art": (38, 96, 130),
    "racer_left_lower": (28, 64, 91),
    "racer_left_upper": (24, 56, 84),
    "racer_left_marquee": (22, 51, 80),
    "racer_right_lower": (28, 64, 91),
    "racer_right_upper": (24, 56, 84),
    "racer_right_marquee": (22, 51, 80),
    "coin": (41, 47, 55),
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
        "from": [round(value, 4) for value in start],
        "to": [round(value, 4) for value in end],
        "faces": faces(texture, only),
    }
    if rotation:
        element["rotation"] = rotation
    elements.append(element)


def x_rotation(origin, angle):
    return {"origin": list(origin), "axis": "x", "angle": angle, "rescale": False}


def build_model():
    elements = []

    # V4 restores the last visually approved/default cabinet geometry.  The
    # marquee, screen frame, control deck and lower cabinet all share X=8.
    # Keeping these measurements together prevents the top from collapsing
    # into the rear shell and prevents the front from reading as three boxes.
    add_cube(elements, "base_plinth", (0.8, 0.0, 4.1), (15.2, 1.45, 15.55), "edge")
    add_cube(elements, "base_front_light", (1.45, 0.48, 3.72), (14.55, 1.16, 4.28), "accent")
    add_cube(elements, "toe_kick", (2.35, 1.35, 7.25), (13.65, 2.8, 14.8), "shell_dark")
    add_cube(elements, "rear_spine_lower", (1.45, 1.35, 12.85), (14.55, 15.25, 15.15), "shell_dark")
    add_cube(elements, "lower_cabinet", (1.55, 2.75, 6.25), (14.45, 13.7, 14.9), "shell")
    add_cube(elements, "lower_front", (2.05, 3.35, 5.72), (13.95, 13.05, 6.75), "shell_dark")
    add_cube(elements, "lower_left_edge", (1.45, 2.65, 5.55), (2.18, 14.0, 7.05), "edge")
    add_cube(elements, "lower_right_edge", (13.82, 2.65, 5.55), (14.55, 14.0, 7.05), "edge")

    # Recessed coin service panel with two illuminated slots and a return tray.
    add_cube(elements, "coin_panel", (5.05, 4.05, 5.25), (10.95, 11.85, 5.96), "coin")
    add_cube(elements, "coin_slot_left", (6.05, 9.5, 4.98), (7.45, 10.05, 5.38), "accent_warm")
    add_cube(elements, "coin_slot_right", (8.55, 9.5, 4.98), (9.95, 10.05, 5.38), "accent_warm")
    add_cube(elements, "coin_return", (6.15, 5.05, 4.96), (9.85, 6.45, 5.38), "black")
    add_cube(elements, "service_badge", (7.15, 7.15, 4.93), (8.85, 8.45, 5.37), "accent")

    add_cube(elements, "waist_bridge", (1.45, 13.15, 5.1), (14.55, 15.35, 14.95), "shell")
    add_cube(elements, "control_rear_riser", (1.55, 15.0, 9.2), (14.45, 17.1, 14.9), "shell")
    add_cube(elements, "control_underbody", (1.25, 13.25, 2.55), (14.75, 14.45, 10.45), "shell_dark")
    add_cube(elements, "waist_front_edge", (1.05, 13.35, 2.0), (14.95, 14.35, 3.35), "edge")
    add_cube(elements, "waist_accent", (1.25, 13.62, 1.72), (14.75, 14.02, 2.22), "accent")

    control_rotation = None
    add_cube(elements, "control_deck", (1.2, 14.15, 2.1), (14.8, 15.35, 10.35), "control", control_rotation)
    add_cube(elements, "joystick_base", (3.05, 15.02, 4.0), (5.15, 15.62, 6.1), "black", control_rotation)
    add_cube(elements, "joystick_stem", (3.88, 15.45, 4.82), (4.32, 17.0, 5.26), "metal", control_rotation)
    add_cube(elements, "joystick_ball", (3.4, 16.55, 4.35), (4.8, 17.95, 5.75), "red", control_rotation)
    button_specs = (
        ("button_a", 8.45, 4.1, "red"),
        ("button_b", 10.25, 4.55, "yellow"),
        ("button_x", 9.05, 6.15, "blue"),
        ("button_y", 10.85, 6.6, "accent"),
    )
    for name, x, z, texture in button_specs:
        add_cube(elements, name, (x, 15.18, z), (x + 1.15, 15.74, z + 1.15), texture, control_rotation)
    add_cube(elements, "start_button", (6.25, 15.18, 7.55), (7.35, 15.67, 8.0), "metal", control_rotation)

    # Restore the approved sloped screen as one rigid assembly.  Every rail
    # uses the same origin/angle, so the glass cannot drift out of its frame.
    add_cube(elements, "rear_spine_mid", (1.5, 15.2, 12.4), (14.5, 23.6, 15.12), "shell_dark")
    add_cube(elements, "rear_spine_crown", (1.35, 23.4, 10.6), (14.65, 27.8, 15.12), "shell")
    add_cube(elements, "upper_left_cheek", (1.25, 15.45, 4.15), (2.25, 27.7, 14.95), "shell")
    add_cube(elements, "upper_right_cheek", (13.75, 15.45, 4.15), (14.75, 27.7, 14.95), "shell")
    add_cube(elements, "screen_chin", (1.55, 15.7, 3.65), (14.45, 17.15, 12.55), "shell_dark")
    add_cube(elements, "screen_brow", (1.45, 25.75, 4.55), (14.55, 27.75, 13.35), "shell_dark")
    add_cube(elements, "screen_back", (2.15, 17.0, 11.85), (13.85, 26.75, 14.7), "shell_dark")

    screen_rotation = x_rotation((8.0, 16.75, 4.45), 10)
    add_cube(elements, "screen_recess", (1.9, 17.2, 2.72), (14.1, 26.45, 4.5), "bezel", screen_rotation)
    add_cube(elements, "screen_surface", (2.7, 18.1, 2.32), (13.3, 26.05, 2.88), "screen", screen_rotation, only=("north", "up", "down", "west", "east"))
    add_cube(elements, "screen_left_rail", (1.5, 16.85, 2.26), (2.42, 26.85, 4.72), "accent", screen_rotation)
    add_cube(elements, "screen_right_rail", (13.58, 16.85, 2.26), (14.5, 26.85, 4.72), "accent", screen_rotation)
    add_cube(elements, "screen_top_rail", (1.65, 26.08, 2.22), (14.35, 26.95, 4.62), "edge", screen_rotation)
    add_cube(elements, "screen_bottom_rail", (1.65, 16.78, 2.2), (14.35, 17.65, 4.62), "edge", screen_rotation)

    # The top is a separate full-width marquee, as in the approved default.
    # Its face is in front of the shell and therefore remains visible from
    # Blockbench's default camera instead of becoming a featureless roof.
    add_cube(elements, "marquee_shell", (0.65, 27.05, 1.55), (15.35, 31.15, 12.95), "edge")
    add_cube(elements, "marquee_rear_cap", (1.4, 27.35, 12.7), (14.6, 30.95, 14.75), "shell")
    add_cube(elements, "marquee_face", (1.15, 27.62, 1.08), (14.85, 30.72, 1.88), "marquee", only=("north",))
    add_cube(elements, "marquee_lower_light", (1.35, 27.05, 0.88), (14.65, 27.5, 1.86), "accent_warm")
    add_cube(elements, "marquee_left_cap", (0.42, 27.42, 0.98), (1.42, 31.0, 2.05), "accent")
    add_cube(elements, "marquee_right_cap", (14.58, 27.42, 0.98), (15.58, 31.0, 2.05), "accent")
    add_cube(elements, "marquee_top", (1.0, 30.72, 1.5), (15.0, 31.48, 13.85), "shell_dark")

    # Both sides are cut from one authored racing artwork master.  The model
    # still needs separate surfaces around the control-deck notch, but the
    # source coordinates and artwork remain continuous across those surfaces.
    add_cube(elements, "left_racer_lower", (1.43, 2.75, 6.05), (1.54, 14.15, 15.18), "racer_left_lower", only=("west",))
    add_cube(elements, "left_racer_upper", (1.23, 15.4, 4.05), (1.34, 27.78, 15.18), "racer_left_upper", only=("west",))
    add_cube(elements, "left_racer_marquee", (0.53, 27.08, 1.48), (0.64, 31.2, 13.02), "racer_left_marquee", only=("west",))
    add_cube(elements, "right_racer_lower", (14.46, 2.75, 6.05), (14.57, 14.15, 15.18), "racer_right_lower", only=("east",))
    add_cube(elements, "right_racer_upper", (14.66, 15.4, 4.05), (14.77, 27.78, 15.18), "racer_right_upper", only=("east",))
    add_cube(elements, "right_racer_marquee", (15.36, 27.08, 1.48), (15.47, 31.2, 13.02), "racer_right_marquee", only=("east",))
    for index in range(6):
        y = 18.15 + index * 1.16
        add_cube(elements, f"rear_vent_{index + 1}", (4.0, y, 15.62), (12.0, y + 0.32, 15.95), "black")
    add_cube(elements, "rear_service_panel", (4.1, 5.0, 14.9), (11.9, 11.9, 15.58), "shell_dark")
    add_cube(elements, "rear_badge", (6.15, 7.3, 15.5), (9.85, 9.4, 15.88), "accent")

    return {
        "credit": "PIQ original upright arcade cabinet draft; independent topology and artwork",
        "design_status": "preview-only; outside release resources",
        "texture_size": [64, 64],
        "textures": TEXTURES,
        "elements": elements,
        "screen_anchor": {
            "note": "4:3 dynamic surface; update renderer coordinates only after visual approval",
            "from": [2.7, 18.1, 2.32],
            "to": [13.3, 26.05, 2.88],
            "rotation": screen_rotation,
        },
    }


def texture_tile(name: str, base: tuple[int, int, int], size=(64, 64)):
    image = Image.new("RGB", size, base)
    draw = ImageDraw.Draw(image)
    if name in ("shell", "shell_dark"):
        for y in range(0, size[1], 4):
            tone = tuple(max(0, channel - (3 if (y // 4) % 2 else 0)) for channel in base)
            draw.line((0, y, size[0], y), fill=tone)
        for x in range(-64, 128, 16):
            draw.line((x, 64, x + 64, 0), fill=tuple(min(255, channel + 7) for channel in base), width=1)
    elif name in ("accent", "accent_warm"):
        for x in range(0, size[0], 8):
            draw.line((x, 0, x, size[1]), fill=tuple(min(255, channel + 18) for channel in base))
    elif name == "screen":
        for y in range(size[1]):
            glow = int(15 * (1 - y / max(1, size[1] - 1)))
            draw.line((0, y, size[0], y), fill=tuple(min(255, channel + glow) for channel in base))
        draw.rounded_rectangle((2, 2, size[0] - 3, size[1] - 3), radius=7, outline=(48, 77, 84), width=2)
    elif name == "coin":
        for y in range(2, size[1], 6):
            draw.line((2, y, size[0] - 3, y), fill=(32, 37, 44))
    else:
        draw.rectangle((0, 0, size[0] - 1, size[1] - 1), outline=tuple(max(0, channel - 18) for channel in base))
    return image


def save_textures():
    TEXTURE_DIR.mkdir(parents=True, exist_ok=True)
    for stale in TEXTURE_DIR.glob("original_upright_*.png"):
        stale.unlink()
    for name, color in BASE_COLORS.items():
        if name in ("marquee", "side_art") or name.startswith("racer_"):
            continue
        texture_tile(name, color).save(TEXTURE_DIR / f"original_upright_{name}.png")

    marquee = Image.new("RGB", (256, 64), (20, 36, 64))
    draw = ImageDraw.Draw(marquee)
    for x in range(256):
        blend = x / 255
        draw.line((x, 0, x, 63), fill=(int(27 + 75 * blend), int(79 + 20 * blend), int(128 - 25 * blend)))
    draw.rectangle((4, 4, 251, 59), outline=(28, 207, 202), width=3)
    draw.line((18, 45, 64, 45, 72, 28, 84, 52, 95, 35, 112, 35), fill=(248, 164, 51), width=3)
    draw.text((122, 8), "PIQ", font=font(34, True), fill=(247, 238, 214), anchor="ma")
    draw.text((188, 43), "PIXEL STATION", font=font(13, True), fill=(255, 184, 56), anchor="mm")
    marquee.save(TEXTURE_DIR / "original_upright_marquee.png")

    side_art = Image.new("RGB", (128, 256), (25, 62, 88))
    sd = ImageDraw.Draw(side_art)
    for y in range(256):
        sd.line((0, y, 127, y), fill=(25, int(62 + 24 * y / 255), int(88 + 34 * y / 255)))
    sd.arc((10, 36, 116, 151), 205, 505, fill=(31, 205, 199), width=5)
    sd.arc((28, 59, 132, 184), 80, 340, fill=(247, 158, 41), width=4)
    sd.rounded_rectangle((37, 89, 93, 145), radius=12, outline=(238, 239, 222), width=4)
    sd.text((65, 116), "PIQ", font=font(21, True), fill=(238, 239, 222), anchor="mm")
    for x, y, color in ((19, 182, (247, 158, 41)), (101, 201, (31, 205, 199)), (43, 222, (221, 58, 66)), (88, 238, (246, 185, 42))):
        sd.rectangle((x - 3, y - 3, x + 3, y + 3), fill=color)
    sd.line((12, 245, 116, 245), fill=(31, 205, 199), width=3)
    side_art.save(TEXTURE_DIR / "original_upright_side_art.png")

    racer_master = build_racer_side_master()
    racer_master.save(TEXTURE_DIR / "original_upright_racer_side_master.png")
    # These bounds follow the V3 aligned silhouette.  Keeping the master
    # artwork in one coordinate system lets the separate Minecraft cuboids
    # meet without visible jumps at the waist and marquee seams.
    regions = {
        "lower": (6.05, 2.65, 15.2, 14.2),
        "upper": (3.95, 15.3, 15.2, 27.9),
        "marquee": (0.85, 27.25, 8.25, 31.4),
    }
    for side, source in (("left", racer_master), ("right", racer_master.transpose(Image.Transpose.FLIP_LEFT_RIGHT))):
        for segment, bounds in regions.items():
            crop_racer_region(source, *bounds).save(
                TEXTURE_DIR / f"original_upright_racer_{side}_{segment}.png"
            )


def stable_uuid(kind: str, name: str):
    return str(uuid.uuid5(uuid.NAMESPACE_URL, f"piq-original-upright/{kind}/{name}"))


def write_blockbench_project(model, output_path=BBMODEL_PATH):
    """Write a portable Blockbench project with every used PNG embedded."""
    texture_ids = {name: str(index) for index, name in enumerate(TEXTURES)}
    texture_entries = []
    for name, texture_id in texture_ids.items():
        path = (TEXTURE_DIR / f"original_upright_{name}.png").resolve()
        raw = path.read_bytes()
        with Image.open(path) as texture_image:
            width, height = texture_image.size
        texture_entries.append(
            {
                "path": path.as_posix(),
                "name": path.name,
                "folder": "",
                "namespace": "piq_fc_arcade",
                "id": texture_id,
                "particle": False,
                "render_mode": "default",
                "render_sides": "auto",
                "frame_time": 1,
                "frame_order_type": "loop",
                "frame_order": "",
                "frame_interpolate": False,
                "visible": True,
                "internal": True,
                "saved": True,
                "uuid": stable_uuid("texture", name),
                "relative_path": f"textures/{path.name}",
                "width": width,
                "height": height,
                "source": "data:image/png;base64," + base64.b64encode(raw).decode("ascii"),
            }
        )

    project_elements = []
    outliner = []
    for index, source in enumerate(model["elements"]):
        element_uuid = stable_uuid("element", f"{index}-{source['name']}")
        start = source["from"]
        end = source["to"]
        element = {
            "name": source["name"],
            "box_uv": False,
            "rescale": False,
            "locked": False,
            "light_emission": 0,
            "render_order": "default",
            "allow_mirror_modeling": True,
            "from": start,
            "to": end,
            "autouv": 0,
            "color": index % 8,
            "origin": [round((start[axis] + end[axis]) / 2, 4) for axis in range(3)],
            "faces": {},
            "type": "cube",
            "uuid": element_uuid,
        }
        for direction, face in source["faces"].items():
            texture_name = face["texture"].removeprefix("#")
            element["faces"][direction] = {
                "uv": face["uv"],
                "rotation": 0,
                "texture": int(texture_ids[texture_name]),
            }
        if "rotation" in source:
            rotation = source["rotation"]
            vector = [0.0, 0.0, 0.0]
            vector[("x", "y", "z").index(rotation["axis"])] = rotation["angle"]
            element["origin"] = rotation["origin"]
            element["rotation"] = vector
            element["rescale"] = rotation.get("rescale", False)
        project_elements.append(element)
        outliner.append(element_uuid)

    project_name = output_path.stem
    project = {
        "meta": {
            "format_version": "4.12",
            "model_format": "free",
            "box_uv": False,
        },
        "name": project_name,
        "model_identifier": f"piq_fc_arcade:{project_name}",
        "visible_box": [1, 1, 0],
        "variable_placeholders": "",
        "variable_placeholder_buttons": [],
        "timeline_setups": [],
        "unhandled_root_fields": {},
        "resolution": {"width": 16, "height": 16},
        "elements": project_elements,
        "outliner": outliner,
        "textures": texture_entries,
    }
    output_path.write_text(json.dumps(project, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def side_pixel(z: float, y: float):
    """Map cabinet side coordinates onto the continuous 512x1024 artwork."""
    return (
        round((z - 1.5) / 14.0 * 511),
        round((32.0 - y) / 32.0 * 1023),
    )


def build_racer_side_master():
    image = Image.new("RGB", (512, 1024), (18, 38, 62))
    draw = ImageDraw.Draw(image)
    for y in range(1024):
        t = y / 1023
        draw.line((0, y, 511, y), fill=(18 + int(10 * t), 38 + int(23 * t), 62 + int(29 * t)))

    # One uninterrupted track ribbon and speed trail across the whole cabinet.
    draw.polygon(((0, 118), (512, 18), (512, 154), (0, 286)), fill=(20, 175, 186))
    draw.polygon(((0, 148), (512, 54), (512, 92), (0, 198)), fill=(235, 242, 230))
    draw.polygon(((0, 346), (512, 205), (512, 298), (0, 466)), fill=(225, 50, 58))
    draw.line((12, 505, 500, 354), fill=(248, 164, 39), width=13)
    draw.line((20, 527, 502, 381), fill=(26, 206, 201), width=7)

    # Original side-view racing car.  The whole silhouette sits inside the
    # large lower side field, so neither wheel nor the nose can be chopped by
    # the control-deck notch or by a structural texture boundary.
    car_outline = [(178, 792), (196, 744), (242, 722), (282, 655), (370, 655), (409, 713), (457, 731), (489, 777), (470, 830), (188, 830)]
    draw.polygon(car_outline, fill=(11, 17, 27), outline=(242, 243, 232), width=8)
    car_body = [(190, 784), (210, 751), (252, 735), (292, 675), (360, 675), (397, 723), (445, 743), (472, 782), (455, 810), (200, 810)]
    draw.polygon(car_body, fill=(218, 47, 54), outline=(248, 157, 36), width=5)
    draw.polygon(((250, 724), (298, 679), (354, 679), (389, 724)), fill=(18, 42, 64), outline=(47, 210, 205), width=5)
    draw.line((326, 679, 326, 726), fill=(232, 241, 232), width=4)
    draw.polygon(((199, 775), (249, 743), (241, 793), (197, 803)), fill=(24, 187, 188))
    draw.polygon(((421, 737), (465, 758), (474, 781), (438, 777)), fill=(246, 180, 38))
    for cx in (251, 414):
        draw.ellipse((cx - 39, 786, cx + 39, 864), fill=(7, 11, 18), outline=(32, 188, 191), width=7)
        draw.ellipse((cx - 18, 807, cx + 18, 843), fill=(119, 133, 145), outline=(237, 241, 230), width=4)
    draw.rounded_rectangle((335, 735, 400, 790), radius=9, fill=(242, 239, 217), outline=(16, 31, 49), width=4)
    draw.text((367, 762), "08", font=font(32, True), fill=(24, 54, 83), anchor="mm")
    draw.text((257, 903), "PIQ", font=font(52, True), fill=(239, 243, 229), anchor="mm")
    draw.text((390, 904), "RACING", font=font(27, True), fill=(246, 164, 39), anchor="mm")
    for index, x in enumerate(range(-64, 560, 52)):
        color = (239, 243, 229) if index % 2 == 0 else (24, 184, 185)
        draw.polygon(((x, 948), (x + 31, 948), (x + 6, 1001), (x - 25, 1001)), fill=color)
    return image


def crop_racer_region(image: Image.Image, z0: float, y0: float, z1: float, y1: float):
    left, bottom = side_pixel(z0, y0)
    right, top = side_pixel(z1, y1)
    box = (max(0, min(left, right)), max(0, min(top, bottom)), min(512, max(left, right) + 1), min(1024, max(top, bottom) + 1))
    return image.crop(box).resize((256, 256), Image.Resampling.LANCZOS)


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
        "down": ([(x0, y0, z1), (x1, y0, z1), (x1, y0, z0), (x0, y0, z0)], (0, -1, 0), 0.58),
        "north": ([(x0, y0, z0), (x1, y0, z0), (x1, y1, z0), (x0, y1, z0)], (0, 0, -1), 0.98),
        "south": ([(x1, y0, z1), (x0, y0, z1), (x0, y1, z1), (x1, y1, z1)], (0, 0, 1), 0.72),
        "west": ([(x0, y0, z1), (x0, y0, z0), (x0, y1, z0), (x0, y1, z1)], (-1, 0, 0), 0.82),
        "east": ([(x1, y0, z0), (x1, y0, z1), (x1, y1, z1), (x1, y1, z0)], (1, 0, 0), 0.9),
    }


def shade(color, factor):
    return tuple(max(0, min(255, int(channel * factor))) for channel in color)


def render_view(model, direction, size):
    camera = normalize(direction)
    right = normalize(cross(camera, (0, 1, 0)))
    screen_up = normalize(cross(right, camera))
    center = (8.0, 15.8, 8.4)
    polygons = []
    projected_points = []
    for element in model["elements"]:
        rotation = element.get("rotation")
        geometry = face_geometry(element["from"], element["to"])
        for face_name, face in element.get("faces", {}).items():
            vertices, normal, light = geometry[face_name]
            vertices = [rotate_point(vertex, rotation) for vertex in vertices]
            rotated_normal = rotate_point(normal, {**rotation, "origin": [0, 0, 0]} if rotation else None)
            normal = normalize(rotated_normal)
            if dot(normal, camera) <= 0.001:
                continue
            projected = []
            depths = []
            for vertex in vertices:
                relative = vec_sub(vertex, center)
                projected.append((dot(relative, right), -dot(relative, screen_up)))
                depths.append(dot(relative, camera))
            texture_key = face.get("texture", "#shell").removeprefix("#")
            polygons.append((sum(depths) / len(depths), projected, shade(BASE_COLORS.get(texture_key, (120, 120, 120)), light), texture_key))
            projected_points.extend(projected)

    min_x, max_x = min(point[0] for point in projected_points), max(point[0] for point in projected_points)
    min_y, max_y = min(point[1] for point in projected_points), max(point[1] for point in projected_points)
    padding = 30
    scale = min((size[0] - padding * 2) / (max_x - min_x), (size[1] - padding * 2) / (max_y - min_y))
    offset_x = (size[0] - (min_x + max_x) * scale) / 2
    offset_y = (size[1] - (min_y + max_y) * scale) / 2
    image = Image.new("RGBA", size, (0, 0, 0, 0))
    draw = ImageDraw.Draw(image)
    for _, polygon, color, texture_key in sorted(polygons, key=lambda item: item[0]):
        points = [(x * scale + offset_x, y * scale + offset_y) for x, y in polygon]
        draw.polygon(points, fill=(*color, 255), outline=(5, 10, 18, 255), width=2)
        if texture_key in ("accent", "accent_warm"):
            draw.line(points[:2], fill=(224, 255, 245, 150), width=1)
        elif texture_key == "screen":
            draw.line(points[:2], fill=(55, 95, 105, 180), width=2)
    return image


def panel(draw, box, scale):
    scaled = tuple(value * scale for value in box)
    draw.rounded_rectangle(scaled, radius=20 * scale, fill=(241, 246, 247), outline=(93, 131, 145), width=3 * scale)


def render_continuous_side_profile(size):
    """Show the authored side as one continuous skin, not flat material tiles."""
    master = Image.open(TEXTURE_DIR / "original_upright_racer_side_master.png").convert("RGBA")
    mask = Image.new("L", master.size, 0)
    mask_draw = ImageDraw.Draw(mask)
    outline = (
        (1.48, 31.2),
        (13.02, 31.2),
        (14.75, 30.95),
        (15.18, 23.4),
        (15.18, 0.0),
        (4.1, 0.0),
        (6.05, 2.75),
        (6.05, 13.35),
        (1.48, 13.35),
        (1.48, 15.4),
        (4.05, 15.4),
        (4.05, 27.08),
        (1.48, 27.08),
    )
    polygon = [side_pixel(z, y) for z, y in outline]
    mask_draw.polygon(polygon, fill=255)
    profile = Image.new("RGBA", master.size, (0, 0, 0, 0))
    profile.paste(master, (0, 0), mask)
    profile_draw = ImageDraw.Draw(profile)
    profile_draw.line(polygon + [polygon[0]], fill=(7, 14, 24, 255), width=8, joint="curve")

    bounds = profile.getbbox()
    if bounds:
        profile = profile.crop(bounds)
    profile.thumbnail((size[0] - 42, size[1] - 52), Image.Resampling.LANCZOS)
    result = Image.new("RGBA", size, (0, 0, 0, 0))
    result.alpha_composite(profile, ((size[0] - profile.width) // 2, (size[1] - profile.height) // 2 - 4))
    return result


def render_preview(model):
    scale = 2
    canvas = Image.new("RGB", (1600 * scale, 1160 * scale), (225, 236, 238))
    draw = ImageDraw.Draw(canvas)
    draw.rectangle((0, 0, canvas.width, 136 * scale), fill=(19, 31, 48))
    draw.text((58 * scale, 27 * scale), "PIQ 原创立式街机", font=font(48 * scale, True), fill=(233, 244, 239))
    draw.text((1055 * scale, 45 * scale), "独立网格 / 独立 UV · 模型草案", font=font(24 * scale), fill=(45, 201, 197))

    views = [
        ((38, 160, 542, 790), (-1.15, 0.62, -1.38), "正面 3/4"),
        ((548, 160, 1052, 790), (1.18, 0.58, -1.32), "右侧 3/4"),
    ]
    for box, direction, label in views:
        panel(draw, box, scale)
        view = render_view(model, direction, ((box[2] - box[0] - 16) * scale, (box[3] - box[1] - 66) * scale))
        canvas.paste(view, ((box[0] + 8) * scale, (box[1] + 4) * scale), view)
        draw.text(((box[0] + 22) * scale, (box[3] - 48) * scale), label, font=font(24 * scale, True), fill=(24, 51, 67))

    side_box = (1058, 160, 1562, 790)
    panel(draw, side_box, scale)
    side_view = render_continuous_side_profile(
        ((side_box[2] - side_box[0] - 16) * scale, (side_box[3] - side_box[1] - 66) * scale)
    )
    canvas.paste(side_view, ((side_box[0] + 8) * scale, (side_box[1] + 4) * scale), side_view)
    draw.text(((side_box[0] + 22) * scale, (side_box[3] - 48) * scale), "连续赛车侧绘", font=font(24 * scale, True), fill=(24, 51, 67))

    info_box = (38, 820, 1562, 1104)
    draw.rounded_rectangle(tuple(value * scale for value in info_box), radius=22 * scale, fill=(26, 44, 65), outline=(45, 201, 197), width=3 * scale)
    draw.text((68 * scale, 850 * scale), "设计边界", font=font(29 * scale, True), fill=(250, 174, 57))
    bullets = [
        "• 保留传统立式街机语义：顶部灯箱、后仰 4:3 屏幕、前伸操作台、下部币门",
        "• 左右侧壳共用一张赛车母版裁切：赛车、轮胎、赛道和速度线不在结构接缝处重新起图",
        "• 预留现有 FC / SFC 动态画面接口；批准造型后才迁移渲染坐标并制作正式 UV",
        "• 当前草案不引用旧 ArcadeMod OBJ、MTL、UV 或贴图，也尚未进入 JAR",
    ]
    for index, line in enumerate(bullets):
        draw.text((68 * scale, (902 + index * 42) * scale), line, font=font(22 * scale), fill=(229, 239, 239))

    # Small material cards show the intended authored visual language even
    # though the fast orthographic renderer shades by material color only.
    cards = [
        ("灯箱", TEXTURE_DIR / "original_upright_marquee.png", (1100, 846, 1518, 950)),
        ("侧绘母版", TEXTURE_DIR / "original_upright_racer_side_master.png", (1226, 960, 1518, 1084)),
    ]
    for label, path, box in cards:
        image = Image.open(path).convert("RGB")
        image.thumbnail(((box[2] - box[0]) * scale, (box[3] - box[1]) * scale), Image.Resampling.LANCZOS)
        canvas.paste(image, (box[0] * scale, box[1] * scale))
        draw.text(((box[0] - 86) * scale, (box[1] + 8) * scale), label, font=font(20 * scale, True), fill=(250, 174, 57))

    draw.text((58 * scale, 1121 * scale), f"由草案 JSON 直接渲染 · {len(model['elements'])} 个独立元素 · 未编译 / 未打包 / 未进行游戏内测试", font=font(19 * scale), fill=(65, 84, 91))
    canvas.resize((1600, 1160), Image.Resampling.LANCZOS).save(PREVIEW_PATH, optimize=True)


def write_readme(model):
    texture_count = len(list(TEXTURE_DIR.glob("original_upright_*.png")))
    text = f"""# PIQ 原创立式街机模型草案

本目录是对传统立式街机外形的独立重制草案，用于替换当前实验型旧资产。

- 模型：`{MODEL_PATH.name}`
- Blockbench 工程：`{ALIGNED_BBMODEL_PATH.name}`（V4 对齐版，已嵌入全部贴图，优先打开这个文件）
- 兼容文件名：`{BBMODEL_PATH.name}`（与 V4 内容相同，供旧流程使用）
- 元素数：{len(model['elements'])}
- 贴图：`textures/` 中共 {texture_count} 张独立草案贴图
- 连续侧绘：`original_upright_racer_side_master.png` 是唯一赛车侧绘母版，左右侧和几何分面均由它按坐标裁切
- 动态屏幕：模型中保留 `screen_anchor`，批准造型后再接入 FC / SFC 渲染器
- 资产边界：未引用 `legacy_generic_machine.obj` 的顶点、面、UV 或原贴图
- 当前状态：只做外观审阅；不在 `src/main/resources`，不会进入现有 JAR

## Blockbench 打开方式

请关闭 Blockbench 中旧的草案标签，再直接打开 `{ALIGNED_BBMODEL_PATH.name}`。工程内嵌了模型实际使用的 {len(TEXTURES)} 张 PNG，同时保留
`textures/` 相对路径和当前电脑的绝对路径，因此无需在 Blockbench 中逐张重新定位。
原始 `{MODEL_PATH.name}` 是 Minecraft 风格的源数据，不建议直接拿它做 Blockbench 编辑工程。

## 与旧实验模型的区别

新模型采用完整下柜、内收脚位、独立币门、连续赛车侧壳、阶梯背板和较窄灯箱；
只保留“顶部灯箱 + 斜屏 + 操作台 + 下柜”这一公共街机类型特征。
"""
    (OUTPUT_DIR / "README.md").write_text(text, encoding="utf-8")


def main():
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    model = build_model()
    MODEL_PATH.write_text(json.dumps(model, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    save_textures()
    write_blockbench_project(model)
    write_blockbench_project(model, ALIGNED_BBMODEL_PATH)
    write_readme(model)
    render_preview(model)
    print(f"model={MODEL_PATH}")
    print(f"blockbench={BBMODEL_PATH}")
    print(f"blockbench_aligned={ALIGNED_BBMODEL_PATH}")
    print(f"elements={len(model['elements'])}")
    print(f"textures={len(list(TEXTURE_DIR.glob('original_upright_*.png')))}")
    print(f"preview={PREVIEW_PATH}")


if __name__ == "__main__":
    main()
