package cn.piq.fcarcade.client.cabinet;

import cn.piq.fcarcade.cabinet.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.client.Minecraft;
import net.minecraft.network.Connection;

/** Explicit uploads and lease-bound downloads. Never blocks the Minecraft or render thread. */
public final class CabinetSharedGames {
    private static final Map<UUID,Transfer> ACTIVE=new ConcurrentHashMap<>();
    private static final Map<UUID,Resolved> RESOLVED=new ConcurrentHashMap<>();
    private static final Semaphore WORKER=new Semaphore(1);
    private record Resolved(Connection connection,CabinetGameManifest manifest){}
    private record Pending(CabinetGameNetwork.Command command,CompletableFuture<CabinetGameNetwork.Reply> result){}
    private static final class Transfer {
        final UUID id=UUID.randomUUID();final CabinetNetwork.Launch request;final Connection connection;
        final Map<Integer,Pending> pending=new ConcurrentHashMap<>();final AtomicBoolean closed=new AtomicBoolean();
        final long began=System.nanoTime();long nextSend,nextNotice,total,transferred;int sequence;
        Transfer(CabinetNetwork.Launch r,Connection c){request=r;connection=c;}
        void check()throws IOException{if(closed.get()||!connection.isConnected()||System.nanoTime()-began>300_000_000_000L)throw new IOException("游戏同步已取消或超时，请重新右键");}
        Pending submit(int operation,CabinetGameManifest manifest,int file,int offset,byte[] bytes,int weight)throws Exception{
            check();long now=System.nanoTime();long delay=nextSend-now;
            if(delay>0)TimeUnit.NANOSECONDS.sleep(Math.min(delay,100_000_000L));
            check();nextSend=Math.max(System.nanoTime(),nextSend)+(long)weight*1_000_000_000L/1_048_576;
            var command=new CabinetGameNetwork.Command(id,sequence++,operation,request.lease(),request.backend(),manifest,file,offset,bytes);
            var p=new Pending(command,new CompletableFuture<>());pending.put(command.sequence(),p);
            Minecraft.getInstance().execute(()->{
                var mc=Minecraft.getInstance();
                if(closed.get()||mc.getConnection()==null||mc.getConnection().getConnection()!=connection||!request.target().matches(mc.level)
                        ||mc.player==null||!mc.player.isAlive()||mc.player.isSpectator()){
                    p.result.completeExceptionally(new IOException("游戏同步已取消：连接或机柜已改变"));return;
                }
                if(!CabinetMediaSender.sendPayload(connection,command,bytes.length+2048,true))
                    p.result.completeExceptionally(new IOException("网络发送拥堵，请稍后重试；原游戏未被替换"));
            });
            return p;
        }
        CabinetGameNetwork.Reply await(Pending p)throws Exception{
            try{check();var r=p.result.get(20,TimeUnit.SECONDS);check();if(!r.success())throw new IOException(r.message());return r;}
            catch(ExecutionException e){if(e.getCause() instanceof Exception x)throw x;throw e;}
            finally{pending.remove(p.command.sequence());}
        }
        CabinetGameNetwork.Reply call(int operation,CabinetGameManifest manifest,int file,int offset,byte[] bytes)throws Exception{
            return await(submit(operation,manifest,file,offset,bytes,bytes.length+2048));
        }
        void notice(String message){
            Minecraft.getInstance().execute(()->{var mc=Minecraft.getInstance();if(!closed.get()&&mc.getConnection()!=null&&mc.getConnection().getConnection()==connection&&request.target().matches(mc.level))cn.piq.fcarcade.client.ui.DeviceNoticesClient.workflow(message);});
        }
        void progress(int bytes,boolean upload){transferred+=bytes;if(total<=0)return;long now=System.nanoTime();if(now<nextNotice)return;nextNotice=now+1_000_000_000L;
            notice((upload?"正在上传缺失游戏文件":"正在下载缺失机柜游戏")+"："+Math.min(100,100*transferred/total)+"% · 右键本机可取消");
        }
        synchronized void abort(){
            if(!closed.compareAndSet(false,true))return;
            RESOLVED.computeIfPresent(request.lease(),(key,value)->value.connection==connection?null:value);
            pending.values().forEach(p->p.result.completeExceptionally(new IOException("游戏同步已取消")));pending.clear();
            Minecraft.getInstance().execute(()->{var mc=Minecraft.getInstance();if(mc.getConnection()!=null&&mc.getConnection().getConnection()==connection)
                CabinetMediaSender.sendPayload(connection,new CabinetGameNetwork.Command(id,Math.min(sequence,20000),CabinetGameNetwork.CANCEL,request.lease(),request.backend(),null,0,0,new byte[0]),256,true);});
        }
    }
    private CabinetSharedGames(){}
    public static void register(){CabinetGameNetwork.setSink(CabinetSharedGames::reply);}
    private static void reply(Connection source,CabinetGameNetwork.Reply r){
        Transfer t=ACTIVE.get(r.transaction());if(t==null||t.connection!=source||t.closed.get())return;
        if(!r.success()&&r.sequence()==0){t.pending.values().forEach(p->{if(r.missingMask()==0)p.result.complete(r);else p.result.completeExceptionally(new IOException("服务器游戏回复包含非法缺失文件计划"));});return;}
        Pending p=t.pending.get(r.sequence());if(p==null)return;
        if(!CabinetGameClientPlan.validReply(r.success(),p.command.operation()==CabinetGameNetwork.OPEN&&p.command.manifest()!=null,
                p.command.manifest(),r.manifest(),r.missingMask())){
            p.result.completeExceptionally(new IOException("服务器游戏回复包含非法缺失文件计划"));return;
        }
        if(r.success()&&(r.manifest()!=null&&!r.manifest().backend().equals(t.request.backend().toString())
                ||p.command.operation()==CabinetGameNetwork.GET&&(r.file()!=p.command.file()||r.offset()!=p.command.offset())
                ||p.command.operation()!=CabinetGameNetwork.GET&&r.bytes().length!=0)){
            p.result.completeExceptionally(new IOException("服务器游戏回复与请求不匹配"));return;
        }
        p.result.complete(r);
    }
    public static void cancel(UUID lease){if(lease==null)return;ACTIVE.values().stream().filter(t->t.request.lease().equals(lease)).forEach(Transfer::abort);RESOLVED.remove(lease);}
    public static String resolvedHash(UUID lease){Resolved r=resolved(lease);return r==null?null:r.manifest.gameHash();}
    public static String resolvedContentId(UUID lease){Resolved r=resolved(lease);return r==null?null:r.manifest.contentId();}
    private static Resolved resolved(UUID lease){Resolved r=RESOLVED.get(lease);var mc=Minecraft.getInstance();return r!=null&&mc.getConnection()!=null&&mc.getConnection().getConnection()==r.connection?r:null;}
    public static Path resolve(CabinetNetwork.Launch request,Path chosen,boolean remember,CabinetBackend provider,CabinetRomBindings.Key key,Connection connection)throws Exception{
        if(Minecraft.getInstance().isSameThread())throw new IllegalStateException("Shared game IO must run off the client thread");
        if(!WORKER.tryAcquire())throw new IOException("上一项游戏同步尚未结束，请稍后重试");
        Transfer t=new Transfer(request,connection);ACTIVE.put(t.id,t);boolean complete=false;
        try{
            Path result;CabinetGameManifest manifest;
            if(remember){
                if(chosen==null)throw new IOException("请明确选择要上传的游戏");
                t.notice("正在校验本地游戏与共享缓存… · 右键本机可取消");
                CabinetGameSelection.validate(chosen,provider.romExtensions(),provider.romExcludedNames());
                List<Path> files=uploadFiles(chosen);var entries=new ArrayList<CabinetGameManifest.Entry>();for(Path file:files)entries.add(describe(t,file));
                manifest=new CabinetGameManifest(request.backend().toString(),entries);
                // Both media and local-sync launchers reuse this cache on ordinary reopen.
                // Complete local staging before OPEN: cancellation/cache failure cannot bind a new server game.
                Path root=cacheRoot();Path directory=CabinetGameClientPlan.cacheDirectory(root,key.context(),manifest);
                CabinetGameClientPlan.cacheSelected(files,directory,manifest,t.id,t::check,additional->checkCacheQuota(root,additional));
                t.notice("正在确认服务器已有游戏文件… · 右键本机可取消");
                var opened=t.call(CabinetGameNetwork.OPEN,manifest,0,0,new byte[0]);if(!manifest.equals(opened.manifest()))throw new IOException("服务器上传计划不一致");
                var plan=new CabinetGameClientPlan(manifest,opened.missingMask());t.total=plan.missingBytes();
                if(t.total==0)t.notice("服务器已复用全部游戏文件，正在验证机柜授权…");else t.progress(0,true);
                plan.transferMissing((index,entry)->upload(t,files.get(index),entry,index));
                CabinetGameClientPlan.verifySelected(files,manifest,t::check);
                result=chosen.toAbsolutePath().normalize();
            }else{
                t.notice("正在确认机柜游戏与本地缓存… · 右键本机可取消");
                var opened=t.call(CabinetGameNetwork.OPEN,null,0,0,new byte[0]);manifest=opened.manifest();
                if(manifest==null||!manifest.backend().equals(request.backend().toString()))throw new IOException("服务器尚未共享此机柜游戏");
                Path root=cacheRoot();Path directory=CabinetGameClientPlan.cacheDirectory(root,key.context(),manifest);
                var plan=CabinetGameClientPlan.inspectCache(directory,manifest,t::check);t.total=plan.missingBytes();
                if(t.total==0)t.notice("已复用本地全部游戏文件，正在验证机柜授权…");else t.progress(0,false);
                plan.transferMissing((index,entry)->download(t,root,directory,entry,index));
                if(CabinetGameClientPlan.inspectCache(directory,manifest,t::check).missingMask()!=0)throw new IOException("本地共享游戏缓存不完整");
                result=directory.resolve(manifest.files().getFirst().name());
                CabinetGameSelection.validate(result,provider.romExtensions(),provider.romExcludedNames());
            }
            var ended=t.call(CabinetGameNetwork.END,null,0,0,new byte[0]);if(!manifest.equals(ended.manifest()))throw new IOException("服务器最终游戏授权不一致");
            synchronized(t){t.check();RESOLVED.put(request.lease(),new Resolved(connection,manifest));complete=true;}
            // The shared manifest, not the old per-user path binding, is the source of truth.
            return result;
        }finally{ACTIVE.remove(t.id,t);if(!complete)t.abort();else t.closed.set(true);WORKER.release();}
    }
    /** Server execution needs an exact OPEN/END grant, not a redundant client ROM download. */
    public static void authorizeHosted(CabinetNetwork.Launch request,Connection connection)throws Exception{
        if(Minecraft.getInstance().isSameThread())throw new IllegalStateException("Shared game authorization must run off the client thread");
        if(!WORKER.tryAcquire())throw new IOException("上一项游戏同步尚未结束，请稍后重试");
        Transfer t=new Transfer(request,connection);ACTIVE.put(t.id,t);boolean complete=false;
        try{
            t.notice("正在确认服务器游戏与机柜授权… · 右键本机可取消");
            var opened=t.call(CabinetGameNetwork.OPEN,null,0,0,new byte[0]);var manifest=opened.manifest();
            if(manifest==null||!manifest.backend().equals(request.backend().toString()))throw new IOException("服务器尚未共享此机柜游戏");
            var ended=t.call(CabinetGameNetwork.END,null,0,0,new byte[0]);
            if(!manifest.equals(ended.manifest()))throw new IOException("服务器最终游戏授权不一致");
            synchronized(t){t.check();RESOLVED.put(request.lease(),new Resolved(connection,manifest));complete=true;}
        }finally{ACTIVE.remove(t.id,t);if(!complete)t.abort();else t.closed.set(true);WORKER.release();}
    }
    private static Path cacheRoot()throws IOException{
        Path root=cn.piq.retro.storage.ConsoleStorage.root(Minecraft.getInstance().gameDirectory.toPath()).resolve("piq-cabinet/shared-games").toAbsolutePath().normalize();
        CabinetGameStore.directory(root);return root;
    }
    private static List<Path> uploadFiles(Path main)throws IOException{
        Path absolute=main.toAbsolutePath().normalize();var files=new ArrayList<Path>();files.add(absolute);
        if(absolute.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip"))for(String name:new TreeSet<>(CabinetGameManifest.BIOS)){
            Path bios=absolute.getParent().resolve(name);if(Files.exists(bios,LinkOption.NOFOLLOW_LINKS)){CabinetGameStore.regular(bios);files.add(bios);}
        }
        return files;
    }
    private static CabinetGameManifest.Entry describe(Transfer t,Path path)throws IOException{
        t.check();
        var attrs=CabinetGameStore.regular(path);if(attrs.size()<1||attrs.size()>CabinetGameManifest.MAX_FILE)throw new IOException("共享游戏单个文件上限为 64 MiB");
        MessageDigest hash;try{hash=MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
        try(var in=Files.newByteChannel(path,Set.of(StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS))){ByteBuffer b=ByteBuffer.allocate(32768);long total=0;for(int n;(n=in.read(b))!=-1;){t.check();if(n==0)continue;total+=n;if(total>attrs.size())throw new IOException("游戏文件在读取时变化");b.flip();hash.update(b);b.clear();}if(total!=attrs.size())throw new IOException("游戏文件被截断");}
        var entry=new CabinetGameManifest.Entry(path.getFileName().toString(),HexFormat.of().formatHex(hash.digest()),(int)attrs.size());CabinetGameStore.verify(path,entry);t.check();return entry;
    }
    private static void upload(Transfer t,Path path,CabinetGameManifest.Entry entry,int index)throws Exception{
        CabinetGameStore.verify(path,entry);
        try(var in=Files.newByteChannel(path,Set.of(StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS))){
            int offset=0;while(offset<entry.size()){
                var batch=new ArrayList<Pending>(4);
                for(int count=0;count<4&&offset<entry.size();count++){
                    byte[] bytes=read(in,Math.min(CabinetGameManifest.CHUNK,entry.size()-offset));
                    batch.add(t.submit(CabinetGameNetwork.PUT,null,index,offset,bytes,bytes.length+2048));offset+=bytes.length;
                }
                for(Pending pending:batch){t.await(pending);t.progress(pending.command.bytes().length,true);}
            }
        }
        CabinetGameStore.verify(path,entry);
    }
    private static void download(Transfer t,Path root,Path directory,CabinetGameManifest.Entry entry,int index)throws Exception{
        Path target=directory.resolve(entry.name());
        if(Files.exists(target,LinkOption.NOFOLLOW_LINKS)){CabinetGameStore.verify(target,entry);return;}
        checkCacheQuota(root,entry.size());Path temporary=directory.resolve("download-"+t.id+"-"+index+".part");
        try{
            try(var out=Files.newByteChannel(temporary,Set.of(StandardOpenOption.WRITE,StandardOpenOption.CREATE_NEW,LinkOption.NOFOLLOW_LINKS))){
                int offset=0;while(offset<entry.size()){
                    var batch=new ArrayList<Pending>(4);
                    for(int count=0;count<4&&offset<entry.size();count++){
                        batch.add(t.submit(CabinetGameNetwork.GET,null,index,offset,new byte[0],CabinetGameManifest.CHUNK+2048));offset+=Math.min(CabinetGameManifest.CHUNK,entry.size()-offset);
                    }
                    for(Pending pending:batch){var r=t.await(pending);byte[] bytes=r.bytes();if(bytes.length!=Math.min(CabinetGameManifest.CHUNK,entry.size()-pending.command.offset()))throw new IOException("游戏下载分片大小不匹配");ByteBuffer b=ByteBuffer.wrap(bytes);while(b.hasRemaining())out.write(b);t.progress(bytes.length,false);}
                }
            }
            t.check();CabinetGameStore.verify(temporary,entry);CabinetGameStore.directory(directory);Files.move(temporary,target);CabinetGameStore.verify(target,entry);
        }finally{if(Files.exists(temporary,LinkOption.NOFOLLOW_LINKS)){CabinetGameStore.regular(temporary);Files.delete(temporary);}}
    }
    private static byte[] read(SeekableByteChannel channel,int size)throws IOException{byte[] data=new byte[size];ByteBuffer b=ByteBuffer.wrap(data);while(b.hasRemaining())if(channel.read(b)<0)throw new EOFException("游戏文件被截断");return data;}
    private static void checkCacheQuota(Path root,long additional)throws IOException{
        long bytes=0;int count=0;try(var paths=Files.walk(root,3)){for(Path path:paths.limit(2049).toList()){
            if(++count>2048)throw new IOException("本地共享游戏缓存条目已满，请清理后重试");
            var attrs=Files.readAttributes(path,java.nio.file.attribute.BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
            if(attrs.isSymbolicLink()||attrs.isOther())throw new IOException("共享游戏缓存不能包含链接");
            if(attrs.isRegularFile())bytes=Math.addExact(bytes,attrs.size());else if(!attrs.isDirectory())throw new IOException("共享游戏缓存类型异常");
        }}
        if(bytes>CabinetGameStore.QUOTA-additional)throw new IOException("本地共享游戏缓存已满（2 GiB），请清理后重试");
    }
}
