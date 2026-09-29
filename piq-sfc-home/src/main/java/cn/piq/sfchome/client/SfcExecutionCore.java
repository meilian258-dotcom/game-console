package cn.piq.sfchome.client;

import cn.piq.sfcarcade.core.*;
import cn.piq.sfchome.core.LibretroSfcCore;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Same initialization, frame timing and canonical state validation for home and synchronized cabinets.
 * Owned by exactly one worker. No files, Minecraft singleton, network or input leases are accessed. */
public final class SfcExecutionCore implements SfcCore {
    public static final int MAX_STATE=16*1024*1024;
    private final LibretroSfcCore core=new LibretroSfcCore();
    private final Thread owner=Thread.currentThread();
    private double fps;
    private void owned(){if(Thread.currentThread()!=owner)throw new IllegalStateException("SFC core accessed outside its worker");}
    public double initialize(){owned();fps=core.runFrame(SfcControllerState.NONE,SfcControllerState.NONE).videoMode().targetFramesPerSecond();
        if(!Double.isFinite(fps)||!(fps>=49&&fps<=51||fps>=59&&fps<=61))throw new IllegalStateException("Unsupported SFC frame rate");
        core.reset(true);return fps;}
    public double targetFps(){owned();if(fps==0)throw new IllegalStateException("SFC not initialized");return fps;}
    public SfcCore nativeCore(){owned();return core;}
    @Override public String backendName(){owned();return core.backendName();}
    @Override public void loadRom(SfcRomImage rom){owned();core.loadRom(rom);fps=0;}
    @Override public SfcFrameResult runFrame(SfcControllerState a,SfcControllerState b){owned();var result=core.runFrame(a,b);
        if(fps!=0&&Math.abs(result.videoMode().targetFramesPerSecond()-fps)>.01)throw new IllegalStateException("SFC switched frame timing during synchronized play");return result;}
    @Override public void copyRgbaFrame(byte[] to){owned();core.copyRgbaFrame(to);}
    @Override public int copyAudioPcm16(short[] to){owned();return core.copyAudioPcm16(to);}
    @Override public byte[] saveState(){owned();byte[] state=core.saveState();validate(state);return state;}
    @Override public void loadState(byte[] state){owned();validate(state);String expected=digest(state);core.loadState(state);
        if(!expected.equals(digest(saveState())))throw new IllegalStateException("Imported SFC state differs");}
    public static void validate(byte[] state){if(state==null||state.length<1||state.length>MAX_STATE)throw new IllegalArgumentException("SFC state size out of bounds");}
    public static String digest(byte[] state){validate(state);try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(state));}catch(java.security.NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}}
    @Override public byte[] saveSram(){owned();return core.saveSram();}
    @Override public void loadSram(byte[] state){owned();core.loadSram(state);}
    @Override public void reset(boolean hard){owned();core.reset(hard);}
    @Override public void close(){owned();core.close();}
}
