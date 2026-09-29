package cn.piq.fcarcade.core.wasm;

import ai.tegmentum.wasmtime4j.*;
import ai.tegmentum.wasmtime4j.jni.JniWasmRuntime;
import cn.piq.fcarcade.core.NesCore;
import cn.piq.fcarcade.rom.INesHeader;
import cn.piq.fcarcade.rom.NesCompatibility;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;

/** Independent Mapper 19 module. Old FC/Zapper WASM and raw-memory saves remain untouched.
 * Initial compatibility targets banked cartridges without N163 expansion audio.
 */
public final class NamcoWasmNesCore implements NesCore {
    public static final String MODULE_RESOURCE="/core/nes_mapper19_v1.wasm";
    public static final String MODULE_SHA256="900467682864994b1e80d8d288ef0864da60927ee6213b19310576379b330985";
    private static final int MAGIC=0x504E3139, VERSION=1, MAX_MEMORY=64*1024*1024, AUDIO_CAPACITY=4096;
    private static final int[][] BUTTONS={{4,5,2,3,6,7,8,9},{10,11,-1,-1,12,13,14,15}};
    private final Thread owner=Thread.currentThread();
    private final WasmRuntime runtime; private final Engine engine;
    private final ai.tegmentum.wasmtime4j.Module module; private final Store store; private final Instance instance;
    private final WasmMemory memory; private final Map<String,WasmFunction> functions=new HashMap<>();
    private final int nes,frame,audio,ram; private final int[] buttons=new int[2];
    private final byte[] moduleHash; private byte[] romHash,baseline,baselineHash;
    private boolean loaded,closed;

    public NamcoWasmNesCore() {
        WasmRuntime r=null;Engine e=null;ai.tegmentum.wasmtime4j.Module m=null;Store s=null;Instance i=null;
        try {
            byte[] bytes;
            try(InputStream in=NamcoWasmNesCore.class.getResourceAsStream(MODULE_RESOURCE)) {
                if(in==null)throw new IllegalStateException("Missing independent Mapper19 module");
                bytes=in.readNBytes(4*1024*1024+1);
                if(bytes.length>4*1024*1024)throw new IllegalStateException("Mapper19 module too large");
            }
            moduleHash=hash(bytes);
            if (!HexFormat.of().formatHex(moduleHash).equals(MODULE_SHA256)) throw new IllegalStateException("Mapper 19 module identity mismatch");
            r=new JniWasmRuntime();e=r.createEngine();m=e.compileModule(bytes);s=e.createStore();i=m.instantiate(s);
            runtime=r;engine=e;module=m;store=s;instance=i;
            memory=instance.getMemory("memory").orElseThrow();
            for(String name:new String[]{"nes_create","nes_destroy","nes_alloc","nes_dealloc","nes_set_rom","nes_bootup","nes_reset","nes_step_frame","nes_copy_frame","nes_copy_audio","nes_copy_cpu_ram","nes_press_button","nes_release_button"})
                functions.put(name,instance.getFunction(name).orElseThrow(()->new IllegalStateException("Missing Mapper19 export: "+name)));
            nes=call("nes_create");if(nes==0)throw new IllegalStateException("Mapper19 core allocation failed");
            frame=allocate(RGBA_BYTES);audio=allocate(AUDIO_CAPACITY*4);ram=allocate(CPU_RAM_BYTES);
        }catch(Throwable failure){quiet(i);quiet(s);quiet(m);quiet(e);quiet(r);throw new IllegalStateException("Mapper19 core initialization failed",failure);}
    }
    @Override public String stateNamespace(){return "nes-mapper19-v1/"+HexFormat.of().formatHex(moduleHash);}
    @Override public void loadRom(byte[] rom) {
        open();if(loaded)throw new IllegalStateException("Use a new Mapper19 core for another ROM");
        var header=INesHeader.parse(rom);NesCompatibility.requireMapper19Supported(header);romHash=hash(rom);
        byte[] normalized=rom;
        if(header.trainerPresent()){
            normalized=new byte[rom.length-INesHeader.TRAINER_BYTES];
            System.arraycopy(rom,0,normalized,0,INesHeader.HEADER_BYTES);
            System.arraycopy(rom,INesHeader.HEADER_BYTES+INesHeader.TRAINER_BYTES,normalized,INesHeader.HEADER_BYTES,normalized.length-INesHeader.HEADER_BYTES);
            normalized[6]&=~4;
        }
        int pointer=allocate(normalized.length);memory.writeBytes(pointer,normalized,0,normalized.length);
        call("nes_set_rom",nes,pointer,normalized.length);call("nes_bootup",nes);loaded=true;
        baseline=wholeMemory();baselineHash=hash(baseline);
    }
    @Override public void reset(){ready();call("nes_reset",nes);setControllerState(0,0);setControllerState(1,0);}
    @Override public void setControllerState(int player,int mask){
        ready();if(player<0||player>1)throw new IllegalArgumentException("Controller port must be 0 or 1");
        int normalized=mask&255,changed=normalized^buttons[player];
        for(int bit=0;bit<8;bit++)if((changed&(1<<bit))!=0&&BUTTONS[player][bit]>=0)
            call((normalized&(1<<bit))!=0?"nes_press_button":"nes_release_button",nes,BUTTONS[player][bit]);
        buttons[player]=normalized;
    }
    @Override public void runFrame(){ready();call("nes_step_frame",nes);}
    @Override public void copyFrameRgba(byte[] out){ready();if(out.length!=RGBA_BYTES)throw new IllegalArgumentException("Frame length");call("nes_copy_frame",nes,frame,out.length);memory.readBytes(frame,out,0,out.length);}
    @Override public void copyCpuRam(byte[] out){ready();if(out.length!=CPU_RAM_BYTES)throw new IllegalArgumentException("RAM length");call("nes_copy_cpu_ram",nes,ram,out.length);memory.readBytes(ram,out,0,out.length);}
    @Override public int copyAudioSamples(float[] out){
        ready();if(out.length==0)return 0;int capacity=Math.min(out.length,AUDIO_CAPACITY),count=call("nes_copy_audio",nes,audio,capacity);
        if(count<0||count>capacity)throw new IllegalStateException("Audio length");
        byte[] bytes=new byte[count*4];memory.readBytes(audio,bytes,0,bytes.length);var buffer=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        for(int n=0;n<count;n++)out[n]=buffer.getFloat();return count;
    }
    @Override public byte[] saveTransientState(){
        ready();byte[] current=wholeMemory(),delta=new byte[current.length];
        for(int n=0;n<current.length;n++)delta[n]=(byte)(current[n]^(n<baseline.length?baseline[n]:0));
        try{
            var bytes=new ByteArrayOutputStream();var h=new DataOutputStream(bytes);
            h.writeInt(MAGIC);h.writeInt(VERSION);h.write(moduleHash);h.write(romHash);h.write(baselineHash);
            h.writeInt(current.length);for(int p:new int[]{nes,frame,audio,ram})h.writeInt(p);
            h.writeByte(buttons[0]);h.writeByte(buttons[1]);h.flush();
            var deflater=new Deflater(Deflater.BEST_SPEED);
            try(var compressed=new DeflaterOutputStream(bytes,deflater)){compressed.write(delta);}finally{deflater.end();}
            return bytes.toByteArray();
        }catch(IOException failure){throw new IllegalStateException("Mapper19 state encoding failed",failure);}
    }
    @Override public void loadTransientState(byte[] state){
        ready();if(state==null||state.length<128||state.length>MAX_MEMORY+1024*1024)throw new IllegalArgumentException("Invalid Mapper19 state size");
        // Decode and validate completely before the first WASM-memory mutation.
        try{
            var in=new ByteArrayInputStream(state);var h=new DataInputStream(in);
            if(h.readInt()!=MAGIC||h.readInt()!=VERSION)throw new IllegalArgumentException("Not a Mapper19 v1 state; legacy NES states are forbidden");
            for(byte[] expected:new byte[][]{moduleHash,romHash,baselineHash})
                if(!MessageDigest.isEqual(expected,h.readNBytes(32)))throw new IllegalArgumentException("Mapper19 module/ROM/baseline identity mismatch");
            int size=h.readInt();if(size<baseline.length||size>MAX_MEMORY||(size&65535)!=0)throw new IllegalArgumentException("Mapper19 state memory length");
            for(int pointer:new int[]{nes,frame,audio,ram})if(h.readInt()!=pointer)throw new IllegalArgumentException("Mapper19 allocation identity mismatch");
            int p1=h.readUnsignedByte(),p2=h.readUnsignedByte();byte[] packed=in.readAllBytes(),delta=new byte[size];var inflater=new Inflater();
            try{
                inflater.setInput(packed);int at=0;
                while(at<size){int count=inflater.inflate(delta,at,size-at);if(count==0)break;at+=count;}
                byte[] excess=new byte[1];int more=inflater.inflate(excess);
                if(at!=size||more!=0||!inflater.finished()||inflater.needsDictionary()||inflater.getRemaining()!=0)throw new IllegalArgumentException("Truncated, trailing or oversized Mapper19 state");
            }finally{inflater.end();}
            for(int n=0;n<size;n++)delta[n]^=n<baseline.length?baseline[n]:0;
            long present=memory.dataSize();if(present<size)memory.grow64((size-present+65535)/65536);
            if(memory.dataSize()<size)throw new IllegalStateException("Mapper19 memory growth failed");
            memory.writeBytes(0,delta,0,size);buttons[0]=p1;buttons[1]=p2;
        }catch(IOException|DataFormatException failure){throw new IllegalArgumentException("Invalid Mapper19 state",failure);}
    }
    @Override public void close(){
        sameThread();if(closed)return;
        try{call("nes_dealloc",ram,CPU_RAM_BYTES);call("nes_dealloc",audio,AUDIO_CAPACITY*4);call("nes_dealloc",frame,RGBA_BYTES);call("nes_destroy",nes);}
        finally{closed=true;quiet(instance);quiet(store);quiet(module);quiet(engine);quiet(runtime);}
    }
    private int allocate(int size){int p=call("nes_alloc",size);if(p==0)throw new IllegalStateException("Mapper19 allocation failed");return p;}
    private byte[] wholeMemory(){long size=memory.dataSize();if(size<=0||size>MAX_MEMORY)throw new IllegalStateException("Mapper19 memory limit");byte[] out=new byte[(int)size];memory.readBytes(0,out,0,out.length);return out;}
    private int call(String name,int... args){try{WasmValue[] values=new WasmValue[args.length];for(int n=0;n<args.length;n++)values[n]=WasmValue.i32(args[n]);WasmValue[] out=functions.get(name).call(values);return out.length==0?0:out[0].asInt();}catch(Exception failure){throw new IllegalStateException("Mapper19 export failed: "+name,failure);}}
    private void sameThread(){if(Thread.currentThread()!=owner)throw new IllegalStateException("Mapper19 core belongs to its construction thread");}
    private void open(){sameThread();if(closed)throw new IllegalStateException("Mapper19 core closed");}
    private void ready(){open();if(!loaded)throw new IllegalStateException("No Mapper19 ROM loaded");}
    private static byte[] hash(byte[] bytes){try{return MessageDigest.getInstance("SHA-256").digest(bytes);}catch(java.security.NoSuchAlgorithmException e){throw new AssertionError(e);}}
    private static void quiet(AutoCloseable value){if(value!=null)try{value.close();}catch(Exception ignored){}}
}
