package cn.piq.fcarcade.home;

import cn.piq.fcarcade.compat.HomeShipSpace;
import cn.piq.fcarcade.layout.RocketArcadeGeometry.Point;
import cn.piq.fcarcade.layout.ZapperAimGeometry;
import java.util.List;
import java.util.UUID;
import java.util.function.BiPredicate;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

/** Loaded-only, bounded occlusion in world + nearby ship storage spaces. Never relies on an unpatched wrapper clip. */
final class HomeShipRay {
    record Result(boolean valid, BlockHitResult hit, double distance) {}
    private HomeShipRay() {}
    static Result trace(Level level, Player player, Vec3 worldFrom, Vec3 worldTo,
                        HomeShipSpace.Frame target, boolean outline, BiPredicate<UUID,BlockPos> ignore) {
        List<HomeShipSpace.Frame> frames = HomeShipSpace.rayFrames(level,worldFrom,worldTo);
        if (target == null || frames.isEmpty() || frames.stream().noneMatch(f -> HomeShipSpace.same(f,target)))
            return new Result(false,null,0);
        BlockHitResult nearest = null; double distance = Double.POSITIVE_INFINITY;
        for (var frame : frames) {
            Vec3 from = frame.toStorage(worldFrom), to = frame.toStorage(worldTo);
            if (!HomeShipSpace.finite(from) || !HomeShipSpace.finite(to)) return new Result(false,null,0);
            // Traverse relative to the start, so large Sable plot coordinates are not mistaken for world-border violations.
            BlockPos base = BlockPos.containing(from); Vec3 offset = Vec3.atLowerCornerOf(base);
            var a = from.subtract(offset); var b = to.subtract(offset);
            var cells = ZapperAimGeometry.cells(new Point(a.x,a.y,a.z),new Point(b.x,b.y,b.z));
            if (cells.isEmpty()) return new Result(false,null,0);
            for (var cell : cells) {
                BlockPos pos = base.offset(cell.x(),cell.y(),cell.z());
                if (!level.hasChunkAt(pos)) return new Result(false,null,0);
                if (ignore.test(frame.id(),pos)) continue;
                var state = level.getBlockState(pos);
                var context = CollisionContext.of(player);
                var shape = outline ? state.getShape(level,pos,context) : state.getCollisionShape(level,pos,context);
                var hit = shape.clip(from,to,pos);
                if (hit != null) {
                    double d = from.distanceTo(hit.getLocation());
                    if (!Double.isFinite(d)) return new Result(false,null,0);
                    if (d < distance) { distance = d; nearest = hit; }
                }
            }
        }
        return new Result(true,nearest,distance);
    }
}
