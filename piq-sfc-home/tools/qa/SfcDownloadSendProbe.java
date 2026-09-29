package cn.piq.sfcarcade.server;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Actual production guard plus compiled-Minecraft send wiring; no Minecraft world/event bus. */
public final class SfcDownloadSendProbe {
    private static int assertions;
    private static final String MANAGER="cn/piq/sfcarcade/server/SfcServerManager";
    private static void check(boolean ok,String why){assertions++;if(!ok)throw new AssertionError(why);}
    private static ClassNode node(String name)throws Exception{
        try(var in=SfcDownloadSendProbe.class.getClassLoader().getResourceAsStream(name+".class")){
            if(in==null)throw new AssertionError(name);var node=new ClassNode();new ClassReader(in).accept(node,0);return node;
        }
    }
    private static MethodNode method(ClassNode node,String name){return node.methods.stream().filter(m->m.name.equals(name)).findFirst().orElseThrow();}
    private static long calls(MethodNode method,String owner,String name){long count=0;for(var i:method.instructions)if(i instanceof MethodInsnNode m&&m.owner.equals(owner)&&m.name.equals(name))count++;return count;}
    private static long calls(ClassNode node,String owner,String name){return node.methods.stream().mapToLong(m->calls(m,owner,name)).sum();}
    public static void main(String[] args)throws Exception{
        Path expected=Path.of(args[0]).toRealPath();
        check(Path.of(SfcServerManager.DownloadSendGuard.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath().equals(expected),"actual production guard origin");
        var guard=new SfcServerManager.DownloadSendGuard();var events=new AtomicInteger();var sent=new AtomicInteger();var current=new AtomicBoolean(true);
        check(!guard.run(()->false,()->{events.incrementAndGet();return true;},sent::incrementAndGet),"no pending/current packet rejected");
        check(events.get()==0&&sent.get()==0,"no pending data means no protection event or packet");
        StringBuilder order=new StringBuilder();
        check(guard.run(()->{order.append('F');return true;},()->{order.append('P');return true;},()->order.append('S')),"authorized packet succeeds");
        check(order.toString().equals("FPFS"),"facts permission final facts precede send");
        for(int slot=0;slot<2;slot++){
            // Start and Chunk use the same production transaction; neither can retain the initial grant.
            current.set(true);int before=sent.get();
            check(!guard.run(current::get,()->false,sent::incrementAndGet),"revoked permission denies packet kind "+slot);
            check(sent.get()==before,"revoked packet sends no data");
            check(!guard.run(current::get,()->{current.set(false);return true;},sent::incrementAndGet),"callback invalidates original transfer");
            check(sent.get()==before,"cancelled transfer sends no data");
        }
        for(String fact:List.of("player","connection","session","block entity","ROM","transfer")){
            var original=new Object();var identity=new AtomicReference<>(original);
            check(!guard.run(()->identity.get()==original,()->{identity.set(new Object());return true;},sent::incrementAndGet),"callback replacement rejected: "+fact);
        }
        int before=sent.get();
        check(guard.run(()->true,()->{check(!guard.run(()->true,()->true,sent::incrementAndGet),"nested transaction rejected");return true;},sent::incrementAndGet),"outer transaction remains valid");
        check(sent.get()==before+1,"reentry cannot send second packet");
        boolean threw=false;try{guard.run(()->true,()->{throw new IllegalStateException("fixture");},sent::incrementAndGet);}catch(IllegalStateException expectedFailure){threw=true;}
        check(threw,"unexpected callback errors propagate without send");
        check(guard.run(()->true,()->true,sent::incrementAndGet),"guard releases after exception");
        threw=false;try{guard.run(()->true,()->true,()->{throw new IllegalStateException("send fixture");});}catch(IllegalStateException expectedFailure){threw=true;}
        check(threw,"failed transport propagates to scoped production cleanup");
        check(guard.run(()->true,()->true,sent::incrementAndGet),"guard releases after transport exception");
        // Consecutive non-periodic ticks must all invoke a new protection decision.
        events.set(0);for(int tick=1;tick<20;tick++)check(guard.run(()->true,()->{events.incrementAndGet();return true;},()->{}),"each pending packet can be checked");
        check(events.get()==19,"no twenty-tick cached permission");
        var manager=node(MANAGER);MethodNode tick=method(manager,"tick"), packet=method(manager,"sendDownloadPacket"), failure=method(manager,"failDownload");
        check(calls(tick,MANAGER,"sendDownloadPacket")==2,"Start and Chunk both use packet transaction");
        check(calls(tick,MANAGER,"downloadAllowed")==0,"idle tick has no cached/periodic permission event");
        check(calls(packet,MANAGER+"$DownloadSendGuard","run")==1,"real path executes tested guard");
        check(calls(packet,MANAGER,"removeDownload")==1&&calls(packet,MANAGER,"failDownload")==1,"failed send removes only its transfer and uses scoped failure");
        check(packet.tryCatchBlocks.stream().anyMatch(t->"java/lang/RuntimeException".equals(t.type))&&packet.tryCatchBlocks.stream().anyMatch(t->"java/lang/LinkageError".equals(t.type)),"transport/callback failure is isolated to original download");
        check(calls(failure,MANAGER,"release")==1&&calls(failure,MANAGER,"fail")==1,"failure has release and feedback");
        check(calls(failure,"net/minecraft/server/network/ServerGamePacketListenerImpl","getConnection")+calls(failure,"net/minecraft/network/PacketListener","getConnection")+calls(failure,"net/minecraft/server/network/ServerCommonPacketListenerImpl","getConnection")>=1,"feedback checks actual connection");
        check(calls(failure,"net/minecraft/network/Connection","isConnected")==1,"feedback requires original connection connected");
        check(calls(manager,"cn/piq/sfcarcade/net/SfcNetwork","sendDownloadStart")==1&&calls(manager,"cn/piq/sfcarcade/net/SfcNetwork","sendDownloadChunk")==1,"no alternate unguarded send callsites");
        var lambdas=manager.methods.stream().filter(m->m.name.startsWith("lambda$sendDownloadPacket$")).toList();
        check(lambdas.stream().mapToLong(m->calls(m,MANAGER,"downloadFacts")).sum()==1,"packet facts verify runtime authority");
        check(lambdas.stream().mapToLong(m->calls(m,MANAGER,"downloadAllowed")).sum()==1,"every packet consults protection");
        check(lambdas.stream().mapToLong(m->calls(m,"java/util/Map","get")).sum()==1,"packet facts require exact current transfer map entry");
        System.out.println("{\"ok\":true,\"assertions\":"+assertions+",\"actual_production_guard\":true,\"compiled_send_wiring\":true,\"minecraft_started\":false,\"event_runtime_executed\":false}");
    }
}
