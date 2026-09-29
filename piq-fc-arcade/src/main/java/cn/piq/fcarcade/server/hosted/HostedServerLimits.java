package cn.piq.fcarcade.server.hosted;

import cn.piq.fcarcade.cabinet.CabinetGameBudget;
import cn.piq.fcarcade.cabinet.CabinetHostingConfig;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.server.MinecraftServer;

/** Shared admission across home and cabinet adapters, including pending and closing workers. */
public final class HostedServerLimits {
    private static final Map<MinecraftServer, Pool> SERVERS = new WeakHashMap<>();
    private HostedServerLimits() {}

    /** Acquire before starting asynchronous work. Registration alone never enables execution. */
    public static synchronized Lease tryAcquire(MinecraftServer server) {
        if (server == null || !server.isSameThread() || !CabinetHostingConfig.enabled()) return null;
        return SERVERS.computeIfAbsent(server, ignored -> new Pool()).acquire(CabinetHostingConfig.maxRooms());
    }

    /** Conservative reservation per recipient copy; failed transports do not manufacture credit. */
    public static synchronized boolean reserveMedia(MinecraftServer server, Lease lease, int bytes) {
        if (server == null || lease == null || !server.isSameThread() || !CabinetHostingConfig.enabled()) return false;
        var pool=SERVERS.get(server);
        return pool!=null && pool.reserve(lease,bytes,CabinetHostingConfig.bytesPerSecond(),System.nanoTime());
    }

    /** A lease must not be released merely because close was requested: wait for actual termination. */
    public static final class Lease implements AutoCloseable {
        private Pool pool;
        private int shareRate;
        private CabinetGameBudget shareBudget;
        private Lease(Pool pool) { this.pool = pool; }
        @Override public synchronized void close() {
            if (pool != null) { pool.release(); pool = null; }
        }
    }

    // Independent of Minecraft so admission races and conservative byte accounting are testable.
    static final class Pool {
        private int active, rate;
        private CabinetGameBudget budget;
        synchronized Lease acquire(int maximum) {
            if (maximum < 1 || active >= maximum) return null;
            active++;
            return new Lease(this);
        }
        private synchronized void release() {
            if (active <= 0) throw new IllegalStateException("Hosted admission underflow");
            active--;
        }
        synchronized int active() { return active; }
        boolean reserve(Lease lease,int bytes,int bytesPerSecond,long now) {
            synchronized(lease) { synchronized(this) {
                if(lease.pool!=this||active<1||bytes<=0||bytes>262144||bytesPerSecond<=0)return false;
                int share=Math.max(1,bytesPerSecond/active);
                if(lease.shareBudget==null||lease.shareRate!=share) {
                    lease.shareRate=share;
                    lease.shareBudget=new CabinetGameBudget(share,262144,now);
                }
                // A busy first room cannot continually consume other rooms' steady-state share.
                if(!lease.shareBudget.permits(bytes,now)||!reserve(bytes,bytesPerSecond,now))return false;
                lease.shareBudget.charge(bytes,now);
                return true;
            }}
        }
        synchronized boolean reserve(int bytes, int bytesPerSecond, long now) {
            if (bytesPerSecond <= 0 || bytes <= 0 || bytes > 262144) return false;
            if (budget == null || rate != bytesPerSecond) {
                rate = bytesPerSecond;
                budget = new CabinetGameBudget(rate, 262144, now);
            }
            if (!budget.permits(bytes, now)) return false;
            budget.charge(bytes, now);
            return true;
        }
    }
}
