package cn.piq.sfchome.client;

import cn.piq.sfchome.net.SfcHomeNetwork;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** One bounded read lane matches the legacy server's one-transfer/player and 40-tick cooldown.
 * Readers sharing a hash share bytes, not controller or watch authority. */
final class SfcRomDownloads {
    static final int LIMIT=4,READERS=16,RETRY_TICKS=60,MAX_ATTEMPTS=3;
    private final Map<String,Transfer> transfers=new LinkedHashMap<>();
    private Object connection;private Transfer active;private long tick,nextRequest;
    final class Ticket {
        final Transfer transfer;final CompletableFuture<byte[]> result=new CompletableFuture<>();
        private Ticket(Transfer transfer){this.transfer=transfer;}
        void cancel(){SfcRomDownloads.this.cancel(this);}
        int offset(){synchronized(SfcRomDownloads.this){return transfer.download.at;}}
        int total(){synchronized(SfcRomDownloads.this){return transfer.download.bytes==null?0:transfer.download.bytes.length;}}
    }
    private static final class Transfer {
        final SfcNetplayWatchContent.Download download;final List<Ticket> readers=new ArrayList<>();
        long first,last;int attempts;
        Transfer(String hash,Object connection){download=new SfcNetplayWatchContent.Download(hash,connection);}
    }
    synchronized Ticket request(String hash,Object origin){
        if(origin==null||hash==null||!hash.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("ROM request identity");
        if(connection!=origin)throw new IllegalStateException("Stale ROM connection");
        var transfer=transfers.get(hash);
        if(transfer==null){if(transfers.size()>=LIMIT)throw new IllegalStateException("SFC ROM 下载队列已满");transfer=new Transfer(hash,origin);transfers.put(hash,transfer);}
        if(transfer.readers.size()>=READERS)throw new IllegalStateException("SFC ROM 下载读者已满");
        var ticket=new Ticket(transfer);transfer.readers.add(ticket);return ticket;
    }
    synchronized void connection(Object next){
        if(connection==next)return;
        var old=List.copyOf(transfers.values());transfers.clear();active=null;connection=next;nextRequest=tick;
        for(var transfer:old){transfer.download.cancel();for(var ticket:List.copyOf(transfer.readers))ticket.result.cancel(false);}
    }
    private synchronized void cancel(Ticket ticket){
        ticket.transfer.readers.remove(ticket);ticket.result.cancel(false);
        if(ticket.transfer!=active&&ticket.transfer.readers.isEmpty())transfers.remove(ticket.transfer.download.hash,ticket.transfer);
        // An active read cannot be cancelled on this legacy wire; drain it before requesting another hash.
    }
    synchronized void tick(Object origin,long now,Consumer<String> send){
        tick=now;connection(origin);if(connection==null)return;
        if(active==null&&!transfers.isEmpty())active=transfers.values().iterator().next();
        if(active==null)return;
        if(active.attempts>0&&(now-active.first>1200||active.download.at>0&&now-active.last>200)){
            finish(new TimeoutException("SFC ROM 下载超时"),null);return;
        }
        if(active.download.at!=0||now<nextRequest)return;
        if(active.attempts>=MAX_ATTEMPTS){finish(new TimeoutException("SFC ROM 下载请求未获响应"),null);return;}
        if(active.attempts++==0)active.first=now;active.last=now;nextRequest=now+RETRY_TICKS;
        try{send.accept(active.download.hash);}catch(RuntimeException failure){finish(failure,null);}
    }
    synchronized void chunk(Object origin,SfcHomeNetwork.RomChunk packet){
        if(origin!=connection||active==null||active.attempts==0||!active.download.hash.equals(packet.romSha()))return;
        active.last=tick;active.download.accept(packet);
        if(active.download.result.isDone()){
            try{finish(null,active.download.result.join());}catch(CompletionException|CancellationException failure){finish(failure,null);}
        }
    }
    private void finish(Throwable failure,byte[] bytes){
        var completed=active;if(completed==null)return;active=null;transfers.remove(completed.download.hash,completed);
        if(failure!=null)completed.download.cancel();
        for(var ticket:List.copyOf(completed.readers))if(failure==null)ticket.result.complete(bytes);else ticket.result.completeExceptionally(failure);
        completed.readers.clear();
    }
}
