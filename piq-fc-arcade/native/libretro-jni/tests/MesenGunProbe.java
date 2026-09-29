// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.retro.libretro.jni;
import java.io.ByteArrayOutputStream;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
/** Original redistributable NES light/trigger test; isolated JVM, no game assets. */
public final class MesenGunProbe {
    static void check(boolean b,String message){if(!b)throw new AssertionError(message);}
    public static void main(String[] args)throws Exception {
        System.load(Path.of(args[0]).toAbsolutePath().toString());
        Path work=Path.of(args[2]).toAbsolutePath();Files.createDirectories(work);
        Path rom=work.resolve("光枪原创诊断.nes");Files.write(rom,rom(),StandardOpenOption.CREATE_NEW);
        ByteBuffer video=ByteBuffer.allocateDirect(2048*2048*4),pcm=ByteBuffer.allocateDirect(65536);
        int[] meta=new int[11];double[] timing=new double[3];
        int[][] phases={{128,120,0,1,1},{20,20,0,1,0},{128,120,0,0,1},{128,120,1,1,0},{128,120,1,0,0},{112,104,0,1,1},{143,135,0,1,1}};
        for(int cycle=0;cycle<3;cycle++){
            long h=NativeLibretroBridge.open(args[1],rom.toString(),work.toString(),work.toString(),"Mesen",true,new int[]{257,262},new String[]{"mesen_region","NTSC","mesen_ramstate","All 0s (Default)","mesen_audio_sample_rate","44100"},16);
            try {
                for(int[] p:phases){
                    int packed=(p[2]==0?p[0]|p[1]<<8:0)|p[2]<<16|p[3]<<17;
                    int[] in={0,0,0,0,-1,0,0,0,0,0,0,-1,packed};
                    for(int frame=0;frame<120;frame++)NativeLibretroBridge.step(h,video,pcm,in,new int[0],meta,timing);
                    byte[] ram=NativeLibretroBridge.memory(h,2);
                    check(ram.length>0x32,"CPU RAM available");
                    int gun=ram[0x30]&255,light=ram[0x31]&255;
                    check((gun&16)==p[3]*16,"trigger expected="+p[3]+" got="+gun);
                    check(light==p[4],"light x="+p[0]+" y="+p[1]+" expected="+p[4]+" got="+light);
                    System.out.println("PASS cycle="+cycle+" x="+p[0]+" y="+p[1]+" off="+p[2]+" trigger="+p[3]+" light="+light);
                }
            } finally {NativeLibretroBridge.close(h);}
        }
        System.out.println("MESEN_JNI_GUN_OK phases=21 cycles=3");
    }
    static byte[] rom(){
        byte[] r=new byte[16+16384+8192];r[0]='N';r[1]='E';r[2]='S';r[3]=26;r[4]=1;r[5]=1;
        Code c=new Code();c.bytes(0x78,0xd8,0xa2,0xff,0x9a,0xe8,0x8e,0,0x20,0x8e,1,0x20,0x8e,0x10,0x40);
        c.label("warm1");c.bytes(0x2c,2,0x20);c.branch(0x10,"warm1");
        c.label("warm2");c.bytes(0x2c,2,0x20);c.branch(0x10,"warm2");
        c.write(0x2006,0x20);c.write(0x2006,0);c.bytes(0xa2,4,0xa0,0,0xa9,0);
        c.label("clear");c.bytes(0x8d,7,0x20,0xc8);c.branch(0xd0,"clear");c.bytes(0xca);c.branch(0xd0,"clear");
        c.write(0x2006,0x3f);c.write(0x2006,0);c.write(0x2007,0x0f);c.write(0x2007,0x30);
        for(int row=13;row<17;row++){int at=0x2000+row*32+14;c.write(0x2006,at>>8);c.write(0x2006,at&255);for(int i=0;i<4;i++)c.write(0x2007,1);}
        c.write(0x2005,0);c.write(0x2005,0);c.write(0x2000,0);c.write(0x2001,0x0a);
        c.write(0x4015,1);c.write(0x4000,0xbf);c.write(0x4001,0);c.write(0x4002,0xfd);c.write(0x4003,8);
        c.label("frame");c.bytes(0xad,0x17,0x40,0x85,0x30,0x29,8);c.branch(0xd0,"dark");c.bytes(0xa9,1,0x85,0x32);
        c.label("dark");c.bytes(0x2c,2,0x20);c.branch(0x10,"frame");
        c.bytes(0xa5,0x32,0x85,0x31,0xa9,0,0x85,0x32,0xe6,0);c.write(0x4016,1);c.write(0x4016,0);c.bytes(0xa2,0);
        c.label("pad");c.bytes(0xad,0x16,0x40,0x29,1,0x95,0x10,0xe8,0xe0,8);c.branch(0xd0,"pad");c.jump("frame");
        byte[] p=c.finish();System.arraycopy(p,0,r,16,p.length);
        for(int i=0;i<3;i++){r[16+16384-6+i*2]=0;r[16+16384-5+i*2]=(byte)0x80;}
        Arrays.fill(r,16+16384+16,16+16384+24,(byte)255);return r;
    }
    static class Code {
        final ByteArrayOutputStream b=new ByteArrayOutputStream();final Map<String,Integer> labels=new HashMap<>();final List<int[]> fixes=new ArrayList<>();final List<String> names=new ArrayList<>();
        void bytes(int... x){for(int v:x)b.write(v);}void label(String n){labels.put(n,b.size());}
        void write(int a,int v){bytes(0xa9,v,0x8d,a&255,a>>8);}void branch(int op,String n){bytes(op);fixes.add(new int[]{b.size(),1});names.add(n);bytes(0);}
        void jump(String n){bytes(0x4c);fixes.add(new int[]{b.size(),2});names.add(n);bytes(0,0);}
        byte[] finish(){byte[] r=b.toByteArray();for(int i=0;i<fixes.size();i++){int[] f=fixes.get(i);int t=labels.get(names.get(i));if(f[1]==1){int d=t-f[0]-1;check(d>=-128&&d<=127,"branch");r[f[0]]=(byte)d;}else{r[f[0]]=(byte)t;r[f[0]+1]=(byte)(0x80+(t>>8));}}return r;}
    }
}
