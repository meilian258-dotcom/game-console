// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.mdhome;
import cn.piq.fcarcade.cabinet.*;
import io.netty.buffer.Unpooled;
import net.minecraft.core.*;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MdPublicCodecTest {
    @Test void activationAndCloseBindExactGeneration(){var b=new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);try{var activation=new MdPublicNetwork.Activated(123);MdPublicNetwork.Activated.CODEC.encode(b,activation);assertEquals(activation,MdPublicNetwork.Activated.CODEC.decode(b));var close=new MdPublicNetwork.Closed(123,false);MdPublicNetwork.Closed.CODEC.encode(b,close);assertEquals(close,MdPublicNetwork.Closed.CODEC.decode(b));assertEquals(0,b.readableBytes());}finally{b.release();}assertThrows(IllegalArgumentException.class,()->new MdPublicNetwork.Activated(0));assertThrows(IllegalArgumentException.class,()->new MdPublicNetwork.Closed(-1,true));}
    @Test void privateCompletionIsDistinctFromPublicPersistence(){var token=UUID.randomUUID();var b=new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);try{var activate=new MdPublicNetwork.PrivateActivated(token);MdPublicNetwork.PrivateActivated.CODEC.encode(b,activate);assertEquals(activate,MdPublicNetwork.PrivateActivated.CODEC.decode(b));var finished=new MdPublicNetwork.PrivateFinished(token,true,false);MdPublicNetwork.PrivateFinished.CODEC.encode(b,finished);assertEquals(finished,MdPublicNetwork.PrivateFinished.CODEC.decode(b));assertEquals(0,b.readableBytes());}finally{b.release();}}
    private static WatchNetwork.Start display(){return new WatchNetwork.Start(1,UUID.randomUUID(),new WatchDescriptor(ResourceLocation.fromNamespaceAndPath("piq_md_home","md"),UUID.randomUUID(),UUID.randomUUID(),ResourceLocation.parse("minecraft:overworld"),new WatchAnchor(BlockPos.ZERO,UUID.randomUUID()),UUID.randomUUID(),List.of(new WatchAnchor(new BlockPos(2,0,0),UUID.randomUUID()))));}
    @Test void hostGrantRoundTripsExactSaveAndDisplayIdentities(){var p=new MdPublicNetwork.Start(123,UUID.randomUUID(),UUID.randomUUID(),display(),true,true,"a".repeat(64),"b".repeat(64));var b=new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);try{MdPublicNetwork.Start.CODEC.encode(b,p);assertEquals(p,MdPublicNetwork.Start.CODEC.decode(b));assertEquals(0,b.readableBytes());}finally{b.release();}}
    @Test void seatRoundTripsBothPortsAndCannotForgeThird(){for(int port=0;port<2;port++){var p=new MdPublicNetwork.Seat(5,display(),port,UUID.randomUUID());var b=new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);try{MdPublicNetwork.Seat.CODEC.encode(b,p);assertEquals(p,MdPublicNetwork.Seat.CODEC.decode(b));}finally{b.release();}}assertThrows(IllegalArgumentException.class,()->new MdPublicNetwork.Seat(2,display(),2,UUID.randomUUID()));}
    @Test void inputRoundTripsAndMalformedMaskFailsClosed(){var p=new MdPublicNetwork.Input(99,1,UUID.randomUUID(),Long.MAX_VALUE,4095);var b=new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);try{MdPublicNetwork.Input.CODEC.encode(b,p);assertEquals(p,MdPublicNetwork.Input.CODEC.decode(b));}finally{b.release();}assertThrows(IllegalArgumentException.class,()->new MdPublicNetwork.Input(99,1,UUID.randomUUID(),1,4096));}
}
