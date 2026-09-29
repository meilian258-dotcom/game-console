package cn.piq.computer.client;

import cn.piq.fcarcade.home.HomeTvStructure;
import cn.piq.fcarcade.layout.RocketArcadeGeometry.Point;
import cn.piq.fcarcade.layout.ZapperAimGeometry;
import java.util.function.Function;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/** The picture is recessed in a multi-block TV. Only its verified own shell may be ignored. */
final class ComputerScreenRay {
    private ComputerScreenRay() {}

    static boolean visible(Level level, Player player, BlockPos television, Vec3 eye, Vec3 end) {
        var context=CollisionContext.of(player);
        return clear(eye,end,level::hasChunkAt,
                pos->television.equals(HomeTvStructure.resolveAnchor(level,pos)),
                pos->level.getBlockState(pos).getCollisionShape(level,pos,context));
    }

    // Narrow seam for tests using the real TV footprint, ray traversal and Minecraft voxel shapes.
    static boolean clear(Vec3 eye, Vec3 end, Predicate<BlockPos> loaded,
                         Predicate<BlockPos> ownTelevision, Function<BlockPos,VoxelShape> collision) {
        var cells=ZapperAimGeometry.cells(point(eye),point(end));
        double distance=eye.distanceTo(end);
        if(cells.isEmpty()||!Double.isFinite(distance)||distance<=0||distance>8)return false;
        for(var cell:cells){
            var pos=new BlockPos(cell.x(),cell.y(),cell.z());
            if(!loaded.test(pos))return false;
            // resolveAnchor checks the proxy's owner UUID, position, facing and TV variant.
            // ArcadeStructure.resolve does not resolve television proxies.
            if(ownTelevision.test(pos))continue;
            var hit=collision.apply(pos).clip(eye,end,pos);
            if(hit!=null&&ZapperAimGeometry.beforeScreen(eye.distanceTo(hit.getLocation()),distance))return false;
        }
        return true;
    }
    private static Point point(Vec3 p){return new Point(p.x,p.y,p.z);}
}
