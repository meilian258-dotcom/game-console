// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.fcarcade.netplay;

import cn.piq.retro.libretro.*;
import cn.piq.retro.storage.RuntimeWorkspace;
import java.nio.file.*;
import java.util.*;

/** Isolated JVM only. Self-authored mapper-85 fixture, or a caller's read-only ROM. */
public final class RestoreRegressionProbe {
    static final List<LibretroProcess.Controls> ZERO = controls(0, 0);
    static List<LibretroProcess.Controls> controls(int a, int b) {
        return List.of(new LibretroProcess.Controls(new int[]{a,b},0));
    }
    static byte[] fixture(int mapper) {
        byte[] rom = new byte[16 + 32768 + 8192];
        rom[0]='N'; rom[1]='E'; rom[2]='S'; rom[3]=0x1a;
        rom[4]=2; rom[5]=1; rom[6]=(byte)((mapper<<4)|2); rom[7]=(byte)(mapper&0xf0);
        byte[] loop = {0x78, (byte)0xd8, (byte)0xa2, (byte)0xff, (byte)0x9a, 0x4c, 0x05, (byte)0xe0};
        System.arraycopy(loop,0,rom,16+0x6000,loop.length);
        for(int vector=0x7ffa;vector<=0x7ffe;vector+=2){rom[16+vector]=0;rom[17+vector]=(byte)0xe0;}
        return rom;
    }
    static void equal(byte[] a, byte[] b, String stage) {
        if(!Arrays.equals(a,b)) {
            int first=Arrays.mismatch(a,b);
            throw new AssertionError(stage+": state mismatch at "+first+", lengths "+a.length+"/"+b.length);
        }
    }
    public static void main(String[] args) throws Exception {
        Path out=Path.of(args[0]);Files.createDirectories(out.resolve("instance"));
        RuntimeWorkspace.configure(out.resolve("instance"));
        byte[] rom=args.length>1 && !args[1].startsWith("fixture:")?Files.readAllBytes(Path.of(args[1])):
                fixture(args.length>1?Integer.parseInt(args[1].substring(8)):85);
        try(var core=new LibretroJniRuntime(JniNetplaySession.profile(),NetplayProcess.class)) {
            var info=core.load(rom);System.out.println("INFO "+info);
            core.run(ZERO,0);core.reset();core.run(ZERO,0); // Production startup sequence.
            for(int round=0;round<6;round++) {
                byte[] before=core.serialize();core.restore(before);
                equal(before,core.serialize(),"round "+round+" exact restore");
                for(int i=0;i<30;i++)core.run(controls((i*13)&255,(i*7)&255),0);
                byte[] expected=core.serialize();
                core.restore(before);
                for(int i=0;i<30;i++)core.run(controls((i*13)&255,(i*7)&255),0);
                equal(expected,core.serialize(),"round "+round+" deterministic replay");
            }
            var frame=core.run(ZERO,3);
            if(frame.rgba().length!=256*240*4 || frame.stereo().length==0)throw new AssertionError("No AV output");
            var memory=core.saveMemory();byte[] state=core.serialize();
            core.run(controls(1,1),0);core.restoreSaveMemory(memory);core.restore(state);
            equal(state,core.serialize(),"SRAM + state restore");
            System.out.println("PASS exact startup, 6 x 30-frame replay, AV and save restore");
        }
        if(LibretroRuntimes.isJniBusy())throw new AssertionError("JNI slot leaked");
        if(args.length>1 && !args[1].startsWith("fixture:"))equal(rom,Files.readAllBytes(Path.of(args[1])),"original ROM unchanged");
        System.out.println("PASS closed");
    }
}
