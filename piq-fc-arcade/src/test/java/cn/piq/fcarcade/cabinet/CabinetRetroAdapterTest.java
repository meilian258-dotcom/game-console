package cn.piq.fcarcade.cabinet;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CabinetRetroAdapterTest {
    private static class Legacy implements CabinetEmulator {
        CabinetFrame frame; int p1,p2,inputCalls,cleared,closed; Thread inputThread;
        RuntimeException failure; String error; boolean ready;
        public boolean isReady(){return ready;}
        public String error(){return error;}
        public void offerInput(int a,int b){if(failure!=null)throw failure;p1=a;p2=b;inputCalls++;inputThread=Thread.currentThread();}
        public void clearInput(){cleared++;}
        public CabinetFrame pollFrame(){return frame;}
        public void close(){closed++;}
    }
    @Test void frameTransferKeepsExactArraysAspectRotationAndNoFrameMeaning(){
        var legacy=new Legacy();var shared=legacy.asRetro();assertNull(shared.pollFrame());
        int[] pixels={0x12345678,0xFFEEDDCC};short[] pcm={Short.MIN_VALUE,Short.MAX_VALUE};
        for(int rotation=0;rotation<4;rotation++){
            legacy.frame=new CabinetFrame(2,1,pixels,4F/3F,rotation,pcm);
            var frame=shared.pollFrame();
            assertSame(pixels,frame.abgr());assertSame(pcm,frame.pcm48k());
            assertEquals(2,frame.width());assertEquals(1,frame.height());
            assertEquals(4F/3F,frame.displayAspect());assertEquals(rotation,frame.rotation());
        }
    }
    @Test void allExistingMasksPassThroughOnTheCallingThread(){
        var legacy=new Legacy();var shared=legacy.asRetro();
        for(int mask=0;mask<4096;mask++){
            shared.offerInput(mask,4095^mask);
            assertEquals(mask,legacy.p1);assertEquals(4095^mask,legacy.p2);
            assertSame(Thread.currentThread(),legacy.inputThread);
        }
        assertEquals(4096,legacy.inputCalls);
        shared.offerInput(Integer.MIN_VALUE,Integer.MAX_VALUE);
        assertEquals(Integer.MIN_VALUE,legacy.p1);assertEquals(Integer.MAX_VALUE,legacy.p2);
    }
    @Test void readinessAndErrorsRemainLiveDelegations(){
        var legacy=new Legacy();var shared=legacy.asRetro();assertFalse(shared.isReady());assertNull(shared.error());
        legacy.ready=true;legacy.error="old provider error";assertTrue(shared.isReady());assertSame(legacy.error,shared.error());
        legacy.failure=new IllegalStateException("old input failure");
        assertSame(legacy.failure,assertThrows(IllegalStateException.class,()->shared.offerInput(0,0)));
    }
    @Test void bridgeDoesNotImplicitlyOpenClearOrCloseAnything(){
        var legacy=new Legacy();var shared=legacy.asRetro();
        assertEquals(0,legacy.inputCalls);assertEquals(0,legacy.cleared);assertEquals(0,legacy.closed);
        shared.clearInput();assertEquals(1,legacy.cleared);assertEquals(0,legacy.closed);
        shared.close();assertEquals(1,legacy.cleared);assertEquals(1,legacy.closed);
    }
    @Test void oldProviderExplicitlyRejectsExtraPortsAndIsolatedRelease(){
        var legacy=new Legacy();var shared=legacy.asRetro();assertEquals(2,shared.maxPlayers());
        shared.offerInputs(1,2,0,0);assertEquals(1,legacy.p1);assertEquals(2,legacy.p2);
        assertThrows(IllegalArgumentException.class,()->shared.offerInputs(0,0,1,0));
        assertThrows(IllegalArgumentException.class,()->shared.offerInputs(0,0,0,1));
        assertThrows(UnsupportedOperationException.class,()->shared.releasePort(1));assertEquals(0,legacy.cleared);
    }
    @Test void newProviderFourPortsAndReleaseAreForwardedWithoutTruncation(){
        int[] received=new int[4];int[] released={-1};
        var legacy=new Legacy(){
            @Override public int maxPlayers(){return 4;}
            @Override public void offerInputs(int a,int b,int c,int d){received[0]=a;received[1]=b;received[2]=c;received[3]=d;}
            @Override public void releasePort(int port){released[0]=port;}
        };
        var shared=legacy.asRetro();assertEquals(4,shared.maxPlayers());
        shared.offerInputs(1,256,512,2048);assertArrayEquals(new int[]{1,256,512,2048},received);
        for(int i=0;i<4;i++){shared.releasePort(i);assertEquals(i,released[0]);}
        assertEquals(0,legacy.cleared);assertEquals(0,legacy.inputCalls);
    }
    @Test void legacyDiagnosticsAreEmptyAndDoNotConsumeOrMutateAnything(){
        var legacy=new Legacy();var shared=legacy.asRetro();
        assertTrue(legacy.diagnostics().isEmpty());assertTrue(shared.diagnostics().isEmpty());
        assertEquals(0,legacy.inputCalls);assertEquals(0,legacy.cleared);assertEquals(0,legacy.closed);
    }
    @Test void capableDiagnosticsPassThroughWithoutPollingOrNativeWork(){
        var lines=java.util.List.of("core 60 fps","pending 0");
        var legacy=new Legacy(){
            @Override public java.util.List<String> diagnostics(){return lines;}
            @Override public CabinetFrame pollFrame(){throw new AssertionError("Diagnostics consumed video");}
            @Override public boolean isReady(){throw new AssertionError("Diagnostics queried native readiness");}
        };
        var shared=legacy.asRetro();assertSame(lines,shared.diagnostics());
        assertEquals(0,legacy.inputCalls);assertEquals(0,legacy.cleared);assertEquals(0,legacy.closed);
    }
}
