// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.sfcarcade.core;

import cn.piq.sfcarcade.core.wasm.WasmSfcCore;
import java.security.MessageDigest;
import java.util.*;

/** Independent public-API probe. Production classes and WASM must load from the frozen JAR. */
public final class SfcReuseProbe {
    private static int checks;
    private static void require(boolean value,String message) {
        checks++; if(!value)throw new AssertionError(message);
    }
    private static byte[] digest(SfcCore core,SfcFrameResult frame) throws Exception {
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        byte[] rgba=new byte[frame.videoMode().requiredRgbaBytes()]; core.copyRgbaFrame(rgba);
        short[] pcm=new short[frame.requiredPcmShorts()];
        require(core.copyAudioPcm16(pcm)==frame.stereoSampleFrames(),"Audio length mismatch");
        digest.update(rgba);
        for(short sample:pcm){digest.update((byte)sample);digest.update((byte)(sample>>>8));}
        return digest.digest();
    }
    private static int first(int frame){return 1<<(frame%12);}
    private static int second(int frame){return frame%3==0?0:1<<((frame+5)%12);}
    public static void main(String[] args) throws Exception {
        SfcRomImage rom=SfcRomImage.fromBytes(SfcTwoPortInputProbe.rom());
        long start=System.nanoTime();
        int width=0,height=0,stateBytes=0;
        try(SfcCore a=new WasmSfcCore();SfcCore b=new WasmSfcCore()){
            a.loadRom(rom);b.loadRom(rom);
            require(SfcControllerState.VALID_MASK==4095,"All twelve keys required");
            for(int f=0;f<96;f++){
                var p1=new SfcControllerState(first(f));var p2=new SfcControllerState(second(f));
                var fa=a.runFrame(p1,p2);var fb=b.runFrame(p1,p2);
                require(fa.videoMode().equals(fb.videoMode()),"Video modes differ at "+f);
                require(fa.emulatedFrameNumber()==f&&fb.emulatedFrameNumber()==f,"Counters differ");
                require(Arrays.equals(digest(a,fa),digest(b,fb)),"Independent instances disagree at "+f);
                width=fa.videoMode().width();height=fa.videoMode().height();
            }
            byte[] state=a.saveState();stateBytes=state.length;
            require(Arrays.equals(state,b.saveState()),"Serialized native states disagree");
            List<byte[]> replay=new ArrayList<>();
            for(int f=0;f<32;f++){
                var fr=a.runFrame(new SfcControllerState(first(f+96)),new SfcControllerState(second(f+96)));
                replay.add(digest(a,fr));
            }
            byte[] expectedFinal=a.saveState();
            a.loadState(state);
            for(int f=0;f<32;f++){
                var fr=a.runFrame(new SfcControllerState(first(f+96)),new SfcControllerState(second(f+96)));
                require(Arrays.equals(replay.get(f),digest(a,fr)),"State replay pixels/audio mismatch at "+f);
            }
            require(Arrays.equals(expectedFinal,a.saveState()),"Replayed native state differs");
            require(a.saveSram().length==0,"ROM-only fixture unexpectedly has SRAM");
            a.loadSram(new byte[0]);
        }
        System.out.println("{\"ok\":true,\"checks\":"+checks+",\"paired_frames\":96,\"state_replay_frames\":32,\"video_width\":"+width+",\"video_height\":"+height+",\"state_bytes\":"+stateBytes+",\"elapsed_ms\":"+((System.nanoTime()-start)/1000000)+"}");
    }
}
