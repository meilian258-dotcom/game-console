package cn.piq.fcarcade.home;

import cn.piq.fcarcade.home.content.*;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.CustomData;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ContentCardDataTest {
    private static final ResourceLocation SYSTEM=ResourceLocation.parse("example:md");
    private static final ContentCardStore.Entry GAME=new ContentCardStore.Entry("a".repeat(64),"game.md",1024);
    @BeforeAll static void bootstrap(){
        if(net.neoforged.fml.loading.LoadingModList.get()==null)
            net.neoforged.fml.loading.LoadingModList.of(List.of(),List.of(),List.of(),List.of(),Map.of());
        SharedConstants.tryDetectVersion();Bootstrap.bootStrap();
    }
    @Test void renamePreservesContentIdentityAndUnrelatedItemMetadata(){
        var stack=new ItemStack(Items.PAPER);var other=new CompoundTag();other.putString("OtherMod","preserved");
        stack.set(DataComponents.CUSTOM_DATA,CustomData.of(other));
        ContentCardData.write(stack,SYSTEM,GAME,"旧名称");
        assertEquals(GAME,ContentCardData.read(stack,SYSTEM));
        ContentCardData.write(stack,SYSTEM,ContentCardData.read(stack,SYSTEM),ContentCardWorkbench.title("新名称"));
        assertEquals(GAME,ContentCardData.read(stack,SYSTEM));assertEquals("新名称",ContentCardData.title(stack));
        var nbt=stack.get(DataComponents.CUSTOM_DATA).copyTag();assertEquals("preserved",nbt.getString("OtherMod"));
        assertEquals(1,nbt.getCompound("GameConsoleContentCard").getInt("Version"));
        assertNull(ContentCardData.read(stack,ResourceLocation.parse("example:gba")));
    }
    @Test void invalidRenameAndStackedCardsDoNotChangeExistingData(){
        var stack=new ItemStack(Items.PAPER);ContentCardData.write(stack,SYSTEM,GAME,"原卡");var original=stack.copy();
        assertThrows(IllegalArgumentException.class,()->ContentCardData.write(stack,SYSTEM,GAME,"bad\nname"));
        assertTrue(ItemStack.matches(original,stack));
        stack.setCount(2);assertThrows(IllegalArgumentException.class,()->ContentCardData.write(stack,SYSTEM,GAME,"新卡"));
        assertEquals("原卡",ContentCardData.title(stack));assertEquals(GAME,ContentCardData.read(stack,SYSTEM));
    }
    @Test void coverAndSavePreferenceSurviveRenameAndReplacingRom(){
        var stack=new ItemStack(Items.PAPER);assertEquals(2,ContentCardData.saveMode(stack));
        ContentCardData.cover(stack,"b".repeat(64));ContentCardData.saveMode(stack,0);
        ContentCardData.write(stack,SYSTEM,GAME,"第一款");
        ContentCardData.write(stack,SYSTEM,GAME,"改名");
        var second=new ContentCardStore.Entry("c".repeat(64),"second.md",2048);
        ContentCardData.write(stack,SYSTEM,second,"第二款");
        assertEquals(second,ContentCardData.read(stack,SYSTEM));assertEquals("b".repeat(64),ContentCardData.cover(stack));
        assertEquals(0,ContentCardData.saveMode(stack));
        ContentCardData.saveMode(stack,2);ContentCardData.cover(stack,"");
        assertEquals(2,ContentCardData.saveMode(stack));assertEquals("",ContentCardData.cover(stack));
        assertEquals(second,ContentCardData.read(stack,SYSTEM));
    }
    @Test void unsupportedCardSaveModeOrBadCoverCannotMutateExistingMetadata(){
        var stack=new ItemStack(Items.PAPER);ContentCardData.write(stack,SYSTEM,GAME,"旧卡");var before=stack.copy();
        for(int mode:new int[]{-1,1,3,Integer.MAX_VALUE})assertThrows(IllegalArgumentException.class,()->ContentCardData.saveMode(stack,mode));
        assertThrows(IllegalArgumentException.class,()->ContentCardData.cover(stack,"../wrong"));
        assertTrue(ItemStack.matches(before,stack));
        var nbt=stack.get(DataComponents.CUSTOM_DATA).copyTag();var card=nbt.getCompound("GameConsoleContentCard");
        card.remove("SaveMode");card.putString("Cover","malformed");stack.set(DataComponents.CUSTOM_DATA,CustomData.of(nbt));
        assertEquals(2,ContentCardData.saveMode(stack));assertEquals("",ContentCardData.cover(stack));
        assertEquals(GAME,ContentCardData.read(stack,SYSTEM));
    }
}
