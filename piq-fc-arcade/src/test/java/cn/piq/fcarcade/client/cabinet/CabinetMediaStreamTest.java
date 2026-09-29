package cn.piq.fcarcade.client.cabinet;

import cn.piq.fcarcade.cabinet.CabinetMediaCodec;
import cn.piq.fcarcade.cabinet.CabinetMediaPacket;
import cn.piq.retro.api.RetroFrame;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CabinetMediaStreamTest {
    private static final UUID ROOM=UUID.randomUUID(),HOST=UUID.randomUUID();
    private static RetroFrame frame(){int[] pixels=new int[320*224];for(int i=0;i<pixels.length;i++)pixels[i]=0xff000000|((i*123)&0xffffff);short[] pcm=new short[3200];for(int i=0;i<pcm.length;i++)pcm[i]=(short)(i*13);return new RetroFrame(320,224,pixels,4F/3F,1,pcm);}
    private static List<CabinetMediaPacket> parts(long seq,CabinetMediaCodec.Encoded e){byte[] bytes=e.data();int n=(bytes.length+24575)/24576;List<CabinetMediaPacket> out=new ArrayList<>();for(int i=0;i<n;i++)out.add(new CabinetMediaPacket(ROOM,HOST,seq,0,i,n,e.width(),e.height(),e.displayAspect(),e.rotation(),e.width()*e.height()*2,Arrays.copyOfRange(bytes,i*24576,Math.min(bytes.length,(i+1)*24576))));return out;}
    @Test void realBackgroundEncodeDecodePreservesQuantizedVideoAndExactPcm()throws Exception{
        RetroFrame input=frame();var expected=CabinetMediaCodec.encodeVideo(input);assertNotNull(expected);
        try(var sender=new CabinetMediaStream(ROOM,HOST,true);var receiver=new CabinetMediaStream(ROOM,HOST,false)){
            sender.sending(true);sender.offer(input);RetroFrame video=null;List<Short> pcm=new ArrayList<>();long end=System.nanoTime()+3_000_000_000L;int packets=0;
            while(System.nanoTime()<end&&(video==null||pcm.size()<input.pcm48k().length)){
                List<CabinetMediaPacket> batch;while((batch=sender.pollOutbound())!=null)for(var packet:batch){assertTrue(packet.data().length<=24576);receiver.accept(packet);packets++;}
                var v=receiver.pollVideo();if(v!=null)video=v;short[] samples;while((samples=receiver.pollAudio())!=null)for(short s:samples)pcm.add(s);
                Thread.sleep(5);
            }
            assertNull(sender.error());assertNull(receiver.error());assertNotNull(video);assertTrue(packets>=2);
            assertEquals(input.displayAspect(),video.displayAspect());assertEquals(input.rotation(),video.rotation());assertArrayEquals(CabinetMediaCodec.decodeVideo(expected),video.abgr());
            assertEquals(input.pcm48k().length,pcm.size());for(int i=0;i<pcm.size();i++)assertEquals(input.pcm48k()[i],pcm.get(i));
        }
    }
    @Test void videoCanBeDroppedWithoutDiscardingPcm()throws Exception{
        try(var stream=new CabinetMediaStream(ROOM,HOST,true)){
            stream.sending(true);RetroFrame f=frame();for(int i=0;i<4;i++)stream.offer(f);
            long end=System.nanoTime()+2_000_000_000L;int audioBytes=0,videos=0;
            while(System.nanoTime()<end&&audioBytes<f.pcm48k().length*8){List<CabinetMediaPacket> batch;while((batch=stream.pollOutbound())!=null)for(var p:batch){if(p.kind()==1)audioBytes+=p.data().length;else if(p.index()==0)videos++;}Thread.sleep(5);}
            assertEquals(f.pcm48k().length*8,audioBytes);assertTrue(videos<=2);assertNull(stream.error());
        }
    }
    @Test void reassemblyRejectsReplayAndMissingFragments(){var e=CabinetMediaCodec.encodeVideo(frame());assertNotNull(e);var p=parts(0,e);var a=new CabinetMediaAssembler();CabinetMediaAssembler.Complete done=null;for(var part:p)done=a.accept(part,0);assertNotNull(done);assertArrayEquals(e.data(),done.bytes());for(var part:p)assertNull(a.accept(part,1));}
    @Test void completedAudioClocksMaySkipButNeverReplay(){var a=new CabinetMediaAssembler();byte[] pcm=new byte[9600];assertNotNull(a.accept(new CabinetMediaPacket(ROOM,HOST,0,1,0,1,0,0,1,0,pcm.length,pcm),0));assertNull(a.accept(new CabinetMediaPacket(ROOM,HOST,0,1,0,1,0,0,1,0,pcm.length,pcm),1));assertNotNull(a.accept(new CabinetMediaPacket(ROOM,HOST,4800,1,0,1,0,0,1,0,pcm.length,pcm),2));}
    @Test void mismatchedRoomAndClosedReceiverIgnoreMedia()throws Exception{try(var r=new CabinetMediaStream(ROOM,HOST,false)){var e=CabinetMediaCodec.encodeVideo(frame());for(var p:parts(0,e))r.accept(new CabinetMediaPacket(UUID.randomUUID(),HOST,p.sequence(),p.kind(),p.index(),p.count(),p.width(),p.height(),p.aspect(),p.rotation(),p.rawLength(),p.data()));Thread.sleep(30);assertNull(r.pollVideo());r.close();for(var p:parts(1,e))r.accept(p);assertNull(r.pollVideo());}}
}
