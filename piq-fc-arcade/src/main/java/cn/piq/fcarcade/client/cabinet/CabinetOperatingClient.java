// SPDX-License-Identifier: GPL-3.0-or-later
// PIQ-authored code: additional permission in piq-fc-arcade/SNES9X-LINKING-EXCEPTION.txt.
package cn.piq.fcarcade.client.cabinet;

import cn.piq.fcarcade.cabinet.CabinetOperatingFiles;
import cn.piq.fcarcade.netplay.*;
import cn.piq.retro.libretro.LibretroOperatingFile;
import cn.piq.retro.replay.ReplayGuard;
import java.util.concurrent.TimeUnit;

/** Host-only adapter; server permissions independently gate the final upload. */
final class CabinetOperatingClient implements JniNetplaySession.OperatingFiles {
    private final NetplayProcess.Persistence channel;
    private final NetplayProfile profile;
    private final long epoch=ReplayGuard.epoch();
    private volatile boolean authorized;
    private boolean clean,loaded,finished;
    private NetplaySaveState.Identity identity;
    CabinetOperatingClient(NetplayProcess.Persistence channel,NetplayProfile profile){this.channel=channel;this.profile=profile;}
    void authorize(){if(ReplayGuard.valid(epoch))authorized=true;}
    @Override public LibretroOperatingFile prepare(NetplaySaveState.Identity game)throws Exception {
        identity=CabinetOperatingFiles.identity(game);
        byte[] initial=CabinetOperatingFiles.unpack(identity,channel.load(identity));loaded=true;
        return new LibretroOperatingFile(CabinetOperatingFiles.relative(profile),CabinetOperatingFiles.MAX_BYTES,initial,bytes->{
            if(!clean||!authorized||!ReplayGuard.valid(epoch))return;
            channel.save(CabinetOperatingFiles.pack(identity,bytes)).get(45,TimeUnit.SECONDS);
            if(!ReplayGuard.valid(epoch))throw new java.io.IOException("Replay 已使旧运营会话失效");
            channel.finish().get(10,TimeUnit.SECONDS);
            finished=true;
        });
    }
    @Override public void normalClose(boolean normal){clean=normal&&loaded&&ReplayGuard.valid(epoch);}
    @Override public void abort(){
        if(!finished&&clean&&loaded&&ReplayGuard.valid(epoch))try{channel.finish().get(10,TimeUnit.SECONDS);finished=true;}catch(Exception failed){channel.abort();return;}
        if(!finished)channel.abort();
    }
}
