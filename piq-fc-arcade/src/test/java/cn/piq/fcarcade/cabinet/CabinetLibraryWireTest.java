package cn.piq.fcarcade.cabinet;

import java.util.*;
import java.nio.file.*;
import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CabinetLibraryWireTest {
    private static final ResourceLocation BACKEND=ResourceLocation.parse("piq_native_arcade:mame");
    private static CabinetGameManifest game(int n){
        var files=new ArrayList<CabinetGameManifest.Entry>();
        files.add(new CabinetGameManifest.Entry("游".repeat(116)+String.format(Locale.ROOT,"%03d",n)+".zip",String.format(Locale.ROOT,"%064x",n+1),64*1024*1024));
        for(String bios:List.of("pgm.zip","neogeo.zip","qsound.zip","qsound_hle.zip"))files.add(new CabinetGameManifest.Entry(bios,"a".repeat(64),16*1024*1024));
        return new CabinetGameManifest(BACKEND.toString(),files);
    }
    @Test void allCatalogSizesNavigateWithoutMissingOrDuplicatingEntriesAndStayWithinWireBudget(){
        var profile=new CabinetGameProfile("名".repeat(64),4,CabinetGameProfile.Orientation.PORTRAIT,CabinetGameProfile.Aspect.THREE_FOUR,Integer.MAX_VALUE);
        for(int total:new int[]{0,1,7,8,64,65,512}){
            var all=new ArrayList<CabinetGameManifest>();for(int i=0;i<total;i++)all.add(game(i));
            var received=new ArrayList<CabinetGameManifest>();
            for(int start=0;start<Math.max(1,total);start+=CabinetLibraryPage.SIZE){
                int offset=CabinetGameLibraryService.pageOffset(start,total);var page=all.subList(offset,Math.min(total,offset+CabinetLibraryPage.SIZE));
                var request=new CabinetGameNetwork.LibraryRequest(UUID.randomUUID(),UUID.randomUUID(),BACKEND,offset,"");
                var reply=new CabinetGameNetwork.LibraryReply(request.request(),request.lease(),BACKEND,true,"信".repeat(160),63,offset,total,page,Collections.nCopies(page.size(),profile),true);
                var b=new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);
                try{
                    CabinetGameNetwork.LibraryRequest.CODEC.encode(b,request);assertEquals(request,CabinetGameNetwork.LibraryRequest.CODEC.decode(b));b.clear();
                    CabinetGameNetwork.LibraryReply.CODEC.encode(b,reply);
                    assertTrue(b.readableBytes()+256<=CabinetLibraryPage.conservativeBytes(page.size()));
                    assertTrue(CabinetLibraryPage.conservativeBytes(page.size())<=32768);
                    var decoded=CabinetGameNetwork.LibraryReply.CODEC.decode(b);assertEquals(reply,decoded);assertEquals(0,b.readableBytes());received.addAll(decoded.games());
                }finally{b.release();}
            }
            assertEquals(all,received);
        }
    }
    @Test void oldStrideAndOversizePageAreRejectedBeforeSending(){
        assertThrows(IllegalArgumentException.class,()->new CabinetGameNetwork.LibraryRequest(UUID.randomUUID(),UUID.randomUUID(),BACKEND,64,""));
        assertThrows(IllegalArgumentException.class,()->new CabinetGameNetwork.LibraryReply(UUID.randomUUID(),UUID.randomUUID(),BACKEND,true,"",63,0,8,java.util.stream.IntStream.range(0,8).mapToObj(CabinetLibraryWireTest::game).toList()));
    }
    @Test void editsUseContainingPageAndRetriesRevalidateAuthorityWithoutBypassingTransport()throws Exception{
        String s=Files.readString(Path.of("src/main/java/cn/piq/fcarcade/cabinet/CabinetGameLibraryService.java"));
        assertTrue(s.contains("catalog.indexOf(game)/CabinetLibraryPage.SIZE"));
        assertTrue(s.contains("!send(delivery)&&!state.replies.offer"));
        for(String guard:List.of("!current(d.player,d.connection)","!d.target.equals(target(p,r))","!CabinetRooms.canConfigureGame(p,r.lease())","!PlayerContentAccess.canBrowse(p)","!PlayerContentAccess.canUseServerRom(p)"))assertTrue(s.contains(guard),guard);
        assertTrue(s.contains("CabinetLibraryPage.conservativeBytes(response.games().size())"));
        assertFalse(s.contains("PacketDistributor.sendToPlayer"));
    }
}
