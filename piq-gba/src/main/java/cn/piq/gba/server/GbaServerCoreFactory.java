// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.gba.server;

import cn.piq.fcarcade.cabinet.CabinetFrame;
import cn.piq.fcarcade.server.hosted.*;
import cn.piq.gba.bridge.GbaJniSession;
import java.nio.file.Path;

/** Single-seat hosted GBA, with SRAM owned by the immutable server/device/player context. */
public final class GbaServerCoreFactory implements ServerCoreFactory {
    @Override public int maxPlayers(){return 1;}
    @Override public int maxConcurrentSessions(){return 1;}
    @Override public String unavailableReason(ServerCoreContext context){
        String reason=cn.piq.retro.libretro.LibretroRuntimes.jniUnavailableReason();
        if(!reason.isBlank())return reason;
        return GbaJniSession.active()||cn.piq.retro.libretro.LibretroRuntimes.isJniBusy()?"GBA JNI 正在运行或等待安全释放":null;
    }
    @Override public ServerCoreHandle open(ServerCoreContext context,Path rom)throws Exception{
        ServerCoreFiles.directory(rom.toAbsolutePath().normalize().getParent(),false);
        GbaJniSession core=new GbaJniSession(rom,context.saveDirectory("gba"));
        return new ServerCoreHandle(){
            @Override public int maxPlayers(){return 1;}
            @Override public boolean isReady(){return core.isReady();}
            @Override public boolean isTerminated(){return core.awaitClosed(0);}
            @Override public String error(){return core.error();}
            @Override public void offerInput(int a,int b){if(b!=0||(a&~4095)!=0)throw new IllegalArgumentException("GBA single-seat input");core.offerInput(a&0xDFD);}
            @Override public void releasePort(int port){if(port!=0)throw new IllegalArgumentException("GBA has one port");core.clearInput();}
            @Override public void clearInput(){core.clearInput();}
            @Override public CabinetFrame pollFrame(){var frame=core.pollFrame();return frame==null?null:new CabinetFrame(240,160,frame.abgr(),1.5F,0,frame.pcm48k());}
            @Override public void close(){core.clearInput();core.close();}
        };
    }
}
