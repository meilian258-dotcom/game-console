// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.retro.storage;

import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Inert files and a child JVM verify native-pin ownership; no native DLL is loaded. */
class RuntimeWorkspaceJniPinTest {
    @TempDir Path base;
    @Test void pinnedNativeWorkspaceIgnoresCloseUntilEveryPinIsReleased()throws Exception{
        var workspace=RuntimeWorkspace.create(base,"libretro",0);Path directory=workspace.directory();
        var first=workspace.pinNative();var second=workspace.pinNative();
        try{
            Files.writeString(directory.resolve("core.dll"),"inert fixture");workspace.close();
            assertTrue(Files.exists(directory.resolve("core.dll")));
            try(var other=FileChannel.open(directory.resolve("lease"),StandardOpenOption.WRITE)){
                assertThrows(OverlappingFileLockException.class,other::tryLock);
            }
            first.close();first.close();workspace.close();assertTrue(Files.exists(directory));
            second.close();workspace.close();assertFalse(Files.exists(directory));
            assertThrows(IllegalStateException.class,workspace::pinNative);
        }finally{first.close();second.close();workspace.close();}
    }
    @Test void closeFromAnotherThreadNeverReclaimsAnActiveNativeDirectory()throws Exception{
        var workspace=RuntimeWorkspace.create(base,"libretro",0);var pin=workspace.pinNative();
        try{
            var closer=new Thread(workspace::close);closer.start();closer.join(3000);assertFalse(closer.isAlive());
            assertTrue(Files.isDirectory(workspace.directory()));assertEquals(0,RuntimeWorkspace.reap(base));
        }finally{pin.close();workspace.close();}
        assertFalse(Files.exists(workspace.directory()));
    }
    @Test void actualJvmShutdownKeepsPinnedFilesForNextProcessRecovery()throws Exception{
        String javaExecutable=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString();
        String cp=Path.of(ShutdownFixture.class.getProtectionDomain().getCodeSource().getLocation().toURI())+java.io.File.pathSeparator
                +Path.of(RuntimeWorkspace.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Process child=new ProcessBuilder(javaExecutable,"-cp",cp,ShutdownFixture.class.getName(),base.toString()).redirectErrorStream(true).start();
        String line;try{assertTrue(child.waitFor(8,TimeUnit.SECONDS));line=child.inputReader().readLine();assertEquals(0,child.exitValue());}
        finally{if(child.isAlive()){child.destroyForcibly();child.waitFor(5,TimeUnit.SECONDS);}}
        assertNotNull(line);Path directory=Path.of(line);assertTrue(directory.startsWith(base));
        assertTrue(Files.exists(directory.resolve("core.dll")),"Shutdown must not delete pinned native files");
        assertEquals(1,RuntimeWorkspace.reap(base));assertFalse(Files.exists(directory));
    }
    public static final class ShutdownFixture {
        public static void main(String[] args)throws Exception{
            var workspace=RuntimeWorkspace.create(Path.of(args[0]),"libretro",0);workspace.pinNative();
            Files.writeString(workspace.directory().resolve("core.dll"),"inert fixture");
            System.out.println(workspace.directory());
        }
    }
}
