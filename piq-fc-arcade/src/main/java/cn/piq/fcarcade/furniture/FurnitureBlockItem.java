package cn.piq.fcarcade.furniture;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

public final class FurnitureBlockItem extends BlockItem {
    // Furniture keeps its normal stack size and placement behaviour. Only the folded
    // stool is a modest improvised weapon; these modifiers never become saved item data.
    private static final ItemAttributeModifiers FOLDED_STOOL_ATTRIBUTES=ItemAttributeModifiers.builder()
        .add(Attributes.ATTACK_DAMAGE,new AttributeModifier(Item.BASE_ATTACK_DAMAGE_ID,3.0,AttributeModifier.Operation.ADD_VALUE),EquipmentSlotGroup.MAINHAND)
        .add(Attributes.ATTACK_SPEED,new AttributeModifier(Item.BASE_ATTACK_SPEED_ID,-2.4,AttributeModifier.Operation.ADD_VALUE),EquipmentSlotGroup.MAINHAND)
        .build();
    public FurnitureBlockItem(Block block,Properties properties) { super(block,properties); }
    @Override public ItemAttributeModifiers getDefaultAttributeModifiers(ItemStack stack) {
        var state=stack.get(DataComponents.BLOCK_STATE);
        return getBlock() instanceof FoldingStoolBlock&&state!=null&&Boolean.TRUE.equals(state.get(FoldingStoolBlock.FOLDED))
            ?FOLDED_STOOL_ATTRIBUTES:super.getDefaultAttributeModifiers(stack);
    }
    @Override public InteractionResult place(BlockPlaceContext context) {
        // Never import a placed bench's pair identity/half from a copied item NBT.
        if(context.getItemInHand().has(DataComponents.BLOCK_ENTITY_DATA))return InteractionResult.FAIL;
        var state=context.getItemInHand().get(DataComponents.BLOCK_STATE);
        if(state!=null && (!(getBlock() instanceof FoldingStoolBlock)||state.properties().size()!=1
                ||state.get(FoldingStoolBlock.FOLDED)==null))return InteractionResult.FAIL;
        return super.place(context);
    }
    @Override protected boolean placeBlock(BlockPlaceContext context,BlockState state) {
        return state.getBlock() instanceof WoodenBenchBlock?FurnitureService.placeBench(context,state):super.placeBlock(context,state);
    }
}
