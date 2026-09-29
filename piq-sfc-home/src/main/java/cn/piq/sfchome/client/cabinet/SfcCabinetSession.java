// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.sfchome.client.cabinet;

import cn.piq.fcarcade.cabinet.CabinetEmulator;
import cn.piq.fcarcade.cabinet.CabinetFrame;
import cn.piq.sfcarcade.core.*;
import cn.piq.sfchome.core.LibretroSfcCore;
import cn.piq.sfchome.client.SfcCoreLease;
import java.util.Objects;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;

/** Trusted-ROM local adapter. Only its daemon worker ever enters/tears down the libretro core. */
public final class SfcCabinetSession implements CabinetEmulator {
    private final SfcCabinetInputs input=new SfcCabinetInputs();
    private final SfcCabinetFrames output=new SfcCabinetFrames();
    private final SfcCoreLease lease;
    private final Thread worker;
    private volatile boolean running=true,ready;
    private volatile String error;
    public SfcCabinetSession(SfcRomImage rom){this(rom,LibretroSfcCore::new);}
    SfcCabinetSession(SfcRomImage rom,Supplier<SfcCore> factory){
        Objects.requireNonNull(rom);Objects.requireNonNull(factory);
        lease=SfcCoreLease.acquireAfterObserver(()->false);
        try{worker=Thread.ofPlatform().daemon(true).name("PIQ-SFC-Cabinet").start(()->run(rom,factory));}
        catch(Throwable failure){lease.close();throw failure;}
    }
    @Override public boolean isReady(){return running&&ready;}
    @Override public String error(){return error;}
    @Override public int maxPlayers(){return 2;}
    @Override public void offerInput(int p1,int p2){
        if(!running)return;
        if(!input.offer(p1,p2)){error="SFC 输入队列已满，游戏已停止以免丢键";close();}
    }
    @Override public void clearInput(){input.clear();}
    @Override public void releasePort(int port){input.releasePort(port);}
    @Override public CabinetFrame pollFrame(){return running?output.poll():null;}
    @Override public void close(){running=false;ready=false;input.clear();output.clear();worker.interrupt();}
    private void run(SfcRomImage rom,Supplier<SfcCore> factory){
        try(SfcCore core=factory.get()){
            if(!running)return;
            core.loadRom(rom);
            byte[] rgba=new byte[0];short[] pcm=new short[0];
            long deadline=System.nanoTime();
            while(running){
                var buttons=input.nextFrame();
                var result=core.runFrame(new SfcControllerState(buttons.p1()),new SfcControllerState(buttons.p2()));
                if(!running)break;
                var mode=result.videoMode();double fps=mode.targetFramesPerSecond();
                if(!Double.isFinite(fps)||fps<25||fps>120)throw new IllegalStateException("SFC 核心返回异常帧率");
                if(result.requiredPcmShorts()>8192)throw new IllegalStateException("SFC 音频单帧超限");
                if(rgba.length!=mode.requiredRgbaBytes())rgba=new byte[mode.requiredRgbaBytes()];
                if(pcm.length<result.requiredPcmShorts())pcm=new short[result.requiredPcmShorts()];
                core.copyRgbaFrame(rgba);
                int samples=core.copyAudioPcm16(pcm);
                if(samples!=result.stereoSampleFrames())throw new IllegalStateException("SFC 音频帧数不一致");
                output.publish(mode,rgba,pcm,Math.multiplyExact(samples,2));ready=true;
                // PAL/NTSC and a running game's timing changes use the actual per-frame core metadata.
                long nanos=Math.max(1,Math.round(1_000_000_000.0/fps));deadline+=nanos;
                long now=System.nanoTime();if(deadline<now-4*nanos)deadline=now;
                long delay=deadline-now;if(delay>0&&running)LockSupport.parkNanos(delay);
            }
        }catch(Throwable failure){
            if(running){System.getLogger(SfcCabinetSession.class.getName()).log(System.Logger.Level.ERROR,"SFC cabinet stopped",failure);error=failure.getMessage()==null?failure.getClass().getSimpleName():failure.getMessage();}
        }finally{
            ready=false;running=false;input.clear();output.clear();lease.close();
        }
    }
}
