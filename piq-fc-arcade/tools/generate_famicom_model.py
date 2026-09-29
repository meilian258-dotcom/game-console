"""Generate the detailed PIQ red-and-cream 8-bit console block model."""

from __future__ import annotations

import json
from pathlib import Path


ROOT = Path(__file__).parents[1]
OUTPUT = ROOT / "src/main/resources/assets/piq_fc_arcade/models/block/famicom_console.json"


TEXTURES = {
    "cream": "minecraft:block/smooth_quartz",
    "cream_shadow": "minecraft:block/calcite",
    "red": "minecraft:block/red_concrete",
    "dark_red": "minecraft:block/red_terracotta",
    "gold": "minecraft:block/yellow_terracotta",
    "black": "minecraft:block/black_concrete",
    "gray": "minecraft:block/polished_deepslate",
    "blue": "minecraft:block/blue_concrete",
    "contact": "minecraft:block/light_blue_concrete",
    "led": "minecraft:block/redstone_block",
    "wire": "minecraft:block/black_wool",
    "brand": "piq_fc_arcade:block/famicom_brand",
    "power_label": "piq_fc_arcade:block/famicom_power_label",
    "eject_label": "piq_fc_arcade:block/famicom_eject_label",
    "reset_label": "piq_fc_arcade:block/famicom_reset_label",
    "controller_1": "piq_fc_arcade:block/famicom_controller_1",
    "controller_2": "piq_fc_arcade:block/famicom_controller_2",
    "particle": "minecraft:block/smooth_quartz",
}


def face(texture: str, full_uv: bool = False) -> dict:
    result = {"texture": f"#{texture}"}
    if full_uv:
        result["uv"] = [0, 0, 16, 16]
    return result


def cube(
    name: str,
    start: list[float],
    end: list[float],
    texture: str,
    face_overrides: dict[str, str] | None = None,
) -> dict:
    custom = face_overrides or {}
    faces = {}
    for direction in ("down", "up", "north", "south", "west", "east"):
        selected = custom.get(direction, texture)
        faces[direction] = face(selected, selected in {
            "brand", "power_label", "eject_label", "reset_label",
            "controller_1", "controller_2",
        })
    return {"name": name, "from": start, "to": end, "faces": faces}


def build_elements() -> list[dict]:
    elements: list[dict] = []

    # Main chassis: narrower and taller than the first revision, matching the
    # recognizable cream shell and red lower rim of the 1980s family console.
    elements.extend([
        cube("red_lower_plinth", [2.6, 0.4, 2.0], [13.4, 1.15, 14.8], "dark_red"),
        cube("cream_main_shell", [2.85, 1.15, 2.25], [13.15, 4.65, 14.55], "cream"),
        cube("front_lower_lip", [2.65, 1.0, 1.75], [13.35, 3.0, 3.15], "cream_shadow"),
        cube("front_brand_plate", [3.3, 1.3, 1.48], [12.7, 2.5, 1.8], "dark_red", {"north": "brand"}),
        cube("top_deck", [3.1, 4.65, 2.7], [12.9, 5.2, 14.2], "cream"),
        cube("left_red_top_trim", [2.75, 4.35, 3.1], [3.25, 5.55, 13.9], "red"),
        cube("right_red_top_trim", [12.75, 4.35, 3.1], [13.25, 5.55, 13.9], "red"),
        cube("front_red_trim", [3.05, 4.45, 2.25], [12.95, 5.15, 3.1], "red"),
    ])

    # Three distinct control stations on the front half of the top deck.
    elements.extend([
        cube("power_label_plate", [3.35, 5.2, 3.15], [5.75, 5.28, 5.65], "cream", {"up": "power_label"}),
        cube("power_switch_recess", [3.8, 5.28, 3.85], [5.35, 5.48, 5.05], "dark_red"),
        cube("power_switch", [4.05, 5.48, 4.0], [5.1, 5.95, 4.85], "red"),
        cube("eject_label_plate", [6.0, 5.2, 3.1], [10.0, 5.28, 6.25], "cream", {"up": "eject_label"}),
        cube("eject_slider_base", [6.8, 5.28, 3.75], [9.2, 5.52, 5.65], "dark_red"),
        cube("eject_slider", [7.2, 5.52, 3.95], [8.8, 6.35, 5.4], "red"),
        cube("reset_label_plate", [10.25, 5.2, 3.15], [12.65, 5.28, 5.65], "cream", {"up": "reset_label"}),
        cube("reset_switch_recess", [10.65, 5.28, 3.75], [12.25, 5.48, 5.2], "dark_red"),
        cube("reset_switch", [10.95, 5.48, 4.0], [11.95, 5.95, 4.95], "red"),
        cube("power_led", [3.45, 5.28, 5.78], [3.85, 5.66, 6.18], "led"),
    ])

    # A visibly recessed cartridge bay with a blue contact row. The red hatch
    # sits behind it and the cream side blocks make the opening read as depth.
    elements.extend([
        cube("cartridge_bay_floor", [4.15, 5.2, 7.0], [11.85, 5.46, 11.65], "black"),
        cube("cartridge_slot", [4.75, 5.46, 8.0], [11.25, 5.72, 10.95], "gray"),
        cube("cartridge_bay_left_wall", [3.55, 5.2, 6.75], [4.25, 6.2, 11.9], "cream_shadow"),
        cube("cartridge_bay_right_wall", [11.75, 5.2, 6.75], [12.45, 6.2, 11.9], "cream_shadow"),
        cube("cartridge_bay_front_wall", [4.15, 5.2, 6.55], [11.85, 5.85, 7.15], "cream"),
        cube("red_cartridge_hatch", [4.1, 5.55, 10.85], [11.9, 6.75, 13.2], "red"),
        cube("cartridge_hatch_front_lip", [3.95, 5.45, 10.65], [12.05, 5.95, 11.2], "dark_red"),
    ])

    # Contact fingers are deliberately individual cuboids so the bay still
    # reads correctly when the game is using a low-resolution texture pack.
    for index in range(14):
        x0 = 4.95 + index * 0.45
        elements.append(cube(
            f"cartridge_contact_{index + 1:02d}",
            [round(x0, 2), 5.72, 9.75],
            [round(x0 + 0.22, 2), 5.92, 10.35],
            "contact",
        ))

    # Raised rear cooling fins, one of the most visible cues in the reference.
    elements.extend([
        cube("rear_vent_base", [3.3, 5.2, 13.15], [12.7, 5.72, 14.45], "cream_shadow"),
        cube("rear_left_cap", [3.0, 5.2, 12.9], [4.05, 6.95, 14.55], "cream"),
        cube("rear_right_cap", [11.95, 5.2, 12.9], [13.0, 6.95, 14.55], "cream"),
    ])
    for index in range(13):
        x0 = 4.15 + index * 0.6
        elements.append(cube(
            f"rear_cooling_fin_{index + 1:02d}",
            [round(x0, 2), 5.72, 13.25],
            [round(x0 + 0.25, 2), 6.72, 14.55],
            "cream_shadow",
        ))

    # Controller I is stored upright in the recessed slot on the left side,
    # matching the original console instead of lying loose beside the chassis.
    elements.extend([
        cube("controller_1_dock_back", [1.45, 0.95, 3.2], [2.85, 4.75, 13.8], "dark_red"),
        cube("controller_1_shell", [1.18, 1.15, 3.55], [2.45, 4.45, 13.45], "dark_red"),
        cube("controller_1_face", [1.03, 1.35, 3.8], [1.2, 4.25, 13.2], "gold", {"west": "controller_1"}),
        cube("controller_1_dock_top_rail", [0.95, 4.35, 3.25], [2.7, 4.8, 13.75], "dark_red"),
        cube("controller_1_dock_bottom_rail", [0.95, 0.9, 3.25], [2.7, 1.4, 13.75], "dark_red"),
        cube("controller_1_dock_front_stop", [0.9, 0.9, 3.15], [2.75, 4.8, 3.7], "red"),
        cube("controller_1_dock_rear_stop", [0.9, 0.9, 13.3], [2.75, 4.8, 13.85], "red"),
        cube("controller_1_dpad_vertical", [0.69, 1.9, 4.43], [1.05, 3.65, 5.22], "black"),
        cube("controller_1_dpad_horizontal", [0.69, 2.38, 3.92], [1.05, 3.17, 5.73], "black"),
        cube("controller_1_dpad_center", [0.57, 2.38, 4.43], [0.7, 3.17, 5.22], "gray"),
        # The handheld pad is landscape in use and rotated as a whole when it
        # is inserted into the console side slot. Consequently SELECT and
        # START sit beside each other across the stored pad, not in the same
        # long column as D-pad/B/A.
        cube("controller_1_select_recess", [0.72, 1.52, 6.94], [1.05, 2.46, 7.9], "black"),
        cube("controller_1_select", [0.58, 1.7, 7.1], [0.73, 2.28, 7.74], "dark_red"),
        cube("controller_1_start_recess", [0.72, 3.14, 6.94], [1.05, 4.08, 7.9], "black"),
        cube("controller_1_start", [0.58, 3.32, 7.1], [0.73, 3.9, 7.74], "dark_red"),
        # B/A form one group near the far end of the landscape controller.
        # Keep the pair together while leaving the large authentic gap from
        # the directional pad at the opposite end.
        cube("controller_1_b_bezel", [0.72, 2.2, 9.95], [1.05, 3.25, 10.93], "dark_red"),
        cube("controller_1_b", [0.54, 2.35, 10.11], [0.73, 3.1, 10.77], "black"),
        cube("controller_1_a_bezel", [0.72, 2.2, 11.25], [1.05, 3.25, 12.23], "dark_red"),
        cube("controller_1_a", [0.54, 2.35, 11.41], [0.73, 3.1, 12.07], "black"),
        cube("controller_1_cable_neck", [1.3, 4.1, 13.45], [2.0, 4.65, 14.25], "wire"),
        cube("controller_1_cable_plug", [1.9, 4.1, 13.75], [3.45, 4.65, 14.25], "wire"),
    ])

    # Controller II mirrors the right-side storage slot and retains the
    # microphone/volume details that distinguish it from controller I.
    elements.extend([
        cube("controller_2_dock_back", [13.15, 0.95, 3.2], [14.55, 4.75, 13.8], "dark_red"),
        cube("controller_2_shell", [13.55, 1.15, 3.55], [14.82, 4.45, 13.45], "dark_red"),
        cube("controller_2_face", [14.8, 1.35, 3.8], [14.97, 4.25, 13.2], "gold", {"east": "controller_2"}),
        cube("controller_2_dock_top_rail", [13.3, 4.35, 3.25], [15.05, 4.8, 13.75], "dark_red"),
        cube("controller_2_dock_bottom_rail", [13.3, 0.9, 3.25], [15.05, 1.4, 13.75], "dark_red"),
        cube("controller_2_dock_front_stop", [13.25, 0.9, 3.15], [15.1, 4.8, 3.7], "red"),
        cube("controller_2_dock_rear_stop", [13.25, 0.9, 13.3], [15.1, 4.8, 13.85], "red"),
        cube("controller_2_dpad_vertical", [14.95, 1.9, 4.43], [15.31, 3.65, 5.22], "black"),
        cube("controller_2_dpad_horizontal", [14.95, 2.38, 3.92], [15.31, 3.17, 5.73], "black"),
        cube("controller_2_dpad_center", [15.3, 2.38, 4.43], [15.43, 3.17, 5.22], "gray"),
        cube("controller_2_volume_track", [14.95, 3.17, 6.02], [15.2, 3.68, 7.24], "gray"),
        cube("controller_2_volume_knob", [15.18, 3.08, 6.27], [15.4, 3.77, 6.88], "black"),
        cube("controller_2_microphone_panel", [14.95, 1.8, 7.73], [15.18, 3.82, 9.14], "dark_red"),
        cube("controller_2_b_bezel", [14.95, 2.2, 9.95], [15.28, 3.25, 10.93], "dark_red"),
        cube("controller_2_b", [15.27, 2.35, 10.11], [15.46, 3.1, 10.77], "black"),
        cube("controller_2_a_bezel", [14.95, 2.2, 11.25], [15.28, 3.25, 12.23], "dark_red"),
        cube("controller_2_a", [15.27, 2.35, 11.41], [15.46, 3.1, 12.07], "black"),
        cube("controller_2_cable_neck", [14.0, 4.1, 13.45], [14.7, 4.65, 14.25], "wire"),
        cube("controller_2_cable_plug", [12.55, 4.1, 13.75], [14.1, 4.65, 14.25], "wire"),
    ])

    # The original II controller replaces SELECT/START with a microphone.
    # A 3x4 perforation array makes that hardware difference readable even on
    # Minecraft's deliberately blocky model.
    for row in range(4):
        for column in range(3):
            y0 = 2.05 + column * 0.58
            z0 = 7.94 + row * 0.31
            elements.append(cube(
                f"controller_2_microphone_hole_{row + 1}_{column + 1}",
                [15.17, round(y0, 2), round(z0, 2)],
                [15.38, round(y0 + 0.24, 2), round(z0 + 0.2, 2)],
                "black",
            ))

    return elements


def main() -> None:
    model = {
        "ambientocclusion": False,
        "gui_light": "front",
        "textures": TEXTURES,
        "elements": build_elements(),
        "display": {
            "thirdperson_righthand": {"rotation": [75, 45, 0], "translation": [0, 2.5, 0], "scale": [0.32, 0.32, 0.32]},
            "thirdperson_lefthand": {"rotation": [75, 225, 0], "translation": [0, 2.5, 0], "scale": [0.32, 0.32, 0.32]},
            "firstperson_righthand": {"rotation": [20, 45, 0], "translation": [0, 2.5, 0], "scale": [0.32, 0.32, 0.32]},
            "firstperson_lefthand": {"rotation": [20, 225, 0], "translation": [0, 2.5, 0], "scale": [0.32, 0.32, 0.32]},
            "gui": {"rotation": [35, 225, 0], "translation": [0, 1.0, 0], "scale": [0.62, 0.62, 0.62]},
            "ground": {"translation": [0, 2, 0], "scale": [0.38, 0.38, 0.38]},
            "fixed": {"rotation": [0, 180, 0], "scale": [0.58, 0.58, 0.58]},
        },
    }
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT.write_text(json.dumps(model, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"generated {OUTPUT} with {len(model['elements'])} elements")


if __name__ == "__main__":
    main()
