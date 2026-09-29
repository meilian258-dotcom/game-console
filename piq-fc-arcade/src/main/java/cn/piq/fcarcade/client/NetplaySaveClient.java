// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.fcarcade.client;

import cn.piq.fcarcade.netplay.*;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.network.Connection;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import static cn.piq.fcarcade.netplay.NetplaySaveNetwork.*;

/** Retains an exact connection/ticket while a normal close's final commit is acknowledged. */
@EventBusSubscriber(modid="piq_fc_arcade",bus=EventBusSubscriber.Bus.MOD,value=Dist.CLIENT)
public final class NetplaySaveClient {
    private NetplaySaveClient(){}
    private record Key(Connection connection,long session){}
    private static final Map<Key,Channel> CHANNELS=new ConcurrentHashMap<>();
    private static final ThreadPoolExecutor IO=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(16),r->{var t=new Thread(r,"PIQ-Netplay-save-client");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    private static final ScheduledExecutorService PACE=Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"PIQ-Netplay-save-transfer");t.setDaemon(true);return t;});
    @SubscribeEvent public static void setup(FMLClientSetupEvent event){event.enqueueWork(()->{
        NetplayNetwork.persistenceFactory((connection,grant)->{
            var key=new Key(connection,grant.session());var channel=new Channel(key,grant.ticket());
            if(CHANNELS.size()>=16||CHANNELS.putIfAbsent(key,channel)!=null)throw new IllegalStateException("保存通道仍在关闭，请稍后重试");
            return channel;
        });
        NetplaySaveNetwork.sink((source,m)->{var c=CHANNELS.get(new Key(source,m.session()));if(c!=null)c.receive(m);});
        PACE.scheduleWithFixedDelay(()->{for(var c:CHANNELS.values())c.expire();},1,1,TimeUnit.SECONDS);
    });}
    private static final class Channel implements NetplayProcess.Persistence {
        final Key key;final UUID ticket;UUID transaction;
        CompletableFuture<byte[]> loaded=new CompletableFuture<>();CompletableFuture<Void> operation;
        NetplaySaveState.Identity identity;
        NetplaySaveTransfer.Assembly download;byte[] outgoing;int offset;
        boolean enabled=true,closed;long lastConfirmed,expires=System.nanoTime()+TimeUnit.SECONDS.toNanos(50);
        Channel(Key key,UUID ticket){this.key=key;this.ticket=ticket;}
        @Override public byte[] load(NetplaySaveState.Identity expected)throws Exception{
            synchronized(this){identity=expected;transaction=UUID.randomUUID();message(READ,0,0,new byte[0],expected.profile()+":"+expected.content());}
            try{return loaded.get(45,TimeUnit.SECONDS);}catch(Exception failed){fail("读取 Netplay 存档未完成，请重试",failed);throw failed;}
        }
        @Override public synchronized boolean enabled(){return enabled;}
        @Override public synchronized CompletableFuture<Void> save(byte[] checkpoint){
            if(closed||!enabled)return CompletableFuture.failedFuture(new IllegalStateException("保存会话不可用"));
            if(operation!=null&&!operation.isDone())return CompletableFuture.failedFuture(new IllegalStateException("上一份存档尚未确认"));
            operation=new CompletableFuture<>();var future=operation;transaction=UUID.randomUUID();UUID tx=transaction;
            expires=System.nanoTime()+TimeUnit.SECONDS.toNanos(45);
            try{IO.execute(()->{
                try{
                    NetplaySaveState.decode(checkpoint,identity);byte[] packed=NetplaySaveTransfer.pack(checkpoint);
                    synchronized(this){if(closed||operation!=future||!transaction.equals(tx))return;outgoing=packed;offset=0;
                        // A final save immediately after a periodic/manual commit must respect the server rate limit.
                        long delay=Math.max(0,lastConfirmed+1_100_000_000L-System.nanoTime());
                        PACE.schedule(()->{synchronized(this){if(!closed&&operation==future&&transaction.equals(tx))message(BEGIN,packed.length,0,new byte[0],"");}},delay,TimeUnit.NANOSECONDS);
                    }
                }catch(Exception e){fail("上传存档失败，旧档保留",e);}
            });}catch(RejectedExecutionException full){fail("本机保存队列繁忙",full);}
            return future;
        }
        @Override public synchronized CompletableFuture<Void> finish(){
            if(closed)return CompletableFuture.failedFuture(new IllegalStateException("保存通道已结束，最终保存未确认"));
            if(operation!=null&&!operation.isDone())return CompletableFuture.failedFuture(new IllegalStateException("保存尚未确认"));
            operation=new CompletableFuture<>();transaction=UUID.randomUUID();expires=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
            message(FINISH,0,0,new byte[0],"");return operation;
        }
        @Override public synchronized void abort(){if(closed)return;if(transaction!=null)message(CANCEL,0,0,new byte[0],"");fail("Netplay 运行器已结束；仅最近一次服务器确认的存档有效",null);}
        synchronized void message(int kind,int value,int at,byte[] bytes,String text){
            if(closed)return;
            try{send(key.connection,new Message(key.session,ticket,transaction,kind,value,at,bytes,text),false);}
            catch(RuntimeException failed){fail("服务器连接中断，保留最近已确认的存档",failed);}
        }
        synchronized void receive(Message m){
            if(closed||!ticket.equals(m.ticket())||!m.transaction().equals(transaction))return;
            try{
                switch(m.kind()){
                    case ERROR->fail(m.text(),null);
                    case DISABLED->{enabled=false;loaded.complete(null);closed=true;CHANNELS.remove(key,this);}
                    case EMPTY->{expires=Long.MAX_VALUE;loaded.complete(null);}
                    case LOAD->{if(loaded.isDone()||download!=null)throw new IllegalStateException("重复的存档下载");download=new NetplaySaveTransfer.Assembly(m.value());message(NEXT,0,0,new byte[0],"");}
                    case DOWNLOAD->{
                        if(download==null)throw new IllegalStateException("未授权的存档片段");download.append(m.offset(),m.bytes());
                        if(download.complete()){
                            byte[] packed=download.finish();int length=download.offset();download=null;
                            IO.execute(()->{try{byte[] bytes=NetplaySaveTransfer.unpack(packed);NetplaySaveState.decode(bytes,identity);
                                synchronized(this){if(closed)return;message(NEXT,0,length,new byte[0],"");expires=Long.MAX_VALUE;loaded.complete(bytes);}
                            }catch(Exception invalid){fail("服务器存档无法恢复，未开始新游戏",invalid);}});
                        }else if(download.offset()%(2*NetplaySaveTransfer.CHUNK)==0)message(NEXT,0,download.offset(),new byte[0],"");
                    }
                    case READY,ACK->{
                        if(outgoing==null||operation==null||operation.isDone()||m.offset()!=offset)throw new IllegalStateException("存档上传确认顺序异常");
                        UUID tx=transaction;int expected=offset;
                        PACE.schedule(()->{synchronized(this){
                            if(closed||outgoing==null||!transaction.equals(tx)||offset!=expected)return;
                            for(int i=0;i<2&&offset<outgoing.length;i++){int start=offset;offset=Math.min(outgoing.length,offset+NetplaySaveTransfer.CHUNK);message(UPLOAD,0,start,Arrays.copyOfRange(outgoing,start,offset),"");}
                        }},50,TimeUnit.MILLISECONDS);
                    }
                    case SAVED->{if(outgoing==null||offset!=outgoing.length||operation==null)throw new IllegalStateException("不完整的保存确认");outgoing=null;lastConfirmed=System.nanoTime();expires=Long.MAX_VALUE;operation.complete(null);}
                    case DONE->{if(loaded.isDone()&&operation!=null&&!operation.isDone()&&outgoing==null){operation.complete(null);closed=true;CHANNELS.remove(key,this);}}
                    default->throw new IllegalStateException("非预期的 Netplay 保存回复");
                }
            }catch(Exception e){fail("Netplay 存档传输中断",e);}
        }
        synchronized void fail(String message,Throwable cause){
            if(closed)return;closed=true;CHANNELS.remove(key,this);download=null;outgoing=null;
            if(transaction!=null)try{send(key.connection,new Message(key.session,ticket,transaction,CANCEL,0,0,new byte[0],""),false);}catch(RuntimeException ignored){}
            var error=new IllegalStateException(message,cause);loaded.completeExceptionally(error);if(operation!=null)operation.completeExceptionally(error);
            var mc=net.minecraft.client.Minecraft.getInstance();mc.execute(()->{if(mc.getConnection()!=null&&mc.getConnection().getConnection()==key.connection&&mc.player!=null)mc.player.displayClientMessage(net.minecraft.network.chat.Component.literal("Netplay 存档："+message),false);});
        }
        synchronized void expire(){if(!key.connection.isConnected()||System.nanoTime()>expires)fail("Netplay 存档连接超时；仅最近确认版本有效",null);}
    }
}
