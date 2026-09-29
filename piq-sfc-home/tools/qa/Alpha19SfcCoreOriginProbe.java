package cn.piq.sfcarcade.core;

import cn.piq.sfcarcade.core.wasm.WasmSfcCore;
import cn.piq.sfchome.server.SfcJoinGate;
import java.nio.file.Path;

/** Test-only entry wrapper: actual production classes must come from final JARs. */
public final class Alpha19SfcCoreOriginProbe {
    private static void origin(Class<?> type,Path expected)throws Exception {
        Path actual=Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();
        if(!actual.equals(expected.toRealPath()))throw new AssertionError("Wrong production origin: "+type.getName()+" from "+actual);
    }
    public static void main(String[]args)throws Exception {
        if(args.length!=2)throw new IllegalArgumentException("final FC JAR, final merged SFC JAR required");
        Path fc=Path.of(args[0]),sfc=Path.of(args[1]);
        origin(WasmSfcCore.class,sfc);origin(SfcRomImage.class,sfc);origin(SfcJoinGate.class,sfc);
        origin(ai.tegmentum.wasmtime4j.Engine.class,fc);
        SfcJoinCoreProbe.main(new String[0]);
    }
}
