package cn.piq.fcarcade.furniture;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Source call-site regressions only; live protection hooks and game audio still need Minecraft QA. */
class FoldingStoolInteractionSourceTest {
    private static String source(String name) throws Exception {
        return Files.readString(Path.of("src/main/java/cn/piq/fcarcade/furniture",name))
            .replaceAll("(?m)//[^\\r\\n]*","").replaceAll("\\s+","");
    }
    private static String pickup() throws Exception {
        String source=source("FurnitureService.java");
        return source.substring(source.indexOf("privatestaticInteractionResultpickupStool("),source.indexOf("privatestaticInteractionResultmessage("));
    }
    @Test void shiftFoldRemainsBeforeOrdinaryFoldedPickupAndOpenSeating() throws Exception {
        String service=source("FurnitureService.java");
        int shift=service.indexOf("if(player.isShiftKeyDown())");
        int take=service.indexOf("returnpickupStool(player,pos,state,be)");
        int sit=service.indexOf("if(!validSeat(level,pos,id,0))");
        assertTrue(shift>=0&&shift<take&&take<sit);
        assertTrue(service.contains("if(!block.isBench()&&state.getValue(FoldingStoolBlock.FOLDED))returnpickupStool("));
        assertTrue(service.contains("if(CHANGING.get().contains(id))returnInteractionResult.FAIL;"));
    }
    @Test void vanillaDoorSoundsOnlyFollowSuccessfulAuthoritativeStateChange() throws Exception {
        String service=source("FurnitureService.java");
        int change=service.indexOf("if(!level.setBlock(pos,next,Block.UPDATE_ALL)||level.getBlockEntity(pos)!=be||level.getBlockState(pos)!=next)returnInteractionResult.FAIL;");
        int sound=service.indexOf("level.playSound(null,pos,next.getValue(FoldingStoolBlock.FOLDED)?SoundEvents.WOODEN_DOOR_CLOSE:SoundEvents.WOODEN_DOOR_OPEN,SoundSource.BLOCKS,.8F,1.0F)");
        assertTrue(change>=0&&change<sound);
        assertEquals(1,service.split("level.playSound\\(",-1).length-1);
        assertFalse(source("FurnitureBlock.java").contains("playSound("));
    }
    @Test void pickupHasBreakProtectionAndEventRevalidationBeforeInventoryWrite() throws Exception {
        String take=pickup();
        int event=take.indexOf("CommonHooks.fireBlockBreak(");
        int reserve=take.indexOf("inventory.setItem(slot,stack)");
        assertTrue(event>=0&&event<reserve);
        String checks=take.substring(event,reserve);
        for(String expected:new String[]{"player.level()!=level","!player.isAlive()","player.isSpectator()","player.hasDisconnected()",
                "player.isPassenger()","player.isShiftKeyDown()","!player.getMainHandItem().isEmpty()","!player.getOffhandItem().isEmpty()",
                "!level.hasChunkAt(pos)","!level.mayInteract(player,pos)","!player.canInteractWithBlock(pos,0)","player.blockActionRestricted(",
                "level.getBlockEntity(pos)!=be","!id.equals(be.identity())","level.getBlockState(pos)!=state","!seats(level,pos,id).isEmpty()"}) {
            assertTrue(checks.contains(expected),expected);
        }
        assertTrue(take.contains("finally{PROTECTION.remove();}"));
    }
    @Test void snapshotTransactionsAndReentryCannotGetAnIndependentPickup() throws Exception {
        String take=pickup();
        assertTrue(take.contains("if(level.captureBlockSnapshots||level.restoringBlockSnapshots)returnInteractionResult.FAIL;"));
        assertTrue(take.contains("||level.captureBlockSnapshots||level.restoringBlockSnapshots||"));
        assertTrue(take.contains("if(!CHANGING.get().add(id))returnInteractionResult.FAIL;"));
        assertTrue(take.contains("finally{CHANGING.get().remove(id);}"));
    }
    @Test void emptySelectedSlotIsReservedAndFailureNeverOverwritesAnotherStack() throws Exception {
        String take=pickup();
        assertTrue(take.contains("if(slot<0||slot>=9||!inventory.getItem(slot).isEmpty())returnInteractionResult.FAIL;"));
        assertTrue(take.indexOf("inventory.setItem(slot,stack)")<take.indexOf("level.removeBlock(pos,false)"));
        assertTrue(take.contains("removed=level.removeBlock(pos,false)&&level.getBlockEntity(pos)!=be;"));
        assertTrue(take.contains("if(!removed&&inventory.getItem(slot)==stack)inventory.setItem(slot,ItemStack.EMPTY);"));
        for(String forbidden:new String[]{"inventory.add(","player.drop(","popResource(","destroyBlock(","playerDestroy(","isCreative()","getAbilities().instabuild"})
            assertFalse(take.contains(forbidden),forbidden);
    }
    @Test void pickupKeepsSpeciesAndComponentsButNotWorldIdentityOrOldFoldState() throws Exception {
        String take=pickup();
        assertTrue(take.contains("varstack=newItemStack(state.getBlock(),1);"));
        assertTrue(take.contains("stack.applyComponents(be.collectComponents());"));
        assertTrue(take.contains("stack.remove(DataComponents.BLOCK_ENTITY_DATA);"));
        assertTrue(take.contains("stack.set(DataComponents.BLOCK_STATE,BlockItemStateProperties.EMPTY.with(FoldingStoolBlock.FOLDED,true));"));
        assertTrue(take.indexOf("stack.setCount(1);")>take.indexOf("stack.applyComponents("));
        assertFalse(take.contains("saveToItem("));
        assertFalse(take.contains("DataComponents.ATTRIBUTE_MODIFIERS"));
    }
    @Test void foldedStoolGetsOnlyMainhandWoodSwordStrengthWithoutReplacingPlacement() throws Exception {
        String item=source("FurnitureBlockItem.java");
        assertTrue(item.contains("classFurnitureBlockItemextendsBlockItem"));
        assertTrue(item.contains("getDefaultAttributeModifiers(ItemStackstack)"));
        assertTrue(item.contains("getBlock()instanceofFoldingStoolBlock&&state!=null&&Boolean.TRUE.equals(state.get(FoldingStoolBlock.FOLDED))"));
        assertTrue(item.contains("?FOLDED_STOOL_ATTRIBUTES:super.getDefaultAttributeModifiers(stack)"));
        assertTrue(item.contains("Attributes.ATTACK_DAMAGE,newAttributeModifier(Item.BASE_ATTACK_DAMAGE_ID,3.0,AttributeModifier.Operation.ADD_VALUE),EquipmentSlotGroup.MAINHAND"));
        assertTrue(item.contains("Attributes.ATTACK_SPEED,newAttributeModifier(Item.BASE_ATTACK_SPEED_ID,-2.4,AttributeModifier.Operation.ADD_VALUE),EquipmentSlotGroup.MAINHAND"));
        assertTrue(item.contains("returnsuper.place(context);"));
        for(String forbidden:new String[]{"hurtEnemy(","mineBlock(","hurtAndBreak(","SWORD_SWEEP","stack.set(DataComponents.ATTRIBUTE_MODIFIERS"})
            assertFalse(item.contains(forbidden),forbidden);
    }
    @Test void allElevenStoolLootTablesStillCopyFoldedWithoutChangingItemCounts() throws Exception {
        for(WoodSpecies wood:WoodSpecies.values()) {
            String loot=Files.readString(Path.of("src/main/resources/data/piq_fc_arcade/loot_table/blocks/furniture",wood.id()+"_stool.json"));
            assertTrue(loot.contains("piq_fc_arcade:furniture/"+wood.id()+"_stool"));
            assertTrue(loot.contains("minecraft:copy_state"));
            assertTrue(loot.contains("\"properties\":[\"folded\"]"));
        }
        String registry=source("FurnitureRegistry.java");
        assertFalse(registry.contains("stacksTo("));
        assertFalse(registry.contains("durability("));
    }
}
