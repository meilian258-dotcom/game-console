// SPDX-License-Identifier: GPL-3.0-or-later
import cn.piq.mdhome.client.MdProfile;
import cn.piq.fcarcade.netplay.*;
import cn.piq.retro.libretro.*;
import cn.piq.retro.libretro.jni.NativeLibretroBridge;
import cn.piq.retro.storage.RuntimeWorkspace;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/** Packaged public API, real independent GX JNI owners. No Minecraft or MEDIA route. */
public final class MdAuthorityNetplayProbe {
  static final Map<Object,NetplayProcess> runs=new ConcurrentHashMap<>();
  static final ScheduledThreadPoolExecutor wire=new ScheduledThreadPoolExecutor(1);
  static NetplayRelay<Object> relay;static NetplayProfile profile;static byte[] rom,saved;static Path out;
  static int saves,finishes,aborts;static volatile NetplayProcess host;
  static void healthy(){for(var p:runs.values())if(p.error()!=null)throw new AssertionError(runs.values().stream().map(NetplayProcess::diagnostic).toList()+" relay="+relay.lastRejection());}
  static void until(java.util.function.BooleanSupplier c)throws Exception {
    long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(35);
    while(!c.getAsBoolean()&&System.nanoTime()<end){healthy();Thread.sleep(10);}
    healthy();if(!c.getAsBoolean())throw new AssertionError("Timeout "+runs.values().stream().map(NetplayProcess::diagnostic).toList()+" relay="+relay.lastRejection());
  }
  static NetplayProcess start(Object key,boolean owner,boolean resume)throws Exception {
    // Match the server-thread transaction: renew must not see a granted-but-unregistered peer.
    synchronized(relay) {
    var t=owner?relay.grant(key,0):relay.grantObserver(key);
    var p=new NetplayProcess(new NetplayProcess.Grant(1023,t.id(),owner,false,owner?0:-1),()->rom,
      chunk->wire.schedule(()->relay.receive(key,chunk),35,TimeUnit.MILLISECONDS),profile,Map::of,true,false,owner);
    if(owner)p.persistence(new NetplayProcess.Persistence(){
      public byte[] load(NetplaySaveState.Identity id){if(saved!=null)NetplaySaveState.decode(saved,id);return resume?saved:null;}
      public CompletableFuture<Void> save(byte[] bytes){try{
        var parts=NetplaySaveState.decode(bytes,NetplaySaveState.identity(profile,NetplaySaveState.hash(rom),Map.of()));
        if(parts.ram().length!=65536||parts.state().length!=1560608)throw new AssertionError("state domains");
        Files.write(out.resolve("save-"+(++saves)+".pns"),bytes,StandardOpenOption.CREATE_NEW);
        saved=bytes.clone();return CompletableFuture.completedFuture(null);
      }catch(Exception e){return CompletableFuture.failedFuture(e);}}
      public CompletableFuture<Void> finish(){finishes++;return CompletableFuture.completedFuture(null);}
      public void abort(){aborts++;}
    });
    runs.put(key,p);p.start();return p;
    }
  }
  static void stop(Object key)throws Exception {
    var p=runs.get(key);p.close();until(()->p.status().equals("已结束"));
    runs.remove(key);relay.revoke(key);
  }
  static void room(Object hostKey) {
    relay=new NetplayRelay<>(1023,hostKey,(target,d)->wire.schedule(()->{var p=runs.get(target);if(p!=null)p.receive(d.chunk(),d.port());},35,TimeUnit.MILLISECONDS),2);
  }
  static void proveFixtureRace()throws Exception {
    var deliveries=new ArrayList<NetplayRelay.Delivery>();Object authority=new Object(),pending=new Object();
    try(var old=new NetplayRelay<Object>(99,authority,(who,delivery)->deliveries.add(delivery),2)) {
      old.grantObserver(pending);
      old.renew(Set.of(authority)); // The previous fixture could interleave exactly here, before runs.put.
      if(deliveries.size()!=2||deliveries.stream().anyMatch(d->d.chunk().kind()!=NetplayChunk.CLOSE))throw new AssertionError("Fixture race not reproduced");
    }
    deliveries.clear();
    try(var fixed=new NetplayRelay<Object>(100,authority,(who,delivery)->deliveries.add(delivery),2)) {
      var registered=new HashSet<Object>();registered.add(authority);
      synchronized(fixed){fixed.grantObserver(pending);registered.add(pending);}
      synchronized(fixed){fixed.renew(Set.copyOf(registered));}
      if(!deliveries.isEmpty())throw new AssertionError("Atomic fixture registration revoked pending peer");
    }
    Files.writeString(out.resolve("fixture-race.json"),"{\"oldGrantBeforeRegisterCanRevoke\":true,\"fixedAtomicGrantRegisterRenew\":true,\"productionChanged\":false}");
  }
  static NetplayProcess.Frame picture(NetplayProcess p)throws Exception {
    NetplayProcess.Frame[] frame={null};until(()->(frame[0]=p.poll())!=null);
    var f=frame[0];if(f.sampleRate()!=48000||f.stereo().length==0||f.width()!=320||f.height()!=224||f.rgba().length!=320*224*4)
      throw new AssertionError("Expected actual 48k stereo/320x224 MD output "+f.width()+"x"+f.height()+" @"+f.sampleRate());
    return f;
  }
  public static void main(String[] args)throws Exception {
    out=Path.of(args[0]);RuntimeWorkspace.configure(Files.createDirectory(out.resolve("instance")));
    proveFixtureRace();
    rom=Files.readAllBytes(out.resolve("diagnostic.md"));
    if(Boolean.getBoolean("piq.probe.packaged")) {
      var declaration=Class.forName("cn.piq.mdhome.MdNetplayProfile");
      if(!declaration.getField("AVAILABLE").getBoolean(null))throw new AssertionError("Packaged MD JNI gate disabled");
      profile=(NetplayProfile)declaration.getMethod("profile").invoke(null);
      var runtime=(LibretroProfile)declaration.getMethod("runtime").invoke(null);
      if(profile.owner()!=declaration||!runtime.equals(profile.jni())||profile.ports()!=2||profile.sampleRate()!=48000
          ||!runtime.devices().equals(List.of(513,513))||!profile.sha().equals(System.getProperty("piq.probe.expected.sha"))
          ||!profile.resource().equals(declaration.getField("RESOURCE").get(null)))throw new AssertionError("Packaged profile mismatch");
      try(var data=profile.owner().getResourceAsStream(profile.resource())) {
        if(data==null||!profile.sha().equals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data.readAllBytes()))))
          throw new AssertionError("Packaged core SHA/resource mismatch");
      }
    } else {
      var old=MdProfile.profile();String resource="/core/windows-x64/gx_probe.dll",sha=System.getProperty("piq.probe.core.sha");
      var jni=new LibretroProfile("Genesis Plus GX PIQ Netplay","md",true,List.of(513,513),false,old.options(),Map.of("windows-x64",new LibretroProfile.Artifact(resource,sha)));
      profile=new NetplayProfile(MdAuthorityNetplayProbe.class,resource,sha,"content.md",jni.options(),513,48000,16*1024*1024,2,jni);
    }
    Object hk=new Object(),p1=new Object(),p2=new Object(),watch=new Object();room(hk);
    wire.scheduleAtFixedRate(()->{var r=relay;if(r!=null)synchronized(r){r.renew(Set.copyOf(runs.keySet()));}},0,100,TimeUnit.MILLISECONDS);
    try {
      host=start(hk,true,false);until(host::ready);long idle=host.framesReceived();Thread.sleep(200);
      if(idle!=0||host.framesReceived()!=0||host.canSave())throw new AssertionError("prepared host ran before activation");
      host.activate();until(()->host.framesReceived()>100);
      var a=start(p1,false,false);until(a::ready);var b=start(p2,false,false);until(b::ready);
      // Both controlling clients are read-only peers. Only the authenticated server mailbox drives ports.
      a.inputRetroPad(4095);b.inputRetroPad(4095);
      if(a.canSave()||b.canSave())throw new AssertionError("Peer became save authority");
      wire.scheduleAtFixedRate(()->{var h=host;if(h!=null&&h.ready()){long f=h.framesReceived();h.cabinetInput(0,(f/30%2)==0?2:1024);h.cabinetInput(1,(f/45%2)==0?1:256);}},0,30,TimeUnit.MILLISECONDS);
      until(()->a.framesReceived()>180&&b.framesReceived()>180);picture(a);picture(b);
      var w=start(watch,false,false);until(w::ready);until(()->w.framesReceived()>150);picture(w);
      int beforePeerClose=saves;stop(p2);if(saves!=beforePeerClose)throw new AssertionError("Observer close wrote progress");
      Object p2again=new Object();var rejoin=start(p2again,false,false);until(rejoin::ready);until(()->rejoin.framesReceived()>150);picture(rejoin);
      host.saveNow().get(20,TimeUnit.SECONDS);if(saved==null)throw new AssertionError("No durable manual save");
      long oldFrame=NetplaySaveState.decode(saved,null).frame();
      stop(watch);stop(p2again);stop(p1);stop(hk);host=null;
      if(finishes!=1||saves<2)throw new AssertionError("No host durable final save");
      room(hk);host=start(hk,true,true);until(host::ready);Thread.sleep(150);
      if(host.framesReceived()!=0||host.canSave())throw new AssertionError("Resumed before activation");
      host.activate();until(()->host.framesReceived()>120);host.saveNow().get(20,TimeUnit.SECONDS);
      if(NetplaySaveState.decode(saved,null).frame()<oldFrame+100)throw new AssertionError("Save did not resume");
      stop(hk);host=null;if(finishes!=2||NativeLibretroBridge.availableSlots()!=4)throw new AssertionError("Final commit/owner slot leak");
      Files.writeString(out.resolve("session-result.json"),"{\"passed\":true,\"packagedProfileVerified\":"+Boolean.getBoolean("piq.probe.packaged")+",\"realJNI\":true,\"mediaFallback\":false,\"nativePeers\":4,\"authorityPorts\":2,\"sampleRate\":48000,\"oneWayDelayMs\":70,\"saves\":"+saves+",\"finishes\":"+finishes+",\"aborts\":"+aborts+",\"scope\":\"prepare/activate; server two-pad authority; read-only peers; CRC; latejoin; reconnect; durable manual/final save; reopen\"}");
      System.out.println("MD_AUTHORITY_JNI_NETPLAY_OK saves="+saves+" finishes="+finishes);
    } finally {
      for(var p:runs.values())p.close();
      long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(35);
      while(NativeLibretroBridge.availableSlots()!=4&&System.nanoTime()<end)Thread.sleep(20);
      wire.shutdown();wire.awaitTermination(5,TimeUnit.SECONDS);if(relay!=null)relay.close();
      if(NativeLibretroBridge.availableSlots()!=4)throw new AssertionError("Native close unconfirmed; no forced unload");
    }
  }
}
