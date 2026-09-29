package cn.piq.fcarcade.home;

import io.netty.buffer.Unpooled;
import java.nio.file.Path;
import java.util.*;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;

/** Actual production Request constructor and wire codec, no game or server authorization simulation. */
public final class CartridgeSaveModeProbe {
    static int assertions;
    static void check(boolean result,String name){assertions++;if(!result)throw new AssertionError(name);}
    static void rejects(Runnable action){boolean rejected=false;try{action.run();}catch(IllegalArgumentException expected){rejected=true;}check(rejected,"noncanonical save-mode request rejected");}
    static CartridgeNetwork.Request request(int mode,String hash,String cover,String title,String file,int offset,byte[] bytes){return new CartridgeNetwork.Request(9,new CartridgeEditBinding(new UUID(1,2),new UUID(3,4),0,2),hash,cover,title,file,mode,offset,bytes);}
    public static void main(String[] args)throws Exception{
        boolean jar=args.length>0;if(jar)for(String name:List.of("cn.piq.fcarcade.home.CartridgeNetwork$Request","cn.piq.fcarcade.home.CartridgeComputerBinding","cn.piq.fcarcade.server.ServerRomLibrary","cn.piq.fcarcade.client.ui.DeviceLayout","cn.piq.fcarcade.client.ui.CartridgeWorkbenchLayout")){
            Class<?> type=Class.forName(name,false,CartridgeSaveModeProbe.class.getClassLoader());
            check(Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath().equals(Path.of(args[0]).toRealPath()),"final jar origin: "+name);
        }
        String hash="a".repeat(64);
        for(int mode=0;mode<=2;mode++){
            var original=request(mode,hash,"","","",0,new byte[0]);var buffer=new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);
            try{CartridgeNetwork.Request.CODEC.encode(buffer,original);var decoded=CartridgeNetwork.Request.CODEC.decode(buffer);
                check(decoded.operation()==9&&decoded.total()==mode,"mode survives real codec");check(decoded.hash().equals(hash)&&decoded.target().equals(original.target()),"ROM and original token/card/slot retained");check(buffer.readableBytes()==0,"whole request consumed");
            }finally{buffer.release();}
        }
        for(int bad:new int[]{-1,3,4,255,Integer.MAX_VALUE})rejects(()->request(bad,hash,"","","",0,new byte[0]));
        rejects(()->request(0,"","","","",0,new byte[0]));rejects(()->request(1,hash,hash,"","",0,new byte[0]));
        rejects(()->request(1,hash,"","title","",0,new byte[0]));rejects(()->request(2,hash,"","","file.nes",0,new byte[0]));
        rejects(()->request(2,hash,"","","",1,new byte[0]));rejects(()->request(2,hash,"","","",0,new byte[]{1}));
        // Decode malicious raw payloads, not just constructor calls from trusted client code.
        for(int bad:new int[]{3,99,Integer.MAX_VALUE}){
            var buffer=new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);
            try{buffer.writeByte(9);buffer.writeUUID(new UUID(1,2));buffer.writeUUID(new UUID(3,4));buffer.writeByte(0);buffer.writeVarInt(2);
                buffer.writeUtf(hash);buffer.writeUtf("");buffer.writeUtf("");buffer.writeUtf("");buffer.writeVarInt(bad);buffer.writeVarInt(0);buffer.writeByteArray(new byte[0]);
                rejects(()->CartridgeNetwork.Request.CODEC.decode(buffer));
            }finally{buffer.release();}
        }
        System.out.println("{\"ok\":true,\"assertions\":"+assertions+",\"actual_codec\":true,\"minecraft_started\":false,\"server_authorization_exercised\":false,\"jar_origin_checked\":"+jar+"}");
    }
}
