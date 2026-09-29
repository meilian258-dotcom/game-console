package cn.piq.fcarcade.client.cabinet;

import java.util.ArrayDeque;
import java.util.Arrays;

/** Bounded stereo FIFO with an absolute sample-frame clock, separate from droppable video. */
public final class CabinetPcmBuffer {
    private static final int LIMIT=28800; // At most 300 ms at 48 kHz stereo.
    public record Chunk(long firstSample,short[] samples) {}
    private final ArrayDeque<Chunk> queue=new ArrayDeque<>();
    private long cursor;
    private int count,offset;
    public synchronized void offer(short[] samples){
        if(samples==null||(samples.length&1)!=0||samples.length>32768)throw new IllegalArgumentException("Invalid stereo PCM");
        long start=cursor;cursor+=samples.length/2;
        if(samples.length==0)return;
        int skip=Math.max(0,samples.length-LIMIT);
        while(!queue.isEmpty()&&count+samples.length-skip>LIMIT){count-=queue.removeFirst().samples().length-offset;offset=0;}
        short[] copy=Arrays.copyOfRange(samples,skip,samples.length);
        queue.addLast(new Chunk(start+skip/2,copy));count+=copy.length;
    }
    public synchronized Chunk poll(int maxShorts){
        if(maxShorts<2||(maxShorts&1)!=0||maxShorts>9600)throw new IllegalArgumentException("PCM block size");
        if(queue.isEmpty())return null;
        long start=queue.peekFirst().firstSample()+offset/2;
        short[] out=new short[Math.min(maxShorts,count)];int used=0;
        while(!queue.isEmpty()&&used<out.length){
            Chunk head=queue.peekFirst();
            if(head.firstSample()+offset/2!=start+used/2)break;
            int n=Math.min(out.length-used,head.samples().length-offset);
            System.arraycopy(head.samples(),offset,out,used,n);used+=n;offset+=n;count-=n;
            if(offset==head.samples().length){queue.removeFirst();offset=0;}
        }
        return new Chunk(start,used==out.length?out:Arrays.copyOf(out,used));
    }
    public synchronized void clear(){queue.clear();count=offset=0;}
    public synchronized int bufferedShorts(){return count;}
}
