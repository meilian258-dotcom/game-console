package cn.piq.fcarcade.cabinet;

import cn.piq.fcarcade.client.cabinet.CabinetGameClientPlan;
import io.netty.buffer.Unpooled;
import java.io.IOException;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.jar.JarFile;
import net.minecraft.core.*;
import net.minecraft.network.*;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.*;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Executes packaged code with synthetic opaque bytes only. Wiring checks are explicitly static. */
public final class CabinetGameReuse35Probe {
    private static int checks, wires, maxWire, fsChecks, replyChecks, wiringChecks;
    private static final String BASE="cn/piq/fcarcade/", BACKEND="piq_native_arcade:mame";
    @FunctionalInterface private interface Throwing { void run() throws Exception; }
    private static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    private static void denied(Throwing action,String why)throws Exception{
        boolean rejected=false;try{action.run();}catch(Exception expected){rejected=true;}check(rejected,why);
    }
    private static CabinetGameManifest.Entry entry(String name,byte[] bytes){return new CabinetGameManifest.Entry(name,CabinetGameManifest.digest(bytes),bytes.length);}
    private static byte[] bytes(int length,int seed){var data=new byte[length];new Random(seed).nextBytes(data);return data;}
    private static CabinetGameManifest manifest(byte[][] data){return new CabinetGameManifest(BACKEND,List.of(entry("synthetic.zip",data[0]),entry("neogeo.zip",data[1]),entry("qsound_hle.zip",data[2])));}
    private static void commit(CabinetGameStore store,int index,CabinetGameManifest.Entry entry,byte[] bytes)throws Exception{
        Path temp=store.temporary(UUID.randomUUID(),index);
        for(int offset=0;offset<bytes.length;offset+=CabinetGameManifest.CHUNK)
            store.append(temp,offset,Arrays.copyOfRange(bytes,offset,Math.min(bytes.length,offset+CabinetGameManifest.CHUNK)));
        store.commit(temp,entry);
    }
    private static int missing(CabinetGameStore store,CabinetGameManifest manifest)throws Exception{
        int mask=0;for(int i=0;i<manifest.files().size();i++)if(!store.contains(manifest.files().get(i)))mask|=1<<i;return mask;
    }
    private static void filesystem(Path work)throws Exception{
        int before=checks;Files.createDirectories(work);
        byte[][] data={bytes(24589,1),bytes(31,2),bytes(47,3)};var manifest=manifest(data);
        for(int wanted=0;wanted<=7;wanted++){
            var store=new CabinetGameStore(work.resolve("objects-"+wanted));
            for(int i=0;i<3;i++)if((wanted&(1<<i))==0)commit(store,i,manifest.files().get(i),data[i]);
            var plan=CabinetGameUploadPlan.fromVerifiedMissing(manifest,missing(store,manifest));
            check(plan.missingMask()==wanted,"Actual SHA/size object presence produces exact mask "+wanted);
            long expected=0;for(int i=0;i<3;i++)if((wanted&(1<<i))!=0)expected+=data[i].length;
            check(plan.missingBytes()==expected,"Quota counts missing bytes only");
            int putBytes=0;
            for(int i=0;i<3;i++)if((wanted&(1<<i))!=0){
                int index=i;check(plan.nextFile()==i&&plan.offset()==0,"Skip cached indices before PUT");
                denied(()->plan.validate(index,1,1),"Gap rejected before filesystem append");
                Path temporary=store.temporary(UUID.randomUUID(),i);
                for(int offset=0;offset<data[i].length;){
                    int length=Math.min(CabinetGameManifest.CHUNK,data[i].length-offset);
                    plan.validate(i,offset,length);store.append(temporary,offset,Arrays.copyOfRange(data[i],offset,offset+length));
                    if(offset+length==data[i].length)store.commit(temporary,manifest.files().get(i));
                    plan.accepted(i,offset,length);offset+=length;putBytes+=length;
                }
                check(store.contains(manifest.files().get(i)),"Only fully committed object becomes reusable");
            }
            check(putBytes==expected,"Measured PUT payload equals missing bytes");
            check(missing(store,manifest)==0,"Final whole manifest exists and verifies");
            var bindings=new CabinetSharedGameData();var target=new CabinetTarget(ResourceLocation.parse("minecraft:overworld"),new BlockPos(0,64,0),UUID.randomUUID(),true);
            var old=new CabinetGameManifest(BACKEND,List.of(entry("old.zip",new byte[]{8})));
            bindings.put(target,old);plan.requireComplete();check(bindings.find(target,BACKEND).equals(old),"Even zero PUTs do not replace old binding before END");
            plan.finish(()->bindings.put(target,manifest));check(bindings.find(target,BACKEND).equals(manifest),"One explicit completed END replaces binding");
            denied(()->plan.finish(()->bindings.put(target,old)),"Duplicate END rejected");
            check(bindings.find(target,BACKEND).equals(manifest),"Replay cannot revert binding");
        }
        var aliases=new CabinetGameManifest(BACKEND,List.of(entry("synthetic.zip",data[0]),entry("neogeo.zip",data[0]),entry("qsound_hle.zip",data[0])));
        var aliasPlan=CabinetGameUploadPlan.fromVerifiedMissing(aliases,7);
        check(aliasPlan.missingMask()==1&&aliasPlan.missingBytes()==data[0].length,"Same-hash named aliases upload one physical object");
        var aliasStore=new CabinetGameStore(work.resolve("aliases"));commit(aliasStore,0,aliases.files().getFirst(),data[0]);
        check(missing(aliasStore,aliases)==0,"One object satisfies all allowed alias names");
        denied(()->CabinetGameUploadPlan.fromVerifiedMissing(aliases,1),"Contradictory object-presence alias rejected");
        var wrongSize=new CabinetGameManifest(BACKEND,List.of(aliases.files().getFirst(),new CabinetGameManifest.Entry("neogeo.zip",aliases.gameHash(),1)));
        denied(()->CabinetGameUploadPlan.fromVerifiedMissing(wrongSize,3),"Same hash conflicting sizes rejected");
        var closed=new CabinetGameUploadPlan(manifest,0);var commits=new AtomicInteger();closed.close();
        denied(()->closed.finish(commits::incrementAndGet),"Cancelled zero-PUT plan cannot commit");check(commits.get()==0,"Cancellation leaves binding unchanged");
        Path full=work.resolve("full-cache");var fullStore=new CabinetGameStore(full);commit(fullStore,0,manifest.files().getFirst(),data[0]);
        for(int i=1;i<CabinetGameStore.MAX_OBJECTS;i++)Files.write(full.resolve("fixture-"+i+".data"),new byte[]{1},StandardOpenOption.CREATE_NEW);
        denied(()->fullStore.checkQuota(0),"Actual full object-count cap reached");
        var one=new CabinetGameManifest(BACKEND,List.of(manifest.files().getFirst()));
        var fullHit=CabinetGameUploadPlan.fromVerifiedMissing(one,missing(fullStore,one));
        check(fullHit.missingBytes()==0,"Full verified cache produces zero-growth plan");fullHit.finish(()->{});
        Path corrupt=full.resolve(one.gameHash()+".data");var changed=data[0].clone();changed[0]^=1;Files.write(corrupt,changed);
        denied(()->fullStore.contains(one.files().getFirst()),"Same size wrong hash cannot be dedup hit");check(Arrays.equals(changed,Files.readAllBytes(corrupt)),"Corrupt object not silently overwritten");

        Path source=Files.createDirectory(work.resolve("source")),root=work.resolve("client-cache");var sources=new ArrayList<Path>();
        for(int i=0;i<3;i++){Path path=source.resolve(manifest.files().get(i).name());Files.write(path,data[i]);sources.add(path);}
        Path directory=CabinetGameClientPlan.cacheDirectory(root,"server:synthetic:25565",manifest);
        check(!directory.equals(CabinetGameClientPlan.cacheDirectory(root,"server:synthetic:25566",manifest)),"Cache does not cross server context");
        AtomicLong quota=new AtomicLong();CabinetGameClientPlan.cacheSelected(sources,directory,manifest,UUID.randomUUID(),()->{},quota::addAndGet);
        check(quota.get()==manifest.size(),"Initial local quota is exactly missing named files");
        var local=CabinetGameClientPlan.inspectCache(directory,manifest,()->{});AtomicInteger requests=new AtomicInteger();local.transferMissing((i,e)->requests.incrementAndGet());
        check(local.missingBytes()==0&&requests.get()==0,"Uploader ordinary reopen uses existing cache with zero GETs");
        CabinetGameClientPlan.cacheSelected(sources,directory,manifest,UUID.randomUUID(),()->{},additional->{throw new IOException("full");});
        check(true,"Fully cached selection never asks quota for new bytes");
        Files.delete(directory.resolve("neogeo.zip"));var partial=CabinetGameClientPlan.inspectCache(directory,manifest,()->{});var indices=new ArrayList<Integer>();partial.transferMissing((i,e)->indices.add(i));
        check(partial.missingMask()==2&&indices.equals(List.of(1))&&partial.missingBytes()==data[1].length,"Only missing BIOS triggers download selection");
        CabinetGameClientPlan.cacheSelected(sources,directory,manifest,UUID.randomUUID(),()->{},additional->check(additional==data[1].length,"Only missing BIOS needs cache growth"));
        Files.write(sources.getFirst(),new byte[]{0});
        denied(()->CabinetGameClientPlan.verifySelected(sources,manifest,()->{}),"Changed selected source rejected before upload OPEN");
        check(Arrays.equals(Files.readAllBytes(directory.resolve("synthetic.zip")),data[0]),"Cached shared object does not alias mutable source");
        Files.write(sources.getFirst(),data[0]);Files.write(directory.resolve("neogeo.zip"),bytes(data[1].length,88));
        denied(()->CabinetGameClientPlan.inspectCache(directory,manifest,()->{}),"Existing wrong BIOS hash fails closed without silent replacement");
        Path cancelled=work.resolve("cancelled-cache");var stops=new AtomicInteger();
        denied(()->CabinetGameClientPlan.cacheSelected(sources,cancelled,manifest,UUID.randomUUID(),()->{if(stops.incrementAndGet()==12)throw new IOException("cancel");},additional->{}),"Cancellation during local copy aborts staging");
        try(var paths=Files.walk(cancelled)){check(paths.noneMatch(p->p.getFileName().toString().endsWith(".part")),"Only owned partial staging cleaned after cancellation");}
        for(int i=0;i<3;i++)check(Arrays.equals(Files.readAllBytes(sources.get(i)),data[i]),"Synthetic source remains untouched");
        fsChecks=checks-before;
    }
    private static byte[] encode(CustomPacketPayload payload,boolean up){var buffer=new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);try{
        if(up)ServerboundCustomPayloadPacket.STREAM_CODEC.encode(buffer,new ServerboundCustomPayloadPacket(payload));else ClientboundCustomPayloadPacket.GAMEPLAY_STREAM_CODEC.encode(buffer,new ClientboundCustomPayloadPacket(payload));
        byte[] raw=new byte[buffer.readableBytes()];buffer.readBytes(raw);maxWire=Math.max(maxWire,raw.length);return raw;
    }finally{buffer.release();}}
    private static CustomPacketPayload decode(byte[] raw,boolean up){var buffer=new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(raw),RegistryAccess.EMPTY);try{
        var payload=up?ServerboundCustomPayloadPacket.STREAM_CODEC.decode(buffer).payload():ClientboundCustomPayloadPacket.GAMEPLAY_STREAM_CODEC.decode(buffer).payload();
        check(!buffer.isReadable(),"Outer packet consumed exactly");return payload;
    }finally{buffer.release();}}
    private static void wire(CustomPacketPayload payload,boolean up)throws Exception{
        byte[] raw=encode(payload,up);check(Arrays.equals(raw,encode(decode(raw,up),up)),"Actual registered outer payload roundtrip");wires++;
        for(int size:new int[]{0,1,raw.length-1})denied(()->decode(Arrays.copyOf(raw,size),up),"Truncated outer payload rejected");
    }
    private static void wire()throws Exception{
        CabinetGameNetwork.register(new RegisterPayloadHandlersEvent());
        var field=NetworkRegistry.class.getDeclaredField("PAYLOAD_REGISTRATIONS");field.setAccessible(true);
        var registrations=(Map<?,?>)((Map<?,?>)field.get(null)).get(ConnectionProtocol.PLAY);
        for(var id:List.of(CabinetGameNetwork.Command.TYPE.id(),CabinetGameNetwork.Reply.TYPE.id())){
            var registered=(PayloadRegistration<?>)registrations.get(id);
            check(registered!=null&&!registered.optional()&&registered.version().equals("cabinet-game-2"),"Actual mandatory cabinet-game-2 registration");
        }
        check(NetworkRegistry.getCodec(CabinetGameNetwork.Reply.TYPE.id(),ConnectionProtocol.PLAY,PacketFlow.SERVERBOUND)==null,"Client cannot mint game reply");
        check(NetworkRegistry.getCodec(CabinetGameNetwork.Command.TYPE.id(),ConnectionProtocol.PLAY,PacketFlow.CLIENTBOUND)==null,"Server cannot send client command as reply");
        var full=manifest(new byte[][]{bytes(9,1),bytes(11,2),bytes(13,3)});UUID tx=UUID.randomUUID(),lease=UUID.randomUUID();var backend=ResourceLocation.parse(BACKEND);
        for(int count=1;count<=3;count++){
            var manifest=new CabinetGameManifest(BACKEND,full.files().subList(0,count));int bound=(1<<count)-1;
            for(int mask=0;mask<=bound;mask++){
                wire(new CabinetGameNetwork.Reply(tx,0,true,manifest,mask,0,0,new byte[0],"计划"),false);
                check(CabinetGameClientPlan.validReply(true,true,manifest,manifest,mask),"Upload accepts every in-range exact OPEN mask");
                if(mask!=0){int bad=mask;denied(()->new CabinetGameNetwork.Reply(tx,1,true,manifest,bad,0,0,new byte[0],""),"Mask forbidden on END or chunk");
                    denied(()->new CabinetGameNetwork.Reply(tx,0,false,manifest,bad,0,0,new byte[0],""),"Mask forbidden on failure");}
            }
            for(int invalid:new int[]{-1,bound+1,Integer.MAX_VALUE})denied(()->new CabinetGameNetwork.Reply(tx,0,true,manifest,invalid,0,0,new byte[0],""),"Out-of-range mask rejected");
            for(boolean success:new boolean[]{false,true})for(boolean upload:new boolean[]{false,true})for(int mask=-1;mask<=8;mask++){
                boolean expected=success&&upload?mask>=0&&mask<=bound:mask==0;
                check(CabinetGameClientPlan.validReply(success,upload,manifest,manifest,mask)==expected,"Exact reply operation/mask truth table");
            }
        }
        wire(new CabinetGameNetwork.Command(tx,0,CabinetGameNetwork.OPEN,lease,backend,full,0,0,new byte[0]),true);
        wire(new CabinetGameNetwork.Command(tx,0,CabinetGameNetwork.OPEN,lease,backend,null,0,0,new byte[0]),true);
        wire(new CabinetGameNetwork.Command(tx,1,CabinetGameNetwork.PUT,lease,backend,null,2,0,new byte[CabinetGameManifest.CHUNK]),true);
        wire(new CabinetGameNetwork.Command(tx,2,CabinetGameNetwork.GET,lease,backend,null,2,0,new byte[0]),true);
        wire(new CabinetGameNetwork.Command(tx,3,CabinetGameNetwork.END,lease,backend,null,0,0,new byte[0]),true);
        wire(new CabinetGameNetwork.Command(tx,4,CabinetGameNetwork.CANCEL,lease,backend,null,0,0,new byte[0]),true);
        wire(new CabinetGameNetwork.Reply(tx,1,true,null,0,2,0,new byte[CabinetGameManifest.CHUNK],""),false);
        wire(new CabinetGameNetwork.Reply(tx,3,true,full,0,0,0,new byte[0],"END"),false);
        wire(new CabinetGameNetwork.Reply(tx,0,false,null,0,0,0,new byte[0],"权限变化"),false);
        denied(()->new CabinetGameNetwork.Reply(tx,0,true,null,1,0,0,new byte[0],""),"No mask without manifest");
        check(maxWire<32768,"Largest actual wire fits shared 32KiB accounting");
    }
    @SuppressWarnings("unchecked") private static void replyIdentity()throws Exception{
        int before=checks;Class<?> shared=Class.forName("cn.piq.fcarcade.client.cabinet.CabinetSharedGames");
        Class<?> transfer=Class.forName(shared.getName()+"$Transfer"),pending=Class.forName(shared.getName()+"$Pending");
        Constructor<?> makeTransfer=transfer.getDeclaredConstructors()[0];makeTransfer.setAccessible(true);
        Constructor<?> makePending=pending.getDeclaredConstructors()[0];makePending.setAccessible(true);
        Field activeField=shared.getDeclaredField("ACTIVE");activeField.setAccessible(true);var active=(Map<UUID,Object>)activeField.get(null);
        Method dispatch=shared.getDeclaredMethod("reply",Connection.class,CabinetGameNetwork.Reply.class);dispatch.setAccessible(true);
        var source=new Connection(PacketFlow.CLIENTBOUND);var wrong=new Connection(PacketFlow.CLIENTBOUND);var manifest=manifest(new byte[][]{bytes(5,1),bytes(7,2),bytes(9,3)});
        var target=new CabinetTarget(ResourceLocation.parse("minecraft:overworld"),new BlockPos(0,64,0),UUID.randomUUID(),true);
        for(int scenario=0;scenario<7;scenario++){
            UUID lease=UUID.randomUUID();var launch=new CabinetNetwork.Launch(target,ResourceLocation.parse(BACKEND),lease);Object job=makeTransfer.newInstance(launch,source);
            Field idField=transfer.getDeclaredField("id");idField.setAccessible(true);UUID tx=(UUID)idField.get(job);
            Field pendingField=transfer.getDeclaredField("pending");pendingField.setAccessible(true);var queue=(Map<Integer,Object>)pendingField.get(job);
            var command=new CabinetGameNetwork.Command(tx,0,CabinetGameNetwork.OPEN,lease,ResourceLocation.parse(BACKEND),scenario==4?null:manifest,0,0,new byte[0]);
            var future=new CompletableFuture<CabinetGameNetwork.Reply>();queue.put(0,makePending.newInstance(command,future));active.put(tx,job);
            if(scenario==3){Field closed=transfer.getDeclaredField("closed");closed.setAccessible(true);((AtomicBoolean)closed.get(job)).set(true);}
            var returned=scenario==5?new CabinetGameManifest(BACKEND,List.of(entry("other.zip",new byte[]{7}))):manifest;
            var response=new CabinetGameNetwork.Reply(scenario==2?UUID.randomUUID():tx,0,true,returned,scenario==5?0:1,0,0,new byte[0],"");
            dispatch.invoke(null,scenario==1?wrong:source,response);
            if(scenario==1||scenario==2||scenario==3)check(!future.isDone(),"Wrong source/transaction/closed lease reply cannot complete request");
            else if(scenario==4||scenario==5)check(future.isCompletedExceptionally(),"Mask for download or mismatched upload manifest rejected by actual sink");
            else check(future.isDone()&&!future.isCompletedExceptionally()&&future.join()==response,"Exact current transfer receives OPEN reply");
            active.remove(tx,job);
        }
        check(active.isEmpty(),"Probe leaves no transfer state");replyChecks=checks-before;
    }
    private static ClassNode read(Path jar,String name)throws Exception{
        try(var input=new JarFile(jar.toFile())){var node=new ClassNode();new ClassReader(input.getInputStream(input.getJarEntry(name+".class")).readAllBytes()).accept(node,0);return node;}
    }
    private static List<MethodInsnNode> calls(MethodNode method){var calls=new ArrayList<MethodInsnNode>();for(var instruction:method.instructions)if(instruction instanceof MethodInsnNode call)calls.add(call);return calls;}
    private static boolean has(MethodNode method,String owner,String name){return calls(method).stream().anyMatch(c->c.owner.equals(owner)&&c.name.equals(name));}
    private static MethodNode method(ClassNode node,String name){return node.methods.stream().filter(m->m.name.equals(name)).findFirst().orElseThrow();}
    private static MethodNode containing(ClassNode node,String owner,String name){return node.methods.stream().filter(m->has(m,owner,name)).findFirst().orElseThrow();}
    private static int index(MethodNode method,String owner,String name){return method.instructions.indexOf(calls(method).stream().filter(c->c.owner.equals(owner)&&c.name.equals(name)).findFirst().orElseThrow());}
    private static void wiring(Path jar)throws Exception{
        int before=checks;String serviceName=BASE+"cabinet/CabinetSharedGameService",planName=BASE+"cabinet/CabinetGameUploadPlan",storeName=BASE+"cabinet/CabinetGameStore";
        var service=read(jar,serviceName);var open=method(service,"open");var valid=method(service,"valid");var process=method(service,"process");
        check(has(open,"net/minecraft/server/level/ServerPlayer","hasPermissions")&&has(open,BASE+"cabinet/CabinetRooms","canConfigureGame"),"OPEN retains administrator and pre-start game authority");
        for(String name:List.of("current","target"))check(has(valid,serviceName,name),"Per-command identity/connection/hardware target recheck "+name);
        check(has(valid,"net/minecraft/server/level/ServerPlayer","hasPermissions")&&has(valid,BASE+"cabinet/CabinetRooms","canConfigureGame"),"Every upload command retains current permission recheck");
        var openIo=containing(service,planName,"fromVerifiedMissing");check(openIo.name.startsWith("lambda$open$"),"Object lookup happens in OPEN worker callback");
        check(index(openIo,storeName,"contains")<index(openIo,planName,"fromVerifiedMissing"),"Actual contains SHA/size validation precedes missing plan");
        check(index(openIo,planName,"missingBytes")<index(openIo,storeName,"checkQuota"),"Quota uses missing plan, not total manifest bytes");
        int quotaIndex=index(openIo,storeName,"checkQuota");boolean skipsZeroQuota=false;
        for(var instruction:openIo.instructions)if(instruction instanceof JumpInsnNode jump&&jump.getOpcode()==Opcodes.IFLE
                &&openIo.instructions.indexOf(jump)>index(openIo,planName,"missingBytes")&&openIo.instructions.indexOf(jump)<quotaIndex
                &&openIo.instructions.indexOf(jump.label)>quotaIndex)skipsZeroQuota=true;
        check(skipsZeroQuota,"Actual zero-missing branch skips quota, including full storage");
        check(index(process,planName,"validate")<index(process,storeName,"append")&&index(process,storeName,"commit")<index(process,planName,"accepted"),"PUT checks plan before mutation and advances only after commit");
        check(has(process,planName,"requireComplete")&&has(process,storeName,"contains"),"END requires complete plan and all object hashes again");
        var finish=containing(service,planName,"finish");check(finish.name.startsWith("lambda$process$"),"END binding finish is server callback");
        check(index(finish,serviceName,"valid")<index(finish,planName,"finish")&&index(finish,planName,"finish")<index(finish,serviceName,"enqueue"),"Fresh authority before END commit; grant reply only after commit");
        for(var m:service.methods)if(has(m,BASE+"cabinet/CabinetSharedGameData","put"))check(m.name.startsWith("lambda$process$"),"No binding write during OPEN/PUT/cancel");
        check(has(method(service,"close"),planName,"close"),"Cancellation permanently closes upload plan");
        check(has(method(service,"flush"),serviceName,"valid"),"Queued replies revalidate live authority before send");
        var rooms=read(jar,BASE+"cabinet/CabinetRooms");check(has(method(rooms,"gameTarget"),BASE+"cabinet/CabinetRooms","validateLease"),"Linked cabinet manifest target remains lease-validated room primary");
        check(has(method(rooms,"canConfigureGame"),BASE+"cabinet/CabinetSynchronizer","started"),"Running sync room cannot change game");

        String sharedName=BASE+"client/cabinet/CabinetSharedGames",clientPlan=BASE+"client/cabinet/CabinetGameClientPlan";
        var shared=read(jar,sharedName);var resolve=method(shared,"resolve");var reply=method(shared,"reply");
        check(index(resolve,clientPlan,"cacheSelected")<index(resolve,sharedName+"$Transfer","call"),"Selected local cache prepared before upload OPEN");
        check(calls(resolve).stream().filter(c->c.owner.equals(clientPlan)&&c.name.equals("transferMissing")).count()==2,"Both PUT and GET dispatch only missing selector");
        check(has(resolve,clientPlan,"verifySelected")&&calls(resolve).stream().filter(c->c.owner.equals(clientPlan)&&c.name.equals("inspectCache")).count()>=2,"Source and downloaded full manifest rechecked before END");
        check(has(reply,clientPlan,"validReply"),"Actual sink validates mask against exact operation/manifest");
        check(shared.methods.stream().flatMap(m->calls(m).stream()).noneMatch(c->c.owner.equals(BASE+"client/cabinet/CabinetGameSelection")&&c.name.equals("load")),"Old local binding never overrides server manifest");
        check(!has(method(shared,"cacheRoot"),sharedName,"checkCacheQuota"),"Full valid cache can be reused without new-growth quota failure");
        var transfer=read(jar,sharedName+"$Transfer");check(has(method(transfer,"abort"),"java/util/concurrent/atomic/AtomicBoolean","compareAndSet"),"Cancellation is exact transfer and idempotent");
        check(has(method(shared,"resolved"),"net/minecraft/client/Minecraft","getConnection"),"Resolved hash tied to current connection");
        var backends=read(jar,BASE+"client/cabinet/CabinetClientBackends");
        check(has(method(backends,"startGame"),backends.name,"startSynchronous"),"Existing media/sync branch is preserved");
        check(has(method(backends,"openBackend"),backends.name,"startGame")&&has(method(backends,"start"),backends.name,"startGame"),"Ordinary reopen and explicit picker retain shared start route");
        check(has(method(backends,"assignment"),backends.name,"startGame"),"LOCAL_SYNC guest enters validated shared-game start route");
        check(!has(method(backends,"assignment"),sharedName,"resolve")&&has(method(backends,"assignment"),BASE+"client/cabinet/CabinetMediaStream","<init>"),"Media guest still directly receives media without loading a ROM");
        int resolverCalls=0;try(var archive=new JarFile(jar.toFile())){for(var e:Collections.list(archive.entries()))if(e.getName().equals(backends.name+".class")||e.getName().startsWith(backends.name+"$")&&e.getName().endsWith(".class")){
            var node=read(jar,e.getName().substring(0,e.getName().length()-6));for(var m:node.methods)for(var call:calls(m))if(call.owner.equals(sharedName)&&call.name.equals("resolve"))resolverCalls++;
        }}check(resolverCalls==2,"Exactly two real worker entry points share resolver: media host and LOCAL_SYNC worker");
        var cleanup=backends.methods.stream().filter(m->m.name.equals("stop")&&m.desc.equals("(Ljava/lang/String;Z)V")).findFirst().orElseThrow();
        check(has(cleanup,sharedName,"cancel"),"Stopping old binding cancels its shared transfer");
        for(var adapter:backends.methods)if(adapter.name.equals("stop")&&adapter.desc.equals("(Ljava/lang/String;ZLjava/lang/Throwable;)V")){
            check(calls(adapter).stream().anyMatch(c->c.owner.equals(backends.name)&&c.name.equals("stop")&&c.desc.equals(cleanup.desc)),"Error adapter still delegates to exact cancellation cleanup");
        }
        wiringChecks=checks-before;
    }
    public static void main(String[] args)throws Exception{
        var output=System.out;Path jar=Path.of(args[0]).toRealPath();
        for(var type:List.of(CabinetGameManifest.class,CabinetGameStore.class,CabinetGameTransfer.class,CabinetGameUploadPlan.class,CabinetGameClientPlan.class,CabinetSharedGameData.class,CabinetGameNetwork.class,CabinetGameNetwork.Command.class,CabinetGameNetwork.Reply.class))
            check(Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath().equals(jar),"Production class loaded only from final JAR "+type.getName());
        net.neoforged.fml.loading.LoadingModList.of(List.of(),List.of(),List.of(),List.of(),Map.of());
        net.minecraft.SharedConstants.tryDetectVersion();net.minecraft.server.Bootstrap.bootStrap();
        filesystem(Path.of(args[1]));wire();replyIdentity();wiring(jar);
        output.println(new com.google.gson.Gson().toJson(Map.of("ok",true,"assertions",checks,"filesystem_assertions",fsChecks,"actual_reply_assertions",replyChecks,"compiled_wiring_assertions",wiringChecks,"outer_roundtrips",wires,"max_outer_bytes",maxWire,"production_origin","final-jar-only","minecraft_world_started",false,"real_socket_opened",false)));
    }
}
