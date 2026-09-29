package cn.piq.fcarcade.session;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;
class ZapperSessionSourceContractTest {
    private static String source(String path)throws Exception{return Files.readString(Path.of("src/main/java/cn/piq/fcarcade/"+path+".java"));}
    private static String section(String text,String begin,String end){int first=text.indexOf(begin),last=text.indexOf(end,first);assertTrue(first>=0&&last>first,"Missing source boundary: "+begin);return text.substring(first,last).replaceAll("\\s+","");}
    @Test void networkBindsActualConnectionAndOnlyGunSessionCanReceive()throws Exception{
        String n=source("FcNetwork"),s=source("server/ServerArcadeSessions");assertTrue(n.contains("player.connection.getConnection()==source"));assertTrue(n.contains("sink.acceptsConnection(source)"));
        assertTrue(s.contains("payload.epoch()!=s.lockstep.epoch()"));assertTrue(s.contains("!s.zapperBinding.lease().equals(payload.lease())"));assertTrue(s.contains("HomeZapperService.authorized(player,s.zapperBinding,true)"));
        assertTrue(s.contains("HomeZapperAim.sample(player,s.zapperBinding)"));assertFalse(s.contains("s.lockstep.acceptZapper(player.getUUID(),payload.epoch(),payload.sequence(),payload.packed()"));
    }
    @Test void gunTakeCannotPowerOrReplaceSessionAndPowerUsesIsolatedMachineSave()throws Exception{
        String s=source("server/ServerArcadeSessions");String start=s.substring(s.indexOf("public static boolean startHomeZapper"),s.indexOf("public static void pauseHomeZapper"));
        assertTrue(start.contains("return takeHomeZapper(player,console,stack)"));assertFalse(start.contains("new Session"));assertFalse(start.contains("loadState"));
        String power=s.substring(s.indexOf("public static boolean powerHomeConsole"),s.indexOf("public static boolean homeRunning"));
        assertTrue(power.contains("Session existing=m.sessions.get(key);if(existing!=null)return"));
        // Opening now also rejects a still-closing hosted save slot. Do not require
        // the old two guards to be textually adjacent and thereby ban that check.
        String opening=section(s,"private boolean createHome(","if(mode==RomSaveMode.MACHINE&&resume)");
        assertTrue(opening.contains("||sessions.containsKey(key)"));
        assertTrue(opening.contains("||mode==RomSaveMode.PLAYER&&personalSaveKeyActive(saveKey)"));
        assertTrue(opening.contains("||mode!=RomSaveMode.NONE&&closingHosted.stream().anyMatch(s->s.saveKey.equals(saveKey))"));
        assertTrue(opening.contains("if(cardId==null||cardActive(cardId))returnfalse;"));
        assertTrue(opening.contains("homeAccepts(p.getServer(),c,rom,gun,saves(p.getServer()).loadReadOnly(saveKey,rom))"));
        // Only a read-only metadata lookup now sits between admission guards and construction.
        String machinePlayers=section(s,"if(mode==RomSaveMode.MACHINE&&resume)","Session s=new Session");
        assertEquals("if(mode==RomSaveMode.MACHINE&&resume)players=homeMachineSavePlayers(p,c,rom,gun,saveKey,key,players);",machinePlayers);
        assertTrue(s.contains("gun?\"core|\"+cn.piq.fcarcade.session.NesCoreVariant.ZAPPER_V1.stateNamespace()+\"|\"+key:key"));
        assertTrue(power.contains("m.cartridgeSaveKey(player.getServer(),console,rom,gun)"));
        String machineKey=section(s,"private String homeSaveKey(MinecraftServer","private boolean createHome(");
        assertTrue(machineKey.contains("returncoreVariant(server,rom,gun).saveKey(key);"));
        assertTrue(machineKey.contains("CartridgeSaveIdentity.key("));
        assertEquals("machine",NesCoreVariant.LEGACY.saveKey("machine"));
        assertEquals("core|"+NesCoreVariant.ZAPPER_V1.stateNamespace()+"|machine",NesCoreVariant.ZAPPER_V1.saveKey("machine"));
        String legacy=section(s,"byte[] saved=s.netplay==null&&resume&&mode!=RomSaveMode.NONE?loadState(p.getServer(),s):null;","if(hostedSelected(c)){");
        assertTrue(legacy.contains("!hostedSelected(c)&&!s.playerMedia&&saved==null&&resume&&gun&&mode==RomSaveMode.MACHINE&&s.homeCardId==null&&!saves(p.getServer()).exists(saveKey,rom)"));
        assertTrue(legacy.contains("loadReadOnly(homeSaveKey(true,\"player|\"+p.getUUID()+\"|\"+machineKey(key)),rom)"));
        assertTrue(legacy.contains("s.variant.acceptsPersistentStateHeader(old,rom)"));
        String metadata=section(s,"private int homeMachineSavePlayers(","private void openHomeSaveSlots(");
        assertTrue(metadata.contains("saveKey.equals(existing.saveKey())&&rom.equals(existing.romSha256())"));
        assertTrue(metadata.contains("Stringlegacy=homeSaveKey(true,\"player|\"+p.getUUID()+\"|\"+machineKey(key))"));
        assertTrue(metadata.contains("!hostedSelected(c)&&c.synchronizationMode()!=CabinetSyncMode.MEDIA&&gun&&!saves(p.getServer()).exists(saveKey,rom)"));
        for(String readOnly:new String[]{legacy,metadata}){assertFalse(readOnly.contains(".delete("));assertFalse(readOnly.contains(".save("));}
        assertTrue(power.contains("m.library(player.getServer()).saveMode(rom)"));assertTrue(power.contains("mode==RomSaveMode.PLAYER"));assertTrue(power.contains("mode==RomSaveMode.NONE"));
        assertFalse(power.contains("HomeControllerService.grant"));assertFalse(power.contains("roster.join"));assertFalse(power.contains("delete("));
        String take=s.substring(s.indexOf("public static boolean takeHomeZapper"),s.indexOf("public static void handleHomeInput"));assertTrue(take.contains("s.homeRuntime==null"));assertTrue(take.contains("!s.variant.isZapper()"));
    }
    @Test void clientUsesServerVariantAndPhysicalGunOrControllerForInputButIndependentHostForSnapshots()throws Exception{
        String s=source("client/ClientArcadeSession");assertTrue(s.contains("expectedVariant.isZapper()"));assertTrue(s.contains("created.stateNamespace().equals(expectedVariant.stateNamespace())"));
        assertTrue(s.contains("variant.isZapper()?heldZapper()||homeRuntime&&role==ArcadeRole.PLAYER_ONE&&heldController():!homeRuntime||heldController()"));assertTrue(s.contains("workerReady&&zapperControlsEnabled&&keyboardAuthorized()"));
        String snapshots=section(s,"private boolean snapshotOwner()","void join(");
        assertTrue(snapshots.contains("return!serverHosted()&&(homeRuntime?computeHost:role==ArcadeRole.PLAYER_ONE);"),"Only the local computing owner may upload snapshots; SERVER clients never may");
        assertFalse(snapshots.contains("hostedOperator"),"Invitation approval must not grant snapshot authority");
        String starter=section(s,"private void startWorker(","private void consumeResults(");
        assertTrue(starter.indexOf("if(serverHosted())return;")>=0&&starter.indexOf("if(serverHosted())return;")<starter.indexOf("newClientNesWorker"),"Hosted receivers must never construct a local core");
    }
    @Test void snapshotReplayCarriesGunBeforeRealCoreFrame()throws Exception{String w=source("client/ClientNesWorker"),c=source("client/ClientArcadeSession");assertTrue(w.indexOf("core.setZapperState")<w.indexOf("core.runFrame()"));assertTrue(c.contains("run.playerTwoMask(),run.zapperState()"));assertTrue(c.contains("payload.playerTwoMask(),payload.zapperState()"));}
    @Test void poweredSocketCleanupCannotReaddDisconnectedViewerOrUseLegacyMembershipFallback()throws Exception{
        String s=source("server/ServerArcadeSessions");
        String detach=section(s,"private void detachHomeDevice(","private void detachHome(");
        // The same live-connection guard encloses both paths: old local viewers
        // retain local observation; hosted clients leave and use WatchService.
        assertTrue(detach.contains("if(current(p)&&s.homeRuntime.player(p.getUUID())==null&&!computeHost(s,p)){if(s.hosted!=null||s.playerMedia)sendInactive(p,s);else{s.viewers.add(p.getUUID());addTracking(viewerships,p.getUUID(),s.key);sendViewerSession(p,s);}}"));
        assertFalse(section(s,"private void detachStaleHome(","private boolean storageInitialized").contains("viewers.add"),"Stale sockets must never be re-added as viewers");
        assertTrue(s.contains("request.connection()==player.connection.getConnection())session.homeRequests.remove(player.getUUID(),request)"));
        assertTrue(s.contains("memberSession != null && memberSession.homeRuntime==null && memberSession.id == sessionId"));
        assertTrue(s.contains("if(!current(p)||c==null||c.connection()!=p.connection.getConnection())detachStaleHome(server,session,id)"));
    }
    @Test void backgroundHostSurvivesLocalDisplayUnloadButNotLostWorldOrConnection()throws Exception{
        String all=source("client/ClientArcadeSession");String s=all.substring(all.indexOf("private boolean isWorldAndBlockValid()"),all.indexOf("private boolean isActiveAt"));
        assertTrue(s.indexOf("dimension != minecraft.level.dimension()")<s.indexOf("if(computeHost)return connected()"));
        assertTrue(s.indexOf("if(computeHost)return connected()")<s.indexOf("minecraft.level.getBlockState(arcadePos)"));
        assertTrue(all.contains("minecraft.getConnection()==keyboardConnection&&minecraft.getConnection().getConnection().isConnected()"));
        String event=source("client/ClientArcadeEvents");String approval=event.substring(event.indexOf("private static void requestHomeApproval"),event.indexOf("public static void openLibrary"));
        assertTrue(approval.contains("if(mc.screen!=own[0])return"));assertTrue(approval.contains("mc.setScreen(null);if(stale)return"));assertTrue(approval.contains("System.nanoTime()>deadline"));assertTrue(approval.contains("SESSIONS.get(payload.sessionId())!=session"));
    }
    @Test void inventoryReceiptCannotMintLeaseAndProtectionReentryIsClosed()throws Exception{String s=source("home/HomeZapperService"),d=source("home/ZapperData");assertTrue(s.contains("CHECKING.get()||context.getHand()"));assertTrue(s.contains("!g.binding.equals(b)||g.connection!=p.connection.getConnection()"));assertTrue(s.contains("HomeControllerInventory.Status.UNIQUE"));assertTrue(s.contains("getMainHandItem()==found.value()"));assertTrue(d.contains("instanceof HomeZapperItem"));}
}
