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
}
