// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.retro.libretro;

import java.net.URLClassLoader;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Isolated unloaded Java state: these tests must never load a DLL or reserve a native slot. */
class NativeSlotQueryTest {
    private static URLClassLoader isolated(){
        return new URLClassLoader(new java.net.URL[]{LibretroRuntime.class.getProtectionDomain().getCodeSource().getLocation()},ClassLoader.getPlatformClassLoader());
    }
    @Test void capacityDiscoveryDoesNotLoadLibraryOrAllocateWorkspace()throws Exception{
        try(var classes=isolated()){
            var bridge=classes.loadClass("cn.piq.retro.libretro.jni.NativeLibretroBridge");
            var loaded=bridge.getDeclaredField("loaded");loaded.setAccessible(true);
            var workspace=bridge.getDeclaredField("libraryWorkspace");workspace.setAccessible(true);
            for(int n=0;n<100;n++)assertEquals(4,bridge.getMethod("freeSlotsIfLoaded").invoke(null));
            assertEquals(false,bridge.getMethod("atCapacity").invoke(null));
            assertEquals(false,loaded.get(null));assertNull(workspace.get(null));
        }
    }
    @Test void previouslyFailedNativeLoadFailsClosedWithoutRetrying()throws Exception{
        try(var classes=isolated()){
            var bridge=classes.loadClass("cn.piq.retro.libretro.jni.NativeLibretroBridge");
            var failure=bridge.getDeclaredField("loadFailure");failure.setAccessible(true);failure.set(null,"test: restart required");
            assertEquals(0,bridge.getMethod("freeSlotsIfLoaded").invoke(null));
            assertEquals(true,bridge.getMethod("atCapacity").invoke(null));
            assertEquals("test: restart required",failure.get(null));
        }
    }
    @Test void unstartedAndClosedRuntimeCanBeQueriedOffOwnerWithoutNativeCalls()throws Exception{
        var profile=new LibretroProfile("Fixture","bin",false,List.of(1),false,Map.of(),
                Map.of("windows-x64",new LibretroProfile.Artifact("/core/unavailable-core.dll","a".repeat(64))));
        var runtime=new LibretroJniRuntime(profile,getClass());
        try(var executor=Executors.newSingleThreadExecutor()){
            assertFalse(executor.submit(runtime::nativeSlotHeld).get(2,TimeUnit.SECONDS));
            runtime.close();
            assertFalse(executor.submit(runtime::nativeSlotHeld).get(2,TimeUnit.SECONDS));
        }
    }
}
