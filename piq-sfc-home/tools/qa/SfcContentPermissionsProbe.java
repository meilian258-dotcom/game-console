package cn.piq.sfchome.net;

import java.util.*;
import io.netty.buffer.Unpooled;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.Bootstrap;

/** Actual Editor record/codec probe; accepts either compiled production classes or the final JAR. */
public final class SfcContentPermissionsProbe {
    private static int checks;
    private static final String ROM="a".repeat(64);
    private static void check(boolean value,String reason){checks++;if(!value)throw new AssertionError(reason);}
    private static void rejected(Runnable action,String reason){boolean refused=false;try{action.run();}catch(IllegalArgumentException expected){refused=true;}check(refused,reason);}
    public static void main(String[] args){
        net.neoforged.fml.loading.LoadingModList.of(List.of(),List.of(),List.of(),List.of(),Map.of());
        SharedConstants.tryDetectVersion();Bootstrap.bootStrap();
        UUID token=UUID.randomUUID();var rows=List.of(new SfcHomeNetwork.RomEntry(ROM,"演示.sfc",32768));
        for(int caps=0;caps<16;caps++){
            var original=new SfcHomeNetwork.Editor(token,true,"PERMISSIONS_UPDATED",ROM,"我的卡","",1,true,caps,rows);
            var buffer=new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);
            try{
                SfcHomeNetwork.Editor.CODEC.encode(buffer,original);
                var decoded=SfcHomeNetwork.Editor.CODEC.decode(buffer);
                check(original.equals(decoded),"Exact editor payload roundtrip caps="+caps);
                check(decoded.capabilities()==caps,"No capability promotion caps="+caps);
                check(buffer.readableBytes()==0,"Codec consumes exactly one packet caps="+caps);
            }finally{buffer.release();}
        }
        check(new SfcHomeNetwork.Editor(token,true,"",ROM,"卡","",1,true,rows).capabilities()==0,"Legacy full constructor fails closed");
        check(new SfcHomeNetwork.Editor(token,true,"",ROM,"卡",rows).capabilities()==0,"Legacy short constructor fails closed");
        for(int invalid:new int[]{-1,16,31,255}){
            rejected(()->new SfcHomeNetwork.Editor(token,true,"",ROM,"卡","",1,true,invalid,rows),"Unknown constructor capability rejected "+invalid);
        }
        for(int invalid:new int[]{16,31,255}){
            var buffer=new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);
            try{
                buffer.writeUUID(token);buffer.writeBoolean(true);buffer.writeUtf("");buffer.writeUtf(ROM);
                buffer.writeUtf("卡");buffer.writeUtf("");buffer.writeByte(1);buffer.writeBoolean(true);buffer.writeByte(invalid);buffer.writeVarInt(0);
                rejected(()->SfcHomeNetwork.Editor.CODEC.decode(buffer),"Unknown wire capability rejected "+invalid);
            }finally{buffer.release();}
        }
        check(SfcEditorPermissions.selection(1,false,false),"Browse can write server ROM");
        check(!SfcEditorPermissions.upload(1,false)&&!SfcEditorPermissions.upload(1,true),"Browse is not upload");
        check(SfcEditorPermissions.upload(3,false)&&!SfcEditorPermissions.upload(3,true),"ROM-only grant");
        check(!SfcEditorPermissions.upload(5,false)&&SfcEditorPermissions.upload(5,true),"Cover-only grant");
        check(!SfcEditorPermissions.admin(7)&&SfcEditorPermissions.admin(15),"Uploads do not grant admin settings");
        System.out.println("SFC_CONTENT_PERMISSIONS_CHECKS="+checks+" PASSED; codec only, no live server/UI claim");
    }
}
