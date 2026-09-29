import io
import json
import unittest
import numpy as np
from PIL import Image
from normalize_cartridge_label import (ROOT, DESIGN, TARGET, ORIGINAL_SHA, GENERATED_SHA,
    normalize, compose, outside_equal, original_bytes, sha)


class CartridgeLabelTests(unittest.TestCase):
    def test_normalization_is_full_canvas_nearest_with_opaque_alpha(self):
        source = Image.open(DESIGN / "generic-cartridge-label-generated.png")
        self.assertEqual(GENERATED_SHA, sha((DESIGN / "generic-cartridge-label-generated.png").read_bytes()))
        result = normalize(source)
        self.assertEqual((512, 256), result.size)
        self.assertEqual(result.tobytes(), source.convert("RGBA").resize((512, 256), Image.Resampling.NEAREST).tobytes())
        self.assertEqual((255, 255), result.getchannel("A").getextrema())

    def test_outside_label_preserves_every_original_rgba_pixel(self):
        old = original_bytes()
        self.assertEqual(ORIGINAL_SHA, sha(old))
        base = Image.open(io.BytesIO(old))
        target = Image.open(TARGET)
        self.assertTrue(outside_equal(base, target))
        label = Image.open(DESIGN / "generic-cartridge-label-512x256.png")
        self.assertEqual(compose(base, label).tobytes(), target.convert("RGBA").tobytes())

    def test_edge_padding_and_corners_are_exact_clamped_texels(self):
        label = np.asarray(Image.open(DESIGN / "generic-cartridge-label-512x256.png").convert("RGBA"))
        skin = np.asarray(Image.open(TARGET).convert("RGBA"))
        self.assertTrue(np.array_equal(label, skin[32:288, 32:544]))
        self.assertTrue(np.array_equal(np.pad(label, ((4, 4), (4, 4), (0, 0)), mode="edge"), skin[28:292, 28:548]))

    def test_other_uv_islands_never_sample_the_changed_region(self):
        model = json.loads((ROOT / "src/main/resources/assets/piq_fc_arcade/models/block/home_fc_cartridge.json").read_bytes())
        count = 0
        for element in model["elements"]:
            for direction, face in element["faces"].items():
                x1, y1, x2, y2 = (v * 64 for v in face["uv"])
                if element["name"] == "中央游戏标签":
                    self.assertEqual("north", direction)
                    self.assertEqual((32, 32, 544, 288), (x1, y1, x2, y2))
                    count += 1
                    continue
                left, right = sorted((x1, x2)); top, bottom = sorted((y1, y2))
                self.assertTrue(right <= 28 or left >= 548 or bottom <= 28 or top >= 292,
                                (element["name"], direction, face["uv"]))
        self.assertEqual(1, count)

    def test_existing_custom_cover_composition_is_identical_on_old_and_new_base(self):
        old = Image.open(io.BytesIO(original_bytes()))
        new = Image.open(TARGET)
        pixels = np.zeros((256, 512, 4), dtype=np.uint8)
        pixels[:, :, 0] = np.arange(512, dtype=np.uint16)[None, :] % 256
        pixels[:, :, 1] = np.arange(256, dtype=np.uint8)[:, None]
        pixels[:, :, 2] = 143
        pixels[:, :, 3] = 255
        custom = Image.fromarray(pixels)
        self.assertEqual(compose(old, custom).tobytes(), compose(new, custom).tobytes())

    def test_transparent_rgb_is_preserved_and_wrong_dimensions_fail(self):
        base = Image.new("RGBA", (1024, 1024), (42, 72, 96, 0))
        label = Image.new("RGBA", (512, 256), (10, 20, 30, 255))
        self.assertEqual((42, 72, 96, 0), compose(base, label).getpixel((0, 0)))
        with self.assertRaises(ValueError): normalize(Image.new("RGBA", (50, 50)))
        with self.assertRaises(ValueError): normalize(Image.new("RGBA", (64, 32), (1, 2, 3, 0)))
        with self.assertRaises(ValueError): compose(Image.new("RGBA", (64, 64)), label)


if __name__ == "__main__": unittest.main()
