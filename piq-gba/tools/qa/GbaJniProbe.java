import cn.piq.gba.bridge.*;
import cn.piq.gba.client.GbaSafeEject;
import cn.piq.retro.libretro.*;
import cn.piq.retro.storage.RuntimeWorkspace;
import java.nio.file.*;
import java.util.*;

public class GbaJniProbe {
    static int checks;
    static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);checks++;System.out.println("CHECK "+why);}
    static GbaProcessSession.Frame pixel(GbaSession s,int pixel)throws Exception{
        long end=System.nanoTime()+8_000_000_000L;int count=0;
        while(System.nanoTime()<end){if(s.error()!=null)throw new AssertionError(s.error());var f=s.pollFrame();if(f!=null){if(f.abgr()[0]==pixel&&++count==2)return f;}Thread.sleep(5);}
        throw new AssertionError("pixel timeout; "+s.error());
    }
    public static void main(String[] args)throws Exception {
        Path root=Path.of(args[0]);Files.createDirectories(root.resolve("instance"));RuntimeWorkspace.configure(root.resolve("instance"));
        Path rom=root.resolve("diagnostic.gba"),saves=root.resolve("saves");
        byte[] original=Files.readAllBytes(rom);
        for(int n=0;n<3;n++){
            var s=new GbaJniSession(rom,saves);
            try{
                pixel(s,0xff000000);check(s.isReady(),"real mGBA JNI ready");
                try{new GbaJniSession(rom,saves);throw new AssertionError("double allowed");}catch(java.io.IOException expected){check(true,"second GBA rejected");}
                if(n==0){s.offerInput(257);var f=pixel(s,0xff000018);check(f.abgr().length==38400,"geometry and A+B");check(f.pcm48k().length>0&&f.pcm48k().length<=32768,"bounded resampled PCM");check(java.util.stream.IntStream.range(0,f.pcm48k().length).anyMatch(i->f.pcm48k()[i]!=0),"audible diagnostic tone");s.clearInput();pixel(s,0xff000000);}
            }finally{
                if(n==0){var result=GbaSafeEject.finish(s);check(result.safe(),"real JNI safe-eject save barrier: "+result.failure());}
                else{s.close();check(s.awaitClosed(6000),"owner close and save completed");}
            }
            check(s.error()==null,"no JNI session error: "+s.error());check(!LibretroRuntimes.isJniBusy()&&!GbaJniSession.active(),"shared slot released");
            List<Path> files;try(var walk=Files.walk(saves)){files=walk.filter(p->p.getFileName().toString().equals("sram.bin")).toList();}
            check(files.size()==1&&files.getFirst().toString().contains("jni-trial-v1-mgba-e31759b"),"isolated battery namespace");
            byte[] ram=Files.readAllBytes(files.getFirst());check(ram.length==32768&&ram[0]==3,"SRAM exact through restart without pressing keys");
        }
        var cancelled=new GbaJniSession(rom,saves);cancelled.close();check(cancelled.awaitClosed(6000)&&!GbaJniSession.active(),"cancel initialization closes asynchronously");
        check(Arrays.equals(original,Files.readAllBytes(rom)),"ROM unchanged");
        System.out.println("PASS checks="+checks);
    }
}
