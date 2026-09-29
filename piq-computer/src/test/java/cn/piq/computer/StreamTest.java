package cn.piq.computer;
import cn.piq.computer.stream.*;
import cn.piq.computer.net.ComputerStreamNetwork.*;
import io.netty.buffer.Unpooled;
import net.minecraft.core.*;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.io.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import static org.junit.jupiter.api.Assertions.*;

class StreamTest {
    final UUID pc=UUID.randomUUID(),session=UUID.randomUUID();
    StreamPart part(long seq,int index,int count,byte[] bytes){return new StreamPart(pc,session,seq,4,0,index,count,640,480,bytes);}
    @Test void partsBounded(){for(int size:new int[]{0,16001})assertThrows(IllegalArgumentException.class,()->part(0,0,1,new byte[size]));assertThrows(IllegalArgumentException.class,()->part(0,0,5,new byte[16000]));assertThrows(IllegalArgumentException.class,()->part(0,0,2,new byte[15000]));}
    @Test void dimensionsFixed(){assertThrows(IllegalArgumentException.class,()->new StreamPart(pc,session,0,0,0,0,1,32767,32767,new byte[3]));}
    @Test void audioBounded(){assertThrows(IllegalArgumentException.class,()->new StreamPart(pc,session,0,0,1,0,1,0,0,new byte[2401]));}
    @Test void join(){var a=new StreamAssembler(pc,session);assertNull(a.accept(part(0,0,2,new byte[16000]),0));assertEquals(16003,a.accept(part(0,1,2,new byte[3]),1).length);assertNull(a.accept(part(0,0,1,new byte[2]),2));}
    @Test void wrongSession(){assertNull(new StreamAssembler(pc,UUID.randomUUID()).accept(part(0,0,1,new byte[2]),0));}
    @Test void missingFirst(){assertNull(new StreamAssembler(pc,session).accept(part(0,1,2,new byte[3]),0));}
    @Test void timeout(){var a=new StreamAssembler(pc,session);a.accept(part(0,0,2,new byte[16000]),0);assertNull(a.accept(part(0,1,2,new byte[3]),1_100_000_000L));assertEquals(4,a.accept(part(1,0,1,new byte[4]),1_200_000_000L).length);}
    @Test void dimensionsChangeReject(){var a=new StreamAssembler(pc,session);a.accept(part(0,0,2,new byte[16000]),0);assertNull(a.accept(part(0,1,3,new byte[16000]),1));}
    @Test void budgetCeiling(){for(int tier=0;tier<3;tier++){var b=new StreamBudget(tier,0);long used=0;for(long t=0;t<10_000_000_000L;t+=10_000_000L)for(int i=0;i<100;i++)if(b.take(1000,t))used+=1000;assertTrue(used<=65536L+StreamBudget.RATES[tier]*10L);assertTrue(used>StreamBudget.RATES[tier]*9L);}}
    @Test void budgetClockReversal(){var b=new StreamBudget(0,10);assertTrue(b.take(65536,10));assertFalse(b.take(1,0));assertFalse(b.take(-1,20));assertFalse(b.take(65537,2_000_000_000L));}
    @Test void authorityCannotImpersonate(){var host=UUID.randomUUID();var a=new StreamAuthority(host,host);assertFalse(a.setAllowed(UUID.randomUUID(),true));assertFalse(a.mayControl(UUID.randomUUID()));assertTrue(a.setAllowed(host,true));assertTrue(a.mayControl(UUID.randomUUID()));a.setAllowed(host,false);assertTrue(a.mayControl(host));}
    @Test void epochsSeparateControllerFromHost(){var host=UUID.randomUUID();var a=new StreamAuthority(host,host);var other=UUID.randomUUID();a.controller(null);a.controller(other);assertEquals(host,a.host);assertEquals(other,a.controller());assertEquals(2,a.epoch());}
    @Test void mediaAuthorityAndReplay(){var host=UUID.randomUUID();var a=new StreamAuthority(host,host);var p=new StreamPart(pc,a.session,0,0,0,0,1,640,480,new byte[3]);assertFalse(a.media(UUID.randomUUID(),p));assertFalse(a.media(host,part(0,0,1,new byte[3])));assertTrue(a.media(host,p));assertFalse(a.media(host,p));}
    @Test void mediaPartsCannotMix(){var host=UUID.randomUUID();var a=new StreamAuthority(host,host);assertTrue(a.media(host,new StreamPart(pc,a.session,0,0,0,0,2,640,480,new byte[16000])));assertFalse(a.media(host,new StreamPart(pc,a.session,0,1,0,1,2,640,480,new byte[3])));}
    @Test void muLawRoundTrip(){for(int i=-32768;i<32768;i+=71)assertTrue(Math.abs(StreamAudio.decode(StreamAudio.encode(i))-i)<1100);assertEquals(0,StreamAudio.decode(StreamAudio.encode(0)));}
    @Test void audioVolume(){assertArrayEquals(new byte[4],StreamAudio.pcm(new byte[]{0,5},0));}
    @Test void resamplerCountAndChunkIndependence(){var src=new byte[44100*4];for(int i=0;i<src.length;i++)src[i]=(byte)i;var whole=new StreamAudio.Resampler().convert(src,44100,2);assertEquals(24000,whole.length);var r=new StreamAudio.Resampler();var out=new ByteArrayOutputStream();for(int i=0;i<src.length;i+=100)out.writeBytes(r.convert(Arrays.copyOfRange(src,i,Math.min(i+100,src.length)),44100,2));assertArrayEquals(whole,out.toByteArray());}
    @Test void badAudioFormats(){var r=new StreamAudio.Resampler();assertThrows(IllegalArgumentException.class,()->r.convert(new byte[3],44100,2));assertThrows(IllegalArgumentException.class,()->r.convert(new byte[4],0,1));}
    @Test void imageRoundTrip()throws Exception{var pixels=new byte[800*600*4];for(int i=0;i<pixels.length;i+=4){pixels[i]=(byte)200;pixels[i+1]=20;pixels[i+2]=60;pixels[i+3]=(byte)255;}var jpeg=StreamImage.encode(pixels,800,600);assertTrue(jpeg.length<=64000);var decoded=StreamImage.decode(jpeg);assertEquals(640*480,decoded.length);assertTrue(Math.abs((decoded[0]&255)-200)<5);assertTrue(Math.abs(((decoded[0]>>>16)&255)-60)<5);}
    @Test void rejectForeignImage()throws Exception{var out=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(100,100,BufferedImage.TYPE_INT_RGB),"jpeg",out);assertThrows(IOException.class,()->StreamImage.decode(out.toByteArray()));assertThrows(IOException.class,()->StreamImage.decode(new byte[64001]));}
    @Test void packetRoundTrip(){var part=part(7,0,1,new byte[]{1,2,3});var b=new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);try{Media.CODEC.encode(b,new Media(part));var decoded=Media.CODEC.decode(b).part();assertEquals(part.computer(),decoded.computer());assertEquals(part.sequence(),decoded.sequence());assertArrayEquals(part.bytes(),decoded.bytes());assertEquals(0,b.readableBytes());}finally{b.release();}}
    @Test void statusCodec(){var key=new Key(ResourceLocation.parse("minecraft:overworld"),BlockPos.ZERO,pc);var s=new Status(key,session,UUID.randomUUID(),UUID.randomUUID(),10,true,2,1,8,true);var b=new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);try{Status.CODEC.encode(b,s);assertEquals(s,Status.CODEC.decode(b));}finally{b.release();}}
    @Test void inputCodec(){var i=new Input(pc,session,3,0,0,639,479,8);var b=new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);try{Input.CODEC.encode(b,i);assertEquals(i,Input.CODEC.decode(b));}finally{b.release();}}
    @Test void inputBounds(){assertThrows(IllegalArgumentException.class,()->new Input(pc,session,0,1,0,640,0,0));assertThrows(IllegalArgumentException.class,()->new Input(pc,session,-1,1,0,0,0,0));}
    @Test void truncatedMedia(){var b=new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);try{Media.CODEC.encode(b,new Media(part(0,0,1,new byte[5])));byte[] bytes=new byte[b.readableBytes()];b.readBytes(bytes);for(int n=0;n<bytes.length;n++){var cut=new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(Arrays.copyOf(bytes,n)),RegistryAccess.EMPTY);try{assertThrows(RuntimeException.class,()->Media.CODEC.decode(cut));}finally{cut.release();}}}finally{b.release();}}
}
