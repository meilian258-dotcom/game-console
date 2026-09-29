package cn.piq.sfchome.client.cabinet;

import cn.piq.fcarcade.cabinet.CabinetFrame;
import cn.piq.fcarcade.cabinet.CabinetSyncCore;
import cn.piq.sfcarcade.core.*;
import cn.piq.sfchome.client.SfcCoreLease;
import cn.piq.sfchome.client.SfcExecutionCore;
import cn.piq.sfchome.net.SfcHomeNetwork;

/** No private clock: the shared cabinet worker executes the server's authoritative frame order. */
final class SfcCabinetSyncCore implements CabinetSyncCore {
    private final SfcCoreLease lease;
    private final SfcExecutionCore core;
    private final SfcCabinetFrames frames=new SfcCabinetFrames();
    private byte[] rgba=new byte[0];private short[] pcm=new short[0];private boolean closed;
    SfcCabinetSyncCore(SfcRomImage rom){lease=SfcCoreLease.acquireAfterObserver(()->false);SfcExecutionCore opened=null;
        try{opened=new SfcExecutionCore();opened.loadRom(rom);opened.initialize();core=opened;}
        catch(RuntimeException|Error failure){try{if(opened!=null)opened.close();}finally{lease.close();}throw failure;}}
    @Override public int maxPlayers(){return 2;}
    @Override public double targetFps(){return core.targetFps();}
    @Override public String compatibilityId(){return SfcHomeNetwork.CORE_BUILD;}
    @Override public CabinetFrame runFrame(int p1,int p2,int p3,int p4){
        if(closed||p1<0||p1>4095||p2<0||p2>4095||p3!=0||p4!=0)throw new IllegalArgumentException("Invalid SFC synchronized input");
        var result=core.runFrame(new SfcControllerState(p1),new SfcControllerState(p2));var mode=result.videoMode();
        if(rgba.length!=mode.requiredRgbaBytes())rgba=new byte[mode.requiredRgbaBytes()];
        if(result.requiredPcmShorts()>8192)throw new IllegalStateException("SFC audio overflow");
        if(pcm.length<result.requiredPcmShorts())pcm=new short[result.requiredPcmShorts()];
        core.copyRgbaFrame(rgba);int count=core.copyAudioPcm16(pcm);
        if(count!=result.stereoSampleFrames())throw new IllegalStateException("SFC audio length differs");
        frames.publish(mode,rgba,pcm,Math.multiplyExact(count,2));return frames.poll();
    }
    @Override public byte[] saveState(){return core.saveState();}
    @Override public void loadState(byte[] state){core.loadState(state);frames.clear();}
    @Override public void close(){if(closed)return;closed=true;try{core.close();}finally{lease.close();frames.clear();}}
}
