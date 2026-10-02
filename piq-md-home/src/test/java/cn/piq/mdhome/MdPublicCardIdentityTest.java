// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.mdhome;

import cn.piq.fcarcade.home.content.ContentCardData;
import cn.piq.fcarcade.home.content.ContentCardStore;
import net.minecraft.SharedConstants;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class MdPublicCardIdentityTest {
    @BeforeAll static void bootstrap(){
        if(net.neoforged.fml.loading.LoadingModList.get()==null)
            net.neoforged.fml.loading.LoadingModList.of(java.util.List.of(),java.util.List.of(),java.util.List.of(),java.util.List.of(),java.util.Map.of());
        SharedConstants.tryDetectVersion();Bootstrap.bootStrap();
    }
    private static ItemStack card(){var stack=new ItemStack(Items.PAPER);ContentCardData.write(stack,ResourceLocation.parse("piq_md_home:md"),new ContentCardStore.Entry("a".repeat(64),"test.md",1024),"test");ContentCardData.ensureId(stack);return stack;}
    @Test void exactPhysicalCardAndSingleCountRequired(){var card=card();var snapshot=card.copy();assertTrue(MdPublicServer.cardUnchanged(card,snapshot,card));assertFalse(MdPublicServer.cardUnchanged(card,snapshot,card.copy()));card.setCount(2);assertFalse(MdPublicServer.cardUnchanged(card,snapshot,card));card.setCount(0);assertFalse(MdPublicServer.cardUnchanged(card,snapshot,card));}
    @Test void inPlaceSaveModeAndPlayersChangesRevoke(){var card=card();var snapshot=card.copy();ContentCardData.saveMode(card,1);assertFalse(MdPublicServer.cardUnchanged(card,snapshot,card));snapshot=card.copy();ContentCardData.players(card,2);assertFalse(MdPublicServer.cardUnchanged(card,snapshot,card));}
    @Test void inPlacePhysicalUuidChangesRevokeWithoutRomChange(){var card=card();var snapshot=card.copy();var entry=ContentCardData.read(card,ResourceLocation.parse("piq_md_home:md"));var tag=card.get(DataComponents.CUSTOM_DATA).copyTag();tag.getCompound("GameConsoleContentCard").putUUID("CardId",UUID.randomUUID());card.set(DataComponents.CUSTOM_DATA,CustomData.of(tag));assertEquals(entry,ContentCardData.read(card,ResourceLocation.parse("piq_md_home:md")));assertFalse(MdPublicServer.cardUnchanged(card,snapshot,card));}
}
