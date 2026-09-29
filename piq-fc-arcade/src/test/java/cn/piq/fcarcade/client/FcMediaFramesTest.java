package cn.piq.fcarcade.client;
import cn.piq.fcarcade.core.NesCore;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;
class FcMediaFramesTest{
    @Test void ownedPixelsAndAudioHaveExpectedShape(){
        byte[] pixels=new byte[NesCore.RGBA_BYTES];pixels[0]=1;pixels[1]=2;pixels[2]=3;
        float[] mono=new float[735];java.util.Arrays.fill(mono,.1F);
        var f=new FcMediaFrames().copy(pixels,mono,mono.length);pixels[0]=99;mono[0]=99;
        assertEquals(0xff030201,f.abgr()[0]);assertEquals(256,f.width());assertEquals(240,f.height());
        assertEquals(4F/3F,f.displayAspect());assertEquals(0,f.rotation());
        assertTrue(f.pcm48k().length>=1598&&f.pcm48k().length<=1602);assertEquals(0,f.pcm48k().length%2);
        assertEquals(0,FcMediaFrames.silent(f).pcm48k().length);
    }
    @Test void conversionBoundsAndNonFiniteAudioAreRejected(){
        var frames=new FcMediaFrames();byte[] rgba=new byte[NesCore.RGBA_BYTES];
        assertThrows(IllegalArgumentException.class,()->frames.copy(new byte[2],new float[0],0));
        assertThrows(IllegalArgumentException.class,()->frames.copy(rgba,new float[0],1));
        assertThrows(IllegalArgumentException.class,()->frames.copy(rgba,new float[]{Float.NaN},1));
    }
    @Test void splitAudioMaintainsContinuousResampling(){
        byte[] rgba=new byte[NesCore.RGBA_BYTES];float[] mono=new float[1470];
        for(int i=0;i<mono.length;i++)mono[i]=(float)Math.sin(i*.1)*.2F;
        short[] whole=new FcMediaFrames().copy(rgba,mono,mono.length).pcm48k();
        var split=new FcMediaFrames();short[] a=split.copy(rgba,java.util.Arrays.copyOfRange(mono,0,735),735).pcm48k();
        short[] b=split.copy(rgba,java.util.Arrays.copyOfRange(mono,735,1470),735).pcm48k();
        short[] combined=java.util.Arrays.copyOf(a,a.length+b.length);System.arraycopy(b,0,combined,a.length,b.length);
        assertArrayEquals(whole,combined);
    }
    @Test void receiverBypassesRomGateAndNeverCreatesCore()throws Exception{
        String events=Files.readString(Path.of("src/main/java/cn/piq/fcarcade/client/ClientArcadeEvents.java"));
        assertTrue(events.contains("state.session(),state.source(),state.token(),state.operator()"));
        String session=Files.readString(Path.of("src/main/java/cn/piq/fcarcade/client/ClientArcadeSession.java"));
        assertTrue(session.contains("if(serverHosted())startHostedMedia();else if (simulationEnabled) startWorker"));
        assertTrue(session.contains("if(serverHosted())return;"));
        assertTrue(session.contains("payload.playerMedia()")==false);
        assertTrue(events.contains("payload.playerMedia()"));
    }
    @Test void sameEpochRecoveryKeepsEncoderAndMediaIdentityWithoutReadinessGate()throws Exception{
        String s=Files.readString(Path.of("src/main/java/cn/piq/fcarcade/client/ClientArcadeSession.java"));
        String authorization=s.substring(s.indexOf("boolean acceptsMediaDemand("),s.indexOf("void mediaDemand("));
        assertTrue(authorization.contains("new java.util.UUID(sessionId,epoch)"));
        assertFalse(authorization.contains("worker.isReady()"));
        String worker=s.substring(s.indexOf("private void startWorker("),s.indexOf("private void consumeResults("));
        assertTrue(worker.contains("worker.mediaTap(mediaPublisher)"));
        assertFalse(worker.contains("mediaPublisher.close()"));
        String close=s.substring(s.indexOf("private void closeSimulation("),s.indexOf("private void closeResources("));
        assertFalse(close.contains("mediaPublisher.close()"));
    }
    @Test void visualReceiverInputDoesNotCreateCoreOrBypassHeldAuthorization()throws Exception{
        String s=Files.readString(Path.of("src/main/java/cn/piq/fcarcade/client/ClientArcadeSession.java"));
        String method=s.substring(s.indexOf("int controllerAnimationMask("),s.indexOf("String diagnosticSummary("));
        assertTrue(method.contains("serverHosted()"));assertTrue(method.contains("keyboardAuthorized()&&inputCapture.armed()?inputMask:0"));
        assertFalse(method.contains("startWorker"));
    }
}
