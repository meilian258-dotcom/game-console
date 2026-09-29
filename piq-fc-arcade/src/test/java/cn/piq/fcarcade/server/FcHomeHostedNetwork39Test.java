package cn.piq.fcarcade.server;

import cn.piq.fcarcade.ArcadeSessionPayload;
import cn.piq.fcarcade.cabinet.CabinetRoomNetwork;
import cn.piq.fcarcade.session.*;
import cn.piq.fcarcade.server.hosted.NesServerCoreFactory;
import io.netty.buffer.Unpooled;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FcHomeHostedNetwork39Test {
    private static final UUID SOURCE=new UUID(3,4),TOKEN=new UUID(5,6),PLAYER=new UUID(7,8);
    private static ArcadeSessionPayload state(boolean home,boolean compute){return new ArcadeSessionPayload(BlockPos.ZERO,9,ArcadeMode.LOCKSTEP,ArcadeRole.SPECTATOR,0,"",16,16,100,"a".repeat(64),3,false,true,NesCoreVariant.ZAPPER_V1,home,compute,null,20);}
    @Test void computingClientCannotBeHiddenInHostedState(){assertThrows(IllegalArgumentException.class,()->new FcHomeHostedNetwork.State(state(true,true),SOURCE,TOKEN,true));}
    @Test void unrelatedLegacySessionCannotUseHostedLane(){assertThrows(IllegalArgumentException.class,()->new FcHomeHostedNetwork.State(state(false,false),SOURCE,TOKEN,false));}
    @Test void operatorApprovalIsSeparateFromComputeAuthority(){
        var payload=new FcHomeHostedNetwork.State(state(true,false),SOURCE,TOKEN,true);var buffer=new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);
        try{FcHomeHostedNetwork.State.CODEC.encode(buffer,payload);var decoded=FcHomeHostedNetwork.State.CODEC.decode(buffer);assertEquals(payload,decoded);assertTrue(decoded.operator());assertFalse(decoded.session().computeHost());assertEquals(NesCoreVariant.ZAPPER_V1,decoded.session().variant());}finally{buffer.release();}
    }
    @Test void mediaRoundTripRetainsRecipientEpochAndSourceToken(){
        var media=new CabinetRoomNetwork.Media(SOURCE,TOKEN,10,1,0,1,0,0,1,0,4,new byte[4]);var payload=new FcHomeHostedNetwork.Stream(9,3,PLAYER,media);var buffer=new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);
        try{FcHomeHostedNetwork.Stream.CODEC.encode(buffer,payload);var decoded=FcHomeHostedNetwork.Stream.CODEC.decode(buffer);assertEquals(9,decoded.session());assertEquals(3,decoded.epoch());assertEquals(PLAYER,decoded.recipient());assertEquals(SOURCE,decoded.media().room());assertEquals(TOKEN,decoded.media().hostMember());assertArrayEquals(media.data(),decoded.media().data());}finally{buffer.release();}
    }
    @Test void zeroEpochAndNegativeSessionNeverBecomeMediaAuthority(){var media=new CabinetRoomNetwork.Media(SOURCE,TOKEN,0,1,0,1,0,0,1,0,4,new byte[4]);assertThrows(IllegalArgumentException.class,()->new FcHomeHostedNetwork.Stream(-1,1,PLAYER,media));assertThrows(IllegalArgumentException.class,()->new FcHomeHostedNetwork.Stream(1,0,PLAYER,media));}
    @Test void everyNesButtonCombinationRoundTripsThroughSharedServerCore(){for(int mask=0;mask<256;mask++)assertEquals(mask,NesServerCoreFactory.nesMask(FcHomeHostedRun.retroMask(mask)));}
    @Test void staleSessionEpochTokenSourceOrRecipientCannotEnterActiveDecoder(){var media=new CabinetRoomNetwork.Media(SOURCE,TOKEN,0,1,0,1,0,0,1,0,4,new byte[4]);var stream=new FcHomeHostedNetwork.Stream(9,3,PLAYER,media);assertTrue(stream.matches(9,3,SOURCE,TOKEN,PLAYER));assertFalse(stream.matches(10,3,SOURCE,TOKEN,PLAYER));assertFalse(stream.matches(9,4,SOURCE,TOKEN,PLAYER));assertFalse(stream.matches(9,3,TOKEN,TOKEN,PLAYER));assertFalse(stream.matches(9,3,SOURCE,SOURCE,PLAYER));assertFalse(stream.matches(9,3,SOURCE,TOKEN,TOKEN));}
}
