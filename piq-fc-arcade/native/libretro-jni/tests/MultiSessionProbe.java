// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.retro.libretro.jni;

import java.io.IOException;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static cn.piq.retro.libretro.jni.NativeLibretroBridge.*;

/** Independent JVM probe: private DLL copies, concurrent software/WGL, stale tokens and failures. */
public final class MultiSessionProbe {
    static final AtomicInteger checks = new AtomicInteger();
    static void check(boolean ok, String why) { checks.incrementAndGet(); if (!ok) throw new AssertionError(why); }
    static void rejects(NativeLibretroBridge.Io io, String why) throws Exception {
        try { io.run(); throw new AssertionError(why); } catch (IOException expected) { checks.incrementAndGet(); }
    }
    static final class Run implements AutoCloseable {
        final long token;
        final ByteBuffer video=ByteBuffer.allocateDirect(64).order(ByteOrder.LITTLE_ENDIAN);
        final ByteBuffer audio=ByteBuffer.allocateDirect(65536).order(ByteOrder.LITTLE_ENDIAN);
        final int[] meta=new int[11];final double[] timing=new double[3];
        Run(Path core, Path root, String label, int mode) throws Exception {
            Path work=Files.createDirectory(root.resolve(label));
            Path copy=Files.copy(core,work.resolve("core.dll"));
            Path content=work.resolve("game.bin");Files.write(content,new byte[]{(byte)mode});
            token=reserve();
            check(openReserved(token,copy.toString(),content.toString(),work.toString(),work.toString(),
                    "PIQ mock",false,new int[]{1,1,1,1},new String[0],mode>=32?1:0)==token,"reservation identity");
        }
        void frame(int mask) throws IOException {
            step(token,video,audio,new int[]{mask,mask*2,mask*4,mask*8,-1,0,0,0,0,0,0,-1,0},new int[0],meta,timing);
        }
        public void close() throws IOException { NativeLibretroBridge.close(token); }
    }
    public static void main(String[] args) throws Exception {
        System.load(Path.of(args[0]).toAbsolutePath().toString());
        Path core=Path.of(args[1]).toAbsolutePath(),root=Path.of(args[2]).toAbsolutePath();
        check(abiVersion()==2 && availableSlots()==4,"ABI2 capacity");
        long[] reserved=new long[4];
        for(int i=0;i<4;i++)reserved[i]=reserve();
        check(availableSlots()==0,"reservations enforce hard limit");
        rejects(NativeLibretroBridge::reserve,"fifth reservation accepted");
        for(long token:reserved){rejects(()->metadata(token,new int[11],new double[3]),"unopened metadata");close(token);}
        long next=reserve();check(next!=reserved[0] && !reservationHeld(reserved[0]),"generation after reuse");
        rejects(()->close(reserved[0]),"stale close");check(reservationHeld(next),"stale close cannot release new session");close(next);

        CountDownLatch ready=new CountDownLatch(4),start=new CountDownLatch(1);
        AtomicReference<Throwable> error=new AtomicReference<>();List<Thread> threads=new ArrayList<>();
        for(int i=0;i<4;i++){
            final int port=i;
            Thread thread=new Thread(()->{
                try(var run=new Run(core,root,"concurrent-"+port,port<2?32+port:0)){
                    ready.countDown();if(!start.await(15,TimeUnit.SECONDS))throw new AssertionError("start timeout");
                    for(int frame=0;frame<300;frame++){
                        run.frame(1<<port);byte[] ram=memory(run.token,0);
                        for(int p=0;p<4;p++)check((ram[p*2]&255)==(1<<(port+p)),"independent core/input state");
                        byte[] state=serialize(run.token);run.frame(0);restore(run.token,state);
                        check(Arrays.equals(state,serialize(run.token)),"independent state restore");
                        check(run.meta[10]==(port<2?1:0),"software/hardware isolation");
                    }
                }catch(Throwable fail){error.compareAndSet(null,fail);ready.countDown();}
            },"test-JNI-owner-"+i);threads.add(thread);thread.start();
        }
        check(ready.await(20,TimeUnit.SECONDS),"concurrent open timeout");
        try { check(error.get()==null,"concurrent load: "+error.get());check(availableSlots()==0,"four running cores");
            rejects(NativeLibretroBridge::reserve,"overbooked active sessions");
        } finally { start.countDown(); }
        for(Thread thread:threads){thread.join(20000);check(!thread.isAlive(),"owner stopped");}
        if(error.get()!=null)throw new AssertionError("concurrent owner failed",error.get());
        check(availableSlots()==4,"all concurrent reservations released");

        CountDownLatch entered=new CountDownLatch(1),done=new CountDownLatch(1);
        Thread slow=new Thread(()->{
            try(var run=new Run(core,root,"slow-owner",12)){entered.countDown();run.frame(1);}
            catch(Throwable fail){error.compareAndSet(null,fail);entered.countDown();}
            finally{done.countDown();}
        });
        try(var fast=new Run(core,root,"fast-owner",0)){
            slow.start();check(entered.await(10,TimeUnit.SECONDS),"slow owner started");Thread.sleep(80);
            fast.frame(4);check(done.getCount()==1,"slow native call cannot block an unrelated core");
            check((memory(fast.token,0)[0]&255)==4,"fast core still usable");
        }
        slow.join(10000);check(!slow.isAlive()&&error.get()==null,"slow owner completes safely");
        try(var healthy=new Run(core,root,"healthy",0);var asynchronous=new Run(core,root,"asynchronous",13)){
            rejects(()->asynchronous.frame(1),"asynchronous core callback accepted");
            healthy.frame(8);check((memory(healthy.token,0)[0]&255)==8,"bad callback isolated to its own core");
        }
        check(availableSlots()==4,"final capacity");
        System.out.println("JNI_MULTI_OK checks="+checks.get()+" owners=4 frames=2400 softwareAndWgl=true");
    }
}
