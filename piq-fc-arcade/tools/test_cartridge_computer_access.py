"""Offline authorization wiring checks; live claims/malicious network tests are still required."""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "src/main/java/cn/piq/fcarcade"


def source(name):
    return (JAVA / name).read_text(encoding="utf-8")


def method(text, signature):
    start = text.index(signature)
    brace = text.index("{", start)
    depth, end = 1, brace + 1
    while depth:
        depth += (text[end] == "{") - (text[end] == "}")
        end += 1
    return text[start:end]


class CartridgeComputerAccessTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.service = source("server/ServerCartridgeService.java")

    def test_old_air_editor_entry_is_gone_and_shift_on_computer_uses_same_authority(self):
        item = source("home/FcCartridgeItem.java")
        self.assertNotIn("ServerCartridgeService.open(", item)
        self.assertNotIn("public static void open(", self.service)
        air = method(item, "public InteractionResultHolder<ItemStack> use(")
        self.assertIn('HomeFeedback.show(serverPlayer, "computer_required")', air)
        self.assertNotIn("openAt", air)
        use_on = method(item, "public InteractionResult useOn(")
        self.assertLess(use_on.index("instanceof CartridgeComputerBlock"), use_on.index("player.isShiftKeyDown()"))
        self.assertIn("ServerCartridgeService.openAt(serverPlayer, context.getHand(), context.getClickedPos())", use_on)

    def test_computer_is_an_identity_only_block_not_a_tv_or_item_container(self):
        block = source("home/CartridgeComputerBlock.java")
        entity = source("home/CartridgeComputerBlockEntity.java")
        self.assertIn("extends HorizontalDirectionalBlock implements EntityBlock", block)
        self.assertIn("CartridgeComputerLayout.parts(turns)", block)
        self.assertIn("RenderShape.MODEL", block)
        self.assertIn('HomeFeedback.show(serverPlayer, "computer_insert_card")', block)
        self.assertIn("extends BlockEntity", entity)
        self.assertIn('tag.putUUID("ComputerId", computerId)', entity)
        for forbidden in ("HomeEndpointBlockEntity", "HomeTvBlockEntity", "ItemStack", "Container", "attach("):
            self.assertNotIn(forbidden, entity)
        self.assertIn("public void setRemoved()", entity)
        self.assertIn("public void onChunkUnloaded()", entity)
        self.assertIn("ServerCartridgeService.computerRemoved(serverLevel.getServer(), this)", entity)

    def test_open_checks_metadata_before_identity_and_captures_computer_and_card(self):
        opened = method(self.service, "public static void openAt(")
        self.assertLess(opened.index("FcCartridgeData.supportsAssembly(stack)"), opened.index("FcCartridgeData.ensureIdentity(stack)"))
        self.assertIn("computerPermitted(player, hand, station, computer)", opened)
        self.assertIn("uniqueCard(player, stack, cartridge)", opened)
        self.assertIn("stack, station, computer, tick)", opened)
        self.assertLess(opened.index("state.valid(player, session, session.target)"), opened.index("CartridgeNetwork.OPEN"))

    def test_loaded_lookup_never_force_loads_a_chunk(self):
        loaded = method(self.service, "private static CartridgeComputerBlockEntity loadedComputer(")
        self.assertLess(loaded.index("hasChunkAt(pos)"), loaded.index("getBlockEntity(pos)"))
        self.assertIn("player.getServer().isSameThread()", loaded)
        self.assertNotIn("getChunk(", loaded)
        facts = method(self.service, "private static boolean computerFacts(")
        self.assertIn("actual == expected && expected.getLevel() == player.serverLevel()", facts)
        self.assertIn("player.serverLevel().dimension().location().toString()", facts)
        self.assertIn("player.serverLevel().mayInteract(player, pos)", facts)
        self.assertIn("!player.hasDisconnected()", facts)

    def test_protection_event_vetoes_reentrancy_and_rechecks_facts_after_callback(self):
        access = method(self.service, "private static boolean computerPermitted(")
        self.assertIn("CHECKING_COMPUTER.get()", access)
        self.assertIn("new PlayerInteractEvent.RightClickBlock(player, hand, pos, hit)", access)
        self.assertIn("!event.isCanceled()", access)
        self.assertIn("event.getUseBlock() != TriState.FALSE", access)
        self.assertIn("event.getUseItem() != TriState.FALSE", access)
        self.assertEqual(access.count("computerFacts(player, binding, expected)"), 2)
        self.assertIn("catch (RuntimeException error)", access)
        self.assertIn("finally { CHECKING_COMPUTER.remove(); }", access)

    def test_each_valid_call_checks_original_stack_snapshot_before_and_after_event(self):
        valid = method(self.service, "boolean valid(")
        self.assertEqual(valid.count("cardMatches(player, session, claimed)"), 2)
        self.assertIn("computerPermitted(player, hand, session.station, session.computer)", valid)
        held = method(self.service, "boolean cardMatches(")
        for clause in ("FcCartridgeData.supportsAssembly(held)", "held == session.stack",
                       "ItemStack.isSameItemSameComponents(held, session.expectedContents)",
                       "uniqueCard(player, held, session.target.cartridgeId())", "player.containerMenu == player.inventoryMenu"):
            self.assertIn(clause, held)
        self.assertIn("this.expectedContents = stack.copy()", self.service)
        commit = method(self.service, "void commit(")
        self.assertLess(commit.index("valid(player, session, session.target)"), commit.index("FcCartridgeData.write("))
        self.assertLess(commit.index("library.find(rom)"), commit.index("FcCartridgeData.write("))
        self.assertLess(commit.index("FcCartridgeData.write("), commit.index("session.expectedContents = session.stack.copy()"))

    def test_async_writes_tick_and_removal_all_recheck_or_cancel_current_lease(self):
        for signature in ("void write(", "void finish("):
            flow = method(self.service, signature)
            self.assertIn("server.execute(() ->", flow)
            self.assertIn("!valid(current, session, request.target())", flow)
            self.assertIn("commit(current, session,", flow)
        tick = method(self.service, "void tick()")
        self.assertIn("!valid(player, session, session.target)", tick)
        removed = method(self.service, "public static void computerRemoved(")
        self.assertIn("session.computer == computer", removed)
        self.assertIn("state.cancel(session.playerId,", removed)
        cancel = method(self.service, "void cancel(UUID playerId, String message)")
        self.assertIn("sessions.remove(playerId)", cancel)
        self.assertIn("session.cancelled = true", cancel)

    def test_set_players_is_idle_current_rom_only_and_refreshes_the_shared_catalog(self):
        changed = method(self.service, "void setPlayers(")
        self.assertIn("session.upload != null || session.processing", changed)
        self.assertNotIn("abortUpload", changed)
        self.assertIn("request.hash().equals(FcCartridgeData.romSha(session.stack))", changed)
        self.assertIn("CartridgeComputerBinding.permitsPlayersSetting(request.total(), library.find(request.hash()) != null, false)", changed)
        self.assertLess(changed.index("valid(player, session, request.target())"), changed.index("library.setMaxPlayers("))
        self.assertIn("CartridgeNetwork.STATUS", changed)
        self.assertNotIn("CartridgeNetwork.OPEN", changed)
        self.assertIn("library.homeCatalog()", changed)
        self.assertIn("同一 ROM", changed)
        self.assertIn("重开生效", changed)
        commit = method(self.service, "void commit(")
        self.assertIn('CartridgeNetwork.STATUS, "", message, library.homeCatalog()', commit)

    def test_metadata_operations_reuse_request_shape_and_protocol_32(self):
        network = source("home/CartridgeNetwork.java")
        self.assertIn("SET_PLAYERS = 8", network)
        self.assertIn("SET_SAVE_MODE = 9", network)
        self.assertIn("operation > SET_SAVE_MODE", network)
        self.assertIn('TrafficPayloadRegistrar.create(event,"32")', network)
        self.assertNotIn("writeBlockPos", network)
        self.assertIn("case CartridgeNetwork.SET_PLAYERS -> state.setPlayers(player, session, request)", self.service)


if __name__ == "__main__":
    unittest.main()
