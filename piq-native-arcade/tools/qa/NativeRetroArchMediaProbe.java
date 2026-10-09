// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.nativearcade.bridge;

import cn.piq.fcarcade.cabinet.CabinetFrame;
import cn.piq.fcarcade.netplay.NetplayProcess;
import cn.piq.fcarcade.retroarch.RetroArchSession;
import cn.piq.retro.storage.RuntimeWorkspace;
import java.io.InputStream;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Explicit isolated-JVM probe, never an automatically executed test or a shipped class.
 * Compile/run with Java 21 and the freshly built FC and Native JARs, including fixed resources:
 * javac -encoding UTF-8 -cp <FC.jar;Native.jar> -d <probe-classes> NativeRetroArchMediaProbe.java
 * java -cp <probe-classes;FC.jar;Native.jar> cn.piq.nativearcade.bridge.NativeRetroArchMediaProbe
 *      <NEW output directory> <existing original invaders.zip>
 * Use an external 420-second watchdog. No replacement DLL, JNI bridge, observer or JNA is needed.
 * Fixture source: tools/make_diagnostic_rom.py and native-diagnostic-original-manifest.json;
 * that manifest traces the original 26-byte 8080 firmware to piq-native-arcade-poc/probe.py.
 * Only the exact original ZIP is accepted. It is not Space Invaders game code.
 * This proves process MEDIA plumbing, not Minecraft/networking, four-player commercial gameplay,
 * save/restore, graceful retro_deinit, long-session stability or release acceptance.
 */
public final class NativeRetroArchMediaProbe {
    private static final String ORIGINAL_SHA="10e62bbacbf6e79efb6db6f7a65ea1edfb65653eab60b41e80dbcb4673a5276c";
    private static final String RUNNER_SHA="1335c34b04d8c3e55afe010a421ff373d938929ec196a6e93a9657972fe034e1";
    private static final String CORE_SHA="00d1a8319378dc94f50cc6610a9947217bdf23f05f70d2fc4d5665357a50b603";
    private static final String RUNTIME_SHA="7344daed05388589e9bd691ed1d30c568c374da4b8b6a12e1502185948c03cd4";
    private static final long CORE_BYTES=309413888L, RUNTIME_BYTES=1659392L;
    private static int checks;
    private static Path root,sessions;
    private static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);checks++;}
    private static void metric(String name,Object value){System.out.println("METRIC\t"+name+"\t"+value);System.out.flush();}
    private static String hash(Path path,long limit)throws Exception{
        var digest=MessageDigest.getInstance("SHA-256");long total=0;
        try(InputStream in=Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS)){
            byte[] buffer=new byte[65536];for(int n;(n=in.read(buffer))!=-1;){total+=n;check(total<=limit,"Hash byte budget: "+path.getFileName());digest.update(buffer,0,n);}
        }
        return HexFormat.of().formatHex(digest.digest());
    }
    private static Set<Long> children(){
        try(var stream=ProcessHandle.current().children()){
            var ids=new HashSet<Long>();stream.filter(ProcessHandle::isAlive).forEach(p->ids.add(p.pid()));return ids;
        }
    }
    private static List<Path> workspaces()throws Exception{
        if(!Files.exists(sessions,LinkOption.NOFOLLOW_LINKS))return List.of();
        try(var paths=Files.list(sessions)){return paths.filter(p->p.getFileName().toString().startsWith("netplay-")).toList();}
    }
    private static final class Ownership {
        Path directory;Long pid;Instant started;
        void observe()throws Exception{
            var live=workspaces();check(live.size()<=1,"At most one isolated workspace");
            if(live.isEmpty())return;
            if(directory==null){directory=live.getFirst();metric("workspace",directory);}
            check(directory.equals(live.getFirst()),"Workspace identity unchanged");
            Path marker=directory.resolve("child");
            if(pid==null&&Files.isRegularFile(marker,LinkOption.NOFOLLOW_LINKS)){
                List<String> lines;
                try{lines=Files.readAllLines(marker);}catch(NoSuchFileException retired){return;}
                // The owner can be between creating and finishing its marker; retry while starting.
                if(lines.size()!=2||lines.get(1).equals("unknown"))return;
                try{
                    long candidate=Long.parseLong(lines.get(0));Instant identity=Instant.parse(lines.get(1));
                    pid=candidate;started=identity;metric("childPid",pid);
                }catch(NumberFormatException|java.time.format.DateTimeParseException partial){/* Retry an unfinished marker. */}
            }
        }
        void verifyRunning()throws Exception{
            observe();check(directory!=null&&pid!=null,"Real child marker captured");
            var process=ProcessHandle.of(pid).orElseThrow();
            check(process.isAlive()&&process.info().startInstant().orElseThrow().equals(started),"Exact owned child alive");
            check(ProcessHandle.current().pid()==process.parent().orElseThrow().pid(),"Native process belongs to probe JVM");
            check(Files.size(directory.resolve("core.dll"))==CORE_BYTES,"Actual lifecycle core byte count");
            check(hash(directory.resolve("core.dll"),CORE_BYTES).equals(CORE_SHA),"Actual lifecycle core pin");
            check(Files.size(directory.resolve("libc++.dll"))==RUNTIME_BYTES,"Actual fixed libc++ byte count");
            check(hash(directory.resolve("libc++.dll"),RUNTIME_BYTES).equals(RUNTIME_SHA),"Actual fixed libc++ pin");
            check(hash(directory.resolve("retroarch.exe"),32L*1024*1024).equals(RUNNER_SHA),"Actual RetroArch executable pin");
            String config=Files.readString(directory.resolve("retroarch.cfg"));
            check(!config.contains("netplay_"),"Actual LOCAL config excludes Netplay");
            check(config.contains("network_cmd_enable = \"false\""),"Native command server disabled");
        }
        void verifyClosed()throws Exception{
            if(pid!=null){
                var process=ProcessHandle.of(pid);
                check(process.isEmpty()||!process.get().isAlive()
                        ||!process.get().info().startInstant().orElse(Instant.MIN).equals(started),"Exact child exited");
            }
            if(directory!=null)check(Files.notExists(directory,LinkOption.NOFOLLOW_LINKS),"Owned workspace actually removed");
            check(workspaces().isEmpty(),"No workspace remains after terminated success");
        }
    }
    private static void frame(CabinetFrame frame){
        check(frame.abgr().length==(long)frame.width()*frame.height(),"Real ABGR geometry");
        check(Float.isFinite(frame.displayAspect())&&frame.displayAspect()>.1f&&frame.displayAspect()<10f
                &&frame.rotation()>=0&&frame.rotation()<=3,"Raw DAR and clockwise rotation bounded");
        check((frame.pcm48k().length&1)==0&&frame.pcm48k().length<=32768,"48k stereo sample boundary");
        check(Arrays.stream(frame.abgr()).allMatch(p->(p>>>24)==255),"Real pixels opaque");
    }
    private static void healthy(NativeRetroArchMediaSession media){
        check(media.error()==null&&!media.terminated().isDone(),"Media unexpectedly ended: "+media.error()+" / "+media.diagnostics());
    }
    /** QA-only observation of the facade already owned by the actual adapter; never calls native code. */
    private static void trace(NativeRetroArchMediaSession media,String name,String point)throws Exception{
        var field=NativeRetroArchMediaSession.class.getDeclaredField("session");field.setAccessible(true);
        var session=(RetroArchSession)field.get(media);
        Files.writeString(root.resolve(name+"-"+point+"-native-tail.log"),session.diagnostic(),StandardOpenOption.CREATE_NEW);
    }
    private static void capture(NativeRetroArchMediaSession media,String name,int number,CabinetFrame frame)throws Exception{
        var image=new BufferedImage(frame.width(),frame.height(),BufferedImage.TYPE_INT_ARGB);
        int[] argb=new int[frame.abgr().length];
        for(int i=0;i<argb.length;i++){int p=frame.abgr()[i];argb[i]=(p&0xff00ff00)|((p&255)<<16)|((p>>>16)&255);}
        image.setRGB(0,0,frame.width(),frame.height(),argb,0,frame.width());
        check(ImageIO.write(image,"png",root.resolve(name+"-frame"+number+"-raw.png").toFile()),"PNG writer available");
        trace(media,name,"frame"+number);
    }
    private static void close(NativeRetroArchMediaSession media,Ownership owned,Set<Long> before,String name)throws Exception{
        long start=System.nanoTime();media.close();media.close();
        long requested=System.nanoTime()-start;metric(name+".closeRequestMillis",TimeUnit.NANOSECONDS.toMillis(requested));
        check(requested<TimeUnit.SECONDS.toNanos(1),"Close request exceeded one-second observation budget");
        media.terminated().get(45,TimeUnit.SECONDS);
        check(!media.isReady()&&media.pollFrame()==null,"Closed adapter retained presentation");
        owned.verifyClosed();var after=children();after.removeAll(before);
        check(after.isEmpty(),"Probe-owned child leaked: "+after);metric(name+".terminatedAndCleaned",true);
        metric(name+".gracefulNativeDeinitProven",false);
    }
    private static void run(Path fixture,int cycle,boolean rejected)throws Exception{
        String name=rejected?"badZip":"cycle"+cycle;metric("stage",name+"-start");
        check(workspaces().isEmpty(),"No earlier workspace before new session");
        var before=children();var owned=new Ownership();var media=new NativeRetroArchMediaSession(fixture);
        Throwable failed=null;
        try{
            trace(media,name,"start");
            check(media.maxPlayers()==4,"Four-port adapter declaration");
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(90);
            if(rejected){
                while(!media.terminated().isDone()){
                    owned.observe();check(!media.isReady()&&media.pollFrame()==null,"Malformed ZIP produced playable media");
                    check(System.nanoTime()<deadline,"Malformed ZIP rejection timed out");Thread.sleep(5);
                }
                media.terminated().get(1,TimeUnit.SECONDS);
                check(media.error()!=null&&!media.isReady(),"Malformed ZIP was not rejected");
                check(owned.directory!=null&&(media.error().contains("原生端启动退出")
                        ||media.error().contains("核心输出已结束")),"Expected native load rejection, not unrelated Java setup failure");
                metric(name+".error",media.error());
            }else{
                while(!media.isReady()){
                    owned.observe();healthy(media);check(System.nanoTime()<deadline,"Real adapter readiness timed out");Thread.sleep(5);
                }
                owned.verifyRunning();
                int frames=0;long samples=0,nonzero=0;var pictures=new HashSet<Integer>();String previousGeometry="";
                deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
                while(frames<120){
                    healthy(media);check(System.nanoTime()<deadline,"Real frame collection timed out");
                    // The original firmware observes invaders IN1, not four-player commercial hardware.
                    media.offerInputs(frames>=30&&frames<60?1:0,0,0,0);
                    var current=media.pollFrame();
                    if(current!=null){
                        frame(current);pictures.add(Arrays.hashCode(current.abgr()));samples+=current.pcm48k().length;
                        for(short sample:current.pcm48k())if(sample!=0)nonzero++;
                        String geometry=current.width()+"x"+current.height()+" rawDAR="+current.displayAspect()+" clockwiseRotation="+current.rotation();
                        if(!geometry.equals(previousGeometry)){
                            metric(name+".geometryAtFrame"+(frames+1),geometry);previousGeometry=geometry;
                            Files.writeString(root.resolve(name+"-geometry.log"),"frame="+(frames+1)+" "+geometry+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);
                        }
                        if(frames==0||frames==29||frames==119){
                            capture(media,name,frames+1,current);
                            metric(name+".pcmAtFrame"+(frames+1),"shorts="+samples+" nonzero="+nonzero+" pictures="+pictures.size());
                        }
                        if(frames==0){metric(name+".width",current.width());metric(name+".height",current.height());
                            metric(name+".rawDisplayAspect",current.displayAspect());metric(name+".clockwiseRotation",current.rotation());}
                        frames++;
                    }
                    Thread.sleep(5);
                }
                media.clearInput();check(samples>0&&nonzero>0,"Original oscillator did not produce actual PCM");
                check(pictures.size()>1,"Original input diagnostic picture never changed");
                metric(name+".frames",frames);metric(name+".pcm48kShorts",samples);metric(name+".nonzeroPcmShorts",nonzero);
                metric(name+".differentPictures",pictures.size());healthy(media);
            }
        }catch(Exception|Error failure){
            failed=failure;try{trace(media,name,"failure");}catch(Exception|Error evidence){failure.addSuppressed(evidence);}throw failure;
        }
        finally{
            try{close(media,owned,before,name);}catch(Exception|Error cleanup){if(failed!=null)failed.addSuppressed(cleanup);else throw cleanup;}
        }
        if(!rejected)check(media.error()==null,"Successful case acquired a terminal error: "+media.error());
        metric("stage",name+"-complete");
    }
    public static void main(String[] args)throws Exception{
        if(args.length!=2)throw new IllegalArgumentException("Expected NEW output directory and existing original invaders.zip");
        Path original=Path.of(args[1]).toAbsolutePath().normalize();
        check(original.getFileName().toString().equals("invaders.zip")&&Files.isRegularFile(original,LinkOption.NOFOLLOW_LINKS),"Original driver filename/regular file");
        check(hash(original,16384).equals(ORIGINAL_SHA),"Only the exact repository-original diagnostic ZIP is accepted");
        root=Path.of(args[0]).toAbsolutePath().normalize();Files.createDirectory(root);
        try{
            Files.createDirectory(root.resolve("instance"));RuntimeWorkspace.configure(root.resolve("instance"));
            sessions=root.resolve("instance/game-console/runtime-sessions");
            Path good=Files.createDirectory(root.resolve("original")).resolve("invaders.zip");Files.copy(original,good);
            Path bad=Files.createDirectory(root.resolve("malformed")).resolve("invaders.zip");
            byte[] invalid=new byte[64];byte[] notice="PIQ original malformed ZIP test".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            System.arraycopy(notice,0,invalid,0,notice.length);Files.write(bad,invalid,StandardOpenOption.CREATE_NEW);
            var config=NativeRetroArchMediaSession.config("invaders.zip");
            check(config.profile().jni()==null&&config.core().sha256().equals(CORE_SHA)&&config.core().maxBytes()==CORE_BYTES,"Fixed non-JNI lifecycle profile");
            check(config.dependencies().size()==1&&config.dependencies().getFirst().artifact().sha256().equals(RUNTIME_SHA),"Fixed companion runtime declaration");
            for(Class<?> type:List.of(NativeRetroArchMediaSession.class,NetplayProcess.class,RetroArchSession.class,RuntimeWorkspace.class))
                metric("classOrigin."+type.getSimpleName(),type.getProtectionDomain().getCodeSource().getLocation());
            metric("originalZipSha256",ORIGINAL_SHA);metric("originalFirmwareSha256","554c673e65d5957da0bc1df0ceb986cbac5500d407ae2d780feab4f8d2eed810");
            for(int cycle=1;cycle<=3;cycle++)run(good,cycle,false);
            run(bad,0,true);
            check(hash(original,16384).equals(ORIGINAL_SHA)&&hash(good,16384).equals(ORIGINAL_SHA),"Original and copied fixture unchanged");
            check(Arrays.equals(invalid,Files.readAllBytes(bad)),"Malformed fixture unchanged");
            Files.writeString(root.resolve("result.txt"),"PASS checks="+checks+"\nThree real MAME RetroArch adapter sessions and malformed ZIP rejection; child exit and workspace cleanup confirmed.\nOriginal diagnostic only. Not Minecraft/Netplay, four-player gameplay, saves, graceful core deinit, long-run stability or release acceptance.\n",StandardOpenOption.CREATE_NEW);
            metric("PASS",checks);
        }catch(Exception|Error failure){Files.writeString(root.resolve("failure.txt"),failure.toString(),StandardOpenOption.CREATE_NEW);throw failure;}
    }
}
