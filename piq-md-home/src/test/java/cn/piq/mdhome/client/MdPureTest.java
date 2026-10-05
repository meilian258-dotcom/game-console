package cn.piq.mdhome.client;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.util.*;
class MdPureTest {
    byte[] rom(){byte[] b=new byte[1024];System.arraycopy("SEGA MEGA DRIVE ".getBytes(java.nio.charset.StandardCharsets.US_ASCII),0,b,256,16);b[6]=2;return b;}
    @Test void ordinaryHeaderAndExtensions()throws Exception{MdRom.validate(rom());assertTrue(MdRom.accepts("测试.MD"));assertTrue(MdRom.accepts("a.bin"));assertFalse(MdRom.accepts("a.smd"));assertFalse(MdRom.accepts("a.zip"));assertFalse(MdRom.accepts("a.cue"));}
    @Test void rejectsOtherHardwareAndBadSizes(){for(String h:List.of("SEGA 32X        ","SEGA PICO       ","SEGA CD         ","garbage         ")){byte[] b=rom();System.arraycopy(h.getBytes(),0,b,256,16);assertThrows(IOException.class,()->MdRom.validate(b));}assertThrows(IOException.class,()->MdRom.validate(new byte[512]));assertThrows(IOException.class,()->MdRom.validate(new byte[513]));}
    @Test void rejectsInvalidResetVector(){byte[] b=rom();b[7]=1;assertThrows(IOException.class,()->MdRom.validate(b));byte[] f=rom();f[4]=1;assertThrows(IOException.class,()->MdRom.validate(f));}
    @Test void audioIsBoundedAndChunkIndependent(){short[] wave=new short[20000];for(int i=0;i<wave.length;i++)wave[i]=(short)(i%2000);MdAudio whole=new MdAudio(),split=new MdAudio();short[] expected=whole.convert(wave,53267);var list=new ArrayList<Short>();for(int at=0;at<wave.length;at+=1000)for(short s:split.convert(Arrays.copyOfRange(wave,at,at+1000),53267))list.add(s);assertEquals(expected.length,list.size());for(int i=0;i<expected.length;i++)assertEquals(expected[i],list.get(i));assertThrows(IllegalArgumentException.class,()->whole.convert(new short[3],53267));assertThrows(IllegalArgumentException.class,()->whole.convert(new short[32770],53267));}
    @Test void profileNeverEnablesNetworkingOrExternalFirmware(){
        var p=MdProfile.profile();assertEquals("Genesis Plus GX",p.name());assertEquals(List.of(513,513),p.devices());
        for(String key:List.of("bios","lock_on","frameskip","overscan"))assertEquals("disabled",p.options().get("genesis_plus_gx_"+key));
        assertEquals(Set.of("windows-x64"),p.cores().keySet());assertTrue(p.fullPath());
    }
    @Test void gxAllInputCombinationsPreserveBindings(){
        int[] expected={1,10,2,3,4,5,6,7,0,9,11,8};Set<Integer> seen=new HashSet<>();
        for(int mask=0;mask<4096;mask++){
            int mapped=0;for(int bit=0;bit<12;bit++)if((mask&(1<<bit))!=0)mapped|=1<<expected[bit];
            assertEquals(mapped,MdProfile.input(MdProfile.Core.GENESIS_PLUS_GX,mask));
            seen.add(mapped);
        }
        assertEquals(4096,seen.size());assertThrows(IllegalArgumentException.class,()->MdProfile.input(MdProfile.Core.GENESIS_PLUS_GX,4096));
    }
    @Test void gxTrimmedBatteryOnlyRestoredBeforeFirstRun(){
        var full=new cn.piq.retro.libretro.LibretroSaveMemory(new byte[65536],new byte[0]);
        byte[] padded=MdSaves.startupRam(new byte[]{-1,0x5a},full);assertEquals(65536,padded.length);assertEquals(0x5a,padded[1]);
        for(int i=2;i<padded.length;i++)assertEquals((byte)0xff,padded[i]);
        assertEquals(65536,MdSaves.startupRam(new byte[0],full).length);
        assertArrayEquals(new byte[65536],MdSaves.startupRam(new byte[65536],full));
        assertEquals(0,MdSaves.startupRam(new byte[0],new cn.piq.retro.libretro.LibretroSaveMemory(new byte[0],new byte[0])).length);
        assertThrows(IllegalStateException.class,()->MdSaves.startupRam(new byte[65537],full));
        assertThrows(IllegalStateException.class,()->MdSaves.startupRam(new byte[2],new cn.piq.retro.libretro.LibretroSaveMemory(new byte[2],new byte[0])));
        assertThrows(IllegalStateException.class,()->MdSaves.startupRam(new byte[0],new cn.piq.retro.libretro.LibretroSaveMemory(new byte[65536],new byte[1])));
    }
    @Test void gxNamespacesRemainByteIdenticalAfterRetirement(){
        var jni=cn.piq.retro.libretro.LibretroRuntimes.Backend.JNI_TRIAL;
        String suffix="gx-v1/9ffa10a115b20e1b49e9caf0b53f287c640ed4e5bb93f7ed9a23b416a4ccfdf7/bb0002f5733b81d3c561b46b1123fbc9e8eb012651a2fa5c578cc2074d360422";
        assertEquals("jni-v1/"+suffix,MdProfile.saveNamespace(MdProfile.Core.GENESIS_PLUS_GX,jni));
        assertEquals("process-v1/"+suffix,MdProfile.saveNamespace(MdProfile.Core.GENESIS_PLUS_GX,cn.piq.retro.libretro.LibretroRuntimes.Backend.PROCESS));
    }
    @Test void gxAudio44100IsChunkIndependent(){
        short[] wave=new short[22050];for(int i=0;i<wave.length;i++)wave[i]=(short)(i%1000);
        var whole=new MdAudio();var split=new MdAudio();short[] expected=whole.convert(wave,44100);var actual=new ArrayList<Short>();
        for(int i=0;i<wave.length;i+=882)for(short s:split.convert(Arrays.copyOfRange(wave,i,Math.min(i+882,wave.length)),44100))actual.add(s);
        assertEquals(expected.length,actual.size());for(int i=0;i<expected.length;i++)assertEquals(expected[i],actual.get(i));
    }
}
