package cn.piq.fcarcade.cabinet;

import cn.piq.fcarcade.layout.*;
import cn.piq.fcarcade.layout.RocketArcadeGeometry.Point;
import cn.piq.fcarcade.world.*;
import net.minecraft.core.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.*;

/** Loaded-only picking of parts outside the anchor cell. Server recomputes from its own player pose. */
public final class CabinetPowerPicking {
    private CabinetPowerPicking(){}
    private record Cabinet(CabinetTarget target,LegacyFcArcadeBlockEntity entity,int turns,boolean compact){
        boolean portrait(){return entity instanceof PortraitCabinetBlockEntity;}
        Point local(Vec3 p){var a=target.anchor();return new Point(p.x-a.getX(),p.y-a.getY(),p.z-a.getZ());}
    }
    public static BlockHitResult pick(Player player){
        if(player==null||!player.isAlive()||player.isSpectator()||player.isShiftKeyDown()||!player.getMainHandItem().isEmpty())return null;
        var level=player.level();var eye=player.getEyePosition();double reach=Math.min(6,player.blockInteractionRange());
        var end=eye.add(player.getViewVector(1).scale(reach));BlockHitResult best=null;double bestDistance=reach*reach;
        var center=BlockPos.containing(eye);int radius=(int)Math.ceil(reach)+1;
        var cabinets=new java.util.ArrayList<Cabinet>();
        for(var pos:BlockPos.betweenClosed(center.offset(-radius,-radius,-radius),center.offset(radius,radius,radius))){
            if(!level.hasChunkAt(pos)||!(level.getBlockEntity(pos) instanceof LegacyFcArcadeBlockEntity cabinet))continue;
            var target=CabinetTarget.resolve(level,pos);if(target==null||!target.anchor().equals(pos))continue;
            var face=cabinet.getBlockState().getValue(FcArcadeBlock.FACING);
            int turns=RocketArcadeGeometry.quarterTurns(face.getStepX(),face.getStepZ());
            boolean compact=cabinet instanceof DualCabinetBlockEntity dual&&dual.compactFootprint();
            cabinets.add(new Cabinet(target,cabinet,turns,compact));
        }
        var loaded=new LoadedBlocks(level,cabinets.stream().map(Cabinet::target).collect(java.util.stream.Collectors.toSet()));
        for(var cabinet:cabinets){
            if(!CabinetCoinPolicy.supported(cabinet.entity().cabinetBackend().toString()))continue;
            var target=cabinet.target();var pos=target.anchor();int turns=cabinet.turns();
            var boxes=cabinet.portrait()?PortraitCabinetGeometry.powerBoxes():CabinetPowerGeometry.boxes(target.dual(),cabinet.compact());
            var point=CabinetPowerGeometry.intersection(boxes,turns,cabinet.local(eye),cabinet.local(end));
            if(point==null)continue;
            var world=new Vec3(point.x()+pos.getX(),point.y()+pos.getY(),point.z()+pos.getZ());
            double distance=eye.distanceToSqr(world);if(distance>bestDistance)continue;
            var obstruction=loaded.clip(new ClipContext(eye,world,ClipContext.Block.OUTLINE,ClipContext.Fluid.NONE,player));
            if(obstruction.getType()!=HitResult.Type.MISS)continue;
            boolean occluded=false;
            for(var body:cabinets){
                double t=CabinetBodyPicking.firstHit(body==cabinet?CabinetBodyPicking.mountBoxes(body.target().dual(),body.compact(),body.portrait(),body.turns()):CabinetBodyPicking.boxes(body.target().dual(),body.compact(),body.portrait(),body.turns()),body.local(eye),body.local(world));
                // Its own conservative hull can be a few mm outside the inset bezel.
                double tolerance=body==cabinet?.001:0;
                if(t<1&&Math.sqrt(distance)*(1-t)>tolerance){occluded=true;break;}
            }
            if(occluded)continue;
            bestDistance=distance;Direction outward=Direction.NORTH;
            for(int i=0;i<Math.floorMod(turns,4);i++)outward=outward.getClockWise();
            // Preserve the real proxy/upper-half hit for its own protection check as well
            // as the anchor's. The old single-block cabinet has no upper proxy.
            BlockPos clicked=pos.immutable();
            var inside=BlockPos.containing(world.subtract(new Vec3(outward.getStepX(),0,outward.getStepZ()).scale(.02)));
            if(target.equals(CabinetTarget.resolve(level,inside)))clicked=inside;
            else{
                var box=boxes.getFirst();var mount=RocketArcadeGeometry.rotate(new Point((box.minX()+box.maxX())/2,(box.minY()+box.maxY())/2,box.minZ()+.02),turns);
                var proxy=BlockPos.containing(pos.getX()+mount.x(),pos.getY()+mount.y(),pos.getZ()+mount.z());
                if(target.equals(CabinetTarget.resolve(level,proxy)))clicked=proxy;
            }
            best=new BlockHitResult(world,outward,clicked,false);
        }
        return best;
    }
    /** Shape callbacks must not load neighboring chunks either. */
    private record LoadedBlocks(Level level,java.util.Set<CabinetTarget> bodies) implements BlockGetter {
        @Override public BlockState getBlockState(BlockPos pos){
            if(!level.hasChunkAt(pos))return Blocks.BARRIER.defaultBlockState();
            var target=CabinetTarget.resolve(level,pos);
            // Only complete, separately tested cabinet bodies replace their broad outline.
            return target!=null&&bodies.contains(target)?Blocks.AIR.defaultBlockState():level.getBlockState(pos);
        }
        @Override public BlockEntity getBlockEntity(BlockPos pos){return level.hasChunkAt(pos)?level.getBlockEntity(pos):null;}
        @Override public FluidState getFluidState(BlockPos pos){return level.hasChunkAt(pos)?level.getFluidState(pos):Fluids.EMPTY.defaultFluidState();}
        @Override public int getHeight(){return level.getHeight();}
        @Override public int getMinBuildHeight(){return level.getMinBuildHeight();}
    }
}
