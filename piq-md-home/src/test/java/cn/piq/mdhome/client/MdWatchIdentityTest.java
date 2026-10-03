package cn.piq.mdhome.client;

import cn.piq.fcarcade.cabinet.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MdWatchIdentityTest {
    private WatchDescriptor descriptor(){return new WatchDescriptor(ResourceLocation.parse("piq_md_home:md"),UUID.randomUUID(),UUID.randomUUID(),ResourceLocation.parse("minecraft:overworld"),new WatchAnchor(BlockPos.ZERO,UUID.randomUUID()),UUID.randomUUID(),List.of(new WatchAnchor(new BlockPos(2,0,0),UUID.randomUUID())));}
    @Test void onlyTheExactControlledOrClosingSourceIsExcluded(){
        var local=descriptor();assertTrue(MdPublicClient.ownsDisplay(local,local,true));
        assertFalse(MdPublicClient.ownsDisplay(descriptor(),local,true));assertFalse(MdPublicClient.ownsDisplay(local,local,false));
        assertFalse(MdPublicClient.ownsDisplay(local,null,true));assertFalse(MdPublicClient.ownsDisplay(null,local,true));
    }
    @Test void oldGenerationOfSameTelevisionCannotBlockNewWatch(){
        var local=descriptor();var restarted=new WatchDescriptor(local.provider(),UUID.randomUUID(),UUID.randomUUID(),local.dimension(),local.origin(),local.link(),local.screens());
        assertFalse(MdPublicClient.ownsDisplay(restarted,local,true));
    }
}
