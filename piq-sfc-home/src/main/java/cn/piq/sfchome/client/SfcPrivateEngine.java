// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.sfchome.client;

import cn.piq.fcarcade.cabinet.CabinetFrame;
import cn.piq.fcarcade.client.privateplay.PrivateEngine;
import cn.piq.fcarcade.client.privateplay.PrivateSaveStore;
import cn.piq.sfcarcade.core.*;
import cn.piq.sfchome.core.LibretroSfcCore;
import cn.piq.retro.libretro.LibretroRuntimes;
import java.io.IOException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;

/** Private P1 playback. All ROM/core/save work belongs to this client worker; no public session exists. */
public final class SfcPrivateEngine implements PrivateEngine {
    private static final int MAX_INPUT_EDGES=128,MAX_PCM=32768;
    private final Object controls=new Object(),pictures=new Object();
    private final ArrayDeque<Integer> edges=new ArrayDeque<>();
    private final short[] audio=new short[MAX_PCM];
    private final CompletableFuture<SaveResult> stopped=new CompletableFuture<>();
    private final SfcCoreLease reserved;
    private final Thread worker;
    private volatile boolean stopping,paused,ready;
    private volatile String error;
    private volatile LibretroSfcCore diagnosticCore;
    private boolean awaitNeutral=true;
    private int wanted,current,firstAudio,audioSize;
    private long inputRevision;
    private CabinetFrame picture;

    /** Fast construction: explicit local path validation, disk IO and core initialization happen on the worker. */
    public SfcPrivateEngine(Path localRom,Path saveRoot){this(localRom,saveRoot,LibretroSfcCore::new,null);}
    /** Trial transport is explicit and never shares a key with the normal private backup. */
    public SfcPrivateEngine(Path localRom,Path saveRoot,LibretroRuntimes.Backend backend){
        this(localRom,saveRoot,factory(backend),saveNamespace(backend));
    }
    static String saveNamespace(LibretroRuntimes.Backend backend){
        return (Objects.requireNonNull(backend)==LibretroRuntimes.Backend.JNI_TRIAL?"jni-trial-v1/":"")+LibretroSfcCore.saveNamespace();
    }
    private static Supplier<SfcCore> factory(LibretroRuntimes.Backend backend){
        Objects.requireNonNull(backend);return ()->new LibretroSfcCore(backend);
    }
    SfcPrivateEngine(Path localRom,Path saveRoot,Supplier<SfcCore> factory,String fixtureNamespace){
        Objects.requireNonNull(localRom);Objects.requireNonNull(saveRoot);Objects.requireNonNull(factory);
        reserved=SfcCoreLease.observing()?null:SfcCoreLease.acquire();
        try{worker=Thread.ofPlatform().daemon(true).name("SFC-Private-Playback")
                .start(()->run(localRom,saveRoot,factory,fixtureNamespace));}
        catch(Throwable failure){if(reserved!=null)reserved.close();throw failure;}
    }
    @Override public int maxPlayers(){return 1;}
    @Override public boolean isReady(){return ready&&!stopping;}
    @Override public String error(){if(error!=null)return error;var selected=diagnosticCore;String nativeError=selected==null?"":selected.diagnosticError();return nativeError.isEmpty()?null:nativeError;}
    @Override public void offerInput(int p1,int p2){
        if((p1&~4095)!=0||p2!=0)throw new IllegalArgumentException("Private SFC accepts one twelve-bit local controller");
        synchronized(controls){
            if(stopping||paused)return;
            if(awaitNeutral){if(p1==0)awaitNeutral=false;return;}
            if(p1==wanted)return;
            if(edges.size()>=MAX_INPUT_EDGES){error="SFC 本地输入队列已满，已停止；旧私人存档未覆盖";requestStop();return;}
            edges.addLast(p1);wanted=p1;
        }
    }
    @Override public void clearInput(){synchronized(controls){releaseInputs();}}
    @Override public void releasePort(int port){
        if(port!=0)throw new IllegalArgumentException("Private SFC exposes P1 only");
        clearInput();
    }
    private void releaseInputs(){edges.clear();wanted=0;current=0;awaitNeutral=true;inputRevision++;}
    @Override public void paused(boolean value){
        synchronized(controls){
            if(paused==value)return;
            paused=value;releaseInputs();
            // The same lock order is used by publish: a pre-pause frame cannot requeue sound.
            clearPictures();
        }
        LockSupport.unpark(worker);
    }
    @Override public CabinetFrame pollFrame(){
        synchronized(pictures){
            if(stopping||paused||picture==null)return null;
            short[] pcm=new short[audioSize];
            for(int i=0;i<audioSize;i++)pcm[i]=audio[(firstAudio+i)%MAX_PCM];
            CabinetFrame last=picture;picture=null;firstAudio=0;audioSize=0;
            return new CabinetFrame(last.width(),last.height(),last.abgr(),last.displayAspect(),0,pcm);
        }
    }
    private void clearPictures(){synchronized(pictures){picture=null;firstAudio=0;audioSize=0;}}
    private void requestStop(){
        synchronized(controls){stopping=true;ready=false;releaseInputs();clearPictures();}
        // Do not interrupt a FileChannel/atomic save or block the render thread joining native teardown.
        LockSupport.unpark(worker);
    }
    @Override public CompletableFuture<SaveResult> stopAndSave(){requestStop();return stopped;}
    @Override public void close(){stopAndSave();}

    private void run(Path localRom,Path saveRoot,Supplier<SfcCore> factory,String fixtureNamespace){
        SfcCore core=null;PrivateSaveStore store=null;PrivateSaveStore.Key key=null;SfcCoreLease lease=reserved;
        boolean initialized=false;
        SaveResult result=new SaveResult(false,"SFC 私人游戏尚未开始，旧本地存档未改动");
        try{
            if(lease==null)lease=SfcCoreLease.acquireAfterObserver(()->stopping);
            SfcRomImage rom=SfcRomImage.fromBytes(SfcClientFiles.importRom(localRom));
            if(stopping)return;
            String namespace=fixtureNamespace==null?coreNamespace():fixtureNamespace;
            key=new PrivateSaveStore.Key("sfc",namespace,rom.sha256());
            store=new PrivateSaveStore(saveRoot);
            var saved=store.load(key); // Corrupt or incompatible material must fail before any replacement is possible.
            if(stopping)return;
            core=factory.get();if(core instanceof LibretroSfcCore libretro)diagnosticCore=libretro;core.loadRom(rom);
            var boot=core.runFrame(SfcControllerState.NONE,SfcControllerState.NONE);
            checkedFps(boot.videoMode());core.reset(true);
            if(saved.isPresent()){
                byte[] state=saved.get().state(),sram=saved.get().sram();
                // Restore native SRAM into a fresh core, then exact machine/resampler state.
                // The versioned namespace never imports old jgenesis private saves.
                core.loadSram(sram);core.loadState(state);
                if(!Arrays.equals(state,core.saveState())||!Arrays.equals(sram,core.saveSram()))
                    throw new IOException("SFC private restore verification failed");
            }
            initialized=true;
            // Preparation can finish while the menu has paused playback. Readiness must not require a frame.
            if(!stopping)ready=true;
            byte[] rgba=new byte[0];short[] pcm=new short[0];long deadline=System.nanoTime();
            while(!stopping){
                int buttons;long revision;
                synchronized(controls){
                    if(!edges.isEmpty())current=edges.removeFirst();
                    buttons=current;revision=inputRevision;
                }
                if(paused){deadline=System.nanoTime();LockSupport.parkNanos(5_000_000L);continue;}
                var frame=core.runFrame(new SfcControllerState(buttons),SfcControllerState.NONE);
                var mode=frame.videoMode();double fps=checkedFps(mode);
                if(frame.requiredPcmShorts()>8192)throw new IOException("SFC private audio frame exceeds bounds");
                if(rgba.length!=mode.requiredRgbaBytes())rgba=new byte[mode.requiredRgbaBytes()];
                if(pcm.length<frame.requiredPcmShorts())pcm=new short[frame.requiredPcmShorts()];
                core.copyRgbaFrame(rgba);int samples=core.copyAudioPcm16(pcm);
                if(samples!=frame.stereoSampleFrames())throw new IOException("SFC private audio frame length differs");
                publish(mode,rgba,pcm,Math.multiplyExact(samples,2),revision);
                if(!stopping)ready=true;
                long nanos=Math.max(1,Math.round(1_000_000_000.0/fps));deadline+=nanos;
                long now=System.nanoTime();if(deadline<now-4*nanos)deadline=now;
                if(deadline>now&&!stopping)LockSupport.parkNanos(deadline-now);
            }
        }catch(Throwable failure){
            error=initialized?"SFC 私人运行异常，已停止；旧本地存档未覆盖":"SFC 私人游戏或存档准备失败；旧本地文件未改动";
            System.getLogger(SfcPrivateEngine.class.getName()).log(System.Logger.Level.ERROR,"Private SFC playback failed",failure);
        }finally{
            stopping=true;ready=false;clearInput();clearPictures();
            if(error!=null)result=new SaveResult(false,error);
            else if(initialized&&core!=null){
                try{store.save(key,core.saveState(),core.saveSram());result=new SaveResult(true,"SFC 私人存档已保存，仅保存在本机");}
                catch(Throwable failure){
                    result=new SaveResult(false,"SFC 私人存档保存失败，旧本地存档已保留");
                    System.getLogger(SfcPrivateEngine.class.getName()).log(System.Logger.Level.WARNING,"Private SFC save failed",failure);
                }
            }
            try{if(core!=null)core.close();}
            catch(Throwable failure){
                error="SFC 核心退出异常，请重启客户端后重试";
                result=new SaveResult(result.saved(),result.message()+"；"+error);
                System.getLogger(SfcPrivateEngine.class.getName()).log(System.Logger.Level.WARNING,"Private SFC teardown failed",failure);
            }finally{if(lease!=null)lease.close();stopped.complete(result);}
        }
    }
    private static double checkedFps(SfcVideoMode mode)throws IOException{
        double fps=mode.targetFramesPerSecond();
        if(!Double.isFinite(fps)||!(fps>=49&&fps<=51||fps>=59&&fps<=61))throw new IOException("SFC private frame rate outside PAL/NTSC bounds");
        return fps;
    }
    private void publish(SfcVideoMode mode,byte[] rgba,short[] pcm,int length,long revision){
        if(length<0||length>pcm.length||(length&1)!=0)throw new IllegalArgumentException("Private SFC output bounds");
        int[] abgr=new int[Math.multiplyExact(mode.width(),mode.height())];
        for(int y=0;y<mode.height();y++)for(int x=0;x<mode.width();x++){
            int p=y*mode.rowStrideBytes()+x*4;
            abgr[y*mode.width()+x]=0xff000000|(rgba[p]&255)|((rgba[p+1]&255)<<8)|((rgba[p+2]&255)<<16);
        }
        synchronized(controls){
            if(stopping||paused||revision!=inputRevision)return;
            synchronized(pictures){
                picture=new CabinetFrame(mode.width(),mode.height(),abgr,(float)(mode.width()*mode.pixelAspectRatio()/mode.height()),0,new short[0]);
                for(int i=0;i<length;i++){
                    if(audioSize==MAX_PCM){firstAudio=(firstAudio+1)%MAX_PCM;audioSize--;}
                    audio[(firstAudio+audioSize)%MAX_PCM]=pcm[i];audioSize++;
                }
            }
        }
    }
    private static String coreNamespace()throws Exception{
        return LibretroSfcCore.saveNamespace();
    }
}
