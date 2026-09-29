package cn.piq.fcarcade.client.privateplay;

import cn.piq.fcarcade.core.NesCores;
import cn.piq.fcarcade.fixtures.NativeSaveTestRom;
import cn.piq.fcarcade.session.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

class NativePrivateSave65Test {
    @TempDir Path root;
    private static final String MODULE="a".repeat(64);
    private void await(BooleanSupplier value)throws Exception{
        long end=System.nanoTime()+10_000_000_000L;
        while(System.nanoTime()<end){if(value.getAsBoolean())return;Thread.sleep(5);}
        fail("condition timed out");
    }
    @Test void realPrivateEngineStopAndSaveReloadsLegacyThenNativeBundle()throws Exception{
        Path rom=root.resolve("battery.nes");Files.write(rom,NativeSaveTestRom.bytes());
        var store=new PrivateSaveStore(root.resolve("account"));
        var key=new PrivateSaveStore.Key("fc",NesCoreVariant.LIBRETRO_V1.stateNamespace()+"/"+MODULE,NativeSaveTestRom.sha());
        // Existing private archive, before native bundles, stays readable.
        try(var core=NesCores.create(NesCoreVariant.LIBRETRO_V1)){
            core.loadRom(NativeSaveTestRom.bytes());core.runFrame();store.save(key,core.saveTransientState(),new byte[0]);
        }
        for(int i=0;i<2;i++){
            var engine=new FcPrivateEngine(rom,store,NesCores::create,MODULE);
            try{
                await(()->engine.error()!=null||engine.pollFrame()!=null);assertNull(engine.error());
                engine.paused(true);
            }finally{assertTrue(engine.stopAndSave().get(10,TimeUnit.SECONDS).saved(),engine.error());}
            var saved=store.load(key).orElseThrow();assertEquals(0,saved.sram().length);
            assertEquals(0x5a,NesPersistentState.decode(saved.state()).memory().ram()[0]&255);
        }
        assertTrue(Files.exists(store.directory(key).resolve("previous.zip")));
    }
}
