"""Offline source/locale contracts for brief physical hardware feedback (not a live MC test)."""
import json
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "src/main/java/cn/piq/fcarcade"
PREFIX = "message.piq_fc_arcade."


def source(name):
    return (JAVA / name).read_text(encoding="utf-8")


def method(text, signature):
    start = text.index(signature)
    brace = text.index("{", start)
    depth = 1
    end = brace + 1
    while depth:
        depth += (text[end] == "{") - (text[end] == "}")
        end += 1
    return text[start:end]


class HomeFeedbackTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.hardware = source("home/HomeHardware.java")
        cls.controllers = source("home/HomeControllerService.java")
        cls.sessions = source("server/ServerArcadeSessions.java")
        cls.languages = {}
        for lang in ("zh_cn", "en_us"):
            path = ROOT / f"src/main/resources/assets/piq_fc_arcade/lang/{lang}.json"
            # Reject duplicate translation keys, which ordinary json.loads silently hides.
            pairs = json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=list)
            if len(pairs) != len(dict(pairs)):
                raise AssertionError(f"Duplicate translation key in {lang}")
            cls.languages[lang] = dict(pairs)

    def test_single_transport_uses_vanilla_action_bar_with_arguments(self):
        feedback = source("home/HomeFeedback.java")
        self.assertIn('player.displayClientMessage(Component.translatable("message.piq_fc_arcade." + key, arguments), true);', feedback)
        self.assertNotIn("sendSystemMessage", feedback)
        for custom in ("PacketDistributor", "FcNetwork", "tick(", "Timer", "Minecraft.getInstance"):
            self.assertNotIn(custom, feedback)

    def test_all_physical_interactions_use_shared_transport_not_chat(self):
        self.assertNotIn("sendSystemMessage", self.hardware)
        self.assertNotIn("sendSystemMessage", self.controllers)
        self.assertIn('HomeFeedback.show(player, "home_" + key);', self.hardware)
        self.assertIn('HomeFeedback.show(player, "controller_" + key, arguments);', self.controllers)
        self.assertIn('message(player, "taken", port + 1);', self.controllers)

    def test_every_routed_key_exists_in_both_languages_with_same_placeholders(self):
        keys = {"home_" + key for key in re.findall(r'message\(player, "([a-z_]+)"', self.hardware)}
        keys |= {"controller_" + key for key in re.findall(r'message\(player, "([a-z0-9_]+)"', self.controllers)}
        keys |= set(re.findall(r'HomeFeedback.show\(player, "([a-z_]+)"', self.sessions))
        keys |= {"controller_" + key for key in ("p1_returned", "p1_stopped", "p2_returned", "p2_stopped")}
        self.assertGreaterEqual(len(keys), 35)
        for key in keys:
            texts = [self.languages[lang][PREFIX + key] for lang in self.languages]
            self.assertTrue(all(texts), key)
            self.assertEqual(re.findall(r"%(?:\d+\$)?[a-z]", texts[0]),
                             re.findall(r"%(?:\d+\$)?[a-z]", texts[1]), key)
        for lang in self.languages:
            self.assertEqual(self.languages[lang][PREFIX + "controller_taken"].count("%s"), 1)

    def test_normal_results_are_short_and_ejection_risk_is_not_lost(self):
        keys = ("home_card_inserted", "home_card_inserted_unlinked", "home_card_ejected",
                "home_wire_selected", "home_wire_connected", "home_wire_disconnected",
                "home_wire_selection_cleared", "controller_taken", "controller_p1_returned",
                "controller_p2_returned", "controller_p1_stopped", "controller_p2_stopped",
                "controller_p2_transfer", "controller_p2_approval")
        for lang, limit in (("zh_cn", 40), ("en_us", 90)):
            for key in keys:
                value = self.languages[lang][PREFIX + key]
                self.assertLessEqual(len(value), limit, (lang, key))
                self.assertNotIn("\n", value)
        for key in ("home_card_ejected", "home_wire_disconnected"):
            chinese = self.languages["zh_cn"][PREFIX + key]
            english = self.languages["en_us"][PREFIX + key]
            self.assertIn("可能丢最后片刻", chinese)
            self.assertIn("建议先保存退出", chinese)
            self.assertIn("may be lost", english)
            self.assertIn("save and exit first", english)
            self.assertNotIn("已保存", chinese)
            self.assertNotIn("saved", english.lower())

    def test_manual_return_emits_one_result_per_port_not_a_second_generic_line(self):
        use_on = method(self.controllers, "public static InteractionResult useOn(")
        self.assertIn("release(player.getServer(), state, lease, true);", use_on)
        self.assertNotIn('message(player, "returned")', use_on)
        release = method(self.controllers, "private static void release(MinecraftServer server, State state, HomeControllerLedger.Lease lease, boolean explicitReturn)")
        self.assertIn('message(player, explicitReturn ? "p1_returned" : "p1_stopped")', release)
        self.assertIn('message(player, explicitReturn ? "p2_returned" : "p2_stopped")', release)
        self.assertEqual(release.count("message(player,"), 2)  # Mutually exclusive port branches.
        self.assertLess(release.index("closeSession("), release.index('"p1_returned"'))
        self.assertLess(release.index("revoke("), release.index('"p2_returned"'))

    def test_physical_p2_departure_does_not_emit_coordinate_chat(self):
        release = method(self.sessions, "public static void releaseHomeController(")
        self.assertIn("session != null && session.homeConsole", release)
        self.assertIn("manager.leave(player, false)", release)
        self.assertNotIn("manager.leave(player, true)", release)
        # Common arcade exits and serious errors retain their prior chat transport.
        self.assertIn('"message.piq_fc_arcade.machine_left"', self.sessions)
        self.assertIn('player.sendSystemMessage(Component.translatable("message.piq_fc_arcade.storage_unavailable"));', self.sessions)

    def test_only_home_specific_server_guidance_is_rerouted(self):
        expected = {"home_playback_not_ready", "home_edit_cartridge", "home_swap_cartridge"}
        self.assertEqual(set(re.findall(r'HomeFeedback.show\(player, "([a-z_]+)"', self.sessions)), expected)
        for old in ("请先连接电视与主机", "电视的游戏由卡带决定", "请更换实体卡带来更换电视上的游戏"):
            self.assertNotIn(old, self.sessions)

    def test_ejection_and_unplug_warnings_follow_stop_cleanup(self):
        interact = method(self.hardware, "public static InteractionResult interactConsole(ServerPlayer player, BlockPos pos, BlockHitResult hit)")
        self.assertLess(interact.index("stopEndpoint(console)"), interact.index('message(player, "card_ejected")'))
        cable = method(self.hardware, "public static InteractionResult useCable(ServerPlayer player, BlockPos pos, InteractionHand hand, BlockHitResult hit)")
        self.assertLess(cable.index("disconnect("), cable.index('message(player, "wire_disconnected")'))


if __name__ == "__main__":
    unittest.main()
