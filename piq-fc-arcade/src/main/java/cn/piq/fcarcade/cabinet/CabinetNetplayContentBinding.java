// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.fcarcade.cabinet;

import java.util.Objects;
import java.util.UUID;

/** Server-thread-only, one-run binding to the host's successfully verified content.
 * No old cabinet selection is accepted at construction; a failed registrar never commits it. */
final class CabinetNetplayContentBinding {
    private final Object connection;
    private final UUID player,lease;
    private final String backend;
    private CabinetGameManifest manifest;
    private boolean retired,binding;

    CabinetNetplayContentBinding(Object connection,UUID player,UUID lease,String backend){
        this.connection=Objects.requireNonNull(connection);this.player=Objects.requireNonNull(player);
        this.lease=Objects.requireNonNull(lease);this.backend=Objects.requireNonNull(backend);
    }
    boolean owns(Object connection,UUID player,UUID lease,String backend){
        return this.connection==connection&&this.player.equals(player)&&this.lease.equals(lease)&&this.backend.equals(backend);
    }
    /** Identical repeated END is harmless; no caller may replace a run's bound content. */
    boolean bind(Object connection,UUID player,UUID lease,String backend,boolean ready,
            CabinetGameManifest next,Runnable registrar){
        Objects.requireNonNull(next);Objects.requireNonNull(registrar);
        if(retired||binding||!owns(connection,player,lease,backend)||!this.backend.equals(next.backend()))
            throw new IllegalStateException("Netplay 内容授权已失效");
        if(manifest!=null){
            if(!manifest.equals(next))throw new IllegalStateException("本局 Netplay 内容已锁定，请关机后重新选择游戏或 BIOS");
            return false;
        }
        if(ready)throw new IllegalStateException("运行中的 Netplay 不能重新绑定内容");
        binding=true;
        try{
            registrar.run();
            if(retired)throw new IllegalStateException("Netplay 内容准备已取消");
            manifest=next;return true;
        }finally{binding=false;}
    }
    boolean matches(CabinetGameManifest value){return !retired&&manifest!=null&&manifest.equals(value);}
    void retire(){retired=true;}
}
