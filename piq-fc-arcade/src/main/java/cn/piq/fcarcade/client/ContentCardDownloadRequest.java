package cn.piq.fcarcade.client;

import cn.piq.fcarcade.home.content.ContentCardDownloadBuffer;
import cn.piq.fcarcade.home.content.ContentCardNetwork;
import cn.piq.fcarcade.home.content.ContentCardStore;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

/** Pure request state used by the client adapter: exact connection identity, one completion, no runtime. */
final class ContentCardDownloadRequest {
    final ResourceLocation system;final UUID token;final BlockPos pos;final String hash;
    final int size,maximum;final Object connection;final long opened;
    final CompletableFuture<byte[]> result=new CompletableFuture<>();
    private ContentCardDownloadBuffer buffer;private boolean validating;
    ContentCardDownloadRequest(boolean clientThread,ResourceLocation system,UUID token,BlockPos pos,String hash,int size,
                               int maximum,Object connection,long opened){
        if(!clientThread)throw new IllegalStateException("Download expectation must be captured on the client thread");
        if(system==null||token==null||pos==null||hash==null||!hash.matches("[0-9a-f]{64}")||maximum<1
                ||maximum>ContentCardStore.MAX_BYTES||size<0||size>maximum||connection==null)
            throw new IllegalArgumentException("Download expectation");
        this.system=system;this.token=token;this.pos=pos.immutable();this.hash=hash;this.size=size;
        this.maximum=maximum;this.connection=connection;this.opened=opened;
    }
    boolean matches(ContentCardNetwork.Message m){return token.equals(m.token())&&system.equals(m.system())&&pos.equals(m.pos());}
    private void require(ContentCardNetwork.Message m,Object current){
        if(current!=connection||result.isDone()||!matches(m))throw new IllegalStateException("Stale content request");
    }
    void offer(ContentCardNetwork.Message m,Object current){
        require(m,current);
        if(m.op()!=ContentCardNetwork.DOWNLOAD_ONLY||buffer!=null||validating||!hash.equals(m.hash())
                ||size!=0&&size!=m.size()||m.offset()!=0||m.data().length!=0)
            throw new IllegalArgumentException("Download offer identity");
        new ContentCardStore.Entry(m.hash(),m.name(),m.size());
        buffer=new ContentCardDownloadBuffer(hash,m.size(),maximum);
    }
    byte[] data(ContentCardNetwork.Message m,Object current){
        require(m,current);
        if(m.op()!=ContentCardNetwork.DATA||buffer==null||validating)throw new IllegalStateException("Unexpected download chunk");
        buffer.accept(m.hash(),m.size(),m.offset(),m.data());
        if(!buffer.complete())return null;
        byte[] bytes=buffer.take();buffer=null;validating=true;return bytes;
    }
    int offset(){return buffer==null?0:buffer.offset();}
    boolean expired(long now){return now-opened>120_000_000_000L;}
    boolean publish(Object current,byte[] verified){
        if(current!=connection||!validating||result.isDone())return false;
        return result.complete(verified);
    }
    void close(Throwable failure){
        buffer=null;
        if(failure==null)result.cancel(false);else result.completeExceptionally(failure);
    }
}
