package cn.piq.sfchome.client;

import cn.piq.fcarcade.cabinet.CabinetFrame;
import cn.piq.fcarcade.client.privateplay.*;
import cn.piq.fcarcade.core.NesCores;
import cn.piq.fcarcade.rom.INesHeader;
import cn.piq.fcarcade.session.NesCoreVariant;
import cn.piq.sfcarcade.core.*;
import cn.piq.sfcarcade.core.wasm.WasmSfcCore;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;

/** Frozen production private engines with original QA-only 6502/65816 programs. No Minecraft, socket or speaker. */
public final class PrivateHome56Probe {
    private static int assertions,cases;
    private static void check(boolean value,String why){assertions++;if(!value)throw new AssertionError(why);}
    private static String sha(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    private static String resource(Class<?> owner,String path)throws Exception{
        try(var in=owner.getResourceAsStream(path)){if(in==null)throw new AssertionError("Missing module "+path);return sha(in.readAllBytes());}
    }
    private static void origin(Class<?> type,Path expected)throws Exception{
        Path actual=Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();
        check(actual.equals(expected.toRealPath()),type.getName()+" did not load from frozen JAR: "+actual);
    }
    private static void healthy(PrivateEngine engine){if(engine.error()!=null)throw new AssertionError(engine.error());}
    private static void ready(PrivateEngine engine)throws Exception{
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(60);
        while(!engine.isReady()&&System.nanoTime()<until){healthy(engine);Thread.sleep(5);}
        healthy(engine);check(engine.isReady(),"Engine can finish loading while paused");
        check(engine.pollFrame()==null,"Paused initialization presents no queued frame/audio");
    }
    private static PrivateEngine start(BiFunction<Path,Path,PrivateEngine> factory,Path rom,Path root)throws Exception{
        PrivateEngine engine=factory.apply(rom,root);
        try{engine.paused(true);ready(engine);return engine;}
        catch(Throwable failure){engine.stopAndSave().get(60,TimeUnit.SECONDS);throw failure;}
    }
    private static PrivateEngine.SaveResult stop(PrivateEngine engine)throws Exception{
        var future=engine.stopAndSave();check(future==engine.stopAndSave(),"Stop is idempotent");
        var saved=future.get(60,TimeUnit.SECONDS);check(!engine.isReady(),"Stopped engine is not ready");return saved;
    }
    private static Path latest(Path root)throws Exception{
        try(var files=Files.walk(root)){
            var paths=files.filter(p->p.getFileName().toString().equals("latest.zip")).toList();
            check(paths.size()==1,"Exactly one ROM/core-scoped current private save");return paths.getFirst();
        }
    }
    private static void same(PrivateSaveStore.Snapshot before,PrivateSaveStore.Snapshot after,String why){
        check(Arrays.equals(before.state(),after.state()),why+" state");check(Arrays.equals(before.sram(),after.sram()),why+" SRAM");
    }
    private static boolean color(CabinetFrame frame,boolean red){
        if(frame==null)return false;int good=0;
        for(int p:frame.abgr())if(red?(p&255)>200&&((p>>>8)&255)<20&&((p>>>16)&255)<20:(p&0xffffff)==0)good++;
        return good>frame.abgr().length/2;
    }
    private static CabinetFrame frame(PrivateEngine engine,Boolean red)throws Exception{
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(12);
        while(System.nanoTime()<until){
            healthy(engine);var frame=engine.pollFrame();
            if(frame!=null&&(red==null||color(frame,red))){
                check(frame.width()>0&&frame.height()>0&&frame.abgr().length==frame.width()*frame.height(),"Actual core video bounds");
                check(frame.pcm48k().length>0&&(frame.pcm48k().length&1)==0,"Actual core stereo PCM returned without speaker");
                check(frame.rotation()==0,"Home frame has no rotation");return frame;
            }
            Thread.sleep(5);
        }
        throw new AssertionError("Actual private frame/input effect not observed");
    }
    private static void exercise(String name,Path rom,Path root,PrivateSaveStore.Key key,
                                 BiFunction<Path,Path,PrivateEngine> factory,boolean sfc)throws Exception{
        PrivateSaveStore saves=new PrivateSaveStore(root);
        check(saves.load(key).isEmpty(),name+" starts without saves");
        PrivateEngine first=start(factory,rom,root);
        try{check(stop(first).saved(),name+" paused initial state saved");}finally{first.close();}
        var initial=saves.load(key).orElseThrow();check(initial.state().length>0,name+" saved actual core state");cases++;

        PrivateEngine zero=start(factory,rom,root);
        try{check(stop(zero).saved(),name+" paused zero-input restore saved");}finally{zero.close();}
        same(initial,saves.load(key).orElseThrow(),name+" restore followed by paused stop advances no frames");cases++;

        PrivateEngine active=start(factory,rom,root);
        try{
            active.paused(false);active.offerInput(0,0);frame(active,sfc?Boolean.FALSE:null);
            if(sfc){
                active.offerInput(256,0);frame(active,Boolean.TRUE);
                active.clearInput();active.offerInput(256,0);frame(active,Boolean.FALSE);
                active.offerInput(0,0);active.offerInput(256,0);frame(active,Boolean.TRUE);
                active.clearInput();active.offerInput(0,0);frame(active,Boolean.FALSE);
            }
            active.paused(true);Thread.sleep(80);check(active.pollFrame()==null,name+" paused output cleared");
            check(stop(active).saved(),name+" progressed state saved while paused");
        }finally{active.close();}
        var progressed=saves.load(key).orElseThrow();
        check(!Arrays.equals(initial.state(),progressed.state()),name+" real gameplay changed saved state");cases++;

        PrivateEngine restored=start(factory,rom,root);
        try{check(stop(restored).saved(),name+" actual progressed snapshot restored");}finally{restored.close();}
        same(progressed,saves.load(key).orElseThrow(),name+" progressed restore retained exact state and SRAM");cases++;
        if(sfc)check(!SfcCoreLease.occupied(),"Real SFC owner released after saved future completion");

        Path archive=latest(root);byte[] corrupted={80,82,73,86,65,84,69};Files.write(archive,corrupted);
        PrivateEngine invalid=factory.apply(rom,root);invalid.paused(true);
        try{
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(60);
            while(invalid.error()==null&&System.nanoTime()<until)Thread.sleep(5);
            check(invalid.error()!=null,name+" corrupt private archive failed closed");
            check(!stop(invalid).saved(),name+" corrupt archive was not reported saved");
            check(Arrays.equals(corrupted,Files.readAllBytes(archive)),name+" corrupt original retained byte for byte");
        }finally{invalid.close();}
        cases++;
    }
    private static void sramRestore(Path scratch,String namespace)throws Exception{
        // Original synthetic input ROM with a 2 KiB battery-backed SRAM declaration.
        // Seed nonzero SRAM through original core9, not a mock, then save an advanced full state.
        byte[] bytes=SfcTwoPortInputProbe.rom();bytes[0x7fd6]=2;bytes[0x7fd8]=1;
        int sum=0;for(int i=0;i<bytes.length;i++)if(i<0x7fdc||i>0x7fdf)sum=(sum+(bytes[i]&255))&65535;
        int checksum=(sum+0x1fe)&65535,complement=checksum^65535;
        bytes[0x7fdc]=(byte)complement;bytes[0x7fdd]=(byte)(complement>>>8);
        bytes[0x7fde]=(byte)checksum;bytes[0x7fdf]=(byte)(checksum>>>8);
        SfcRomImage image=SfcRomImage.fromBytes(bytes);Path rom=scratch.resolve("original-sram-input.sfc"),root=scratch.resolve("sfc-sram-saves");
        Files.write(rom,bytes,StandardOpenOption.CREATE_NEW);
        var key=new PrivateSaveStore.Key("sfc",namespace,image.sha256());var saves=new PrivateSaveStore(root);
        byte[] seeded=new byte[2048];Arrays.fill(seeded,(byte)0x5a);PrivateSaveStore.Snapshot before;
        try(SfcCore core=new WasmSfcCore()){
            core.loadRom(image);core.runFrame(SfcControllerState.NONE,SfcControllerState.NONE);core.reset(true);core.loadSram(seeded);
            for(int i=0;i<5;i++)core.runFrame(new SfcControllerState(256),SfcControllerState.NONE);
            before=new PrivateSaveStore.Snapshot(core.saveState(),core.saveSram());
            check(Arrays.equals(seeded,before.sram()),"Original core retains nonempty seeded SRAM cache");
        }
        saves.save(key,before.state(),before.sram());
        PrivateEngine paused=start(SfcPrivateEngine::new,rom,root);
        try{check(stop(paused).saved(),"Nonempty SRAM exact paused restore saved");}finally{paused.close();}
        same(before,saves.load(key).orElseThrow(),"Nonempty SRAM restore advances no frames");cases++;
        PrivateEngine active=start(SfcPrivateEngine::new,rom,root);
        try{
            active.paused(false);active.offerInput(0,0);frame(active,Boolean.FALSE);
            active.offerInput(256,0);frame(active,Boolean.TRUE);active.paused(true);Thread.sleep(80);
            check(stop(active).saved(),"Nonempty SRAM resumed gameplay saved");
        }finally{active.close();}
        var progressed=saves.load(key).orElseThrow();
        check(!Arrays.equals(before.state(),progressed.state()),"Nonempty SRAM actual gameplay advances state");
        check(Arrays.equals(seeded,progressed.sram()),"Nonempty SRAM persists through private play");
        PrivateEngine restored=start(SfcPrivateEngine::new,rom,root);
        try{check(stop(restored).saved(),"Nonempty SRAM progressed state reload saved");}finally{restored.close();}
        same(progressed,saves.load(key).orElseThrow(),"Nonempty SRAM progressed restore is exact");
        check(Arrays.equals(bytes,Files.readAllBytes(rom)),"Original SRAM ROM fixture unchanged");cases++;
    }
    /** Same original JMP-$8000 image as WasmNesCoreTest.minimalLoopRom; no game assets. */
    private static byte[] nesRom(){
        byte[] rom=new byte[16+16384+8192];rom[0]='N';rom[1]='E';rom[2]='S';rom[3]=0x1a;rom[4]=1;rom[5]=1;
        rom[16]=0x4c;rom[17]=0;rom[18]=(byte)0x80;
        for(int vector:new int[]{0x3ffa,0x3ffc,0x3ffe}){rom[16+vector]=0;rom[17+vector]=(byte)0x80;}
        return rom;
    }
    public static void main(String[] args)throws Exception{
        if(args.length!=3)throw new IllegalArgumentException("Expected frozen FC JAR, SFC JAR and empty private-save-temp directory");
        Path fc=Path.of(args[0]),sfc=Path.of(args[1]),scratch=Path.of(args[2]).toAbsolutePath().normalize();
        check(scratch.getFileName().toString().equals("private-save-temp")&&!Files.exists(scratch),"Fresh explicit QA-only save directory");
        Files.createDirectory(scratch);
        origin(FcPrivateEngine.class,fc);origin(PrivateSaveStore.class,fc);origin(PrivateEngine.class,fc);
        origin(SfcPrivateEngine.class,sfc);origin(WasmSfcCore.class,sfc);origin(SfcRomImage.class,sfc);
        origin(ai.tegmentum.wasmtime4j.Engine.class,fc);
        byte[] nes=nesRom(),snes=SfcTwoPortInputProbe.rom();
        Path nesPath=scratch.resolve("original-loop.nes"),sfcPath=scratch.resolve("original-input.sfc");
        Files.write(nesPath,nes,StandardOpenOption.CREATE_NEW);Files.write(sfcPath,snes,StandardOpenOption.CREATE_NEW);
        var variant=NesCoreVariant.forRom(INesHeader.parse(nes),false);
        var fcKey=new PrivateSaveStore.Key("fc",variant.stateNamespace()+"/"+resource(FcPrivateEngine.class,NesCores.moduleResource(variant)),sha(nes));
        var sfcKey=new PrivateSaveStore.Key("sfc","sfc-private-state-v1/abi"+SfcCoreAbi.VERSION+"/"+resource(WasmSfcCore.class,"/assets/piq_sfc_arcade/core/piq_sfc_wasm.wasm"),SfcRomImage.fromBytes(snes).sha256());
        exercise("FC",nesPath,scratch.resolve("fc-saves"),fcKey,FcPrivateEngine::new,false);
        exercise("SFC",sfcPath,scratch.resolve("sfc-saves"),sfcKey,SfcPrivateEngine::new,true);
        sramRestore(scratch,sfcKey.coreNamespace());
        check(Arrays.equals(nes,Files.readAllBytes(nesPath)),"Original FC fixture unchanged");
        check(Arrays.equals(snes,Files.readAllBytes(sfcPath)),"Original SFC fixture unchanged");
        System.out.println("PRIVATE_HOME56_RESULT {\"ok\":true,\"cases\":"+cases+",\"assertions\":"+assertions
                +",\"production_origin\":\"jar-only\",\"actual_fc_sfc_cores\":true,\"minecraft_started\":false,\"commercial_roms\":false,\"socket_opened\":false,\"audio_device_opened\":false}");
    }
}
