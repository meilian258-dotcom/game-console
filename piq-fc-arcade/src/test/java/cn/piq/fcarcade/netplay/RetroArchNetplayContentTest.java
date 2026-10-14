// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.fcarcade.netplay;

import cn.piq.retro.libretro.LibretroContentFiles;
import java.io.IOException;
import java.io.*;
import java.net.ServerSocket;
import cn.piq.fcarcade.session.ZapperInput;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class RetroArchNetplayContentTest {
    @TempDir Path root;
    private NetplayProfile profile(String name,int limit) {
        return new NetplayProfile(getClass(),"/test/core.dll","a".repeat(64),name,Map.of(),1,48000,limit,4);
    }
    private LibretroContentFiles files(String name,int size)throws IOException {
        Path source=root.resolve(name);Files.write(source,new byte[size]);
        return LibretroContentFiles.inspect(name,Map.of(name,source),1024,1024,()->{});
    }
    private NetplayProcess process(boolean host,AtomicInteger reads) {
        return new NetplayProcess(new NetplayProcess.Grant(1,UUID.randomUUID(),host,true),
                ()->{reads.incrementAndGet();throw new AssertionError("Cancelled content supplier called");},
                chunk->{},profile("game.zip",1024),Map::of);
    }
    @Test void fileBindingForHostAndParticipantDoesNotReadAgainOrLaunchOnCancel()throws Exception {
        var bundle=files("game.zip",32);var reads=new AtomicInteger();
        for(boolean host:new boolean[]{true,false}) {
            var process=process(host,reads);
            process.fileContent(bundle);process.close();process.start();
            process.terminated().get(2,TimeUnit.SECONDS);
            assertEquals(0,reads.get());assertFalse(process.ready());assertFalse(process.forcedTermination());
            assertThrows(IllegalStateException.class,()->process.fileContent(bundle));
        }
    }
    @Test void fileContractRejectsDifferentContentNameOversizeTinyAndReservedNames()throws Exception {
        var bundle=files("game.zip",32);
        assertDoesNotThrow(()->NetplayProcess.validateFileContent(profile("game.zip",1024),bundle));
        assertThrows(IOException.class,()->NetplayProcess.validateFileContent(profile("other.zip",1024),bundle));
        assertThrows(IOException.class,()->NetplayProcess.validateFileContent(profile("game.zip",16),bundle));
        var tiny=files("tiny.zip",15);
        assertThrows(IOException.class,()->NetplayProcess.validateFileContent(profile("tiny.zip",1024),tiny));
        Path main=root.resolve("main.zip"),reserved=root.resolve("options.cfg");
        Files.write(main,new byte[32]);Files.write(reserved,new byte[32]);
        var collision=LibretroContentFiles.inspect("main.zip",Map.of("main.zip",main,"options.cfg",reserved),1024,1024,()->{});
        assertThrows(IOException.class,()->NetplayProcess.validateFileContent(profile("main.zip",1024),collision));
    }
    @Test void filesChangedAfterBindingAreRejectedDuringStageAndOriginalIsNeverOverwritten()throws Exception {
        var bundle=files("game.zip",32);Files.write(root.resolve("game.zip"),new byte[]{1,2});
        Path stage=Files.createDirectory(root.resolve("stage"));
        assertThrows(IOException.class,()->bundle.stage(stage,()->{}));
        assertArrayEquals(new byte[]{1,2},Files.readAllBytes(root.resolve("game.zip")));
    }
    @Test void netplayCloseDoesNotBlockCallerOnPersistenceAbortOrTransportAndIsIdempotent()throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var reads=new AtomicInteger();
        var aborts=new AtomicInteger();var process=process(true,reads);
        process.persistence(new NetplayProcess.Persistence(){
            public byte[] load(NetplaySaveState.Identity identity){throw new AssertionError("Unexpected load");}
            public CompletableFuture<Void> save(byte[] bytes){throw new AssertionError("Unexpected save");}
            public void abort(){
                aborts.incrementAndGet();entered.countDown();
                try{assertTrue(release.await(5,TimeUnit.SECONDS));}
                catch(InterruptedException failure){Thread.currentThread().interrupt();throw new AssertionError(failure);}
            }
        });
        try(var caller=Executors.newSingleThreadExecutor()) {
            try {
                caller.submit(process::close).get(1,TimeUnit.SECONDS);
                assertTrue(entered.await(1,TimeUnit.SECONDS));assertFalse(process.terminated().isDone());
                process.close();process.start();assertEquals(0,reads.get());
            } finally {release.countDown();}
            process.terminated().get(2,TimeUnit.SECONDS);
            assertEquals(1,aborts.get());assertNull(process.error());
        }
    }
    @Test void ownerCleanupCannotConfirmCloseWhileAnotherThreadStillRunsAbort()throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        var process=process(true,new AtomicInteger());
        var socketsField=NetplayProcess.class.getDeclaredField("sockets");socketsField.setAccessible(true);
        var sockets=(NetplaySockets)socketsField.get(process);
        var listener=sockets.own(new ServerSocket(0,1,java.net.InetAddress.getLoopbackAddress()));
        process.persistence(new NetplayProcess.Persistence(){
            public byte[] load(NetplaySaveState.Identity identity){return null;}
            public CompletableFuture<Void> save(byte[] bytes){return CompletableFuture.completedFuture(null);}
            public void abort(){entered.countDown();try{assertTrue(release.await(4,TimeUnit.SECONDS));}catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}}
        });
        var cleanup=NetplayProcess.class.getDeclaredMethod("cleanup");cleanup.setAccessible(true);
        try(var owner=Executors.newSingleThreadExecutor()) {
            try {
                process.close();assertTrue(entered.await(1,TimeUnit.SECONDS));
                assertTrue(listener.isClosed(),"Native IO must close before caller abort");
                var done=owner.submit(()->{try{cleanup.invoke(process);}catch(Exception e){throw new RuntimeException(e);}});
                assertThrows(TimeoutException.class,()->done.get(100,TimeUnit.MILLISECONDS));
                assertFalse(process.terminated().isDone(),"Owner must not publish false successful cleanup");
                release.countDown();done.get(2,TimeUnit.SECONDS);
                process.terminated().get(2,TimeUnit.SECONDS);assertNull(process.error());
            } finally {release.countDown();}
        }
    }
    @Test void gunTriggerIsNeutralImmediatelyAfterCloseEvenBeforeCloseWorkerRuns()throws Exception {
        var process=new NetplayProcess(new NetplayProcess.Grant(1,UUID.randomUUID(),true,true),
                ()->{throw new AssertionError("Do not start core");},chunk->{},false,true);
        process.authoritativeGun(1,1,255,ZapperInput.pack(128,120,false,true));
        process.close();
        process.authoritativeGun(1,2,255,ZapperInput.pack(128,120,false,true));
        var bytes=new ByteArrayOutputStream();process.writeInputs(new DataOutputStream(bytes));
        try(var in=new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()))){
            assertEquals(0,in.readInt());assertEquals(NetplayGunMailbox.NEUTRAL.coordinates(),in.readInt());
            assertEquals(NetplayGunMailbox.NEUTRAL.flags(),in.readInt());
            for(int p=0;p<4;p++)assertEquals(0,in.readInt());
        }
        process.terminated().get(2,TimeUnit.SECONDS);
    }
}
