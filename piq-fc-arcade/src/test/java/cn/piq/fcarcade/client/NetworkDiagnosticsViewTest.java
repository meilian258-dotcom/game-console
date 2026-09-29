package cn.piq.fcarcade.client;

import cn.piq.fcarcade.network.ModTrafficCounter;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static cn.piq.fcarcade.client.NetworkDiagnosticsView.*;

class NetworkDiagnosticsViewTest {
    @Test void controllerWinsOverCloserBackgroundAndSpectatorSessions() {
        var playing=new Device("SFC",Mode.LOCAL_INPUT,Role.CONTROLLING,16);
        var near=new Device("watch",Mode.WATCH_MEDIA,Role.WATCHING,1);
        var host=new Device("FC",Mode.SERVER_MEDIA,Role.COMPUTING,0);
        assertSame(playing,choose(List.of(near,host,playing)));assertSame(playing,choose(List.of(playing,host,near)));
        assertSame(near,choose(List.of(host,near)));
    }
    @Test void selectionIsStableForEqualDistancesAndIdleDoesNotInventMode() {
        var a=new Device("A",Mode.LOCAL_INPUT,Role.WATCHING,1);var b=new Device("B",Mode.LEGACY_RELAY,Role.WATCHING,1);
        assertSame(a,choose(List.of(b,a)));assertNull(choose(List.of()));
        var lines=lines(new ModTrafficCounter.Sample(0,0,List.of()),false,List.of());
        assertEquals("当前模式：无活动设备",lines.getFirst());
    }
    @Test void lifetimeUnitsAndCombinedBytesAreExplicit() {
        assertEquals("0 B",bytes(0));assertEquals("1023 B",bytes(1023));assertEquals("1.00 KiB",bytes(1024));
        assertEquals("1.00 MiB",bytes(1L<<20));assertEquals("1.00 GiB",bytes(1L<<30));
        assertEquals("8.00 EiB",bytes(Long.MAX_VALUE));assertThrows(IllegalArgumentException.class,()->bytes(-1));
        var text=lines(new ModTrafficCounter.Sample(1024,2048,List.of(),1024,2048),false,List.of());
        assertTrue(text.get(2).contains("1.0 KiB/s"));assertTrue(text.get(3).contains("↑ 1.00 KiB  ↓ 2.00 KiB"));
        assertTrue(text.get(3).contains("合计 3.00 KiB"));
    }
    @Test void memoryConnectionStillShowsRealModeAndZeroNetworkBytes() {
        var device=new Device("FC",Mode.LOCAL_INPUT,Role.CONTROLLING,0);
        var text=lines(new ModTrafficCounter.Sample(0,0,List.of()),true,List.of(device));
        assertEquals("当前模式：本地输入同步",text.getFirst());assertTrue(text.get(2).contains("同机内部"));
        assertTrue(text.get(3).contains("合计 0 B"));assertTrue(text.getLast().contains("不计入流量"));
    }
    @Test void allModesAreTruthfulAndPanelLinesRemainBoundedWithManyStreams() {
        assertTrue(Mode.WATCH_MEDIA.label().contains("未下发"));assertNotEquals(Mode.PLAYER_MEDIA,Mode.LEGACY_RELAY);
        for(var mode:Mode.values()){
            var streams=new ArrayList<ModTrafficCounter.StreamRate>();for(int i=0;i<16;i++)streams.add(new ModTrafficCounter.StreamRate(UUID.randomUUID(),20));
            var text=lines(new ModTrafficCounter.Sample(0,0,streams),false,List.of(new Device("设备",mode,Role.CONTROLLING,0)));
            assertEquals("当前模式："+mode.label(),text.getFirst());assertEquals(8,text.size());assertTrue(text.get(5).contains("320.0 FPS / 16 路"));
        }
    }
    @Test void categoriesAndNativeModeDoNotClaimNetplayIsFree(){
        var count=new ModTrafficCounter(()->0);count.upload(cn.piq.fcarcade.network.TrafficCategory.NETPLAY,2048);
        var text=lines(count.sample(),false,List.of(new Device("街机",Mode.NETPLAY,Role.CONTROLLING,0)));
        assertEquals("当前模式：RetroArch Netplay",text.getFirst());
        assertTrue(text.get(4).contains("Netplay"));assertTrue(text.get(4).contains("累计 2.00 KiB"));
        assertTrue(text.get(6).startsWith("游戏/资源"));assertTrue(text.get(7).startsWith("其他/旧同步"));
    }
}
