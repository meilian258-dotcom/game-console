// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.sfchome.server.hosted;

import cn.piq.fcarcade.cabinet.CabinetFrame;
import cn.piq.fcarcade.server.hosted.*;
import cn.piq.sfcarcade.core.*;
import cn.piq.sfchome.core.LibretroSfcCore;
import java.nio.file.Path;
import java.util.Arrays;

/** Dedicated-server adapter: no client class, singleton, player lookup or audio device. */
public final class SfcServerCoreFactory implements ServerCoreFactory {
    @Override public int maxPlayers(){return 2;}
    @Override public String unavailableReason(ServerCoreContext context){String reason=LibretroSfcCore.unavailableReason();return reason.isEmpty()?null:reason;}
    @Override public ServerCoreHandle open(ServerCoreContext context,Path rom){return new ServerCoreWorker(2,false,true,()->openCore(context,rom));}
    private static ServerCoreWorker.Core openCore(ServerCoreContext context,Path path)throws Exception{
        SfcRomImage rom=SfcRomImage.fromBytes(ServerCoreFiles.read(path,SfcRomImage.MIN_ROM_BYTES,SfcRomImage.MAX_ROM_BYTES+512));
        LibretroSfcCore core=new LibretroSfcCore();HostedSaveFile save=null;
        try{
            core.loadRom(rom);
            double fps=core.runFrame(SfcControllerState.NONE,SfcControllerState.NONE).videoMode().targetFramesPerSecond();
            if(!Double.isFinite(fps)||!(fps>=49&&fps<=51||fps>=59&&fps<=61))throw new IllegalStateException("SFC frame rate bounds");
            core.reset(true);
            save=new HostedSaveFile(context.saveDirectory("sfc-libretro-v1"),rom.sha256(),LibretroSfcCore.saveNamespace(),8*1024*1024);
            byte[] initial=save.load();if(initial.length>0)core.loadSram(initial);
            HostedSaveFile ownedSave=save;
            return new ServerCoreWorker.Core(){
                private byte[] rgba=new byte[0];private short[] pcm=new short[0];
                @Override public double targetFps(){return fps;}
                @Override public CabinetFrame runFrame(int a,int b,int c,int d,int gun){
                    if(c!=0||d!=0||gun!=cn.piq.fcarcade.session.ZapperInput.NEUTRAL)throw new IllegalArgumentException("SFC input capabilities");
                    var result=core.runFrame(new SfcControllerState(a),new SfcControllerState(b));var mode=result.videoMode();
                    if(Math.abs(mode.targetFramesPerSecond()-fps)>.01)throw new IllegalStateException("SFC changed timing during hosted play");
                    if(rgba.length!=mode.requiredRgbaBytes())rgba=new byte[mode.requiredRgbaBytes()];
                    if(result.requiredPcmShorts()>8192)throw new IllegalStateException("SFC audio bounds");
                    if(pcm.length<result.requiredPcmShorts())pcm=new short[result.requiredPcmShorts()];
                    core.copyRgbaFrame(rgba);int samples=core.copyAudioPcm16(pcm);
                    if(samples!=result.stereoSampleFrames())throw new IllegalStateException("SFC audio length mismatch");
                    int[] abgr=new int[mode.width()*mode.height()];
                    for(int y=0;y<mode.height();y++)for(int x=0;x<mode.width();x++){int p=y*mode.rowStrideBytes()+x*4;abgr[y*mode.width()+x]=0xff000000|(rgba[p]&255)|((rgba[p+1]&255)<<8)|((rgba[p+2]&255)<<16);}
                    return new CabinetFrame(mode.width(),mode.height(),abgr,(float)(mode.width()*mode.pixelAspectRatio()/mode.height()),0,Arrays.copyOf(pcm,samples*2));
                }
                @Override public void reset(){core.reset(false);}
                @Override public void persist()throws Exception{ownedSave.save(core.saveSram());}
                @Override public void close(){try{core.close();}finally{ownedSave.close();}}
            };
        }catch(Throwable failure){try{core.close();}finally{if(save!=null)save.close();}throw failure;}
    }
}
