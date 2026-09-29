package cn.piq.fcarcade.cabinet;

/** Thread-safe bytes awaiting actual transport completion, not merely awaiting application dequeue. */
public final class CabinetSendWindow implements AutoCloseable {
    public static final int MAX_BYTES=196608,MAX_PACKETS=6;
    private int inFlight;
    private boolean closed;
    public synchronized Ticket reserve(int[] packetBytes){
        if(closed||packetBytes==null||packetBytes.length<1||packetBytes.length>MAX_PACKETS)return null;
        long total=0;for(int bytes:packetBytes){if(bytes<1||bytes>MAX_BYTES)return null;total+=bytes;}
        if(total>MAX_BYTES-inFlight)return null;
        inFlight+=(int)total;return new Ticket(packetBytes.clone());
    }
    public synchronized int inFlight(){return inFlight;}
    @Override public synchronized void close(){closed=true;inFlight=0;}
    public final class Ticket {
        private final int[] bytes;
        private final boolean[] completed;
        private Ticket(int[] bytes){this.bytes=bytes;completed=new boolean[bytes.length];}
        /** Success/failure and duplicated callbacks all consume exactly one packet ticket. */
        public void complete(int index){synchronized(CabinetSendWindow.this){
            if(index<0||index>=bytes.length)throw new IllegalArgumentException("Invalid completion index");
            if(completed[index])return;completed[index]=true;if(!closed)inFlight-=bytes[index];
        }}
        public void cancelUnsent(int first){for(int index=first;index<bytes.length;index++)complete(index);}
    }
}
