package cn.piq.computer.world;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.shapes.CollisionContext;

/** Single-cell placement uses vanilla's complete placement/protection transaction. */
public final class ComputerBlockItem extends BlockItem {
    public ComputerBlockItem(Block b,Properties p){super(b,p);}
    @Override public InteractionResult place(BlockPlaceContext c){if(c.getItemInHand().has(DataComponents.BLOCK_ENTITY_DATA)||c.getItemInHand().has(DataComponents.BLOCK_STATE))return InteractionResult.FAIL;return super.place(c);}
    private static boolean canPlace(BlockPlaceContext c,BlockPos p,BlockState state){
        var l=c.getLevel();var who=c.getPlayer();var at=BlockPlaceContext.at(c,p,c.getClickedFace());
        return l.hasChunkAt(p)&&!l.isOutsideBuildHeight(p)&&l.getWorldBorder().isWithinBounds(p)&&at.getClickedPos().equals(p)&&l.getBlockState(p).canBeReplaced(at)&&l.getBlockEntity(p)==null&&l.getFluidState(p).isEmpty()&&l.isUnobstructed(state,p,who==null?CollisionContext.empty():CollisionContext.of(who))&&(who==null||l.mayInteract(who,p)&&who.mayUseItemAt(p,c.getClickedFace(),c.getItemInHand()));
    }
    @Override protected boolean placeBlock(BlockPlaceContext c,BlockState s){
        return canPlace(c,c.getClickedPos(),s)&&super.placeBlock(c,s);
    }
}
