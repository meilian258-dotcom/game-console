import cn.piq.mdhome.client.*;
import cn.piq.retro.libretro.*;
import cn.piq.retro.storage.RuntimeWorkspace;
import cn.piq.fcarcade.client.privateplay.PrivateSaveStore;
import cn.piq.retro.netplay.RollbackTimeline;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
public class MdNativeProbe {
    static int checks;
    static void check(boolean v,String s){checks++;if(!v)throw new AssertionError(s);System.out.println("OK "+s);}
    public static void main(String[] args)throws Exception{
        Path out=Path.of(args[0]);Files.createDirectories(out.resolve("workspace"));RuntimeWorkspace.configure(out.resolve("workspace"));
        var backend=LibretroRuntimes.Backend.valueOf(args[1]);var selected=MdProfile.Core.valueOf(args[2]);
        int batteryIndex=selected==MdProfile.Core.GENESIS_PLUS_GX?1:0;
        byte[] rom=MdRom.read(out.resolve("diagnostic.md"));
        try(var core=LibretroRuntimes.create(MdProfile.profile(selected),MdProfile.class,backend)){
            var info=core.load(rom);System.out.println("INFO "+info+" "+core.coreVersion());
            LibretroProcess.Output f=null;
            for(int i=0;i<90;i++)f=core.run(List.of(new LibretroProcess.Controls(new int[]{0,0},0)),3);
            check(f.rgba().length==f.info().width()*f.info().height()*4,"video size");
            check(Arrays.stream(toInts(f.stereo())).anyMatch(x->x!=0),"PSG audio");
            byte[] idle=f.rgba();
            byte[] beforeInput=core.serialize();byte[] batteryBefore=core.saveMemory().ram();
            for(int i=0;i<20;i++)f=core.run(List.of(new LibretroProcess.Controls(new int[]{MdProfile.input(selected,1),0},0)),3);
            check(!Arrays.equals(idle,f.rgba()),"A changes frame");
            var mem=core.saveMemory();check(mem.ram().length>0,"SRAM exists");check(mem.ram()[batteryIndex]==0x5a,"A persists SRAM");check(mem.rtc().length==0,"no RTC");
            core.restore(beforeInput);
            boolean batteryRolledBack=Arrays.equals(batteryBefore,core.saveMemory().ram());
            System.out.println("CAPABILITY stateAlsoRollsBackBattery="+batteryRolledBack+"; MC Netplay is NOT enabled by this probe");
            // Re-establish battery before the deterministic audiovisual-only timeline check.
            core.run(Collections.nCopies(20,new LibretroProcess.Controls(new int[]{MdProfile.input(selected,1),0},0)),3);
            byte[] state=core.serialize();check(state.length>0,"state");
            core.run(List.of(new LibretroProcess.Controls(new int[]{0,0},0)),3);core.restore(state);
            check(Arrays.equals(mem.ram(),core.saveMemory().ram()),"state SRAM restore");
            boolean exact=Arrays.equals(state,core.serialize());System.out.println("CAPABILITY exactStateRoundtrip="+exact);
            if(selected==MdProfile.Core.GENESIS_PLUS_GX||Boolean.getBoolean("md.probe.rollback"))check(exact,"state byte roundtrip");
            Files.write(out.resolve("state.bin"),state);Files.write(out.resolve("ram.bin"),mem.ram());
            if(Boolean.getBoolean("md.probe.rollback")){
            var adapter=new RollbackTimeline.Core<LibretroProcess.Output>(){
                public byte[] save(){return core.serialize();}
                public void restore(byte[] s){core.restore(s);}
                public LibretroProcess.Output step(int p1,int p2,boolean present){return core.run(List.of(new LibretroProcess.Controls(new int[]{MdProfile.input(selected,p1),MdProfile.input(selected,p2)},0)),present?3:0);}
            };
            var canonical=new ArrayList<RollbackTimeline.Input>();
            for(int i=0;i<24;i++)canonical.add(new RollbackTimeline.Input(i,i>=5&&i<16?1:0,i>=10?256:0,3));
            core.restore(state);for(var input:canonical)adapter.step(input,true);
            byte[] expectedState=core.serialize();var expected=adapter.step(0,0,true);
            core.restore(state);var timeline=new RollbackTimeline<LibretroProcess.Output>(adapter,0);
            for(int i=0;i<24;i++)timeline.advance(0,0,0);
            check(timeline.canonical(canonical),"delayed input triggers shared rollback");
            check(timeline.replayedFrames()==19,"bounded replay count");
            byte[] actualState=core.serialize();Files.write(out.resolve("canonical-state.bin"),expectedState);Files.write(out.resolve("rollback-state.bin"),actualState);
            boolean sameState=Arrays.equals(expectedState,actualState);
            var replay=adapter.step(0,0,true);
            boolean sameVideo=Arrays.equals(expected.rgba(),replay.rgba()),sameAudio=Arrays.equals(expected.stereo(),replay.stereo());
            System.out.println("ROLLBACK state="+sameState+" firstDifference="+Arrays.mismatch(expectedState,actualState)+" video="+sameVideo+" audio="+sameAudio);
            check(sameState&&sameVideo&&sameAudio&&batteryRolledBack,"MD Netplay release gate (not part of private single-player acceptance)");
            }
        }
        check(!LibretroRuntimes.isJniBusy(),"shared slot freed");
        for(int trial=0;trial<2;trial++){
            var engine=new MdEngine(out.resolve("diagnostic.md"),out.resolve("saves"),backend,selected);
            long until=System.nanoTime()+20_000_000_000L;
            while(!engine.isReady()&&engine.error()==null&&System.nanoTime()<until)Thread.sleep(20);
            check(engine.isReady(),"engine ready "+trial+" "+engine.error());
            engine.offerInput(0,0);if(trial==0)engine.offerInput(1,0);
            Thread.sleep(500);var frame=engine.pollFrame();check(frame!=null&&frame.pcm48k().length>0,"presentation "+trial);
            var saved=engine.stopAndSave().get(15,TimeUnit.SECONDS);check(saved.saved(),"save/restore "+trial+" "+saved.message());
            var key=new PrivateSaveStore.Key("md",MdProfile.saveNamespace(selected,backend),PrivateSaveStore.sha256(rom));
            var disk=new PrivateSaveStore(out.resolve("saves")).load(key).orElseThrow();
            check(disk.sram().length>batteryIndex&&disk.sram()[batteryIndex]==0x5a,"disk SRAM survives restart "+trial);
        }
        check(!LibretroRuntimes.isJniBusy(),"engine slot freed");
        // Exercise the public engine lifecycle used by physical reset and the explicit no-save preference.
        var oldFiles=fence(out.resolve("saves"));
        var gated=new MdEngine(out.resolve("diagnostic.md"),out.resolve("saves"),backend,selected,true,true);
        awaitReady(gated);gated.paused(false);gated.offerInput(1,0);Thread.sleep(120);
        check(gated.pollFrame()==null&&!gated.requestReset(),"private loaded state remains gated before authority READY");
        check(!gated.stopAndSave().get(15,TimeUnit.SECONDS).saved()&&oldFiles.equals(fence(out.resolve("saves"))),"cancel private restored launch preserves every old progress byte");
        var gatedRun=new MdEngine(out.resolve("diagnostic.md"),out.resolve("saves"),backend,selected,true,true);
        awaitReady(gatedRun);gatedRun.activate();gatedRun.offerInput(0,0);Thread.sleep(150);
        check(gatedRun.pollFrame()!=null&&gatedRun.stopAndSave().get(15,TimeUnit.SECONDS).saved(),"private explicit activation opens gameplay and normal local save");
        oldFiles=fence(out.resolve("saves"));
        var noSave=new MdEngine(out.resolve("diagnostic.md"),out.resolve("saves"),backend,selected,false);
        awaitReady(noSave);check(noSave.isReady(),"no-save can start beside existing saved progress");
        noSave.offerInput(0,0);Thread.sleep(180);var neutral=noSave.pollFrame();
        noSave.offerInput(1,0);Thread.sleep(180);var pressed=noSave.pollFrame();
        check(neutral!=null&&pressed!=null&&!Arrays.equals(neutral.abgr(),pressed.abgr()),"engine input reaches native pad");
        noSave.paused(true);check(noSave.requestReset(),"reset accepted while controller is returned/paused");
        Thread.sleep(180);check(noSave.pollFrame()==null,"reset does not resume absent controller");
        noSave.paused(false);noSave.offerInput(0,0);Thread.sleep(180);var reset=noSave.pollFrame();
        check(reset!=null&&Arrays.equals(neutral.abgr(),reset.abgr()),"reset resumes clean neutral video without stale input");
        var noResult=noSave.stopAndSave().get(15,TimeUnit.SECONDS);
        check(!noResult.saved()&&noResult.message().contains("不存档"),"no-save reports no save instead of false success");
        check(oldFiles.equals(fence(out.resolve("saves"))),"no-save and reset preserve every existing save byte");
        check(!noSave.requestReset(),"reset rejected after close");
        var freshRoot=out.resolve("none-never-created");var fresh=new MdEngine(out.resolve("diagnostic.md"),freshRoot,backend,selected,false);
        awaitReady(fresh);check(fresh.isReady(),"no-save fresh session ready");fresh.stopAndSave().get(15,TimeUnit.SECONDS);
        check(!Files.exists(freshRoot),"no-save never creates a progress directory");
        if(selected==MdProfile.Core.GENESIS_PLUS_GX){
            for(boolean hasBattery:new boolean[]{true,false}){
                Path diagnostic=out.resolve(hasBattery?"empty-battery.md":"no-battery.md");byte[] bytes=rom.clone();
                if(!hasBattery)Arrays.fill(bytes,0x1b0,0x1bc,(byte)0);Files.write(diagnostic,bytes);
                Path saves=out.resolve(hasBattery?"empty-battery-saves":"no-battery-saves");
                for(int trial=0;trial<2;trial++){
                    var engine=new MdEngine(diagnostic,saves,backend,selected);long until=System.nanoTime()+20_000_000_000L;
                    while(!engine.isReady()&&engine.error()==null&&System.nanoTime()<until)Thread.sleep(20);
                    check(engine.isReady(),"empty/no battery ready "+hasBattery+" "+trial+" "+engine.error());
                    check(engine.stopAndSave().get(15,TimeUnit.SECONDS).saved(),"empty/no battery save "+hasBattery+" "+trial);
                }
            }
        }
        var cancelled=new MdEngine(out.resolve("diagnostic.md"),out.resolve("cancel-saves"),backend,selected);
        cancelled.stopAndSave().get(15,TimeUnit.SECONDS);
        check(!MdEngine.active()&&!LibretroRuntimes.isJniBusy(),"startup cancel releases owner and JNI slot");
        System.out.println("PASS checks="+checks);
    }
    static int[] toInts(short[] s){int[] r=new int[s.length];for(int i=0;i<s.length;i++)r[i]=s[i];return r;}
    static void awaitReady(MdEngine engine)throws Exception{
        long until=System.nanoTime()+20_000_000_000L;
        while(!engine.isReady()&&engine.error()==null&&System.nanoTime()<until)Thread.sleep(20);
        if(!engine.isReady()){engine.stopAndSave().get(15,TimeUnit.SECONDS);throw new AssertionError("engine start: "+engine.error());}
    }
    static Map<String,String> fence(Path root)throws Exception{
        var result=new TreeMap<String,String>();try(var files=Files.walk(root)){
            for(var file:files.filter(Files::isRegularFile).toList())result.put(root.relativize(file).toString(),PrivateSaveStore.sha256(Files.readAllBytes(file)));
        }return result;
    }
}
