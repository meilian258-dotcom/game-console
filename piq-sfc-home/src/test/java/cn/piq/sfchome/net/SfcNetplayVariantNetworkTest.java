// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.sfchome.net;

import cn.piq.fcarcade.netplay.NetplayLaunchSpec;
import cn.piq.fcarcade.netplay.NetplayVariant;
import cn.piq.sfchome.core.SfcNetplayProfile;
import io.netty.buffer.Unpooled;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Actual protocol codec and trusted-registry boundary; no core/runtime is created. */
class SfcNetplayVariantNetworkTest {
    private static final long WIRE=(1L<<50)+87;
    private static final UUID TICKET=new UUID(41,73);
    private static RegistryFriendlyByteBuf buffer(){return new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);}
    private static SfcHomeNetwork.Session session(NetplayVariant variant,boolean host){
        return new SfcHomeNetwork.Session(15,3,ResourceLocation.parse("minecraft:overworld"),
                new BlockPos(1,64,2),new UUID(1,2),new BlockPos(3,64,2),new UUID(3,4),new UUID(5,6),
                "a".repeat(64),SfcNetplayProfile.buildKey(variant),host?-1:1,new UUID(7,8),host,3,new UUID(9,10),new UUID(11,12));
    }
    private static SfcHomeNetwork.NetplayStart roundTrip(SfcHomeNetwork.NetplayStart value){
        var buffer=buffer();
        try{
            SfcHomeNetwork.NetplayStart.CODEC.encode(buffer,value);
            var decoded=SfcHomeNetwork.NetplayStart.CODEC.decode(buffer);
            assertEquals(value,decoded);assertEquals(0,buffer.readableBytes());return decoded;
        }finally{buffer.release();}
    }
    private static RegistryFriendlyByteBuf raw(String variant,String fingerprint,boolean persistence){
        var buffer=buffer();
        SfcHomeNetwork.Session.CODEC.encode(buffer,session(SfcNetplayProfile.current(),true));
        buffer.writeLong(WIRE);buffer.writeUUID(TICKET);
        // Deliberately bypass the production encoder's limits to exercise hostile wire input.
        buffer.writeUtf(variant,256);buffer.writeUtf(fingerprint,256);buffer.writeBoolean(persistence);
        return buffer;
    }
    private static void rejectedWire(String variant,String fingerprint,boolean persistence){
        var buffer=raw(variant,fingerprint,persistence);
        try{assertThrows(RuntimeException.class,()->SfcHomeNetwork.NetplayStart.CODEC.decode(buffer));}
        finally{buffer.release();}
    }

    @Test void bothTrustedVariantsRoundTripAllFieldsForHostAndSecondPort(){
        for(var variant:List.of(SfcNetplayProfile.current(),SfcNetplayProfile.legacy()))for(boolean host:new boolean[]{true,false}){
            var value=new SfcHomeNetwork.NetplayStart(session(variant,host),WIRE,TICKET,variant.launch());
            var decoded=roundTrip(value);
            assertEquals(variant,SfcNetplayProfile.resolve(decoded.launch()));
            assertEquals(SfcNetplayProfile.buildKey(variant),decoded.session().coreBuild());
            assertEquals(variant.launch().compatibilitySha(),decoded.launch().compatibilitySha());
            assertTrue(decoded.launch().persistenceSupported());assertEquals(host,decoded.session().executionHost());
            assertEquals(host?-1:1,decoded.session().port());
        }
    }

    @Test void persistenceFlagIsTransportedNotReplacedByLocalDefaults(){
        var variant=SfcNetplayProfile.current();var expected=variant.launch();
        var disabled=new NetplayLaunchSpec(expected.variant(),expected.compatibilitySha(),false);
        var decoded=roundTrip(new SfcHomeNetwork.NetplayStart(session(variant,true),WIRE,TICKET,disabled));
        assertFalse(decoded.launch().persistenceSupported());
        assertThrows(IllegalArgumentException.class,()->SfcNetplayProfile.resolve(decoded.launch()));
    }

    @Test void unknownVariantAndAlteredFingerprintDecodeButCannotSelectExecutable(){
        var variant=SfcNetplayProfile.current();
        for(var spec:List.of(new NetplayLaunchSpec("unknown-sfc","a".repeat(64),true),
                new NetplayLaunchSpec(variant.id(),"0".repeat(64),true),
                new NetplayLaunchSpec("a".repeat(48),"b".repeat(64),false))){
            var decoded=roundTrip(new SfcHomeNetwork.NetplayStart(session(variant,true),WIRE,TICKET,spec));
            assertEquals(spec,decoded.launch());
            assertThrows(IllegalArgumentException.class,()->SfcNetplayProfile.resolve(decoded.launch()));
        }
    }

    @Test void unspecifiedIsNotAnImplicitLegacyFallback(){
        var decoded=roundTrip(new SfcHomeNetwork.NetplayStart(session(SfcNetplayProfile.legacy(),true),WIRE,TICKET,NetplayLaunchSpec.UNSPECIFIED));
        assertEquals(NetplayLaunchSpec.UNSPECIFIED,decoded.launch());
        assertThrows(IllegalArgumentException.class,()->SfcNetplayProfile.resolve(decoded.launch()));
        var compatibility=new SfcHomeNetwork.NetplayStart(session(SfcNetplayProfile.legacy(),true),WIRE,TICKET);
        assertEquals(SfcNetplayProfile.legacy().launch(),compatibility.launch());
        assertNotEquals(NetplayLaunchSpec.UNSPECIFIED,compatibility.launch());
    }

    @Test void oversizedAndMalformedWireFieldsAreRejected(){
        rejectedWire("a".repeat(49),"a".repeat(64),true);
        rejectedWire("sfc-test","a".repeat(65),true);
        rejectedWire("sfc-test","a".repeat(63),true);
        rejectedWire("sfc-test","A".repeat(64),true);
        rejectedWire("../snes9x.dll","a".repeat(64),true);
        rejectedWire("SFC","a".repeat(64),true);
        rejectedWire("","a".repeat(64),true);
        rejectedWire("sfc-test","",true);
        rejectedWire("legacy","",false);
    }

    @Test void everyTruncationOfActualGrantIsRejected(){
        var variant=SfcNetplayProfile.current();var buffer=buffer();
        try{
            SfcHomeNetwork.NetplayStart.CODEC.encode(buffer,new SfcHomeNetwork.NetplayStart(session(variant,true),WIRE,TICKET,variant.launch()));
            byte[] bytes=new byte[buffer.readableBytes()];buffer.getBytes(0,bytes);
            for(int length=0;length<bytes.length;length++){
                var cut=new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(Arrays.copyOf(bytes,length)),RegistryAccess.EMPTY);
                try{assertThrows(RuntimeException.class,()->SfcHomeNetwork.NetplayStart.CODEC.decode(cut),"truncated length "+length);}
                finally{cut.release();}
            }
        }finally{buffer.release();}
    }

    @Test void grantStillRequiresNetplayModeValidWireAndTicket(){
        var variant=SfcNetplayProfile.current();var session=session(variant,true);
        assertThrows(IllegalArgumentException.class,()->new SfcHomeNetwork.NetplayStart(session,(1L<<50)-1,TICKET,variant.launch()));
        assertThrows(NullPointerException.class,()->new SfcHomeNetwork.NetplayStart(session,WIRE,null,variant.launch()));
        assertThrows(NullPointerException.class,()->new SfcHomeNetwork.NetplayStart(session,WIRE,TICKET,null));
        var media=new SfcHomeNetwork.Session(session.sessionId(),session.epoch(),session.dimension(),session.consolePos(),session.consoleId(),
                session.tvPos(),session.tvId(),session.linkId(),session.romSha(),SfcHomeNetwork.CORE_BUILD,session.port(),session.controllerLease(),
                true,0,session.mediaSource(),session.mediaStream());
        assertThrows(IllegalArgumentException.class,()->new SfcHomeNetwork.NetplayStart(media,WIRE,TICKET,variant.launch()));
    }
}
