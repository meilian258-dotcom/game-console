"""Source wiring checks: hiding five entries must not unregister old saved-world items."""
import json
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "src/main/java/cn/piq/fcarcade"
RESOURCES = ROOT / "src/main/resources"
RETIRED = {"fc_arcade", "stream_fc_arcade", "deluxe_fc_arcade", "deluxe_stream_fc_arcade", "leaderboard_panel"}
KEPT = {"legacy_fc_arcade", "famicom_console", "subor_console", "retro_tv", "lcd_tv", "dual_cabinet",
        "fc_cartridge", "fc_cartridge_board", "fc_cartridge_shell", "wide_lcd_tv", "large_lcd_tv", "vintage_tv", "av_cable", "cartridge_computer", "tv_remote"}
OPTIONAL = {"waterframes_fc_arcade", "waterframes_tv_fc_arcade", "waterframes_tv_box_fc_arcade", "waterframes_panel_fc_arcade"}


class CreativeTabRetirementTest(unittest.TestCase):
    def test_functional_blocks_withdraws_only_five_known_entries(self):
        source = (JAVA / "FcArcadeMod.java").read_text(encoding="utf-8")
        accepted = re.findall(r"event\.accept\(ModItems\.([A-Z_]+)\.get\(\)\)", source)
        paths = [name.lower() for name in accepted]
        self.assertEqual(set(paths), KEPT | OPTIONAL)
        self.assertEqual(len(paths), len(set(paths)))
        self.assertTrue(RETIRED.isdisjoint(paths))
        self.assertIn('if (ModList.get().isLoaded("waterframes"))', source)

    def test_all_withdrawn_item_and_block_ids_remain_registered(self):
        for filename, registry in (("ModItems.java", "ITEMS"), ("ModBlocks.java", "BLOCKS")):
            source = (JAVA / "registry" / filename).read_text(encoding="utf-8")
            registered = set(re.findall(rf'{registry}\.register\(\s*"([a-z_]+)"', source))
            self.assertTrue(RETIRED <= registered, (filename, RETIRED - registered))

    def test_render_and_loot_assets_are_not_deleted(self):
        for item in RETIRED:
            for folder in ("models/item", "models/block", "blockstates"):
                path = RESOURCES / "assets/piq_fc_arcade" / folder / f"{item}.json"
                self.assertIsInstance(json.loads(path.read_text(encoding="utf-8")), dict)
            path = RESOURCES / "data/piq_fc_arcade/loot_table/blocks" / f"{item}.json"
            self.assertIsInstance(json.loads(path.read_text(encoding="utf-8")), dict)


if __name__ == "__main__":
    unittest.main()
