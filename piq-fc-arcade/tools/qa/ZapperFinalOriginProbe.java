import java.nio.file.Path;
import java.util.List;
import cn.piq.fcarcade.core.NesCore;
import cn.piq.fcarcade.core.wasm.WasmNesCore;
import cn.piq.fcarcade.core.wasm.ZapperWasmNesCore;

/** Only probes are compiled; every production class must originate in the supplied final JAR. */
public final class ZapperFinalOriginProbe {
    public static void main(String[] args)throws Exception {
        Path expected=Path.of(args[0]).toRealPath();
        for(Class<?> type:List.of(NesCore.class,WasmNesCore.class,ZapperWasmNesCore.class))
            if(!Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath().equals(expected))
                throw new AssertionError("Production class not from final JAR: "+type.getName());
        var resource=ZapperWasmNesCore.class.getResource(ZapperWasmNesCore.MODULE_RESOURCE);
        if(resource==null||!resource.getProtocol().equals("jar"))throw new AssertionError("Zapper WASM must come from final JAR");
        System.out.println("{\"ok\":true,\"production_origin\":\"final-jar-only\",\"production_compiled\":false,\"origin_assertions\":4}");
        cn.piq.fcarcade.core.wasm.ZapperCoreProbe.main(new String[0]);
    }
}
