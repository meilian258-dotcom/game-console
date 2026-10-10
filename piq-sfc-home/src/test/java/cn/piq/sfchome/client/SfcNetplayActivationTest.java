package cn.piq.sfchome.client;

import cn.piq.fcarcade.netplay.NetplayProcess;
import cn.piq.sfcarcade.core.SfcCore;
import cn.piq.sfchome.net.SfcHomeNetwork;
import cn.piq.sfchome.net.SfcJoinNetwork;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Runs the production SFC worker and lifecycle with an injected shared-process port. */
class SfcNetplayActivationTest {
    static SfcHomeNetwork.NetplayStart grant(boolean host){
        var session=new SfcHomeNetwork.Session(31,2,ResourceLocation.parse("minecraft:overworld"),BlockPos.ZERO,UUID.randomUUID(),
                new BlockPos(1,0,0),UUID.randomUUID(),UUID.randomUUID(),"a".repeat(64),SfcHomeNetwork.CORE_BUILD,
                host?-1:1,UUID.randomUUID(),host,3,UUID.randomUUID(),UUID.randomUUID());
        return new SfcHomeNetwork.NetplayStart(session,(1L<<50)+32,UUID.randomUUID());
    }
    static SfcHomeNetwork.NetplayActivated activation(SfcHomeNetwork.NetplayStart grant){
        return new SfcHomeNetwork.NetplayActivated(grant.session().sessionId(),grant.session().epoch(),grant.wire(),grant.ticket());
    }
    static void await(BooleanSupplier condition)throws Exception{
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
        while(!condition.getAsBoolean()&&System.nanoTime()<end)Thread.sleep(2);
        assertTrue(condition.getAsBoolean(),"Worker did not reach expected state");
    }
    @Test void hostAnnouncesPreparationWithoutFirstFrameAndPresentsOnlyAfterExactActivation()throws Exception{
        var grant=grant(true);var host=new Host();var progress=new SfcStartupProgress();
        try(var playback=new SfcPlayback(grant.session(),new byte[32768],progress,host,grant)){
            await(()->host.ready.get()==1);assertTrue(host.waitForActivation);
            assertEquals(SfcStartupProgress.Stage.READY,progress.stage());assertFalse(playback.started());
            playback.netplayInput(123);Thread.sleep(40);
            assertEquals(0,host.runtime.polls.get());assertEquals(0,host.opens.get());assertEquals(0,host.frames.get());
            assertEquals(0,host.runtime.nonzeroInputs.get());
            var exact=activation(grant);
            assertFalse(playback.activateNetplay(new SfcHomeNetwork.NetplayActivated(32,exact.epoch(),exact.wire(),exact.ticket())));
            assertFalse(playback.activateNetplay(new SfcHomeNetwork.NetplayActivated(exact.sessionId(),3,exact.wire(),exact.ticket())));
            assertFalse(playback.activateNetplay(new SfcHomeNetwork.NetplayActivated(exact.sessionId(),exact.epoch(),exact.wire()+1,exact.ticket())));
            assertFalse(playback.activateNetplay(new SfcHomeNetwork.NetplayActivated(exact.sessionId(),exact.epoch(),exact.wire(),UUID.randomUUID())));
            assertEquals(0,host.runtime.activations.get());
            assertTrue(playback.activateNetplay(exact));assertTrue(playback.activateNetplay(exact));
            playback.netplayInput(123);await(()->host.frames.get()>0&&host.audio.get()>0);
            assertEquals(1,host.runtime.activations.get());assertEquals(1,host.ready.get());
            assertTrue(playback.started());assertEquals(SfcStartupProgress.Stage.RUNNING,progress.stage());
            assertTrue(host.runtime.nonzeroInputs.get()>0);
        }
        await(()->host.runtime.closed&&!SfcCoreLease.occupied());assertEquals(1,host.closes.get());
    }
    @Test void cancelledPreparedWorkerIgnoresQueuedReadyAndLateActivationWithoutAudio()throws Exception{
        var grant=grant(true);var host=new Host();host.deferActions=true;
        var playback=new SfcPlayback(grant.session(),new byte[32768],new SfcStartupProgress(),host,grant);
        try{
            await(()->!host.actions.isEmpty());playback.close();await(()->host.runtime.closed&&!SfcCoreLease.occupied());
            for(Runnable action;(action=host.actions.poll())!=null;)action.run();
            assertFalse(playback.activateNetplay(activation(grant)));
            assertEquals(0,host.ready.get());assertEquals(0,host.runtime.activations.get());
            assertEquals(0,host.opens.get());assertEquals(0,host.frames.get());assertEquals(0,host.audio.get());
        }finally{playback.close();await(()->!SfcCoreLease.occupied());}
    }
    @Test void peerStillJoinsAnAlreadyRunningRoomWithoutHostActivation()throws Exception{
        var grant=grant(false);var host=new Host();
        try(var playback=new SfcPlayback(grant.session(),new byte[32768],new SfcStartupProgress(),host,grant)){
            await(()->host.ready.get()==1&&host.frames.get()>0);assertFalse(host.waitForActivation);
            assertFalse(playback.activateNetplay(activation(grant)));assertEquals(0,host.runtime.activations.get());
        }
        await(()->host.runtime.closed&&!SfcCoreLease.occupied());
    }
    @Test void gateRejectsEarlyAndClosedApprovalAndDoesNotCommitFailedActivation(){
        var grant=grant(true);var gate=new SfcNetplayStartGate(grant);var calls=new AtomicInteger();
        assertFalse(gate.activate(activation(grant),calls::incrementAndGet));assertFalse(gate.active());
        assertTrue(gate.prepared());assertFalse(gate.prepared());
        assertThrows(IllegalStateException.class,()->gate.activate(activation(grant),()->{throw new IllegalStateException("owner closed");}));
        assertFalse(gate.active());gate.close();
        assertFalse(gate.activate(activation(grant),calls::incrementAndGet));assertEquals(0,calls.get());
    }
    @Test void peerFailureAfterVideoKeepsOneClassifiedNoticeAndClosesAllResources()throws Exception{
        for(String reason:new String[]{"JNI Netplay 确认状态 CRC 不一致","Netplay 连接已结束，请重新加入"}){
            cn.piq.fcarcade.client.ui.DeviceNotices.clear();
            var grant=grant(false);var host=new Host();
            try(var playback=new SfcPlayback(grant.session(),new byte[32768],new SfcStartupProgress(),host,grant)){
                await(()->host.frames.get()>0&&host.audio.get()>0);assertTrue(playback.started());
                host.runtime.failure=reason;
                await(()->playback.error()!=null&&host.runtime.closed&&!SfcCoreLease.occupied());
                var entries=cn.piq.fcarcade.client.ui.DeviceNotices.snapshot();assertEquals(1,entries.size());
                var entry=entries.getFirst();
                assertEquals(cn.piq.fcarcade.client.ui.NetplayFailureCode.classifyReason(reason).summary(),entry.summary());
                assertFalse(entry.summary().contains("本机依赖"));assertTrue(entry.detail().contains(reason));assertTrue(entry.detail().contains("core.dll"));
                assertNull(playback.errorForLeaveNotice(),"Client cleanup must not publish a second generic failure");
                for(int i=0;i<3;i++){
                    String notice=playback.errorForLeaveNotice();if(notice!=null)cn.piq.fcarcade.client.ui.DeviceNotices.record("SFC",notice);
                }
                assertSame(entry,cn.piq.fcarcade.client.ui.DeviceNotices.last());assertEquals(1,cn.piq.fcarcade.client.ui.DeviceNotices.snapshot().size());
                assertEquals(1,host.closes.get());assertFalse(playback.activateNetplay(activation(grant)));
                int frames=host.frames.get(),audio=host.audio.get();Thread.sleep(30);
                assertEquals(frames,host.frames.get());assertEquals(audio,host.audio.get());
            }finally{cn.piq.fcarcade.client.ui.DeviceNotices.clear();}
        }
    }
    @Test void normalCloseDoesNotReportTheTransportErrorProducedByCancellation()throws Exception{
        cn.piq.fcarcade.client.ui.DeviceNotices.clear();var grant=grant(false);var host=new Host();
        try(var playback=new SfcPlayback(grant.session(),new byte[32768],new SfcStartupProgress(),host,grant)){
            await(()->host.frames.get()>0);playback.close();
            await(()->host.runtime.closed&&!SfcCoreLease.occupied());
            assertNull(playback.error());assertTrue(cn.piq.fcarcade.client.ui.DeviceNotices.snapshot().isEmpty());
        }
    }
    static final class Runtime implements SfcPlayback.Netplay {
        volatile boolean started,active,closed;
        volatile String failure;
        final AtomicInteger activations=new AtomicInteger(),polls=new AtomicInteger(),nonzeroInputs=new AtomicInteger();
        public void start(){started=true;}public boolean ready(){return started&&!closed;}
        public void activate(){if(!ready())throw new IllegalStateException("not prepared");active=true;activations.incrementAndGet();}
        public boolean nativeSlotHeld(){return started&&!closed;}
        public void input(int mask){if(mask!=0)nonzeroInputs.incrementAndGet();}
        public NetplayProcess.Frame poll(){
            polls.incrementAndGet();if(!active)return null;
            // Deliberately make a frame available immediately; the SFC worker must enforce authority.
            if(polls.get()>1)return null;
            return new NetplayProcess.Frame(1,new byte[]{0,0,0,(byte)255},new float[0],1,1,1,new short[]{1,1},48000);
        }
        public String error(){return failure;}public String diagnostic(){return "fixture: loaded core.dll and rom.zip successfully; 运行环境已就绪";}
        public void close(){failure="Netplay 连接已结束";closed=true;active=false;}
    }
    static final class Host implements SfcPlayback.Host {
        final Runtime runtime=new Runtime();final ConcurrentLinkedQueue<Runnable> actions=new ConcurrentLinkedQueue<>();
        final AtomicInteger ready=new AtomicInteger(),opens=new AtomicInteger(),audio=new AtomicInteger(),frames=new AtomicInteger(),closes=new AtomicInteger();
        volatile boolean deferActions,waitForActivation;
        public void execute(Runnable action){if(deferActions)actions.add(action);else action.run();}
        public boolean isCurrent(SfcPlayback playback){return true;}
        public void ready(SfcPlayback playback,SfcHomeNetwork.Ready value){ready.incrementAndGet();}
        public SfcPlayback.Netplay openNetplay(SfcHomeNetwork.NetplayStart grant,byte[] rom,boolean waitForActivation){
            this.waitForActivation=waitForActivation;runtime.active=!waitForActivation;return runtime;
        }
        public SfcPlayback.Audio openAudio(){opens.incrementAndGet();return new SfcPlayback.Audio(){
            public void submit(short[] pcm,int length,float gain){audio.incrementAndGet();}public void close(){closes.incrementAndGet();}
        };}
        public void mediaFrame(SfcPlayback playback,int width,int height,int stride,float aspect,byte[] rgba,short[] pcm,int length){frames.incrementAndGet();}
        public void captured(SfcPlayback playback,SfcJoinNetwork.Capture request,byte[] bytes,String sha){}
        public void applied(SfcPlayback playback,SfcJoinNetwork.Capture request,String sha,boolean success){}
        public void backup(SfcPlayback playback,SfcCore core,int frame){fail("Netplay must use its shared persistence, not legacy backup");}
    }
}
