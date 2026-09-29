import cn.piq.fcarcade.cabinet.CabinetMediaCodec;
import cn.piq.retro.api.RetroFrame;
import java.nio.file.Path;

public final class CabinetFinalCodecOriginProbe {
    public static void main(String[] args)throws Exception{
        Path expected=Path.of(args[0]).toRealPath();
        for(Class<?> type:new Class<?>[]{CabinetMediaCodec.class,CabinetMediaCodec.Encoded.class,RetroFrame.class})
            if(!Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath().equals(expected))throw new AssertionError("Not supplied final jar: "+type);
        System.out.println("{\"ok\":true,\"classes\":3,\"production_origin\":\"final-jar-only\",\"minecraft_or_native_core_started\":false}");
    }
}
