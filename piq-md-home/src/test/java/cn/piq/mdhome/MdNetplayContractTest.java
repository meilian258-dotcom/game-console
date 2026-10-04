// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.mdhome;

import cn.piq.fcarcade.cabinet.*;
import cn.piq.fcarcade.netplay.*;
import cn.piq.mdhome.client.MdProfile;
import cn.piq.mdhome.save.MdSaveCatalog;
import io.netty.buffer.Unpooled;
import java.nio.file.*;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class MdNetplayContractTest {
    private static final String ROM="a".repeat(64);
    @TempDir Path directory;
    private static RegistryFriendlyByteBuf buffer(){return new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);}
    private static WatchNetwork.Start display(){return new WatchNetwork.Start(1,UUID.randomUUID(),new WatchDescriptor(ResourceLocation.parse("piq_md_home:md"),UUID.randomUUID(),UUID.randomUUID(),ResourceLocation.parse("minecraft:overworld"),new WatchAnchor(BlockPos.ZERO,UUID.randomUUID()),UUID.randomUUID(),List.of(new WatchAnchor(new BlockPos(2,0,0),UUID.randomUUID()))));}
    @Test void nativeContractHasTwoSixButtonPortsAndCorrectPublicAudioRate(){
        var p=MdNetplayProfile.profile();assertNotNull(p.jni());assertEquals(2,p.ports());assertEquals(List.of(513,513),p.jni().devices());assertEquals(48000,p.sampleRate());assertTrue(p.contentName().endsWith(".md"));assertEquals(8*1024*1024,p.maxRomBytes());
    }
    @Test void netplayArtifactIsIndependentlyPinnedAndLeavesMediaPrivateCoreUnchanged(){
        var p=MdNetplayProfile.profile();var legacy=MdProfile.profile();
        assertEquals("Genesis Plus GX PIQ Netplay",p.jni().name());assertEquals("v1.7.4c2838c7-piqnp1",MdNetplayProfile.VERSION);
        assertEquals("/core/windows-x64/genesis_plus_gx_piq_netplay_libretro.dll",p.resource());
        assertEquals("8200a6d5e7c39f60afefd8e87e369d253f737de6a73bf8d920d2ab81888a5fb5",p.sha());
        assertEquals("/core/windows-x64/genesis_plus_gx_libretro.dll",legacy.cores().get("windows-x64").resource());
        assertEquals("9ffa10a115b20e1b49e9caf0b53f287c640ed4e5bb93f7ed9a23b416a4ccfdf7",legacy.cores().get("windows-x64").sha256());
        assertNotEquals(legacy.cores().get("windows-x64"),p.jni().cores().get("windows-x64"));
    }
    @Test void netplayProfileCannotInheritUnverifiedMediaCoreOrOptionsChanges()throws Exception {
        String source=Files.readString(Path.of("src/main/java/cn/piq/mdhome/MdNetplayProfile.java"));
        assertFalse(source.contains("MdProfile.profile()"));assertFalse(source.contains("MdProfile.SHA"));
        assertEquals(Map.of("genesis_plus_gx_system_hw","mega drive / genesis","genesis_plus_gx_region_detect","auto","genesis_plus_gx_bios","disabled","genesis_plus_gx_lock_on","disabled","genesis_plus_gx_frameskip","disabled","genesis_plus_gx_overscan","disabled","genesis_plus_gx_render","single field"),MdNetplayProfile.profile().options());
    }
    @Test void commonIdentityIsNotRawRomOrLegacyMediaIdentity(){
        var n=MdNetplayProfile.identity(ROM);assertEquals(NetplaySaveState.identity(MdNetplayProfile.profile(),ROM,Map.of()),n);
        assertNotEquals(ROM,n.content());assertNotEquals(MdSaveCatalog.identity(ROM),n);assertNotEquals(MdNetplayProfile.identity("b".repeat(64)),n);
    }
    @Test void independentStorePreservesMediaAndDoesNotMigrateOnFreshNetplay()throws Exception {
        var media=new MdSaveCatalog(directory.resolve("md-public-saves/v1"));var netplay=new MdSaveCatalog(directory.resolve("md-netplay-saves/v1"));
        String owner=MdSaveCatalog.personal(UUID.randomUUID(),1);var m=MdSaveCatalog.identity(ROM);var n=MdNetplayProfile.identity(ROM);
        byte[] old=NetplaySaveState.encode(new NetplaySaveState.Parts(m,8,new byte[]{1},new byte[]{2},new byte[0]));
        try(var lease=media.lease(owner,m,"","旧串流",1,false)){lease.write(old);}var before=media.read(owner);
        assertNull(netplay.read(owner));assertNotEquals(media.lockKey(owner),netplay.lockKey(owner));
        try(var lease=netplay.lease(owner,n,"","新联机",2,false)){assertNull(lease.read());assertThrows(java.io.IOException.class,()->lease.write(old));}
        assertNull(netplay.read(owner));assertEquals(before.version(),media.read(owner).version());
        byte[] current=NetplaySaveState.encode(new NetplaySaveState.Parts(n,20,new byte[]{3},new byte[]{4},new byte[0]));
        try(var lease=netplay.lease(owner,n,"","新联机",2,false)){lease.write(current);}
        assertEquals(before.version(),media.read(owner).version());assertEquals(n,netplay.read(owner).identity());
    }
    @Test void hostGrantSeparatesRawDownloadHashFromCompoundSaveContentAndMode(){
        var identity=MdNetplayProfile.identity(ROM);var grant=new MdPublicNetwork.Start(9,UUID.randomUUID(),UUID.randomUUID(),display(),true,false,identity.profile(),ROM,identity.content(),true);
        var b=buffer();try{MdPublicNetwork.Start.CODEC.encode(b,grant);assertEquals(grant,MdPublicNetwork.Start.CODEC.decode(b));assertEquals(0,b.readableBytes());}finally{b.release();}
    }
    @Test void remoteSeatRetainsPhysicalPortButHasSeparateReadOnlyNativeTicket(){
        for(int port=0;port<2;port++){var grant=new MdPublicNetwork.Seat(10,display(),port,UUID.randomUUID(),UUID.randomUUID(),ROM,1024,true);var b=buffer();try{MdPublicNetwork.Seat.CODEC.encode(b,grant);assertEquals(grant,MdPublicNetwork.Seat.CODEC.decode(b));assertEquals(0,b.readableBytes());}finally{b.release();}
            var nativeGrant=new NetplayProcess.Grant(grant.wire(),grant.ticket(),false,false,-1);assertFalse(nativeGrant.player());assertEquals(-1,nativeGrant.port());assertNotEquals(grant.loan(),grant.ticket());}
        assertThrows(IllegalArgumentException.class,()->new MdPublicNetwork.Seat(10,display(),1,UUID.randomUUID(),UUID.randomUUID(),ROM,9*1024*1024,true));
    }
    @Test void staleMediaStartEncodingIsRejectedWithoutNetplayFields(){
        var b=buffer();try{b.writeVarLong(7);b.writeUUID(UUID.randomUUID());b.writeUUID(UUID.randomUUID());WatchNetwork.Start.CODEC.encode(b,display());b.writeBoolean(true);b.writeBoolean(false);b.writeUtf(ROM,64);b.writeUtf(ROM,64);assertThrows(RuntimeException.class,()->MdPublicNetwork.Start.CODEC.decode(b));}finally{b.release();}
    }
    @Test void sharedDownloadRequestHasNoClientChosenPathOrCoreAndRoundTrips(){
        var request=new MdPublicNetwork.Download(8,UUID.randomUUID(),ROM);var b=buffer();try{MdPublicNetwork.Download.CODEC.encode(b,request);assertEquals(request,MdPublicNetwork.Download.CODEC.decode(b));var denied=new MdPublicNetwork.DownloadDenied(request.request());MdPublicNetwork.DownloadDenied.CODEC.encode(b,denied);assertEquals(denied,MdPublicNetwork.DownloadDenied.CODEC.decode(b));}finally{b.release();}
        assertThrows(IllegalArgumentException.class,()->new MdPublicNetwork.Download(8,UUID.randomUUID(),"../rom.md"));
    }
    @Test void remoteReadyBindsWirePhysicalPortAndLoanAndCannotForgeThirdPort(){
        var ready=new MdPublicNetwork.SeatReady(19,1,UUID.randomUUID());var b=buffer();try{MdPublicNetwork.SeatReady.CODEC.encode(b,ready);assertEquals(ready,MdPublicNetwork.SeatReady.CODEC.decode(b));assertEquals(0,b.readableBytes());}finally{b.release();}
        assertThrows(IllegalArgumentException.class,()->new MdPublicNetwork.SeatReady(0,1,ready.loan()));assertThrows(IllegalArgumentException.class,()->new MdPublicNetwork.SeatReady(19,2,ready.loan()));
    }
    @Test void controllerRemapKeepsEveryPhysicalBitDistinctOnBothNativePorts(){
        var values=new HashSet<Integer>();for(int bit=0;bit<12;bit++){int converted=MdProfile.input(MdProfile.Core.GENESIS_PLUS_GX,1<<bit);assertEquals(1,Integer.bitCount(converted));assertTrue(values.add(converted));}assertEquals(4095,MdProfile.input(MdProfile.Core.GENESIS_PLUS_GX,4095));
    }
    @Test void modeAdapterUsesPreparedActivationSharedTransportAndCancellation()throws Exception {
        var root=Path.of("src/main/java/cn/piq/mdhome");String server=Files.readString(root.resolve("MdPublicServer.java")),client=Files.readString(root.resolve("client/MdPublicClient.java")),content=Files.readString(root.resolve("client/MdNetplayContent.java"));
        assertTrue(server.contains("NetplayNetwork.room(wire,connection,2)"));assertTrue(server.contains("s.room.grantObserver(seat.connection)"));assertTrue(server.contains("NetplaySaveServer.activate"));assertTrue(server.contains("NetplaySaveServer.abort"));assertTrue(server.contains("NetplaySaveServer.awaitFinish"));
        assertTrue(client.contains("Map::of,true,false,true"));assertTrue(client.contains("netplay.activate()"));assertTrue(client.contains("stoppingNetplay.terminated()"));assertTrue(client.contains("netplay.cabinetInput(p,MdProfile.input"));
        assertTrue(content.contains("ContentCardClient.expectDownload"));assertTrue(content.contains("ContentCardClient.cancelDownload"));assertFalse(content.contains("registerRuntime"));
        assertTrue(server.contains("seat.ready=!s.netplay||p==s.host"));assertTrue(server.contains("int mask=seat.ready&&s.console.authorized"));assertTrue(server.contains("seat.loan.equals(reply.loan())"));
        assertTrue(client.contains("hostActivated:!seat.netplay()||netplay!=null&&netplay.ready()&&seatReadySent"));assertTrue(client.contains("!forceZero&&inputReady()&&held()"));
        assertTrue(content.contains("JniNetplayConsent.allowed()"));assertTrue(content.contains("current.getConnection()!=connection"));
    }
}
