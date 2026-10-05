// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.fcarcade.home.flow;
import cn.piq.retro.flow.DeviceSessionFlow.Stage;
import io.netty.buffer.Unpooled;
import java.util.*;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import static cn.piq.fcarcade.home.flow.HomeLaunchNetwork.*;
import static org.junit.jupiter.api.Assertions.*;

class HomeLaunchNetworkTest {
    @Test void readOnlyRowsRejectForgedMetadataAndStaleOrMissingProgress(){
        var row=new Row(1,"v","原有进度","hash",1,0,true,false);
        assertTrue(row.accepts(new Choice(1,"v","原有进度",1,true)));
        assertTrue(row.accepts(new Choice(1,"v","原有进度",1,false)));
        assertFalse(row.accepts(new Choice(1,"v","改名",1,true)));
        assertFalse(row.accepts(new Choice(1,"v","原有进度",2,true)));
        assertFalse(row.accepts(new Choice(1,"stale","原有进度",1,true)));
        assertFalse(new Row(1,"","原有进度","hash",1,0,true,false).accepts(new Choice(1,"","原有进度",1,true)));
    }
    @Test void legacyReadOnlyMetadataSurvivesWire(){var row=new Row(1,"v","原有进度","hash",1,0,true,false);var view=new View(UUID.randomUUID(),1,ResourceLocation.parse("test:sfc"),"SFC","游戏",2,2,Stage.SAVE_SELECTION,List.of(row),"");var b=buffer();try{View.CODEC.encode(b,view);var decoded=View.CODEC.decode(b);assertEquals(view,decoded);assertFalse(decoded.rows().getFirst().metadataEditable());}finally{b.release();}}
    private static RegistryFriendlyByteBuf buffer(){return new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);}
    @Test void savePageRoundTripsReadableTitleAndIndependentMetadata(){var page=new View(UUID.randomUUID(),6,ResourceLocation.parse("example:console"),"主机","自定义中文标题",2,2,Stage.SAVE_SELECTION,List.of(new Row(1,"v1","双人存档","ROM identity",2,42,true)),"选择后确认本局人数");var b=buffer();try{View.CODEC.encode(b,page);assertEquals(page,View.CODEC.decode(b));assertEquals(0,b.readableBytes());}finally{b.release();}}
    @Test void allLifecycleStagesIncludingCancelledAndClosedRoundTrip(){for(var stage:Stage.values()){var page=new View(UUID.randomUUID(),9,ResourceLocation.parse("example:console"),"机器","名字",0,2,stage,List.of(),"状态");var b=buffer();try{View.CODEC.encode(b,page);assertEquals(page,View.CODEC.decode(b));}finally{b.release();}}}
    @Test void boundedActionsCannotSmuggleSaveChoiceIntoConsentOrCancel(){var choice=new Choice(1,"old-version","双人标签",2,true);assertThrows(IllegalArgumentException.class,()->new Action(UUID.randomUUID(),1,ALLOW,choice));assertThrows(IllegalArgumentException.class,()->new Action(UUID.randomUUID(),1,SELECT,null));for(int op=SELECT;op<=BACK;op++){var a=new Action(UUID.randomUUID(),Long.MAX_VALUE,op,op==SELECT?choice:null);var b=buffer();try{Action.CODEC.encode(b,a);assertEquals(a,Action.CODEC.decode(b));assertEquals(0,b.readableBytes());}finally{b.release();}}}
    @Test void malformedCountsAndStagesRejectBeforeAllocation(){for(int[] values:new int[][]{{255,0},{0,-1},{0,17}}){var b=buffer();try{b.writeUUID(UUID.randomUUID());b.writeVarLong(1);b.writeUtf("example:console");b.writeUtf("机器");b.writeUtf("标题");b.writeByte(0);b.writeByte(2);b.writeByte(values[0]);b.writeVarInt(values[1]);assertThrows(IllegalArgumentException.class,()->View.CODEC.decode(b));}finally{b.release();}}}
    @Test void semanticValidationRejectsAmbiguousRowsAndUnboundedText(){var row=new Row(1,"","Save 1","",1,0,true);assertThrows(IllegalArgumentException.class,()->new View(UUID.randomUUID(),1,ResourceLocation.parse("example:console"),"x","title",0,2,Stage.JOIN_CONFIRM,List.of(row),""));assertThrows(IllegalArgumentException.class,()->new View(UUID.randomUUID(),1,ResourceLocation.parse("example:console"),"x","title",2,2,Stage.SAVE_SELECTION,List.of(row,row),""));assertThrows(IllegalArgumentException.class,()->new Choice(1,"","x".repeat(33),1,false));assertThrows(IllegalArgumentException.class,()->new Choice(1,"","bad\nname",1,false));assertThrows(IllegalArgumentException.class,()->new Row(1,"v","name","",2,-1,true));}
}
