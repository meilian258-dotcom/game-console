import cn.piq.mdhome.client.*;
import cn.piq.retro.libretro.*;
import cn.piq.retro.storage.RuntimeWorkspace;
import cn.piq.fcarcade.client.privateplay.PrivateSaveStore;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
public class MdNativeProbe {
    static int checks;
    static void check(boolean v,String s){checks++;if(!v)throw new AssertionError(s);System.out.println("OK "+s);}
    public static void main(String[] args)throws Exception{
        Path out=Path.of(args[0]);Files.createDirectories(out.resolve("workspace"));RuntimeWorkspace.configure(out.resolve("workspace"));
        var backend=LibretroRuntimes.Backend.valueOf(args[1]);byte[] rom=MdRom.read(out.resolve("diagnostic.md"));
        try(var core=LibretroRuntimes.create(MdProfile.profile(),MdProfile.class,backend)){
            var info=core.load(rom);System.out.println("INFO "+info+" "+core.coreVersion());
            LibretroProcess.Output f=null;
            for(int i=0;i<90;i++)f=core.run(List.of(new LibretroProcess.Controls(new int[]{0,0},0)),3);
            check(f.rgba().length==f.info().width()*f.info().height()*4,"video size");
            check(Arrays.stream(toInts(f.stereo())).anyMatch(x->x!=0),"PSG audio");
            byte[] idle=f.rgba();
            for(int i=0;i<20;i++)f=core.run(List.of(new LibretroProcess.Controls(new int[]{1,0},0)),3);
            check(!Arrays.equals(idle,f.rgba()),"A changes frame");
            var mem=core.saveMemory();check(mem.ram().length>0,"SRAM exists");check(mem.ram()[0]==0x5a,"A persists SRAM");check(mem.rtc().length==0,"no RTC");
            byte[] state=core.serialize();check(state.length>0,"state");
            core.run(List.of(new LibretroProcess.Controls(new int[]{0,0},0)),3);core.restore(state);
            check(Arrays.equals(mem.ram(),core.saveMemory().ram()),"state SRAM restore");
            Files.write(out.resolve("state.bin"),state);Files.write(out.resolve("ram.bin"),mem.ram());
        }
        check(!LibretroRuntimes.isJniBusy(),"shared slot freed");
        for(int trial=0;trial<2;trial++){
            var engine=new MdEngine(out.resolve("diagnostic.md"),out.resolve("saves"),backend);
            long until=System.nanoTime()+20_000_000_000L;
            while(!engine.isReady()&&engine.error()==null&&System.nanoTime()<until)Thread.sleep(20);
            check(engine.isReady(),"engine ready "+trial+" "+engine.error());
            engine.offerInput(0,0);if(trial==0)engine.offerInput(1,0);
            Thread.sleep(500);var frame=engine.pollFrame();check(frame!=null&&frame.pcm48k().length>0,"presentation "+trial);
            var saved=engine.stopAndSave().get(15,TimeUnit.SECONDS);check(saved.saved(),"save/restore "+trial+" "+saved.message());
            var key=new PrivateSaveStore.Key("md",(backend==LibretroRuntimes.Backend.JNI_TRIAL?"jni-v1/":"process-v1/")+MdProfile.SHA,PrivateSaveStore.sha256(rom));
            var disk=new PrivateSaveStore(out.resolve("saves")).load(key).orElseThrow();
            check(disk.sram().length>0&&disk.sram()[0]==0x5a,"disk SRAM survives restart "+trial);
        }
        check(!LibretroRuntimes.isJniBusy(),"engine slot freed");
        System.out.println("PASS checks="+checks);
    }
    static int[] toInts(short[] s){int[] r=new int[s.length];for(int i=0;i<s.length;i++)r[i]=s[i];return r;}
}
