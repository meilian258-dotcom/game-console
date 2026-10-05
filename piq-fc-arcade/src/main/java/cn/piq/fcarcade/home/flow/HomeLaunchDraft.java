// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.fcarcade.home.flow;

import java.util.*;
import static cn.piq.fcarcade.home.flow.HomeLaunchNetwork.*;

/** Per-window, per-slot unsent form values. Never grants permission or persists save data. */
final class HomeLaunchDraft {
    private record Entry(Row source,String name,int players) {}
    private final Map<Integer,Entry> entries=new HashMap<>();
    private List<Row> rows=List.of();
    private UUID token;
    private int selectedSlot;

    void receive(View view) {
        if(!view.token().equals(token)){entries.clear();selectedSlot=0;token=view.token();}
        rows=view.rows();
        entries.keySet().removeIf(slot->rows.stream().noneMatch(row->row.slot()==slot));
        for(var row:rows){
            var old=entries.get(row.slot());
            // A new server snapshot (including editability/ports) invalidates only this slot's draft.
            if(old==null||!row.equals(old.source())||!row.metadataEditable())
                entries.put(row.slot(),new Entry(row,row.name(),row.players()));
        }
        if(rows.stream().noneMatch(row->row.slot()==selectedSlot))selectedSlot=rows.isEmpty()?0:rows.getFirst().slot();
    }
    boolean available(){return !rows.isEmpty();}
    int selected(){for(int i=0;i<rows.size();i++)if(rows.get(i).slot()==selectedSlot)return i;return -1;}
    void select(int index){selectedSlot=rows.get(index).slot();}
    private Entry current(){var entry=entries.get(selectedSlot);if(entry==null)throw new IllegalStateException("No save slot");return entry;}
    String name(){return current().name();}
    int players(){return current().players();}
    void name(String value){var old=current();text(value,32);if(old.source().metadataEditable())entries.put(selectedSlot,new Entry(old.source(),value,old.players()));}
    void togglePlayers(int maxPlayers){var old=current();if(old.source().metadataEditable()&&maxPlayers>1)entries.put(selectedSlot,new Entry(old.source(),old.name(),old.players()==1?2:1));}
    Choice choice(boolean resume){var entry=current();return new Choice(selectedSlot,entry.source().version(),entry.name(),entry.players(),resume);}
}
