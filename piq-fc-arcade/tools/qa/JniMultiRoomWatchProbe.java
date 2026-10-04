// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.fcarcade.netplay;

import cn.piq.retro.libretro.jni.NativeLibretroBridge;
import cn.piq.retro.storage.RuntimeWorkspace;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/** DEVELOPMENT probe: two real JNI rooms in one isolated JVM; no MC instance or third-party ROM. */
public final class JniMultiRoomWatchProbe {
    private static int checks;
    private static void check(boolean value,String label){if(!value)throw new AssertionError(label);checks++;System.out.println("CHECK "+label);}
    // Original ROM-free diagnostic from the 2026-09-29 JNI probe, embedded so this tool is source-only.
    private static byte[] rom(){
        byte[] r=new byte[16+16384+8192];r[0]='N';r[1]='E';r[2]='S';r[3]=26;r[4]=1;r[5]=1;r[6]=2;r[8]=1;
        int[] code={0x78,0xd8,0xa2,0xff,0x9a,0xa9,0,0x8d,0,0x20,0x8d,1,0x20,0x2c,2,0x20,0x10,0xfb,0xa9,1,0x8d,0x16,0x40,0xa9,0,0x8d,0x16,0x40,0xad,0x16,0x40,0x29,1,0x85,0,0xad,0x17,0x40,0x29,1,0x05,0,0xf0,4,0xa9,0x30,0xd0,2,0xa9,0x0f,0x85,1,0xad,2,0x20,0xa9,0x3f,0x8d,6,0x20,0xa9,0,0x8d,6,0x20,0xa5,1,0x8d,7,0x20,0xa9,0,0x8d,6,0x20,0x8d,6,0x20,0x4c,0x0d,0x80};
        for(int i=0;i<code.length;i++)r[16+i]=(byte)code[i];
        int[] boot={0xa9,0x5a,0x8d,0,0x60,0x4c,0,0x80};for(int i=0;i<boot.length;i++)r[16+0x100+i]=(byte)boot[i];
        for(int i=0x3ffa;i<0x4000;i+=2){r[16+i]=0;r[16+i+1]=(byte)0x80;}r[16+0x3ffd]=(byte)0x81;return r;
    }
    private static final Object VIEWER=new Object(); // The same observer connection may belong to both rooms.
    private static final class Room implements AutoCloseable {
        final long id;final Object owner=new Object();final Map<Object,NetplayProcess> peers=new ConcurrentHashMap<>();
        final ScheduledThreadPoolExecutor wire=new ScheduledThreadPoolExecutor(1);
        final NetplayRelay<Object> relay;final NetplayProcess host;NetplayProcess observer;
        String hostPicture,observerPicture;UUID observerTicket;boolean closed;
        Room(long id)throws Exception{
            this.id=id;
            relay=new NetplayRelay<>(id,owner,(target,message)->wire.execute(()->{
                var process=peers.get(target);if(process!=null)process.receive(message.chunk(),message.port());
            }));
            var ticket=relay.grant(owner,0);
            host=new NetplayProcess(new NetplayProcess.Grant(id,ticket.id(),true,true,0),JniMultiRoomWatchProbe::rom,
                    chunk->relay.receive(owner,chunk),true);
            peers.put(owner,host);
            wire.scheduleAtFixedRate(()->{synchronized(peers){relay.renew(Set.copyOf(peers.keySet()));}},0,50,TimeUnit.MILLISECONDS);
            host.start();
        }
        void observe(){
            synchronized(peers){
                var ticket=relay.grantObserver(VIEWER);check(ticket!=null&&!ticket.player(),"room "+id+" read-only observer grant");
                if(observerTicket!=null)check(!observerTicket.equals(ticket.id()),"room "+id+" reconnect uses new ticket");observerTicket=ticket.id();
                observer=new NetplayProcess(new NetplayProcess.Grant(id,ticket.id(),false,false,-1),JniMultiRoomWatchProbe::rom,
                        chunk->relay.receive(VIEWER,chunk),true);
                peers.put(VIEWER,observer);observerPicture=null;observer.start();
            }
        }
        void stopObserver()throws Exception{
            var previous=observer;peers.remove(VIEWER);relay.revoke(VIEWER,observerTicket);previous.close();
            previous.terminated().get(20,TimeUnit.SECONDS);check(!previous.nativeSlotHeld(),"room "+id+" stopped observer released exact reservation");
            observer=null;observerPicture=null;
        }
        void poll(){
            healthy(host);if(observer!=null)healthy(observer);
            NetplayProcess.Frame frame;while((frame=host.poll())!=null)if(frame.rgba().length>0)hostPicture=NetplaySaveState.hash(frame.rgba());
            if(observer!=null)while((frame=observer.poll())!=null)if(frame.rgba().length>0)observerPicture=NetplaySaveState.hash(frame.rgba());
        }
        void healthy(NetplayProcess p){if(p.error()!=null)throw new AssertionError("room "+id+": "+p.diagnostic());}
        boolean ready(){return host.ready()&&observer!=null&&observer.ready()&&host.framesReceived()>60&&observer.framesReceived()>60;}
        public void close()throws Exception{
            if(closed)return;closed=true;
            for(var p:List.copyOf(peers.values()))p.close();
            for(var p:List.copyOf(peers.values())){p.terminated().get(20,TimeUnit.SECONDS);check(!p.nativeSlotHeld(),"room "+id+" exact native owner released");}
            peers.clear();relay.close();wire.shutdownNow();check(wire.awaitTermination(3,TimeUnit.SECONDS),"room "+id+" wire stopped");
        }
    }
    private static void until(String label,BooleanSupplier condition,Room... rooms)throws Exception{
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
        while(System.nanoTime()<end){for(var room:rooms)room.poll();if(condition.getAsBoolean()){check(true,label);return;}Thread.sleep(10);}
        for(var room:rooms)System.err.println(room.host.diagnostic()+"\n"+(room.observer==null?"no observer":room.observer.diagnostic()));
        throw new AssertionError("Timeout: "+label);
    }
    private static void steady(Room a,Room b,int aInput,int bInput)throws Exception{
        a.host.inputRetroPad(aInput);b.host.inputRetroPad(bInput);
        // Observer input is intentionally attempted; the immutable grant must keep it neutral.
        a.observer.inputRetroPad(65535);b.observer.inputRetroPad(65535);
        long aFrame=a.host.framesReceived(),bFrame=b.host.framesReceived();
        until("independent hosts continue 40 frames",()->a.host.framesReceived()>aFrame+40&&b.host.framesReceived()>bFrame+40,a,b);
        until("each observer matches only its own host; rooms show different input",()->a.hostPicture!=null&&b.hostPicture!=null
                &&a.hostPicture.equals(a.observerPicture)&&b.hostPicture.equals(b.observerPicture)&&!a.hostPicture.equals(b.hostPicture),a,b);
        System.out.println("PICTURES "+aInput+"/"+bInput+" "+a.hostPicture+" "+b.hostPicture);
    }
    public static void main(String[] args)throws Exception{
        Path root=Path.of(args[0]).toAbsolutePath();Files.createDirectories(root.resolve("instance"));RuntimeWorkspace.configure(root.resolve("instance"));
        System.out.println((args.length>1&&args[1].equals("jar")?"PROVIDED JAR SNAPSHOT":"DEVELOPMENT CURRENT CLASSES; NOT A FROZEN JAR")+"; NOT MINECRAFT ACCEPTANCE");
        check(NativeLibretroBridge.freeSlotsIfLoaded()==4,"unloaded discovery reports four without loading");
        try(var a=new Room(91001);var b=new Room(91002)){
            a.observe();b.observe();until("two rooms, four real JNI instances ready",()->a.ready()&&b.ready(),a,b);
            check(a.host.nativeSlotHeld()&&a.observer.nativeSlotHeld()&&b.host.nativeSlotHeld()&&b.observer.nativeSlotHeld(),"all four exact reservations held");
            check(NativeLibretroBridge.freeSlotsIfLoaded()==0,"four real instances consume original native cap");
            try{long unexpected=NativeLibretroBridge.reserve();NativeLibretroBridge.close(unexpected);throw new AssertionError("fifth slot accepted");}
            catch(IOException expected){check(true,"fifth native reservation rejected");}
            steady(a,b,256,0);String aPressed=a.hostPicture,bReleased=b.hostPicture;
            steady(a,b,0,256);check(!aPressed.equals(a.hostPicture)&&!bReleased.equals(b.hostPicture),"both rooms respond independently to their own inputs");
            final long bBefore=b.host.framesReceived();a.stopObserver();check(NativeLibretroBridge.freeSlotsIfLoaded()==1,"only one stopped observer slot returned");
            until("other room runs while A observer is absent",()->b.host.framesReceived()>bBefore+40,b);
            a.observe();until("only A observer reconnects and catches up",()->a.observer.ready()&&a.observer.framesReceived()>60,a,b);
            check(NativeLibretroBridge.freeSlotsIfLoaded()==0,"reconnect reuses one available slot");steady(a,b,256,0);
            a.close();check(NativeLibretroBridge.freeSlotsIfLoaded()==2,"closing room A leaves exactly room B slots");
            final long threshold=b.host.framesReceived()+40;
            until("room B continues after room A closes",()->b.host.framesReceived()>threshold,b);
            check(b.host.nativeSlotHeld()&&b.observer.nativeSlotHeld(),"room B reservations remain intact");
        }
        check(NativeLibretroBridge.freeSlotsIfLoaded()==4,"all real reservations returned only after termination");
        System.out.println("PASS "+checks+" checks");
    }
}
