package cn.piq.fcarcade.server.hosted;

import java.util.ArrayDeque;
import java.util.Objects;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

/** Bounded encoder-to-server mailbox. Stale video is dropped, never replayed after a slow tick. */
public final class HostedMediaQueue<T> {
    public static final long MAX_VIDEO_AGE_NANOS=200_000_000L;
    private record Entry<T>(T value,boolean video,long time) {}
    private final int capacity;
    private final Predicate<T> isVideo;
    private final LongSupplier clock;
    private final ArrayDeque<Entry<T>> queue=new ArrayDeque<>();
    public HostedMediaQueue(int capacity,Predicate<T> isVideo) { this(capacity,isVideo,System::nanoTime); }
    public HostedMediaQueue(int capacity,Predicate<T> isVideo,LongSupplier clock) {
        if(capacity<2||capacity>32)throw new IllegalArgumentException("Media mailbox capacity");
        this.capacity=capacity;this.isVideo=Objects.requireNonNull(isVideo);this.clock=Objects.requireNonNull(clock);
    }
    public synchronized boolean videoRoom() {
        prune(clock.getAsLong());return queue.size()<capacity-1||queue.stream().anyMatch(Entry::video);
    }
    public synchronized boolean offer(T value) {
        Objects.requireNonNull(value);long now=clock.getAsLong();prune(now);boolean video=isVideo.test(value);
        if(queue.size()>=capacity) {
            // A fresh video may supersede old video; audio gets that same headroom first.
            var iterator=queue.iterator();boolean removed=false;
            while(iterator.hasNext())if(iterator.next().video()){iterator.remove();removed=true;break;}
            if(!removed) {
                if(video)return false;
                queue.removeFirst(); // Bounded audio discontinuity is safer than growing latency.
            }
        }
        queue.addLast(new Entry<>(value,video,now));return true;
    }
    public synchronized T poll() { prune(clock.getAsLong());var first=queue.pollFirst();return first==null?null:first.value(); }
    public synchronized int size() { return queue.size(); }
    public synchronized void clear() { queue.clear(); }
    private void prune(long now) { queue.removeIf(e->e.video()&&now-e.time()>MAX_VIDEO_AGE_NANOS); }
}
