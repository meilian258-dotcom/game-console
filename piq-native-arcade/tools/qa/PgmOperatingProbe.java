// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.nativearcade.bridge;

import cn.piq.retro.libretro.*;
import cn.piq.retro.libretro.jni.NativeLibretroBridge;
import cn.piq.retro.storage.RuntimeWorkspace;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;

/** Isolated fixed-core NVRAM probe. Never modifies core bytes, ROMs or production saves.
 * Arguments: output-dir, read-only-rom-dir, prior-fs-or-dash, frames:pad-mask ... .
 * Private frame PNGs/native NVRAM remain outside distributable source/artifacts. */
public final class PgmOperatingProbe {
    private static final String CORE="/native-runtime/win-x64-fbneo-state-v3/fbneo_libretro.dll";
    private static final String SHA="3E0AB5DB5898E9E75E1704CAA703D6F4E4CB269C4F971AB498B434F22D18F885";
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    private static String hash(byte[] value)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));}
    public static void main(String[] args)throws Exception {
        Path out=Path.of(args[0]).toAbsolutePath(),source=Path.of(args[1]).toAbsolutePath();
        Files.createDirectories(out);Path instance=Files.createDirectory(out.resolve("instance"));RuntimeWorkspace.configure(instance);
        var profile=new LibretroProfile("FinalBurn Neo","zip",true,List.of(1,1,1,1),false,
                Map.of("fbneo-samplerate","48000","fbneo-fixed-frameskip","0","fbneo-force-60hz","disabled","fbneo-allow-patched-romsets","disabled","fbneo-diagnostic-input","Hold Start + L + R"),
                Map.of("windows-x64",new LibretroProfile.Artifact(CORE,SHA)));
        var files=Map.of("kov.zip",source.resolve("kov.zip"),"pgm.zip",source.resolve("pgm.zip"));
        var originals=new TreeMap<String,String>();for(var e:files.entrySet())originals.put(e.getKey(),hash(Files.readAllBytes(e.getValue())));
        byte[] initial=args[2].equals("-")?null:Files.readAllBytes(Path.of(args[2]));byte[][] saved={null};
        try(var runtime=new LibretroJniRuntime(profile,PgmOperatingProbe.class)){
            runtime.operatingFile(new LibretroOperatingFile("fbneo/kov.fs",1024*1024,initial,bytes->{saved[0]=bytes.clone();Files.write(out.resolve("kov.fs"),bytes,StandardOpenOption.CREATE_NEW);}));
            runtime.loadFiles("kov.zip",files,null);System.out.println("CORE "+runtime.coreVersion()+" sha="+SHA);
            for(int a=3;a<args.length;a++){
                String[] command=args[a].split(":");int frames=Integer.parseInt(command[0]),pad=Integer.parseInt(command[1]);check(frames>0&&frames<=10000,"frame budget");
                LibretroProcess.Output latest=null;
                for(int f=0;f<frames;f++)latest=runtime.run(List.of(new LibretroProcess.Controls(new int[]{pad,0,0,0},0)),LibretroProcess.VIDEO);
                var image=new BufferedImage(latest.info().width(),latest.info().height(),BufferedImage.TYPE_INT_RGB);byte[] rgba=latest.rgba();
                for(int y=0;y<image.getHeight();y++)for(int x=0;x<image.getWidth();x++){int i=(y*image.getWidth()+x)*4;image.setRGB(x,y,((rgba[i]&255)<<16)|((rgba[i+1]&255)<<8)|(rgba[i+2]&255));}
                ImageIO.write(image,"png",out.resolve("step-"+(a-3)+".png").toFile());System.out.println("STEP "+(a-3)+" frames="+frames+" mask="+pad);
            }
        }
        check(saved[0]!=null&&saved[0].length==131072,"PGM native NVRAM file expected");
        check(NativeLibretroBridge.availableSlots()==4,"native slot release");
        for(var e:files.entrySet())check(originals.get(e.getKey()).equals(hash(Files.readAllBytes(e.getValue()))),"source ROM changed");
        System.out.println("NVRAM_CLOSE_OK bytes="+saved[0].length+" sha256="+hash(saved[0])+" prior="+(initial==null?"none":hash(initial))+" slots=4 originalsUnchanged=true");
    }
}
