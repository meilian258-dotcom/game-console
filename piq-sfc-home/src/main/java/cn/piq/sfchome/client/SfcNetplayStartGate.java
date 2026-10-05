package cn.piq.sfchome.client;

import cn.piq.sfchome.net.SfcHomeNetwork;
import java.util.Objects;

/** One runtime grant: local preparation is not permission to present or run a host. */
final class SfcNetplayStartGate {
    private final SfcHomeNetwork.NetplayStart grant;
    private boolean prepared,active,closed;

    SfcNetplayStartGate(SfcHomeNetwork.NetplayStart grant){
        this.grant=Objects.requireNonNull(grant);
        // Peers join an already running room; only the new host waits for launch authority.
        active=!grant.session().executionHost();
    }
    synchronized boolean prepared(){if(closed||prepared)return false;prepared=true;return true;}
    synchronized boolean active(){return active&&!closed;}
    synchronized boolean activate(SfcHomeNetwork.NetplayActivated message,Runnable activate){
        var session=grant.session();
        if(closed||!prepared||!session.executionHost()||message.sessionId()!=session.sessionId()
                ||message.epoch()!=session.epoch()||message.wire()!=grant.wire()||!message.ticket().equals(grant.ticket()))return false;
        if(active)return true;
        activate.run();active=true;return true;
    }
    synchronized void close(){closed=true;active=false;}
}
