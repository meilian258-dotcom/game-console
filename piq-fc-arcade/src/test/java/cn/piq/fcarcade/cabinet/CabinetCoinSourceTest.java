package cn.piq.fcarcade.cabinet;

import java.nio.file.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Wiring guardrails, explicitly not Minecraft permission/latency acceptance tests. */
class CabinetCoinSourceTest {
    private static String source(String path)throws Exception{return Files.readString(Path.of("src/main/java/cn/piq/fcarcade/"+path+".java"));}
    @Test void oldNbtIsFreeButPlacementDefaultsPaid()throws Exception{
        var be=source("world/LegacyFcArcadeBlockEntity");assertTrue(be.contains("private boolean coinRequired = true"));
        assertTrue(be.contains("coinRequired = tag.getBoolean(\"CoinRequired\")"));assertTrue(be.contains("tag.putBoolean(\"CoinRequired\", coinRequired)"));
    }
    @Test void physicalUseRechecksHeldIdentityRayRoomAndTopologyBeforeOneDebit()throws Exception{
        var item=source("cabinet/ArcadeCoinItem");assertTrue(item.contains("onItemUseFirst"));assertTrue(item.contains("InteractionResult.CONSUME"));
        var service=source("cabinet/CabinetCoinService");int auth=service.indexOf("validatedTarget"),after=service.indexOf("var after=target"),debit=service.indexOf("held.shrink(1)");
        assertTrue(auth>0&&after>auth&&debit>after);assertEquals(debit,service.lastIndexOf("held.shrink(1)"));
        for(String check:new String[]{"player.getItemInHand(hand)!=held","ItemStack.matches(held,original)","fresh.member()!=admission.member()","fresh.room()!=admission.room()","fresh.secondary(),admission.secondary()","player.connection.getConnection()!=connection","level.getBlockEntity(hit.target().anchor())!=hit.anchor()"})assertTrue(service.contains(check),check);
        assertTrue(service.indexOf("getCooldowns().addCooldown")<service.indexOf("var hit=target"));
        assertTrue(service.indexOf("if(!CabinetRooms.insertCoin")<debit);
    }
    @Test void debugOnlyChangesCoinsAndDoesNotChangeFcOrSfcInput()throws Exception{
        var settings=source("cabinet/CabinetSyncSettings");assertTrue(settings.contains("coinSupported&&debug!=null&&editable"));
        assertFalse(settings.contains("isCreative()"));assertTrue(settings.contains("!p.hasPermissions(2)"));assertTrue(settings.contains("setCoinRequired(packet.coinMode()==1)"));
        var client=source("client/cabinet/CabinetClientBackends");assertTrue(client.contains("filter(room.coinRequired(),p1)"));assertTrue(client.contains("message.sequence()<=coinSequence"));assertTrue(client.contains("!peers.owns(message.member(),message.port())"));
        var sync=source("cabinet/CabinetSynchronizer");assertTrue(sync.contains("s.timeline.input(m.port,change.mask())"));
    }
    @Test void ordinaryReleaseUsesPreservingCapabilityButSeatDestructionStillHardReleases()throws Exception{
        var sync=source("cabinet/CabinetSynchronizer");
        assertTrue(sync.contains("if(p.reset())s.timeline.releaseGameplay(m.port)"));
        assertTrue(sync.substring(sync.indexOf("static void removed")).contains("s.timeline.release(port)"));
        var worker=source("cabinet/HostedCabinetWorker");
        assertTrue(worker.contains("core.supportsCoinPreservingRelease()&&inputs.coin(port)"));
        assertTrue(worker.contains("inputs.releasePreservingCoins(port);active.releaseGameplayPortKeepingCoin(port)"));
        assertTrue(worker.contains("void releasePort(int port)"));
        var rooms=source("cabinet/CabinetRooms");
        assertTrue(rooms.contains("coinSupportProblem(player,admission)!=null"));
        assertTrue(rooms.contains("room.coinReleaseSupported=room.mode==CabinetSyncMode.LOCAL_SYNC||packet.coinReleaseSupported()"));
        assertTrue(rooms.contains("if(change.reset())CabinetHostedSessions.releaseGameplay"));
        assertTrue(rooms.contains("state.ledger.reset(member);CabinetHostedSessions.release(server,room,member.port)"));
        var client=source("client/cabinet/CabinetClientBackends");
        assertTrue(client.contains("if(message.reset())releaseGameplayPort(message.port())"));
        assertTrue(client.contains("releaseGameplayPortKeepingCoin(port)"));
        assertTrue(client.contains("!emulator.supportsCoinPreservingRelease()||peers==null"));
        assertTrue(client.contains("emulator!=null&&emulator.supportsCoinPreservingRelease()"));
        var service=source("cabinet/CabinetCoinService");
        assertTrue(service.indexOf("coinSupportProblem(player,fresh)")<service.indexOf("held.shrink(1)"));
    }
}
