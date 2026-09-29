// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.gba.server;

import cn.piq.fcarcade.cabinet.CabinetFrame;
import cn.piq.fcarcade.server.hosted.*;
import cn.piq.gba.bridge.GbaProcessSession;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Properties;

/** Single-seat hosted GBA, with SRAM owned by the immutable server/device/player context. */
public final class GbaServerCoreFactory implements ServerCoreFactory {
    @Override public int maxPlayers(){return 1;}
    @Override public int maxConcurrentSessions(){return 1;}
    private static Path runtime(ServerCoreContext context){return cn.piq.retro.storage.ConsoleStorage.root(context.gameRoot()).resolve("piq-gba/runtime");}
    @Override public String unavailableReason(ServerCoreContext context){
        return ServerCoreFiles.windowsRuntimeReason(runtime(context),"mgba_libretro.dll","piq-gba-helper.jar","jna-5.14.0.jar");
    }
    @Override public ServerCoreHandle open(ServerCoreContext context,Path rom)throws Exception{
        Properties lock=new Properties();try(var input=GbaServerCoreFactory.class.getResourceAsStream("/piq-gba-runtime.properties")){
            if(input==null)throw new IOException("GBA runtime identity resource is missing");lock.load(input);
        }
        ServerCoreFiles.directory(runtime(context),false);ServerCoreFiles.directory(rom.toAbsolutePath().normalize().getParent(),false);
        GbaProcessSession core=new GbaProcessSession(runtime(context),rom,context.saveDirectory("gba"),lock.getProperty("helper.sha256"));
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
