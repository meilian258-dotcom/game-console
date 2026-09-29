// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.gba.bridge;
import java.nio.file.*;
import java.util.*;

public final class GbaProcessProbe {
    static int assertions;
    static void check(boolean b,String s){assertions++;if(!b)throw new AssertionError(s);}
    static void until(java.util.function.BooleanSupplier predicate)throws Exception {long due=System.nanoTime()+8_000_000_000L;while(!predicate.getAsBoolean()){if(System.nanoTime()>due)throw new AssertionError("timeout");Thread.sleep(5);}}
    static GbaProcessSession.Frame awaitPixel(GbaProcessSession core,int pixel)throws Exception {
        long due=System.nanoTime()+8_000_000_000L;int consecutive=0;GbaProcessSession.Frame frame;
        while(System.nanoTime()<due){if(core.error()!=null)throw new AssertionError(core.error());frame=core.pollFrame();
            if(frame!=null){consecutive=frame.abgr()[0]==pixel?consecutive+1:0;if(consecutive>=2)return frame;}Thread.sleep(5);}
        throw new AssertionError("No acknowledged input frame");
    }
    public static void main(String[] args)throws Exception {
        Path runtime=Path.of(args[0]),rom=Path.of(args[1]),saves=Path.of(args[2]);String hash=args[3];
        if(args.length==5){Path expected=Path.of(args[4]).toRealPath();for(Class<?> type:List.of(GbaProcessSession.class,GbaProcessSession.Frame.class,GbaSaveStore.class,GbaProtocol.class))check(Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath().equals(expected),"actual final addon origin "+type.getName());}
        try{new GbaProcessSession(null,rom,saves,hash);throw new AssertionError("null path");}catch(NullPointerException expected){check(!GbaProcessSession.active(),"invalid path cannot acquire global process slot");}
        GbaProcessSession session=new GbaProcessSession(runtime,rom,saves,hash);
        until(()->session.isReady()||session.error()!=null);check(session.isReady(),"real worker ready: "+session.error());
        try{new GbaProcessSession(runtime,rom,saves,hash);throw new AssertionError("double core");}catch(java.io.IOException expected){assertions++;}
        session.offerInput(1<<8|1);
        GbaProcessSession.Frame f=awaitPixel(session,0xff000018);check(f.abgr().length==38400,"real private pipe frame");check(f.abgr()[0]==0xff000018,"real input carried through pipe A+B");
        check(f.pcm48k().length>0&&f.pcm48k().length<=32768,"bounded audio mailbox");
        session.clearInput();f=awaitPixel(session,0xff000000);check(f.abgr()[0]==0xff000000,"force clear queued/held input");
        long start=System.nanoTime();session.close();check(System.nanoTime()-start<50_000_000L,"nonblocking UI close");until(()->!GbaProcessSession.active());check(session.error()==null,"clean saved exit: "+session.error());
        List<Path> files;try(var paths=Files.walk(saves)){files=paths.filter(p->p.getFileName().toString().equals("sram.bin")).toList();}
        check(files.size()==1,"one isolated ROM-key save");byte[] saved=Files.readAllBytes(files.getFirst());check(saved.length==32768&&saved[0]==3,"actual private worker SRAM persisted");
        GbaProcessSession reopened=new GbaProcessSession(runtime,rom,saves,hash);until(()->reopened.isReady()||reopened.error()!=null);check(reopened.isReady(),"restart with own save");
        reopened.offerInput(1<<8|1);awaitPixel(reopened,0xff000018);reopened.close();until(()->!GbaProcessSession.active());byte[] after=Files.readAllBytes(files.getFirst());check(Arrays.equals(saved,after),"saved cartridge data exact after restart old="+saved.length+" new="+after.length+" first="+after[0]+" error="+reopened.error());
        GbaProcessSession rejected=new GbaProcessSession(runtime,rom,saves,"0".repeat(64));until(()->!GbaProcessSession.active());check(rejected.error()!=null&&!rejected.isReady(),"wrong helper SHA rejects before start");
        GbaSaveStore store=new GbaSaveStore(saves.resolve("store-test"),"A".repeat(64));byte[] a=new byte[32768],b=new byte[32768];a[3]=1;b[3]=2;store.save(a);store.save(b);check(Arrays.equals(b,store.load()),"atomic store current");
        Path current=saves.resolve("store-test").resolve("A".repeat(64)).resolve("sram.bin");Files.write(current,new byte[]{5});check(Arrays.equals(a,store.load()),"corrupt current falls back to previous");check(Files.size(current)==1,"corrupt bytes preserved");
        store.save(b);check(Arrays.equals(b,store.load()),"recovered save remains writable after next auto-save");
        List<Path> corrupt;try(var paths=Files.list(current.getParent())){corrupt=paths.filter(p->p.getFileName().toString().startsWith("sram.corrupt-")).toList();}
        check(corrupt.size()==1&&Arrays.equals(new byte[]{5},Files.readAllBytes(corrupt.getFirst())),"exact damaged file quarantined without overwrite");
        check(Arrays.equals(a,Files.readAllBytes(current.resolveSibling("sram.previous.bin"))),"good recovery backup preserved");
        check(Arrays.equals(b,new GbaSaveStore(saves.resolve("store-test"),"A".repeat(64)).load()),"recovered save survives new store instance");
        Path redirect=saves.resolve("redirect-parent"),outside=saves.resolve("outside");Files.createDirectory(outside);
        Process junction=new ProcessBuilder("cmd.exe","/d","/c","mklink","/J",redirect.toString(),outside.toString()).redirectErrorStream(true).start();
        if(junction.waitFor()!=0)throw new AssertionError("Required real junction probe unavailable");
        try{new GbaSaveStore(redirect.resolve("nested"),"B".repeat(64));throw new AssertionError("junction accepted");}catch(java.io.IOException expected){check(!Files.exists(outside.resolve("nested")),"ancestor junction rejected before directory creation");}
        finally{Files.delete(redirect);}
        try{store.save(new byte[513]);throw new AssertionError("bad save accepted");}catch(java.io.IOException expected){assertions++;}
        System.out.println("{\"ok\":true,\"assertions\":"+assertions+",\"production_origin\":\""+(args.length==5?"final-jar-only":"fresh-standalone-compile")+"\",\"actual_child_process_protocol\":true,\"actual_save_restart\":true,\"minecraft_started\":false,\"user_rom_or_save_used\":false}");
    }
}
