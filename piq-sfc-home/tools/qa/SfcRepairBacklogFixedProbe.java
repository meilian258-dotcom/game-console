package cn.piq.sfchome.client;

import cn.piq.sfcarcade.core.SfcTwoPortInputProbe;
import cn.piq.sfcarcade.core.wasm.WasmSfcCore;
import cn.piq.sfchome.net.*;
import cn.piq.sfchome.server.SfcRepairLedger;
import com.google.gson.Gson;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/** Actual WASM Playback regression with controlled delayed delivery; supports source and final JAR origins. */
public final class SfcRepairBacklogFixedProbe implements SfcPlayback.Host {
    private final Queue<Runnable> callbacks=new ConcurrentLinkedQueue<>();
    private final CountDownLatch atGoal=new CountDownLatch(1), releaseGoal=new CountDownLatch(1), atBacklog=new CountDownLatch(1), releaseBacklog=new CountDownLatch(1);
    private volatile SfcPlayback playback;
    private volatile int observed, doneFrame=-1, queuedAtDone=-1;
    private boolean ready, restored;
    private int assertions;
    private void check(boolean value,String message){assertions++;if(!value)throw new AssertionError(message);}
    private void pump(){for(Runnable r;(r=callbacks.poll())!=null;)r.run();}
    private void waitFor(BooleanSupplier condition)throws Exception{long end=System.nanoTime()+20_000_000_000L;while(!condition.getAsBoolean()&&System.nanoTime()<end){pump();if(playback.error()!=null)throw new AssertionError(playback.error());Thread.sleep(2);}pump();check(condition.getAsBoolean(),"diagnostic deadline");}
    private void frames(int first,int count){for(int i=0;i<count;i+=4)check(playback.offer(new SfcHomeNetwork.Frames(991,1,first+i,new int[Math.min(4,count-i)],new int[Math.min(4,count-i)])),"normal batch accepted");}
    private static Path origin(Class<?> type)throws Exception{return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();}
    public static void main(String[] args)throws Exception{
        var p=new SfcRepairBacklogFixedProbe();Path jar=Path.of(args[0]).toRealPath();
        for(Class<?> c:List.of(SfcPlayback.class,SfcRepairLedger.class))p.check(origin(c).equals(jar),"tested production origin "+c.getName());
        for(Class<?> c:List.of(SfcExecutionCore.class,WasmSfcCore.class))p.check(origin(c).equals(Path.of(args[1]).toRealPath()),"unchanged core origin "+c.getName());
        byte[] rom=SfcTwoPortInputProbe.rom();UUID lease=UUID.randomUUID(), token=UUID.randomUUID();
        var session=new SfcHomeNetwork.Session(991,1,ResourceLocation.fromNamespaceAndPath("minecraft","overworld"),new BlockPos(0,64,0),new UUID(1,1),new BlockPos(2,64,0),new UUID(2,2),new UUID(3,3),SfcExecutionCore.digest(rom),SfcHomeNetwork.CORE_BUILD,0,lease,false);
        try{
            p.playback=new SfcPlayback(session,rom,new SfcStartupProgress(),p);p.waitFor(()->p.ready);
            for(int first=0;first<600;first+=120){p.frames(first,120);int target=first+120;p.waitFor(()->p.observed==target);}
            var snapshot=p.playback.checkpoint(600);p.check(snapshot!=null,"real frame-600 checkpoint exists");
            var key=new SfcRepairNetwork.Key(991,1,lease,token,600);p.playback.beginRepair(key);
            p.playback.restoreRepair(key,snapshot.bytes(),snapshot.sha());p.waitFor(()->p.restored);
            p.check(p.playback.replayRepair(new SfcRepairNetwork.Replay(key,new int[120],new int[120])),"real replay accepted");
            p.waitFor(()->p.atGoal.getCount()==0);
            // The server sent Resume at head 720. While the guest is delayed, normal authoritative
            // frames 720..919 arrive, exactly as phase DONE permits in SfcHomeServer.repairTick.
            p.playback.resumeRepair(new SfcRepairNetwork.Key(991,1,lease,token,720));
            p.frames(720,200);p.releaseGoal.countDown();p.waitFor(()->p.atBacklog.getCount()==0);p.check(p.doneFrame<0,"no ACK while 160 authoritative frames remain");p.releaseBacklog.countDown();p.waitFor(()->p.doneFrame>=0);
            int atAck=p.observed;long start=System.nanoTime();Thread.sleep(500);p.pump();int after=p.observed;
            // Exercise the final-JAR server ledger acceptance independently, not a fake server.
            var ledger=new SfcRepairLedger();for(int i=0;i<920;i+=4)ledger.append(i,new int[4],new int[4]);
            UUID host=UUID.randomUUID(),guest=UUID.randomUUID();ledger.report(host,true,600,snapshot.sha());
            var repair=ledger.start(new SfcRepairLedger.Mismatch(guest,600,snapshot.sha()),lease,0);
            p.check(repair!=null,"actual ledger begins repair");
            int offset=0;while(offset<snapshot.bytes().length){int n=Math.min(SfcRepairLedger.CHUNK,snapshot.bytes().length-offset);p.check(repair.append(repair.token,600,snapshot.bytes().length,offset,snapshot.sha(),Arrays.copyOfRange(snapshot.bytes(),offset,offset+n),offset/SfcRepairLedger.CHUNK/8),"actual ledger uploads checkpoint");offset+=n;}
            while(repair.peekPart()!=null)repair.part();p.check(repair.restored(repair.token,600,snapshot.sha(),true),"actual ledger restore hash check");
            repair.resume=720;repair.phase=SfcRepairLedger.Phase.DONE;
            boolean accepted=repair.done(repair.token,p.doneFrame,true);
            var out=new LinkedHashMap<String,Object>();out.put("diagnostic_completed",true);out.put("fixed_backlog_gate",p.doneFrame>=914&&p.doneFrame<=920&&p.queuedAtDone<=1&&accepted);
            out.put("actual_worker",SfcPlayback.class.getName());out.put("actual_wasm",true);out.put("minecraft_started",false);out.put("original_diagnostic_rom_only",true);
            out.put("checkpoint_frame",600);out.put("resume_goal",720);out.put("server_head",920);out.put("ack_frame",p.doneFrame);out.put("queued_four_frame_batches_at_ack",p.queuedAtDone);out.put("observed_at_ack",atAck);out.put("observed_after_500ms",after);out.put("elapsed_ms_after_ack",(System.nanoTime()-start)/1_000_000);
            out.put("server_ledger_accepts_caught_up_ack",accepted);out.put("server_ledger_rejects_200_frame_lag",!repair.done(repair.token,720,true));p.check(!repair.done(repair.token,720,true),"server rejects old goal lagging 200 frames");p.check(!repair.done(repair.token,921,true),"future ACK rejected");out.put("assertions",p.assertions);
            p.check(Boolean.TRUE.equals(out.get("fixed_backlog_gate")),"full catch-up gate fixed");
            System.out.println("PIQ_AUDIT "+new Gson().toJson(out));
        }finally{p.releaseGoal.countDown();p.releaseBacklog.countDown();if(p.playback!=null)p.playback.close();long end=System.nanoTime()+5_000_000_000L;while(SfcCoreLease.occupied()&&System.nanoTime()<end)Thread.sleep(5);p.check(!SfcCoreLease.occupied(),"real core lease released");}
    }
    public void execute(Runnable r){callbacks.add(r);}public boolean isCurrent(SfcPlayback p){return p==playback;}
    public void ready(SfcPlayback p,SfcHomeNetwork.Ready r){ready=true;}
    public void captured(SfcPlayback p,SfcJoinNetwork.Capture c,byte[] bytes,String sha){}
    public void applied(SfcPlayback p,SfcJoinNetwork.Capture c,String sha,boolean success){}
    public SfcPlayback.Audio openAudio(){return new SfcPlayback.Audio(){public void submit(short[] pcm,int length,float gain){}public void close(){}};}
    public void backup(SfcPlayback p,WasmSfcCore core,int frame){}
    public boolean consistencyChecks(){return true;}
    public void repairedState(SfcPlayback p,SfcRepairNetwork.Key key,String sha,boolean success){check(success,"actual restore succeeds");restored=true;}
    public void repairedFrames(SfcPlayback p,SfcRepairNetwork.Key key,boolean success){check(success,"worker emits completion success");doneFrame=key.frame();try{var field=SfcPlayback.class.getDeclaredField("input");field.setAccessible(true);queuedAtDone=((BlockingQueue<?>)field.get(p)).size();}catch(Exception e){throw new AssertionError(e);}}
    public void observedFrame(int frame,int width,int height,byte[] rgba,short[] pcm,int length){observed=frame;if(frame==720){atGoal.countDown();try{if(!releaseGoal.await(10,TimeUnit.SECONDS))throw new AssertionError("goal release deadline");}catch(InterruptedException e){Thread.currentThread().interrupt();}}if(frame==760){atBacklog.countDown();try{if(!releaseBacklog.await(10,TimeUnit.SECONDS))throw new AssertionError("backlog release deadline");}catch(InterruptedException e){Thread.currentThread().interrupt();}}}
}

