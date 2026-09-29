// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.retro.libretro;

import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Pure Java admission/contract tests. No test in this class loads a native DLL. */
class LibretroJniContractTest {
    @TempDir Path temp;
    private static LibretroProfile profile(int ports){return new LibretroProfile("Fixture","bin",false,
            Collections.nCopies(ports,1),false,Map.of(),Map.of("windows-x64",new LibretroProfile.Artifact("/core/fixture.dll","a".repeat(64))));}
    @Test void explicitProcessChoiceRetainsOldTransportAndDoesNotReserveJni(){
        boolean busy=LibretroRuntimes.isJniBusy();
        try(var runtime=LibretroRuntimes.create(profile(4),getClass(),LibretroRuntimes.Backend.PROCESS)){
            assertInstanceOf(LibretroProcess.class,runtime);assertEquals(LibretroRuntimes.Backend.PROCESS,runtime.backend());
            assertEquals(1,LibretroRuntime.API_VERSION);assertEquals("",runtime.diagnosticError());
        }
        assertEquals(busy,LibretroRuntimes.isJniBusy());
    }
    @Test void inputContractPreservesEveryPortAndDoesNotAliasCallerArrays(){
        int[] pads={1,2,4,8};var input=new LibretroProcess.Controls(pads,0);pads[3]=16;
        assertArrayEquals(new int[]{1,2,4,8},input.pads());var copy=input.pads();copy[0]=0;
        assertArrayEquals(new int[]{1,2,4,8},input.pads());assertEquals(4,profile(4).devices().size());
        assertThrows(IllegalArgumentException.class,()->profile(5));
    }
    @Test void onlyTrustedFeatureDeclarationsAreAcceptedAndConstructingDoesNotLoadDll(){
        boolean busy=LibretroRuntimes.isJniBusy();
        // Bit 6 is now the explicit, trusted legacy inline-options adapter opt-in.
        // Bit 7 remains outside the ABI's declared feature set.
        assertThrows(IllegalArgumentException.class,()->new LibretroJniRuntime(profile(1),getClass(),128));
        assertThrows(IllegalArgumentException.class,()->new LibretroJniRuntime(profile(1),getClass(),LibretroJniRuntime.MESEN_GUN));
        try(var legacy=new LibretroJniRuntime(profile(1),getClass(),LibretroJniRuntime.LEGACY_INLINE_OPTIONS)){
            assertEquals(LibretroRuntimes.Backend.JNI_TRIAL,legacy.backend());
            assertEquals(busy,LibretroRuntimes.isJniBusy());
        }
        try(var runtime=new LibretroJniRuntime(profile(1),getClass(),LibretroJniRuntime.WGL_COMPAT|LibretroJniRuntime.POINTER|LibretroJniRuntime.KEYBOARD)){
            assertEquals(LibretroRuntimes.Backend.JNI_TRIAL,runtime.backend());
            assertTrue(runtime.capabilities().contains(LibretroRuntime.Capability.OPENGL_COMPAT_VIDEO));
            assertTrue(runtime.capabilities().contains(LibretroRuntime.Capability.KEYBOARD));
            assertFalse(runtime.capabilities().contains(LibretroRuntime.Capability.STATE));
            assertFalse(runtime.capabilities().contains(LibretroRuntime.Capability.SAVE_MEMORY));
            assertThrows(UnsupportedOperationException.class,()->runtime.capabilities().clear());
            assertEquals("",runtime.diagnosticError());
        }
        assertEquals(busy,LibretroRuntimes.isJniBusy());
    }
    @Test void uninitializedRuntimeRejectsForeignThreadBeforeIoOrNativeCalls()throws Exception{
        var runtime=new LibretroJniRuntime(profile(1),getClass());var caught=new AtomicReference<Throwable>();
        var thread=new Thread(()->{try{runtime.load(new byte[]{1});}catch(Throwable e){caught.set(e);}});thread.start();thread.join();
        assertInstanceOf(IllegalStateException.class,caught.get());assertTrue(caught.get().getMessage().contains("owner"));
        assertThrows(IllegalStateException.class,()->runtime.run(List.of(new LibretroProcess.Controls(new int[]{0},0)),3));
        runtime.close();runtime.close();
    }
    @Test void rejectedManifestPathsNeverOpenAWorkspaceOrTouchSource()throws Exception{
        Path source=Files.write(temp.resolve("source.bin"),new byte[]{1,2,3});boolean busy=LibretroRuntimes.isJniBusy();
        try(var runtime=new LibretroJniRuntime(profile(1),getClass())){
            for(String name:List.of("../escape.bin","a/../escape.bin","/absolute.bin","C:/absolute.bin","folder\\file.bin","bad:stream.bin","trailing. ","a//b.bin"))
                assertThrows(IllegalArgumentException.class,()->runtime.loadFiles(name,Map.of(name,source),null),name);
            assertThrows(IllegalArgumentException.class,()->runtime.loadFiles("missing.bin",Map.of("other.bin",source),null));
            assertThrows(IllegalArgumentException.class,()->runtime.loadFiles("source.bin",Map.of(),null));
        }
        assertArrayEquals(new byte[]{1,2,3},Files.readAllBytes(source));assertEquals(busy,LibretroRuntimes.isJniBusy());
    }
    @Test void initializationFailureReleasesSlotAndWorkspaceWithoutAnyNativeResource()throws Exception{
        // Separate Java static state, with every native resource deliberately hidden before System.load.
        var classes=LibretroRuntime.class.getProtectionDomain().getCodeSource().getLocation();
        try(var isolated=new URLClassLoader(new java.net.URL[]{classes},ClassLoader.getPlatformClassLoader()){
            @Override public InputStream getResourceAsStream(String name){return name.startsWith("core/")?null:super.getResourceAsStream(name);}
        }){
            var workspace=isolated.loadClass("cn.piq.retro.storage.RuntimeWorkspace");workspace.getMethod("configure",Path.class).invoke(null,temp);
            var profiles=isolated.loadClass("cn.piq.retro.libretro.LibretroProfile");
            var artifact=isolated.loadClass("cn.piq.retro.libretro.LibretroProfile$Artifact").getConstructor(String.class,String.class)
                    .newInstance("/core/fixture.dll","a".repeat(64));
            var declaration=profiles.getConstructor(String.class,String.class,boolean.class,List.class,boolean.class,Map.class,Map.class)
                    .newInstance("Fixture","bin",false,List.of(1),false,Map.of(),Map.of("windows-x64",artifact));
            var type=isolated.loadClass("cn.piq.retro.libretro.LibretroJniRuntime");var runtime=type.getConstructor(profiles,Class.class).newInstance(declaration,type);
            var failure=assertThrows(InvocationTargetException.class,()->type.getMethod("load",byte[].class).invoke(runtime,(Object)new byte[]{1}));
            assertInstanceOf(IllegalStateException.class,failure.getCause());
            assertEquals(false,isolated.loadClass("cn.piq.retro.libretro.LibretroRuntimes").getMethod("isJniBusy").invoke(null));
            type.getMethod("close").invoke(runtime);
            Path root=temp.resolve("game-console/runtime-sessions");
            if(Files.isDirectory(root))try(var files=Files.list(root)){assertTrue(files.noneMatch(Files::isDirectory));}
        }
    }
}
