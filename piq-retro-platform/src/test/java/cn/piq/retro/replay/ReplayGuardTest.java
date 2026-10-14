package cn.piq.retro.replay;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ReplayGuardTest {
    private static final AtomicReference<Throwable> FAILURE=new AtomicReference<>();
    private static final BooleanSupplier SERVICE=()->{
        Throwable error=FAILURE.get();
        if(error instanceof Error e)throw e;
        if(error instanceof RuntimeException e)throw e;
        return false;
    };
    static { ReplayGuard.install(SERVICE); }
    @AfterEach void restore(){FAILURE.set(null);ReplayGuard.exitReplay();ReplayGuard.playing();}

    @Test void explicitWholeIntervalInvalidatesWithoutPolling(){
        long before=ReplayGuard.epoch();
        ReplayGuard.enterReplay();ReplayGuard.enterReplay();
        long active=ReplayGuard.epoch();assertEquals(before+1,active);
        ReplayGuard.exitReplay();ReplayGuard.exitReplay();
        assertFalse(ReplayGuard.playing());assertFalse(ReplayGuard.valid(before));
        assertTrue(ReplayGuard.valid(ReplayGuard.epoch()));
    }
    @Test void serviceFailureFailsClosedAndReplacementIsRejected(){
        ReplayGuard.install(SERVICE);
        assertThrows(IllegalStateException.class,()->ReplayGuard.install(()->false));
        FAILURE.set(new IllegalStateException("broken"));assertTrue(ReplayGuard.playing());
        FAILURE.set(new NoClassDefFoundError("missing"));assertTrue(ReplayGuard.playing());
        FAILURE.set(new AssertionError("broken"));assertTrue(ReplayGuard.playing());
    }
    @Test void cleanupOncePerEpochAndNeverRestartOnNormalReturn(){
        var retirement=new ReplayGuard.Retirement();var count=new AtomicInteger();
        assertFalse(retirement.block(count::incrementAndGet));
        ReplayGuard.enterReplay();assertTrue(retirement.block(count::incrementAndGet));
        assertTrue(retirement.block(count::incrementAndGet));assertEquals(1,count.get());
        ReplayGuard.exitReplay();assertFalse(retirement.block(count::incrementAndGet));assertEquals(1,count.get());
        ReplayGuard.enterReplay();assertTrue(retirement.block(count::incrementAndGet));assertEquals(2,count.get());
    }
    @Test void failedCleanupRemainsRetryable(){
        var retirement=new ReplayGuard.Retirement();var count=new AtomicInteger();ReplayGuard.enterReplay();
        assertThrows(IllegalStateException.class,()->retirement.block(()->{throw new IllegalStateException("failed release");}));
        assertTrue(retirement.block(count::incrementAndGet));assertEquals(1,count.get());
    }
    @Test void rejectedEntryDoesNotInvalidateCurrentOwner(){
        var refusal=new AtomicReference<RuntimeException>();
        ReplayGuard.registerEntryCheck("test-replay-owner",()->{if(refusal.get()!=null)throw refusal.get();});
        long before=ReplayGuard.epoch();refusal.set(new IllegalStateException("unsupported live owner"));
        try{assertThrows(IllegalStateException.class,ReplayGuard::checkEntry);assertTrue(ReplayGuard.valid(before));}
        finally{refusal.set(null);}
        assertDoesNotThrow(ReplayGuard::checkEntry);
        assertThrows(IllegalArgumentException.class,()->ReplayGuard.registerEntryCheck("test-replay-owner",()->{}));
    }
}
