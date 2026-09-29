package cn.piq.fcarcade.core.libretro;

import cn.piq.fcarcade.session.NesCoreVariant;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class LibretroNesCoreTest {
    /** Original NROM diagnostic: white background, square-wave tone, both controller ports into RAM. */
    static byte[] diagnosticRom() {
        byte[] rom = new byte[16 + 16384 + 8192];
        rom[0]='N';rom[1]='E';rom[2]='S';rom[3]=0x1a;rom[4]=1;rom[5]=1;
        int[] setup={0x78,0xd8,0xa2,0xff,0x9a,0xa9,0,0x8d,0,0x20,0x8d,1,0x20,
            0xad,2,0x20,0x10,0xfb,0xad,2,0x20,0x10,0xfb,
            0xa9,0x3f,0x8d,6,0x20,0xa9,0,0x8d,6,0x20,0xa9,0x30,0x8d,7,0x20,
            0xa9,0,0x8d,5,0x20,0x8d,5,0x20,0xa9,0x0a,0x8d,1,0x20,
            0xa9,1,0x8d,0x15,0x40,0xa9,0xbf,0x8d,0,0x40,
            0xa9,0xff,0x8d,2,0x40,0xa9,8,0x8d,3,0x40};
        int p=16; for(int value:setup)rom[p++]=(byte)value;
        int loop=0x8000+p-16;
        int[] read={0xa9,1,0x8d,0x16,0x40,0xa9,0,0x8d,0x16,0x40,0xa2,8,
            0xad,0x16,0x40,0x4a,0x66,0,0xad,0x17,0x40,0x85,3,0x4a,0x66,1,0xca,0xd0,0xef,
            0xa5,0,0x85,4,0xa5,1,0x85,5,0xe6,2,0x4c,loop&255,loop>>8};
        for(int value:read)rom[p++]=(byte)value;
        for(int v=16+16384-6;v<16+16384;v+=2){rom[v]=0;rom[v+1]=(byte)0x80;}
        return rom;
    }
    @Test void realCoreVideoAudioInputAndRam() {
        try(var core=new LibretroNesCore(false)) {
            core.loadRom(diagnosticRom()); core.setControllerState(0,0x81);core.setControllerState(1,0x42);
            for(int i=0;i<20;i++)core.runFrame();
            byte[] frame=new byte[256*240*4],ram=new byte[2048];float[] audio=new float[4096];
            core.copyFrameRgba(frame); core.copyCpuRam(ram);int count=core.copyAudioSamples(audio);
            assertTrue(count>700&&count<800);assertTrue(frame[3]==(byte)255);
            assertTrue(java.util.stream.IntStream.range(0,frame.length).anyMatch(i->i%4!=3&&(frame[i]&255)>100));
            assertTrue(java.util.stream.IntStream.range(0,count).anyMatch(i->Math.abs(audio[i])>0.001));
            assertEquals(0x81,ram[4]&255);assertEquals(0x42,ram[5]&255);
        }
    }
    @Test void independentInstancesAndSnapshotContinuation() {
        try(var first=new LibretroNesCore(false);var second=new LibretroNesCore(false)) {
            first.loadRom(diagnosticRom());second.loadRom(diagnosticRom());
            for(int i=0;i<30;i++)first.runFrame();
            byte[] state=first.saveTransientState();second.loadTransientState(state);
            byte[] a=new byte[2048],b=new byte[2048],fa=new byte[256*240*4],fb=new byte[fa.length];
            first.copyCpuRam(a);second.copyCpuRam(b);assertArrayEquals(a,b,"RAM immediately restored");
            first.copyFrameRgba(fa);second.copyFrameRgba(fb);assertArrayEquals(fa,fb,"frame immediately restored");
            for(int i=0;i<60;i++) {
                first.setControllerState(0,i&255);second.setControllerState(0,i&255);
                first.runFrame();second.runFrame();first.copyCpuRam(a);second.copyCpuRam(b);
                first.copyFrameRgba(fa);second.copyFrameRgba(fb);assertArrayEquals(a,b,"RAM at "+i);assertArrayEquals(fa,fb,"frame at "+i);
            }
            first.setControllerState(0,1);second.setControllerState(0,2);first.runFrame();second.runFrame();
            first.copyCpuRam(a);second.copyCpuRam(b);assertNotEquals(a[4],b[4]);
        }
    }
    @Test void refusesOtherGameModeAndInvalidStateWithoutMutating() {
        try(var normal=new LibretroNesCore(false);var gun=new LibretroNesCore(true)) {
            normal.loadRom(diagnosticRom());gun.loadRom(diagnosticRom());normal.runFrame();gun.runFrame();
            byte[] state=normal.saveTransientState();
            assertThrows(IllegalArgumentException.class,()->gun.loadTransientState(state));
            byte[] bad=state.clone();bad[44]^=1;
            assertThrows(IllegalArgumentException.class,()->normal.loadTransientState(bad));
            assertThrows(IllegalArgumentException.class,()->normal.loadTransientState(new byte[4]));
            assertFalse(NesCoreVariant.LEGACY.acceptsStateHeader(state,"0".repeat(64)));
            normal.runFrame();gun.runFrame();
        }
    }
    @Test void lightGunTriggerAndOffscreen() {
        try(var core=new LibretroNesCore(true)) {
            core.loadRom(diagnosticRom());assertTrue(core.supportsZapper());
            core.setZapperState(128,120,false,true);for(int i=0;i<20;i++)core.runFrame();
            byte[] ram=new byte[2048];core.copyCpuRam(ram);assertEquals(16,ram[3]&16);
            core.setZapperState(0,0,true,false);core.runFrame();core.copyCpuRam(ram);
            assertEquals(0,ram[3]&16);assertEquals(8,ram[3]&8);
        }
    }
    @Test void restoreAlsoRestoresFrontendInputLatch() {
        try(var core=new LibretroNesCore(false)) {
            core.loadRom(diagnosticRom());core.setControllerState(0,0x81);core.setControllerState(1,0x42);
            for(int i=0;i<20;i++)core.runFrame();byte[] state=core.saveTransientState();
            core.setControllerState(0,0);core.setControllerState(1,0);core.runFrame();core.loadTransientState(state);
            core.runFrame();byte[] ram=new byte[2048];core.copyCpuRam(ram);
            assertEquals(0x81,ram[4]&255);assertEquals(0x42,ram[5]&255);
        }
    }
    @Test void ownerThreadAndClosedState() throws Exception {
        var core=new LibretroNesCore(false);
        ExecutorService executor=Executors.newSingleThreadExecutor();
        try {assertInstanceOf(IllegalStateException.class,executor.submit(()->{try{core.setControllerState(0,0);return null;}catch(Exception e){return e;}}).get());}
        finally{executor.shutdownNow();core.close();}
        assertThrows(IllegalStateException.class,()->core.loadRom(diagnosticRom()));core.close();
    }
}
