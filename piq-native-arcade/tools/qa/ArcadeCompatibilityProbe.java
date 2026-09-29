// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.nativearcade.bridge;

import cn.piq.nativearcade.NativeNetplayProfile;
import cn.piq.retro.libretro.*;
import cn.piq.retro.libretro.jni.NativeLibretroBridge;
import cn.piq.retro.storage.RuntimeWorkspace;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;

/** Isolated diagnostics only. Licensed ROMs/BIOS and snapshots are never report content. */
public final class ArcadeCompatibilityProbe {
    private static final List<String> BIOS=List.of("pgm.zip","neogeo.zip","qsound_hle.zip","qsound.zip");
    private static void metric(String key,Object value){System.out.println("METRIC\t"+key+"\t"+value);System.out.flush();}
    private static void require(boolean ok,String why){if(!ok)throw new AssertionError(why);}
    private static String sha(byte[] b)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));}
    private static String sound(short[] samples)throws Exception{
        byte[] data=new byte[samples.length*2];for(int n=0;n<samples.length;n++){data[2*n]=(byte)samples[n];data[2*n+1]=(byte)(samples[n]>>>8);}return sha(data);
    }
    private static int[] pads(int frame){
        int coin=frame>=180&&frame<183?4:0, start=frame>=210&&frame<213?8:0;
        int play=frame>240?((frame/24&1)==0?128|1:64|256):0;
        return new int[]{coin|start|play,frame>420?play:0,frame>660?play:0,frame>780?play:0};
    }
    private static LibretroProcess.Output step(LibretroRuntime core,int frame){return core.run(List.of(new LibretroProcess.Controls(pads(frame),0)),3);}
    private static Map<String,Path> content(Path rom){
        var files=new TreeMap<String,Path>();files.put(rom.getFileName().toString(),rom);
        for(String name:BIOS){Path p=rom.getParent().resolve(name);if(Files.isRegularFile(p)&&!p.equals(rom))files.put(name,p);}
        return files;
    }
    private static long different(byte[] a,byte[] b){long n=Math.abs(a.length-b.length);for(int i=0;i<Math.min(a.length,b.length);i++)if(a[i]!=b[i])n++;return n;}
    private static void capture(Path root,LibretroProcess.Output out)throws Exception{
        var img=new BufferedImage(out.info().width(),out.info().height(),BufferedImage.TYPE_INT_ARGB);byte[] b=out.rgba();
        for(int y=0;y<img.getHeight();y++)for(int x=0;x<img.getWidth();x++){int i=4*(y*img.getWidth()+x);img.setRGB(x,y,0xff000000|((b[i]&255)<<16)|((b[i+1]&255)<<8)|(b[i+2]&255));}
        ImageIO.write(img,"png",root.resolve("qa-last-frame.png").toFile());
    }
    private static void core(Path root,Path rom,boolean diagnosticOptions)throws Exception{
        var files=content(rom);String name=rom.getFileName().toString();
        var profile=NativeNetplayProfile.runtime();
        if(diagnosticOptions){var options=new TreeMap<>(profile.options());options.remove("fbneo-diagnostic-input");profile=new LibretroProfile(profile.name(),profile.extension(),profile.fullPath(),profile.devices(),profile.mesenGun(),options,profile.cores());}
        metric("diagnosticOptionsOverride",diagnosticOptions);
        int baseline=Integer.getInteger("gc.probe.baselineFrames",900);require(baseline>=900&&baseline<=7200,"Probe frame budget");
        try(var a=new LibretroJniRuntime(profile,NativeNetplayProfile.class)){
            var info=a.loadFiles(name,files,null);metric("loaded",true);metric("fps",info.fps());metric("sampleRate",info.sampleRate());metric("rotation",a.rotation());
            // Same early gate as the production JNI Netplay. Do not waive a failure later.
            a.run(List.of(new LibretroProcess.Controls(new int[4],0)),0);a.reset();a.run(List.of(new LibretroProcess.Controls(new int[4],0)),0);
            byte[] initial=a.serialize();a.restore(initial);byte[] resaved=a.serialize();
            metric("earlyStateBytes",initial.length);metric("earlyRestoreDiffBytes",different(initial,resaved));
            metric("earlyGatePassed",initial.length<=2*1024*1024&&Arrays.equals(initial,resaved));
            a.reset();
            var pictures=new HashSet<String>();long pcm=0,nonzero=0;
            for(int f=0;f<baseline;f++){
                var out=step(a,f);pictures.add(sha(out.rgba()));pcm+=out.stereo().length;
                for(short sample:out.stereo())if(sample!=0)nonzero++;
                if(f==baseline-1)capture(root,out);
            }
            metric("baselineFrames",baseline);metric("differentPictures",pictures.size());metric("pcmShorts",pcm);metric("nonzeroPcmShorts",nonzero);
            require(pictures.size()>2&&pcm>0,"No real picture/audio output");
            byte[] checkpoint=a.serialize();var video=new ArrayList<String>();var audio=new ArrayList<String>();
            for(int f=baseline;f<baseline+180;f++){var out=step(a,f);video.add(sha(out.rgba()));audio.add(sound(out.stereo()));}
            byte[] end=a.serialize();
            a.restore(checkpoint);metric("lateRestoreDiffBytes",different(checkpoint,a.serialize()));
            int videoDiff=0,audioDiff=0;
            var videoIndices=new ArrayList<Integer>();
            for(int f=baseline;f<baseline+180;f++){var out=step(a,f);if(!video.get(f-baseline).equals(sha(out.rgba()))){videoDiff++;videoIndices.add(f-baseline);}if(!audio.get(f-baseline).equals(sound(out.stereo())))audioDiff++;}
            metric("localVideoDiffIndices",videoIndices);
            metric("localReplayVideoDiffFrames",videoDiff);metric("localReplayPcmDiffFrames",audioDiff);metric("localReplayEndStateDiffBytes",different(end,a.serialize()));
            try(var b=new LibretroJniRuntime(profile,NativeNetplayProfile.class)){
                b.loadFiles(name,files,null);b.run(List.of(new LibretroProcess.Controls(new int[4],0)),0);b.reset();b.run(List.of(new LibretroProcess.Controls(new int[4],0)),0);
                b.restore(checkpoint);metric("crossRestoreDiffBytes",different(checkpoint,b.serialize()));videoDiff=audioDiff=0;
                videoIndices.clear();
                for(int f=baseline;f<baseline+180;f++){var out=step(b,f);if(!video.get(f-baseline).equals(sha(out.rgba()))){videoDiff++;videoIndices.add(f-baseline);}if(!audio.get(f-baseline).equals(sound(out.stereo())))audioDiff++;}
                metric("crossVideoDiffIndices",videoIndices);
                metric("crossReplayVideoDiffFrames",videoDiff);metric("crossReplayPcmDiffFrames",audioDiff);metric("crossReplayEndStateDiffBytes",different(end,b.serialize()));
            }
            metric("replayFramesPerPath",180);
        }
    }
    private static void media(Path root,Path rom)throws Exception{
        var r=new NativeJniMediaSession(root,rom);int frames=0;long audio=0,nonzero=0;var pictures=new HashSet<Integer>();
        try{
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(65);
            while(frames<600&&System.nanoTime()<deadline){
                require(r.error()==null,"MAME: "+r.error());int[] p=pads(frames);r.offerInputs(p[0],p[1],p[2],p[3]);
                var f=r.pollFrame();if(f!=null){frames++;require(f.abgr().length==f.width()*f.height(),"Video geometry");pictures.add(Arrays.hashCode(f.abgr()));audio+=f.pcm48k().length;
                    for(short sample:f.pcm48k())if(sample!=0)nonzero++;
                    if(frames==1){metric("width",f.width());metric("height",f.height());metric("rotation",f.rotation());metric("aspect",f.displayAspect());}
                }Thread.sleep(5);
            }
            metric("mediaFrames",frames);metric("differentPictures",pictures.size());metric("pcmShorts",audio);metric("nonzeroPcmShorts",nonzero);
            require(frames==600&&pictures.size()>2&&audio>0,"MAME output incomplete");metric("mediaSmokePassed",true);
        }finally{
            r.close();long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);while(!r.isTerminated()&&System.nanoTime()<deadline)Thread.sleep(10);
            metric("mediaTerminated",r.isTerminated());require(r.isTerminated(),"Native close unconfirmed");
        }
    }
    public static void main(String[] args)throws Exception{
        Path root=Path.of(args[0]).toAbsolutePath(),rom=Path.of(args[2]).toAbsolutePath();Files.createDirectories(root.resolve("instance"));RuntimeWorkspace.configure(root.resolve("instance"));
        try{if(args[1].equals("media"))media(root,rom);else core(root,rom,args[1].equals("diagnostic-options"));metric("probeCompleted",true);}
        catch(Throwable t){metric("failureType",t.getClass().getSimpleName());metric("failureBase64",Base64.getEncoder().encodeToString(String.valueOf(t.getMessage()).getBytes(java.nio.charset.StandardCharsets.UTF_8)));throw t;}
        finally{metric("freeNativeSlots",NativeLibretroBridge.availableSlots());}
    }
}
