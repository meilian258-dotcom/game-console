package cn.piq.fcarcade.client;

import cn.piq.fcarcade.compat.HomeShipSpace;
import cn.piq.fcarcade.home.HomeConsoleBlockEntity;
import cn.piq.fcarcade.home.HomeHardware;
import cn.piq.fcarcade.home.HomeTvBlockEntity;
import cn.piq.fcarcade.home.SuborConsoleBlock;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Quaternionf;

/** Global screen events do not inherit the Sable transform supplied to block-entity renderers. */
final class HomeScreenRenderPose {
    private HomeScreenRenderPose() {}

    /** Called only inside the caller's push/pop scope; never use for an already transformed BER. */
    static boolean apply(RenderLevelStageEvent event, Level level, BlockPos anchor) {
        var frame = HomeShipSpace.GROUND;
        if (level.getBlockEntity(anchor) instanceof HomeTvBlockEntity tv
                && tv.consolePos() != null && level.hasChunkAt(tv.consolePos())
                && level.getBlockEntity(tv.consolePos()) instanceof HomeConsoleBlockEntity console
                && !(console.getBlockState().getBlock() instanceof SuborConsoleBlock)) {
            frame = HomeShipSpace.at(level, anchor, event.getPartialTick().getGameTimeDeltaPartialTick(true));
            if (frame == null || frame.ship() && HomeHardware.connectedConsole(level, anchor) != console) return false;
        }
        // Other systems and ordinary cabinets retain their original ground rendering path.
        return apply(event.getPoseStack(), frame, anchor, event.getCamera().getPosition());
    }

    static boolean apply(PoseStack poses, HomeShipSpace.Frame frame, BlockPos anchor, Vec3 camera) {
        if (frame == null || anchor == null || !HomeShipSpace.finite(camera)) return false;
        // Subtract in double precision before feeding the float render matrix. A ship's plot
        // can be millions of blocks away while its visible screen is only meters from us.
        var origin = frame.toWorld(Vec3.atLowerCornerOf(anchor)).subtract(camera);
        poses.translate(origin.x, origin.y, origin.z);
        if (frame.ship()) poses.mulPose(new Quaternionf(frame.rotation()));
        return true;
    }
}
