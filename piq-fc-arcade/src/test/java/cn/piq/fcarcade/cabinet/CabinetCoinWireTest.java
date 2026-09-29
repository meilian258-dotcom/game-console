package cn.piq.fcarcade.cabinet;

import io.netty.buffer.Unpooled;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CabinetCoinWireTest {
    private static final UUID ROOM=UUID.randomUUID(),MEMBER=UUID.randomUUID(),TOKEN=UUID.randomUUID();
    private static final ResourceLocation MAME=ResourceLocation.parse(CabinetCoinPolicy.BACKEND);
    private static final CabinetTarget TARGET=new CabinetTarget(ResourceLocation.parse("minecraft:overworld"),new BlockPos(1,2,3),UUID.randomUUID(),true);
    private static <T> void roundTrip(StreamCodec<RegistryFriendlyByteBuf,T> codec,T value){
        var b=new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);
        try{codec.encode(b,value);byte[] bytes=new byte[b.readableBytes()];b.getBytes(0,bytes);assertEquals(value,codec.decode(b));assertEquals(0,b.readableBytes());
            for(int length=0;length<bytes.length;length++){var truncated=new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(java.util.Arrays.copyOf(bytes,length)),RegistryAccess.EMPTY);
                try{assertThrows(RuntimeException.class,()->codec.decode(truncated));}finally{truncated.release();}}
        }finally{b.release();}
    }
    @Test void allCoinPacketsRoundTripAndEveryTruncationFails(){
        for(boolean paid:new boolean[]{false,true})for(CabinetSyncMode mode:CabinetSyncMode.values()){
            roundTrip(CabinetRoomNetwork.Assignment.CODEC,new CabinetRoomNetwork.Assignment(ROOM,MEMBER,MEMBER,0,2,TARGET,MAME,TARGET,null,mode,paid));
            roundTrip(CabinetSyncNetwork.Setting.CODEC,new CabinetSyncNetwork.Setting(TARGET,MAME,mode.ordinal(),true,true,"已核对",true,true,"可托管",TOKEN,paid,true));
        }
        for(int port=0;port<4;port++)roundTrip(CabinetRoomNetwork.Coin.CODEC,new CabinetRoomNetwork.Coin(ROOM,MEMBER,TOKEN,port,Long.MAX_VALUE));
        for(boolean supported:new boolean[]{false,true})roundTrip(CabinetRoomNetwork.Ready.CODEC,new CabinetRoomNetwork.Ready(ROOM,MEMBER,supported));
        assertFalse(new CabinetRoomNetwork.Ready(ROOM,MEMBER).coinReleaseSupported());
        for(int coin=-1;coin<=1;coin++)roundTrip(CabinetSyncNetwork.Mode.CODEC,new CabinetSyncNetwork.Mode(TARGET,MAME,-1,TOKEN,coin));
    }
    @Test void malformedCoinAuthorityAndPortsFailClosed(){
        assertThrows(IllegalArgumentException.class,()->new CabinetRoomNetwork.Coin(ROOM,MEMBER,TOKEN,4,1));
        assertThrows(IllegalArgumentException.class,()->new CabinetRoomNetwork.Coin(ROOM,MEMBER,TOKEN,0,0));
        assertThrows(IllegalArgumentException.class,()->new CabinetSyncNetwork.Mode(TARGET,MAME,1,TOKEN,1));
        assertThrows(IllegalArgumentException.class,()->new CabinetSyncNetwork.Mode(TARGET,MAME,-1,TOKEN,2));
        assertThrows(IllegalArgumentException.class,()->new CabinetRoomNetwork.Assignment(ROOM,MEMBER,MEMBER,0,2,TARGET,CabinetBackends.NES,TARGET,null,CabinetSyncMode.MEDIA,true));
        assertThrows(IllegalArgumentException.class,()->new CabinetSyncNetwork.Setting(TARGET,MAME,0,true,true,"",false,true,"",null,true,true));
    }
}
