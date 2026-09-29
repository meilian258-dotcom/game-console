package cn.piq.fcarcade.server.hosted;

import cn.piq.fcarcade.cabinet.CabinetBackends;
import net.minecraft.resources.ResourceLocation;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/** No client linkage and no implicit hosting permission. Stopping cores retain their slots. */
public final class ServerCoreRegistry {
    private static final Map<ResourceLocation,ServerCoreFactory> FACTORIES=new HashMap<>();
    private static final Map<ResourceLocation,List<ServerCoreHandle>> ACTIVE=new HashMap<>();
    private static final Map<ResourceLocation,Integer> OPENING=new HashMap<>();
    private static long closeGeneration;
    static {register(CabinetBackends.NES,new NesServerCoreFactory());}
    private ServerCoreRegistry(){}
    public static synchronized void register(ResourceLocation id,ServerCoreFactory factory){
        Objects.requireNonNull(id);Objects.requireNonNull(factory);
        if(factory.maxPlayers()<1||factory.maxPlayers()>4||factory.maxConcurrentSessions()<1||factory.maxConcurrentSessions()>32)
            throw new IllegalArgumentException("Hosted core capability bounds");
        ServerCoreFactory old=FACTORIES.get(id);
        if(old!=null){if(old.getClass()==factory.getClass())return;throw new IllegalStateException("Hosted backend already registered: "+id);}
        FACTORIES.put(id,factory);
    }
    public static synchronized ServerCoreFactory find(ResourceLocation id){return FACTORIES.get(id);}
    public static synchronized Set<ResourceLocation> backends(){return Set.copyOf(FACTORIES.keySet());}
    private static void reap(){for(var handles:ACTIVE.values())handles.removeIf(ServerCoreHandle::isTerminated);}
    public static synchronized int activeCount(ResourceLocation id){reap();return ACTIVE.getOrDefault(id,List.of()).size()+OPENING.getOrDefault(id,0);}
    public static synchronized int activeCount(){reap();return ACTIVE.values().stream().mapToInt(List::size).sum()+OPENING.values().stream().mapToInt(Integer::intValue).sum();}
    public static ServerCoreHandle open(ResourceLocation id,ServerCoreContext context,Path rom)throws Exception{
        ServerCoreFactory factory;long generation;
        synchronized(ServerCoreRegistry.class){
            factory=FACTORIES.get(id);if(factory==null)throw new IOException("No server core registered for "+id);
            if(activeCount(id)>=factory.maxConcurrentSessions())throw new IOException("Hosted backend capacity is full or still stopping");
            OPENING.merge(id,1,Integer::sum);
            generation=closeGeneration;
        }
        ServerCoreHandle opened=null;boolean accepted=false;
        try{
            String reason=factory.unavailableReason(context);if(reason!=null)throw new IOException(reason);
            opened=Objects.requireNonNull(factory.open(context,rom),"Hosted factory returned null");
            if(opened.maxPlayers()!=factory.maxPlayers())throw new IOException("Hosted input capacity mismatch");
            synchronized(ServerCoreRegistry.class){
                if(generation!=closeGeneration)throw new IOException("Hosted opening cancelled by server stop");
                ACTIVE.computeIfAbsent(id,key->new ArrayList<>()).add(opened);accepted=true;
            }
            return opened;
        }finally{
            synchronized(ServerCoreRegistry.class){
                OPENING.compute(id,(key,count)->count==null||count<=1?null:count-1);
                if(opened!=null&&!accepted)ACTIVE.computeIfAbsent(id,key->new ArrayList<>()).add(opened);
            }
            // The caller owns a shared admission lease until open returns or throws.
            // Never return a failed opening while its unreturned core is still alive.
            if(opened!=null&&!accepted)awaitFailedClose(opened);
        }
    }
    private static void awaitFailedClose(ServerCoreHandle handle){
        try{handle.close();}catch(Throwable ignored){/* Keep the original opening failure and its admission. */}
        boolean interrupted=false;
        for(;;){
            try{if(handle.isTerminated())break;}catch(Throwable ignored){/* Uncertain termination cannot release capacity. */}
            try{Thread.sleep(50);}catch(InterruptedException ignored){interrupted=true;}
        }
        if(interrupted)Thread.currentThread().interrupt();
    }
    /** Signal only; caller continues to account for activeCount until actual termination. */
    public static synchronized void closeAll(){closeGeneration++;for(var list:ACTIVE.values())for(var core:list)core.close();}
}
