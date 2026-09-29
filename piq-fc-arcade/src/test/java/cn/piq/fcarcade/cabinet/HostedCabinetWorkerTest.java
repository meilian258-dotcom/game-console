package cn.piq.fcarcade.cabinet;

import cn.piq.fcarcade.server.hosted.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class HostedCabinetWorkerTest {
    @TempDir Path temp;
    private static final class Fake implements ServerCoreHandle {
        volatile boolean closed;final List<int[]> input=new CopyOnWriteArrayList<>();int frame;
        public boolean isReady(){return !closed;}public boolean isTerminated(){return closed;}public String error(){return null;}
        public int maxPlayers(){return 2;}public void offerInput(int a,int b){offerInputs(a,b,0,0);}
        public void offerInputs(int a,int b,int c,int d){input.add(new int[]{a,b,c,d});}public void releasePort(int port){}public void clearInput(){}
        public CabinetFrame pollFrame(){if(frame++%5!=0)return null;return new CabinetFrame(2,2,new int[]{-1,-1,-1,-1},1F,0,new short[400]);}
        public void close(){closed=true;}
    }
    private record Fixture(HostedCabinetWorker worker,Fake core,Path staging){}
    private Fixture start(boolean collision,boolean corrupt)throws Exception{
        var id=ResourceLocation.fromNamespaceAndPath("test","hosted_"+UUID.randomUUID().toString().replace("-",""));var core=new Fake();
        ServerCoreRegistry.register(id,new ServerCoreFactory(){public int maxPlayers(){return 2;}public String unavailableReason(ServerCoreContext c){return null;}public ServerCoreHandle open(ServerCoreContext c,Path p){return core;}});
        byte[] data=new byte[16];var entry=new CabinetGameManifest.Entry("fixture.nes",CabinetGameManifest.digest(data),data.length);
        Path objects=Files.createDirectory(temp.resolve("objects"));Files.write(objects.resolve(entry.sha256()+".data"),corrupt?new byte[5]:data);
        Path staging=temp.resolve("running").resolve(UUID.randomUUID().toString());if(collision)Files.createDirectories(staging);
        var worker=new HostedCabinetWorker(UUID.randomUUID(),UUID.randomUUID(),id,new ServerCoreContext(temp,temp.resolve("saves"),UUID.randomUUID(),UUID.randomUUID()),new CabinetGameManifest(id.toString(),List.of(entry)),objects,staging);
        return new Fixture(worker,core,staging);
    }
    private static void until(java.util.function.BooleanSupplier condition)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);while(!condition.getAsBoolean()&&System.nanoTime()<end)Thread.sleep(5);assertTrue(condition.getAsBoolean(),"bounded async completion");}
    @Test void serverWorkerDeliversMediaAndPreservesTapEdgesAndCleansOnlyItsStaging()throws Exception{
        var f=start(false,false);try{until(f.worker::ready);f.worker.inputs(new int[]{1,0,0,0});f.worker.inputs(new int[4]);until(()->f.core.input.size()>=2);
            assertEquals(1,f.core.input.get(0)[0]);assertEquals(0,f.core.input.get(1)[0]);until(()->f.worker.poll()!=null);
        }finally{f.worker.close();until(f.worker::terminated);}assertFalse(Files.exists(f.staging));assertTrue(Files.exists(temp.resolve("objects")));
    }
    @Test void preexistingEmptyDirectoryIsNeverDeleted()throws Exception{var f=start(true,false);until(f.worker::terminated);assertNotNull(f.worker.error());assertTrue(Files.isDirectory(f.staging));assertFalse(f.core.closed);}
    @Test void damagedSharedObjectNeverStartsCoreAndIsPreserved()throws Exception{var f=start(false,true);until(f.worker::terminated);assertNotNull(f.worker.error());assertFalse(f.core.closed);assertFalse(Files.exists(f.staging));try(var files=Files.list(temp.resolve("objects"))){assertEquals(1,files.count());}}
}
