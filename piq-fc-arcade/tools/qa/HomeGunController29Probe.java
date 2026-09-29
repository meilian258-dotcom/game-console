package cn.piq.fcarcade.core.wasm;

import cn.piq.fcarcade.core.NesCore;
import java.nio.file.Path;
import java.util.*;

/** Original diagnostic: continuously read all eight $4016 P1 bits and $4017 gun bits. */
public final class HomeGunController29Probe {
    static int assertions;
    static void check(boolean value,String why){assertions++;if(!value)throw new AssertionError(why);}
    static void emit(List<Integer> p,int... values){for(int v:values)p.add(v);}
    static byte[] diagnostic()throws Exception{
        var base=ZapperCoreProbe.class.getDeclaredMethod("diagnostic",boolean.class);base.setAccessible(true);
        byte[] rom=(byte[])base.invoke(null,true);int at=-1;
        int[] signature={0xa9,1,0x8d,0x16,0x40,0xa9,0,0x8d,0x16,0x40};
        for(int i=16;i<16+16384-signature.length;i++){boolean same=true;for(int j=0;j<signature.length;j++)same&=(rom[i+j]&255)==signature[j];if(same){at=i;break;}}
        check(at>=16,"original diagnostic polling loop found");var p=new ArrayList<Integer>();emit(p,signature);
        for(int bit=0;bit<8;bit++)emit(p,0xad,0x16,0x40,0x29,1,0x85,8+bit);
        emit(p,0xad,0x17,0x40,0x85,4,0x29,16,0x85,1);
        emit(p,0xa5,4,0x29,8,0xd0,4,0xa9,1,0x85,0);
        int loop=0x8000+at-16;emit(p,0x4c,loop&255,loop>>8);
        for(int i=0;i<p.size();i++)rom[at+i]=(byte)(int)p.get(i);return rom;
    }
    static byte[] ram(NesCore c){byte[] out=new byte[NesCore.CPU_RAM_BYTES];c.copyCpuRam(out);return out;}
    static void run(NesCore c,int count){float[] out=new float[4096];for(int i=0;i<count;i++){c.runFrame();c.copyAudioSamples(out);}}
    static void controls(NesCore c,int mask,boolean trigger){c.setControllerState(0,mask);c.setControllerState(1,0);c.setZapperState(128,120,false,trigger);run(c,3);byte[] r=ram(c);for(int i=0;i<8;i++)check(r[8+i]==((mask>>>i)&1),"actual P1 serial bit "+i+" mask "+mask);check(r[1]==(trigger?16:0),"actual P2 trigger independent of P1");check(r[0]==1,"P2 optical light detected");}
    public static void main(String[] args)throws Exception{
        Path jar=Path.of(args[0]).toRealPath();check(Path.of(ZapperWasmNesCore.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath().equals(jar),"core source is supplied final jar");
        byte[] rom=diagnostic();try(var c=new ZapperWasmNesCore();var peer=new ZapperWasmNesCore()){
            c.loadRom(rom);peer.loadRom(rom);controls(c,0,true);for(int i=0;i<8;i++)controls(c,1<<i,true);
            controls(c,255,true);controls(c,0,true);controls(c,128,false);controls(c,0,false);
            controls(c,0x55,true);peer.loadTransientState(c.saveTransientState());
            for(int i=0;i<12;i++){int mask=(i&1)==0?0x33:0xcc;boolean fire=i%3!=0;c.setControllerState(0,mask);peer.setControllerState(0,mask);c.setZapperState(128,120,false,fire);peer.setZapperState(128,120,false,fire);c.runFrame();peer.runFrame();
                byte[] a=new byte[NesCore.RGBA_BYTES],b=new byte[NesCore.RGBA_BYTES];c.copyFrameRgba(a);peer.copyFrameRgba(b);check(Arrays.equals(a,b),"snapshot picture "+i);check(Arrays.equals(ram(c),ram(peer)),"snapshot RAM "+i);
                float[] x=new float[4096],y=new float[4096];int n=c.copyAudioSamples(x),m=peer.copyAudioSamples(y);check(n==m&&Arrays.equals(Arrays.copyOf(x,n),Arrays.copyOf(y,m)),"snapshot PCM "+i);
            }
        }
        System.out.println("{\"ok\":true,\"assertions\":"+assertions+",\"p1_bits\":8,\"gun_physical_port\":2,\"snapshot_consistent_frames\":12,\"actual_wasm\":true,\"production_origin\":\"final-jar-only\",\"production_compiled\":false,\"minecraft_started\":false,\"commercial_rom_used\":false}");
    }
}
