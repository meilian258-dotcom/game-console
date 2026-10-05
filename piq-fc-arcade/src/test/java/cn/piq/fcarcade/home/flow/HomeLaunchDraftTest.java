// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.fcarcade.home.flow;

import cn.piq.retro.flow.DeviceSessionFlow.Stage;
import java.util.*;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import static cn.piq.fcarcade.home.flow.HomeLaunchNetwork.*;
import static org.junit.jupiter.api.Assertions.*;

class HomeLaunchDraftTest {
    private final UUID token=UUID.randomUUID();
    private final Row first=new Row(1,"v1","原名","game",1,1,true);
    private final Row second=new Row(2,"","Save 2","",1,0,true);
    private View view(UUID id,long revision,Stage stage,List<Row> rows){return new View(id,revision,ResourceLocation.parse("test:home"),"主机","游戏",2,2,stage,rows,"");}
    @Test void backFromJoinAndValidationFailurePreserveUnsentFields(){
        var draft=new HomeLaunchDraft();var rows=List.of(first,second);
        draft.receive(view(token,1,Stage.SAVE_SELECTION,rows));draft.name("新的中文名称");draft.togglePlayers(2);
        var choice=draft.choice(true);
        draft.receive(view(token,2,Stage.JOIN_CONFIRM,rows));draft.receive(view(token,3,Stage.SAVE_SELECTION,rows));
        assertEquals(choice,draft.choice(true));
        // Validation failure sends the same authoritative rows, never the unsent name.
        draft.receive(view(token,4,Stage.SAVE_SELECTION,rows));assertEquals(choice,draft.choice(true));
    }
    @Test void slotsKeepSeparateDraftsAndFollowSlotIdentityAcrossReorder(){
        var draft=new HomeLaunchDraft();draft.receive(view(token,1,Stage.SAVE_SELECTION,List.of(first,second)));
        draft.name("槽一");draft.select(1);draft.name("槽二");draft.togglePlayers(2);draft.select(0);assertEquals("槽一",draft.name());
        draft.receive(view(token,2,Stage.SAVE_SELECTION,List.of(second,first)));assertEquals(1,draft.selected());assertEquals("槽一",draft.name());
        draft.select(0);assertEquals("槽二",draft.name());assertEquals(2,draft.players());
    }
    @Test void changedServerSnapshotResetsOnlyAffectedSlot(){
        var draft=new HomeLaunchDraft();draft.receive(view(token,1,Stage.SAVE_SELECTION,List.of(first,second)));draft.name("旧草稿");
        draft.select(1);draft.name("保留");draft.select(0);
        var changed=new Row(1,"v2","新进度","other-game",2,9,true);
        draft.receive(view(token,2,Stage.SAVE_SELECTION,List.of(changed,second)));
        assertEquals(new Choice(1,"v2","新进度",2,true),draft.choice(true));draft.select(1);assertEquals("保留",draft.name());
    }
    @Test void readOnlyRowsCannotRetainOrCreateEditableDraft(){
        var draft=new HomeLaunchDraft();draft.receive(view(token,1,Stage.SAVE_SELECTION,List.of(first)));draft.name("曾可编辑");draft.togglePlayers(2);
        var locked=new Row(1,"v1","原名","game",1,1,true,false);draft.receive(view(token,2,Stage.SAVE_SELECTION,List.of(locked)));
        draft.name("不允许");draft.togglePlayers(2);assertTrue(locked.accepts(draft.choice(true)));assertEquals("原名",draft.name());assertEquals(1,draft.players());
    }
    @Test void newRequestAndRemovedSlotsCannotReuseOldDraft(){
        var draft=new HomeLaunchDraft();draft.receive(view(token,1,Stage.SAVE_SELECTION,List.of(first,second)));draft.select(1);draft.name("旧请求");
        draft.receive(view(UUID.randomUUID(),1,Stage.SAVE_SELECTION,List.of(first,second)));assertEquals(0,draft.selected());draft.select(1);assertEquals("Save 2",draft.name());
        draft.receive(view(token,2,Stage.SAVE_SELECTION,List.of(first)));assertEquals(0,draft.selected());
        draft.receive(view(token,3,Stage.PREPARING,List.of()));assertFalse(draft.available());assertEquals(-1,draft.selected());
    }
    @Test void singlePlayerAndUneditedNamesKeepAuthoritativeValues(){
        var draft=new HomeLaunchDraft();draft.receive(view(token,1,Stage.SAVE_SELECTION,List.of(first)));draft.togglePlayers(1);assertEquals(1,draft.players());
        draft.name("");assertFalse(first.accepts(draft.choice(true))); // failed validation must not erase the draft
        draft.receive(view(token,2,Stage.SAVE_SELECTION,List.of(first)));assertEquals("",draft.name());
    }
}
