package cn.piq.fcarcade.access;

import cn.piq.fcarcade.home.*;
import cn.piq.fcarcade.cabinet.*;
import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Wire and source contracts supplement (not replace) live dedicated-server authorization tests. */
class PlayerContentResourcePermissionsTest {
    private String source(String path)throws Exception{return Files.readString(Path.of("src/main/java/cn/piq/fcarcade/"+path+".java"));}
    @Test void independentUseAndUploadBitsNeverCreateAdministrativeOrUnlistedAccess(){
        UUID listed=UUID.randomUUID(),other=UUID.randomUUID();
        for(int flags=0;flags<16;flags++){
            var policy=new PlayerContentPolicy(false,(flags&1)!=0,(flags&2)!=0,Set.of(listed),(flags&4)!=0,(flags&8)!=0);
            int caps=policy.capabilities(listed,false);assertEquals(0,policy.capabilities(other,false));assertFalse(PlayerContentPolicy.has(caps,PlayerContentPolicy.ADMIN));
            assertEquals((flags&1)!=0,PlayerContentPolicy.has(caps,PlayerContentPolicy.ROM_UPLOAD));assertEquals((flags&2)!=0,PlayerContentPolicy.has(caps,PlayerContentPolicy.COVER_UPLOAD));
            assertEquals((flags&4)!=0,PlayerContentPolicy.has(caps,PlayerContentPolicy.SERVER_ROM_USE));assertEquals((flags&8)!=0,PlayerContentPolicy.has(caps,PlayerContentPolicy.SERVER_COVER_USE));
            assertEquals((flags&12)==12,PlayerContentPolicy.has(caps,PlayerContentPolicy.SERVER_ROM_USE|PlayerContentPolicy.SERVER_COVER_USE));
        }
    }
    @Test void cartridgeCoverCatalogIsImmutableBoundedAndRoundTrips(){
        var covers=new ArrayList<>(List.of("a".repeat(64),"b".repeat(64)));var reply=new CartridgeNetwork.Reply(CartridgeNetwork.STATUS,CartridgeNetwork.NO_TARGET,"","","","","",0,0,new byte[0],List.of(),63,covers);covers.clear();
        var buffer=new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);try{CartridgeNetwork.Reply.CODEC.encode(buffer,reply);var decoded=CartridgeNetwork.Reply.CODEC.decode(buffer);assertEquals(reply.covers(),decoded.covers());assertEquals(63,decoded.capabilities());assertEquals(0,buffer.readableBytes());}finally{buffer.release();}
        for(var invalid:List.of(List.of("../cover"),List.of(""),List.of("a".repeat(64),"a".repeat(64)),Collections.nCopies(257,"a".repeat(64))))assertThrows(IllegalArgumentException.class,()->new CartridgeNetwork.Reply(CartridgeNetwork.STATUS,CartridgeNetwork.NO_TARGET,"","","","","",0,0,new byte[0],List.of(),63,invalid));
    }
    @Test void cabinetCatalogWireCarriesOnlyManifestsAndRequiresSameBackend(){
        var backend=ResourceLocation.parse("piq:test");var m=new CabinetGameManifest(backend.toString(),List.of(new CabinetGameManifest.Entry("game.zip","a".repeat(64),4096)));
        var request=new CabinetGameNetwork.LibraryRequest(UUID.randomUUID(),UUID.randomUUID(),backend,0,m.contentId());var buffer=new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);
        try{CabinetGameNetwork.LibraryRequest.CODEC.encode(buffer,request);assertEquals(request,CabinetGameNetwork.LibraryRequest.CODEC.decode(buffer));var reply=new CabinetGameNetwork.LibraryReply(request.request(),request.lease(),backend,true,"catalog",63,0,1,List.of(m));CabinetGameNetwork.LibraryReply.CODEC.encode(buffer,reply);assertEquals(reply,CabinetGameNetwork.LibraryReply.CODEC.decode(buffer));assertEquals(0,buffer.readableBytes());}finally{buffer.release();}
        assertThrows(IllegalArgumentException.class,()->new CabinetGameNetwork.LibraryRequest(request.request(),request.lease(),backend,64,m.contentId()));
        assertThrows(IllegalArgumentException.class,()->new CabinetGameNetwork.LibraryRequest(request.request(),request.lease(),backend,1,""));
        assertThrows(IllegalArgumentException.class,()->new CabinetGameNetwork.LibraryReply(request.request(),request.lease(),ResourceLocation.parse("piq:other"),true,"",63,0,1,List.of(m)));
        assertThrows(IllegalArgumentException.class,()->new CabinetGameNetwork.LibraryReply(request.request(),request.lease(),backend,true,"",63,0,2,List.of(m,m)));
        assertThrows(IllegalArgumentException.class,()->new CabinetGameNetwork.LibraryReply(request.request(),request.lease(),backend,true,"",64,0,0,List.of()));
    }
    @Test void optionsTransactionPreservesFullListAndRechecksCurrentOperator()throws Exception{
        String s=source("access/PlayerContentAccess");assertTrue(s.contains("old.allowedPlayers(), next.serverRomUse(), next.serverCoverUse()"));assertTrue(s.contains("!options(old).equals(expected)"));assertTrue(s.contains("policy().equals(old) && save(changed)"));assertTrue(s.contains("getPlayer(player.getUUID()) == player"));assertTrue(s.contains("player.getServer().isSameThread()"));assertFalse(s.contains("public static boolean save("));assertFalse(s.contains("isCreative()"));
    }
    @Test void cabinetSelectionUsesExactLiveLeaseIdleHostAndVerifiedObjectsBeforeCommit()throws Exception{
        String s=source("cabinet/CabinetGameLibraryService");for(String required:List.of("ServerCabinets.validateLease(p,r.lease(),r.backend())","PlayerContentAccess.canUseServerRom(s.player)","CabinetRooms.canConfigureGame(s.player,s.request.lease())","Objects.equals(s.previous","store.contains(file)","boolean authorized=valid(selection)","state.pending.values().removeIf(s->!valid(s))","CabinetSharedGameService.hasTransfer(p)"))assertTrue(s.contains(required),required);
        assertTrue(s.indexOf("boolean authorized=valid(selection)")<s.indexOf(".put(target,selected)"));assertFalse(s.contains("Files.delete"));assertFalse(s.contains("Files.readAllBytes"));
        String upload=source("cabinet/CabinetSharedGameService");assertTrue(upload.contains("CabinetRooms.canConfigureGame(j.player,j.lease)&&cn.piq.fcarcade.access.PlayerContentAccess.canUploadRom(j.player)"));assertTrue(upload.contains("if(!valid(j)||problem!=null)"));
    }
    @Test void oldFcCabinetUploadsAreIdentityBoundAndRevocationStickyAcrossTicks()throws Exception{
        String s=source("server/ServerArcadeSessions");assertTrue(s.contains("player!=upload.player"));assertTrue(s.contains("player.serverLevel()!=upload.level"));assertTrue(s.contains("player.connection.getConnection()!=upload.connection"));assertTrue(s.contains("if(!validUpload(server.getPlayerList().getPlayer(entry.getKey()),entry.getValue()))return true"));assertTrue(s.contains("selectRom(player, upload.blockPos, descriptor.sha256(), true)"));
    }
}
