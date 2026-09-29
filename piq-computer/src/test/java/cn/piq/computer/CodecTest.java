package cn.piq.computer;
import cn.piq.computer.net.ComputerNetwork.*;
import io.netty.buffer.Unpooled;
import java.util.UUID;
import net.minecraft.core.*;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class CodecTest {
    @Test void openRoundTrip(){var m=new Open(ResourceLocation.parse("minecraft:overworld"),new BlockPos(-100,-59,40),UUID.randomUUID(),true,UUID.randomUUID());var b=new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);try{Open.CODEC.encode(b,m);assertEquals(m,Open.CODEC.decode(b));assertEquals(0,b.readableBytes());}finally{b.release();}}
    @Test void commandRoundTrip(){for(int kind=0;kind<=6;kind++){var m=new Command(ResourceLocation.parse("minecraft:overworld"),new BlockPos(-100,-59,40),UUID.randomUUID(),UUID.randomUUID(),42,kind,259,639,479,7);var b=new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);try{Command.CODEC.encode(b,m);assertEquals(m,Command.CODEC.decode(b));assertEquals(0,b.readableBytes());}finally{b.release();}}}
    @Test void allTruncationsRejected(){var m=new Command(ResourceLocation.parse("minecraft:overworld"),BlockPos.ZERO,UUID.randomUUID(),UUID.randomUUID(),1,0,0,0,0,0);var b=new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);try{Command.CODEC.encode(b,m);byte[] data=new byte[b.readableBytes()];b.readBytes(data);for(int i=0;i<data.length;i++){var cut=new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(java.util.Arrays.copyOf(data,i)),RegistryAccess.EMPTY);try{assertThrows(RuntimeException.class,()->Command.CODEC.decode(cut));}finally{cut.release();}}}finally{b.release();}}
}
