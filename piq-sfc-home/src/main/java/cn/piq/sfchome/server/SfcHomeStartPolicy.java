// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.sfchome.server;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Player-count metadata permits joining, never forces P1 to wait. */
final class SfcHomeStartPolicy {
    enum Plan { SINGLE, WAIT_FOR_SECOND, DUAL }
    static Plan plan(int players,boolean explicit,boolean approvedSecond){
        if(players!=1&&players!=2)throw new IllegalArgumentException("SFC players must be 1 or 2");
        if(explicit&&players==1)return Plan.SINGLE;
        if(approvedSecond)return Plan.DUAL;
        return Plan.SINGLE;
    }
    /** A useOn/use pair and an old empty-hand packet may arrive in the same server tick. */
    static final class InteractionGate {
        private final Map<UUID,Long> last=new HashMap<>();
        boolean allow(UUID player,long tick){
            Long previous=last.get(player);
            if(previous!=null&&tick<=previous)return false;
            last.put(player,tick);return true;
        }
        void expireBefore(long tick){last.values().removeIf(value->value<tick);}
    }
    private SfcHomeStartPolicy(){}
}
