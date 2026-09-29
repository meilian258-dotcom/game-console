// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.sfchome.client;

import java.util.concurrent.atomic.AtomicReference;

/** Shared by home playback and the cabinet provider; only the owning worker releases after core close. */
public final class SfcCoreLease implements AutoCloseable {
    private static final AtomicReference<SfcCoreLease> OWNER=new AtomicReference<>();
    private final boolean readOnly;
    private SfcCoreLease(boolean readOnly) {this.readOnly=readOnly;}
    public static SfcCoreLease acquire(){
        return acquire(false);
    }
    static SfcCoreLease acquireObserver(){return acquire(true);}
    private static SfcCoreLease acquire(boolean readOnly){
        SfcCoreLease lease=new SfcCoreLease(readOnly);
        if(!OWNER.compareAndSet(null,lease))throw new IllegalStateException("SFC 核心正在使用或退出，请先结束当前 SFC 游戏；持续无响应请重启客户端");
        return lease;
    }
    public static boolean occupied(){return OWNER.get()!=null;}
    public static boolean observing(){var current=OWNER.get();return current!=null&&current.readOnly;}
    /** Off-thread startup only: await already-cancelled read-only teardown; never displace an active game. */
    public static SfcCoreLease acquireAfterObserver(java.util.function.BooleanSupplier cancelled){
        long deadline=System.nanoTime()+5_000_000_000L;
        while(observing()){
            if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted()||System.nanoTime()>deadline)
                throw new IllegalStateException("SFC 旁观核心尚未退出，请稍后重试");
            java.util.concurrent.locks.LockSupport.parkNanos(2_000_000L);
        }
        if(cancelled.getAsBoolean())throw new IllegalStateException("SFC 启动已取消");
        return acquire();
    }
    @Override public void close(){OWNER.compareAndSet(this,null);}
}
