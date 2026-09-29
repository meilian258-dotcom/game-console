package cn.piq.sfchome.core;

import cn.piq.sfcarcade.core.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import static org.junit.jupiter.api.Assertions.*;

/** Actual pinned Mesen-S worker with original LoROM bytecode, not commercial game material. */
@EnabledOnOs(OS.WINDOWS)
class LibretroSfcCoreTest {
    @Test void hostedFactoryReportsAvailableWithNullReason(){
        var factory=new cn.piq.sfchome.server.hosted.SfcServerCoreFactory();
        assertNull(factory.unavailableReason(null));assertEquals(2,factory.maxPlayers());
    }
    static byte[] rom(boolean pal,boolean battery){
        byte[] r=new byte[32768];Arrays.fill(r,(byte)255);
        int[] code={0x78,0x18,0xfb,0xc2,0x30,0xa2,0xff,0x1f,0x9a,0xe2,0x20,0xa9,0x80,0x8d,0,0x21,
            0x9c,0x21,0x21,0xa9,0xe0,0x8d,0x22,0x21,0xa9,3,0x8d,0x22,0x21,0xa9,15,0x8d,0,0x21,0x80,0xfe};
        for(int i=0;i<code.length;i++)r[i]=(byte)code[i];
        Arrays.fill(r,0x7fc0,0x7fd5,(byte)32);byte[] name="PIQ ORIGINAL SFC TEST".getBytes(java.nio.charset.StandardCharsets.US_ASCII);System.arraycopy(name,0,r,0x7fc0,name.length);
        r[0x7fd5]=0x20;r[0x7fd6]=(byte)(battery?2:0);r[0x7fd7]=5;r[0x7fd8]=(byte)(battery?3:0);r[0x7fd9]=(byte)(pal?2:1);r[0x7fda]=0;r[0x7fdb]=0;
        for(int offset:new int[]{0x7fe4,0x7fe6,0x7fe8,0x7fea,0x7fee,0x7ff4,0x7ff8,0x7ffa,0x7ffc,0x7ffe}){r[offset]=0;r[offset+1]=(byte)128;}
        int sum=510;for(int i=0;i<r.length;i++)if(i<0x7fdc||i>0x7fdf)sum+=r[i]&255;
        r[0x7fdc]=(byte)~sum;r[0x7fdd]=(byte)(~sum>>8);r[0x7fde]=(byte)sum;r[0x7fdf]=(byte)(sum>>8);return r;
    }
    static LibretroSfcCore open(boolean pal,boolean battery){var c=new LibretroSfcCore();try{c.loadRom(SfcRomImage.fromBytes(rom(pal,battery)));return c;}catch(Throwable e){c.close();throw e;}}
    static byte[] pixels(LibretroSfcCore c,SfcFrameResult f){byte[] p=new byte[f.videoMode().requiredRgbaBytes()];c.copyRgbaFrame(p);return p;}
    @Test void ntscAndPalRenderGreenAndOutput48k(){for(boolean pal:new boolean[]{false,true})try(var c=open(pal,false)){
        long count=0;SfcFrameResult f=null;for(int i=0;i<65;i++){f=c.runFrame(SfcControllerState.NONE,SfcControllerState.NONE);count+=f.stereoSampleFrames();}
        assertEquals(pal?50:60,f.videoMode().targetFramesPerSecond(),1);assertTrue(count>48000*(65/f.videoMode().targetFramesPerSecond()-.15));
        byte[] p=pixels(c,f);int at=(f.videoMode().height()/2*f.videoMode().width()+f.videoMode().width()/2)*4;
        assertTrue((p[at+1]&255)>150);assertTrue((p[at]&255)<30);assertTrue((p[at+2]&255)<30);
    }}
    @Test void independentCoresAgreeAndStateRoundTrips(){try(var a=open(false,false);var b=open(false,false)){
        assertArrayEquals(a.saveState(),b.saveState());
        for(int i=0;i<36;i++){var p=new SfcControllerState(1<<(i%12));a.runFrame(p,p);b.runFrame(p,p);assertArrayEquals(a.saveState(),b.saveState());}
        byte[] state=a.saveState();var f=a.runFrame(SfcControllerState.NONE,SfcControllerState.NONE);byte[] image=pixels(a,f);short[] audio=new short[f.requiredPcmShorts()];a.copyAudioPcm16(audio);
        b.loadState(state);assertArrayEquals(state,b.saveState());var g=b.runFrame(SfcControllerState.NONE,SfcControllerState.NONE);assertEquals(f,g);assertArrayEquals(image,pixels(b,g));short[] other=new short[g.requiredPcmShorts()];b.copyAudioPcm16(other);assertArrayEquals(audio,other);
    }}
    @Test void sramRestoresInFreshProcessAndBadStateDoesNotMutate(){byte[] saved;try(var c=open(false,true)){
        saved=c.saveSram();assertEquals(8192,saved.length);Arrays.fill(saved,(byte)0x5a);c.loadSram(saved);assertArrayEquals(saved,c.saveSram());
        c.runFrame(SfcControllerState.NONE,SfcControllerState.NONE);byte[] state=c.saveState(),bad=state.clone();bad[10]^=1;assertThrows(IllegalArgumentException.class,()->c.loadState(bad));assertArrayEquals(state,c.saveState());
        assertThrows(IllegalArgumentException.class,()->c.loadState(new byte[512]));assertArrayEquals(state,c.saveState());
    }try(var c=open(false,true)){c.loadSram(saved);assertArrayEquals(saved,c.saveSram());}}
    @Test void wrongRomStateRejectedAndHardResetDeterministic(){try(var c=open(false,false);var d=open(true,false)){
        byte[] initial=c.saveState();c.runFrame(SfcControllerState.NONE,SfcControllerState.NONE);assertThrows(IllegalArgumentException.class,()->d.loadState(c.saveState()));c.reset(true);assertArrayEquals(initial,c.saveState());
    }}
}
