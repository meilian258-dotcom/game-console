// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.computer;

import java.util.UUID;

/** Server-only ephemeral seat; never serialized to world data. */
public final class InputLease {
    public static final long TIMEOUT=60;
    private UUID owner,token;
    private long expires,lastSequence=-1,rateTick=-1;
    private int count;
    public UUID owner() {return owner;}
    public boolean active(long now) {return owner!=null&&now<expires;}
    public UUID acquire(UUID player,long now) {
        if(active(now)&&!player.equals(owner))return null;
        owner=player;token=UUID.randomUUID();expires=now+TIMEOUT;lastSequence=-1;rateTick=-1;count=0;return token;
    }
    public boolean accept(UUID player,UUID ticket,long sequence,long now) {
        if(!active(now)||!player.equals(owner)||!ticket.equals(token)||sequence<=lastSequence||sequence<0)return false;
        if(rateTick!=now){rateTick=now;count=0;}
        if(++count>32)return false;
        lastSequence=sequence;expires=now+TIMEOUT;return true;
    }
    public boolean release(UUID player,UUID ticket) {
        if(!player.equals(owner)||!ticket.equals(token))return false;
        clear();return true;
    }
    public void clear(){owner=token=null;expires=0;lastSequence=-1;}
}
