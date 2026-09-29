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
    @Test void profileNeverEnablesNetworkingOrExternalFirmware(){var p=MdProfile.profile();assertEquals("off",p.options().get("blastem_megawifi"));assertEquals("md1va3",p.options().get("blastem_model"));assertEquals(Set.of("windows-x64"),p.cores().keySet());assertTrue(p.fullPath());}
}
