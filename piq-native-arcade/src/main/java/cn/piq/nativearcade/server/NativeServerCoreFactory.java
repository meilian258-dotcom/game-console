// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.nativearcade.server;

import cn.piq.fcarcade.cabinet.CabinetFrame;
import cn.piq.fcarcade.server.hosted.*;
import cn.piq.nativearcade.bridge.NativeJniMediaSession;
import java.nio.file.Path;

/** Owner-thread JNI media adapter. No snapshot persistence/reset is claimed by this bridge. */
public final class NativeServerCoreFactory implements ServerCoreFactory {
    @Override public int maxPlayers(){return 4;}
    @Override public int maxConcurrentSessions(){return 1;}
    private static Path runtime(ServerCoreContext context){return cn.piq.retro.storage.ConsoleStorage.root(context.gameRoot()).resolve("piq-native-arcade/runtime");}
    @Override public String unavailableReason(ServerCoreContext context){
        return NativeJniMediaSession.unavailableReason();
    }
    @Override public ServerCoreHandle open(ServerCoreContext context,Path rom)throws Exception{
        NativeJniMediaSession core=new NativeJniMediaSession(runtime(context),rom);
        return new ServerCoreHandle(){
            @Override public int maxPlayers(){return 4;}
            @Override public boolean isReady(){return core.isReady();}
            @Override public boolean isTerminated(){return core.isTerminated();}
            @Override public String error(){return core.error();}
            @Override public void offerInput(int a,int b){core.offerInput(a,b);}
            @Override public void offerInputs(int a,int b,int c,int d){core.offerInputs(a,b,c,d);}
            @Override public void releasePort(int port){core.releasePort(port);}
            @Override public boolean supportsCoinPreservingRelease(){return true;}
            @Override public void releaseGameplayPortKeepingCoin(int port){core.releaseGameplayPortKeepingCoin(port);}
            @Override public void clearInput(){core.clearInput();}
            @Override public CabinetFrame pollFrame(){var frame=core.pollFrame();return frame==null?null:new CabinetFrame(frame.width(),frame.height(),frame.abgr(),frame.displayAspect(),frame.rotation(),frame.pcm48k());}
            @Override public void close(){core.clearInput();core.close();}
        };
    }
}
