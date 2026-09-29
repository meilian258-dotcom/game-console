// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.fcarcade.netplay;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import static cn.piq.fcarcade.netplay.NetplaySaveNetwork.*;

/** One already-authorized host's save protocol. All transitions run on the authority executor;
 * disk/codec work and lease release run on the bounded IO executor. No world objects are captured. */
final class NetplaySaveSession {
    interface Storage extends AutoCloseable {
        byte[] read() throws Exception;
        void write(byte[] bytes) throws Exception;
        @Override void close() throws Exception;
    }
    final long wire; final UUID ticket; final NetplaySaveState.Identity identity;
    final Callable<? extends Storage> factory;
    final Executor io, authority; final Consumer<Message> sender; final BooleanSupplier connected;
    final LongSupplier clock; final Runnable released;
    volatile Storage storage; volatile boolean closing;
    boolean read,ready,working,closeQueued,retired; int closingWrites;
    long deadline,nextWrite,receivedFrame=-1,creditAt,transferDeadline;
    double credit=65536;
    UUID tx; NetplaySaveTransfer.Assembly upload; byte[] download; int sent;

    NetplaySaveSession(long wire,UUID ticket,NetplaySaveState.Identity identity,Callable<? extends Storage> factory,
            Executor io,Executor authority,Consumer<Message> sender,BooleanSupplier connected,LongSupplier clock,Runnable released){
        this.wire=wire;this.ticket=ticket;this.identity=identity;this.factory=factory;
        this.io=io;this.authority=authority;this.sender=sender;this.connected=connected;this.clock=clock;this.released=released;
        creditAt=clock.getAsLong();deadline=creditAt+TimeUnit.SECONDS.toNanos(45);
    }
    void retire(){retired=true;deadline=Math.min(deadline,clock.getAsLong()+TimeUnit.SECONDS.toNanos(70));}
    void reply(int kind,int value,int offset,byte[] bytes,String text){
        if(!closing&&connected.getAsBoolean())sender.accept(new Message(wire,ticket,tx,kind,value,offset,bytes,text));
    }
    void small(int kind,String text){reply(kind,0,0,new byte[0],text);}
    void receive(Message m){
        if(closing||!connected.getAsBoolean()||clock.getAsLong()>deadline||wire!=m.session()||!ticket.equals(m.ticket()))return;
        try{
            if(m.kind()==CANCEL){dispose();return;}
            if(m.kind()==READ){
                if(read)return;
                read=true;tx=m.transaction();
                if(!m.text().equals(identity.profile()+":"+identity.content()))throw new IllegalArgumentException("核心或游戏/BIOS 身份不匹配");
                if(factory==null){small(DISABLED,"本局不保存");dispose();return;}
                work(()->{storage=factory.call();byte[] state=storage.read();if(state!=null)NetplaySaveState.decode(state,identity);return state==null?null:NetplaySaveTransfer.pack(state);},packed->{
                    if(packed==null){markReady();small(EMPTY,"新进度");}
                    else{download=packed;sent=0;transferDeadline=clock.getAsLong()+TimeUnit.SECONDS.toNanos(40);reply(LOAD,packed.length,0,new byte[0],"");}
                });return;
            }
            if(m.kind()==NEXT&&download!=null&&m.transaction().equals(tx)){
                if(m.offset()!=sent)throw new IllegalArgumentException("存档下载顺序无效");
                if(sent==download.length){download=null;markReady();small(DONE,"存档已传输");return;}
                for(int i=0;i<2&&sent<download.length;i++){int at=sent;sent=Math.min(download.length,sent+NetplaySaveTransfer.CHUNK);reply(DOWNLOAD,download.length,at,Arrays.copyOfRange(download,at,sent),"");}return;
            }
            if(m.kind()==FINISH&&ready&&!working&&upload==null){tx=m.transaction();small(DONE,"保存会话已结束");dispose();return;}
            if(!ready||factory==null||working)return;
            if(m.kind()==BEGIN){
                if(upload!=null||clock.getAsLong()<nextWrite)throw new IllegalArgumentException("保存请求过于频繁");
                // One capture may already be in flight before retirement, followed by the final capture.
                if(retired&&closingWrites++>=2)throw new IllegalArgumentException("结束会话的保存额度已用完");
                tx=m.transaction();upload=new NetplaySaveTransfer.Assembly(m.value());
                transferDeadline=clock.getAsLong()+TimeUnit.SECONDS.toNanos(40);nextWrite=clock.getAsLong()+1_000_000_000L;
                small(READY,"");return;
            }
            if(m.kind()==UPLOAD&&upload!=null&&m.transaction().equals(tx)){
                long now=clock.getAsLong();credit=Math.min(65536,credit+(now-creditAt)*0.001048576);creditAt=now;
                byte[] part=m.bytes();if(part.length>credit)throw new IllegalArgumentException("存档上传超出速率预算");credit-=part.length;
                upload.append(m.offset(),part);
                if(upload.complete()){
                    byte[] bytes=upload.finish();upload=null;
                    work(()->{byte[] state=NetplaySaveTransfer.unpack(bytes);var p=NetplaySaveState.decode(state,identity);
                        if(closing||p.frame()<=receivedFrame)throw new IllegalArgumentException("拒绝过期存档");
                        storage.write(state);return p.frame();
                    },frame->{receivedFrame=frame;small(SAVED,"已保存到服务器");});
                }else if(upload.offset()%(2*NetplaySaveTransfer.CHUNK)==0)reply(ACK,0,upload.offset(),new byte[0],"");
            }
        }catch(Exception invalid){fail(invalid);}
    }
    <T> void work(Callable<T> call,Consumer<T> done){
        working=true;
        try{io.execute(()->{
            T result=null;Exception error=null;try{result=call.call();}catch(Exception e){error=e;}
            T value=result;Exception failure=error;
            if(!closing)authority.execute(()->{working=false;if(closing)return;
                try{if(failure!=null)fail(failure);else done.accept(value);}catch(Exception e){fail(e);}
            });
        });}catch(RejectedExecutionException full){working=false;fail(new IllegalStateException("存档 IO 队列繁忙"));}
    }
    void fail(Exception error){
        String message=error.getMessage();if(message==null)message="存档操作失败";if(message.length()>200)message=message.substring(0,200);
        try{if(tx!=null)small(ERROR,message+"；本次保存未确认，请保留已有备份");}catch(RuntimeException ignored){}
        finally{dispose();}
    }
    void markReady(){ready=true;if(!retired)deadline=Long.MAX_VALUE;}
    void dispose(){
        closing=true;upload=null;download=null;if(closeQueued)return;
        try{io.execute(()->{try{if(storage!=null)storage.close();}catch(Exception e){System.getLogger(getClass().getName()).log(System.Logger.Level.WARNING,"Netplay save close failed",e);}finally{storage=null;released.run();}});closeQueued=true;}
        catch(RejectedExecutionException full){/* retain the lease; tick retries instead of permitting a second writer */}
    }
    void tick(){
        if(closing||!connected.getAsBoolean()||clock.getAsLong()>deadline)dispose();
        else if((upload!=null||download!=null)&&clock.getAsLong()>transferDeadline)fail(new IllegalStateException("存档传输超时"));
    }
}
