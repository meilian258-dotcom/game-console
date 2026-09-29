// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.gba.bridge;

import java.nio.file.*;
import java.util.*;

/** No Minecraft, sockets or commercial ROM. Actual mGBA inside this child JVM. */
public final class GbaCoreProbe {
    private static int assertions;
    private static void check(boolean value,String label){assertions++;if(!value)throw new AssertionError(label);}
    private static int color(int gba){int r=(gba&31)*255/31,g=((gba>>>5)&31)*255/31,b=((gba>>>10)&31)*255/31;return 0xff000000|(b<<16)|(g<<8)|r;}
    public static void main(String[] args)throws Exception {
        if(args.length!=3&&args.length!=4)throw new IllegalArgumentException("DLL, owned diagnostic.gba, private directory, optional exact helper JAR");
        if(args.length==4){Path expected=Path.of(args[3]).toRealPath();for(Class<?> type:List.of(GbaCore.class,GbaCore.Frame.class,PcmResampler.class,GbaProtocol.class))check(Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath().equals(expected),"actual final helper origin "+type.getName());}
        Path dll=Path.of(args[0]),rom=Path.of(args[1]),dir=Path.of(args[2]);
        byte[] saved,state;double fps,rate;int format;long samples=0,nonzero=0;String version;
        try(GbaCore core=new GbaCore(dll,rom,dir)){
            fps=core.fps();rate=core.sampleRate();format=core.pixelFormat();version=core.version();
            check(fps>59&&fps<60,"real GBA frame timing");check(format==2,"actual official core RGB565");
            GbaCore.Frame f=null;
            for(int i=0;i<90;i++){
                f=core.step(0);check(f!=null&&f.width()==240&&f.height()==160,"GBA frame dimensions");
                samples+=f.pcm48k().length/2;for(short s:f.pcm48k())if(s!=0)nonzero++;
            }
            check(f.abgr()[239]==0xff0000ff,"original diagnostic solid red ABGR");check(f.abgr()[0]==color(0),"neutral key register pixel");
            check(samples>68000&&samples<75000,"real audio at converted 48k clock");check(nonzero>1000,"real PSG ROM audio nonzero");
            int[] bits={8,0,2,3,7,6,4,5,11,10};
            for(int index=0;index<bits.length;index++){
                for(int step=0;step<3;step++)f=core.step(1<<bits[index]);
                int expected=color(1<<index),actual=f.abgr()[0];
                check((actual&255)==(expected&255)&&Math.abs(((actual>>>8)&255)-((expected>>>8)&255))<=2&&(actual>>>16&255)==(expected>>>16&255),"actual key "+index+" expected="+Integer.toHexString(expected)+" got="+Integer.toHexString(actual));
                for(int step=0;step<3;step++)f=core.step(0);
                check(f.abgr()[0]==color(0),"released key "+index);
            }
            for(int i=0;i<3;i++)core.step((1<<8)|1);
            saved=core.saveRam();check(saved.length==32768,"SRAM detected from owned ROM");check(saved[0]==3,"actual cartridge write A+B to SRAM");
            state=core.snapshot();check(state.length>1024&&state.length<GbaCore.MAX_STATE,"real serialization bounded");
            for(int i=0;i<5;i++)core.step(0);core.restore(state);for(int i=0;i<3;i++)f=core.step(0);
            check(f.abgr()[0]==color(0),"snapshot restoration and neutral input");
            Files.write(dir.resolve("diagnostic-only.sav"),saved,StandardOpenOption.CREATE_NEW);
        }
        try(GbaCore reopened=new GbaCore(dll,rom,dir)){
            reopened.loadRam(Files.readAllBytes(dir.resolve("diagnostic-only.sav")));
            for(int i=0;i<5;i++)reopened.step(0);
            byte[] after=reopened.saveRam();
            check(after.length>=saved.length&&after.length<=GbaCore.MAX_SAVE,"autodetect save capacity remains bounded");
            check(Arrays.equals(saved,Arrays.copyOf(after,saved.length)),"all persisted SRAM bytes survived before any new write");
            for(int i=saved.length;i<after.length;i++)check(after[i]==(byte)0xff,"untyped save padding untouched");
            for(int i=0;i<3;i++)reopened.step((1<<8)|1);
            check(Arrays.equals(saved,reopened.saveRam()),"cartridge redetects exact SRAM size and all data survives");
            reopened.close();reopened.close();
            try{reopened.step(0);throw new AssertionError("closed step accepted");}catch(java.io.IOException expected){assertions++;}
        }
        PcmResampler whole=new PcmResampler(),split=new PcmResampler();short[] source=new short[2048];
        for(int i=0;i<source.length;i++)source[i]=(short)(i*17-9000);
        short[] all=whole.convert(source,source.length,32768),a=split.convert(Arrays.copyOfRange(source,0,222),222,32768),b=split.convert(Arrays.copyOfRange(source,222,2048),1826,32768);
        short[] joined=new short[a.length+b.length];System.arraycopy(a,0,joined,0,a.length);System.arraycopy(b,0,joined,a.length,b.length);
        check(Arrays.equals(all,joined),"32768 streaming phase independent of callback boundaries");
        check(Arrays.equals(source,new PcmResampler().convert(source,source.length,48000)),"48k identity no sample changes");
        System.out.println("{\"ok\":true,\"assertions\":"+assertions+",\"production_origin\":\""+(args.length==4?"final-jar-only":"fresh-standalone-compile")+"\",\"core_version\":\""+version+"\",\"actual_rgb565\":true,\"fps\":"+fps+",\"native_sample_rate\":"+rate+",\"pcm48k_frames_first90\":"+samples+",\"nonzero_samples\":"+nonzero+",\"save_ram_bytes\":"+saved.length+",\"state_bytes\":"+state.length+",\"diagnostic\":\"original-no-nintendo-logo-no-bios-no-commercial-data\",\"minecraft_started\":false,\"gameplay_compatibility_claimed\":false}");
    }
}
