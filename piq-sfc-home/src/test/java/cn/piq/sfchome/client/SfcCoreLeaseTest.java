package cn.piq.sfchome.client;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class SfcCoreLeaseTest {
    @Test void refusesParallelHomeOrCabinetWorker(){try(var lease=SfcCoreLease.acquire()){assertTrue(SfcCoreLease.occupied());assertThrows(IllegalStateException.class,SfcCoreLease::acquire);}assertFalse(SfcCoreLease.occupied());}
    @Test void staleOwnerCannotReleaseNewWorker(){var old=SfcCoreLease.acquire();old.close();try(var current=SfcCoreLease.acquire()){old.close();assertTrue(SfcCoreLease.occupied());assertThrows(IllegalStateException.class,SfcCoreLease::acquire);}assertFalse(SfcCoreLease.occupied());}
    @Test void cancelledStartNeverStealsReadOnlyCore(){try(var old=SfcCoreLease.acquireObserver()){assertTrue(SfcCoreLease.observing());assertThrows(IllegalStateException.class,()->SfcCoreLease.acquireAfterObserver(()->true));assertTrue(SfcCoreLease.observing());}assertFalse(SfcCoreLease.occupied());}
    @Test void activeWorkerWaitsForReadOnlyTeardown()throws Exception{
        var old=SfcCoreLease.acquireObserver();var future=new java.util.concurrent.CompletableFuture<SfcCoreLease>();
        var worker=Thread.ofPlatform().daemon(true).start(()->{try{future.complete(SfcCoreLease.acquireAfterObserver(()->false));}catch(Throwable bad){future.completeExceptionally(bad);}});
        try{Thread.sleep(20);assertFalse(future.isDone());assertTrue(SfcCoreLease.observing());old.close();try(var active=future.get(2,java.util.concurrent.TimeUnit.SECONDS)){assertTrue(SfcCoreLease.occupied());assertFalse(SfcCoreLease.observing());old.close();assertTrue(SfcCoreLease.occupied());}}finally{old.close();worker.join(2500);}
    }
}
