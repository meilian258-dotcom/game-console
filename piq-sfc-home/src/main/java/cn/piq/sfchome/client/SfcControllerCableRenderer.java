package cn.piq.sfchome.client;

import cn.piq.fcarcade.client.ControllerCableRenderer;
import cn.piq.fcarcade.client.ControllerCableRenderer.Lease;
import cn.piq.fcarcade.layout.ControllerCableGeometry.Style;
import cn.piq.fcarcade.layout.RocketArcadeGeometry;
import cn.piq.sfchome.data.SfcControllerData;
import cn.piq.sfchome.world.SfcHomeConsoleBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;

/** SFC data-only adapter; common FC code has no reverse dependency on this addon. */
public final class SfcControllerCableRenderer {
    private SfcControllerCableRenderer() {}
    public static void render(SfcHomeConsoleBlockEntity console, float partial, PoseStack poses,
                              MultiBufferSource buffers, int light, int overlay) {
        var facing = console.getBlockState().getValue(BlockStateProperties.HORIZONTAL_FACING);
        int turns = RocketArcadeGeometry.quarterTurns(facing.getStepX(), facing.getStepZ());
        ControllerCableRenderer.render(console, turns, Style.SFC, visual(console, 0), visual(console, 1),
                (stack, port, lease) -> SfcControllerData.isController(stack) && SfcControllerData.port(stack) == port
                        && lease.equals(SfcControllerData.leaseId(stack)), partial, poses, buffers, light, overlay);
    }
    private static Lease visual(SfcHomeConsoleBlockEntity console, int port) {
        return console.controllerDocked(port) ? null
                : new Lease(console.controllerVisualPlayer(port), console.controllerVisualLease(port));
    }
    public static AABB bounds(SfcHomeConsoleBlockEntity console) { return ControllerCableRenderer.bounds(console); }
}
