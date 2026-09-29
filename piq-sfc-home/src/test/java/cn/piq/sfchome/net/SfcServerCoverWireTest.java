package cn.piq.sfchome.net;

import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SfcServerCoverWireTest {
    @Test void allIndependentCapabilityBitsAndServerCoversRoundTrip(){
        for(int caps=0;caps<64;caps++){
            var reply=new SfcHomeNetwork.Editor(UUID.randomUUID(),true,"目录","a".repeat(64),"名称","",2,true,caps,List.of(new SfcHomeNetwork.RomEntry("a".repeat(64),"test.sfc",32768)),List.of("b".repeat(64)));
            var buffer=new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);try{SfcHomeNetwork.Editor.CODEC.encode(buffer,reply);assertEquals(reply,SfcHomeNetwork.Editor.CODEC.decode(buffer));assertEquals(0,buffer.readableBytes());}finally{buffer.release();}
        }
    }
    @Test void serverCoverSelectionIsAnExplicitOperationAndCatalogRejectsUnsafeHashes(){
        var action=new SfcHomeNetwork.EditorAction(UUID.randomUUID(),SfcHomeNetwork.USE_COVER,"b".repeat(64),"",0,0,new byte[0]);
        var buffer=new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);try{SfcHomeNetwork.EditorAction.CODEC.encode(buffer,action);var actual=SfcHomeNetwork.EditorAction.CODEC.decode(buffer);assertEquals(action.token(),actual.token());assertEquals(action.operation(),actual.operation());assertEquals(action.hash(),actual.hash());assertArrayEquals(action.data(),actual.data());assertEquals(0,buffer.readableBytes());}finally{buffer.release();}
        for(var covers:List.of(List.of(""),List.of("../picture.png"),List.of("b".repeat(64),"b".repeat(64)),Collections.nCopies(257,"b".repeat(64))))assertThrows(IllegalArgumentException.class,()->new SfcHomeNetwork.Editor(UUID.randomUUID(),true,"","","","",2,false,63,List.of(),covers));
    }
}
