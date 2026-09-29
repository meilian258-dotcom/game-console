package cn.piq.fcarcade.netplay;

import java.util.*;
import java.util.concurrent.*;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;

/** Event-loop draining avoids waiting on MC ticks and respects Netty backpressure.
 * Each MC connection has at most 1 MiB of queued native data. Overflow ends the
 * affected native stream; it never drops bytes and then continues that stream.
 */
final class NetplayTransport {
    private record Key(Connection connection,boolean clientbound){}
    private static final Map<Key,Queue> QUEUES=new ConcurrentHashMap<>();
    static void send(Connection connection,NetplayNetwork.Data packet,boolean clientbound){
        if(connection==null||!connection.isConnected()||connection.channel()==null)return;
        Key key=new Key(connection,clientbound);
        QUEUES.compute(key,(k,q)->{if(q==null)q=new Queue(k);q.offer(packet);return q;});
    }
    private static final class Queue {
        final Key key;final ArrayDeque<NetplayNetwork.Data> pending=new ArrayDeque<>();
        int bytes;boolean scheduled;
        Queue(Key key){this.key=key;}
        synchronized void offer(NetplayNetwork.Data data){
            if(bytes+size(data)>1024*1024||pending.size()>=256){
                var c=data.chunk();pending.removeIf(p->{boolean same=p.chunk().session()==c.session()&&p.chunk().ticket().equals(c.ticket());if(same)bytes-=size(p);return same;});
                data=new NetplayNetwork.Data(new NetplayChunk(c.session(),c.ticket(),NetplayChunk.CLOSE,0,new byte[0]),false);
            }
            if(pending.size()>=256)return; // Other already-closing streams fill the control cap.
            pending.add(data);bytes+=size(data);
            if(!scheduled){scheduled=true;key.connection.channel().eventLoop().execute(this::drain);}
        }
        private void drain(){
            var connection=key.connection;var channel=connection.channel();
            if(!connection.isConnected()||channel==null){QUEUES.remove(key,this);synchronized(this){pending.clear();bytes=0;scheduled=false;}return;}
            for(int i=0;i<16&&channel.isWritable();i++){
                NetplayNetwork.Data data;synchronized(this){data=pending.poll();if(data!=null)bytes-=size(data);}
                if(data==null)break;
                if(key.clientbound)connection.send(new ClientboundCustomPayloadPacket(data));else connection.send(new ServerboundCustomPayloadPacket(data));
                // Only drained, handed-off packets count; queued/overflow-discarded bytes do not.
                if(!connection.isMemoryConnection()){
                    if(key.clientbound)cn.piq.fcarcade.network.ServerTrafficMeter.netplay(true,data.encodedBytes());
                    else cn.piq.fcarcade.network.ModTrafficProbe.endpoint(connection,true,data.encodedBytes());
                }
            }
            // Map compute and queue lock use the same order as producers.
            QUEUES.computeIfPresent(key,(k,q)->{
                if(q!=this)return q;
                synchronized(this){if(pending.isEmpty()){scheduled=false;return null;}}
                channel.eventLoop().schedule(this::drain,2,TimeUnit.MILLISECONDS);return this;
            });
        }
        private static int size(NetplayNetwork.Data p){return p.chunk().bytes().length+48;}
    }
}
