import cn.piq.fcarcade.client.cabinet.CabinetBackend;
import cn.piq.fcarcade.cabinet.CabinetRomBindings;
import cn.piq.retro.api.RetroEmulatorFactory;
import cn.piq.sfchome.client.cabinet.SfcCabinetProvider;
import cn.piq.nativearcade.client.NativeCabinetBackend;
import java.nio.file.*;
import java.util.*;
import com.google.gson.Gson;

/** Instantiate frozen old adapter bytecode and invoke the new inherited default method, without opening a core. */
public final class GbaServer31LegacyProbe {
    private static int assertions;
    private static void check(boolean b,String why){assertions++;if(!b)throw new AssertionError(why);}
    private static Path origin(Class<?> type)throws Exception{return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();}
    public static void main(String[] args)throws Exception {
        check(origin(CabinetBackend.class).equals(Path.of(args[0]).toRealPath()),"new interface exact FC31");
        check(origin(RetroEmulatorFactory.class).equals(Path.of(args[0]).toRealPath()),"public factory exact FC31");
        check(origin(SfcCabinetProvider.class).equals(Path.of(args[1]).toRealPath()),"old SFC18 exact bytecode");
        check(origin(NativeCabinetBackend.class).equals(Path.of(args[2]).toRealPath()),"old Native10 exact bytecode");
        var method=CabinetBackend.class.getMethod("prepareFactory",RetroEmulatorFactory.class,CabinetRomBindings.Key.class,UUID.class,net.minecraft.network.Connection.class);check(method.isDefault(),"new API remains a default method");
        int[] opened={0};RetroEmulatorFactory original=rom->{opened[0]++;throw new AssertionError("Must not open emulator during compatibility check");};
        for(CabinetBackend backend:List.<CabinetBackend>of(new SfcCabinetProvider(),new NativeCabinetBackend())){
            check(backend.getClass().getMethod("prepareFactory",RetroEmulatorFactory.class,CabinetRomBindings.Key.class,UUID.class,net.minecraft.network.Connection.class).getDeclaringClass()==CabinetBackend.class,"frozen adapter uses inherited new method");
            var key=new CabinetRomBindings.Key("server:compatibility.invalid:25565","minecraft:overworld",UUID.randomUUID(),"test:legacy");
            check(backend.prepareFactory(original,key,UUID.randomUUID(),null)==original,"old registered factory identity preserved");
            check(backend.prepareFactory(original,null,null,null)==original,"default adds no new context precondition to old adapters");
            check(opened[0]==0,"preparation never invokes core factory");check(!backend.extensions().isBlank(),"old public metadata method still links");
        }
        System.out.println(new Gson().toJson(Map.of("ok",true,"assertions",assertions,"production_origin","final-jar-only","actual_frozen_adapter_instances",2,"default_factory_identity_preserved",true,"factory_open_calls",opened[0],"minecraft_or_native_core_started",false)));
    }
}
