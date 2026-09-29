package cn.piq.fcarcade.server.hosted;

import cn.piq.fcarcade.cabinet.CabinetFrame;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.io.IOException;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class ServerCoreRegistryTest {
    @TempDir Path root;
    static class Handle implements ServerCoreHandle {
        volatile boolean closing,terminated;public boolean isReady(){return !closing;}public boolean isTerminated(){return terminated;}public String error(){return null;}public int maxPlayers(){return 1;}
        public void offerInput(int a,int b){}public void clearInput(){}public CabinetFrame pollFrame(){return null;}public void close(){closing=true;}
    }
    static class Factory implements ServerCoreFactory {
        final Handle handle;Factory(Handle h){handle=h;}public int maxPlayers(){return 1;}public int maxConcurrentSessions(){return 1;}
        public String unavailableReason(ServerCoreContext c){return null;}public ServerCoreHandle open(ServerCoreContext c,Path p){return handle;}
    }
    ServerCoreContext context(){return new ServerCoreContext(root,root.resolve("saves"),UUID.randomUUID(),UUID.randomUUID());}
    ResourceLocation id(){return ResourceLocation.fromNamespaceAndPath("hosted_test","fixture_"+UUID.randomUUID());}
    @Test void closingRetainsCapacityUntilExactInstanceTerminates()throws Exception{
        var id=id();var handle=new Handle();ServerCoreRegistry.register(id,new Factory(handle));
        var opened=ServerCoreRegistry.open(id,context(),root.resolve("fixture.rom"));assertSame(handle,opened);assertEquals(1,ServerCoreRegistry.activeCount(id));
        opened.close();assertEquals(1,ServerCoreRegistry.activeCount(id));assertThrows(IOException.class,()->ServerCoreRegistry.open(id,context(),root.resolve("fixture.rom")));
        handle.terminated=true;assertEquals(0,ServerCoreRegistry.activeCount(id));
    }
    @Test void failedOpeningReturnsItsReservation()throws Exception{
        var id=id();ServerCoreRegistry.register(id,new Factory(new Handle()){@Override public String unavailableReason(ServerCoreContext c){return "missing fixture runtime";}});
        assertThrows(IOException.class,()->ServerCoreRegistry.open(id,context(),root));assertEquals(0,ServerCoreRegistry.activeCount(id));
    }
    @Test void registrationDoesNotOpenACoreOrGrantHostingPermission(){
        var id=id();var handle=new Handle();ServerCoreRegistry.register(id,new Factory(handle));assertNotNull(ServerCoreRegistry.find(id));assertEquals(0,ServerCoreRegistry.activeCount(id));assertFalse(handle.closing);
        assertNotNull(ServerCoreRegistry.find(cn.piq.fcarcade.cabinet.CabinetBackends.NES));
    }
    @Test void closeAllCancelsAnOpeningWithoutPrematureCapacityRelease()throws Exception{
        var id=id();var handle=new Handle();var entered=new java.util.concurrent.CountDownLatch(1);var go=new java.util.concurrent.CountDownLatch(1);
        ServerCoreRegistry.register(id,new Factory(handle){@Override public ServerCoreHandle open(ServerCoreContext c,Path p){entered.countDown();try{if(!go.await(4,java.util.concurrent.TimeUnit.SECONDS))throw new AssertionError("Fixture timeout");}catch(InterruptedException e){throw new AssertionError(e);}return handle;}});
        try(var executor=java.util.concurrent.Executors.newSingleThreadExecutor()){
            var future=executor.submit(()->ServerCoreRegistry.open(id,context(),root));
            try{assertTrue(entered.await(2,java.util.concurrent.TimeUnit.SECONDS));assertEquals(1,ServerCoreRegistry.activeCount(id));ServerCoreRegistry.closeAll();go.countDown();
                ServerCoreWorkerTest.await(()->handle.closing);
                assertThrows(java.util.concurrent.TimeoutException.class,()->future.get(100,java.util.concurrent.TimeUnit.MILLISECONDS));
                assertTrue(handle.closing);assertEquals(1,ServerCoreRegistry.activeCount(id));
            }finally{go.countDown();handle.terminated=true;}
            var failure=assertThrows(java.util.concurrent.ExecutionException.class,()->future.get(3,java.util.concurrent.TimeUnit.SECONDS));assertInstanceOf(IOException.class,failure.getCause());
            assertTrue(failure.getCause().getMessage().contains("cancelled by server stop"));
            assertEquals(0,ServerCoreRegistry.activeCount(id));
        }
    }
    @Test void capabilityFailureKeepsCallerAdmissionUntilReturnedHandleTerminatesEvenIfCloseThrows()throws Exception{
        var id=id();var released=new java.util.concurrent.atomic.AtomicBoolean();
        var handle=new Handle(){@Override public int maxPlayers(){return 2;}@Override public void close(){closing=true;throw new IllegalStateException("fixture close failed");}};
        ServerCoreRegistry.register(id,new Factory(handle));
        try(var executor=java.util.concurrent.Executors.newSingleThreadExecutor()){
            var future=executor.submit(()->{try{return ServerCoreRegistry.open(id,context(),root);}finally{released.set(true);}});
            try{
                ServerCoreWorkerTest.await(()->handle.closing);assertEquals(1,ServerCoreRegistry.activeCount(id));
                assertThrows(java.util.concurrent.TimeoutException.class,()->future.get(100,java.util.concurrent.TimeUnit.MILLISECONDS));assertFalse(released.get());
                assertThrows(IOException.class,()->ServerCoreRegistry.open(id,context(),root));
            }finally{handle.terminated=true;}
            var failure=assertThrows(java.util.concurrent.ExecutionException.class,()->future.get(3,java.util.concurrent.TimeUnit.SECONDS));
            assertInstanceOf(IOException.class,failure.getCause());assertEquals("Hosted input capacity mismatch",failure.getCause().getMessage());
            assertTrue(released.get());assertEquals(0,ServerCoreRegistry.activeCount(id));
        }
    }
    @Test void interruptCannotReleaseFailedOpeningBeforeCoreTerminationAndIsRestored()throws Exception{
        var id=id();var opener=new java.util.concurrent.atomic.AtomicReference<Thread>();var interrupted=new java.util.concurrent.atomic.AtomicBoolean();
        var handle=new Handle(){@Override public int maxPlayers(){return 2;}};ServerCoreRegistry.register(id,new Factory(handle));
        try(var executor=java.util.concurrent.Executors.newSingleThreadExecutor()){
            var future=executor.submit(()->{opener.set(Thread.currentThread());try{return ServerCoreRegistry.open(id,context(),root);}finally{interrupted.set(Thread.currentThread().isInterrupted());}});
            try{
                ServerCoreWorkerTest.await(()->handle.closing);opener.get().interrupt();
                assertThrows(java.util.concurrent.TimeoutException.class,()->future.get(100,java.util.concurrent.TimeUnit.MILLISECONDS));assertEquals(1,ServerCoreRegistry.activeCount(id));
            }finally{handle.terminated=true;}
            var failure=assertThrows(java.util.concurrent.ExecutionException.class,()->future.get(3,java.util.concurrent.TimeUnit.SECONDS));
            assertInstanceOf(IOException.class,failure.getCause());assertTrue(interrupted.get());assertEquals(0,ServerCoreRegistry.activeCount(id));
        }
    }
}
