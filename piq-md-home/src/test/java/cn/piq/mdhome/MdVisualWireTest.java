// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.mdhome;

import io.netty.buffer.Unpooled;
import net.minecraft.core.*;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MdVisualWireTest {
    private static RegistryFriendlyByteBuf buffer(){return new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);}
    private static MdPublicNetwork.Visual packet(int port,long seq,int mask,int pulse){return new MdPublicNetwork.Visual(ResourceLocation.parse("minecraft:overworld"),new BlockPos(-5,9,10),UUID.randomUUID(),7,UUID.randomUUID(),UUID.randomUUID(),port,seq,mask,pulse,false);}
    @Test void resetIsExplicitAndRequiresNeutralMasks(){
        var p=packet(1,3,0,0);
        var reset=new MdPublicNetwork.Visual(p.dimension(),p.console(),p.hardware(),p.wire(),p.player(),p.loan(),p.port(),p.sequence(),0,0,true);
        var b=buffer();try{MdPublicNetwork.Visual.CODEC.encode(b,reset);assertEquals(reset,MdPublicNetwork.Visual.CODEC.decode(b));}finally{b.release();}
        assertFalse(p.reset());assertTrue(reset.reset());
        assertThrows(IllegalArgumentException.class,()->new MdPublicNetwork.Visual(p.dimension(),p.console(),p.hardware(),p.wire(),p.player(),p.loan(),p.port(),p.sequence(),1,0,true));
        assertThrows(IllegalArgumentException.class,()->new MdPublicNetwork.Visual(p.dimension(),p.console(),p.hardware(),p.wire(),p.player(),p.loan(),p.port(),p.sequence(),0,1,true));
    }
    @Test void roundTripsBothPortsMasksPulsesAndExactIdentities(){
        for(int port=0;port<2;port++)for(int bit=0;bit<12;bit++){
            var expected=packet(port,Long.MAX_VALUE,1<<bit,4095);var b=buffer();
            try{MdPublicNetwork.Visual.CODEC.encode(b,expected);assertTrue(b.readableBytes()<256);assertEquals(expected,MdPublicNetwork.Visual.CODEC.decode(b));assertEquals(0,b.readableBytes());}finally{b.release();}
        }
    }
    @Test void invalidMasksPortsAndSequencesReject(){
        assertThrows(IllegalArgumentException.class,()->packet(2,1,0,0));
        assertThrows(IllegalArgumentException.class,()->packet(0,0,0,0));
        assertThrows(IllegalArgumentException.class,()->packet(0,1,4096,0));
        assertThrows(IllegalArgumentException.class,()->packet(0,1,0,-1));
    }
    @Test void everyTruncationRejects(){
        var b=buffer();try{
            MdPublicNetwork.Visual.CODEC.encode(b,packet(1,20,4095,4095));byte[] data=new byte[b.readableBytes()];b.readBytes(data);
            for(int size=0;size<data.length;size++){var cut=buffer();try{cut.writeBytes(data,0,size);assertThrows(RuntimeException.class,()->MdPublicNetwork.Visual.CODEC.decode(cut));}finally{cut.release();}}
        }finally{b.release();}
    }
    @Test void visualRegisteredOnlyServerToClientAndAfterAuthorityGate() throws Exception {
        var root=java.nio.file.Path.of("src/main/java/cn/piq/mdhome");
        String network=java.nio.file.Files.readString(root.resolve("MdPublicNetwork.java"));
        assertTrue(network.contains("create(event,\"md-public-3\")"));assertFalse(network.contains("create(event,\"md-public-2\")"));assertTrue(network.contains(".playToClient(Visual.TYPE,Visual.CODEC"));
        assertTrue(network.contains(".playToClient(Activated.TYPE,Activated.CODEC"));assertTrue(network.contains(".playToClient(PrivateActivated.TYPE,PrivateActivated.CODEC"));
        assertTrue(network.contains(".playToServer(Closed.TYPE,Closed.CODEC"));assertTrue(network.contains(".playToServer(PrivateFinished.TYPE,PrivateFinished.CODEC"));
        assertFalse(network.contains(".playToServer(Activated.TYPE"));assertFalse(network.contains(".playToServer(PrivateActivated.TYPE"));
        assertFalse(network.contains(".playToServer(Visual.TYPE"));assertFalse(network.contains(".playBidirectional(Visual.TYPE"));
        String server=java.nio.file.Files.readString(root.resolve("MdPublicServer.java"));
        assertTrue(server.indexOf("if(accepted==MdPublicInputGate.Result.REJECT)")<server.indexOf("seat.visual.offer(mask)"));
        assertTrue(server.indexOf("int mask=s.console.authorized")<server.indexOf("seat.visual.offer(mask)"));
        assertTrue(server.contains("viewer.getChunkTrackingView().contains(operator.chunkPosition())"));
        assertTrue(server.contains("if(++sent>=64)break"));
        assertFalse(server.contains("getPlayers().forEach"));
    }
}
