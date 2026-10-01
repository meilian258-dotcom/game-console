package cn.piq.fcarcade.home;

import cn.piq.fcarcade.home.content.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ContentCardWorkbenchTest {
    private List<ContentCardStore.Entry> games(int count){return java.util.stream.IntStream.range(0,count).mapToObj(i->new ContentCardStore.Entry(String.format("%064x",i),"游戏"+i+".MD",512)).toList();}
    @Test void eightAndNinthGamesPaginateWithoutAnEmptyExtraPage(){
        var eight=ContentCardWorkbench.page(games(8),"",1);assertEquals(0,eight.index());assertEquals(8,eight.entries().size());
        var nine=ContentCardWorkbench.page(games(9),"",1);assertEquals(1,nine.index());assertEquals(9,nine.total());assertEquals("游戏8.MD",nine.entries().getFirst().name());
    }
    @Test void searchAllCatalogBeforePagingAndClampStalePage(){
        var p=ContentCardWorkbench.page(games(40),"游戏32.md",4);assertEquals(1,p.total());assertEquals(0,p.index());assertEquals(games(40).get(32),p.entries().getFirst());
        assertEquals(0,ContentCardWorkbench.page(games(40),"missing",5).total());
    }
    @Test void malformedNamesQueriesAndUploadEncodingFailBeforeMutation(){
        for(var bad:List.of(""," ","bad\nname","长".repeat(129)))assertThrows(IllegalArgumentException.class,()->ContentCardWorkbench.title(bad));
        assertThrows(IllegalArgumentException.class,()->ContentCardWorkbench.page(games(1),"a".repeat(65),0));
        assertThrows(IllegalArgumentException.class,()->ContentCardWorkbench.page(games(1),"",32));
        assertThrows(IllegalArgumentException.class,()->ContentCardWorkbench.uploadTitle(new byte[]{(byte)0xff},"old"));
        assertEquals("中文游戏",ContentCardWorkbench.uploadTitle(" 中文游戏 ".getBytes(StandardCharsets.UTF_8),"old"));
        assertEquals("old",ContentCardWorkbench.uploadTitle(new byte[0],"old"));
    }
    @Test void deviceDiagnosticsDoNotEnableNetworkLanes(){
        HomeSystems.ServerHooks unadapted=new Hooks();assertFalse(unadapted.deviceSettingsAvailable());
        HomeSystems.ServerHooks legacy=new Hooks(){public boolean synchronizationSettingsAvailable(){return true;}};
        assertTrue(legacy.deviceSettingsAvailable());
        HomeSystems.ServerHooks privateOnly=new Hooks(){public boolean deviceSettingsAvailable(){return true;}};
        assertTrue(privateOnly.deviceSettingsAvailable());assertFalse(privateOnly.synchronizationSettingsAvailable());
    }
    private static class Hooks implements HomeSystems.ServerHooks {
        public void onInteract(net.minecraft.server.level.ServerPlayer p,net.minecraft.world.InteractionHand hand,HomeSystems.Connection c,net.minecraft.world.phys.BlockHitResult hit){}
    }
}
