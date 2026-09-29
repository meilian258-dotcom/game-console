package cn.piq.fcarcade.server;

import cn.piq.fcarcade.home.*;
import cn.piq.fcarcade.session.NesCoreVariant;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class CartridgeSave61Test {
    @TempDir Path root;
    static final String ROM="a".repeat(64), OTHER="b".repeat(64);
    static final Instant NOW=Instant.parse("2026-09-20T12:00:00Z");
    static Instant ago(int days){return NOW.minusSeconds(days*86400L);}
    @Test void modesAndProgressFollowBoardThroughShellSwap(){
        for(int mode=-1;mode<=2;mode++)for(boolean saved:new boolean[]{false,true}){
            UUID card=UUID.randomUUID();
            var initial=new CartridgeParts.Whole(card,ROM,"game",OTHER,0,0,mode,saved);
            var pieces=CartridgeParts.split(initial,2,UUID.randomUUID());
            var reassembled=CartridgeParts.combine(pieces.board(),new CartridgeParts.Shell(UUID.randomUUID(),""));
            assertEquals(card,reassembled.id());assertEquals(mode,reassembled.saveMode());assertEquals(saved,reassembled.saved());
            assertEquals(ROM,reassembled.rom());assertEquals("",reassembled.cover());assertEquals(2,reassembled.revision());
        }
    }
    @Test void physicalCardIdentityIsIndependentAndGunAbiStillIsolated(){
        UUID a=UUID.randomUUID(),b=UUID.randomUUID();Set<String> keys=new HashSet<>();
        for(var core:NesCoreVariant.values()){
            String key=core.saveKey(CartridgeSaveIdentity.key(a));assertTrue(keys.add(key));
            assertTrue(CartridgeSaveIdentity.owns(key,a));assertFalse(CartridgeSaveIdentity.owns(key,b));
            assertFalse(CartridgeSaveIdentity.owns("player|"+a,a));
            assertFalse(CartridgeSaveIdentity.owns(key,new UUID(0,0)));
        }
        assertThrows(IllegalArgumentException.class,()->CartridgeSaveIdentity.key(null));
        assertThrows(IllegalArgumentException.class,()->new CartridgeParts.Whole(a,ROM,"","",0,0,3,false));
    }
    @Test void cardSavesNeverExpireAndCanOnlyBeManagedWithMatchingCard()throws Exception{
        var saves=new ArcadeSaveStore(root);UUID card=UUID.randomUUID(),owner=UUID.randomUUID();
        String key=CartridgeSaveIdentity.key(card);saves.save(key,ROM,new byte[]{1});
        saves.save("machine|old",ROM,new byte[]{2});saves.save("player|unknown",ROM,new byte[]{3});
        saves.played(owner,ago(100));assertEquals(0,saves.cleanupOlderThanDays(1,NOW));assertEquals(3,saves.list().size());
        var row=new FcSaveManagementStore(root).current(saves.list().stream().filter(s->s.saveKey().equals(key)).findFirst().orElseThrow().storageId());
        assertTrue(FcSaveManagementStore.allowed(row,owner,false,card));
        assertFalse(FcSaveManagementStore.allowed(row,owner,false,UUID.randomUUID()));
    }
    @Test void cleanupUsesPlayerActivityNotFileAgeAndMovesBytesToRecoveryDirectory()throws Exception{
        var saves=new ArcadeSaveStore(root);UUID owner=UUID.randomUUID();String key=PlayerSaveSlots.key(owner,1);
        saves.save(key,ROM,new byte[]{5,4,3});byte[] original=Files.readAllBytes(saves.path(key,ROM));
        saves.played(owner,ago(31));
        saves.load(key,ROM); // Reading touches the old file timestamp but must not pretend the player played.
        assertEquals(0,saves.cleanupOlderThanDays(0,NOW));assertEquals(1,saves.cleanupOlderThanDays(30,NOW));
        assertFalse(saves.exists(key,ROM));
        try(var files=Files.list(root.resolve("deleted-by-cartridge-manager"))){assertArrayEquals(original,Files.readAllBytes(files.findFirst().orElseThrow()));}
    }
    @Test void currentActivityProtectsEveryOldPersonalSlot(){
        var saves=new ArcadeSaveStore(root);UUID owner=UUID.randomUUID();
        for(int slot=1;slot<=3;slot++)saves.save(PlayerSaveSlots.key(owner,slot),ROM,new byte[]{1});
        saves.played(owner,ago(31));
        assertEquals(0,saves.cleanupOlderThanDays(30,NOW,info->info.saveKey().endsWith("|2")));
        saves.played(owner,ago(1));assertEquals(0,saves.cleanupOlderThanDays(30,NOW));assertEquals(3,saves.list().size());
    }
    @Test void missingHistoryReceivesFullGraceAndThresholdIsStrict(){
        var saves=new ArcadeSaveStore(root);UUID owner=UUID.randomUUID();String key=PlayerSaveSlots.key(owner,1);
        saves.save(key,ROM,new byte[]{1});
        assertEquals(0,saves.cleanupOlderThanDays(30,NOW));
        assertEquals(0,saves.cleanupOlderThanDays(30,NOW.plusSeconds(30*86400L)));
        assertEquals(1,saves.cleanupOlderThanDays(30,NOW.plusSeconds(30*86400L+1)));
    }
    @Test void failedActivityWriteKeepsCurrentProcessConservative()throws Exception{
        var activity=new PersonalSaveActivity(root);UUID player=UUID.randomUUID();activity.played(player,ago(31));
        Path marker=root.resolve("personal-play-activity").resolve(player+".bin");Files.delete(marker);Files.createDirectory(marker);
        assertThrows(java.io.IOException.class,()->activity.played(player,NOW));
        Files.delete(marker);Files.write(marker,java.nio.ByteBuffer.allocate(8).putLong(ago(31).toEpochMilli()).array());
        assertEquals(NOW.toEpochMilli(),activity.lastPlayed(player,NOW));
    }
    @Test void copiesUnambiguousTransportSaveWithoutChangingOriginal()throws Exception{
        var saves=new ArcadeSaveStore(root);UUID player=UUID.randomUUID();String target=PlayerSaveSlots.key(player,1),old="player-home-v1|"+target;
        saves.save(old,ROM,new byte[]{1,2,3},"progress",2);byte[] before=Files.readAllBytes(saves.path(old,ROM));
        HomePersonalSaveMigration.copy(saves,player,NesCoreVariant.LEGACY,k->false);
        assertArrayEquals(new byte[]{1,2,3},saves.loadReadOnly(target,ROM));assertArrayEquals(before,Files.readAllBytes(saves.path(old,ROM)));
    }
    @Test void ambiguousMigrationAndBusyDestinationNeverOverwrite(){
        var saves=new ArcadeSaveStore(root);UUID player=UUID.randomUUID();String target=PlayerSaveSlots.key(player,1);
        saves.save("player-home-v1|"+target,ROM,new byte[]{1});saves.save("server-home-v1|"+target,ROM,new byte[]{2});
        HomePersonalSaveMigration.copy(saves,player,NesCoreVariant.LEGACY,k->false);assertFalse(saves.exists(target,ROM));
        saves.save("server-home-v1|"+target,ROM,new byte[]{1});
        HomePersonalSaveMigration.copy(saves,player,NesCoreVariant.LEGACY,k->k.equals(target));assertFalse(saves.exists(target,ROM));
        saves.save(target,OTHER,new byte[]{9});HomePersonalSaveMigration.copy(saves,player,NesCoreVariant.LEGACY,k->false);
        assertArrayEquals(new byte[]{9},saves.loadReadOnly(target,OTHER));assertFalse(saves.exists(target,ROM));
    }
    static String source(String name)throws Exception{return Files.readString(Path.of("src/main/java/cn/piq/fcarcade/"+name+".java"));}
    static String section(String text,String begin,String end){int start=text.indexOf(begin);return text.substring(start,text.indexOf(end,start));}
    @Test void homeAdmissionPinsActualCardModeAndDoesNotDiscardProgressOnRestart()throws Exception{
        String s=source("server/ServerArcadeSessions");String create=section(s,"private boolean createHome(","private int homeMachineSavePlayers(");
        for(String guard:new String[]{"cartridgeMode(c)!=mode","cardActive(cardId)","cartridgeSaveKey(p.getServer(),c,rom,gun)","loadReadOnly(saveKey,rom)","closingHosted.stream()"})assertTrue(create.contains(guard),guard);
        // Netplay retires a personal slot's old ROM only AFTER the new checkpoint is durable.
        assertTrue(create.indexOf("store.save(saveKey,rom,state")<create.indexOf("store.delete(saveKey,old.romSha256())"));
        assertTrue(create.contains("if(mode==RomSaveMode.PLAYER)"));
        assertTrue(create.contains("!old.romSha256().equals(rom)"));
        assertTrue(s.contains("s.homeCardId.equals(cn.piq.fcarcade.home.FcCartridgeData.id(c.insertedCartridge()))"));
        assertTrue(s.contains("s.saveMode==cartridgeMode(c)"));
        String saved=section(s,"private boolean saveState(","private static void reportSaveFailure(");
        assertTrue(saved.contains("saves(server).save("));assertTrue(saved.indexOf("cartridgeSaved(")>saved.indexOf("saves(server).save("));
    }
    @Test void sessionChoiceIsOnceAndNoJoinAttemptCreatesHostRequest()throws Exception{
        String s=source("server/ServerArcadeSessions");
        for(String part:new String[]{section(s,"private boolean requestHomeControl(","private boolean homeSocketAvailable("),section(s,"private void requestSecondPlayer(","private void selectRom(")}){
            assertFalse(part.contains("requestJoinApproval"));assertFalse(part.contains("pendingApplicants.add"));assertFalse(part.contains("homeRequests.put"));
        }
        String choose=section(s,"private void setMultiplayerEnabled(","private void decideJoin(");
        assertTrue(choose.contains("session.multiplayerChosen"));assertTrue(choose.contains("computeHost(session,player)"));assertTrue(choose.contains("ArcadeRole.PLAYER_ONE"));
        assertTrue(s.contains("!computeHost(s,p)&&!s.multiplayerEnabled"));
    }
    @Test void animationReadsOneLocalMonotonicClockAndUnrelatedPacketsDoNotRestart()throws Exception{
        String client=section(source("client/HomeApplianceClient"),"static double powerAmount(","static void drawIdle(");
        assertFalse(client.contains("getGameTime"));assertTrue(client.contains("TelevisionPowerTransition.presentationTime()"));
        String packets=section(source("home/HomeTvBlockEntity"),"@Override public void onDataPacket(","/** Only the loaded");
        assertFalse(packets.contains("getGameTime"));assertTrue(packets.contains("TelevisionPowerTransition.presentationTime()"));
        var t=new TelevisionPowerTransition();t.loaded(false);t.observe(true,true,100);
        double previous=-1;
        for(int frame=0;frame<=84;frame++){double now=100+frame/6.0;t.observe(true,true,now);double amount=t.amount(now);assertTrue(amount>=previous);previous=amount;}
        assertEquals(1,previous);
    }
}
