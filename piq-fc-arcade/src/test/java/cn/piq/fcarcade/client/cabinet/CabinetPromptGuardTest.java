package cn.piq.fcarcade.client.cabinet;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
class CabinetPromptGuardTest {
    @Test void onlyExactHostConnectionAndTokenCanAnswer(){
        var g=new CabinetPromptGuard();Object c=new Object();UUID r=UUID.randomUUID(),h=UUID.randomUUID(),t=UUID.randomUUID();
        assertTrue(g.claim(c,r,h,t,10));assertTrue(g.live(c,r,h,t,11));
        assertFalse(g.live(new Object(),r,h,t,11));assertFalse(g.live(c,r,UUID.randomUUID(),t,11));
        assertFalse(g.finish(c,r,h,UUID.randomUUID()));assertTrue(g.finish(c,r,h,t));assertFalse(g.finish(c,r,h,t));
        assertFalse(g.claim(c,r,h,t,12));
    }
    @Test void expiryAndBackwardTimeFailClosed(){
        var g=new CabinetPromptGuard();Object c=new Object();UUID r=UUID.randomUUID(),h=UUID.randomUUID(),t=UUID.randomUUID();
        assertTrue(g.claim(c,r,h,t,10));assertFalse(g.live(c,r,h,t,9));
        assertTrue(g.live(c,r,h,t,10+CabinetPromptGuard.TIMEOUT_NANOS-1));assertFalse(g.live(c,r,h,t,10+CabinetPromptGuard.TIMEOUT_NANOS));
    }
    @Test void neverTwoConcurrentPrompts(){
        var g=new CabinetPromptGuard();Object c=new Object();UUID r=UUID.randomUUID(),h=UUID.randomUUID(),t=UUID.randomUUID(),u=UUID.randomUUID();
        assertTrue(g.claim(c,r,h,t,0));assertFalse(g.claim(c,r,h,u,0));assertTrue(g.finish(c,r,h,t));assertTrue(g.claim(c,r,h,u,1));
    }
    @Test void historyBoundDoesNotForgetOldAnswers(){
        var g=new CabinetPromptGuard();Object c=new Object();UUID r=UUID.randomUUID(),h=UUID.randomUUID();
        for(int i=0;i<256;i++){UUID t=new UUID(0,i);assertTrue(g.claim(c,r,h,t,i));assertTrue(g.finish(c,r,h,t));}
        assertFalse(g.claim(c,r,h,new UUID(0,0),300));assertFalse(g.claim(c,r,h,new UUID(0,256),300));
        assertTrue(g.claim(new Object(),r,h,new UUID(0,256),300));
    }
    @Test void nullScopeNeverAccepted(){var g=new CabinetPromptGuard();UUID t=UUID.randomUUID();assertFalse(g.claim(null,t,t,t,0));assertFalse(g.live(null,t,t,t,0));g.clear();}
}
