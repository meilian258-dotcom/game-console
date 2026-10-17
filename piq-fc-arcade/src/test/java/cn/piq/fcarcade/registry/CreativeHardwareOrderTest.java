package cn.piq.fcarcade.registry;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CreativeHardwareOrderTest {
    @Test void addonsAreGroupedWithMainHardwareAndRetiredBoxOnlyIsHidden(){
        var input=List.of("piq_pvz:player_box","piq_computer:keyboard_mouse_black","piq_fc_arcade:furniture/oak_bench",
            "piq_fc_arcade:dual_cabinet","piq_sfc_arcade:sfc_arcade","piq_computer:computer_black","piq_fc_arcade:famicom_console",
            "piq_computer:cpu","piq_gba:handheld","piq_fc_arcade:portrait_cabinet","piq_fc_arcade:retro_tv","piq_pvz:future_item");
        assertEquals(List.of("piq_fc_arcade:famicom_console","piq_sfc_arcade:sfc_arcade","piq_gba:handheld",
            "piq_fc_arcade:portrait_cabinet","piq_fc_arcade:dual_cabinet","piq_fc_arcade:retro_tv",
            "piq_computer:computer_black","piq_computer:cpu","piq_computer:keyboard_mouse_black",
            "piq_fc_arcade:furniture/oak_bench","piq_pvz:future_item"),CreativeHardwareOrder.sorted(input,s->s));
        assertEquals(12,input.size());
    }
    @Test void stacksAndUnknownOrderArePreservedWithoutDuplicateCreation(){
        record Stack(String id,int customData){}
        var a=new Stack("unknown:a",1);var b=new Stack("unknown:b",2);var c=new Stack("piq_fc_arcade:fc_cartridge",7);var d=new Stack(c.id(),8);
        var sorted=CreativeHardwareOrder.sorted(List.of(a,c,b,d),Stack::id);
        assertEquals(List.of(c,d,a,b),sorted);assertSame(c,sorted.getFirst());assertEquals(4,sorted.size());
    }
    @Test void allBenchesBeforeStoolsWithStableWoodOrder(){
        var items=List.of("piq_fc_arcade:furniture/oak_bench","piq_fc_arcade:furniture/oak_stool","piq_fc_arcade:furniture/birch_bench","piq_fc_arcade:furniture/birch_stool");
        assertEquals(List.of(items.get(0),items.get(2),items.get(1),items.get(3)),CreativeHardwareOrder.sorted(items,s->s));
    }
}
