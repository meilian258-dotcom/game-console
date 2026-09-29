package cn.piq.sfchome.net;
import cn.piq.sfchome.server.SfcJoinGate;
import java.util.*;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.*;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import io.netty.buffer.Unpooled;

/** Actual public production records/codecs and NeoForge-patched outer serverbound packet codec. */
public final class SfcJoinPacketProbe {
    static int checks;
    static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    static void fails(Runnable r,String why){boolean rejected=false;try{r.run();}catch(IllegalArgumentException expected){rejected=true;}check(rejected,why);}
    static <T>T round(StreamCodec<RegistryFriendlyByteBuf,T>codec,T value){var b=new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);try{codec.encode(b,value);T result=codec.decode(b);check(b.readableBytes()==0,"Exactly one packet consumed");return result;}finally{b.release();}}
    public static void main(String[]args){
        UUID token=UUID.randomUUID();long session=42;int epoch=1;String sha="a".repeat(64);
        var offer=new SfcJoinNetwork.Offer(session,epoch);check(round(SfcJoinNetwork.Offer.CODEC,offer).equals(offer),"offer");
        var allow=new SfcJoinNetwork.Allow(session,epoch,true);check(round(SfcJoinNetwork.Allow.CODEC,allow).equals(allow),"allow");
        var approval=new SfcJoinNetwork.Approval(session,epoch,token,"玩家二");check(round(SfcJoinNetwork.Approval.CODEC,approval).equals(approval),"approval name and nonce");
        var decision=new SfcJoinNetwork.Decision(session,epoch,token,false);check(round(SfcJoinNetwork.Decision.CODEC,decision).equals(decision),"deny consent");
        var capture=new SfcJoinNetwork.Capture(session,epoch,token,123);check(round(SfcJoinNetwork.Capture.CODEC,capture).equals(capture),"fixed frame boundary");
        byte[]bytes=new byte[SfcJoinGate.CHUNK];new Random(123).nextBytes(bytes);
        var state=new SfcJoinNetwork.State(session,epoch,token,123,SfcJoinGate.MAX_STATE,0,sha,bytes);var copy=round(SfcJoinNetwork.State.CODEC,state);
        check(copy.token().equals(token)&&copy.frame()==123&&Arrays.equals(copy.data(),bytes),"download state");
        var upload=new SfcJoinNetwork.Upload(state);check(Arrays.equals(round(SfcJoinNetwork.Upload.CODEC,upload).value().data(),bytes),"upload state");
        bytes[0]^=1;check(state.data()[0]!=bytes[0],"input array defensive copy");byte[] exposed=state.data();exposed[0]^=1;check(state.data()[0]!=exposed[0],"getter defensive copy");
        var applied=new SfcJoinNetwork.Applied(session,epoch,token,123,sha,true);check(round(SfcJoinNetwork.Applied.CODEC,applied).equals(applied),"applied hash");
        var result=new SfcJoinNetwork.Result(session,epoch,token,true,"已加入");check(round(SfcJoinNetwork.Result.CODEC,result).equals(result),"result");
        var input=new SfcJoinNetwork.ControllerInput(token,new SfcHomeNetwork.Input(session,epoch,3,4095,false));check(round(SfcJoinNetwork.ControllerInput.CODEC,input).equals(input),"lease bound P2 input");
        for(long id:new long[]{0,-1,Long.MIN_VALUE})fails(()->new SfcJoinNetwork.Offer(id,1),"invalid session");
        fails(()->new SfcJoinNetwork.Allow(1,0,true),"invalid epoch");fails(()->new SfcJoinNetwork.Approval(1,1,token,"x\n"),"name controls");
        fails(()->new SfcJoinNetwork.Approval(1,1,token,"x".repeat(193)),"name bound");fails(()->new SfcJoinNetwork.Capture(1,1,token,-1),"negative frame");
        fails(()->new SfcJoinNetwork.State(1,1,token,0,SfcJoinGate.MAX_STATE+1,0,sha,new byte[]{1}),"total cap before allocation");
        fails(()->new SfcJoinNetwork.State(1,1,token,0,100,99,sha,new byte[]{1,2}),"offset overflow");
        fails(()->new SfcJoinNetwork.State(1,1,token,0,SfcJoinGate.MAX_STATE,0,sha,new byte[SfcJoinGate.CHUNK+1]),"chunk cap");
        fails(()->new SfcJoinNetwork.State(1,1,token,0,10,0,"../escape",new byte[]{1}),"hash syntax");
        // Exercise the actual outer packet, not merely the custom payload codec.
        NetworkRegistry.register(SfcJoinNetwork.Upload.TYPE,SfcJoinNetwork.Upload.CODEC,(p,c)->{},List.of(ConnectionProtocol.PLAY),Optional.of(PacketFlow.SERVERBOUND),"3",false);
        var outer=new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);int packetBytes;
        var largest=new SfcJoinNetwork.Upload(new SfcJoinNetwork.State(Long.MAX_VALUE,Integer.MAX_VALUE,token,Integer.MAX_VALUE,SfcJoinGate.MAX_STATE,SfcJoinGate.MAX_STATE-SfcJoinGate.CHUNK,sha,state.data()));
        try{ServerboundCustomPayloadPacket.STREAM_CODEC.encode(outer,new ServerboundCustomPayloadPacket(largest));packetBytes=outer.readableBytes();check(packetBytes<32767,"Maximum upload including channel ID and widest varints is below vanilla serverbound cap");
            var decoded=ServerboundCustomPayloadPacket.STREAM_CODEC.decode(outer);check(decoded.payload() instanceof SfcJoinNetwork.Upload,"NeoForge outer codec dispatches production upload");
            check(Arrays.equals(((SfcJoinNetwork.Upload)decoded.payload()).value().data(),state.data()),"Maximum block survives outer roundtrip");check(outer.readableBytes()==0,"Outer codec consumes exactly its packet");
        }finally{outer.release();}
        System.out.println("{\"passed\":true,\"assertions\":"+checks+",\"max_upload_outer_packet_bytes\":"+packetBytes+",\"outer_codec\":\"NeoForge21.1.236 ServerboundCustomPayloadPacket.STREAM_CODEC\",\"minecraft_started\":false}");
    }
}
