package cn.piq.fcarcade.server;

import cn.piq.fcarcade.ArcadeSessionPayload;
import cn.piq.fcarcade.home.HomeRuntimeAuthority;
import cn.piq.fcarcade.session.*;
import io.netty.buffer.Unpooled;
import java.nio.file.*;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FcPlayerMedia53Test {
    private static final UUID SOURCE=new UUID(13,14),TOKEN=new UUID(15,16),LEASE=new UUID(17,18);
    private static ArcadeSessionPayload state(boolean host,boolean media){return new ArcadeSessionPayload(BlockPos.ZERO,9,ArcadeMode.LOCKSTEP,ArcadeRole.PLAYER_TWO,2,"guest",16,16,100,"a".repeat(64),3,false,true,NesCoreVariant.LEGACY,true,host,LEASE,20,media);}
    private static String server()throws Exception{return Files.readString(Path.of("src/main/java/cn/piq/fcarcade/server/ServerArcadeSessions.java"));}
    private static String section(String s,String start,String end){int a=s.indexOf(start),b=s.indexOf(end,a);assertTrue(a>=0&&b>a);return s.substring(a,b);}
    @Test void playerReceiverRoundTripsWithIndependentAuthorityAndPhysicalLease(){
        var payload=new FcHomeHostedNetwork.State(state(false,true),SOURCE,TOKEN,false,true);
        var buffer=new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);
        try{FcHomeHostedNetwork.State.CODEC.encode(buffer,payload);var decoded=FcHomeHostedNetwork.State.CODEC.decode(buffer);assertEquals(payload,decoded);assertTrue(decoded.playerMediaReceiver());assertTrue(decoded.session().playerMedia());assertFalse(decoded.session().computeHost());assertEquals(LEASE,decoded.session().controllerLease());assertEquals(0,buffer.readableBytes());}finally{buffer.release();}
    }
    @Test void legacyConstructorsDoNotManufacturePlayerMediaAuthority(){
        var p=new ArcadeSessionPayload(BlockPos.ZERO,9,ArcadeMode.LOCKSTEP,ArcadeRole.PLAYER_ONE,1,"",16,16,100,"a".repeat(64),3,false,true,NesCoreVariant.LEGACY,true,false,LEASE,20);
        assertFalse(p.playerMedia());assertFalse(new FcHomeHostedNetwork.State(p,SOURCE,TOKEN,true).playerMediaReceiver());
    }
    @Test void mediaLaneCannotHideAComputingClientOrSwapWithServerHosted(){
        assertThrows(IllegalArgumentException.class,()->new FcHomeHostedNetwork.State(state(true,true),SOURCE,TOKEN,false,true));
        assertThrows(IllegalArgumentException.class,()->new FcHomeHostedNetwork.State(state(false,true),SOURCE,TOKEN,false,false));
        assertThrows(IllegalArgumentException.class,()->new FcHomeHostedNetwork.State(state(false,false),SOURCE,TOKEN,false,true));
        assertThrows(IllegalArgumentException.class,()->new FcHomeHostedNetwork.State(state(false,true),SOURCE,TOKEN,true,true));
    }
    @Test void mediaCannotBeDeclaredByLegacyCabinet(){assertThrows(IllegalArgumentException.class,()->new ArcadeSessionPayload(BlockPos.ZERO,9,ArcadeMode.LOCKSTEP,ArcadeRole.SPECTATOR,0,"",16,16,100,"a".repeat(64),3,false,true,NesCoreVariant.LEGACY,false,false,null,0,true));}
    @Test void remoteDepartureReleasesOnlySocketAndCannotTakeComputeAuthority(){
        UUID host=UUID.randomUUID(),guest=UUID.randomUUID();Object h=new Object(),g=new Object();var a=new HomeRuntimeAuthority<Object>(host,h);
        assertTrue(a.take(guest,g,LEASE,1));assertTrue(a.authorized(guest,g,LEASE,1));assertFalse(a.host(guest,g));
        assertNotNull(a.release(guest,g,LEASE,1));assertTrue(a.running());assertTrue(a.host(host,h));assertFalse(a.authorized(guest,g,LEASE,1));
        a.close();assertFalse(a.host(host,h));assertFalse(a.take(guest,g,UUID.randomUUID(),1));
    }
    @Test void framesAndSnapshotRecoveryNeverDriveRemoteMediaClients()throws Exception{
        String s=server();assertTrue(section(s,"private static void broadcastFrame(","private static String safeMessage").contains("!session.playerMedia||computeHost(session,player)"));
        assertTrue(section(s,"private boolean grantHome(","private void decideHome(").contains("s.hosted==null&&!s.playerMedia&&!alreadyParticipant&&!computeHost(s,p)"));
        assertTrue(section(s,"private boolean allowResync(","private void resyncParticipant(").contains("if(session.playerMedia&&!computeHost(session,player))return false;"));
        assertTrue(section(s,"private void handleDigest(","private static void restart(").contains("session.playerMedia"));
    }
    @Test void streamAuthorityIsBoundToHardwareConnectionAndHeldController()throws Exception{
        String s=server(),source=section(s,"private static Session playerMediaSession(","/** Source authority"),recipients=section(s,"private static List<ServerPlayer> playerMediaRecipients(","/** Removes only text");
        assertTrue(source.contains("s.playerMedia&&s.homeReady&&playerWatchSource(s).equals(source)&&m.validHome(server,s)"));
        assertTrue(recipients.contains("Manager.computeHost(s,p)"));assertTrue(recipients.contains("c.connection()!=p.connection.getConnection()"));assertTrue(recipients.contains("HomeControllerService.mediaAuthorized(p,s.key.anchor(),s.id,c.port(),c.lease())"));assertTrue(recipients.contains("HomeZapperService.authorized(p,s.zapperBinding,false)"));
        assertTrue(s.contains("FcHomeHostedNetwork.send(p,s.id,s.lockstep.epoch(),batch)"));
    }
    @Test void restartDisconnectAndSavesKeepSourceAndPersistentStateIsolated()throws Exception{
        String s=server();assertTrue(section(s,"private static void restart(","private Session sessionFor(").contains("session.mediaToken=UUID.randomUUID();"));
        assertTrue(s.contains("WatchService.closed(server,session.mediaSource,session.mediaToken)"));
        assertTrue(section(s,"private void disconnect(","private void tick(").contains("if(powered.hosted==null)close(player.getServer(),powered)"));
        assertTrue(s.contains("return coreVariant(server,rom,gun).saveKey(key);"));
        assertTrue(s.contains("HomePersonalSaveMigration.copy("));
        assertTrue(s.contains("!hostedSelected(c)&&!s.playerMedia&&saved==null"));
        assertTrue(section(s,"private void reconcileViewers(","private void removeViewer(").contains("candidate.playerMedia"));
    }
    @Test void serverPolicyAndOpenTransactionBothRespectPlayerHostingDisable()throws Exception{
        String s=server();assertTrue(section(s,"public static boolean powerHomeConsole(","public static void homeSaveAction(").contains("CabinetSyncMode.MEDIA&&!CabinetHostingConfig.playerAllowed()"));
        assertTrue(section(s,"private boolean createHome(","Session s=new Session").contains("CabinetSyncMode.MEDIA&&!CabinetHostingConfig.playerAllowed()"));
        String settings=Files.readString(Path.of("src/main/java/cn/piq/fcarcade/home/HomeSyncSettings.java"));assertTrue(settings.contains("CabinetHostingConfig.playerAllowed())policyMask&=~1"));
    }
    @Test void personalStorageKeepsMediaButNotInputAndNeverExtendsLeaseAuthority()throws Exception{
        String s=Files.readString(Path.of("src/main/java/cn/piq/fcarcade/home/HomeControllerService.java"));
        String media=section(s,"public static boolean mediaAuthorized(","public static UUID leaseId(");
        assertFalse(media.contains("holds("));assertTrue(media.contains("expectedLease.equals(lease.id())"));
        assertTrue(media.contains("usable(player,console)"));assertTrue(media.contains("state.ledger.authorized(lease.id(),player.getUUID(),identity(console),session,port)"));
        assertTrue(media.contains("!hasExternalLoan(player,player.containerMenu,lease.id())"));
        assertTrue(media.contains("locate(player,lease.id()).status()==HomeControllerInventory.Status.UNIQUE"));
        assertTrue(section(s,"public static boolean authorized(","public static boolean mediaAuthorized(").contains("holds(player, ownedItem(state, lease, player))"));
    }
    @Test void stalledHostUsesBoundedExistingWatchdogAndTellsParticipantsSnapshotLimit()throws Exception{
        String s=server();String guard=section(s,"if (session.hosted==null&&!session.timeline.canRecordFrames(","for (int frame = 0;");
        assertTrue(guard.contains("if(session.playerMedia)for(UUID id:session.allPlayers())"));
        assertTrue(guard.contains("if(current(participant))participant.sendSystemMessage"));
        assertTrue(guard.contains("仅保留最近确认的快照，未确认的操作可能丢失"));assertTrue(guard.contains("close(server, session);"));
        assertEquals(3600,LockstepTimeline.MAX_RETAINED_FRAMES);
    }
}
