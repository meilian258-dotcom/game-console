package cn.piq.fcarcade.core.wasm;

import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import cn.piq.fcarcade.core.NesCore;

/** Original diagnostic iNES images are constructed in memory, never commercial ROM files. */
public final class ZapperCoreProbe {
    private static int assertions;
    private static void require(boolean value,String message){assertions++;if(!value)throw new AssertionError(message);}
    private static void rejected(Runnable test,String message){boolean rejected=false;try{test.run();}catch(IllegalArgumentException expected){rejected=true;}require(rejected,message);}
    private static byte[] diagnostic(boolean target) {
        byte[] rom=new byte[16+16384+8192];rom[0]='N';rom[1]='E';rom[2]='S';rom[3]=26;rom[4]=1;rom[5]=1;
        var p=new ArrayList<Integer>();
        emit(p,0x78,0xd8,0xa2,0xff,0x9a,0xa9,0,0x8d,0,0x20,0x8d,1,0x20,0x8d,0x15,0x40,0xa9,0x40,0x8d,0x17,0x40);
        // Write black backdrop, white first pattern color; rendering has no sprite/entity tests.
        emit(p,0x2c,2,0x20,0xa9,0x3f,0x8d,6,0x20,0xa9,0,0x8d,6,0x20,0xa9,0x0f,0x8d,7,0x20,0xa9,0x30,0x8d,7,0x20);
        if(target)for(int row=13;row<17;row++){
            int address=0x2000+row*32+14;
            emit(p,0xa9,address>>8,0x8d,6,0x20,0xa9,address&255,0x8d,6,0x20,0xa9,1);
            for(int x=0;x<4;x++)emit(p,0x8d,7,0x20);
        }
        emit(p,0xa9,0,0x85,0,0x85,1,0x85,2,0x8d,5,0x20,0x8d,5,0x20,0xa9,8,0x8d,1,0x20);
        int loop=0x8000+p.size();
        emit(p,0xa9,1,0x8d,0x16,0x40,0xa9,0,0x8d,0x16,0x40,0xad,0x17,0x40,0x85,4);
        emit(p,0x29,8,0xd0,4,0xa9,1,0x85,0); // D3 low: detected light sometime during raster.
        emit(p,0xa5,4,0x29,16,0xf0,4,0xa9,1,0x85,1);
        emit(p,0xa5,4,0x29,1,0xf0,4,0xa9,1,0x85,2);
        emit(p,0x4c,loop&255,loop>>8);
        for(int n=0;n<p.size();n++)rom[16+n]=(byte)(int)p.get(n);
        for(int offset:new int[]{0x3ffa,0x3ffc,0x3ffe}){rom[16+offset]=0;rom[16+offset+1]=(byte)0x80;}
        for(int row=0;row<8;row++)rom[16+16384+16+row]=(byte)0xff;
        return rom;
    }
    private static void emit(List<Integer> p,int... values){for(int value:values)p.add(value);}
    private static byte[] ram(NesCore core){byte[] b=new byte[NesCore.CPU_RAM_BYTES];core.copyCpuRam(b);return b;}
    private static byte[] frame(NesCore core){byte[] b=new byte[NesCore.RGBA_BYTES];core.copyFrameRgba(b);return b;}
    private static void frames(NesCore core,int count){float[] samples=new float[4096];for(int n=0;n<count;n++){core.runFrame();core.copyAudioSamples(samples);}}
    private static void sensor(boolean target,int x,int y,boolean offscreen,boolean trigger,boolean pad,boolean expectedLight){
        try(var core=new ZapperWasmNesCore()){
            core.loadRom(diagnostic(target));core.setZapperState(x,y,offscreen,trigger);core.setControllerState(1,pad?1:0);frames(core,4);
            var ram=ram(core);require(ram[0]==(expectedLight?1:0),"raster light region");require(ram[1]==(trigger?1:0),"trigger independent");require(ram[2]==(pad?1:0),"P2 serial D0 remains intact");
            require((ram[4]&8)!=0,"light expires during vblank instead of a stale frame lookup");
            require(core.supportsZapper(),"capability");require(core.stateNamespace().startsWith("nes-zapper-v1/"),"state namespace");
            require(core.stateNamespace().length()==78,"module identity in namespace");
        }
    }
    public static void main(String[] args)throws Exception{
        sensor(false,128,120,false,false,false,false);
        sensor(false,128,120,false,true,true,false);
        sensor(true,128,120,false,false,false,true);
        sensor(true,128,120,false,true,true,true);
        sensor(true,20,20,false,true,false,false);
        sensor(true,128,120,true,true,true,false);
        try(var core=new ZapperWasmNesCore();var peer=new ZapperWasmNesCore();var other=new ZapperWasmNesCore();var legacy=new WasmNesCore()){
            byte[] rom=diagnostic(true);core.loadRom(rom);peer.loadRom(rom);other.loadRom(diagnostic(false));legacy.loadRom(rom);
            require(!legacy.supportsZapper(),"old core remains controller-only");
            core.setControllerState(1,1);core.setZapperState(128,120,false,true);frames(core,4);
            byte[] saved=core.saveTransientState();peer.loadTransientState(saved);
            frames(core,3);frames(peer,3);require(Arrays.equals(frame(core),frame(peer)),"state restored frame");require(Arrays.equals(ram(core),ram(peer)),"state restored RAM/input");
            byte[] before=core.saveTransientState();
            rejected(()->core.loadTransientState(legacy.saveTransientState()),"legacy state rejected by gun");
            rejected(()->legacy.loadTransientState(saved),"gun state rejected by legacy");
            rejected(()->other.loadTransientState(saved),"wrong ROM rejected");
            for(int offset:new int[]{4,8,40,72,108,112,116,120}){byte[] bad=saved.clone();bad[offset]^=1;rejected(()->core.loadTransientState(bad),"identity/ABI tamper "+offset);}
            rejected(()->core.loadTransientState(Arrays.copyOf(saved,saved.length-1)),"truncated state rejected");
            rejected(()->core.loadTransientState(Arrays.copyOf(saved,saved.length+1)),"trailing compressed data rejected");
            require(Arrays.equals(before,core.saveTransientState()),"invalid states cannot mutate live memory");
            rejected(()->core.setZapperState(256,10,false,false),"out-of-range aim rejected");
            core.setZapperState(128,120,true,false);core.reset();frames(core,3);require(ram(core)[0]==0&&ram(core)[1]==0,"reset clears gun");
        }
        System.out.println("{\"ok\":true,\"assertions\":"+assertions+",\"actual_wasm_core\":true,\"minecraft_started\":false,\"commercial_rom_used\":false}");
    }
}
