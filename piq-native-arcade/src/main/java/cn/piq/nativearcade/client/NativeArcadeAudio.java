// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.nativearcade.client;

import javax.sound.sampled.*;
import java.util.concurrent.*;

/** Bounded PCM sink; failure to open an audio device must not abort emulation. */
final class NativeArcadeAudio implements AutoCloseable {
    private final ArrayBlockingQueue<short[]> queue=new ArrayBlockingQueue<>(4);
    private final Thread worker;
    private volatile boolean running=true;
    private volatile float gain=.6F;
    private volatile SourceDataLine line;
    NativeArcadeAudio(){worker=Thread.ofPlatform().daemon(true).name("PIQ-Native-Audio").start(this::run);}
    void gain(float v){gain=Math.max(0,Math.min(1,v));}
    void offer(short[] samples){if(!running||samples.length==0)return;var copy=samples.clone();if(!queue.offer(copy)){queue.poll();queue.offer(copy);}}
    private void run(){try{
        var format=new AudioFormat(48000,16,2,true,false);line=AudioSystem.getSourceDataLine(format);line.open(format,48000/5*4);line.start();
        while(running){short[] pcm=queue.poll(250,TimeUnit.MILLISECONDS);if(pcm==null)continue;byte[] bytes=new byte[pcm.length*2];float volume=gain;
            for(int i=0;i<pcm.length;i++){short s=(short)Math.round(pcm[i]*volume);bytes[i*2]=(byte)s;bytes[i*2+1]=(byte)(s>>>8);}
            if(running)line.write(bytes,0,bytes.length);
        }
    }catch(Exception ignored){/* Muted if device unavailable. */}finally{running=false;if(line!=null){line.stop();line.close();}}}
    @Override public void close(){running=false;queue.clear();worker.interrupt();var current=line;if(current!=null)current.close();}
}
