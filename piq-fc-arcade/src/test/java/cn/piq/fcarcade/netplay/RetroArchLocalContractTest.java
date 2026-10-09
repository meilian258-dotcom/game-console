// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.fcarcade.netplay;

import cn.piq.fcarcade.retroarch.RetroArchSession;
import cn.piq.retro.libretro.LibretroProfile;
import java.io.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Behavioral protocol tests. These never start a native process or pretend to be core tests. */
class RetroArchLocalContractTest {
    private static final String ROM="a".repeat(64), RUNNER="b".repeat(64);
    private static NetplayProfile profile(int ports) {
        var fc=NetplayProfile.fc();
        return new NetplayProfile(fc.owner(),fc.resource(),fc.sha(),fc.contentName(),fc.options(),
                fc.device(),fc.sampleRate(),fc.maxRomBytes(),ports);
    }
    private static NetplayProcess local(int ports) {
        return NetplayProcess.local(RetroArchSession.Config.of(profile(ports)),()->new byte[16],Map::of);
    }
    private static int[] words(NetplayProcess process)throws IOException {
        var bytes=new ByteArrayOutputStream();process.writeInputs(new DataOutputStream(bytes));
        assertEquals(32,bytes.size(),"PNPI remains eight big-endian words");
        var buffer=ByteBuffer.wrap(bytes.toByteArray());int[] result=new int[8];
        for(int i=0;i<result.length;i++)result[i]=buffer.getInt();return result;
    }
    private static RetroArchSession.Dependency dependency(String name,String hash) {
        return new RetroArchSession.Dependency(RetroArchLocalContractTest.class,
                new LibretroProfile.Artifact("/core/"+name,hash,1024),name,512);
    }
    private static int[] packet(int... pads) {
        int[] expected = new int[8];
        // The unused legacy aim lane uses its off-screen sentinel, not coordinates (0, 0).
        expected[1] = NetplayGunMailbox.NEUTRAL.coordinates();
        expected[2] = NetplayGunMailbox.NEUTRAL.flags();
        System.arraycopy(pads, 0, expected, 3, pads.length);
        return expected;
    }

    @Test void localLaunchHasNoNetplayRolePortNicknameOrCommandServer() {
        try(var process=local(4)) {
            assertEquals(NetplayProcess.Role.LOCAL,process.role());
            assertThrows(IllegalStateException.class,process::grant);
            var command=process.command("runner.exe");
            for(String argument:command)for(String forbidden:List.of("--host","--connect","--port","--nick","--check-frames"))
                assertFalse(argument.startsWith(forbidden),argument);
            assertEquals("./content.nes",command.getLast(),"Full-path cores must receive a nonempty dirname");
            assertEquals(List.of("1:1","2:1","3:1","4:1"),command.stream().filter(s->s.matches("[1-4]:1")).toList());
            String config=NetplayProcess.localConfiguration();
            assertFalse(config.contains("netplay_"));
            assertTrue(config.contains("network_cmd_enable = \"false\""));
            assertTrue(config.contains("stdin_cmd_enable = \"false\""));
        }
    }
    @Test void localDoesNotAcceptRemoteAuthorityOrReserveJniSlots()throws Exception {
        try(var process=local(2)) {
            for(int kind:List.of(NetplayChunk.OPEN,NetplayChunk.ACK,NetplayChunk.CLOSE))
                process.receive(new NetplayChunk(1,UUID.randomUUID(),kind,0,new byte[0]),1);
            assertFalse(process.nativeSlotHeld());assertFalse(process.ready());assertNull(process.error());
            assertArrayEquals(packet(),words(process));
            assertThrows(IllegalStateException.class,()->process.cabinetCoin(0,1));
            assertThrows(IllegalStateException.class,()->process.authoritativeGun(1,1,1,1));
        }
    }
    @Test void localFourPortProviderPreservesSelectAndNeverWritesLegacyInputWord()throws Exception {
        try(var process=local(4)) {
            int[] input={4,256,2048,4095};process.localInputProvider(()->input);
            assertArrayEquals(packet(4,256,2048,4095),words(process));
            input[1]=0;
            assertArrayEquals(packet(4,0,2048,4095),words(process));
            process.close();
            assertArrayEquals(packet(),words(process));
        }
    }
    @Test void localMailboxReleaseAffectsOnlyRequestedPort()throws Exception {
        try(var process=local(4)) {
            for(int port=0;port<4;port++)process.localInput(port,16<<port);
            assertArrayEquals(packet(16,32,64,128),words(process));
            process.localRelease(1);
            assertArrayEquals(packet(16,0,64,128),words(process));
            process.close();process.localInput(0,4095);
            assertArrayEquals(packet(),words(process));
        }
    }
    @Test void invalidProviderFailsBeforeWritingAnyPartialPacket() {
        for(int[] bad:List.of(new int[]{1},new int[]{0,0,0},new int[]{-1,0},new int[]{4096,0})) {
            try(var process=local(2)) {
                process.localInputProvider(()->bad);var bytes=new ByteArrayOutputStream();
                assertThrows(IOException.class,()->process.writeInputs(new DataOutputStream(bytes)));
                assertEquals(0,bytes.size());
            }
        }
    }
    @Test void directInputRejectsUnsupportedPortsAndHighButtonBits() {
        try(var process=local(2)) {
            for(int port:List.of(-1,2,4)) {
                assertThrows(IllegalArgumentException.class,()->process.localInput(port,0));
                assertThrows(IllegalArgumentException.class,()->process.localRelease(port));
            }
            for(int mask:List.of(-1,4096,65535))assertThrows(IllegalArgumentException.class,()->process.localInput(0,mask));
        }
    }
    @Test void cancelBeforeStartDoesNotReadFilesCallProviderOrLaunchCore()throws Exception {
        var reads=new AtomicInteger();var inputs=new AtomicInteger();
        var session=new RetroArchSession(RetroArchSession.Config.of(profile(2)),()->{reads.incrementAndGet();return null;});
        session.inputProvider(()->{inputs.incrementAndGet();return new int[2];});
        session.close();session.close();session.start();session.terminated().get(1,TimeUnit.SECONDS);
        assertEquals(0,reads.get());assertEquals(0,inputs.get());assertFalse(session.ready());
        assertFalse(session.forcedTermination());assertNull(session.error());
        assertThrows(IllegalStateException.class,()->session.inputProvider(()->new int[2]));
    }
    @Test void slowAbortNeverBlocksCloseOrAllowsCancelledSessionToStart()throws Exception {
        var abortEntered=new CountDownLatch(1);var releaseAbort=new CountDownLatch(1);
        var reads=new AtomicInteger();var aborts=new AtomicInteger();
        var session=new RetroArchSession(RetroArchSession.Config.of(profile(2)),()->{reads.incrementAndGet();return null;});
        session.persistence(new NetplayProcess.Persistence(){
            public byte[] load(NetplaySaveState.Identity identity){throw new AssertionError("Cancelled session loaded a save");}
            public CompletableFuture<Void> save(byte[] bytes){throw new AssertionError("Cancelled session saved");}
            public void abort(){
                aborts.incrementAndGet();abortEntered.countDown();
                try{if(!releaseAbort.await(5,TimeUnit.SECONDS))throw new AssertionError("Abort test release timed out");}
                catch(InterruptedException failure){Thread.currentThread().interrupt();throw new AssertionError(failure);}
            }
        });
        try(var caller=Executors.newSingleThreadExecutor()){
            try{
                caller.submit(session::close).get(1,TimeUnit.SECONDS);
                assertTrue(abortEntered.await(1,TimeUnit.SECONDS));
                assertFalse(session.terminated().isDone(),"Unfinished abort must not report cleanup success");
                session.start();session.close();
                assertEquals(0,reads.get());
                assertThrows(IllegalStateException.class,()->session.inputProvider(()->new int[2]));
            }finally{releaseAbort.countDown();session.close();}
            session.terminated().get(1,TimeUnit.SECONDS);
            assertEquals(1,aborts.get());assertEquals(0,reads.get());
        }
    }
    @Test void localCannotSilentlySelectJniOrTheUnadaptedLightGun() {
        var p=profile(2);
        var jni=new LibretroProfile("same-core","nes",false,List.of(1,1),false,p.options(),
                Map.of("windows-x64",new LibretroProfile.Artifact(p.resource(),p.sha())));
        assertThrows(IllegalArgumentException.class,()->RetroArchSession.Config.of(p.withJni(jni)));
        assertThrows(IllegalArgumentException.class,()->RetroArchSession.Config.of(NetplayProfile.fcZapper()));
    }
    @Test void fixedCoreAndDependencyDeclarationsRejectMismatchAndEscapingNames() {
        var p=profile(2);
        assertThrows(IllegalArgumentException.class,()->new RetroArchSession.Config(p,
                new LibretroProfile.Artifact(p.resource(),ROM),List.of()));
        assertThrows(IllegalArgumentException.class,()->new RetroArchSession.Config(p,
                new LibretroProfile.Artifact("/another.dll",p.sha()),List.of()));
        for(String name:List.of("../libc++.dll","core.dll","retroarch.exe","LIBC++.DLL","custom.dll"))
            assertThrows(IllegalArgumentException.class,()->dependency(name,ROM));
        var dep=dependency("libc++.dll",ROM);
        assertThrows(IllegalArgumentException.class,()->new RetroArchSession.Config(p,
                new LibretroProfile.Artifact(p.resource(),p.sha()),List.of(dep,dep)));
        assertThrows(IllegalArgumentException.class,()->new RetroArchSession.Dependency(getClass(),dep.artifact(),dep.fileName(),0));
        assertThrows(IllegalArgumentException.class,()->new RetroArchSession.Dependency(getClass(),dep.artifact(),dep.fileName(),1025));
    }
    @Test void localSaveDomainIncludesRunnerPortsOptionsAndFixedDependencies() {
        var cfg=RetroArchSession.Config.of(profile(2));
        var local=NetplaySaveState.localIdentity(cfg,ROM,Map.of(),RUNNER);
        var old=NetplaySaveState.identity(cfg.profile(),ROM,Map.of());
        assertNotEquals(old.profile(),local.profile());assertEquals(old.content(),local.content());
        assertNotEquals(local,NetplaySaveState.localIdentity(cfg,ROM,Map.of(),"c".repeat(64)));
        assertNotEquals(local,NetplaySaveState.localIdentity(RetroArchSession.Config.of(profile(4)),ROM,Map.of(),RUNNER));
        var deps=List.of(dependency("libc++.dll","c".repeat(64)),dependency("libunwind.dll","d".repeat(64)));
        var withDeps=new RetroArchSession.Config(cfg.profile(),cfg.core(),deps);
        var depIdentity=NetplaySaveState.localIdentity(withDeps,ROM,Map.of(),RUNNER);
        assertNotEquals(local,depIdentity);
        assertEquals(depIdentity,NetplaySaveState.localIdentity(new RetroArchSession.Config(cfg.profile(),cfg.core(),deps.reversed()),ROM,Map.of(),RUNNER));
        var p=cfg.profile();var changed=new NetplayProfile(p.owner(),p.resource(),p.sha(),p.contentName(),Map.of("mesen_region","PAL"),1,44100,p.maxRomBytes());
        assertNotEquals(local,NetplaySaveState.localIdentity(RetroArchSession.Config.of(changed),ROM,Map.of(),RUNNER));
    }
    @Test void localCheckpointsRejectLegacyAndChangedContentWithoutMutatingOriginal() {
        var cfg=RetroArchSession.Config.of(profile(2));var identity=NetplaySaveState.localIdentity(cfg,ROM,Map.of(),RUNNER);
        byte[] bytes=NetplaySaveState.encode(new NetplaySaveState.Parts(identity,8,new byte[]{1,2},new byte[]{3},new byte[0]));
        byte[] unchanged=bytes.clone();
        assertThrows(IllegalArgumentException.class,()->NetplaySaveState.decode(bytes,NetplaySaveState.identity(cfg.profile(),ROM,Map.of())));
        assertThrows(IllegalArgumentException.class,()->NetplaySaveState.decode(bytes,NetplaySaveState.localIdentity(cfg,"c".repeat(64),Map.of(),RUNNER)));
        assertThrows(IllegalArgumentException.class,()->NetplaySaveState.decode(bytes,NetplaySaveState.localIdentity(cfg,ROM,Map.of("bios.bin","c".repeat(64)),RUNNER)));
        assertArrayEquals(unchanged,bytes);assertEquals(8,NetplaySaveState.decode(bytes,identity).frame());
    }
    @Test void localContentNamesKeepFirmwareButRejectPathAndDeviceAliases() {
        for(String name:List.of("bios.bin","neogeo.zip","game.gba","firmware.rom"))assertTrue(NetplaySaveState.safeLocalName(name));
        for(String name:List.of("../bios.bin","a/bios.bin","C:\\bios.bin","CON.bin","NUL.zip","LPT1.bin","a..bin",".bin"))
            assertFalse(NetplaySaveState.safeLocalName(name),name);
    }
}
