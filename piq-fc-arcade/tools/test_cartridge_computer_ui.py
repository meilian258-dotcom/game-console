"""Source wiring complements the compiled pure layout/lease tests, not a live GUI test."""
import json
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


class CartridgeComputerUiTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.editor = (ROOT / "src/main/java/cn/piq/fcarcade/client/ClientCartridgeEditor.java").read_text(encoding="utf-8")

    def test_players_applies_to_written_rom_and_sends_one_or_two_without_file_payload(self):
        self.assertIn("game.sha256().equals(romSha)", self.editor)
        self.assertIn("!busy && !scanning && game != null", self.editor)
        self.assertIn("CartridgeNetwork.SET_PLAYERS, target, game.sha256()", self.editor)
        self.assertIn('game.maxPlayers() == 2 ? 1 : 2, 0, new byte[0]', self.editor)
        self.assertIn("未设置时家用机默认双人", self.editor)
        self.assertIn("明确设置仍由同一 ROM 的卡带与街机共用", self.editor)
        self.assertIn("已运行游戏需结束重开", self.editor)

    def test_refresh_keeps_upload_but_status_refreshes_catalog_and_unlocks(self):
        refresh = self.editor.split("editor.serverRoms = reply.catalog()", 1)[1].split("ItemStack held =", 1)[0]
        self.assertNotIn("busy = false", refresh)
        self.assertNotIn("upload = null", refresh)
        receive = self.editor.split("@Override protected void init()", 1)[0]
        self.assertIn("if (!reply.catalog().isEmpty())", receive)
        self.assertIn("editor.serverRoms = reply.catalog(); editor.mergeEntries();", receive)
        self.assertIn("editor.busy = false; editor.upload = null; editor.pendingPermission = 0; editor.rebuildWidgets();", receive)

    def test_existing_folders_and_small_layout_stay_available(self):
        self.assertIn("DeviceLayout.browser(width, height, 2)", self.editor)
        self.assertIn("ClientFcDirectories::openRomDirectory", self.editor)
        self.assertIn("ClientFcDirectories::openCoverDirectory", self.editor)
        self.assertIn("离开电脑、拆机或换槽会取消", self.editor)
        self.assertIn("if (!menu.supported())", self.editor)

    def test_new_computer_guidance_is_present_in_both_languages(self):
        keys = ["block.piq_fc_arcade.cartridge_computer", "item.piq_fc_arcade.fc_cartridge.computer_hint"]
        keys += ["message.piq_fc_arcade." + key for key in
                 ("computer_insert_card", "computer_required", "computer_denied", "computer_invalid_card", "computer_busy")]
        for language in ("zh_cn", "en_us"):
            data = json.loads((ROOT / f"src/main/resources/assets/piq_fc_arcade/lang/{language}.json").read_text(encoding="utf-8-sig"))
            for key in keys:
                with self.subTest(language=language, key=key):
                    self.assertTrue(data[key])


if __name__ == "__main__":
    unittest.main()
