package cn.piq.nativearcade.bridge;

import cn.piq.fcarcade.netplay.*;
import cn.piq.fcarcade.layout.CabinetVideoGeometry;
import cn.piq.nativearcade.NativeNetplayProfile;
import cn.piq.retro.storage.RuntimeWorkspace;
import cn.piq.retro.libretro.jni.NativeLibretroBridge;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/** Opt-in local licensed-ROM test. No Minecraft, no network service, no writes to the source ROM directory. */
public final class ArcadeJniObserverProbe {
    static final Map<Object,NetplayProcess> runs=new ConcurrentHashMap<>();
    static NetplayRelay<Object> relay;
    static NetplayProcess host;
    static void check(boolean condition,String reason){if(!condition)throw new AssertionError(reason);}
    static void await(BooleanSupplier done)throws Exception{
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
        while(!done.getAsBoolean()&&System.nanoTime()<end){
            relay.renew(Set.copyOf(runs.keySet()));
            for(var r:runs.values())check(r.error()==null,r.diagnostic());
            if(host!=null){host.cabinetInput(2,256);host.cabinetInput(3,512);}
            Thread.sleep(5);
        }
        for(var r:runs.values())check(r.error()==null,r.diagnostic());
        check(done.getAsBoolean(),"timeout "+runs.values().stream().map(NetplayProcess::diagnostic).toList());
    }
    static void stop(Object key)throws Exception{
        var process=runs.remove(key);relay.revoke(key);process.close();
        await(()->process.status().equals("已结束"));
    }
    public static void main(String[] args)throws Exception{
        Path root=Path.of(args[0]).toAbsolutePath().normalize(),rom=Path.of(args[1]).toRealPath();
        check(!Files.exists(root),"Refuse to replace QA evidence");Files.createDirectories(root.resolve("instance"));
        RuntimeWorkspace.configure(root.resolve("instance"));
        var profile=NativeNetplayProfile.profile(rom.getFileName().toString());
        check(profile.jniAspect()==NetplayProfile.JniAspect.PRESENTED,"Need the updated FBNeo DAR declaration");
        byte[] bytes=Files.readAllBytes(rom);String before=NetplaySaveState.hash(bytes);
        var auxiliary=new TreeMap<String,byte[]>();
        for(String name:List.of("pgm.zip","neogeo.zip","qsound_hle.zip","qsound.zip")){
            Path path=rom.getParent().resolve(name);if(Files.isRegularFile(path))auxiliary.put(name,Files.readAllBytes(path));
        }
        Object hostKey=new Object();relay=new NetplayRelay<>(906,hostKey,(key,message)->{
            var r=runs.get(key);if(r!=null)r.receive(message.chunk(),message.port());
        },4);
        long watchFrames=0,rejoinFrames=0;NetplayProcess.Frame picture=null;
        try{
            var ticket=relay.grant(hostKey,0);
            host=new NetplayProcess(new NetplayProcess.Grant(906,ticket.id(),true,true,0),()->bytes,p->relay.receive(hostKey,p),profile,()->auxiliary,true);
            runs.put(hostKey,host);host.start();await(()->host.framesReceived()>90);
            for(int attempt=0;attempt<2;attempt++){
                Object watchKey=new Object();var grant=relay.grant(watchKey,-1);
                var observer=new NetplayProcess(new NetplayProcess.Grant(906,grant.id(),false,false),()->bytes,
                        p->relay.receive(watchKey,p),profile,()->auxiliary,true);
                check(observer.grant().port()==-1&&!observer.grant().host()&&!observer.grant().player(),"Observer must remain seatless");
                runs.put(watchKey,observer);observer.start();await(observer::ready);observer.input(65535);
                await(()->observer.framesReceived()>90);
                picture=observer.poll();check(picture!=null&&picture.rgba().length==4*picture.width()*picture.height(),"Observer picture");
                check(picture.rotation()>=0&&picture.rotation()<=3,"Public CW rotation");
                check(picture.sampleRate()==48000,"Public audio rate");
                check(Double.isFinite(CabinetVideoGeometry.displayAspect(picture.aspect(),picture.rotation())),"Public raw DAR");
                check(!observer.canSave(),"Observer must not save");
                try{observer.checkpoint().get(5,TimeUnit.SECONDS);throw new AssertionError("Observer checkpoint permitted");}
                catch(ExecutionException expected){}
                if(attempt==0)watchFrames=observer.framesReceived();else rejoinFrames=observer.framesReceived();
                stop(watchKey);
            }
            stop(hostKey);
            check(NativeLibretroBridge.availableSlots()==4,"Core slots leaked");
            check(before.equals(NetplaySaveState.hash(Files.readAllBytes(rom))),"Source ROM changed");
            String json="{\"ok\":true,\"realJni\":true,\"minecraftStarted\":false,\"liveMultiplayerVerified\":false,\"romSha256\":\""+before+
                    "\",\"lateObserverFrames\":"+watchFrames+",\"rejoinedObserverFrames\":"+rejoinFrames+
                    ",\"width\":"+picture.width()+",\"height\":"+picture.height()+",\"clockwiseRotation\":"+picture.rotation()+
                    ",\"rawDar\":"+picture.aspect()+",\"displayDar\":"+CabinetVideoGeometry.displayAspect(picture.aspect(),picture.rotation())+
                    ",\"observerSeat\":-1,\"observerSaveBlocked\":true,\"freeNativeSlots\":4}";
            Files.writeString(root.resolve("result.json"),json+"\n",StandardOpenOption.CREATE_NEW);System.out.println(json);
        }finally{
            for(var r:runs.values())r.close();relay.close();
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
            while(NativeLibretroBridge.availableSlots()!=4&&System.nanoTime()<end)Thread.sleep(10);
            System.out.println("FREE_NATIVE_SLOTS="+NativeLibretroBridge.availableSlots());
        }
    }
}
