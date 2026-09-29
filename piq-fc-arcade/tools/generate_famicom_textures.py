"""Generate small texture plates for the detailed console model."""

from __future__ import annotations

from pathlib import Path

from PIL import Image, ImageDraw, ImageFont


ROOT = Path(__file__).parents[1]
OUTPUT = ROOT / "src/main/resources/assets/piq_fc_arcade/textures/block"

CREAM = (238, 222, 174, 255)
RED = (154, 24, 36, 255)
DARK_RED = (91, 18, 27, 255)
BLACK = (25, 23, 22, 255)
GOLD = (195, 150, 60, 255)


def load_font(size: int, bold: bool = False) -> ImageFont.FreeTypeFont | ImageFont.ImageFont:
    candidates = [
        Path("C:/Windows/Fonts/arialbd.ttf" if bold else "C:/Windows/Fonts/arial.ttf"),
        Path("C:/Windows/Fonts/msyhbd.ttc" if bold else "C:/Windows/Fonts/msyh.ttc"),
    ]
    for candidate in candidates:
        if candidate.exists():
            return ImageFont.truetype(str(candidate), size)
    return ImageFont.load_default()


def centered(draw: ImageDraw.ImageDraw, box: tuple[int, int, int, int], text: str, font, fill) -> None:
    left, top, right, bottom = box
    bounds = draw.textbbox((0, 0), text, font=font)
    width = bounds[2] - bounds[0]
    height = bounds[3] - bounds[1]
    draw.text(((left + right - width) / 2, (top + bottom - height) / 2 - bounds[1]), text, font=font, fill=fill)


def save_brand() -> None:
    image = Image.new("RGBA", (256, 64), RED)
    draw = ImageDraw.Draw(image)
    draw.rectangle((3, 3, 252, 60), outline=CREAM, width=3)
    draw.line((18, 16, 238, 16), fill=CREAM, width=2)
    draw.line((18, 49, 238, 49), fill=CREAM, width=2)
    centered(draw, (12, 9, 244, 56), "FAMILY COMPUTER", load_font(22, True), CREAM)
    image.save(OUTPUT / "famicom_brand.png", optimize=True)


def save_control(name: str, label: str, arrow: bool = True) -> None:
    image = Image.new("RGBA", (96, 96), CREAM)
    draw = ImageDraw.Draw(image)
    draw.rectangle((2, 2, 93, 93), outline=GOLD, width=3)
    centered(draw, (5, 7, 91, 37), label, load_font(18, True), DARK_RED)
    if arrow:
        draw.line((48, 42, 48, 70), fill=DARK_RED, width=5)
        draw.polygon(((32, 64), (64, 64), (48, 83)), fill=DARK_RED)
    image.save(OUTPUT / name, optimize=True)


def save_controller(name: str, number: str, microphone: bool) -> None:
    # Design the controller in its real landscape orientation first, then
    # rotate the complete artwork for the vertical console storage slot.
    # This preserves the actual left/middle/right control layout instead of
    # inventing a narrow remote-control arrangement.
    # The original pad is roughly three times wider than it is tall. Keeping
    # that proportion here makes the generous D-pad-to-button gap visible
    # instead of compressing the controls into a 2:1 remote-like panel.
    landscape = Image.new("RGBA", (256, 80), CREAM)
    draw = ImageDraw.Draw(landscape)
    draw.rectangle((2, 2, 253, 77), outline=GOLD, width=3)
    draw.line((7, 17, 248, 17), fill=BLACK, width=2)
    draw.line((7, 22, 248, 22), fill=GOLD, width=2)
    draw.rectangle((10, 8, 31, 26), outline=BLACK, width=3)
    centered(draw, (10, 7, 31, 27), number, load_font(12, True), BLACK)
    if microphone:
        # Controller II has no SELECT/START. Its distinctive hardware is a
        # volume slider and the built-in microphone grille in the middle.
        centered(draw, (62, 27, 110, 39), "VOLUME", load_font(7, True), DARK_RED)
        draw.rounded_rectangle((72, 40, 109, 49), radius=4, fill=BLACK)
        draw.rounded_rectangle((83, 38, 96, 51), radius=4, fill=DARK_RED, outline=GOLD, width=1)
        centered(draw, (112, 31, 138, 43), "MIC", load_font(8, True), DARK_RED)
        draw.rounded_rectangle((116, 44, 153, 73), radius=4, fill=DARK_RED, outline=GOLD, width=2)
        for y in (50, 57, 64):
            for x in (122, 130, 138, 146):
                draw.ellipse((x, y, x + 3, y + 3), fill=BLACK)
    else:
        centered(draw, (80, 31, 117, 43), "SELECT", load_font(7, True), DARK_RED)
        centered(draw, (80, 56, 117, 69), "START", load_font(7, True), DARK_RED)
    centered(draw, (174, 42, 197, 65), "B", load_font(15, True), BLACK)
    centered(draw, (216, 42, 239, 65), "A", load_font(15, True), BLACK)
    image = landscape.rotate(90, expand=True)
    image.save(OUTPUT / name, optimize=True)


def main() -> None:
    OUTPUT.mkdir(parents=True, exist_ok=True)
    save_brand()
    save_control("famicom_power_label.png", "POWER")
    save_control("famicom_eject_label.png", "EJECT")
    save_control("famicom_reset_label.png", "RESET")
    save_controller("famicom_controller_1.png", "I", False)
    save_controller("famicom_controller_2.png", "II", True)
    print(f"generated six console textures in {OUTPUT}")


if __name__ == "__main__":
    main()
