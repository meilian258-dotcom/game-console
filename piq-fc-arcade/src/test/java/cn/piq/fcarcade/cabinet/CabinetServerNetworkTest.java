package cn.piq.fcarcade.cabinet;

import io.netty.buffer.Unpooled;
import java.util.UUID;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CabinetServerNetworkTest {
    private static RegistryFriendlyByteBuf buffer(){return new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);}
    private static <T> void roundTrip(StreamCodec<RegistryFriendlyByteBuf,T> codec,T value){
        var b=buffer();try{
            codec.encode(b,value);int size=b.readableBytes();assertEquals(value,codec.decode(b));assertEquals(0,b.readableBytes());
            for(int length=0;length<size;length++){
                var partial=buffer();try{partial.writeBytes(b,0,length);assertThrows(RuntimeException.class,()->codec.decode(partial));}finally{partial.release();}
            }
        }finally{b.release();}
    }
    @Test void readsChangesPushesAndRepliesRoundTripAndRejectEveryTruncation(){
        for(boolean immediate:new boolean[]{false,true})for(int seconds:new int[]{0,60,3600})for(int range:new int[]{1,16,128}){
            var r=new CabinetServerRules(immediate,seconds,range);var nonce=UUID.randomUUID();
            for(int hand=0;hand<2;hand++)for(boolean save:new boolean[]{false,true})roundTrip(CabinetServerNetwork.Request.CODEC,new CabinetServerNetwork.Request(nonce,hand,save,3,r));
            roundTrip(CabinetServerNetwork.State.CODEC,new CabinetServerNetwork.State(nonce,4,r,true,"已保存"));
            roundTrip(CabinetServerNetwork.State.CODEC,new CabinetServerNetwork.State(CabinetServerNetwork.PUSH,4,r,false,""));
        }
    }
    @Test void invalidRequestsFailBeforeWorldMutation(){
        var id=UUID.randomUUID();var rules=CabinetServerRules.DEFAULT;
        assertThrows(IllegalArgumentException.class,()->new CabinetServerNetwork.Request(id,2,true,1,rules));
        assertThrows(IllegalArgumentException.class,()->new CabinetServerNetwork.Request(id,0,true,-1,rules));
        assertThrows(IllegalArgumentException.class,()->new CabinetServerNetwork.Request(CabinetServerNetwork.PUSH,0,true,1,rules));
        assertThrows(IllegalArgumentException.class,()->new CabinetServerNetwork.State(id,0,rules,true,""));
        assertThrows(IllegalArgumentException.class,()->new CabinetServerNetwork.State(id,1,rules,true,"x".repeat(193)));
        for(int[] values:new int[][]{{-1,16},{3601,16},{60,0},{60,129}}){
            var b=buffer();try{
                b.writeUUID(id);b.writeByte(0);b.writeBoolean(true);b.writeVarLong(1);b.writeBoolean(false);b.writeVarInt(values[0]);b.writeVarInt(values[1]);
                assertThrows(IllegalArgumentException.class,()->CabinetServerNetwork.Request.CODEC.decode(b));
            }finally{b.release();}
        }
    }
}
