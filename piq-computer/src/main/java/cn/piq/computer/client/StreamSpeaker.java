package cn.piq.computer.client;
import cn.piq.computer.stream.StreamAudio;
import java.util.concurrent.*;
import javax.sound.sampled.*;

/** At most 200 ms queued. Local output failures do not stop video or remote control. */
final class StreamSpeaker implements AutoCloseable {
    private record Chunk(byte[] bytes,long at){}
    private final ArrayBlockingQueue<Chunk> queue=new ArrayBlockingQueue<>(4);
    private volatile boolean closed;private volatile SourceDataLine line;volatile float volume;volatile String error="";
    StreamSpeaker(){var t=new Thread(this::play,"Computer-Stream-Audio");t.setDaemon(true);t.start();}
    void offer(byte[] pcm){if(closed)return;var c=new Chunk(pcm,System.nanoTime());if(!queue.offer(c)){queue.poll();queue.offer(c);}}
    private void play(){try{var format=new AudioFormat(24000,16,1,true,false);var s=AudioSystem.getSourceDataLine(format);line=s;s.open(format,4800);if(closed)return;s.start();
        while(!closed){var c=queue.poll(100,TimeUnit.MILLISECONDS);if(c==null)continue;if(System.nanoTime()-c.at>200_000_000L){s.flush();continue;}byte[] pcm=StreamAudio.pcm(c.bytes,volume);if(s.available()<pcm.length)s.flush();s.write(pcm,0,pcm.length);}
    }catch(Exception e){if(!closed)error="旁观音频不可用";}finally{var s=line;if(s!=null){s.stop();s.close();}}}
    public void close(){closed=true;queue.clear();var s=line;if(s!=null)s.close();}
}
