// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.gba.client;

import cn.piq.gba.bridge.GbaProcessSession;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.*;
import java.io.IOException;
import java.lang.reflect.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/** Real final client adapter plus actual Connection; never creates a Minecraft instance or core. */
public final class GbaLaunchContextProbe {
    private static int assertions;
    private static void check(boolean ok,String label){assertions++;if(!ok)throw new AssertionError(label);}
    private static void rejected(Method open,Object launch,String reason)throws Exception {
        try{open.invoke(null,Path.of("this-probe-never-opens-a-rom.gba"),launch);throw new AssertionError("unsafe open accepted");}
        catch(InvocationTargetException expected){check(expected.getCause() instanceof IOException&&expected.getCause().getMessage().contains(reason),"exact rejection "+reason);}
        check(!GbaProcessSession.active(),"rejection never reserves or starts a child");
    }
    private static void noMinecraftReads(String resource,Set<String> methods)throws Exception {
        ClassNode node=new ClassNode();try(var input=GbaLaunchContextProbe.class.getResourceAsStream(resource)){if(input==null)throw new AssertionError(resource);new ClassReader(input).accept(node,0);}
        int checked=0;
        for(MethodNode method:node.methods)if(methods.contains(method.name)||method.name.startsWith("lambda$prepareFactory$")){
            checked++;
            for(AbstractInsnNode instruction:method.instructions){
                if(instruction instanceof MethodInsnNode call)check(!call.owner.equals("net/minecraft/client/Minecraft")&&!call.name.equals("root"),"worker never fetches Minecraft/root: "+method.name);
                if(instruction instanceof FieldInsnNode field)check(!field.owner.startsWith("net/minecraft/client/"),"worker never reads a current world/player: "+method.name);
            }
        }
        check(checked>=methods.size(),"all requested production worker methods examined");
    }
    public static void main(String[] args)throws Exception {
        if(args.length!=1)throw new IllegalArgumentException("exact final GBA JAR");
        Path finalJar=Path.of(args[0]).toRealPath();
        Class<?> launchType=Class.forName("cn.piq.gba.client.GbaCabinetBackend$Launch");
        for(Class<?> type:List.of(GbaCabinetBackend.class,launchType,GbaProcessSession.class))check(Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath().equals(finalJar),"actual final production origin");
        check(!GbaProcessSession.active(),"no pre-existing core");
        try{new GbaCabinetBackend().open(null);throw new AssertionError("context-free call accepted");}
        catch(IOException expected){check(expected.getMessage().contains("launch context"),"old context-free API rejects without Minecraft lookup or save fallback");}
        check(!GbaProcessSession.active(),"old open cannot reserve core");
        check(launchType.isRecord(),"captured launch is immutable record");
        check(Arrays.stream(launchType.getRecordComponents()).map(RecordComponent::getType).toList().equals(List.of(Path.class,Path.class,Connection.class,Thread.class)),"only frozen paths, actual connection and captured thread retained");
        Constructor<?> constructor=launchType.getDeclaredConstructors()[0];constructor.setAccessible(true);
        Method open=GbaCabinetBackend.class.getDeclaredMethod("openScoped",Path.class,launchType);open.setAccessible(true);
        Path runtime=Path.of("unread-runtime"),saves=Path.of("unwritten-saves");
        Connection old=new Connection(PacketFlow.CLIENTBOUND),other=new Connection(PacketFlow.CLIENTBOUND);
        EmbeddedChannel channel=new EmbeddedChannel(old),otherChannel=new EmbeddedChannel(other);
        try{
            check(old.isConnected()&&other.isConnected(),"actual embedded connections are live");
            Object frozen=constructor.newInstance(runtime,saves,old,Thread.currentThread());
            rejected(open,frozen,"off the client thread");
            channel.close().syncUninterruptibly();
            check(!old.isConnected()&&other.isConnected(),"old disconnected while a different actual connection remains active");
            AtomicReference<Throwable> error=new AtomicReference<>();
            Thread worker=new Thread(()->{try{rejected(open,frozen,"closed before launch");}catch(Throwable failure){error.set(failure);}},"gba-context-probe-worker");
            worker.start();worker.join(5000);check(!worker.isAlive(),"stale asynchronous startup returns promptly");if(error.get()!=null)throw new AssertionError(error.get());
            Connection unconnected=new Connection(PacketFlow.CLIENTBOUND);
            rejected(open,constructor.newInstance(runtime,saves,unconnected,new Thread("captured-client-placeholder")),"closed before launch");
        }finally{channel.finishAndReleaseAll();otherChannel.finishAndReleaseAll();}
        noMinecraftReads("/cn/piq/gba/client/GbaCabinetBackend.class",Set.of("open","openScoped","connected"));
        noMinecraftReads("/cn/piq/gba/client/GbaCabinetBackend$1.class",Set.of("isReady","error","offerInput","pollFrame","clearInput","close","releasePort","maxPlayers"));
        check(!GbaProcessSession.active(),"all gates left process ownership free");
        System.out.println("{\"ok\":true,\"assertions\":"+assertions+",\"production_origin\":\"final-jar-only\",\"actual_connection\":true,\"actual_client_adapter\":true,\"worker_bytecode_no_minecraft_lookup\":true,\"minecraft_started\":false,\"native_core_started\":false}");
    }
}
