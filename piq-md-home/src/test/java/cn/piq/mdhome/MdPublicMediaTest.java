// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.mdhome;
import cn.piq.fcarcade.cabinet.*;
import cn.piq.fcarcade.client.cabinet.WatchMediaStream;
import cn.piq.retro.api.RetroFrame;
import io.netty.buffer.Unpooled;
import net.minecraft.core.*;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real async encode -> MD payload codec -> 2P decode and independent late spectator decode. */
class MdPublicMediaTest {
    @Test void streamedPicturePcmAndLateJoinAreIndependentOfNativeInputAndRejectStaleLeases()throws Exception{
        UUID source=UUID.randomUUID(),host=UUID.randomUUID(),loan=UUID.randomUUID();long wire=42;
        var descriptor=new WatchDescriptor(ResourceLocation.parse("piq_md_home:md"),source,host,ResourceLocation.parse("minecraft:overworld"),new WatchAnchor(BlockPos.ZERO,UUID.randomUUID()),UUID.randomUUID(),List.of(new WatchAnchor(new BlockPos(1,0,0),UUID.randomUUID())));
        int[] pixels=new int[64*32];Arrays.fill(pixels,0xff12aa67);short[] pcm={100,-100,2000,-2000};var frame=new RetroFrame(64,32,pixels,4f/3,0,pcm);
        try(var sender=new WatchMediaStream(source,host,true);var second=new WatchMediaStream(source,host,false);var observer=new WatchMediaStream(source,host,false)){
            sender.sending(true);sender.offer(frame);RetroFrame p2=null,watch=null;short[] heard=null;long deadline=System.nanoTime()+4_000_000_000L;int admitted=0;
            while(System.nanoTime()<deadline&&(p2==null||heard==null)){
                List<CabinetMediaPacket> batch;while((batch=sender.pollOutbound())!=null){
                    for(var part:batch){var m=new CabinetRoomNetwork.Media(part.room(),part.hostMember(),part.sequence(),part.kind(),part.index(),part.count(),part.width(),part.height(),part.aspect(),part.rotation(),part.rawLength(),part.data());var sent=new MdPublicNetwork.Media(wire,loan,m);var b=new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);
                        try{MdPublicNetwork.Media.CODEC.encode(b,sent);var arrived=MdPublicNetwork.Media.CODEC.decode(b);assertTrue(MdPublicNetwork.belongsTo(arrived,wire,loan,descriptor));assertFalse(MdPublicNetwork.belongsTo(arrived,wire+1,loan,descriptor));assertFalse(MdPublicNetwork.belongsTo(arrived,wire,UUID.randomUUID(),descriptor));second.accept(arrived.packet());admitted++;}finally{b.release();}}
                    sender.transportResult(batch,true);
                }
                var next=second.pollVideo();if(next!=null)p2=next;var sound=second.pollAudio();if(sound!=null)heard=sound;Thread.sleep(5);
            }
            assertTrue(admitted>0);assertNotNull(p2);assertArrayEquals(pcm,heard);assertArrayEquals(CabinetMediaCodec.decodeVideo(CabinetMediaCodec.encodeVideo(frame)),p2.abgr());assertNull(observer.pollVideo());
            // Late watcher requires a new independently decodable full frame, not a restarted host core.
            sender.offer(new RetroFrame(64,32,pixels,4f/3,0,new short[0]));deadline=System.nanoTime()+3_000_000_000L;
            while(System.nanoTime()<deadline&&watch==null){List<CabinetMediaPacket> batch;while((batch=sender.pollOutbound())!=null){batch.forEach(observer::accept);sender.transportResult(batch,true);}watch=observer.pollVideo();Thread.sleep(5);}
            assertNotNull(watch);assertArrayEquals(p2.abgr(),watch.abgr());assertNull(sender.error());assertNull(second.error());assertNull(observer.error());
            sender.sending(false);sender.offer(frame);Thread.sleep(75);assertNull(sender.pollOutbound());
        }
    }
}
