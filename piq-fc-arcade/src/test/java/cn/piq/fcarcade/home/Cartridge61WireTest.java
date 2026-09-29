package cn.piq.fcarcade.home;

import cn.piq.fcarcade.*;
import cn.piq.fcarcade.config.*;
import cn.piq.fcarcade.rom.RomSaveMode;
import io.netty.buffer.Unpooled;
import net.minecraft.core.*;
import net.minecraft.network.RegistryFriendlyByteBuf;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class Cartridge61WireTest {
    private static RegistryFriendlyByteBuf buffer(){return new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);}
    @Test void physicalCardModeIsIndependentOfCatalogOnActualWire(){
        for(var mode:RomSaveMode.values()){
            var b=buffer();try{
                var reply=new CartridgeNetwork.Reply(CartridgeNetwork.STATUS,CartridgeNetwork.NO_TARGET,"","a".repeat(64),"","game","",0,0,new byte[0],List.of(),0,List.of(),mode);
                CartridgeNetwork.Reply.CODEC.encode(b,reply);var decoded=CartridgeNetwork.Reply.CODEC.decode(b);
                assertEquals(mode,decoded.cardSaveMode());assertTrue(decoded.catalog().isEmpty());assertEquals(0,b.readableBytes());
            }finally{b.release();}
        }
    }
    @Test void cardContinuePromptAndPersonalPickerHaveDifferentWireFlag(){
        var slots=new ArcadeSaveSlotsPayload(BlockPos.ZERO,"a".repeat(64),"game",java.util.stream.IntStream.rangeClosed(1,3).mapToObj(i->new ArcadeSaveSlotEntry(i,false,"slot "+i,1,0,"","")).toList());
        for(boolean card:new boolean[]{false,true})for(boolean gun:new boolean[]{false,true}){
            var b=buffer();try{
                var packet=new ArcadeHomeSaveSlotsPayload(UUID.randomUUID(),gun,slots,card);
                ArcadeHomeSaveSlotsPayload.STREAM_CODEC.encode(b,packet);assertEquals(packet,ArcadeHomeSaveSlotsPayload.STREAM_CODEC.decode(b));assertEquals(0,b.readableBytes());
            }finally{b.release();}
        }
    }
    @Test void retentionUsesActualBoundedWireAndExplicitOff(){
        for(int days:new int[]{0,1,30,3650}){
            assertTrue(AdminTerminalPolicy.valid(AdminTerminalPolicy.RETENTION,days));
            var b=buffer();try{
                var packet=new AdminTerminalNetwork.Request(UUID.randomUUID(),0,AdminTerminalPolicy.RETENTION,days,0,-1,16,days);
                AdminTerminalNetwork.Request.CODEC.encode(b,packet);assertEquals(packet,AdminTerminalNetwork.Request.CODEC.decode(b));
                var state=new AdminTerminalNetwork.State(UUID.randomUUID(),true,0,-1,16,7,"",days);
                AdminTerminalNetwork.State.CODEC.encode(b,state);assertEquals(state,AdminTerminalNetwork.State.CODEC.decode(b));assertEquals(0,b.readableBytes());
            }finally{b.release();}
        }
        assertFalse(AdminTerminalPolicy.valid(AdminTerminalPolicy.RETENTION,-1));assertFalse(AdminTerminalPolicy.valid(AdminTerminalPolicy.RETENTION,3651));
        assertThrows(IllegalArgumentException.class,()->new AdminTerminalNetwork.Request(UUID.randomUUID(),0,AdminTerminalPolicy.RETENTION,0,0,-1,16,-1));
    }
}
