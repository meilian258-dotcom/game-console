package cn.piq.fcarcade.home;

import cn.piq.fcarcade.home.HomeConsoleBlockEntity;
import cn.piq.fcarcade.home.HomeTvBlockEntity;
import cn.piq.fcarcade.home.HomeTvStructure;
import cn.piq.fcarcade.home.ZapperBinding;
import cn.piq.fcarcade.layout.RocketArcadeGeometry;
import cn.piq.fcarcade.layout.RocketArcadeGeometry.Point;
import cn.piq.fcarcade.layout.ScreenAspectFit;
import cn.piq.fcarcade.layout.ScreenRayMapping;
import cn.piq.fcarcade.layout.ScreenSurfaceGeometry;
import cn.piq.fcarcade.layout.ZapperAimGeometry;
import cn.piq.fcarcade.world.ArcadeStructure;
import cn.piq.fcarcade.world.FcArcadeBlock;
import net.minecraft.world.entity.player.Player;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import java.util.Optional;

/** Optical coordinates only; NES, never world brightness, determines light detection. */
public final class HomeZapperAim {
    private HomeZapperAim() {}
    public static Optional<ScreenRayMapping.Pixel> sample(Player player,ZapperBinding binding) {
        if(player==null||binding==null)return Optional.empty();
        var level=player.level();
        if(!level.dimension().location().equals(binding.dimension())||!level.hasChunkAt(binding.consolePos())
                ||!level.hasChunkAt(binding.tvPos()))return Optional.empty();
        if(!(level.getBlockEntity(binding.consolePos())instanceof HomeConsoleBlockEntity console)
                ||!(level.getBlockEntity(binding.tvPos())instanceof HomeTvBlockEntity tv)
                ||!binding.consoleId().equals(console.hardwareId())||!binding.tvId().equals(tv.hardwareId())
                ||!binding.linkId().equals(console.linkId())||!binding.linkId().equals(tv.linkId())
                ||!binding.tvPos().equals(console.tvPos())||!binding.consolePos().equals(tv.consolePos())
                ||!HomeHardware.connected(level,console,tv)
                ||!HomeTvStructure.complete(level,binding.tvPos()))return Optional.empty();
        var layout=ArcadeStructure.resolve(level,binding.tvPos());
        if(!layout.anchor().equals(binding.tvPos())||!(tv.getBlockState().getBlock()instanceof FcArcadeBlock block))return Optional.empty();
        var facing=layout.facing();
        var surface=ScreenSurfaceGeometry.frame(block.displayStyle(),RocketArcadeGeometry.quarterTurns(facing.getStepX(),facing.getStepZ()),
                layout.width(),layout.height(),HomeTvStructure.centered(tv.getBlockState()),ScreenAspectFit.Aspect.FOUR_THREE);
        var frame=cn.piq.fcarcade.compat.HomeShipSpace.at(level,binding.tvPos());
        if(frame==null)return Optional.empty();
        Vec3 worldEye=player.getEyePosition(),worldDirection=player.getLookAngle();
        Vec3 eye=frame.toStorage(worldEye),direction=frame.directionToStorage(worldDirection);
        var pixel=ScreenRayMapping.hit(surface,new Point(eye.x-binding.tvPos().getX(),eye.y-binding.tvPos().getY(),eye.z-binding.tvPos().getZ()),
                point(direction),ZapperAimGeometry.MAX_DISTANCE);
        if(pixel.isEmpty())return pixel;
        if(frame.ship()) {
            Vec3 worldEnd=worldEye.add(worldDirection.normalize().scale(pixel.get().distance()));
            var ray=HomeShipRay.trace(level,player,worldEye,worldEnd,frame,false,
                    (ship,pos)->java.util.Objects.equals(ship,frame.id())&&binding.tvPos().equals(HomeTvStructure.resolveAnchor(level,pos)));
            if(!ray.valid()||ray.hit()!=null&&ZapperAimGeometry.beforeScreen(ray.distance(),pixel.get().distance()))return Optional.empty();
            // Entity boxes remain world-space; never query them at the ship's far-away storage coordinates.
            return entitiesClear(player,worldEye,worldEnd,pixel.get().distance())?pixel:Optional.empty();
        }
        Vec3 end=eye.add(direction.normalize().scale(pixel.get().distance()));
        var cells=ZapperAimGeometry.cells(point(eye),point(end));if(cells.isEmpty())return Optional.empty();
        for(var cell:cells) {
            BlockPos pos=new BlockPos(cell.x(),cell.y(),cell.z());
            if(!level.hasChunkAt(pos))return Optional.empty();
            // The display image is recessed inside its own selection/collision shell.
            // Ignore only exact, live proxy ownership of this validated TV, not a box
            // around the TV and never neighboring blocks or another television.
            if(binding.tvPos().equals(HomeTvStructure.resolveAnchor(level,pos)))continue;
            var state=level.getBlockState(pos);
            var shape=state.getCollisionShape(level,pos,CollisionContext.of(player));
            var hit=shape.clip(eye,end,pos);
            if(hit!=null&&ZapperAimGeometry.beforeScreen(eye.distanceTo(hit.getLocation()),pixel.get().distance()))return Optional.empty();
        }
        return entitiesClear(player,eye,end,pixel.get().distance())?pixel:Optional.empty();
    }
    private static boolean entitiesClear(Player player,Vec3 eye,Vec3 end,double distance) {
        var entities=player.level().getEntities(player,new AABB(eye,end).inflate(.1),entity->entity.isPickable()&&!entity.isSpectator());
        if(entities.size()>256)return false;
        for(var entity:entities) {
            var box=entity.getBoundingBox();if(box.contains(eye))return false;
            var hit=box.clip(eye,end);
            if(hit.isPresent()&&ZapperAimGeometry.beforeScreen(eye.distanceTo(hit.get()),distance))return false;
        }
        return true;
    }
    private static Point point(Vec3 v){return new Point(v.x,v.y,v.z);}
}
