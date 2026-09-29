package cn.piq.fcarcade.furniture;

import java.util.UUID;
import net.minecraft.core.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.*;

/** Ephemeral one-player vanilla mount; no inventory, saved riders, controls or input remapping. */
public final class FurnitureSeat extends Entity {
    private BlockPos owner;
    private UUID identity;
    private int index;
    private Vec3 entry;
    public FurnitureSeat(EntityType<? extends FurnitureSeat> type,Level level) {
        super(type,level);noPhysics=true;setNoGravity(true);setInvulnerable(true);
    }
    void configure(BlockPos owner,UUID identity,int index,Vec3 position,Vec3 entry,float yaw) {
        this.owner=owner.immutable();this.identity=identity;this.index=index;this.entry=entry;
        moveTo(position.x,position.y,position.z,yaw,0);
    }
    boolean belongs(BlockPos pos,UUID id) { return pos.equals(owner)&&id.equals(identity); }
    int index() { return index; }
    @Override protected void defineSynchedData(SynchedEntityData.Builder builder) {}
    @Override protected void readAdditionalSaveData(CompoundTag tag) { /* noSave: never resurrect an occupied seat. */ }
    @Override protected void addAdditionalSaveData(CompoundTag tag) {}
    @Override public boolean shouldBeSaved() { return false; }
    @Override public boolean isPickable() { return false; }
    @Override public boolean isPushable() { return false; }
    @Override public boolean canBeCollidedWith() { return false; }
    @Override protected boolean canAddPassenger(Entity entity) { return entity instanceof Player && getPassengers().isEmpty(); }
    @Override public Vec3 getPassengerRidingPosition(Entity passenger) {
        // Entity.positionRider subtracts Player.DEFAULT_VEHICLE_ATTACHMENT.y = 0.6.
        // This world point is the model's actual seat top (hip), NOT the player's feet.
        return position();
    }
    @Override public void tick() {
        super.tick();setDeltaMovement(Vec3.ZERO);
        if(level() instanceof ServerLevel server) {
            if(owner==null||identity==null||!FurnitureService.validSeat(server,owner,identity,index)
                ||(tickCount>1 && (getPassengers().size()!=1 || !(getFirstPassenger() instanceof ServerPlayer player)
                || !player.isAlive() || player.isSpectator() || player.level()!=server || player.hasDisconnected()
                || !FurnitureService.mayRemain(player,owner)))) discard();
        }
    }
    @Override public Vec3 getDismountLocationForPassenger(LivingEntity passenger) {
        if(owner==null)return position();
        // Prefer nearby solid ground. Never force-load the adjacent chunk to find an exit.
        for(int radius=1;radius<=2;radius++)for(Direction direction:Direction.Plane.HORIZONTAL) {
            BlockPos base=BlockPos.containing(getX(),owner.getY(),getZ()).relative(direction,radius);
            for(int dy=1;dy>=-1;dy--) {
                var feet=base.offset(0,dy,0);var below=feet.below();
                if(!level().hasChunkAt(feet)||!level().hasChunkAt(below)||!level().getWorldBorder().isWithinBounds(feet)
                    ||!level().getBlockState(below).isFaceSturdy(level(),below,Direction.UP))continue;
                var target=Vec3.atBottomCenterOf(feet);
                if(level().getFluidState(feet).isEmpty()&&level().noCollision(passenger,passenger.getDimensions(Pose.STANDING).makeBoundingBox(target)))return target;
            }
        }
        if(entry!=null&&level().hasChunkAt(BlockPos.containing(entry))&&level().noCollision(passenger,passenger.getDimensions(Pose.STANDING).makeBoundingBox(entry)))return entry;
        // The top of the furniture remains preferable to teleports through a wall.
        return position();
    }
}
