// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.sfchome.client;

import cn.piq.fcarcade.netplay.NetplayLaunchSpec;
import cn.piq.fcarcade.netplay.NetplayVariant;
import cn.piq.sfchome.core.SfcNetplayProfile;
import cn.piq.sfchome.net.SfcHomeNetwork;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Executes production admission decisions without a Minecraft instance, runtime or native core. */
class SfcNetplayAdmissionTest {
    private static final UUID ID=new UUID(4,5);
    private static SfcHomeNetwork.Session session(int mode,String build,boolean host){
        return new SfcHomeNetwork.Session(7,2,ResourceLocation.parse("minecraft:overworld"),
                BlockPos.ZERO,ID,new BlockPos(2,0,0),ID,ID,"a".repeat(64),build,
                host?-1:1,ID,host,mode,ID,ID);
    }
    private static SfcHomeNetwork.NetplayStart grant(NetplayVariant variant,boolean host){
        return new SfcHomeNetwork.NetplayStart(session(3,SfcNetplayProfile.buildKey(variant),host),
                1L<<50,ID,variant.launch());
    }
    private static BooleanSupplier neverRead(){return ()->{throw new AssertionError("permission must not be read");};}

    @Test void bothRegisteredVariantsAdmitHostAndSecondPlayer(){
        for(var variant:new NetplayVariant[]{SfcNetplayProfile.current(),SfcNetplayProfile.legacy()})
            for(boolean host:new boolean[]{false,true}){
                var start=grant(variant,host);
                assertDoesNotThrow(()->SfcHomeClient.NetplayAdmission.requireGrant(start));
                assertDoesNotThrow(()->SfcHomeClient.NetplayAdmission.requirePrepared(start.session(),start,()->true));
            }
    }

    @Test void jniRequiresRegisteredBuildWhileOtherModesRetainHistoricalBuild(){
        for(var variant:new NetplayVariant[]{SfcNetplayProfile.current(),SfcNetplayProfile.legacy()}){
            String build=SfcNetplayProfile.buildKey(variant);
            assertTrue(SfcHomeClient.NetplayAdmission.buildSupported(3,build));
            for(int mode:new int[]{0,1,2})assertFalse(SfcHomeClient.NetplayAdmission.buildSupported(mode,build));
        }
        assertFalse(SfcHomeClient.NetplayAdmission.buildSupported(3,SfcHomeNetwork.CORE_BUILD));
        assertFalse(SfcHomeClient.NetplayAdmission.buildSupported(3,"sfc-jni-v1:"+"0".repeat(64)));
        assertFalse(SfcHomeClient.NetplayAdmission.buildSupported(3,null));
        for(int mode:new int[]{0,1,2}){
            assertTrue(SfcHomeClient.NetplayAdmission.buildSupported(mode,SfcHomeNetwork.CORE_BUILD));
            assertFalse(SfcHomeClient.NetplayAdmission.buildSupported(mode,null));
        }
    }

    @Test void launchAndSessionCannotSelectDifferentCores(){
        var current=grant(SfcNetplayProfile.current(),true);
        var legacy=grant(SfcNetplayProfile.legacy(),false);
        for(var mixed:new SfcHomeNetwork.NetplayStart[]{
                new SfcHomeNetwork.NetplayStart(current.session(),current.wire(),ID,legacy.launch()),
                new SfcHomeNetwork.NetplayStart(legacy.session(),legacy.wire(),ID,current.launch())}){
            assertThrows(IllegalArgumentException.class,()->SfcHomeClient.NetplayAdmission.requireGrant(mixed));
            assertThrows(IllegalArgumentException.class,()->SfcHomeClient.NetplayAdmission.requirePrepared(mixed.session(),mixed,neverRead()));
        }
    }

    @Test void unknownFingerprintVariantAndPersistenceAreRejectedWithoutFallback(){
        var current=grant(SfcNetplayProfile.current(),true);
        for(var bad:new NetplayLaunchSpec[]{NetplayLaunchSpec.UNSPECIFIED,
                new NetplayLaunchSpec("unregistered-core",current.launch().compatibilitySha(),true),
                new NetplayLaunchSpec(current.launch().variant(),"0".repeat(64),true),
                new NetplayLaunchSpec(current.launch().variant(),current.launch().compatibilitySha(),false)}){
            var invalid=new SfcHomeNetwork.NetplayStart(current.session(),current.wire(),ID,bad);
            assertThrows(IllegalArgumentException.class,()->SfcHomeClient.NetplayAdmission.requireGrant(invalid));
            assertThrows(IllegalArgumentException.class,()->SfcHomeClient.NetplayAdmission.requirePrepared(invalid.session(),invalid,neverRead()));
        }
    }

    @Test void legacyConstructorDoesNotSilentlyRepairAnUntrustedSessionBuild(){
        var invalid=new SfcHomeNetwork.NetplayStart(session(3,SfcHomeNetwork.CORE_BUILD,true),1L<<50,ID);
        assertEquals(SfcHomeNetwork.CORE_BUILD,invalid.session().coreBuild());
        assertThrows(IllegalArgumentException.class,()->SfcHomeClient.NetplayAdmission.requireGrant(invalid));
        var valid=new SfcHomeNetwork.NetplayStart(session(3,SfcNetplayProfile.buildKey(SfcNetplayProfile.legacy()),true),1L<<50,ID);
        assertDoesNotThrow(()->SfcHomeClient.NetplayAdmission.requireGrant(valid));
    }

    @Test void permissionRevokedDuringContentWorkRejectsBeforeNativeStart(){
        var start=grant(SfcNetplayProfile.current(),false);
        var allowed=new AtomicBoolean(true);var checks=new AtomicInteger();
        BooleanSupplier consent=()->{checks.incrementAndGet();return allowed.get();};
        SfcHomeClient.NetplayAdmission.requireGrant(start);
        SfcHomeClient.NetplayAdmission.requirePermission(consent);
        allowed.set(false);
        assertThrows(IllegalStateException.class,()->SfcHomeClient.NetplayAdmission.requirePrepared(start.session(),start,consent));
        assertEquals(2,checks.get());
    }

    @Test void disabledPlatformOrConsentRejectsBothEntryPoints(){
        var start=grant(SfcNetplayProfile.current(),true);
        assertThrows(IllegalStateException.class,()->SfcHomeClient.NetplayAdmission.requirePermission(()->false));
        assertThrows(IllegalStateException.class,()->SfcHomeClient.NetplayAdmission.requirePrepared(start.session(),start,()->false));
    }

    @Test void finalPermissionFailureIsNotSwallowedAndGrantMustOwnExactSession(){
        var start=grant(SfcNetplayProfile.current(),true);
        var equalSession=session(3,start.session().coreBuild(),true);
        assertEquals(start.session(),equalSession);assertNotSame(start.session(),equalSession);
        assertThrows(IllegalArgumentException.class,()->SfcHomeClient.NetplayAdmission.requirePrepared(equalSession,start,neverRead()));
        assertThrows(IllegalArgumentException.class,()->SfcHomeClient.NetplayAdmission.requirePrepared(start.session(),null,neverRead()));
        assertThrows(LinkageError.class,()->SfcHomeClient.NetplayAdmission.requirePrepared(start.session(),start,()->{throw new LinkageError("unavailable backend");}));
    }

    @Test void nonJniModesNeverAcquireConsentOrRequireNetplayGrant(){
        for(int mode:new int[]{0,1,2})for(boolean host:new boolean[]{false,true}){
            var session=session(mode,SfcHomeNetwork.CORE_BUILD,host);
            assertDoesNotThrow(()->SfcHomeClient.NetplayAdmission.requirePrepared(session,null,neverRead()));
        }
    }

    @Test void seenGrantFenceIsExactAndDoesNotConsumeNewAssignments(){
        var order=new SfcHomeClient.SessionOrder();Object first=new String("connection"),other=new String("connection");
        assertFalse(order.seen(first,7,2,ID));assertTrue(order.accept(first,7,2,ID));
        assertTrue(order.seen(first,7,2,ID));
        assertFalse(order.seen(other,7,2,ID));assertFalse(order.seen(null,7,2,ID));
        assertFalse(order.seen(first,8,2,ID));assertFalse(order.seen(first,7,3,ID));
        assertFalse(order.seen(first,7,2,new UUID(4,6)));
        assertTrue(order.accept(first,8,2,ID));assertTrue(order.seen(first,7,2,ID));
        assertTrue(order.accept(other,7,2,ID));assertFalse(order.seen(first,7,2,ID));
    }

    @Test void rejectedAssignmentCannotRestartWhenConsentLaterChanges(){
        var order=new SfcHomeClient.SessionOrder();Object connection=new Object();
        assertTrue(order.accept(connection,7,2,ID)); // rejection records the exact tuple, same as success
        assertTrue(order.seen(connection,7,2,ID));assertFalse(order.accept(connection,7,2,ID));
        assertTrue(order.accept(connection,7,2,new UUID(4,6)),"new controller grant may make a fresh attempt");
    }

    @Test void actualClientWiringChecksBeforeAdmissionAndAfterAsyncContent()throws Exception{
        String source=Files.readString(Path.of("src/main/java/cn/piq/sfchome/client/SfcHomeClient.java"));
        String netplay=source.substring(source.indexOf("@Override public void netplay("),source.indexOf("@Override public void netplayActivated("));
        assertTrue(netplay.indexOf("SESSION_ORDER.seen(")<netplay.indexOf("NetplayAdmission.requireGrant(value)"));
        assertTrue(netplay.indexOf("NetplayAdmission.requireGrant(value)")<netplay.indexOf("NetplayAdmission.requirePermission("));
        assertTrue(netplay.indexOf("NetplayAdmission.requirePermission(")<netplay.indexOf("session(incoming)"));
        assertTrue(netplay.contains("SfcHomeNetwork.leave(incoming.controllerLease()"));
        assertFalse(netplay.contains("closeLocal()"),"reject only the incoming grant, not an unrelated active run");
        String begin=source.substring(source.indexOf("private static void maybeBegin()"),source.indexOf("@Override public void romChunk("));
        assertTrue(begin.indexOf("NetplayAdmission.requirePrepared(")<begin.indexOf("new SfcPlayback("));
        assertTrue(begin.indexOf("NetplayAdmission.requirePrepared(")<begin.indexOf("SfcCoreLease.occupied()"));
        assertTrue(begin.contains("catch(RuntimeException|LinkageError failure){fail("));
        assertTrue(source.contains("NetplayAdmission.buildSupported(message.syncMode(),message.coreBuild())"));
    }
}
