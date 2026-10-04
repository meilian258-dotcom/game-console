package cn.piq.fcarcade.client;

import cn.piq.fcarcade.netplay.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.network.Connection;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

/** Main-thread session descriptors; network-thread delivery only enters bounded pipe mailboxes. */
public final class NetplayClient {
    private NetplayClient(){}
    private record Key(Connection source,long session){}
    private static final Map<Key,NetplayNetwork.State> STATES=new ConcurrentHashMap<>();
    private static final Map<Key,NetplayProcess> RUNS=new ConcurrentHashMap<>();
    private static Connection current(){var c=Minecraft.getInstance().getConnection();return c==null?null:c.getConnection();}
    @EventBusSubscriber(modid="piq_fc_arcade",bus=EventBusSubscriber.Bus.MOD,value=Dist.CLIENT)
    public static class Setup {
        @SubscribeEvent public static void setup(FMLClientSetupEvent event){event.enqueueWork(()->NetplayNetwork.sink(new NetplayNetwork.Sink(){
            public void state(Connection source,NetplayNetwork.State state){
                if(source!=current()||Minecraft.getInstance().level==null)return;
                var key=new Key(source,state.session().sessionId());var old=STATES.get(key);
                if(old!=null&&old.session().controlRevision()>state.session().controlRevision())return;
                STATES.put(key,state);ClientArcadeEvents.applySession(state.session());
            }
            public void data(Connection source,NetplayNetwork.Data data){var run=RUNS.get(new Key(source,data.chunk().session()));if(run!=null)run.receive(data.chunk(),data.port());}
            public void gun(Connection source,NetplayNetwork.GunFrame input){
                if(source!=current()||Minecraft.getInstance().level==null)return;
                var key=new Key(source,input.session());var state=STATES.get(key);var run=RUNS.get(key);
                if(state==null||run==null||!state.session().computeHost()||!state.session().variant().isZapper()
                        ||state.session().epoch()!=input.epoch()||!state.ticket().equals(input.ticket())||!run.grant().ticket().equals(input.ticket()))return;
                run.authoritativeGun(input.revision(),input.sequence(),input.buttons(),input.aim());
            }
        }));}
    }
    static NetplayNetwork.State state(long session){return STATES.get(new Key(current(),session));}
    static boolean nativeSlotHeld(long session){var run=RUNS.get(new Key(current(),session));return run!=null&&run.nativeSlotHeld();}
    static NetplayProcess start(NetplayNetwork.State state)throws IllegalStateException {
        Connection connection=current();var key=new Key(connection,state.session().sessionId());
        String sha=state.session().romSha256();
        if(state.jniTrial()&&!JniNetplayConsent.allowed())throw new IllegalStateException("本机已停用 FC JNI 或平台不支持；Windows x64 可用 /gameconsole-jni-netplay 恢复");
        var grant=new NetplayProcess.Grant(state.session().sessionId(),state.ticket(),state.session().computeHost(),state.player());
        java.util.concurrent.Callable<byte[]> content=()->{var rom=ClientRomLibrary.loadBySha256(sha);if(rom==null||!rom.sha256().equalsIgnoreCase(sha))throw new IllegalStateException("Netplay ROM 校验失败");return rom.bytes();};
        var run=state.jniTrial()?new NetplayProcess(grant,content,chunk->NetplayNetwork.upstream(connection,chunk),true,state.session().variant().isZapper())
                :new NetplayProcess(grant,content,chunk->NetplayNetwork.upstream(connection,chunk),state.session().variant().isZapper()?NetplayProfile.fcZapper():NetplayProfile.fc(),Map::of);
        var old=RUNS.remove(key);if(old!=null){NetplayNetwork.unbind(connection,old);old.close();}
        NetplayNetwork.bind(connection,run);try{RUNS.put(key,run);run.start();return run;}catch(RuntimeException failure){RUNS.remove(key,run);NetplayNetwork.unbind(connection,run);run.close();throw failure;}
    }
    static void stop(NetplayProcess run){RUNS.entrySet().removeIf(e->{if(e.getValue()!=run)return false;NetplayNetwork.unbind(e.getKey().source(),run);return true;});run.close();}
    static void forget(long session){STATES.remove(new Key(current(),session));}
    @EventBusSubscriber(modid="piq_fc_arcade",value=Dist.CLIENT)
    public static class Events {
        @SubscribeEvent public static void logout(net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.LoggingOut event){for(var e:RUNS.entrySet()){NetplayNetwork.unbind(e.getKey().source(),e.getValue());e.getValue().close();}RUNS.clear();STATES.clear();}
    }
}
