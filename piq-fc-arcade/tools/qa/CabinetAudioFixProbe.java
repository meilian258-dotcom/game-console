package cn.piq.fcarcade.client.cabinet;

import cn.piq.fcarcade.cabinet.*;
import cn.piq.nativearcade.layout.NativeCabinetLayout;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.core.BlockPos;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.junit.platform.launcher.core.*;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipFile;

/** Real API, bytecode wiring, actual worker/mailbox/audio queue. No Minecraft instance or audio device. */
public final class CabinetAudioFixProbe {
    static int assertions;
    static void check(boolean value,String why){assertions++;if(!value)throw new AssertionError(why);}
    static void origin(String name,Path expected)throws Exception{
        Class<?> type=Class.forName(name,false,CabinetAudioFixProbe.class.getClassLoader());
        check(Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath().equals(expected.toRealPath()),"Exact production CodeSource: "+name);
    }
    static ClassNode read(Path path,String name)throws Exception{
        byte[] bytes;if(Files.isDirectory(path))bytes=Files.readAllBytes(path.resolve(name+".class"));
        else try(var zip=new ZipFile(path.toFile())){bytes=zip.getInputStream(zip.getEntry(name+".class")).readAllBytes();}
        var node=new ClassNode();new ClassReader(bytes).accept(node,0);return node;
    }
    static MethodNode method(ClassNode node,String name){return node.methods.stream().filter(m->m.name.equals(name)).findFirst().orElseThrow();}
    static long calls(MethodNode m,String owner,String name){return Arrays.stream(m.instructions.toArray()).filter(i->i instanceof MethodInsnNode c&&c.owner.equals(owner)&&c.name.equals(name)).count();}
    static void wiring(Path fc,Path nativePath,Path oldNative)throws Exception{
        origin("cn.piq.fcarcade.client.cabinet.CabinetAudio",fc);
        origin("cn.piq.fcarcade.client.cabinet.CabinetClientBackends",fc);
        origin("cn.piq.nativearcade.client.NativeArcadeClient",nativePath);
        String nativeClient="cn/piq/nativearcade/client/NativeArcadeClient";
        var old=read(oldNative,nativeClient);check(calls(method(old,"render"),nativeClient,"upload")==1,"Frozen negative control: BER consumes frame/PCM");
        var node=read(nativePath,nativeClient);var frame=method(node,"frame");
        check(frame.visibleAnnotations.stream().anyMatch(a->a.desc.equals("Lnet/neoforged/bus/api/SubscribeEvent;")),"Global frame handler subscribed");
        check(frame.desc.equals("(Lnet/neoforged/neoforge/client/event/RenderLevelStageEvent;)V"),"Real render-stage event signature");
        check(Arrays.stream(frame.instructions.toArray()).anyMatch(i->i instanceof FieldInsnNode f&&f.name.equals("AFTER_ENTITIES")&&f.owner.equals("net/neoforged/neoforge/client/event/RenderLevelStageEvent$Stage")),"Exactly one global stage selected");
        check(!RenderLevelStageEvent.Stage.class.getDeclaredField("AFTER_ENTITIES").equals(RenderLevelStageEvent.Stage.class.getDeclaredField("AFTER_BLOCK_ENTITIES")),"Actual NeoForge API declares distinct stages without bootstrapping renderer statics");
        check(calls(frame,nativeClient,"current")==1,"Pump retains exact active-session validation");
        check(calls(frame,nativeClient,"upload")==1,"Global stage pumps once");
        check(calls(method(node,"render"),nativeClient,"upload")==0,"BER now draws only, including repeated cabinet rendering");
        check(node.methods.stream().mapToLong(m->calls(m,nativeClient,"upload")).sum()==1,"No tick/BER duplicate pump");
        check(calls(method(node,"upload"),"cn/piq/nativearcade/bridge/NativeProcessSession","pollFrame")==1,"One queue consumption per pump");
        check(calls(method(node,"upload"),"cn/piq/nativearcade/client/NativeArcadeAudio","offer")==1,"Global pump feeds PCM");
        var restore=method(read(fc,"cn/piq/fcarcade/client/cabinet/CabinetClientBackends"),"restore");
        MethodInsnNode gate=null,reset=null;
        for(var i:restore.instructions){if(i instanceof MethodInsnNode m){if(m.owner.endsWith("/CabinetSyncClient")&&m.name.equals("restore"))gate=m;if(m.owner.endsWith("/CabinetAudio")&&m.name.equals("reset"))reset=m;}}
        check(gate!=null&&reset!=null,"Restore callback reaches generation reset");
        var next=gate.getNext();while(next.getOpcode()<0)next=next.getNext();
        check(next instanceof JumpInsnNode jump&&jump.getOpcode()==Opcodes.IFEQ&&restore.instructions.indexOf(jump.label)>restore.instructions.indexOf(reset),"Rejected/host/replayed restore cannot reset audio");
        var audio=read(fc,"cn/piq/fcarcade/client/cabinet/CabinetAudio");
        for(String name:List.of("reset","close")){
            var m=method(audio,name);check(Arrays.stream(m.instructions.toArray()).noneMatch(i->i instanceof MethodInsnNode c&&(c.owner.startsWith("javax/sound/")||c.name.equals("join"))),"Caller has no device I/O or thread wait: "+name);
        }
        var camera=new Frustum(new Matrix4f(),new Matrix4f().perspective((float)Math.toRadians(70),16f/9f,.05f,100));camera.prepare(0,1,0);
        for(int turns=0;turns<4;turns++){
            var b=NativeCabinetLayout.bounds(turns);var box=new AABB(b.minX(),b.minY(),b.minZ(),b.maxX(),b.maxY(),b.maxZ());
            check(camera.isVisible(box.move(-1,0,-8)),"Original front-camera positive control");
            check(!camera.isVisible(box.move(-1,0,8)),"Original behind-camera negative control remains culled without gating pump");
        }
    }
    static final class Core implements CabinetSyncCore {
        volatile boolean closed;int frame;
        public int maxPlayers(){return 2;}public double targetFps(){return 60;}public String compatibilityId(){return "audio-test-fixture-not-native";}
        public CabinetFrame runFrame(int a,int b,int c,int d){frame++;return new CabinetFrame(1,1,new int[]{-1},1,0,new short[]{111,222,333,444});}
        public byte[] saveState(){return new byte[]{(byte)frame};}public void loadState(byte[] state){frame=state[0]&255;}public void close(){closed=true;}
    }
    static CabinetSyncNetwork.Restore packet(CabinetRoomNetwork.Assignment a,UUID token,boolean begin,byte[] bytes){return new CabinetSyncNetwork.Restore(new CabinetSyncNetwork.StatePart(a.room(),a.member(),1,token,0,0,1,0,CabinetSyncState.hash(new byte[1]),begin,bytes));}
    static void restored(CabinetSyncWorker worker)throws Exception{
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(System.nanoTime()<end){var event=worker.pollEvent();if(event!=null&&event.kind()==CabinetSyncWorker.Event.RESTORED)return;Thread.sleep(1);}throw new AssertionError("Restore did not complete: "+worker.error());
    }
    static void repair()throws Exception{
        var target=new CabinetTarget(ResourceLocation.parse("minecraft:overworld"),BlockPos.ZERO,UUID.randomUUID(),true);UUID host=UUID.randomUUID();
        var assignment=new CabinetRoomNetwork.Assignment(UUID.randomUUID(),UUID.randomUUID(),host,1,2,target,ResourceLocation.parse("test:core"),target,null,CabinetSyncMode.LOCAL_SYNC);
        var connection=new Connection(PacketFlow.CLIENTBOUND);var channel=new EmbeddedChannel(connection);var core=new Core();
        var worker=new CabinetSyncWorker(()->new CabinetSyncWorker.Opened(core,"a".repeat(64)),false);var mailbox=new CabinetSyncClient(assignment,connection,worker);
        var device=new CabinetAudioGenerationTest.Device();device.available.set(0);var audio=CabinetAudioGenerationTest.audio(device);
        try{
            CabinetAudioGenerationTest.await(worker::isReady);UUID first=UUID.randomUUID();check(mailbox.restore(packet(assignment,first,true,new byte[0])),"Guest initial restore accepted");
            check(!mailbox.restore(packet(assignment,first,false,new byte[1])),"Data chunk is not a new reset");restored(worker);
            check(worker.frames(List.of(new CabinetSyncTimeline.Step(1,0,0,0,0))),"Fixture frame accepted");
            cn.piq.retro.api.RetroFrame frame=null;long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(frame==null&&System.nanoTime()<end){frame=worker.pollFrame();Thread.sleep(1);}
            check(frame!=null&&Arrays.equals(frame.pcm48k(),new short[]{111,222,333,444}),"Actual worker hands PCM to actual sink");audio.offer(frame.pcm48k());
            check(!mailbox.restore(packet(assignment,first,true,new byte[0])),"Replay begin rejected without touching sink");check(device.flushCount.get()==0,"No independent spurious device flush");
            UUID repair=UUID.randomUUID();boolean accepted=mailbox.restore(packet(assignment,repair,true,new byte[0]));check(accepted,"Fresh repair accepted");if(accepted)audio.reset();
            check(worker.pollFrame()==null,"Worker old ring cleared");CabinetAudioGenerationTest.await(()->device.flushCount.get()==1);
            device.available.set(38400);audio.offer(new short[]{55,55});CabinetAudioGenerationTest.await(()->device.writes.size()==1);
            check(device.writes.getFirst()[0]==55&&device.writes.getFirst().length==4,"No old worker PCM survives in downstream sink");
            var hostAssignment=new CabinetRoomNetwork.Assignment(assignment.room(),host,host,0,2,target,assignment.backend(),target,null,CabinetSyncMode.LOCAL_SYNC);
            var hostMailbox=new CabinetSyncClient(hostAssignment,connection,worker);check(!hostMailbox.restore(packet(hostAssignment,UUID.randomUUID(),true,new byte[0])),"Host restore never resets playback");hostMailbox.close();
            channel.close();check(!mailbox.restore(packet(assignment,UUID.randomUUID(),true,new byte[0])),"Disconnected source rejects repair");
        }finally{mailbox.close();worker.close();CabinetAudioGenerationTest.finish(audio,device);channel.finishAndReleaseAll();CabinetAudioGenerationTest.await(()->core.closed);}
    }
    public static void main(String[] args)throws Exception{
        wiring(Path.of(args[0]),Path.of(args[1]),Path.of(args[2]));repair();
        var listener=new SummaryGeneratingListener();var launcher=LauncherFactory.create();launcher.registerTestExecutionListeners(listener);
        launcher.execute(LauncherDiscoveryRequestBuilder.request().selectors(selectClass(CabinetAudioGenerationTest.class)).build());var summary=listener.getSummary();summary.printTo(new java.io.PrintWriter(System.out));summary.printFailuresTo(new java.io.PrintWriter(System.out));
        check(summary.getTestsFoundCount()==10&&summary.getTestsSucceededCount()==10&&summary.getTestsSkippedCount()==0,"All 10 actual audio generation/device concurrency tests pass");
        System.out.println("{\"ok\":true,\"assertions\":"+assertions+",\"audio_behavior_tests\":10,\"actual_mc_event_and_frustum\":true,\"bytecode_wiring\":true,\"real_worker_mailbox_audio_queue\":true,\"controlled_audio_endpoint\":true,\"physical_audio_device\":false,\"minecraft_started\":false,\"native_core_started\":false}");
    }
}
