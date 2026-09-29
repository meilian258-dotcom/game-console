package cn.piq.fcarcade.server.hosted;

import cn.piq.fcarcade.cabinet.CabinetFrame;
import cn.piq.fcarcade.session.ZapperInput;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.locks.LockSupport;

/** Per-port bounded input FIFOs, latest picture and 341ms PCM tail. Core calls stay on its owner. */
public final class ServerCoreWorker implements ServerCoreHandle {
    public interface Core extends AutoCloseable {
        double targetFps();
        CabinetFrame runFrame(int p1,int p2,int p3,int p4,int zapper)throws Exception;
        default void reset()throws Exception{throw new UnsupportedOperationException("Reset unavailable");}
        default void persist()throws Exception{}
        @Override void close();
    }
    @FunctionalInterface public interface Opener {Core open()throws Exception;}
    private final int players;private final boolean zapper,reset;
    /** Caller holds lock. Unrelated controllers consume their next edge in the same frame. */
    static final class InputState {
        private final List<ArrayDeque<Integer>> pending=new java.util.ArrayList<>(5);
        private final int[] held=new int[5],offered=new int[5];
        InputState(){for(int p=0;p<5;p++)pending.add(new ArrayDeque<>());clear();}
        boolean offer(int[] next){
            for(int p=0;p<next.length;p++)if(next[p]!=offered[p]&&pending.get(p).size()>=128)return false;
            for(int p=0;p<next.length;p++)offerPort(p,next[p]);
            return true;
        }
        boolean offerPort(int port,int value){
            if(value==offered[port])return true;
            if(pending.get(port).size()>=128)return false;
            offered[port]=value;pending.get(port).addLast(value);return true;
        }
        int[] sample(){for(int p=0;p<5;p++)if(!pending.get(p).isEmpty())held[p]=pending.get(p).removeFirst();return held.clone();}
        void release(int port){pending.get(port).clear();held[port]=offered[port]=port==4?ZapperInput.NEUTRAL:0;}
        void clear(){for(int p=0;p<5;p++)release(p);}
    }
    private final Object lock=new Object();private final InputState inputs=new InputState();
    private final short[] pcm=new short[32768];private int pcmHead,pcmSize;
    private CabinetFrame picture;private boolean resetPending;
    private volatile boolean closed,ready,terminated;private volatile String error;
    private final Thread owner;
    public ServerCoreWorker(int players,boolean zapper,boolean reset,Opener opener){
        if(players<1||players>4)throw new IllegalArgumentException("Player capacity");
        this.players=players;this.zapper=zapper;this.reset=reset;
        owner=Thread.ofPlatform().daemon(true).name("PIQ hosted core owner").unstarted(()->run(opener));owner.start();
    }
    @Override public int maxPlayers(){return players;}
    @Override public boolean isReady(){return ready&&!closed;}
    @Override public String error(){return error;}
    @Override public boolean isTerminated(){return terminated;}
    @Override public boolean supportsZapper(){return zapper;}
    @Override public boolean supportsReset(){return reset;}
    @Override public void offerInput(int a,int b){offerInputs(a,b,0,0);}
    @Override public void offerInputs(int a,int b,int c,int d){
        int[] next={a,b,c,d};for(int p=0;p<4;p++)if((next[p]&~4095)!=0||(p>=players&&next[p]!=0))throw new IllegalArgumentException("Hosted controller input bounds");
        synchronized(lock){if(!closed&&!inputs.offer(next))fail("Hosted input queue overflow");}
    }
    @Override public void offerZapper(int packed){
        if(!zapper)throw new UnsupportedOperationException("Light gun unavailable");ZapperInput.validate(packed);
        synchronized(lock){if(!closed&&!inputs.offerPort(4,packed))fail("Hosted input queue overflow");}
    }
    @Override public void offerFrameInput(int a,int b,int c,int d,int packed){
        int[] next={a,b,c,d,ZapperInput.validate(packed)};
        for(int p=0;p<4;p++)if((next[p]&~4095)!=0||(p>=players&&next[p]!=0))throw new IllegalArgumentException("Hosted controller input bounds");
        if(!zapper&&packed!=ZapperInput.NEUTRAL)throw new UnsupportedOperationException("Light gun unavailable");
        synchronized(lock){if(!closed&&!inputs.offer(next))fail("Hosted input queue overflow");}
    }
    @Override public void releasePort(int port){
        if(port<0||port>=players)throw new IllegalArgumentException("Controller port");
        synchronized(lock){inputs.release(port);if(port==1&&zapper)inputs.release(4);}
    }
    @Override public void clearInput(){synchronized(lock){inputs.clear();}}
    @Override public void reset(){if(!reset)throw new UnsupportedOperationException("Reset unavailable");synchronized(lock){if(closed)return;clearInput();resetPending=true;picture=null;pcmHead=pcmSize=0;}}
    @Override public CabinetFrame pollFrame(){synchronized(lock){if(closed||picture==null)return null;short[] out=new short[pcmSize];for(int i=0;i<out.length;i++)out[i]=pcm[(pcmHead+i)%pcm.length];var frame=picture;picture=null;pcmHead=pcmSize=0;return new CabinetFrame(frame.width(),frame.height(),frame.abgr(),frame.displayAspect(),frame.rotation(),out);}}
    @Override public void close(){closed=true;ready=false;clearInput();synchronized(lock){picture=null;pcmHead=pcmSize=0;}LockSupport.unpark(owner);}
    private void fail(String reason){if(error==null)error=reason;close();}
    private void publish(CabinetFrame frame){synchronized(lock){if(closed||resetPending)return;picture=frame;for(int i=0;i<frame.pcm48k().length;i+=2){if(pcmSize==pcm.length){pcmHead=(pcmHead+2)%pcm.length;pcmSize-=2;}pcm[(pcmHead+pcmSize++)%pcm.length]=frame.pcm48k()[i];pcm[(pcmHead+pcmSize++)%pcm.length]=frame.pcm48k()[i+1];}}}
    private void run(Opener opener){Core core=null;boolean initialized=false;
        try{
            if(closed)return;core=opener.open();if(core==null)throw new IllegalStateException("Missing hosted core");
            double fps=core.targetFps();if(!Double.isFinite(fps)||fps<49||fps>65)throw new IllegalStateException("Hosted frame rate bounds");
            initialized=true;if(closed)return;ready=true;long period=(long)(1_000_000_000D/fps),due=System.nanoTime();int count=0;
            while(!closed){
                boolean doReset;int[] input;
                synchronized(lock){doReset=resetPending;resetPending=false;input=inputs.sample();}
                if(doReset)core.reset();if(closed)break;
                publish(core.runFrame(input[0],input[1],input[2],input[3],input[4]));
                if(++count%300==0)core.persist();
                due+=period;long wait=due-System.nanoTime();if(wait>0)LockSupport.parkNanos(wait);else if(wait<-period*3)due=System.nanoTime();
            }
        }catch(Throwable failure){if(!closed||error==null)error="Hosted core: "+failure.getClass().getSimpleName()+": "+failure.getMessage();}
        finally{
            closed=true;ready=false;
            if(core!=null){try{if(initialized&&error==null)core.persist();}catch(Throwable failure){if(error==null)error="Hosted final save: "+failure.getMessage();}finally{try{core.close();}catch(Throwable failure){if(error==null)error="Hosted close: "+failure.getMessage();}}}
            synchronized(lock){inputs.clear();picture=null;pcmHead=pcmSize=0;}terminated=true;
        }
    }
}
