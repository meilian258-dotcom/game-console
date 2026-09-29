// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.pvz.runtime;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicReference;

/** Three owned buffers: producer, latest pending frame, consumer. Never queue old video. */
public final class FrameMailbox {
    private final ArrayBlockingQueue<byte[]> free=new ArrayBlockingQueue<>(3);
    private final AtomicReference<byte[]> pending=new AtomicReference<>();
    public FrameMailbox(int size){for(int i=0;i<3;i++)free.add(new byte[size]);}
    public byte[] acquire(){byte[] frame=free.poll();return frame!=null?frame:pending.getAndSet(null);}
    public void publish(byte[] frame){release(pending.getAndSet(frame));}
    public byte[] poll(){return pending.getAndSet(null);}
    public void release(byte[] frame){if(frame!=null&&!free.offer(frame))throw new IllegalStateException("Frame released twice");}
}
