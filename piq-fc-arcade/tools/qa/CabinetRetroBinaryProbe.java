import cn.piq.fcarcade.cabinet.CabinetEmulator;
import java.net.URLClassLoader;
import java.nio.file.Path;

/** Loads an old-ABI implementation without recompiling it against the new interface. */
public final class CabinetRetroBinaryProbe {
    public static void main(String[] args)throws Exception{
        try(var loader=new URLClassLoader(new java.net.URL[]{Path.of(args[0]).toUri().toURL()},CabinetRetroBinaryProbe.class.getClassLoader())){
            var legacy=(CabinetEmulator)Class.forName("LegacyCabinetFixture",true,loader).getConstructor().newInstance();
            var shared=legacy.asRetro();
            if(shared.isReady())throw new AssertionError("Unexpected ready before input");
            shared.offerInput(0x123,0x456);
            if(!shared.isReady())throw new AssertionError("Old implementation input changed");
            var old=legacy.pollFrame();var frame=shared.pollFrame();
            if(old.abgr()!=frame.abgr()||old.pcm48k()!=frame.pcm48k())throw new AssertionError("Frame arrays copied");
            if(old.displayAspect()!=frame.displayAspect()||old.rotation()!=frame.rotation())throw new AssertionError("Presentation changed");
            shared.clearInput();if(shared.isReady())throw new AssertionError("Clear did not reach old implementation");shared.close();
            System.out.println("{\"old_binary_compatibility\":true,\"zero_copy\":true,\"minecraft_started\":false,\"core_started\":false}");
        }
    }
}
