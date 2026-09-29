package cn.piq.fcarcade.client;

import cn.piq.fcarcade.home.HomeConsoleBlockEntity;
import cn.piq.fcarcade.home.HomeControllerData;
import cn.piq.fcarcade.home.SuborConsoleBlock;
import cn.piq.fcarcade.layout.ControllerCableGeometry;
import cn.piq.fcarcade.layout.ControllerCableGeometry.Style;
import cn.piq.fcarcade.layout.RocketArcadeGeometry;
import cn.piq.fcarcade.layout.RocketArcadeGeometry.Point;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.List;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/** Two identity-bound visual cords. No cached players, network authority, texture or model mutations. */
public final class ControllerCableRenderer {
    public record Lease(UUID player, UUID lease) {}
    @FunctionalInterface public interface Receipt { boolean matches(ItemStack stack, int port, UUID lease); }
    private static final ResourceLocation TEXTURE = ResourceLocation.withDefaultNamespace("textures/block/black_concrete.png");
    private ControllerCableRenderer() {}

    /** Call after the console model's facing PoseStack has been popped (world-aligned block-local axes). */
    public static void renderFc(HomeConsoleBlockEntity console, float partial, PoseStack poses,
                                MultiBufferSource buffers, int light, int overlay) {
        var state = console.getBlockState();
        var face = state.getValue(BlockStateProperties.HORIZONTAL_FACING);
        int turns = RocketArcadeGeometry.quarterTurns(face.getStepX(), face.getStepZ());
        Style style = state.getBlock() instanceof SuborConsoleBlock
                ? SuborConsoleBlock.compact(state) ? Style.SUBOR_COMPACT : SuborConsoleBlock.wide(state) ? Style.SUBOR_WIDE : Style.SUBOR : Style.FAMICOM;
        render(console, turns, style, visual(console, 0), visual(console, 1),
                (stack, port, lease) -> HomeControllerData.isController(stack)
                        && HomeControllerData.port(stack) == port && lease.equals(HomeControllerData.leaseId(stack)),
                partial, poses, buffers, light, overlay);
    }
    private static Lease visual(HomeConsoleBlockEntity console, int port) {
        return console.controllerDocked(port) ? null
                : new Lease(console.controllerVisualPlayer(port), console.controllerVisualLease(port));
    }
    public static AABB bounds(BlockEntity console) {
        return new AABB(console.getBlockPos()).inflate(ControllerCableGeometry.RENDER_MARGIN);
    }

    public static void render(BlockEntity console, int turns, Style style, Lease p1, Lease p2, Receipt receipt,
                              float partial, PoseStack poses, MultiBufferSource buffers, int light, int overlay) {
        Minecraft mc = Minecraft.getInstance();
        var level = console.getLevel();
        if (level == null || level != mc.level || console.isRemoved() || mc.getConnection() == null
                || !mc.getConnection().getConnection().isConnected() || !level.hasChunkAt(console.getBlockPos())
                || level.getBlockEntity(console.getBlockPos()) != console || receipt == null) return;
        Vec3 origin = Vec3.atLowerCornerOf(console.getBlockPos());
        var frame = style == Style.FAMICOM
                ? cn.piq.fcarcade.compat.HomeShipSpace.at(level,console.getBlockPos(),partial)
                : cn.piq.fcarcade.compat.HomeShipSpace.GROUND;
        if (frame == null) return;
        if (frame.toStorage(mc.gameRenderer.getMainCamera().getPosition()).distanceToSqr(origin.add(.5, .5, .5))
                > ControllerCableGeometry.VIEW_RANGE * ControllerCableGeometry.VIEW_RANGE) return;
        drawPort(mc, console, style, turns, 0, p1, receipt, partial, origin, poses, buffers, light, overlay);
        drawPort(mc, console, style, turns, 1, p2, receipt, partial, origin, poses, buffers, light, overlay);
    }
    private static void drawPort(Minecraft mc, BlockEntity console, Style style, int turns, int port, Lease visual,
                                 Receipt receipt, float partial, Vec3 origin, PoseStack poses,
                                 MultiBufferSource buffers, int light, int overlay) {
        if (visual == null || visual.player() == null || visual.lease() == null) return;
        Player player = console.getLevel().getPlayerByUUID(visual.player());
        if (player == null || player.level() != console.getLevel() || player.isRemoved() || !player.isAlive()
                || player.isSpectator() || player.isInvisible() || player.isVisuallySwimming() || player.isFallFlying()) return;
        ItemStack main = player.getMainHandItem(), off = player.getOffhandItem();
        boolean mainMatches = main.getCount() == 1 && receipt.matches(main, port, visual.lease());
        boolean offMatches = off.getCount() == 1 && receipt.matches(off, port, visual.lease());
        int hand = ControllerCableGeometry.heldHand(visual.player(), visual.lease(), player.getUUID(), port,
                mainMatches ? visual.lease() : null, port, offMatches ? visual.lease() : null, port,
                style == Style.FAMICOM ? player.distanceToSqr(origin.add(.5, .5, .5))
                        : player.position().distanceToSqr(origin.add(.5, .5, .5)), false);
        if (hand < 0) return;
        boolean right = (hand == 0 ? player.getMainArm() : player.getMainArm().getOpposite()) == HumanoidArm.RIGHT;
        boolean twoHands = (hand == 0 ? off : main).isEmpty();
        Vec3 grip;
        if (player == mc.getCameraEntity() && mc.options.getCameraType().isFirstPerson()) {
            // The first-person item pass is absent behind a GUI/scoping; do not draw a dangling local cord.
            if (mc.screen != null || mc.options.hideGui || player.isScoping() || player.isUsingItem()) return;
            // Both FC and SFC custom two-hand rigs use this exact translation/pitch. Equip progress is not
            // public at BER time: idle equip is used, never reflective access to the renderer's mutable model.
            var rig = ControllerPoseLayout.first(right, twoHands, 0, player.getAttackAnim(partial));
            Point local = ControllerCableGeometry.firstGrip(rig.x(), rig.y(), rig.z(), rig.pitch(), rig.yaw());
            if (style == Style.SFC && !twoHands) {
                // SFC deliberately leaves mixed-hand drawing to vanilla: use its hand region, not the two-hand rig.
                local = new Point(right ? .48 : -.48, -.48, -.78);
            }
            var v = new Vector3f((float)local.x(), (float)local.y(), (float)local.z());
            mc.gameRenderer.getMainCamera().rotation().transform(v);
            grip = mc.gameRenderer.getMainCamera().getPosition().add(v.x, v.y, v.z);
        } else {
            double yaw = Mth.rotLerp(partial, player.yBodyRotO, player.yBodyRot);
            Point local = ControllerCableGeometry.thirdGrip(yaw, right, twoHands, player.isCrouching());
            grip = player.getPosition(partial).add(local.x(), local.y(), local.z());
        }
        if (style == Style.FAMICOM) {
            grip = cn.piq.fcarcade.compat.HomeShipSpace.localPoint(console.getLevel(),console.getBlockPos(),grip,partial);
            if (grip == null) return;
        }
        Vec3 end = grip.subtract(origin);
        drawCable(ControllerCableGeometry.cable(ControllerCableGeometry.socket(style, port, turns),
                new Point(end.x, end.y, end.z)), poses, buffers, light, overlay);
    }
    private static void drawCable(List<Point> path, PoseStack poses, MultiBufferSource buffers, int light, int overlay) {
        if (path.isEmpty()) return;
        VertexConsumer out = buffers.getBuffer(RenderType.entityCutoutNoCull(TEXTURE));
        for (int i = 1; i < path.size(); i++) {
            Point a = path.get(i - 1), b = path.get(i);
            Vec3 axis = new Vec3(b.x() - a.x(), b.y() - a.y(), b.z() - a.z()).normalize();
            Vec3 u = axis.cross(Math.abs(axis.y) > .95 ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0)).normalize();
            Vec3 v = axis.cross(u).normalize();
            for (int side = 0; side < ControllerCableGeometry.SIDES; side++) {
                double t = side * Math.PI / 4, s = (side + 1) * Math.PI / 4;
                Vec3 n = u.scale(Math.cos(t)).add(v.scale(Math.sin(t)));
                Vec3 m = u.scale(Math.cos(s)).add(v.scale(Math.sin(s)));
                vertex(out, poses, a, n, 0, 0, light, overlay); vertex(out, poses, b, n, 0, 1, light, overlay);
                vertex(out, poses, b, m, 1, 1, light, overlay); vertex(out, poses, a, m, 1, 0, light, overlay);
            }
        }
    }
    private static void vertex(VertexConsumer out, PoseStack poses, Point p, Vec3 n, float u, float v, int light, int overlay) {
        double radius = ControllerCableGeometry.RADIUS;
        out.addVertex(poses.last().pose(), (float)(p.x() + n.x * radius), (float)(p.y() + n.y * radius),
                (float)(p.z() + n.z * radius)).setColor(255, 255, 255, 255).setUv(u, v).setOverlay(overlay)
                .setLight(light).setNormal(poses.last(), (float)n.x, (float)n.y, (float)n.z);
    }
}
