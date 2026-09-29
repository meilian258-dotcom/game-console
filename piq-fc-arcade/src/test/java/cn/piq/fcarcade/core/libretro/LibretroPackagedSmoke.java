package cn.piq.fcarcade.core.libretro;

import java.util.Arrays;

/** Only the final mod JAR and this class: no external JNA/core directories on the classpath. */
public final class LibretroPackagedSmoke {
    public static void main(String[] args) {
        byte[] rom = new byte[16+16384+8192];
        rom[0]='N';rom[1]='E';rom[2]='S';rom[3]=0x1a;rom[4]=1;rom[5]=1;
        rom[16]=0x4c;rom[17]=0;rom[18]=(byte)0x80;
        for(int i=16+16384-6;i<16+16384;i+=2){rom[i]=0;rom[i+1]=(byte)0x80;}
        try(var a=new LibretroNesCore(false);var b=new LibretroNesCore(false)) {
            a.loadRom(rom);b.loadRom(rom);
            for(int i=0;i<20;i++)a.runFrame();
            byte[] state=a.saveTransientState();b.loadTransientState(state);
            byte[] fa=new byte[256*240*4],fb=new byte[fa.length];
            for(int i=0;i<30;i++) {
                a.runFrame();b.runFrame();a.copyFrameRgba(fa);b.copyFrameRgba(fb);
                if(!Arrays.equals(fa,fb))throw new AssertionError("Packaged core frame divergence");
            }
            System.out.println("FC packaged libretro: two independent workers, 30 matching post-restore frames; state="+state.length);
        }
    }
}
