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
    static final class Runtime implements SfcPlayback.Netplay {
        volatile boolean started,active,closed;
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
        public String error(){return null;}public String diagnostic(){return "fixture";}
        public void close(){closed=true;active=false;}
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
