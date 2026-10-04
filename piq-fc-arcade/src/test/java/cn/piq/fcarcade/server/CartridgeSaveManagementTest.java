package cn.piq.fcarcade.server;

import cn.piq.fcarcade.home.CartridgeSaveNetwork;
import cn.piq.fcarcade.home.CartridgeSaveNetwork.*;
import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CartridgeSaveManagementTest {
    private static final String HASH="a".repeat(64);
    private static String source(String file)throws Exception{return Files.readString(Path.of("src/main/java/cn/piq/fcarcade/"+file+".java"));}
    private RegistryFriendlyByteBuf buffer(){return new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);}
    @Test void openAndEachValidActionRoundTripWithoutPathsOrStateBytes(){
        var b=buffer();try{
            var open=new Open("fc",UUID.randomUUID(),UUID.randomUUID(),0,3);Open.CODEC.encode(b,open);assertEquals(open,Open.CODEC.decode(b));
            for(int op=0;op<=4;op++){var q=new Request(UUID.randomUUID(),op,op==0||op==4?"":HASH,op==0||op==4?"":HASH,op==1?"新名称":"",op==3?UUID.randomUUID():null);Request.CODEC.encode(b,q);assertEquals(q,Request.CODEC.decode(b));}
            assertEquals(0,b.readableBytes());
        }finally{b.release();}
    }
    @Test void replyBindsOriginalEditorAndIndependentSessionAndDeletionVersion(){
        var b=buffer();try{UUID editor=UUID.randomUUID(),session=UUID.randomUUID(),confirm=UUID.randomUUID();var row=new Entry(HASH,HASH,"存档","本人","FC 本地输入同步",123,400,false,true);var reply=new Reply(session,"fc",HASH,"确认删除",List.of(row),HASH,HASH,confirm,false,editor);Reply.CODEC.encode(b,reply);assertEquals(reply,Reply.CODEC.decode(b));assertEquals(0,b.readableBytes());}finally{b.release();}
    }
    @Test void namespacedAddonCodecDoesNotGrantUnknownSystemAuthority()throws Exception{
        var b=buffer();try{var open=new Open("piq_md_home:md",UUID.randomUUID(),UUID.randomUUID(),0,3);Open.CODEC.encode(b,open);assertEquals(open,Open.CODEC.decode(b));}finally{b.release();}
        var s=source("home/CartridgeSaveNetwork");assertTrue(s.contains("else if(r.system().equals(\"fc\")||r.system().equals(\"sfc\"))opener.accept(p,r)"));assertTrue(s.contains("此机型未注册存档管理服务"));
        assertThrows(IllegalArgumentException.class,()->new Open("UPPER:bad",UUID.randomUUID(),UUID.randomUUID(),0,0));
    }
    @Test void malformedOrUnconfirmedDeleteAndHiddenFieldsFailClosed(){
        UUID id=UUID.randomUUID();assertThrows(IllegalArgumentException.class,()->new Request(id,3,HASH,HASH,"",null));assertThrows(IllegalArgumentException.class,()->new Request(id,2,HASH,HASH,"",id));assertThrows(IllegalArgumentException.class,()->new Request(id,0,HASH,"","",null));assertThrows(IllegalArgumentException.class,()->new Request(id,1,"../../save",HASH,"new",null));assertThrows(IllegalArgumentException.class,()->new Request(id,1,HASH,HASH,"x".repeat(33),null));assertThrows(IllegalArgumentException.class,()->new Open("gba",id,id,0,0));
    }
    @Test void decoderRejectsOversizedCatalogBeforeAllocatingRows(){
        var b=buffer();try{b.writeUUID(UUID.randomUUID());b.writeUtf("fc");b.writeUtf(HASH);b.writeUtf("bounded");b.writeVarInt(65);assertThrows(IllegalArgumentException.class,()->Reply.CODEC.decode(b));}finally{b.release();}
    }
    @Test void standaloneSaveLeaseRechecksPhysicalBindingAndPermissionCallback()throws Exception{
        var s=source("server/CartridgeSaveService");for(String guard:List.of("p.connection.getConnection()!=s.connection","p.serverLevel()!=s.level","!PlayerContentAccess.canBrowse(p)","s.level.getBlockEntity(s.computer.getBlockPos())!=s.computer","!s.computer.computerId().equals(s.computerId)","p.distanceToSqr(s.computer.getBlockPos().getCenter())>25","p.getItemInHand(s.hand)!=s.stack","!ItemStack.isSameItemSameComponents(s.stack,s.snapshot)","copies==1","&&facts(p,s)","BUSY.get()"))assertTrue(s.contains(guard),guard);
        assertTrue(s.contains("s.confirmation.equals(r.confirmation())"));assertTrue(s.contains("s.confirmExpires>tick"));assertTrue(s.contains("s.pendingVersion.equals(r.version())"));assertTrue(s.contains("FcSaveManagementStore.allowed(row,p.getUUID(),p.hasPermissions(2),s.card)"));
        int consume=s.indexOf("s.clearConfirmation();if(!confirmed)");assertTrue(consume>0&&consume<s.indexOf("s.store.delete(r.id(),r.version())"));
        assertTrue(s.contains("!row.version().equals(r.version())"));assertTrue(s.contains("ServerArcadeSessions.cartridgeSaveActive"));assertTrue(s.contains("SFC 托管 SRAM 存档管理尚未接入"));
        assertTrue(s.contains("tick-s.lastRequest<20"));assertTrue(s.contains("LAST_OPEN"));assertTrue(s.indexOf("tick-s.lastRequest<20")<s.indexOf("if(!permitted(p,s)||SESSIONS"));
    }
    @Test void onlyExistingEditorCanGrantAndAllClosingModesAreBusy()throws Exception{
        var s=source("server/ServerCartridgeService");var grant=s.substring(s.indexOf("static CartridgeSaveService.Grant authorizeSaveManager"),s.indexOf("public static void computerRemoved"));assertTrue(grant.contains("!state.valid(player,session,target)"));assertTrue(grant.contains("session.processing||session.upload!=null"));assertTrue(grant.contains("!PlayerContentAccess.canBrowse(player)"));
        s=source("server/ServerArcadeSessions");var busy=s.substring(s.indexOf("static boolean cartridgeSaveActive("),s.indexOf("public static void openSaveCatalog("));assertTrue(busy.contains("m.sessions.values()"));assertTrue(busy.contains("m.closingHosted"));assertFalse(busy.contains("RomSaveMode.PLAYER"));
    }
}
