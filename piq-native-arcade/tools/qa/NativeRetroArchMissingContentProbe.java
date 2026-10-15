// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.nativearcade.bridge;

import cn.piq.fcarcade.netplay.NetplayProcess;
import cn.piq.fcarcade.retroarch.RetroArchSession;
import cn.piq.retro.libretro.LibretroContentFiles;
import cn.piq.retro.storage.RuntimeWorkspace;
import java.io.InputStream;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/** One explicitly requested missing-content rejection in an isolated Java 21 JVM.
 * javac -encoding UTF-8 -cp "<FC.jar>;<Native.jar>" -d <classes> NativeRetroArchMissingContentProbe.java
 * java -cp "<classes>;<FC.jar>;<Native.jar>" cn.piq.nativearcade.bridge.NativeRetroArchMissingContentProbe
 *      <NEW output directory> <existing original driver.zip>
 * Use an external 180-second watchdog. The original ROM and same-directory declared BIOS are read only;
 * the production adapter owns temporary staging. No replacement DLL, download, JNI or JNA is used.
 * Output logs/identities are PRIVATE diagnostic evidence, not release assets. No ROM is retained as
 * an evidence artifact; production staging is removed only by its owning session after child exit.
 * PASS means accurate rejection and actual child/workspace cleanup, NOT a playable game or graceful
 * retro_deinit, Minecraft, Netplay, save compatibility, long-session stability or release acceptance.
 */
public final class NativeRetroArchMissingContentProbe {
    private static final String RUNNER_SHA="1335c34b04d8c3e55afe010a421ff373d938929ec196a6e93a9657972fe034e1";
    private static final String CORE_SHA="00d1a8319378dc94f50cc6610a9947217bdf23f05f70d2fc4d5665357a50b603";
    private static final String RUNTIME_SHA="7344daed05388589e9bd691ed1d30c568c374da4b8b6a12e1502185948c03cd4";
    private static final long CORE_BYTES=309413888L, RUNTIME_BYTES=1659392L;
    private static int checks;
    private static Path root,sessions;
    private static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);checks++;}
    private static void metric(String name,Object value){System.out.println("METRIC\t"+name+"\t"+value);System.out.flush();}
    private static String hash(InputStream in,long limit)throws Exception{
        var digest=MessageDigest.getInstance("SHA-256");long total=0;
        byte[] buffer=new byte[65536];
        for(int n;(n=in.read(buffer))!=-1;){total+=n;check(total<=limit,"Hash byte budget");digest.update(buffer,0,n);}
        return HexFormat.of().formatHex(digest.digest());
    }
    private static String hash(Path path,long limit)throws Exception{
        try(var in=Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS)){return hash(in,limit);}
    }
    private static void resource(Class<?> owner,String name,String expected,long limit)throws Exception{
        try(var in=owner.getResourceAsStream(name)){
            check(in!=null,"Missing fixed resource: "+name);
            check(hash(in,limit).equals(expected),"Fixed resource digest: "+name);
        }
    }
    private record Source(Path path,long size,FileTime modified,Object key,String sha,long limit){
        static Source capture(Path path,long limit)throws Exception{
            var before=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
            check(before.isRegularFile()&&!before.isSymbolicLink()&&!before.isOther()
                    &&path.toRealPath().equals(path),"Original must be an ordinary non-link file");
            check(before.size()>0&&before.size()<=limit,"Original content byte budget");
            var result=new Source(path,before.size(),before.lastModifiedTime(),before.fileKey(),hash(path,limit),limit);
            result.verify();return result;
        }
        void verify()throws Exception{
            var now=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
            check(now.isRegularFile()&&!now.isSymbolicLink()&&!now.isOther()&&path.toRealPath().equals(path)
                    &&now.size()==size&&now.lastModifiedTime().equals(modified)&&Objects.equals(now.fileKey(),key),
                    "Original metadata changed: "+path.getFileName());
            check(hash(path,limit).equals(sha),"Original SHA changed: "+path.getFileName());
            var after=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
            check(after.isRegularFile()&&!after.isSymbolicLink()&&!after.isOther()&&after.size()==size
                    &&after.lastModifiedTime().equals(modified)&&Objects.equals(after.fileKey(),key),
                    "Original changed while checking: "+path.getFileName());
        }
        String evidence(){return path.getFileName()+"\t"+size+"\t"+modified+"\t"+sha+"\n";}
    }
    private static Set<Long> children(){
        try(var stream=ProcessHandle.current().children()){
            var ids=new HashSet<Long>();stream.filter(ProcessHandle::isAlive).forEach(p->ids.add(p.pid()));return ids;
        }
    }
    private static List<Path> workspaces()throws Exception{
        if(!Files.exists(sessions,LinkOption.NOFOLLOW_LINKS))return List.of();
        try(var paths=Files.list(sessions)){return paths.filter(p->p.getFileName().toString().startsWith("netplay-")).toList();}
        catch(NoSuchFileException retired){return List.of();}
    }
    /** Marker identity must match an actually observed live child of this JVM; no native calls. */
    private static final class Ownership {
        private record ChildIdentity(long pid,Instant started,long parentPid) {}
        private final Map<ChildIdentity,Instant> observedChildren=new ConcurrentHashMap<>();
        private final long ownerPid=ProcessHandle.current().pid();
        private final Thread observer=new Thread(this::observeChildren,"missing-content-child-observer");
        private volatile boolean stopRequested,observerStopped;
        private volatile Throwable observerFailure;
        Path directory;Long pid;Instant started;boolean parentVerified;
        void start(){observer.setDaemon(true);observer.start();}
        private void observeChildren(){
            // Windows ProcessHandle enumeration/parent queries may be slow. They must never block
            // marker capture, nor may the marker alone stand in for an actual live OS identity.
            try{
                while(!stopRequested){
                    try(var children=ProcessHandle.current().children()){
                        for(var actual:children.toList()){
                            if(!actual.isAlive())continue;
                            var start=actual.info().startInstant();var parent=actual.parent();
                            if(start.isEmpty()||parent.isEmpty()||parent.get().pid()!=ownerPid||!actual.isAlive())continue;
                            var identity=new ChildIdentity(actual.pid(),start.get(),parent.get().pid());
                            if(!observedChildren.containsKey(identity)){
                                if(observedChildren.size()>=16)throw new AssertionError("Bounded observed child identities");
                                observedChildren.put(identity,Instant.now());
                            }
                        }
                    }
                    if(!stopRequested)Thread.sleep(1);
                }
            }catch(InterruptedException interrupted){if(!stopRequested)observerFailure=interrupted;}
            catch(Exception|Error failure){observerFailure=failure;}
            finally{observerStopped=true;}
        }
        void stopAndJoin()throws InterruptedException{
            stopRequested=true;observer.interrupt();observer.join(TimeUnit.SECONDS.toMillis(10));
        }
        void matchIdentity(){
            parentVerified=pid!=null&&started!=null&&observedChildren.containsKey(new ChildIdentity(pid,started,ownerPid));
        }
        void observe()throws Exception{
            // Only file I/O and a non-blocking identity-map lookup on the main observation loop.
            matchIdentity();
            var live=workspaces();check(live.size()<=1,"At most one isolated workspace");
            if(live.isEmpty())return;
            if(directory==null){directory=live.getFirst();metric("workspace",directory);}
            check(directory.equals(live.getFirst()),"Workspace identity unchanged");
            Path marker=directory.resolve("child");
            if(pid==null&&Files.isRegularFile(marker,LinkOption.NOFOLLOW_LINKS)){
                List<String> lines;
                try{lines=Files.readAllLines(marker);}catch(NoSuchFileException retired){return;}
                if(lines.size()!=2||lines.get(1).equals("unknown"))return;
                try{
                    long candidate=Long.parseLong(lines.get(0));Instant identity=Instant.parse(lines.get(1));
                    check(candidate>0,"Positive child PID");pid=candidate;started=identity;
                    metric("childPid",pid);metric("childStarted",started);
                }catch(NumberFormatException|java.time.format.DateTimeParseException partial){return;}
            }
            matchIdentity();
        }
        void verifyClosed()throws Exception{
            check(observerStopped&&!observer.isAlive(),"Live-child observer must stop within ten seconds");
            check(observerFailure==null,"Live-child observer failed: "+observerFailure);
            check(directory!=null&&pid!=null&&started!=null&&parentVerified,"Real child ownership must be observed, not inferred");
            var process=ProcessHandle.of(pid);
            check(process.isEmpty()||!process.get().isAlive()
                    ||process.get().info().startInstant().filter(time->!time.equals(started)).isPresent(),"Exact child exited");
            check(Files.notExists(directory,LinkOption.NOFOLLOW_LINKS),"Owned workspace actually removed");
            check(workspaces().isEmpty(),"No workspace remains after terminated success");
        }
        String evidence(){return "directory="+directory+"\npid="+pid+"\nstarted="+started+"\nparentVerified="+parentVerified
                +"\nobserverStopped="+observerStopped+"\nobserverAlive="+observer.isAlive()+"\nobserverFailure="+observerFailure
                +"\nactuallyObservedLiveChildren="+observedChildren+"\n";}
    }
    private static void trace(NativeRetroArchMediaSession media,String name)throws Exception{
        Files.writeString(root.resolve(name),media.failureDiagnostic(),StandardOpenOption.CREATE_NEW);
    }
    private static void run(Path original)throws Exception{
        check(workspaces().isEmpty(),"No earlier isolated workspace");
        var before=children();var owned=new Ownership();NativeRetroArchMediaSession media=null;
        Throwable failed=null;
        try{
            owned.start();media=new NativeRetroArchMediaSession(original);
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(90);
            while(!media.terminated().isDone()){
                owned.observe();
                check(!media.isReady()&&media.pollFrame()==null,"Expected missing content produced playable media");
                check(System.nanoTime()<deadline,"Missing-content rejection timed out");Thread.sleep(1);
            }
            media.terminated().get(1,TimeUnit.SECONDS);
            check(!media.isReady()&&media.pollFrame()==null,"Rejected adapter retained presentation");
            String error=media.error();String diagnostic=media.failureDiagnostic();
            trace(media,"native-failure.log");
            check(error!=null&&error.contains("MAME 游戏数据缺失"),
                    "Expected actionable missing-content error, not a generic process exit: "+error);
            check(diagnostic.contains("NOT FOUND")&&diagnostic.contains("Required files are missing"),
                    "Original MAME missing-file evidence must survive termination");
            Files.writeString(root.resolve("player-error.txt"),error+"\n",StandardOpenOption.CREATE_NEW);
            metric("error",error);metric("expectedMissingContentRejected",true);
        }catch(Exception|Error failure){
            failed=failure;if(media!=null)try{trace(media,"probe-failure-native.log");}catch(Exception|Error evidence){failure.addSuppressed(evidence);}throw failure;
        }finally{
            try{
                try{
                    if(media!=null){
                        long began=System.nanoTime();media.close();media.close();
                        check(System.nanoTime()-began<TimeUnit.SECONDS.toNanos(1),"Close request exceeded one-second observation budget");
                        media.terminated().get(45,TimeUnit.SECONDS);
                        check(!media.isReady()&&media.pollFrame()==null,"Closed adapter retained presentation");
                    }
                }finally{
                    try{owned.stopAndJoin();}
                    finally{
                        owned.matchIdentity();
                        Files.writeString(root.resolve("ownership.txt"),owned.evidence(),StandardOpenOption.CREATE_NEW);
                    }
                }
                owned.verifyClosed();var after=children();after.removeAll(before);
                check(after.isEmpty(),"Probe-owned child leaked: "+after);
                metric("terminatedAndCleaned",true);metric("gracefulNativeDeinitProven",false);
            }catch(Exception|Error cleanup){if(failed!=null)failed.addSuppressed(cleanup);else throw cleanup;}
        }
    }
    public static void main(String[] args)throws Exception{
        if(args.length!=2)throw new IllegalArgumentException("Expected NEW output directory and existing original driver.zip");
        Path original=Path.of(args[1]).toAbsolutePath().normalize();
        var config=NativeRetroArchMediaSession.config(original.getFileName().toString());
        root=Path.of(args[0]).toAbsolutePath().normalize();
        check(!root.startsWith(original.getParent())&&!original.startsWith(root),"Evidence directory must be separate from original ROM directory");
        var sources=new ArrayList<Source>();sources.add(Source.capture(original,LibretroContentFiles.MAX_MAIN));
        for(String name:NativeRomStaging.AUXILIARY_NAMES){
            Path auxiliary=original.getParent().resolve(name);
            if(!auxiliary.equals(original)&&Files.exists(auxiliary,LinkOption.NOFOLLOW_LINKS))sources.add(Source.capture(auxiliary,16L*1024*1024));
        }
        Files.createDirectory(root);Throwable failed=null;
        try{
            Files.createDirectory(root.resolve("instance"));RuntimeWorkspace.configure(root.resolve("instance"));
            sessions=root.resolve("instance/game-console/runtime-sessions");
            check(config.profile().jni()==null&&config.core().sha256().equals(CORE_SHA)&&config.core().maxBytes()==CORE_BYTES,"Fixed non-JNI lifecycle profile");
            check(config.dependencies().size()==1&&config.dependencies().getFirst().artifact().sha256().equals(RUNTIME_SHA)
                    &&config.dependencies().getFirst().bytes()==RUNTIME_BYTES,"Fixed companion runtime declaration");
            resource(NetplayProcess.class,"/core/netplay/piq-retroarch.exe",RUNNER_SHA,32L*1024*1024);
            resource(NativeRetroArchMediaSession.class,config.core().resource(),CORE_SHA,CORE_BYTES);
            var dependency=config.dependencies().getFirst();
            resource(dependency.owner(),dependency.artifact().resource(),RUNTIME_SHA,RUNTIME_BYTES);
            StringBuilder originals=new StringBuilder("name\tbytes\tmtime\tSHA256\n");
            for(Source source:sources)originals.append(source.evidence());
            Files.writeString(root.resolve("original-identities.tsv"),originals,StandardOpenOption.CREATE_NEW);
            for(Class<?> type:List.of(NativeRetroArchMediaSession.class,NetplayProcess.class,RetroArchSession.class,RuntimeWorkspace.class))
                metric("classOrigin."+type.getSimpleName(),type.getProtectionDomain().getCodeSource().getLocation());
            run(original);
        }catch(Exception|Error failure){failed=failure;throw failure;}
        finally{
            try{for(Source source:sources)source.verify();metric("originalsUnchanged",true);}
            catch(Exception|Error changed){if(failed!=null)failed.addSuppressed(changed);else{failed=changed;throw changed;}}
            finally{if(failed!=null)Files.writeString(root.resolve("failure.txt"),failed.toString()+"\n"+Arrays.toString(failed.getSuppressed()),StandardOpenOption.CREATE_NEW);}
        }
        Files.writeString(root.resolve("result.txt"),"PASS checks="+checks+"\nOne real fixed-MAME missing-content rejection; actionable error, original native evidence, exact child exit, workspace cleanup and unchanged input identities confirmed.\nNot playable-game, Minecraft/Netplay, saves, graceful native teardown, long-run or release acceptance. Private logs/identities: do not distribute.\n",StandardOpenOption.CREATE_NEW);
        metric("PASS",checks);
    }
}
