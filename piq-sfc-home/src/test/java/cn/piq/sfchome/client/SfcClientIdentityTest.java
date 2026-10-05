package cn.piq.sfchome.client;

import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Executes the production nested guards without loading Minecraft or constructing a core. */
class SfcClientIdentityTest {
    private static Object instance(String name,Class<?>[] types,Object... args)throws Exception{
        var c=Class.forName("cn.piq.sfchome.client."+name).getDeclaredConstructor(types);c.setAccessible(true);return c.newInstance(args);
    }
    private static Object instance(String name)throws Exception{return instance(name,new Class<?>[0]);}
    private static Object call(Object target,String name,Object... args)throws Exception{
        Method chosen=Arrays.stream(target.getClass().getDeclaredMethods()).filter(m->m.getName().equals(name)&&m.getParameterCount()==args.length).findFirst().orElseThrow();chosen.setAccessible(true);
        try{return chosen.invoke(target,args);}catch(InvocationTargetException e){if(e.getCause() instanceof RuntimeException r)throw r;if(e.getCause() instanceof Error r)throw r;throw e;}
    }
    private static boolean yes(Object target,String name,Object... args)throws Exception{return (boolean)call(target,name,args);}
    private static UUID id(int n){return new UUID(7,n);}

    @Test void duplicateAndRetiredSessionNeverRestartCore()throws Exception{
        var order=instance("SfcHomeClient$SessionOrder");Object connection=new Object();
        assertTrue(yes(order,"accept",connection,12L,1,id(1)));
        assertFalse(yes(order,"accept",connection,12L,1,id(1)));
        // Local leave does not reset this fence: the same lease cannot resurrect.
        assertFalse(yes(order,"accept",connection,12L,1,id(1)));
        assertTrue(yes(order,"accept",connection,13L,1,id(2)));
        assertFalse(yes(order,"accept",connection,12L,1,id(1)));
    }
    @Test void secondPlayerCanRejoinSameRunningSessionWithNewLease()throws Exception{
        var order=instance("SfcHomeClient$SessionOrder");Object c=new Object();
        assertTrue(yes(order,"accept",c,1L,1,id(1)));
        assertTrue(yes(order,"accept",c,1L,1,id(2)));
        assertFalse(yes(order,"accept",c,1L,1,id(1)));
        assertFalse(yes(order,"accept",c,1L,1,id(2)));
        assertTrue(yes(order,"accept",c,1L,2,id(2)));
        assertFalse(yes(order,"accept",c,1L,1,id(2)));
    }
    @Test void exactConnectionNotEqualsAndFreshConnectionCanReuseServerIds()throws Exception{
        var order=instance("SfcHomeClient$SessionOrder");Object a=new String("same"),b=new String("same");
        assertFalse(yes(order,"sameConnection",a,b));assertTrue(yes(order,"sameConnection",a,a));assertFalse(yes(order,"sameConnection",null,null));
        assertTrue(yes(order,"accept",a,300L,7,id(1)));assertTrue(yes(order,"accept",b,1L,1,id(1)));
    }
    @Test void invalidIdentitiesAndBoundedLeaseHistoryFailClosed()throws Exception{
        var order=instance("SfcHomeClient$SessionOrder");Object c=new Object();
        assertFalse(yes(order,"accept",null,1L,1,id(1)));assertFalse(yes(order,"accept",c,0L,1,id(1)));
        assertFalse(yes(order,"accept",c,1L,0,id(1)));assertFalse(yes(order,"accept",c,1L,1,null));
        for(int i=0;i<256;i++)assertTrue(yes(order,"accept",c,1L,1,id(i)));
        assertFalse(yes(order,"accept",c,1L,1,id(300)));assertTrue(yes(order,"limitReached"));
        assertFalse(yes(order,"accept",c,1L,1,id(0)));assertFalse(yes(order,"limitReached"));
        assertFalse(yes(order,"accept",c,2L,1,id(300)));assertTrue(yes(order,"limitReached"));
        assertTrue(yes(order,"accept",new Object(),2L,1,id(300)));assertFalse(yes(order,"limitReached"));
    }
    @Test void hostSessionTenThenJoinOlderHostFiveWithFreshLease()throws Exception{
        var order=instance("SfcHomeClient$SessionOrder");Object c=new Object();
        assertTrue(yes(order,"accept",c,10L,1,id(1)));
        assertTrue(yes(order,"accept",c,5L,1,id(2)));
        assertFalse(yes(order,"accept",c,10L,1,id(1)));
        assertFalse(yes(order,"accept",c,5L,1,id(2)));
        assertTrue(yes(order,"accept",c,5L,1,id(3)));
    }
    private static Object consent(BooleanSupplier current,Consumer<Boolean> callback)throws Exception{return instance("SfcJoinScreen$Consent",new Class<?>[]{BooleanSupplier.class,Consumer.class},current,callback);}
    @Test void consentAcceptThenRemovedOrEscapeAnswersExactlyOnce()throws Exception{
        List<Boolean> answers=new ArrayList<>();var c=consent(()->true,answers::add);
        assertTrue(yes(c,"answer",true));assertFalse(yes(c,"answer",false));assertFalse(yes(c,"current"));assertEquals(List.of(true),answers);
    }
    @Test void escapeAndReplacementDeclineOnce()throws Exception{
        List<Boolean> answers=new ArrayList<>();var c=consent(()->true,answers::add);
        assertTrue(yes(c,"answer",false));assertFalse(yes(c,"answer",true));assertEquals(List.of(false),answers);
    }
    @Test void expiredPromptAndReplacedSessionNeverSendLateAnswer()throws Exception{
        AtomicBoolean current=new AtomicBoolean(true);List<Boolean> answers=new ArrayList<>();var c=consent(current::get,answers::add);
        current.set(false);assertFalse(yes(c,"current"));assertTrue(yes(c,"answer",true));assertTrue(answers.isEmpty());
        var expired=consent(()->true,answers::add);call(expired,"expire");assertFalse(yes(expired,"answer",false));assertTrue(answers.isEmpty());
    }
    @Test void callbackReentrancyAndExceptionCannotSendSecondAnswer()throws Exception{
        AtomicReference<Object> holder=new AtomicReference<>();AtomicInteger sends=new AtomicInteger();
        holder.set(consent(()->true,value->{sends.incrementAndGet();try{assertFalse(yes(holder.get(),"answer",false));}catch(Exception e){throw new AssertionError(e);}}));
        assertTrue(yes(holder.get(),"answer",true));assertEquals(1,sends.get());
        var broken=consent(()->true,value->{throw new IllegalStateException("connection closed");});
        assertThrows(IllegalStateException.class,()->call(broken,"answer",true));assertFalse(yes(broken,"answer",false));
    }
    @Test void transferIsBoundToExactConnectionPlaybackTokenAndFrame()throws Exception{
        var g=instance("SfcJoinClient$TransferGuard");Object c=new Object(),p=new Object();
        assertTrue(yes(g,"begin",c,p,id(1),42));assertTrue(yes(g,"matches",c,p,id(1),42));
        assertFalse(yes(g,"matches",new Object(),p,id(1),42));assertFalse(yes(g,"matches",c,new Object(),id(1),42));
        assertFalse(yes(g,"matches",c,p,id(2),42));assertFalse(yes(g,"matches",c,p,id(1),43));
        assertFalse(yes(g,"begin",c,p,id(1),42));assertFalse(yes(g,"begin",c,p,id(2),43));
    }
    @Test void snapshotAndAppliedAreExactlyOnceEvenWhenPacketsRepeat()throws Exception{
        var g=instance("SfcJoinClient$TransferGuard");Object c=new Object(),p=new Object();
        assertTrue(yes(g,"begin",c,p,id(1),42));assertTrue(yes(g,"dataReady"));assertFalse(yes(g,"dataReady"));assertTrue(yes(g,"completedData"));
        assertTrue(yes(g,"acknowledge"));assertFalse(yes(g,"acknowledge"));assertTrue(yes(g,"acknowledged"));
        assertTrue(yes(g,"finish",c,p,id(1)));assertFalse(yes(g,"begin",c,p,id(1),42));
        assertFalse(yes(g,"dataReady"));assertFalse(yes(g,"acknowledge"));
    }
    @Test void cancelledAndFailedTokensCannotAllocateAnotherTransfer()throws Exception{
        var g=instance("SfcJoinClient$TransferGuard");Object c=new Object(),p=new Object();
        assertFalse(yes(g,"finish",c,p,id(1)));assertFalse(yes(g,"begin",c,p,id(1),42));
        assertTrue(yes(g,"begin",c,p,id(2),44));assertTrue(yes(g,"acknowledge"));assertTrue(yes(g,"finish",c,p,id(2)));
        assertFalse(yes(g,"begin",c,p,id(2),44));assertTrue(yes(g,"begin",c,p,id(3),45));
    }
    @Test void wrongOrLateResultCannotClearCurrentTransfer()throws Exception{
        var g=instance("SfcJoinClient$TransferGuard");Object c=new Object(),p=new Object();assertTrue(yes(g,"begin",c,p,id(1),42));
        assertFalse(yes(g,"finish",new Object(),p,id(1)));assertFalse(yes(g,"finish",c,new Object(),id(1)));assertFalse(yes(g,"finish",c,p,id(2)));
        assertTrue(yes(g,"matches",c,p,id(1),42));assertTrue(yes(g,"finish",c,p,id(1)));
    }
    @Test void freshPlaybackCanUseSameTokenButOldCallbackCannotMatch()throws Exception{
        var g=instance("SfcJoinClient$TransferGuard");Object c=new Object(),old=new Object(),next=new Object();
        assertTrue(yes(g,"begin",c,old,id(1),42));assertTrue(yes(g,"finish",c,old,id(1)));
        assertTrue(yes(g,"begin",c,next,id(1),42));assertFalse(yes(g,"matches",c,old,id(1),42));assertFalse(yes(g,"finish",c,old,id(1)));
        assertTrue(yes(g,"matches",c,next,id(1),42));
    }
    @Test void replayHistoryIsBoundedWithoutEvictingOldTokens()throws Exception{
        var g=instance("SfcJoinClient$TransferGuard");Object c=new Object(),p=new Object();
        for(int i=0;i<256;i++){assertTrue(yes(g,"begin",c,p,id(i),i));assertTrue(yes(g,"finish",c,p,id(i)));}
        assertFalse(yes(g,"begin",c,p,id(0),0));call(g,"finish",c,p,id(300));assertFalse(yes(g,"begin",c,p,id(400),400));
        call(g,"clear");assertTrue(yes(g,"begin",c,new Object(),id(1),1));
    }
    @Test void allThreeGuardsRunWithoutMinecraftClasses()throws Exception{
        for(String name:List.of("SfcHomeClient$SessionOrder","SfcJoinClient$TransferGuard","SfcJoinScreen$Consent")){
            Class<?> type=Class.forName("cn.piq.sfchome.client."+name);try(var input=type.getResourceAsStream(name+".class")){
                assertNotNull(input);String pool=new String(input.readAllBytes(),StandardCharsets.ISO_8859_1);assertFalse(pool.contains("net/minecraft/"));assertFalse(pool.contains("net/neoforged/"));
            }
        }
    }
    @Test void actualClientPathsUseGuardsAndLeaseBoundExit()throws Exception{
        Path root=Path.of("src/main/java/cn/piq/sfchome");String home=Files.readString(root.resolve("client/SfcHomeClient.java"));
        assertTrue(home.indexOf("SESSION_ORDER.accept(")<home.indexOf("closeLocal();",home.indexOf("@Override public void session(")));
        assertTrue(home.contains("message.controllerLease()"));assertTrue(home.contains("InputOwnership.owns(INPUT_OWNER)"));assertTrue(home.contains("mc.player.isAlive()&&!mc.player.isSpectator()"));
        assertFalse(home.contains("sendToServer(new SfcHomeNetwork.Leave("));
        for(String file:List.of("net/SfcHomeNetwork.java","net/SfcJoinNetwork.java")){
            String network=Files.readString(root.resolve(file));assertTrue(network.contains("Object source=context.connection();"));assertTrue(network.contains("h.acceptsConnection(source)"));
            // Home protocol 13 adds an explicit READY activation grant; join wire remains unchanged.
            assertTrue(network.contains(file.equals("net/SfcHomeNetwork.java")?"TrafficPayloadRegistrar.create(event,\"13\")":"TrafficPayloadRegistrar.create(e,\"5\")"));
        }
        String hosted=Files.readString(root.resolve("net/SfcHostedNetwork.java"));
        assertTrue(hosted.contains("SfcHomeNetwork.dispatch(c,h->h.hosted(p))"));
        assertFalse(hosted.contains("playToServer("),"The hosted receiver must not gain a media upload authority");
        String join=Files.readString(root.resolve("client/SfcJoinClient.java"));assertTrue(join.contains("!GUARD.begin("));assertTrue(join.contains("!GUARD.acknowledge()"));assertTrue(join.contains("GUARD.completedData()||GUARD.acknowledged()"));assertTrue(join.contains("()->canAnswer(s,c)"));
        String screen=Files.readString(root.resolve("client/SfcJoinScreen.java"));assertTrue(screen.contains("removed(){consent.answer(false);}"));assertTrue(screen.contains("tick(){if(!consent.current())expire();}"));
    }
}
