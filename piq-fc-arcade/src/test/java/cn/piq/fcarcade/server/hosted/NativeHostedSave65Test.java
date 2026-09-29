package cn.piq.fcarcade.server.hosted;

import cn.piq.fcarcade.fixtures.NativeSaveTestRom;
import cn.piq.fcarcade.session.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

class NativeHostedSave65Test {
    @TempDir Path root;
    private void await(BooleanSupplier value) throws Exception {
        long end=System.nanoTime()+10_000_000_000L;
        while(System.nanoTime()<end){if(value.getAsBoolean())return;Thread.sleep(5);}
        fail("condition timed out");
    }
    private Path rom() throws Exception {
        Path path=root.resolve("battery.nes");Files.write(path,NativeSaveTestRom.bytes());return path;
    }
    private void runThenClose(ServerCoreHandle run) throws Exception {
        try{await(()->run.error()!=null||run.pollFrame()!=null);assertNull(run.error());}
        finally{run.close();await(run::isTerminated);}
        assertNull(run.error());
    }
    @Test void managedHomeFinalSavePublishesBundleAndRestoresOnNewWorker() throws Exception {
        var factory=new NesServerCoreFactory();Path rom=rom();byte[] initial=null;
        for(int i=0;i<2;i++){
            var result=new AtomicReference<byte[]>();
            var managed=new NesManagedState(NativeSaveTestRom.sha(),initial,result::set);
            var context=new ServerCoreContext(root,root.resolve("unused"),UUID.randomUUID(),UUID.randomUUID(),NesCoreVariant.LIBRETRO_V1,managed);
            runThenClose(factory.open(context,rom));initial=result.get();assertNotNull(initial);
            assertEquals(0x5a,NesPersistentState.decode(initial).memory().ram()[0]&255);
            assertFalse(Files.exists(root.resolve("unused")),"Managed homes must not bypass the session's save owner");
        }
    }
    @Test void unmanagedCabinetPersistsAndLoadsThroughHostedSaveFile() throws Exception {
        var context=new ServerCoreContext(root,root.resolve("hosted"),UUID.randomUUID(),UUID.randomUUID(),NesCoreVariant.LIBRETRO_V1);
        var factory=new NesServerCoreFactory();Path rom=rom();
        runThenClose(factory.open(context,rom));
        Path saved;
        try(var walk=Files.walk(root.resolve("hosted"))){saved=walk.filter(p->p.getFileName().toString().equals("state.bin")).findFirst().orElseThrow();}
        assertTrue(Files.size(saved)>8192);
        runThenClose(factory.open(context,rom));
        assertTrue(Files.exists(saved.resolveSibling("state.previous.bin"))||Files.size(saved)>8192);
    }
    @Test void invalidInitialBundleFailsBeforePublishingOrOverwriting() throws Exception {
        var result=new AtomicReference<byte[]>();byte[] broken=new byte[]{0x50,0x46,0x50,0x31,1};
        var managed=new NesManagedState(NativeSaveTestRom.sha(),broken,result::set);
        var context=new ServerCoreContext(root,root.resolve("unused"),UUID.randomUUID(),UUID.randomUUID(),NesCoreVariant.LIBRETRO_V1,managed);
        var run=new NesServerCoreFactory().open(context,rom());
        try{await(run::isTerminated);assertNotNull(run.error());assertNull(result.get());}
        finally{run.close();await(run::isTerminated);}
    }
}
