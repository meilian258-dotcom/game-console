package cn.piq.fcarcade.cabinet;

import java.util.*;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Transport-level bounded whole-frame admission. A slow connection cannot queue unlimited MC sends. */
public final class CabinetMediaSender {
    private static final Map<Connection,CabinetSendWindow> WINDOWS=new WeakHashMap<>();
    private CabinetMediaSender(){}
    public static boolean serverbound(Connection connection,List<CabinetMediaPacket> batch){return send(connection,null,batch,false);}
    public static boolean clientbound(Connection connection,UUID recipient,List<CabinetMediaPacket> batch){
        if(recipient==null)return false;return send(connection,recipient,batch,false);
    }
    /** Observation shares the same physical Connection window as player streams. */
    public static boolean watchServerbound(Connection connection,List<CabinetMediaPacket> batch){return send(connection,null,batch,true);}
    public static boolean watchClientbound(Connection connection,UUID recipient,List<CabinetMediaPacket> batch){
        if(recipient==null)return false;return send(connection,recipient,batch,true);
    }
    /** Trusted callers include all encoded fields/header in conservativeBytes. Game/state data
     * shares the exact physical window with media; no new per-protocol queue or window exists. */
    public static boolean sendPayload(Connection connection,CustomPacketPayload payload,int conservativeBytes,boolean serverbound){
        if(payload==null||conservativeBytes<32||conservativeBytes>32768||connection==null||!connection.isConnected()
                ||connection.channel()==null||!connection.channel().isWritable()
                ||connection.getDirection()!=(serverbound?PacketFlow.CLIENTBOUND:PacketFlow.SERVERBOUND))return false;
        CabinetSendWindow window;
        synchronized(WINDOWS){window=WINDOWS.computeIfAbsent(connection,key->new CabinetSendWindow());}
        var ticket=window.reserve(new int[]{conservativeBytes});if(ticket==null)return false;
        try{
            Packet<?> packet=serverbound?new ServerboundCustomPayloadPacket(payload):new ClientboundCustomPayloadPacket(payload);
            connection.send(packet,completion(ticket,0));return true;
        }catch(RuntimeException|LinkageError failure){
            // send may have queued before throwing. Only actual completion/disconnect may release it.
            return false;
        }
    }
    /** Admit an entire encoded frame against the same physical window before queuing any packet.
     * Trusted callers provide conservative encoded sizes including all headers for every payload. */
    public static boolean sendPayloads(Connection connection,List<? extends CustomPacketPayload> supplied,int[] conservativeBytes,boolean serverbound){
        if(connection==null||!connection.isConnected()||connection.channel()==null||!connection.channel().isWritable()
                ||connection.getDirection()!=(serverbound?PacketFlow.CLIENTBOUND:PacketFlow.SERVERBOUND)
                ||supplied==null||supplied.isEmpty()||supplied.size()>CabinetSendWindow.MAX_PACKETS
                ||conservativeBytes==null||conservativeBytes.length!=supplied.size())return false;
        List<? extends CustomPacketPayload> batch;
        try{batch=List.copyOf(supplied);}catch(NullPointerException invalid){return false;}
        int[] bytes=conservativeBytes.clone();long total=0;
        if(batch.isEmpty()||batch.size()>CabinetSendWindow.MAX_PACKETS||bytes.length!=batch.size())return false;
        for(int size:bytes){if(size<32||size>32768)return false;total+=size;}
        if(total>CabinetSendWindow.MAX_BYTES)return false;
        CabinetSendWindow window;
        synchronized(WINDOWS){window=WINDOWS.computeIfAbsent(connection,key->new CabinetSendWindow());}
        var ticket=window.reserve(bytes);if(ticket==null)return false;
        int index=0;
        try{
            for(;index<batch.size();index++){
                var payload=batch.get(index);
                Packet<?> packet=serverbound?new ServerboundCustomPayloadPacket(payload):new ClientboundCustomPayloadPacket(payload);
                connection.send(packet,completion(ticket,index));
            }
            return true;
        }catch(RuntimeException|LinkageError failure){
            // The throwing call can have enqueued its packet already. Reclaim only later calls
            // that were never attempted; submitted/uncertain writes need actual completion.
            ticket.cancelUnsent(index+1);return false;
        }
    }
    private static boolean send(Connection connection,UUID recipient,List<CabinetMediaPacket> supplied,boolean watch){
        if(connection==null||!connection.isConnected()||connection.channel()==null||!connection.channel().isWritable()
                ||connection.getDirection()!=(recipient==null?PacketFlow.CLIENTBOUND:PacketFlow.SERVERBOUND))return false;
        if(supplied==null||supplied.isEmpty()||supplied.size()>CabinetSendWindow.MAX_PACKETS)return false;
        List<CabinetMediaPacket> batch;
        try{batch=List.copyOf(supplied);}catch(NullPointerException invalid){return false;}
        int[] bytes=weights(batch);if(bytes==null)return false;
        CabinetSendWindow window;
        synchronized(WINDOWS){window=WINDOWS.computeIfAbsent(connection,key->new CabinetSendWindow());}
        var ticket=window.reserve(bytes);if(ticket==null)return false;
        int index=0;
        try{
            // Reserve before any Connection.send, including before its event-loop queue can grow.
            for(;index<batch.size();index++){
                var p=batch.get(index);var media=new CabinetRoomNetwork.Media(p.room(),p.hostMember(),p.sequence(),p.kind(),p.index(),p.count(),p.width(),p.height(),p.aspect(),p.rotation(),p.rawLength(),p.data());
                net.minecraft.network.protocol.common.custom.CustomPacketPayload payload=watch
                        ?(recipient==null?new WatchNetwork.Media(media):new WatchNetwork.Stream(recipient,media))
                        :(recipient==null?media:new CabinetRoomNetwork.Stream(recipient,media));
                Packet<?> packet=recipient==null?new ServerboundCustomPayloadPacket(payload):new ClientboundCustomPayloadPacket(payload);
                connection.send(packet,completion(ticket,index));
            }
            return true;
        }catch(RuntimeException|LinkageError failure){
            // The throwing send may already have enqueued its packet. Keep its reservation until a
            // real callback/disconnect; only definitely unsubmitted later packets can be reclaimed.
            ticket.cancelUnsent(index+1);return false;
        }
    }
    private static int[] weights(List<CabinetMediaPacket> batch){
        if(batch.isEmpty()||batch.size()>6)return null;var head=batch.getFirst();
        if(head.index()!=0||head.count()!=batch.size())return null;int[] weights=new int[batch.size()];long total=0;
        for(int i=0;i<batch.size();i++){
            var p=batch.get(i);
            if(!p.room().equals(head.room())||!p.hostMember().equals(head.hostMember())||p.sequence()!=head.sequence()||p.kind()!=head.kind()
                    ||p.index()!=i||p.count()!=head.count()||p.width()!=head.width()||p.height()!=head.height()||p.aspect()!=head.aspect()
                    ||p.rotation()!=head.rotation()||p.rawLength()!=head.rawLength())return null;
            int length=p.data().length;total+=length;weights[i]=length+256; // Conservative complete MC payload header.
        }
        return total<=CabinetRoomMedia.MAX_FRAME?weights:null;
    }
    static PacketSendListener completion(CabinetSendWindow.Ticket ticket,int index){
        return new PacketSendListener(){
            @Override public void onSuccess(){ticket.complete(index);}
            @Override public Packet<?> onFailure(){ticket.complete(index);return null;}
        };
    }
    /** Never replace an active connection's outstanding window when a player leaves/rejoins a room. */
    public static void release(Connection connection){
        if(connection==null)return;
        synchronized(WINDOWS){var window=WINDOWS.get(connection);if(window==null)return;
            if(!connection.isConnected()){window.close();WINDOWS.remove(connection);}
            else if(window.inFlight()==0)WINDOWS.remove(connection);
        }
    }
    static int inFlight(Connection connection){synchronized(WINDOWS){var window=WINDOWS.get(connection);return window==null?0:window.inFlight();}}
}
