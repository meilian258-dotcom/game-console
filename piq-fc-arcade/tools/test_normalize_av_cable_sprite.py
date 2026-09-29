"""Run with: python -m unittest discover -s tools -p test_normalize_av_cable_sprite.py -v"""
import io
import json
from pathlib import Path
import tempfile
import unittest

from PIL import Image

import normalize_av_cable_sprite as sprite_tool


class NormalizeAvCableSpriteTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.native, cls.preview, cls.report = sprite_tool.build_artifacts()

    def test_fixed_approved_source_hash_and_original_bytes_preserved(self):
        before = sprite_tool.SOURCE.read_bytes()
        self.assertEqual(sprite_tool.SOURCE_SHA256, sprite_tool.sha256(before))
        sprite_tool.build_artifacts()
        self.assertEqual(before, sprite_tool.SOURCE.read_bytes())

    def test_changed_source_is_rejected_before_decode_or_output(self):
        with tempfile.TemporaryDirectory() as folder:
            source = Path(folder) / "different.png"
            source.write_bytes(b"not the approved image")
            with self.assertRaisesRegex(ValueError, "Source SHA256"):
                sprite_tool.run(source, Path(folder) / "out.png", Path(folder) / "artifacts")
            self.assertFalse((Path(folder) / "out.png").exists())
            self.assertFalse((Path(folder) / "artifacts").exists())

    def test_native_dimensions_binary_alpha_and_clean_transparent_rgb(self):
        with Image.open(io.BytesIO(self.native)) as image:
            self.assertEqual((64, 64), image.size)
            self.assertEqual("RGBA", image.mode)
            self.assertEqual({0, 255}, set(image.getchannel("A").getdata()))
            self.assertTrue(all((r, g, b) == (0, 0, 0) for r, g, b, a in image.getdata() if a == 0))

    def test_all_four_edges_have_transparent_margin(self):
        with Image.open(io.BytesIO(self.native)) as image:
            alpha = image.getchannel("A")
            for offset in range(64):
                for pixel in [(offset, 0), (offset, 63), (0, offset), (63, offset)]:
                    self.assertEqual(0, alpha.getpixel(pixel))
            self.assertGreaterEqual(min(sprite_tool.inspect_sprite(image)["margins_left_top_right_bottom"]), 1)

    def test_visible_colors_are_exact_nearest_samples_without_recolor_or_crop(self):
        with Image.open(sprite_tool.SOURCE) as source:
            nearest = source.resize((64, 64), Image.Resampling.NEAREST)
            with Image.open(io.BytesIO(self.native)) as normalized:
                for original, actual in zip(nearest.getdata(), normalized.getdata()):
                    expected = (*original[:3], 255) if original[3] >= 128 else (0, 0, 0, 0)
                    self.assertEqual(expected, actual)

    def test_alpha_threshold_includes_128_without_changing_visible_rgb(self):
        source = Image.new("RGBA", (64, 64), (9, 19, 29, 0))
        for x, alpha in enumerate([0, 1, 127, 128, 254, 255]):
            source.putpixel((x, 4), (20 + x, 40, 60, alpha))
        normalized = sprite_tool.normalize_pixels(source)
        for x in range(3):
            self.assertEqual((0, 0, 0, 0), normalized.getpixel((x, 4)))
        for x in range(3, 6):
            self.assertEqual((20 + x, 40, 60, 255), normalized.getpixel((x, 4)))

    def test_preview_is_exact_eightfold_nearest_and_outputs_are_deterministic(self):
        with Image.open(io.BytesIO(self.native)) as native, Image.open(io.BytesIO(self.preview)) as preview:
            self.assertEqual((512, 512), preview.size)
            self.assertEqual(native.resize((512, 512), Image.Resampling.NEAREST).tobytes(), preview.tobytes())
        self.assertEqual((self.native, self.preview, self.report), sprite_tool.build_artifacts())

    def test_different_existing_output_is_not_overwritten_and_preflight_writes_nothing(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            existing, fresh = root / "existing.png", root / "fresh.png"
            existing.write_bytes(b"preserve existing asset")
            with self.assertRaises(FileExistsError):
                sprite_tool.publish_files({fresh: self.native, existing: self.native}, sprite_tool.SOURCE)
            self.assertEqual(b"preserve existing asset", existing.read_bytes())
            self.assertFalse(fresh.exists())

    def test_identical_rerun_is_safe_and_source_cannot_be_an_output(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            source = root / "original.png"
            source.write_bytes(b"preserve original")
            target = root / "item.png"
            sprite_tool.publish_files({target: self.native}, source)
            previous_time = target.stat().st_mtime_ns
            sprite_tool.publish_files({target: self.native}, source)
            self.assertEqual(previous_time, target.stat().st_mtime_ns)
            self.assertEqual(self.native, target.read_bytes())
            with self.assertRaisesRegex(ValueError, "original source"):
                sprite_tool.publish_files({source: self.native}, source)
            self.assertEqual(b"preserve original", source.read_bytes())

    def test_item_model_uses_only_the_local_sprite_and_keeps_generated_parent(self):
        model = sprite_tool.PROJECT / "src/main/resources/assets/piq_fc_arcade/models/item/av_cable.json"
        self.assertEqual({"parent": "minecraft:item/generated",
                          "textures": {"layer0": "piq_fc_arcade:item/av_cable"}},
                         json.loads(model.read_text(encoding="utf-8")))


if __name__ == "__main__":
    unittest.main()
