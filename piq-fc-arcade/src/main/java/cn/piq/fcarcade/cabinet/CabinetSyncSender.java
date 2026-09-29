package cn.piq.fcarcade.cabinet;

import java.util.*;
import net.minecraft.network.*;
import net.minecraft.network.protocol.*;
import net.minecraft.network.protocol.common.*;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Snapshot/frame queue backpressure with actual send completion, bounded per physical connection. */
public final class CabinetSyncSender {
    private CabinetSyncSender(){}
    public static boolean send(Connection connection,CustomPacketPayload payload,int bytes){
        return connection!=null&&bytes>0&&bytes<=30000&&CabinetMediaSender.sendPayload(connection,payload,bytes,connection.getDirection()==PacketFlow.CLIENTBOUND);
    }
}
