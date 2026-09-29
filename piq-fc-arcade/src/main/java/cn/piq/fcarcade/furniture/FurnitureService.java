package cn.piq.fcarcade.furniture;

import java.util.*;
import net.minecraft.core.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.*;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.BlockItemStateProperties;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.*;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.neoforged.neoforge.common.*;
import net.neoforged.neoforge.common.util.TriState;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/** Server-thread furniture lifecycle. Existing emulator session and controller services are untouched. */
public final class FurnitureService {
    private static final ThreadLocal<Set<UUID>> CHANGING=ThreadLocal.withInitial(HashSet::new);
    private static final ThreadLocal<Boolean> PROTECTION=ThreadLocal.withInitial(()->false);
    private FurnitureService() {}
    public static BlockPos other(BlockPos pos,BlockState state) {
        var delta=FurnitureLayout.second(FurnitureBlock.turns(state));
        int sign=FurnitureBlock.isAnchor(state)?1:-1;
        return pos.offset(delta.x()*sign,0,delta.z()*sign);
    }
    private static BlockPos anchor(BlockPos pos,BlockState state) { return FurnitureBlock.isAnchor(state)?pos:other(pos,state); }
    private static BlockPos cell(FurnitureLedger.Entry e,int part) {
        var d=FurnitureLayout.second(e.turns());return new BlockPos(e.x()+d.x()*part,e.y(),e.z()+d.z()*part);
    }
    private static boolean owns(ServerLevel level,FurnitureLedger.Entry e,int part) {
        var pos=cell(e,part);
        return level.hasChunkAt(pos)&&level.getBlockEntity(pos) instanceof FurnitureBlockEntity be&&e.id().equals(be.identity())
            &&be.getBlockState().getBlock() instanceof WoodenBenchBlock block&&block.wood().id().equals(e.wood())
            &&FurnitureBlock.turns(be.getBlockState())==e.turns()&&FurnitureBlock.isAnchor(be.getBlockState())==(part==0);
    }
    static boolean canPlace(BlockPlaceContext context,BlockState state) {
        var level=context.getLevel();var player=context.getPlayer();var pos=context.getClickedPos();
        for(var p:List.of(pos,other(pos,state))) {
            if(level.isOutsideBuildHeight(p)||!level.hasChunkAt(p)||!level.getWorldBorder().isWithinBounds(p))return false;
            var at=BlockPlaceContext.at(context,p,context.getClickedFace());
            if(!at.getClickedPos().equals(p)||!level.getBlockState(p).canBeReplaced(at)||level.getBlockEntity(p)!=null
                ||!level.getFluidState(p).isEmpty()||!level.isUnobstructed(state,p,player==null?CollisionContext.empty():CollisionContext.of(player)))return false;
            if(player!=null&&(!level.mayInteract(player,p)||!player.mayUseItemAt(p,context.getClickedFace(),context.getItemInHand())))return false;
        }
        return true;
    }
    static boolean placeBench(BlockPlaceContext context,BlockState state) {
        var level=context.getLevel();if(!canPlace(context,state))return false;
        var pos=context.getClickedPos();var second=other(pos,state);var right=state.setValue(WoodenBenchBlock.PART,WoodenBenchBlock.Part.RIGHT);
        var before=level.getBlockState(pos);var beforeRight=level.getBlockState(second);var id=UUID.randomUUID();
        FurnitureBlockEntity firstEntity=null,secondEntity=null;boolean committed=false;
        int flags=level.captureBlockSnapshots?Block.UPDATE_ALL:Block.UPDATE_CLIENTS;
        CHANGING.get().add(id);
        try {
            if(!level.setBlock(pos,state,flags)||!(level.getBlockEntity(pos) instanceof FurnitureBlockEntity first))return false;
            firstEntity=first;first.identity(id);
            // Hooks from the first cell must not overwrite a newly introduced second-cell occupant.
            var at=BlockPlaceContext.at(context,second,context.getClickedFace());
            if(!level.hasChunkAt(second)||!level.getWorldBorder().isWithinBounds(second)
                ||context.getPlayer()!=null&&(!level.mayInteract(context.getPlayer(),second)||!context.getPlayer().mayUseItemAt(second,context.getClickedFace(),context.getItemInHand()))
                ||level.getBlockState(second)!=beforeRight||level.getBlockEntity(second)!=null||!level.getBlockState(second).canBeReplaced(at)
                ||!level.isUnobstructed(right,second,context.getPlayer()==null?CollisionContext.empty():CollisionContext.of(context.getPlayer())))return false;
            if(!level.setBlock(second,right,flags)||!(level.getBlockEntity(second) instanceof FurnitureBlockEntity mate))return false;
            secondEntity=mate;mate.identity(id);
            if(level.getBlockState(pos)!=state||level.getBlockEntity(pos)!=firstEntity||level.getBlockState(second)!=right||level.getBlockEntity(second)!=secondEntity)return false;
            if(level instanceof ServerLevel server) {
                var data=FurnitureSavedData.get(server);
                if(!data.ledger.add(new FurnitureLedger.Entry(id,pos.getX(),pos.getY(),pos.getZ(),FurnitureBlock.turns(state),((FurnitureBlock)state.getBlock()).wood().id(),false,0)))return false;
                data.setDirty();
            }
            committed=true;
            // NeoForge captures BOTH writes and emits its EntityMultiPlaceEvent around normal ItemStack use.
            if(!level.captureBlockSnapshots){level.blockUpdated(pos,state.getBlock());level.blockUpdated(second,right.getBlock());}
            return true;
        } finally {
            if(!committed) {
                if(secondEntity!=null&&level.getBlockEntity(second)==secondEntity&&level.getBlockState(second)==right)level.setBlock(second,beforeRight,Block.UPDATE_ALL);
                if(firstEntity!=null&&level.getBlockEntity(pos)==firstEntity&&level.getBlockState(pos)==state)level.setBlock(pos,before,Block.UPDATE_ALL);
            }
            CHANGING.get().remove(id);
        }
    }
    private static List<FurnitureSeat> seats(ServerLevel level,BlockPos owner,UUID identity) {
        return level.getEntitiesOfClass(FurnitureSeat.class,new AABB(owner).inflate(3),seat->seat.belongs(owner,identity)&&!seat.isRemoved());
    }
    static void eject(ServerLevel level,BlockPos owner,UUID identity) { for(var seat:seats(level,owner,identity))seat.discard(); }
    static boolean validSeat(ServerLevel level,BlockPos pos,UUID identity,int index) {
        if(!level.hasChunkAt(pos)||!(level.getBlockEntity(pos) instanceof FurnitureBlockEntity be)||!identity.equals(be.identity())
            ||!(be.getBlockState().getBlock() instanceof FurnitureBlock block)||!FurnitureBlock.isAnchor(be.getBlockState()))return false;
        if(!block.isBench())return index==0&&!be.getBlockState().getValue(FoldingStoolBlock.FOLDED);
        var e=FurnitureSavedData.get(level).ledger.get(identity);
        return index>=0&&index<2&&e!=null&&!e.closed()&&owns(level,e,0)&&owns(level,e,1);
    }
    static boolean mayRemain(ServerPlayer player,BlockPos owner) {
        var level=player.serverLevel();if(!level.hasChunkAt(owner)||!level.mayInteract(player,owner))return false;
        var state=level.getBlockState(owner);
        return !(state.getBlock() instanceof WoodenBenchBlock)
            ||level.hasChunkAt(other(owner,state))&&level.mayInteract(player,other(owner,state));
    }
    static InteractionResult interact(ServerPlayer player,BlockPos clicked,BlockHitResult hit) {
        var level=player.serverLevel();
        if(PROTECTION.get()||!level.getServer().isSameThread()||!level.hasChunkAt(clicked)||player.isSpectator()||!player.isAlive()
            ||!player.getMainHandItem().isEmpty()||!player.getOffhandItem().isEmpty()||!level.mayInteract(player,clicked)
            ||!player.canInteractWithBlock(clicked,0))return InteractionResult.FAIL;
        var state=level.getBlockState(clicked);if(!(state.getBlock() instanceof FurnitureBlock block))return InteractionResult.PASS;
        var pos=anchor(clicked,state);if(!level.hasChunkAt(pos)||!(level.getBlockEntity(pos) instanceof FurnitureBlockEntity be))return InteractionResult.FAIL;
        var id=be.identity();
        if(CHANGING.get().contains(id))return InteractionResult.FAIL;
        // Original clicked event has already passed the normal game mode path. For a two-cell bench,
        // check the other cell too rather than allowing right-half access to bypass anchor protection.
        if(block.isBench()) {
            var other=other(clicked,state);
            if(!level.hasChunkAt(other)||!level.mayInteract(player,other))return InteractionResult.FAIL;
            PROTECTION.set(true);
            try {
                var e=NeoForge.EVENT_BUS.post(new PlayerInteractEvent.RightClickBlock(player,InteractionHand.MAIN_HAND,other,
                    new BlockHitResult(Vec3.atCenterOf(other),hit.getDirection(),other,false)));
                if(e.isCanceled()||e.getUseBlock()==TriState.FALSE||e.getUseItem()==TriState.FALSE)return InteractionResult.FAIL;
            } finally { PROTECTION.remove(); }
        }
        if(level.getBlockEntity(pos)!=be||!level.hasChunkAt(clicked)||level.getBlockState(clicked)!=state
            ||!level.mayInteract(player,pos)||!level.mayInteract(player,clicked))return InteractionResult.FAIL;
        if(player.isShiftKeyDown()) {
            if(block.isBench())return InteractionResult.PASS;
            if(!seats(level,pos,id).isEmpty())return message(player,"occupied");
            var next=state.cycle(FoldingStoolBlock.FOLDED);
            if(!level.isUnobstructed(next,pos,CollisionContext.empty()))return message(player,"space");
            if(!level.setBlock(pos,next,Block.UPDATE_ALL)||level.getBlockEntity(pos)!=be||level.getBlockState(pos)!=next)return InteractionResult.FAIL;
            // Broadcast once from the server, including the clicking player. This uses
            // the current vanilla wooden-door sounds, not a bundled historical recording.
            level.playSound(null,pos,next.getValue(FoldingStoolBlock.FOLDED)?SoundEvents.WOODEN_DOOR_CLOSE:SoundEvents.WOODEN_DOOR_OPEN,
                SoundSource.BLOCKS,.8F,1.0F);
            return InteractionResult.CONSUME;
        }
        if(player.isPassenger())return message(player,"riding");
        if(!block.isBench()&&state.getValue(FoldingStoolBlock.FOLDED))return pickupStool(player,pos,state,be);
        if(!validSeat(level,pos,id,0))return message(player,block.isBench()?"incomplete":"folded");
        var existing=seats(level,pos,id);int preferred=block.isBench()&&!FurnitureBlock.isAnchor(state)?1:0;
        for(int attempt=0;attempt<(block.isBench()?2:1);attempt++) {
            int index=(preferred+attempt)%(block.isBench()?2:1);
            if(existing.stream().anyMatch(seat->seat.index()==index))continue;
            var p=FurnitureLayout.seat(block.isBench(),index,FurnitureBlock.turns(state));
            var world=new Vec3(pos.getX()+p.x(),pos.getY()+p.y(),pos.getZ()+p.z());
            // Check the head/torso volume above the seat, not the intentionally bent feet under its top.
            if(!level.noCollision(player,new AABB(world.x-.3,world.y+.02,world.z-.3,world.x+.3,world.y+1.3,world.z+.3)))continue;
            var seat=FurnitureRegistry.SEAT.get().create(level);if(seat==null)return InteractionResult.FAIL;
            float yaw=state.getValue(FurnitureBlock.FACING).toYRot();seat.configure(pos,id,index,world,player.position(),yaw);
            if(!level.addFreshEntity(seat)||!player.startRiding(seat)||!validSeat(level,pos,id,index)
                ||player.level()!=level||!player.isAlive()||player.isSpectator()||player.hasDisconnected()
                ||player.getVehicle()!=seat||seat.getPassengers().size()!=1||seat.getFirstPassenger()!=player
                ||level.getBlockEntity(pos)!=be||level.getBlockState(clicked)!=state||!mayRemain(player,pos)) {
                seat.discard();return InteractionResult.FAIL;
            }
            player.setYRot(yaw);player.setYBodyRot(yaw);player.setYHeadRot(yaw);
            return InteractionResult.CONSUME;
        }
        return message(player,existing.size()>=(block.isBench()?2:1)?"occupied":"space");
    }
    private static InteractionResult pickupStool(ServerPlayer player,BlockPos pos,BlockState state,FurnitureBlockEntity be) {
        var level=player.serverLevel();var id=be.identity();
        if(level.captureBlockSnapshots||level.restoringBlockSnapshots)return InteractionResult.FAIL;
        if(!seats(level,pos,id).isEmpty())return message(player,"occupied");
        if(!CHANGING.get().add(id))return InteractionResult.FAIL;
        try {
            // Right-click permission alone is not permission to remove a protected block.
            // Use the same cancellable break hook as normal player destruction, without
            // invoking playerDestroy, loot tables or any creative-mode inventory shortcut.
            PROTECTION.set(true);
            try {
                if(CommonHooks.fireBlockBreak(level,player.gameMode.getGameModeForPlayer(),player,pos,state).isCanceled())return InteractionResult.FAIL;
            } finally { PROTECTION.remove(); }
            // Event listeners may change worlds, blocks, seats, hands or the selected slot.
            // Revalidate all authority and identity before transferring this exact stool.
            if(player.level()!=level||!player.isAlive()||player.isSpectator()||player.hasDisconnected()||player.isPassenger()||player.isShiftKeyDown()
                ||!player.getMainHandItem().isEmpty()||!player.getOffhandItem().isEmpty()||!level.hasChunkAt(pos)
                ||level.captureBlockSnapshots||level.restoringBlockSnapshots||!level.mayInteract(player,pos)||!player.canInteractWithBlock(pos,0)
                ||player.blockActionRestricted(level,pos,player.gameMode.getGameModeForPlayer())
                ||level.getBlockEntity(pos)!=be||!id.equals(be.identity())||level.getBlockState(pos)!=state||!seats(level,pos,id).isEmpty())return InteractionResult.FAIL;
            var inventory=player.getInventory();int slot=inventory.selected;
            if(slot<0||slot>=9||!inventory.getItem(slot).isEmpty())return InteractionResult.FAIL;
            var stack=new ItemStack(state.getBlock(),1);
            // BlockItem already persists the original item's implicit components in the
            // block entity. Never copy world identity/NBT or a stale folded state back.
            stack.applyComponents(be.collectComponents());
            stack.remove(DataComponents.BLOCK_ENTITY_DATA);
            stack.set(DataComponents.BLOCK_STATE,BlockItemStateProperties.EMPTY.with(FoldingStoolBlock.FOLDED,true));
            stack.setCount(1);
            // Empty-handed use guarantees a free selected slot even if every other slot
            // is full. Reserve it before world callbacks; do not merge, discard, or drop
            // overflow, and transfer exactly one in both survival and creative modes.
            inventory.setItem(slot,stack);
            boolean removed=false;
            try {
                removed=level.removeBlock(pos,false)&&level.getBlockEntity(pos)!=be;
                if(!removed)return InteractionResult.FAIL;
            } finally {
                if(!removed&&inventory.getItem(slot)==stack)inventory.setItem(slot,ItemStack.EMPTY);
            }
            inventory.setChanged();player.containerMenu.broadcastChanges();
            return InteractionResult.CONSUME;
        } finally { CHANGING.get().remove(id); }
    }
    private static InteractionResult message(ServerPlayer player,String key) { player.displayClientMessage(Component.translatable("message.piq_fc_arcade.furniture."+key),true);return InteractionResult.CONSUME; }
    static boolean breakBench(Level level,BlockPos pos,BlockState state,Player player,boolean harvest,FluidState fluid) {
        if(!(level instanceof ServerLevel server))return level.setBlock(pos,fluid.createLegacyBlock(),11);
        if(PROTECTION.get()||!server.getServer().isSameThread())return false;
        if(!(level.getBlockEntity(pos) instanceof FurnitureBlockEntity be)||!level.mayInteract(player,pos))return false;
        var data=FurnitureSavedData.get(server);var entry=data.ledger.get(be.identity());
        if(entry!=null&&!entry.closed()) {
            // Unloaded counterpart: defer player destruction, never bypass its protection or force-load it.
            var mate=other(pos,state);
            if(!level.hasChunkAt(mate)||!level.mayInteract(player,mate))return false;
            var oldMate=level.getBlockEntity(mate);
            var mateState=level.getBlockState(mate);
            PROTECTION.set(true);
            try { if(player instanceof ServerPlayer sp&&CommonHooks.fireBlockBreak(level,sp.gameMode.getGameModeForPlayer(),sp,mate,mateState).isCanceled())return false; }
            finally { PROTECTION.remove(); }
            if(level.getBlockEntity(pos)!=be||level.getBlockState(pos)!=state||level.getBlockEntity(mate)!=oldMate||level.getBlockState(mate)!=mateState
                ||!level.mayInteract(player,mate)||!level.mayInteract(player,pos))return false;
        }
        destroyBench(server,pos,be,harvest&&!player.isCreative(),null);
        if(level.getBlockEntity(pos)==be&&level.getBlockState(pos)==state)level.setBlock(pos,fluid.createLegacyBlock(),11);
        return true;
    }
    static void removed(Level level,BlockPos pos,BlockState old) {
        if(!(level instanceof ServerLevel server)||!(level.getBlockEntity(pos) instanceof FurnitureBlockEntity be))return;
        if(old.getBlock() instanceof WoodenBenchBlock) {
            if(level.restoringBlockSnapshots) { var data=FurnitureSavedData.get(server);data.ledger.cancel(be.identity());data.setDirty();return; }
            destroyBench(server,pos,be,true,pos);
        } else eject(server,pos,be.identity());
    }
    private static void destroyBench(ServerLevel level,BlockPos pos,FurnitureBlockEntity be,boolean drop,BlockPos removing) {
        var id=be.identity();if(CHANGING.get().contains(id))return;
        var data=FurnitureSavedData.get(level);var entry=data.ledger.get(id);
        if(entry==null)return;
        int part=FurnitureBlock.isAnchor(be.getBlockState())?0:1;
        if(!cell(entry,part).equals(pos)||!owns(level,entry,part))return;
        boolean first=data.ledger.claim(id);data.setDirty();eject(level,cell(entry,0),id);
        CHANGING.get().add(id);
        try {
            for(int i=0;i<2;i++) {
                var p=cell(entry,i);if(!level.hasChunkAt(p))continue;
                if(!p.equals(removing)&&owns(level,entry,i))level.removeBlock(p,false);
                data.ledger.acknowledge(id,i);
            }
            if(first&&drop)Block.popResource(level,pos,new ItemStack(be.getBlockState().getBlock()));
            data.setDirty();
        } finally { CHANGING.get().remove(id); }
    }
    static void reconcile(ServerLevel level,FurnitureBlockEntity be) {
        if(!(be.getBlockState().getBlock() instanceof WoodenBenchBlock))return;
        if(level.captureBlockSnapshots||level.restoringBlockSnapshots)return;
        var data=FurnitureSavedData.get(level);var e=data.ledger.get(be.identity());
        if(e==null) { eject(level,anchor(be.getBlockPos(),be.getBlockState()),be.identity());level.removeBlock(be.getBlockPos(),false);return; }
        if(e.closed()) { destroyBench(level,be.getBlockPos(),be,false,null);return; }
        int part=FurnitureBlock.isAnchor(be.getBlockState())?0:1;
        if(!owns(level,e,part)) { level.removeBlock(be.getBlockPos(),false);return; }
        int other=1-part;
        if(level.hasChunkAt(cell(e,other))&&!owns(level,e,other))destroyBench(level,be.getBlockPos(),be,false,null);
    }
}
