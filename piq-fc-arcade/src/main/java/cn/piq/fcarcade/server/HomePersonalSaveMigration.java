package cn.piq.fcarcade.server;

import cn.piq.fcarcade.session.NesCoreVariant;
import java.util.*;
import java.util.function.Predicate;

/** Copy an unambiguous old transport-specific slot. Originals and occupied destinations are never changed. */
final class HomePersonalSaveMigration {
    private HomePersonalSaveMigration(){}
    static void copy(ArcadeSaveStore store,UUID player,NesCoreVariant core,Predicate<String> active){
        for(int slot=1;slot<=3;slot++){
            String target=core.saveKey(PlayerSaveSlots.key(player,slot));
            if(active.test(target)||store.list().stream().anyMatch(i->target.equals(i.saveKey())))continue;
            var found=store.list().stream().filter(i->i.saveKey().equals("player-home-v1|"+target)||i.saveKey().equals("server-home-v1|"+target)).toList();
            if(found.isEmpty()||found.stream().anyMatch(i->active.test(i.saveKey())))continue;
            var first=found.getFirst();byte[] state=store.loadReadOnly(first.saveKey(),first.romSha256());
            if(!core.acceptsPersistentStateHeader(state,first.romSha256()))continue;
            if(found.stream().anyMatch(i->!i.romSha256().equals(first.romSha256())||i.players()!=first.players()||!Arrays.equals(state,store.loadReadOnly(i.saveKey(),i.romSha256()))))continue;
            if(active.test(target)||found.stream().anyMatch(i->active.test(i.saveKey()))||store.list().stream().anyMatch(i->target.equals(i.saveKey())))continue;
            store.save(target,first.romSha256(),state,first.slotName(),first.players());
        }
    }
}
