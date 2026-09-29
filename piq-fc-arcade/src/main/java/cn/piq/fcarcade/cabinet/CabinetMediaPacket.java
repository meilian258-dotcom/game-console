package cn.piq.fcarcade.cabinet;

import java.util.Objects;
import java.util.UUID;

/** Pure immutable media DTO, usable by workers and tests without loading Minecraft networking. */
public record CabinetMediaPacket(UUID room,UUID hostMember,long sequence,int kind,int index,int count,
                                 int width,int height,float aspect,int rotation,int rawLength,byte[] data) {
    public CabinetMediaPacket {
        Objects.requireNonNull(room);Objects.requireNonNull(hostMember);
        CabinetRoomMedia.check(sequence,kind,index,count,width,height,aspect,rotation,rawLength,data);
        data=data.clone();
    }
    @Override public byte[] data(){return data.clone();}
}
