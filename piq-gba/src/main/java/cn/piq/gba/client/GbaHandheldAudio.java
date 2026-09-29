// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.gba.client;

import javax.sound.sampled.*;
import java.util.concurrent.*;

/** Optional 48 kHz stereo output. All audio-device calls belong to this daemon, never Minecraft. */
final class GbaHandheldAudio implements AutoCloseable {
    private record Chunk(long generation,short[] samples){}
    private final ArrayBlockingQueue<Chunk> queue=new ArrayBlockingQueue<>(3);
    private final Thread worker;
    private volatile boolean running=true;
    private volatile float gain;
    private volatile long generation;
    GbaHandheldAudio(){worker=Thread.ofPlatform().daemon(true).name("PIQ-GBA-Handheld-Audio").start(this::run);}
    void gain(float value){gain=Float.isFinite(value)?Math.max(0,Math.min(1,value)):0;}
    void silence(){gain=0;generation++;queue.clear();worker.interrupt();}
    void offer(short[] pcm){
        if(!running||gain<=0||pcm==null||pcm.length>32768||(pcm.length&1)!=0)return;
        long token=generation;
        for(int offset=0;offset<pcm.length;offset+=4800){
            var chunk=new Chunk(token,java.util.Arrays.copyOfRange(pcm,offset,Math.min(pcm.length,offset+4800)));
            if(!queue.offer(chunk)){queue.poll();queue.offer(chunk);}
        }
    }
    private void run(){SourceDataLine line=null;try{
        var format=new AudioFormat(48000,16,2,true,false);
        line=AudioSystem.getSourceDataLine(format);if(!running)return;
        line.open(format,19200);if(!running)return;line.start();long observed=generation;
        while(running){
            if(observed!=generation){line.flush();observed=generation;}
            Chunk chunk;try{chunk=queue.poll(20,TimeUnit.MILLISECONDS);}catch(InterruptedException wake){continue;}
            if(chunk==null||chunk.generation()!=generation||gain<=0)continue;
            byte[] data=new byte[chunk.samples().length*2];float volume=gain;
            for(int i=0;i<chunk.samples().length;i++){short s=(short)Math.round(chunk.samples()[i]*volume);data[i*2]=(byte)s;data[i*2+1]=(byte)(s>>>8);}
            int offset=0;
            while(running&&chunk.generation()==generation&&gain>0&&offset<data.length){
                int count=Math.min(data.length-offset,line.available())&~3;
                if(count>0)offset+=line.write(data,offset,count);
                else try{Thread.sleep(2);}catch(InterruptedException wake){/* Recheck cancellation/generation. */}
            }
        }
    }catch(Exception ignored){/* Muting never stops a game or the Minecraft thread. */}
    finally{running=false;queue.clear();if(line!=null){line.stop();line.flush();line.close();}}}
    @Override public void close(){running=false;silence();}
}
