package cn.piq.pvz.runtime;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;

/** Run only in a disposable JVM, never in Minecraft. Real core, no stub audio/video. */
public class JniProbe {
    static void check(boolean v,String why){if(!v)throw new AssertionError(why);}
    interface Throwing {void run()throws Exception;}
    static void rejected(Throwing action)throws Exception{try{action.run();throw new AssertionError("accepted invalid native call");}catch(java.io.IOException expected){}}
    static void screenshot(byte[] p,Path out)throws Exception{var image=new BufferedImage(800,600,BufferedImage.TYPE_INT_RGB);int[] rgb=new int[800*600];for(int i=0;i<rgb.length;i++)rgb[i]=((p[4*i]&255)<<16)|((p[4*i+1]&255)<<8)|(p[4*i+2]&255);image.setRGB(0,0,800,600,rgb,0,800);ImageIO.write(image,"png",out.toFile());}
    public static void main(String[] args)throws Exception{
        Path root=Path.of(args[0]),content=Path.of(args[1]),out=Path.of(args[2]);Files.createDirectories(out);var user=UUID.fromString("12345678-1111-2222-3333-123456789abc");
        PvzJniBridge.load();String cwd=PvzJniBridge.workingDirectory();
        rejected(()->PvzJniBridge.open(root.resolve("missing.dll").toString(),root.toString(),root.toString()));
        var workspace=new PvzJniWorkspace(root,content,user);
        long token=PvzJniBridge.open(workspace.core.toString(),workspace.data.toString(),workspace.save.toString());
        try{
            var video=ByteBuffer.allocateDirect(800*600*4);var audio=ByteBuffer.allocateDirect(32768);int[] keys={};
            rejected(()->PvzJniBridge.step(token,null,audio,0,0,0,0,false,keys));
            rejected(()->PvzJniBridge.step(token,ByteBuffer.allocate(800*600*4),audio,0,0,0,0,false,keys));
            rejected(()->PvzJniBridge.step(token,video,audio,0,0,0,0,false,new int[385]));
            rejected(()->PvzJniBridge.step(token,video,audio,0,99999,0,0,false,keys));
            AtomicReference<Throwable> failure=new AtomicReference<>();Thread wrong=new Thread(()->{try{rejected(()->PvzJniBridge.close(token));}catch(Throwable t){failure.set(t);}});wrong.start();wrong.join();check(failure.get()==null,"wrong-thread native close");
            check(cwd.equals(PvzJniBridge.workingDirectory()),"core changed process cwd");
        }finally{PvzJniBridge.close(token);workspace.close();}
        rejected(()->PvzJniBridge.close(token));
        List<String> reports=new ArrayList<>();
        for(int round=0;round<2;round++){
            var raw=new AtomicLong();var nonzero=new AtomicLong();var chunks=new AtomicLong();int frames=0;var hashes=new HashSet<Integer>();long ready=0,began=System.nanoTime();int step=0;boolean paused=false;int pauseFrames=0;
            PvzJniRuntime runtime=new PvzJniRuntime(root,content,user);runtime.volume(0);runtime.audioSink(pcm->{raw.addAndGet(pcm.length);chunks.incrementAndGet();for(byte b:pcm)if(b!=0)nonzero.incrementAndGet();});
            check(runtime.saveDirectory().toString().contains("jni-saves"),"save isolation");
            rejected(()->new PvzJniRuntime(root,content,user));
            String status="";
            try{
                while(System.nanoTime()-began<60_000_000_000L){
                    check(!runtime.finished(),runtime.error());long now=System.nanoTime();if(ready==0&&runtime.ready())ready=now;double seconds=ready==0?0:(now-ready)/1e9;
                    byte[] f=runtime.poll();if(f!=null){frames++;hashes.add(Arrays.hashCode(f));if(seconds>23)screenshot(f,out.resolve("round"+round+".png"));if(seconds>5.5&&seconds<6.8)pauseFrames++;runtime.releaseFrame(f);}
                    if(seconds>2&&step==0){runtime.input(0,0,27000,1,true);step++;}
                    if(seconds>2.2&&step==1){runtime.input(0,0,27000,0,true);step++;}
                    if(seconds>3&&step==2){runtime.key(true,0,'A');runtime.key(true,8,0);runtime.key(false,8,0);runtime.input(0,-14000,10000,1,true);step++;}
                    if(seconds>3.2&&step==3){runtime.input(0,-14000,10000,0,true);step++;}
                    if(seconds>5&&step==4){runtime.pause(true);paused=true;step++;}
                    if(seconds>7&&step==5){runtime.pause(false);runtime.input(0,18000,-15000,1,true);step++;}
                    if(seconds>7.2&&step==6){runtime.input(0,18000,-15000,0,true);step++;}
                    if(seconds>9&&step==7){runtime.input(0,-9832,10259,1,true);step++;}
                    if(seconds>9.7&&step==8){runtime.input(0,-9832,10259,0,true);step++;}
                    if(seconds>12&&step==9){runtime.input(0,10600,-17800,1,true);step++;}
                    if(seconds>12.7&&step==10){runtime.input(0,10600,-17800,0,true);step++;}
                    if(seconds>=24){status=runtime.status();break;}
                    Thread.sleep(6);
                }
            }finally{runtime.close();}
            check(runtime.finished()&&runtime.error().isEmpty(),"close: "+runtime.error());check(ready>0&&frames>100&&hashes.size()>3,"video");check(nonzero.get()>100&&chunks.get()>20,"audio");check(paused&&pauseFrames==0,"pause ignored");
            check(cwd.equals(PvzJniBridge.workingDirectory()),"process cwd changed");
            check(ProcessHandle.current().children().noneMatch(p->p.info().command().orElse("").contains("pvz-host")),"JNI launched subprocess");
            reports.add("{\"round\":"+round+",\"frames\":"+frames+",\"unique\":"+hashes.size()+",\"audioBytes\":"+raw+",\"audioNonzero\":"+nonzero+",\"pauseFrames\":"+pauseFrames+",\"status\":\""+status+"\"}");
        }
        String report="{\"ok\":true,\"nativeBoundsAndThreadChecks\":true,\"cwdUnchanged\":true,\"originalSavesUnused\":true,\"runs\":["+String.join(",",reports)+"],\"minecraftTested\":false}";
        Files.writeString(out.resolve("result.json"),report);System.out.println(report);
    }
}
