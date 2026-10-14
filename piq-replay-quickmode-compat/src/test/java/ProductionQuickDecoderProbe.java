import cn.piq.replayquick.*;
import com.google.gson.*;
import io.netty.buffer.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import net.minecraft.network.FriendlyByteBuf;
import org.spongepowered.asm.launch.MixinBootstrap;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.service.MixinService;

/** Real installed Sable Mixin + complete MC decoder, fed by the compiled production completion. */
public final class ProductionQuickDecoderProbe {
    static int checks,broken,repaired,positive,rawAccepted;
    static Class<?> packetClass;static Constructor<?> decoder;
    static final String TARGET="net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket";
    static void require(boolean valid,String why){checks++;if(!valid)throw new AssertionError(why);}
    static Object decode(byte[] body)throws Throwable {
        var buffer=new FriendlyByteBuf(Unpooled.wrappedBuffer(body));
        try{Object value=decoder.newInstance(buffer);require(buffer.readableBytes()==0,"exact decoder consumption");return value;}
        catch(InvocationTargetException failure){throw failure.getCause();}
        finally{require(buffer.release(),"decoder buffer released");}
    }
    static boolean flag(Object packet)throws Exception{return (boolean)packetClass.getMethod("sable$isActuallyInSubLevel").invoke(packet);}
    static void initializeDecoder()throws Exception {
        require(SableWireContract.supported(),"actual Sable wire contract accepted");
        System.setProperty("mixin.bootstrapService",KeyboardMixinTransformProbe.Boot.class.getName());
        System.setProperty("mixin.service",KeyboardMixinTransformProbe.Host.class.getName());
        MixinBootstrap.init();var environment=MixinEnvironment.getDefaultEnvironment().setSide(MixinEnvironment.Side.CLIENT);
        var host=(KeyboardMixinTransformProbe.Host)MixinService.getService();Mixins.addConfiguration("production_decoder_sable.mixins.json");
        byte[] original;try(var stream=host.getResourceAsStream(TARGET.replace('.','/')+".class")){original=stream.readAllBytes();}
        byte[] transformed=host.transformer().transformClass(environment,TARGET,original);
        require(!Arrays.equals(original,transformed),"real Sponge transformed real target");
        class Loader extends ClassLoader{Loader(){super(ProductionQuickDecoderProbe.class.getClassLoader());}Class<?> define(){return defineClass(TARGET,transformed,0,transformed.length);}}
        packetClass=new Loader().define();decoder=packetClass.getDeclaredConstructor(FriendlyByteBuf.class);decoder.setAccessible(true);
    }
    static byte[] complete(QuickSableState state,byte[] original,int time)throws Throwable {
        var dispatch=Unpooled.buffer();dispatch.writeByte(42);dispatch.writeByte(112);dispatch.writeBytes(original);dispatch.readerIndex(1);
        try{int start=dispatch.readerIndex(),refs=dispatch.refCnt();require(QuickSableStateBehaviorTest.complete(state,dispatch,time),"production completed payload");
            require(dispatch.readerIndex()==start&&dispatch.refCnt()==refs,"production preserves index/refcount");
            byte[] out=new byte[original.length+1];dispatch.getBytes(start+1,out);
            require(Arrays.equals(original,Arrays.copyOf(out,original.length)),"production preserves original bytes");
            require(!QuickSableStateBehaviorTest.complete(state,dispatch,time),"production does not double-append");return out;
        }finally{require(dispatch.release(),"caller releases dispatch buffer");}
    }
    public static void main(String[] args){try{run(args);System.exit(0);}catch(Throwable failure){failure.printStackTrace();System.exit(1);}}
    static void run(String[] args)throws Throwable {
        initializeDecoder();
        if(args.length==0){var builder=new SableFlagTimeline.Builder();builder.spawn(0,7,true);builder.observe(1,7,SableFlagTimeline.Flag.TRUE);
            byte[] body=new byte[28];body[0]=7;require(flag(decode(complete(QuickSableStateBehaviorTest.state(builder.build(),112),body,1))),"positive production state decoded");positive++;
        }else{
            var details=JsonParser.parseString(Files.readString(Path.of(args[0]))).getAsJsonObject();var builder=new SableFlagTimeline.Builder();
            for(var element:details.getAsJsonArray("rawEvents")){var event=element.getAsJsonObject();var parsed=event.getAsJsonObject("parsed");
                int id=parsed.get("id").getAsInt(),time=event.get("time").getAsInt();String type=event.get("type").getAsString();
                if(type.startsWith("Spawn"))builder.spawn(time,id,true);
                else if(type.equals("EntityTeleport")||type.equals("EntityPosition")||type.equals("EntityPositionRotation")){
                    var value=parsed.get("sableFlag");builder.observe(time,id,value.isJsonNull()?SableFlagTimeline.Flag.UNKNOWN:value.getAsBoolean()?SableFlagTimeline.Flag.TRUE:SableFlagTimeline.Flag.FALSE);
                }
                if(type.equals("EntityTeleport")){Object packet=decode(HexFormat.of().parseHex(parsed.get("hex").getAsString()));require(flag(packet)==parsed.get("sableFlag").getAsBoolean(),"original flags decode");rawAccepted++;}
            }
            var timeline=args.length>1?RecordedReplayIndexTest.loadIndex(Path.of(args[1])):builder.build();
            var state=QuickSableStateBehaviorTest.state(timeline,112);
            for(var element:details.getAsJsonArray("quickEvents")){var event=element.getAsJsonObject();if(!event.get("type").getAsString().equals("EntityTeleport"))continue;
                var parsed=event.getAsJsonObject("parsed");int id=parsed.get("id").getAsInt(),time=event.get("seekTarget").getAsInt();
                require(timeline.isPlayerAt(id,time),"player proven by trace spawn");byte[] original=HexFormat.of().parseHex(parsed.get("hex").getAsString());
                try{decode(original);throw new AssertionError("Unextended packet decoded unexpectedly");}catch(IndexOutOfBoundsException expected){broken++;}
                Object packet=decode(complete(state,original,time));require(flag(packet)==(timeline.flagAt(id,time)==SableFlagTimeline.Flag.TRUE),"production flag from exact target");
                var pos=parsed.getAsJsonObject("position");for(String axis:List.of("x","y","z"))require((double)packetClass.getMethod("get"+axis.toUpperCase(Locale.ROOT)).invoke(packet)==pos.get(axis).getAsDouble(),"real decoder unchanged coordinate");repaired++;
                var trueBuilder=new SableFlagTimeline.Builder();trueBuilder.spawn(0,id,true);trueBuilder.observe(0,id,SableFlagTimeline.Flag.TRUE);
                require(flag(decode(complete(QuickSableStateBehaviorTest.state(trueBuilder.build(),112),original,time))),"positive recorded state preserved");positive++;
            }
            require(rawAccepted==14&&broken==8&&repaired==8&&positive==8,"current trace counts");
        }
        System.out.println("ProductionQuickDecoderProbe checks="+checks+" raw="+rawAccepted+" underflow="+broken+" repaired="+repaired+" positive="+positive+" PASS (actual production method and full transformed MC class; no game started)");
    }
}
