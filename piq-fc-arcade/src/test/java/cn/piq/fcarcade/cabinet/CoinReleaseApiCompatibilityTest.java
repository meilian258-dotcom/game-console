package cn.piq.fcarcade.cabinet;

import cn.piq.retro.api.RetroEmulator;
import cn.piq.retro.api.RetroFrame;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CoinReleaseApiCompatibilityTest {
    private static class LegacyCabinet implements CabinetEmulator {
        int hard,clear;
        public boolean isReady(){return true;}public String error(){return null;}
        public void offerInput(int a,int b){}public void releasePort(int port){hard++;}
        public void clearInput(){clear++;}public CabinetFrame pollFrame(){return null;}public void close(){}
    }
    @Test void oldCabinetDefaultsRefusePaidCoinReleaseWithoutTouchingControls() {
        var legacy=new LegacyCabinet();assertFalse(legacy.supportsCoinPreservingRelease());
        assertThrows(UnsupportedOperationException.class,()->legacy.releaseGameplayPortKeepingCoin(0));
        assertFalse(legacy.asRetro().supportsCoinPreservingRelease());
        assertThrows(UnsupportedOperationException.class,()->legacy.asRetro().releaseGameplayPortKeepingCoin(0));
        assertEquals(0,legacy.hard);assertEquals(0,legacy.clear);
    }
    @Test void capableCabinetBridgeDelegatesSoftReleaseOnly() {
        int[] released={-1};
        var capable=new LegacyCabinet(){public boolean supportsCoinPreservingRelease(){return true;}public void releaseGameplayPortKeepingCoin(int port){released[0]=port;}};
        var bridge=capable.asRetro();assertTrue(bridge.supportsCoinPreservingRelease());bridge.releaseGameplayPortKeepingCoin(3);
        assertEquals(3,released[0]);assertEquals(0,capable.hard);assertEquals(0,capable.clear);
        bridge.releasePort(3);assertEquals(1,capable.hard);
    }
    @Test void oldRetroDefaultIsAlsoExplicitlyUnsupported() {
        var legacy=new RetroEmulator(){public boolean isReady(){return true;}public String error(){return null;}public void offerInput(int a,int b){}
            public void clearInput(){fail("Must not clear another player");}public RetroFrame pollFrame(){return null;}public void close(){}};
        assertFalse(legacy.supportsCoinPreservingRelease());
        assertThrows(UnsupportedOperationException.class,()->legacy.releaseGameplayPortKeepingCoin(0));
    }
    public static void main(String[] args)throws Exception {
        var instance=new CoinReleaseApiCompatibilityTest();int count=0;
        for(var method:CoinReleaseApiCompatibilityTest.class.getDeclaredMethods())if(method.isAnnotationPresent(Test.class)){method.invoke(instance);count++;}
        System.out.println("Optional coin release API tests passed: "+count);
    }
}
