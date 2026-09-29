package cn.piq.fcarcade.network;

import io.netty.buffer.Unpooled;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ModTrafficCodecTest {
    private static RegistryFriendlyByteBuf buffer(){return new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);}
    private static final StreamCodec<RegistryFriendlyByteBuf,String> RAW=StreamCodec.of((b,p)->b.writeUtf(p,64),b->b.readUtf(64));
    @Test void serverEncodeAndServerDecodeDoNotDoubleCountIntegratedOrLanServerWork() {
        var clock=new AtomicLong();var count=new ModTrafficCounter(clock::get);ModTrafficProbe.clientCollector(count);
        var c2s=ModTrafficProbe.toServer(RAW);var s2c=ModTrafficProbe.toClient(RAW);
        var up=buffer();var down=buffer();
        try {
            c2s.encode(up,"input");assertEquals("input",c2s.decode(up));
            s2c.encode(down,"video");assertEquals("video",s2c.decode(down));
            clock.set(1_000_000_000L);assertEquals(6,count.sample().uploadBytesPerSecond());assertEquals(6,count.sample().downloadBytesPerSecond());
        } finally {up.release();down.release();ModTrafficProbe.clientCollector(null);}
    }
    @Test void wrapperBytesEqualOriginalAndMalformedPayloadDoesNotCountAsSuccessfulReceive() {
        var raw=buffer();var observed=buffer();var broken=buffer();var clock=new AtomicLong();var count=new ModTrafficCounter(clock::get);ModTrafficProbe.clientCollector(count);
        try {
            RAW.encode(raw,"画面");ModTrafficProbe.toServer(RAW).encode(observed,"画面");
            byte[] a=new byte[raw.readableBytes()],b=new byte[observed.readableBytes()];raw.readBytes(a);observed.readBytes(b);assertArrayEquals(a,b);
            broken.writeByte(40);assertThrows(RuntimeException.class,()->ModTrafficProbe.toClient(RAW).decode(broken));
            clock.set(1_000_000_000L);assertEquals(0,count.sample().downloadBytesPerSecond());
        } finally {raw.release();observed.release();broken.release();ModTrafficProbe.clientCollector(null);}
    }
    @Test void diagnosticsPacketsAreBoundedAndRoundTripEveryAllowedCap() {
        for(int fps:new int[]{20,30,60}) {
            var b=buffer();try {
                var request=new HostedDiagnosticsNetwork.Request(UUID.randomUUID(),20,fps);
                HostedDiagnosticsNetwork.Request.CODEC.encode(b,request);assertEquals(request,HostedDiagnosticsNetwork.Request.CODEC.decode(b));
                var state=new HostedDiagnosticsNetwork.State(request.nonce(),fps,true,true,"confirmed");
                HostedDiagnosticsNetwork.State.CODEC.encode(b,state);assertEquals(state,HostedDiagnosticsNetwork.State.CODEC.decode(b));assertEquals(0,b.readableBytes());
            } finally {b.release();}
        }
        assertThrows(IllegalArgumentException.class,()->new HostedDiagnosticsNetwork.Request(UUID.randomUUID(),20,120));
        assertThrows(IllegalArgumentException.class,()->new HostedDiagnosticsNetwork.State(UUID.randomUUID(),60,true,true,"x".repeat(257)));
    }
    @Test void codecCompletingAfterConnectionReplacementCannotChargeNewSession() {
        var old=new ModTrafficCounter(()->0);var fresh=new ModTrafficCounter(()->0);var b=buffer();
        StreamCodec<RegistryFriendlyByteBuf,String> switching=StreamCodec.of((out,value)->{
            RAW.encode(out,value);ModTrafficProbe.clientCollector(fresh);
        },in->{var value=RAW.decode(in);ModTrafficProbe.clientCollector(fresh);return value;});
        try {
            ModTrafficProbe.clientCollector(old);ModTrafficProbe.toServer(switching).encode(b,"old");
            assertEquals(0,fresh.sample().totalBytes());assertEquals(0,old.sample().totalBytes());
            ModTrafficProbe.clientCollector(old);assertEquals("old",ModTrafficProbe.toClient(switching).decode(b));
            assertEquals(0,fresh.sample().totalBytes());
            b.clear();ModTrafficProbe.toServer(RAW).encode(b,"new");assertEquals(4,fresh.sample().uploadedBytes());
        }finally{b.release();ModTrafficProbe.clientCollector(null);}
    }
}
