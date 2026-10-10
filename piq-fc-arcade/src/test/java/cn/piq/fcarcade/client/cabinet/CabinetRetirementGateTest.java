package cn.piq.fcarcade.client.cabinet;

import cn.piq.fcarcade.netplay.NetplayProcess;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CabinetRetirementGateTest {
    @Test void pendingRetirementBlocksUntilSuccessfulCompletionWithoutWaiting(){
        var gate=new CabinetRetirementGate();var done=new CompletableFuture<Void>();assertNull(gate.reason());
        gate.retain(done);gate.retain(done);assertTrue(gate.reason().contains("正在关闭"));assertFalse(done.isDone());
        done.complete(null);assertNull(gate.reason());
        var next=new CompletableFuture<Void>();gate.retain(next);assertNotNull(gate.reason());
        done.complete(null);assertNotNull(gate.reason());next.complete(null);assertNull(gate.reason());
    }
    @Test void failedCancelledOrUnknownRetirementNeverBecomesFreeViaAnotherSuccess(){
        for(int mode=0;mode<3;mode++){
            var gate=new CabinetRetirementGate();var failed=new CompletableFuture<Void>();var later=new CompletableFuture<Void>();
            if(mode==0){failed.completeExceptionally(new IllegalStateException("child exit unknown"));gate.retain(failed);}
            else if(mode==1){failed.cancel(false);gate.retain(failed);}else gate.retain(null);
            gate.retain(later);later.complete(null);
            for(int repeat=0;repeat<5;repeat++)assertTrue(gate.reason().contains("关闭未确认"));
        }
    }
    @Test void allCapturedInstancesMustFinishAndOutOfOrderSuccessCannotDropOtherClaim(){
        var gate=new CabinetRetirementGate();var first=new CompletableFuture<Void>();var second=new CompletableFuture<Void>();
        gate.retain(first);gate.retain(second);second.complete(null);assertNotNull(gate.reason());
        first.complete(null);assertNull(gate.reason());
    }
    @Test void actualUnstartedRetroArchCloseReturnsWhileGateRetainsTransportCleanup()throws Exception{
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var gate=new CabinetRetirementGate();
        var process=new NetplayProcess(new NetplayProcess.Grant(1,UUID.randomUUID(),true,true),
                ()->{throw new AssertionError("Native content must not be opened");},chunk->{
                    entered.countDown();try{if(!release.await(5,TimeUnit.SECONDS))throw new IllegalStateException("test cleanup timed out");}
                    catch(InterruptedException failure){Thread.currentThread().interrupt();throw new IllegalStateException(failure);}
                });
        gate.retain(process.terminated());
        try(var caller=Executors.newSingleThreadExecutor()){
            try{caller.submit(process::close).get(1,TimeUnit.SECONDS);assertTrue(entered.await(1,TimeUnit.SECONDS));
                assertFalse(process.terminated().isDone());assertTrue(gate.reason().contains("正在关闭"));
                process.start();assertFalse(process.ready());assertNotNull(gate.reason());
            }finally{release.countDown();}
            process.terminated().get(2,TimeUnit.SECONDS);assertNull(gate.reason());assertFalse(process.forcedTermination());
        }
    }
}
