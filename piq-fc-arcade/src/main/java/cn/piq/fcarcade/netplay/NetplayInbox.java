package cn.piq.fcarcade.netplay;
import java.util.ArrayDeque;
import java.util.concurrent.TimeUnit;

/** Nonblocking network admission; bounded bytes AND fragment count, with one socket writer. */
final class NetplayInbox {
    static final int MAX_BYTES=2*1024*1024,MAX_FRAGMENTS=4096;
    private final ArrayDeque<byte[]> queue=new ArrayDeque<>();private int bytes;private boolean closed;
    synchronized boolean offer(byte[] data){
        if(closed||data.length==0||data.length>NetplayChunk.LIMIT||queue.size()>=MAX_FRAGMENTS||data.length>MAX_BYTES-bytes)return false;
        queue.addLast(data);bytes+=data.length;notifyAll();return true;
    }
    synchronized byte[] poll(long millis)throws InterruptedException{
        long end=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(millis);
        while(queue.isEmpty()&&!closed){long left=end-System.nanoTime();if(left<=0)return null;TimeUnit.NANOSECONDS.timedWait(this,left);}
        var next=queue.pollFirst();if(next!=null)bytes-=next.length;return next;
    }
    synchronized int size(){return queue.size();}
    synchronized int bytes(){return bytes;}
    synchronized void close(){closed=true;queue.clear();bytes=0;notifyAll();}
}
