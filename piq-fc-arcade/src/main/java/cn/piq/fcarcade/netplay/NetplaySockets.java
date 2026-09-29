package cn.piq.fcarcade.netplay;

import java.io.Closeable;
import java.io.IOException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** Own sockets from construction, including authentication/connect in progress.
 * Retirement cannot miss a socket that finishes opening after cancellation.
 */
final class NetplaySockets implements AutoCloseable {
    private final Set<Closeable> owned=Collections.newSetFromMap(new IdentityHashMap<>());
    private boolean closed;

    synchronized <T extends Closeable> T own(T socket)throws IOException {
        if(closed){socket.close();throw new IOException("Netplay 已结束");}
        owned.add(socket);return socket;
    }
    synchronized void release(Closeable socket){
        if(socket!=null&&owned.remove(socket))quietClose(socket);
    }
    @Override public synchronized void close(){
        if(closed)return;closed=true;
        for(var socket:owned)quietClose(socket);
        owned.clear();
    }
    private static void quietClose(Closeable value){try{value.close();}catch(IOException ignored){}}
}
