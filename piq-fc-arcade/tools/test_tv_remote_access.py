"""Remote authority/sync wiring; not a claim of live multiplayer/claims testing."""
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "src/main/java/cn/piq/fcarcade/home"


def source(name):
    return (JAVA / name).read_text(encoding="utf-8")


def method(text, signature):
    start = text.index(signature)
    end = text.index("{", start) + 1
    depth = 1
    while depth:
        depth += (text[end] == "{") - (text[end] == "}")
        end += 1
    return text[start:end]


class TvRemoteAccessTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.item = source("TvRemoteItem.java")
        cls.service = source("TvRemoteService.java")
        cls.tv = source("HomeTvBlockEntity.java")

    def test_first_hook_block_use_and_air_use_share_one_server_authority(self):
        for signature in ("public InteractionResult onItemUseFirst(", "public InteractionResult useOn(",
                          "public InteractionResultHolder<ItemStack> use("):
            self.assertIn("activate(", method(self.item, signature))
        self.assertIn("TvRemoteService.use(serverPlayer, hand, this)", self.item)
        self.assertNotIn("getClickedPos()", self.item)
        self.assertNotIn("getClickLocation()", self.item)

    def test_hold_latch_and_cooldown_precede_lookup_and_authorization(self):
        use = method(self.service, "public static InteractionResult use(")
        self.assertLess(use.index("canActivate("), use.index("addCooldown("))
        self.assertLess(use.index("addCooldown("), use.index("Target target = target(player)"))
        self.assertIn("player.isUsingItem()", use)
        self.assertIn("player.startUsingItem(hand)", use)
        self.assertIn("return UseAnim.NONE", self.item)
        self.assertIn("return TvRemotePolicy.HOLD_TICKS", self.item)
        self.assertNotIn("shrink(", self.service + self.item)

    def test_loaded_only_view_guards_all_shape_queries_and_unloaded_space_blocks_ray(self):
        for name in ("getBlockState", "getBlockEntity", "getFluidState"):
            body = method(self.service, "public " + {"getBlockState": "BlockState", "getBlockEntity": "BlockEntity",
                                                    "getFluidState": "FluidState"}[name] + " " + name + "(")
            self.assertLess(body.index("hasChunkAt(pos)"), body.index("level." + name + "(pos)"))
        self.assertIn("Blocks.BARRIER.defaultBlockState()", self.service)
        self.assertNotIn("getChunk(", self.service)
        self.assertNotIn("level.clip(", self.service)

    def test_server_eye_first_block_ray_not_client_claim_or_nearest_tv_search(self):
        target = method(self.service, "private static Target target(")
        for clause in ("player.getEyePosition()", "player.getLookAngle()", "TvRemotePolicy.RANGE",
                       "ClipContext.Block.OUTLINE", "ClipContext.Fluid.NONE", "HitResult.Type.BLOCK",
                       "TvRemotePolicy.inRange(eye.distanceToSqr(hit.getLocation()))"):
            self.assertIn(clause, target)
        self.assertNotIn("getEntities(", target)

    def test_shared_tv_anchor_resolution_requires_loaded_complete_current_tv(self):
        target = method(self.service, "private static Target target(")
        self.assertLess(target.index("hasChunkAt(clicked)"), target.index("HomeHardware.loadedEndpoint"))
        for clause in ("HomeHardware.loadedEndpoint(level, clicked) instanceof HomeTvBlockEntity tv",
                       "tv.getLevel() != level", "isWithinBounds(tv.getBlockPos())",
                       "HomeTvStructure.complete(level, tv.getBlockPos())"):
            self.assertIn(clause, target)
        self.assertNotIn("instanceof HomeConsoleBlockEntity", self.service)
        self.assertNotIn("hasPermissions(", self.service)

    def test_both_cell_and_anchor_protection_events_veto_and_recheck_after_callbacks(self):
        allowed = method(self.service, "private static boolean permitted(")
        self.assertIn("new PlayerInteractEvent.RightClickBlock(", allowed)
        self.assertIn("event.isCanceled()", allowed)
        self.assertIn("event.getUseBlock() == TriState.FALSE", allowed)
        self.assertIn("event.getUseItem() == TriState.FALSE", allowed)
        self.assertIn("HomeHardware.allowAnchorInteraction(", allowed)
        self.assertEqual(3, allowed.count("sameTarget(player, hand, held, expected)"))

    def test_post_event_rechecks_los_identity_dimension_live_entity_hand_and_permissions(self):
        same = method(self.service, "private static boolean sameTarget(")
        for clause in ("player.serverLevel() != expected.level()", "stillHolding(player, hand, held)",
                       "Target current = target(player)", "current.tv() == expected.tv()",
                       "current.identity().equals(expected.identity())", "current.clickedEntity() == expected.clickedEntity()",
                       "mayInteract(player, current.hit().getBlockPos())", "mayInteract(player, current.tv().getBlockPos())"):
            self.assertIn(clause, same)
        held = method(self.service, "private static boolean stillHolding(")
        self.assertIn("player.getItemInHand(hand) == held", held)
        self.assertIn("!player.isSpectator()", held)

    def test_reentrancy_and_callback_failure_fail_closed_and_release_guard(self):
        use = method(self.service, "public static InteractionResult use(")
        self.assertLess(use.index("CHECKING_REMOTE.get()"), use.index("CHECKING_REMOTE.set(true)"))
        self.assertIn("catch (RuntimeException error)", use)
        self.assertIn("finally { CHECKING_REMOTE.remove(); }", use)
        self.assertLess(use.index("!permitted("), use.index("setScanlinesEnabled("))

    def test_scanline_flag_defaults_off_and_is_saved_synced_per_loaded_instance(self):
        self.assertIn("private boolean scanlinesEnabled;", self.tv)
        self.assertNotIn("static boolean scanlinesEnabled", self.tv)
        self.assertIn('scanlinesEnabled = tag.getBoolean("CrtScanlines")', self.tv)
        self.assertIn('tag.putBoolean("CrtScanlines", scanlinesEnabled)', self.tv)
        update = method(self.tv, "boolean setScanlinesEnabled(")
        for clause in ("instanceof net.minecraft.server.level.ServerLevel", "isSameThread()", "isRemoved()",
                       "hasChunkAt(worldPosition)", "getBlockEntity(worldPosition) != this", "changed();"):
            self.assertIn(clause, update)
        endpoint = source("HomeEndpointBlockEntity.java")
        self.assertIn("Block.UPDATE_CLIENTS", endpoint)
        self.assertIn("return saveWithoutMetadata(registries)", endpoint)
        self.assertIn("ClientboundBlockEntityDataPacket.create(this)", endpoint)
        for forbidden in ("ServerArcadeSessions", "RomLibrary", "clearLink()", "attach(", "setBlock(", "removeBlock("):
            self.assertNotIn(forbidden, self.service)


if __name__ == "__main__":
    unittest.main()
