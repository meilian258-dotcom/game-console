package cn.piq.fcarcade.config;

import cn.piq.fcarcade.access.PlayerContentAccess;
import io.netty.buffer.Unpooled;
import java.util.UUID;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AdminTerminalNetworkTest {
    private static RegistryFriendlyByteBuf buffer(){return new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);}
    @Test void allOptionsAndAllowedCommandsRoundTrip() {
        for(int mask=0;mask<=31;mask++) {
            assertEquals(mask,AdminTerminalService.mask(AdminTerminalService.options(mask)));
            var b=buffer();
            try {
                var id=UUID.randomUUID();
                var q=new AdminTerminalNetwork.Request(id,mask%2,AdminTerminalPolicy.ACCESS,mask,mask,mask%4-1,4+mask);
                AdminTerminalNetwork.Request.CODEC.encode(b,q);assertEquals(q,AdminTerminalNetwork.Request.CODEC.decode(b));
                var s=new AdminTerminalNetwork.State(id,true,mask,mask%4-1,4+mask,mask%8,"已保存");
                AdminTerminalNetwork.State.CODEC.encode(b,s);assertEquals(s,AdminTerminalNetwork.State.CODEC.decode(b));
                assertEquals(0,b.readableBytes());
            } finally {b.release();}
        }
    }
    @Test void invalidHandActionBoundsAndMaskFailBeforeMutation() {
        UUID id=UUID.randomUUID();
        assertThrows(IllegalArgumentException.class,()->new AdminTerminalNetwork.Request(id,2,0,0,0,-1,16));
        assertThrows(IllegalArgumentException.class,()->new AdminTerminalNetwork.Request(id,0,100,0,0,-1,16));
        assertThrows(IllegalArgumentException.class,()->new AdminTerminalNetwork.Request(id,0,0,0,32,-1,16));
        assertThrows(IllegalArgumentException.class,()->new AdminTerminalNetwork.Request(id,0,0,0,0,3,16));
        assertThrows(IllegalArgumentException.class,()->new AdminTerminalNetwork.Request(id,0,0,0,0,-1,65));
        assertThrows(IllegalArgumentException.class,()->new AdminTerminalNetwork.State(id,true,0,-1,16,8,""));
        assertThrows(IllegalArgumentException.class,()->new AdminTerminalNetwork.State(id,true,0,-1,16,7,"x".repeat(193)));
    }
    @Test void malformedWireCannotBypassRecordValidation() {
        var b=buffer();try {
            b.writeUUID(UUID.randomUUID());b.writeByte(0);b.writeVarInt(AdminTerminalPolicy.ACCESS);b.writeInt(32);
            b.writeByte(0);b.writeInt(-1);b.writeVarInt(16);b.writeVarInt(0);
            assertThrows(IllegalArgumentException.class,()->AdminTerminalNetwork.Request.CODEC.decode(b));
        }finally{b.release();}
    }
}
