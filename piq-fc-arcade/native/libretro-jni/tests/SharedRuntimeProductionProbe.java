// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.retro.libretro.jni;
import cn.piq.retro.libretro.*;
import cn.piq.retro.storage.RuntimeWorkspace;
import java.nio.file.*;
import java.util.*;

/** Real public Java resource loading; synthetic shared-libc++ core only. */
public final class SharedRuntimeProductionProbe {
    static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    public static void main(String[] args)throws Exception {
        check(args.length==4,"Expected instance, observer, core SHA, production classes");
        Path classes=Path.of(args[3]).toRealPath();
        check(Path.of(NativeLibretroBridge.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath().equals(classes),"Shadow bridge class");
        System.load(Path.of(args[1]).toAbsolutePath().toString());
        RuntimeWorkspace.configure(Path.of(args[0]));
        var profile=new LibretroProfile("PIQ mock","bin",false,List.of(1),false,Map.of("piq_mode","normal"),
                Map.of("windows-x64",new LibretroProfile.Artifact("/core/shared-runtime-mock.dll",args[2])));
        var sessionField=LibretroJniRuntime.class.getDeclaredField("workspace");sessionField.setAccessible(true);
        var bridgeField=NativeLibretroBridge.class.getDeclaredField("libraryWorkspace");bridgeField.setAccessible(true);
        Path retained=null; int frames=0;
        for(int cycle=0;cycle<3;cycle++) {
            var session=new LibretroJniRuntime(profile,SharedRuntimeProductionProbe.class);
            Path coreDirectory;
            try {
                var info=session.load(new byte[64]);
                check(info.width()==2&&info.height()==2,"Mock geometry missing");
                coreDirectory=((RuntimeWorkspace)sessionField.get(session)).directory();
                Path core=coreDirectory.resolve("core.dll");
                check(Path.of(RuntimeDependencyProbe.modulePath(core.toString())).toRealPath().equals(core.toRealPath()),"Core module not loaded from its workspace");
                Path expected=((RuntimeWorkspace)bridgeField.get(null)).directory().resolve("libc++.dll").toRealPath();
                Path actual=Path.of(RuntimeDependencyProbe.modulePath("libc++.dll")).toRealPath();
                check(actual.equals(expected),"Runtime is not the production loader's fixed module");
                if(retained==null)retained=actual;check(retained.equals(actual),"Runtime module path changed");
                for(int n=0;n<3;n++) {
                    var output=session.run(List.of(new LibretroProcess.Controls(new int[]{0},0)),3);
                    check(output.rgba().length==16&&output.stereo().length>0,"Missing real mock video/audio");frames++;
                }
            } finally {session.close();}
            check(RuntimeDependencyProbe.modulePath("core.dll").isEmpty(),"Core remains mapped after close");
            check(!Files.exists(coreDirectory),"Closed core workspace remains");
            check(Path.of(RuntimeDependencyProbe.modulePath("libc++.dll")).toRealPath().equals(retained),"Shared runtime vanished or changed");
            check(NativeLibretroBridge.availableSlots()==4&&!session.nativeSlotHeld(),"Core slot not recovered");
        }
        System.out.println("SHARED_PRODUCTION_OK cycles=3 frames="+frames+" realCoreUnloads=3 runtimeStable=true workspaceClean=true");
    }
}
