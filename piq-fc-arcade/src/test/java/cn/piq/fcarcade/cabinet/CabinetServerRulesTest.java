package cn.piq.fcarcade.cabinet;

import java.nio.file.*;
import java.util.*;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CabinetServerRulesTest {
    private static String source(String name)throws Exception{return Files.readString(Path.of("src/main/java/cn/piq/fcarcade/"+name+".java"));}
    @Test void threeUserModesAndIndependentFallback(){
        var manual=new CabinetServerRules(false,0,16);
        assertFalse(manual.timedShutdown());assertFalse(manual.closeAfterExit(true,false,true));
        var immediate=new CabinetServerRules(true,0,16);
        assertFalse(immediate.timedShutdown());assertTrue(immediate.closeAfterExit(true,false,true));
        var both=new CabinetServerRules(true,60,16);
        assertTrue(both.timedShutdown());assertTrue(both.closeAfterExit(true,false,true));
        assertFalse(both.closeAfterExit(true,true,true));assertFalse(both.closeAfterExit(false,false,true));assertFalse(both.closeAfterExit(true,false,false));
        assertEquals(new CabinetServerRules(false,60,16),CabinetServerRules.DEFAULT);
    }
    @Test void acceptsZeroAndBoundsRejectInvalidPackets(){
        for(int s:new int[]{0,1,60,3600})for(int r:new int[]{1,16,64,128})assertNotNull(new CabinetServerRules(false,s,r));
        for(int s:new int[]{-1,3601,Integer.MAX_VALUE})assertThrows(IllegalArgumentException.class,()->new CabinetServerRules(false,s,16));
        for(int r:new int[]{0,-1,129,Integer.MAX_VALUE})assertThrows(IllegalArgumentException.class,()->new CabinetServerRules(false,60,r));
    }
    @Test void nativeNbtRoundTripsZeroAndFalseWithoutFallingBack(){
        for(boolean immediate:new boolean[]{false,true})for(int seconds:new int[]{0,1,60,3600})for(int range:new int[]{1,16,128}){
            var in=new CompoundTag();in.putBoolean("ImmediateOnExit",immediate);in.putInt("IdleSeconds",seconds);in.putInt("Range",range);in.putLong("Revision",12);
            var out=CabinetServerSettings.load(in,null).save(new CompoundTag(),null);
            assertEquals(immediate,out.getBoolean("ImmediateOnExit"));assertEquals(seconds,out.getInt("IdleSeconds"));assertEquals(range,out.getInt("Range"));assertEquals(12,out.getLong("Revision"));
        }
    }
    @Test void missingAndMalformedNbtUseDocumentedDefaults(){
        var tags=new ArrayList<CompoundTag>();tags.add(new CompoundTag());
        var malformed=new CompoundTag();malformed.putString("IdleSeconds","0");malformed.putString("Range","128");tags.add(malformed);
        var invalid=new CompoundTag();invalid.putInt("IdleSeconds",-1);invalid.putInt("Range",999);invalid.putLong("Revision",-1);tags.add(invalid);
        for(var tag:tags){var out=CabinetServerSettings.load(tag,null).save(new CompoundTag(),null);assertEquals(60,out.getInt("IdleSeconds"));assertEquals(16,out.getInt("Range"));assertEquals(1,out.getLong("Revision"));}
    }
    @Test void compareAndSetRejectsStaleWritesAndIdempotentReadDoesNotResetTimers(){
        var settings=CabinetServerSettings.load(new CompoundTag(),null);var changed=new CabinetServerRules(true,0,32);
        assertFalse(settings.replace(0,changed));assertTrue(settings.replace(1,changed));assertFalse(settings.replace(1,CabinetServerRules.DEFAULT));
        assertTrue(settings.replace(2,changed));var out=settings.save(new CompoundTag(),null);assertEquals(2,out.getLong("Revision"));assertEquals(0,out.getInt("IdleSeconds"));
        var max=new CompoundTag();max.putLong("Revision",Long.MAX_VALUE);assertFalse(CabinetServerSettings.load(max,null).replace(Long.MAX_VALUE,changed));
    }
    @Test void lastSeatAcrossAllRoomModesOnlyAndRejoinCancels(){
        for(var mode:CabinetSyncMode.values()){
            var ledger=new CabinetRoomLedger<String>();var room=ledger.open(UUID.randomUUID(),"pair",CabinetCoinPolicy.BACKEND,4,0);room.mode=mode;room.ready=true;
            var guest=ledger.join(UUID.randomUUID(),room,0,2,3);room.host().controlling=false;
            var rules=new CabinetServerRules(true,60,16);assertFalse(rules.closeAfterExit(true,room.hasController(),room.ready));
            guest.controlling=false;assertTrue(rules.closeAfterExit(true,room.hasController(),room.ready));
            var timer=new CabinetIdleShutdown<UUID>();timer.update(room.id,false,true,60,0);guest.controlling=true;timer.update(room.id,room.hasController(),true,60,1);assertTrue(timer.pollDue(60_000_000_000L).isEmpty());
        }
    }
    @Test void zeroDisablesCountdownAndSettingsChangeStartsFreshDeadline(){
        var timer=new CabinetIdleShutdown<String>();timer.update("r",false,true,60,0);timer.update("r",false,true,0,1);assertTrue(timer.pollDue(100_000_000_000L).isEmpty());
        timer.update("r",false,true,10,0);timer.cancel("r");timer.update("r",false,true,30,5_000_000_000L);
        assertTrue(timer.pollDue(34_999_999_999L).isEmpty());assertEquals(List.of("r"),timer.pollDue(35_000_000_000L));
    }
    @Test void rangeBoundaryAndLegacyProviderBoundsRemainSupported(){
        for(int range:new int[]{1,16,64,128}){
            var rules=new CabinetServerRules(false,60,range);assertTrue(rules.visible((double)range*range));assertFalse(rules.visible((double)range*range+.001));
            var source=new WatchLedger.Source(UUID.randomUUID(),UUID.randomUUID());var ledger=new WatchLedger();var p=UUID.randomUUID();var c=new Object();
            assertNotNull(ledger.select(p,c,List.of(new WatchLedger.Candidate(source,range*range,range*range,rules.exitRange()*rules.exitRange())),true,0));
            assertNull(ledger.select(p,c,List.of(new WatchLedger.Candidate(source,rules.exitRange()*rules.exitRange()+.001,range*range,rules.exitRange()*rules.exitRange())),true,1));
        }
        var source=new WatchLedger.Source(UUID.randomUUID(),UUID.randomUUID());assertEquals(256,new WatchLedger.Candidate(source,0).enterSquared());
    }
    @Test void globalNetworkUsesHeldTerminalOpConnectionNonceAndRevision()throws Exception{
        var net=source("cabinet/CabinetServerNetwork");
        for(var check:List.of("p.hasPermissions(2)","ModItems.ADMIN_TERMINAL.get()","getPlayer(p.getUUID())==p","server.isSameThread()","source==player.connection.getConnection()","request.expectedRevision()"))assertTrue(net.contains(check.replace("server.isSameThread()","getServer().isSameThread()")),check);
        assertFalse(net.contains("isCreative()"));assertTrue(net.contains("PlayerLoggedInEvent"));assertFalse(net.contains("TickEvent"));
        var client=source("client/cabinet/CabinetServerSettingsScreen");assertTrue(client.contains("value.nonce().equals(nonce)"));assertTrue(client.contains("state==null?0:state.revision()"));
    }
    @Test void oldCabinetsReadGlobalWithoutChunkEnumerationAndLocalWritesAreRetired()throws Exception{
        var settings=source("config/GameConsoleAdminSettings");assertTrue(settings.contains("block instanceof LegacyFcArcadeBlockEntity) return cn.piq.fcarcade.cabinet.CabinetServerSettings.rules"));
        assertTrue(settings.contains("home.observationRange(fallback)"));assertTrue(settings.contains("external.observationRange(fallback)"));
        var network=source("cabinet/CabinetSyncSettings");assertFalse(network.contains(".setPowerSettings("));assertFalse(network.contains(".setObservationRange("));
        var global=source("cabinet/CabinetServerSettings");assertFalse(global.contains("getChunk("));assertFalse(global.contains("setBlock"));
        var rooms=source("cabinet/CabinetRooms");assertTrue(rooms.contains("copyServerRules(room,CabinetServerSettings.rules(server))"));assertTrue(rooms.contains("state.idleShutdown.cancel(room.id);updateIdleShutdown(state,room)"));
    }
    @Test void hostMediaIsOfferedBeforeVisibilityUploadGateAndObserversSkipUpload()throws Exception{
        var client=source("client/cabinet/CabinetClientBackends");client=client.substring(client.indexOf("@SubscribeEvent public static void render("));
        assertTrue(client.indexOf("media.offer(frame)")<client.indexOf("if(visible){"));assertTrue(client.indexOf("if(visible){")<client.indexOf("new DynamicTexture"));
        var watch=source("client/watch/WatchClient");watch=watch.substring(watch.indexOf("@SubscribeEvent public static void render("));
        assertTrue(watch.indexOf("adapter.visible(event,d)")>=0);
        assertTrue(watch.indexOf("adapter.visible(event,d)")<watch.indexOf("r.texture.upload()"));
        var cache=source("client/cabinet/CabinetClientSettings");assertTrue(cache.contains("source==current()"));assertTrue(cache.contains("value.revision()<state.revision()"));
    }
}
