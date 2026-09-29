// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.retro.libretro;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LibretroDefaultBackendTest {
    @Test void adaptedWindowsClientsDefaultToJniWithoutConsentState(){
        for(String os:new String[]{"Windows 10","Windows 11","WINDOWS"})for(String arch:new String[]{"amd64","x86_64"})
            assertEquals(LibretroRuntimes.Backend.JNI_TRIAL,LibretroRuntimes.defaultBackend(true,os,arch));
    }
    @Test void unadaptedCallersAndUnsupportedPlatformsKeepProcess(){
        for(String os:new String[]{"Windows 11","Linux","Mac OS X",""})for(String arch:new String[]{"amd64","x86_64","aarch64","x86",""}){
            assertEquals(LibretroRuntimes.Backend.PROCESS,LibretroRuntimes.defaultBackend(false,os,arch));
            if(!os.startsWith("Windows")||!(arch.equals("amd64")||arch.equals("x86_64")))
                assertEquals(LibretroRuntimes.Backend.PROCESS,LibretroRuntimes.defaultBackend(true,os,arch));
        }
    }
}
