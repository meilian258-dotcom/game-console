package cn.piq.fcarcade.client.cabinet;

import javax.sound.sampled.*;
import java.util.ArrayDeque;
import java.util.concurrent.locks.LockSupport;

/** A failed audio device mutes playback, never the Minecraft game thread. */
final class CabinetAudio implements AutoCloseable {
    @FunctionalInterface interface LineFactory { SourceDataLine create(AudioFormat format) throws Exception; }
    private record Chunk(long generation, short[] pcm) {}
    private final Object state=new Object();
    private final ArrayDeque<Chunk> queue=new ArrayDeque<>(4);
    private final LineFactory factory;
    private final Thread worker;
    private volatile boolean running=true;
    private volatile float gain=.6F;
    private volatile long generation;
    CabinetAudio(){this(AudioSystem::getSourceDataLine);}
    // The device is opened, written, flushed and closed exclusively by this worker.
    CabinetAudio(LineFactory factory){this.factory=factory;worker=Thread.ofPlatform().daemon(true).name("PIQ-Cabinet-Audio").unstarted(this::run);worker.start();}
    void gain(float v){gain=Math.max(0,Math.min(1,v));}
    void offer(short[] pcm){
        long observed=generation;
        if(!running||pcm.length==0||pcm.length>32768||(pcm.length&1)!=0)return;
        var copy=pcm.clone();
        synchronized(state){
            if(!running||observed!=generation)return;
            if(queue.size()==4)queue.removeFirst();
            queue.addLast(new Chunk(observed,copy));
        }
        LockSupport.unpark(worker);
    }
    /** Discard pre-repair audio without waiting for a device write or device flush. */
    void reset(){
        synchronized(state){if(!running)return;generation++;queue.clear();}
        LockSupport.unpark(worker);
    }
    private Chunk poll(long expected){synchronized(state){return generation==expected?queue.pollFirst():null;}}
    private void run(){
        SourceDataLine opened=null;
        try{
            var format=new AudioFormat(48000,16,2,true,false);
            opened=factory.create(format);
            if(!running)return;opened.open(format,38400);if(!running)return;opened.start();
            long applied=0;Chunk chunk=null;byte[] bytes=null;int offset=0;
            while(running){
                long desired=generation;
                if(applied!=desired){
                    chunk=null;bytes=null;offset=0;
                    // This also removes samples from a write that was in progress during reset.
                    // A newer reset during flush is handled again before any subsequent write.
                    opened.stop();opened.flush();if(!running)break;opened.start();applied=desired;
                    continue;
                }
                if(chunk==null){
                    chunk=poll(applied);
                    if(chunk==null){LockSupport.parkNanos(2_000_000L);continue;}
                    if(chunk.generation()!=applied){chunk=null;continue;}
                    var pcm=chunk.pcm();bytes=new byte[pcm.length*2];offset=0;float volume=gain;
                    for(int i=0;i<pcm.length;i++){short s=(short)Math.round(pcm[i]*volume);bytes[i*2]=(byte)s;bytes[i*2+1]=(byte)(s>>>8);}
                }
                if(!running||generation!=applied)continue;
                // At most 10 ms, whole stereo frames, and never more than the device's free space.
                int count=Math.min(Math.min(opened.available(),1920),bytes.length-offset)&~3;
                if(count<=0){LockSupport.parkNanos(2_000_000L);continue;}
                if(generation!=applied)continue;
                int written=opened.write(bytes,offset,count);
                if(written<0||written>count||(written&3)!=0)throw new IllegalStateException("Invalid audio write size");
                offset+=written;
                if(offset==bytes.length){chunk=null;bytes=null;}
                if(written==0)LockSupport.parkNanos(2_000_000L);
            }
        }catch(Exception ignored){/* Audio is optional. */}
        finally{
            synchronized(state){running=false;queue.clear();}
            if(opened!=null){
                try{opened.stop();}catch(Exception ignored){}
                try{opened.flush();}catch(Exception ignored){}
                try{opened.close();}catch(Exception ignored){}
            }
        }
    }
    @Override public void close(){
        synchronized(state){if(!running)return;running=false;generation++;queue.clear();}
        LockSupport.unpark(worker);
    }
}
