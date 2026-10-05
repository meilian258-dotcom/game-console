package cn.piq.sfchome.net;

import io.netty.buffer.Unpooled;
import java.util.Arrays;
import java.util.UUID;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SfcNetplayActivationCodecTest {
    @Test void activationKeepsEveryAuthorityFieldAndRejectsTruncation(){
        var value=new SfcHomeNetwork.NetplayActivated(31,2,(1L<<50)+32,UUID.randomUUID());
        var buffer=new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);
        try{
            SfcHomeNetwork.NetplayActivated.CODEC.encode(buffer,value);
            byte[] raw=new byte[buffer.readableBytes()];buffer.getBytes(0,raw);
            assertEquals(value,SfcHomeNetwork.NetplayActivated.CODEC.decode(buffer));assertEquals(0,buffer.readableBytes());
            for(int length=0;length<raw.length;length++){
                var cut=new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(Arrays.copyOf(raw,length)),RegistryAccess.EMPTY);
                try{assertThrows(RuntimeException.class,()->SfcHomeNetwork.NetplayActivated.CODEC.decode(cut));}finally{cut.release();}
            }
        }finally{buffer.release();}
    }
    @Test void invalidIdentityIsRejectedBeforeSending(){
        UUID ticket=UUID.randomUUID();long wire=1L<<50;
        assertThrows(IllegalArgumentException.class,()->new SfcHomeNetwork.NetplayActivated(0,1,wire,ticket));
        assertThrows(IllegalArgumentException.class,()->new SfcHomeNetwork.NetplayActivated(1,0,wire,ticket));
        assertThrows(IllegalArgumentException.class,()->new SfcHomeNetwork.NetplayActivated(1,1,wire-1,ticket));
        assertThrows(NullPointerException.class,()->new SfcHomeNetwork.NetplayActivated(1,1,wire,null));
    }
}
