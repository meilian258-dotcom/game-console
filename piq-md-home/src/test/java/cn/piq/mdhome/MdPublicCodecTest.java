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
    private static WatchNetwork.Start display(){return new WatchNetwork.Start(1,UUID.randomUUID(),new WatchDescriptor(ResourceLocation.fromNamespaceAndPath("piq_md_home","md"),UUID.randomUUID(),UUID.randomUUID(),ResourceLocation.parse("minecraft:overworld"),new WatchAnchor(BlockPos.ZERO,UUID.randomUUID()),UUID.randomUUID(),List.of(new WatchAnchor(new BlockPos(2,0,0),UUID.randomUUID()))));}
    @Test void hostGrantRoundTripsExactSaveAndDisplayIdentities(){var p=new MdPublicNetwork.Start(123,UUID.randomUUID(),UUID.randomUUID(),display(),true,true,"a".repeat(64),"b".repeat(64));var b=new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);try{MdPublicNetwork.Start.CODEC.encode(b,p);assertEquals(p,MdPublicNetwork.Start.CODEC.decode(b));assertEquals(0,b.readableBytes());}finally{b.release();}}
    @Test void seatRoundTripsBothPortsAndCannotForgeThird(){for(int port=0;port<2;port++){var p=new MdPublicNetwork.Seat(5,display(),port,UUID.randomUUID());var b=new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);try{MdPublicNetwork.Seat.CODEC.encode(b,p);assertEquals(p,MdPublicNetwork.Seat.CODEC.decode(b));}finally{b.release();}}assertThrows(IllegalArgumentException.class,()->new MdPublicNetwork.Seat(2,display(),2,UUID.randomUUID()));}
    @Test void inputRoundTripsAndMalformedMaskFailsClosed(){var p=new MdPublicNetwork.Input(99,1,UUID.randomUUID(),Long.MAX_VALUE,4095);var b=new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);try{MdPublicNetwork.Input.CODEC.encode(b,p);assertEquals(p,MdPublicNetwork.Input.CODEC.decode(b));}finally{b.release();}assertThrows(IllegalArgumentException.class,()->new MdPublicNetwork.Input(99,1,UUID.randomUUID(),1,4096));}
}
