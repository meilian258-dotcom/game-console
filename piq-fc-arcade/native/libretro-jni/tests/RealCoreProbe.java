// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.retro.libretro.jni;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.CRC32;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
/** Isolated native integration probe; caller stages private content and save directory. */
public final class RealCoreProbe {
    public static void main(String[] args)throws Exception {
        System.load(Path.of(args[0]).toAbsolutePath().toString());
        String core=args[1],content=args[2],work=args[3],name=args[4];
        boolean full=Boolean.parseBoolean(args[5]);int features=Integer.parseInt(args[6]);
        int frames=Integer.parseInt(args[7]);String[] pins=Arrays.copyOfRange(args,8,args.length);
        int ports=name.equals("FinalBurn Neo")?4:2;
        int[] devices=new int[ports];Arrays.fill(devices,1);
        ByteBuffer pixels=ByteBuffer.allocateDirect(2048*2048*4).order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer pcm=ByteBuffer.allocateDirect(65536).order(ByteOrder.LITTLE_ENDIAN);
        int[] meta=new int[11],input={0,0,0,0,-1,-32768,-32768,0,0,0,0,-1,0};double[] timing=new double[3];
        long h=NativeLibretroBridge.open(core,content,work,work,name,full,devices,pins,features);
        try {
            NativeLibretroBridge.metadata(h,meta,timing);
            int capabilities=NativeLibretroBridge.saveCapabilities(h);
            if(name.equals("PvZ")&&(capabilities&1)!=0)throw new AssertionError("PvZ stub state must not be advertised");
            System.out.println("OPEN name="+name+" version="+NativeLibretroBridge.coreVersion(h)+" metadata="+Arrays.toString(meta)+" timing="+Arrays.toString(timing));
            long audio=0;CRC32 crc=new CRC32();Set<Long> crcs=new HashSet<>();
            for(int i=0;i<frames;i++) {
                input[0]=i%60<30?1<<8:0;
                NativeLibretroBridge.step(h,pixels,pcm,input,new int[0],meta,timing);audio+=meta[7];
                crc.reset();pixels.position(0).limit(meta[6]);crc.update(pixels);pixels.clear();crcs.add(crc.getValue());
                if((features&1)!=0)Thread.sleep(16);
            }
            if(meta[0]<1||meta[1]<1||audio<1)throw new AssertionError("No video or audio");
            BufferedImage image=new BufferedImage(meta[0],meta[1],BufferedImage.TYPE_INT_ARGB);
            for(int y=0;y<meta[1];y++)for(int x=0;x<meta[0];x++){int abgr=pixels.getInt((y*meta[0]+x)*4);image.setRGB(x,y,0xff000000|((abgr&255)<<16)|(abgr&0xff00)|((abgr>>16)&255));}
            ImageIO.write(image,"png",Path.of(work,"last-frame.png").toFile());
            String stateResult="unsupported";if((capabilities&1)!=0){byte[] state=NativeLibretroBridge.serialize(h);NativeLibretroBridge.restore(h,state);byte[] again=NativeLibretroBridge.serialize(h);stateResult=state.length+" bytes, reserializeEqual="+Arrays.equals(state,again);}
            int ram=NativeLibretroBridge.memory(h,0).length,rtc=NativeLibretroBridge.memory(h,1).length;
            System.out.println("REAL_CORE_OK frames="+frames+" videoCRCs="+crcs.size()+" audioShorts="+audio+" state="+stateResult+" RAM="+ram+" RTC="+rtc+" metadata="+Arrays.toString(meta)+" timing="+Arrays.toString(timing));
        } finally {NativeLibretroBridge.close(h);}
    }
}
