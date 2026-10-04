// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.mdhome.save;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** Owner-wide gate shared by save-channel registration and queued catalog mutations. */
public final class MdSaveTransactions {
    private final Map<String,Reservation> held=new HashMap<>();
    public synchronized boolean busy(String owner){return held.containsKey(owner);}
    public synchronized Reservation reserve(String owner,BooleanSupplier otherWriterIdle){
        if(held.containsKey(owner)||!otherWriterIdle.getAsBoolean())throw new IllegalStateException("此归属的存档正在游戏或保存");
        var reservation=new Reservation(owner);held.put(owner,reservation);return reservation;
    }
    public synchronized void start(String owner,Runnable register){
        if(held.containsKey(owner))throw new IllegalStateException("此归属的存档正在管理，请稍后开机");register.run();
    }
    public final class Reservation implements AutoCloseable {
        private final String owner;private final AtomicBoolean allowed=new AtomicBoolean(true);
        private Reservation(String owner){this.owner=Objects.requireNonNull(owner);}
        public boolean allowed(){return allowed.get();}
        /** Revocation prevents writes but keeps the gate until queued/running IO has actually ended. */
        public void revoke(){allowed.set(false);}
        public void close(){allowed.set(false);synchronized(MdSaveTransactions.this){held.remove(owner,this);}}
    }
}
