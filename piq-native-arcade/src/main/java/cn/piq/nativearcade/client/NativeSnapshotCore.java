// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.nativearcade.client;

import cn.piq.fcarcade.cabinet.CabinetFrame;
import cn.piq.fcarcade.cabinet.CabinetSyncCore;
import cn.piq.nativearcade.NativeSnapshotProfile;
import cn.piq.nativearcade.bridge.NativeSnapshotSession;
import cn.piq.nativearcade.bridge.NativeSnapshotState;
import java.io.IOException;
import java.nio.file.Path;
import java.util.function.Consumer;

/** Worker-only adapter. Logical frame zero is the verified 32-frame native bootstrap boundary. */
public final class NativeSnapshotCore implements CabinetSyncCore {
    private final NativeSnapshotSession session;
    public NativeSnapshotCore(Path runtime,Path rom,Consumer<Runnable> cancellationRegistrar)throws IOException{
        session=new NativeSnapshotSession(runtime,rom,cancellationRegistrar);
    }
    @Override public int maxPlayers(){return NativeSnapshotProfile.PLAYERS;}
    @Override public double targetFps(){return session.hello().fps();}
    @Override public String compatibilityId(){return NativeSnapshotProfile.COMPATIBILITY_ID;}
    @Override public int snapshotIntervalFrames(){return NativeSnapshotProfile.SNAPSHOT_INTERVAL;}
    @Override public CabinetFrame runFrame(int p1,int p2,int p3,int p4)throws IOException{
        if(p3!=0||p4!=0)throw new IOException("本地输入同步最多支持两个游戏席位；第三/四席请使用音画串流");
        var value=session.step(p1,p2);
        if(!value.hasVideo())throw new IOException("Native core did not produce a post-bootstrap picture");
        // The legacy helper is CCW; only normalize at its entrance to the public CW frame contract.
        return new CabinetFrame(value.width(),value.height(),value.abgr(),value.displayAspect(),(4-value.rotation())&3,value.pcm48k());
    }
    @Override public byte[] saveState()throws IOException{return NativeSnapshotState.encode(session.frame(),session.romHash(),session.saveState());}
    @Override public void loadState(byte[] value)throws IOException{var state=NativeSnapshotState.decode(value,session.romHash());session.loadState(state.internalFrame(),state.payload());}
    @Override public void loadState(byte[] value,long logicalFrame)throws IOException{
        var state=NativeSnapshotState.decode(value,session.romHash(),logicalFrame);
        session.loadState(state.internalFrame(),state.payload());
    }
    /** Cross-thread cancellation signal only; no native function calls from this thread. */
    @Override public void requestClose(){session.close();}
    @Override public void close(){session.close();}
    public long ownedPid(){return session.ownedPid();}
}
