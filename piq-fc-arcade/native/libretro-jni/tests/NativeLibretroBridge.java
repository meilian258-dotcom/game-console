// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.retro.libretro.jni;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
/** Independent test-only ABI declarations. Never package this class in the mod. */
public class NativeLibretroBridge {
    public static native int abiVersion();
    public static native int runtimeDependencyApiVersion();
    public static native void retainRuntimeDependencies(String[] paths,String[] sha256)throws IOException;
    public static native int availableSlots();
    public static native long reserve()throws IOException;
    public static native boolean reservationHeld(long token);
    public static native long openReserved(long token,String core,String content,String system,String save,String expected,boolean path,int[] devices,String[] pins,int features)throws IOException;
    static long lastReservation;
    public static long open(String core,String content,String system,String save,String expected,boolean path,int[] devices,String[] pins,int features)throws IOException{
        long token=reserve();lastReservation=token;
        return openReserved(token,core,content,system,save,expected,path,devices,pins,features);
    }
    static boolean anyReservation(){return availableSlots()!=4;}
    public static native void metadata(long h,int[] ints,double[] numbers)throws IOException;
    public static native String coreVersion(long h)throws IOException;
    public static native int saveCapabilities(long h)throws IOException;
    public static native void step(long h,ByteBuffer rgba,ByteBuffer pcm,int[] input,int[] keys,int[] ints,double[] numbers)throws IOException;
    public static native byte[] serialize(long h)throws IOException;
    public static native void restore(long h,byte[] state)throws IOException;
    public static native byte[] memory(long h,int id)throws IOException;
    public static native void restoreMemory(long h,byte[] ram,byte[] rtc)throws IOException;
    public static native void reset(long h)throws IOException;
    public static native void close(long h)throws IOException;
    static int checks;
    static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    interface Io {void run()throws Exception;}
    static void rejects(Io task,String message)throws Exception{try{task.run();throw new AssertionError("Accepted "+message);}catch(IOException expected){checks++;}}
    public static void main(String[] args)throws Exception{
        System.load(Path.of(args[0]).toAbsolutePath().toString());check(abiVersion()==2,"ABI");
        String core=Path.of(args[1]).toAbsolutePath().toString(),work=Path.of(args[2]).toAbsolutePath().toString();
        check(!anyReservation(),"no initial native reservation");
        if(args.length>3&&args[3].equals("startup-cleanup-failure")){
            rejects(()->open(core,work+"/mode35.bin",work,work,"PIQ mock",false,new int[]{1},new String[0],1),"startup and cleanup failure");
            long failed=lastReservation;check(reservationHeld(failed),"failed open retains its known reservation");
            var retained=new AtomicReference<Boolean>();Thread observer=new Thread(()->retained.set(reservationHeld(failed)));observer.start();observer.join();check(Boolean.TRUE.equals(retained.get()),"foreign observer can see retained reservation without owner lock");
            rejects(()->close(failed),"startup teardown failure prevents retrying that slot");
            long spare=open(core,work+"/normal.bin",work,work,"PIQ mock",false,new int[]{1},new String[0],0);
            close(spare);check(availableSlots()==3,"startup teardown failure only quarantines its slot");
            System.out.println("JNI_STARTUP_QUARANTINE_OK checks="+checks);return;
        }
        ByteBuffer rgba=ByteBuffer.allocateDirect(2048*2048*4).order(ByteOrder.LITTLE_ENDIAN),pcm=ByteBuffer.allocateDirect(65536).order(ByteOrder.LITTLE_ENDIAN);
        int[] in={1,2,4,8,0,100,-200,3,7,-8,1,-1,0},meta=new int[11];double[] timing=new double[3];
        for(int cycle=0;cycle<10;cycle++){
            long h=open(core,work+"/normal.bin",work,work,"PIQ mock",false,new int[]{1,1,1,1},new String[]{"piq_mode","normal"},14);
            check(reservationHeld(h),"active native reservation");
            check(coreVersion(h).equals("abi1"),"version");check(saveCapabilities(h)==3,"save capabilities");metadata(h,meta,timing);check(meta[0]==2&&meta[2]==4&&timing[2]==32040,"initial AV");
            rejects(()->open(core,work+"/normal.bin",work,work,"PIQ mock",false,new int[]{1},new String[0],0),"second active");
            var failure=new AtomicReference<Throwable>();Thread thread=new Thread(()->{try{metadata(h,new int[11],new double[3]);failure.set(new AssertionError("cross thread accepted"));}catch(IOException expected){}catch(Throwable t){failure.set(t);}});thread.start();thread.join();check(failure.get()==null,"cross thread rejected");
            for(int f=1;f<=4;f++){step(h,rgba,pcm,in,f==1?new int[]{1,65,65,0}:new int[0],meta,timing);check(meta[5]==((4-f)%4),"rotation mapping");check(meta[6]==16&&meta[7]==6,"output lengths");check(meta[8]==(f==4?1:0),"duplicate metadata");check(rgba.getInt(0)==0xff0000ff&&rgba.getInt(4)==0xffff0000&&rgba.getInt(8)==0xff00ff00&&rgba.getInt(12)==-1,"pixel conversion/pitch");check(pcm.getShort(0)==1234&&pcm.getShort(2)==-2345,"PCM LE");}
            byte[] memory=memory(h,0);check(memory[0]==1&&memory[2]==2&&memory[4]==4&&memory[6]==8,"four ports");check(memory[8]==1&&memory[9]==1&&memory[10]==1&&memory[11]==1,"pointer/mouse/keyboard");
            int[] released={0,0,0,0,-1,0,0,0,0,0,0,-1,0};
            step(h,rgba,pcm,released,new int[]{0,65,0,0},meta,timing);
            byte[] clear=memory(h,0);check(clear[0]==0&&clear[2]==0&&clear[4]==0&&clear[6]==0&&clear[9]==0&&clear[10]==0&&clear[11]==0,"all four ports and mouse/key released");
            step(h,rgba,pcm,in,new int[]{1,65,65,0},meta,timing);memory=memory(h,0);
            check(memory[0]==1&&memory[2]==2&&memory[4]==4&&memory[6]==8&&memory[8]==3&&memory[11]==1,"repeated four-port actions");
            byte[] saved=serialize(h);rejects(()->step(h,ByteBuffer.allocateDirect(4),pcm,in,new int[0],meta,timing),"short buffer");check(Arrays.equals(saved,serialize(h)),"bad input never runs core");
            int[] bad=in.clone();bad[0]=65536;rejects(()->step(h,rgba,pcm,bad,new int[0],meta,timing),"overflow pad");rejects(()->step(h,rgba,pcm,in,new int[]{1,65,0xd800,0},meta,timing),"surrogate character");
            byte[] ram=memory.clone();Arrays.fill(ram,(byte)0x5a);rejects(()->restoreMemory(h,ram,new byte[9]),"mismatched RTC");check(Arrays.equals(memory,memory(h,0)),"dual-region atomic validation");
            restoreMemory(h,ram,new byte[8]);check(memory(h,0)[0]==0x5a,"RAM restoration");restore(h,saved);check(Arrays.equals(saved,serialize(h)),"state roundtrip");rejects(()->restore(h,new byte[63]),"wrong state size");rejects(()->memory(h,4),"bad memory id");reset(h);check(serialize(h)[0]==0,"reset");close(h);check(!reservationHeld(h)&&!anyReservation(),"normal close releases reservation");rejects(()->close(h),"stale handle");
        }
        for(int mode:new int[]{7,8,9}){long h=open(core,work+"/mode"+mode+".bin",work,work,"PIQ mock",false,new int[]{1},new String[0],0);if(mode==9)rejects(()->memory(h,0),"oversized RAM");else rejects(()->step(h,rgba,pcm,new int[]{0,0,0,0,-1,0,0,0,0,0,0,-1,0},new int[0],meta,timing),"bad callback");close(h);}
        rejects(()->open(core,work+"/normal.bin",work,work,"wrong",false,new int[]{1},new String[0],0),"wrong name");
        rejects(()->open(core,work+"/normal.bin",work,work,"PIQ mock",false,new int[]{1},new String[]{"piq_mode","bad"},0),"wrong option");
        rejects(()->open(core+".missing",work+"/normal.bin",work,work,"PIQ mock",false,new int[]{1},new String[0],0),"missing core");
        rejects(()->open(core,work+"/missing.bin",work,work,"PIQ mock",false,new int[]{1},new String[0],0),"missing content");
        rejects(()->open(core,work+"/mode10.bin",work,work,"PIQ mock",false,new int[]{1},new String[]{"piq_inline","2"},0),"legacy options require explicit flag");
        rejects(()->open(core,work+"/mode10.bin",work,work,"PIQ mock",false,new int[]{1},new String[]{"piq_inline","bad"},64),"legacy options still validate pins");
        long legacy=open(core,work+"/mode10.bin",work,work,"PIQ mock",false,new int[]{1},new String[]{"piq_inline","2"},64);close(legacy);checks++;
        long unicode=open(core,work+"/中文测试.bin",work,work,"PIQ mock",false,new int[]{1},new String[0],0);close(unicode);checks++;
        long stub=open(core,work+"/mode11.bin",work,work,"PIQ mock",false,new int[]{1},new String[0],0);
        check(saveCapabilities(stub)==2,"positive stub size does not imply save state");
        step(stub,rgba,pcm,new int[]{0,0,0,0,-1,0,0,0,0,0,0,-1,0},new int[0],meta,timing);
        check(meta[6]==16,"unsupported state probe does not stop core");close(stub);
        long noGame=open(core,"",work,work,"PIQ mock",false,new int[]{1},new String[0],32);close(noGame);checks++;
        for(int mode:new int[]{32,33}){long h=open(core,work+"/mode"+mode+".bin",work,work,"PIQ mock",false,new int[]{1},new String[0],1);step(h,rgba,pcm,new int[]{0,0,0,0,-1,0,0,0,0,0,0,-1,0},new int[0],meta,timing);check(meta[10]==1,"hardware metadata");check(rgba.getInt(0)==(mode==32?0xff0000ff:0xffff0000),"hardware row origin");close(h);}
        long brokenClose=open(core,work+"/mode34.bin",work,work,"PIQ mock",false,new int[]{1},new String[0],1);
        rejects(()->close(brokenClose),"Win32 teardown failure detected");
        check(reservationHeld(brokenClose),"failed close retains reservation");
        rejects(()->close(brokenClose),"failed teardown cannot retry into free slot");
        rejects(()->saveCapabilities(brokenClose),"failed teardown blocks all core calls");
        long spare=open(core,work+"/normal.bin",work,work,"PIQ mock",false,new int[]{1},new String[0],0);
        close(spare);check(availableSlots()==3,"failed teardown only quarantines its own slot");
        System.out.println("JNI_MOCK_OK checks="+checks);
    }
}
