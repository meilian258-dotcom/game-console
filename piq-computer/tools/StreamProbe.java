package cn.piq.computer.client;
import cn.piq.computer.stream.*;
import cn.piq.computer.net.ComputerStreamNetwork.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;

/** Final JAR adapter -> actual packet codec -> authority/budget -> reassembly -> JPEG/PCM. Not MC multiplayer. */
public final class StreamProbe {
    static void check(boolean ok,String msg){if(!ok)throw new AssertionError(msg);}
    public static void main(String[] args)throws Exception{
        boolean flash=args[0].equals("flash");Path root=Path.of(args[1]),content=Path.of(args[2]),out=Path.of(args[3]);Files.createDirectories(out);
        var me=UUID.randomUUID();var pc=UUID.randomUUID();var auth=new StreamAuthority(me,me);var assembler=new StreamAssembler(pc,auth.session);var budget=new StreamBudget(1,System.nanoTime());
        ProgramBackend backend=flash?new FlashComputerBackend(root,content):new PvzComputerBackend(root,content,UUID.fromString("03e2e551-18ca-4567-b6ca-d8c46a9fbab5"));
        var raw=new AtomicLong();var nonzero=new AtomicLong();var chunks=new AtomicLong();var resampler=new StreamAudio.Resampler();
        backend.audioSink((pcm,rate,channels)->{raw.addAndGet(pcm.length);var wire=resampler.convert(pcm,rate,channels);chunks.incrementAndGet();for(byte b:wire)if(StreamAudio.decode(b)!=0)nonzero.incrementAndGet();check(StreamAudio.pcm(wire,1).length==wire.length*2,"pcm decode");});
        backend.volume(0);int frames=0,received=0,drops=0,step=0;long bytes=0,seq=0;long began=System.nanoTime(),ready=0,last=0;Set<Integer> hashes=new HashSet<>();boolean paused=false;
        try{
            while(System.nanoTime()-began<60_000_000_000L){
                check(!backend.finished(),backend.error());long now=System.nanoTime();if(ready==0&&backend.ready())ready=now;double seconds=ready==0?0:(now-ready)/1e9;
                var frame=backend.frame();if(frame!=null){frames++;
                    if(now-last>66_666_667L){last=now;var jpeg=StreamImage.encode(frame,backend.width(),backend.height());if(jpeg!=null){int count=(jpeg.length+15999)/16000;if(budget.take(jpeg.length+count*96,now)){
                        for(int i=0;i<count;i++){var part=new StreamPart(pc,auth.session,seq,now/1000,0,i,count,640,480,Arrays.copyOfRange(jpeg,i*16000,Math.min(jpeg.length,(i+1)*16000)));var b=new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);try{Media.CODEC.encode(b,new Media(part));bytes+=b.readableBytes();var decoded=Media.CODEC.decode(b).part();check(auth.media(me,decoded),"authority rejected host");check(!auth.media(UUID.randomUUID(),decoded),"spoof accepted");var joined=assembler.accept(decoded,now);if(joined!=null){var pixels=StreamImage.decode(joined);hashes.add(Arrays.hashCode(pixels));received++;if(received==1||seconds>23){var image=new BufferedImage(640,480,BufferedImage.TYPE_INT_RGB);for(int n=0;n<pixels.length;n++){int p=pixels[n];pixels[n]=(p&0xff00)|((p&255)<<16)|((p>>>16)&255);}image.setRGB(0,0,640,480,pixels,0,640);ImageIO.write(image,"png",out.resolve(received==1?"first.png":"last.png").toFile());}}}finally{b.release();}}seq++;
                    }else drops++;}}
                    backend.releaseFrame(frame);
                }
                if(seconds>=3&&step==0){backend.pointer(320,430,1,true);step++;}
                if(seconds>=3.2&&step==1){backend.pointer(320,430,0,true);step++;}
                if(seconds>=6&&step==2){backend.pointer(flash?320:225,flash?248:315,1,true);step++;}
                if(seconds>=6.2&&step==3){backend.pointer(flash?320:225,flash?248:315,0,true);step++;}
                if(seconds>=9&&step==4){backend.pointer(flash?310:500,flash?451:120,1,true);step++;}
                if(seconds>=9.2&&step==5){backend.pointer(flash?310:500,flash?451:120,0,true);step++;}
                if(seconds>=12&&step==6){backend.key(262,true);step++;}
                if(seconds>=14&&step==7){backend.releaseInput();auth.controller(null);backend.pause(true);paused=true;step++;}
                if(seconds>=17&&step==8){auth.setAllowed(me,true);auth.controller(UUID.randomUUID());backend.releaseInput();backend.pause(false);backend.key(68,true);step++;}
                if(seconds>=19&&step==9){backend.key(68,false);step++;}
                if(seconds>=24)break;Thread.sleep(8);
            }
        }finally{backend.audioSink(null);backend.releaseInput();backend.close();}
        check(backend.error().isEmpty(),backend.error());check(ready>0&&received>20&&hashes.size()>3,"video missing/stale");check(nonzero.get()>100&&chunks.get()>20,"audio missing");check(paused&&step==10&&auth.epoch()==2,"handoff probe incomplete");
        String report="{\"kind\":\""+args[0]+"\",\"frames\":"+frames+",\"receivedJpeg\":"+received+",\"unique\":"+hashes.size()+",\"videoPacketBytes\":"+bytes+",\"budgetDrops\":"+drops+",\"rawAudioBytes\":"+raw.get()+",\"audioChunks\":"+chunks.get()+",\"nonzeroAudioSamples\":"+nonzero.get()+",\"exitConfirmed\":true,\"minecraftTest\":false}";
        Files.writeString(out.resolve("result.json"),report);System.out.println(report);
    }
}
